package dev.smorki.foliaredstonelimiter.listener;

import dev.smorki.foliaredstonelimiter.FoliaRedstoneLimiter;
import dev.smorki.foliaredstonelimiter.scheduler.RegionFreezeManager;
import dev.smorki.foliaredstonelimiter.scheduler.TickCounterManager;
import dev.smorki.foliaredstonelimiter.util.ChunkKey;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;

/**
 * Intercepts every redstone state-change event and applies the rate limiter.
 *
 * <h3>Thread context</h3>
 * <p>In Folia, {@link BlockRedstoneEvent} fires on the region thread that owns
 * the block's chunk. This is crucial: we can safely read and mutate per-chunk
 * state here without additional locking, because all events for the same chunk
 * always arrive on the same thread.
 *
 * <h3>Event priority: LOWEST</h3>
 * <p>We cancel the event early (LOWEST priority) so downstream listeners
 * never process the update. If we used HIGH or MONITOR, other plugins would
 * already have acted on the redstone change before we could cancel it —
 * wasting CPU and potentially producing side-effects (pistons half-extended, etc.).
 */
public class RedstoneListener implements Listener {

    private final FoliaRedstoneLimiter plugin;
    private final RegionFreezeManager  freezeManager;
    private final TickCounterManager   counterManager;

    public RedstoneListener(FoliaRedstoneLimiter plugin,
                            RegionFreezeManager  freezeManager,
                            TickCounterManager   counterManager) {
        this.plugin         = plugin;
        this.freezeManager  = freezeManager;
        this.counterManager = counterManager;
    }

    /**
     * Main hot-path: called for every single redstone update on the server.
     * Keep this method lean — every nanosecond counts.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRedstoneChange(BlockRedstoneEvent event) {
        World world = event.getBlock().getWorld();

        // ── Bypass check ───────────────────────────────────────────────────
        // List lookup on an immutable snapshot — no lock needed.
        if (plugin.getConfigManager().getBypassWorlds().contains(world.getName())) return;

        // ── Chunk coordinates ──────────────────────────────────────────────
        Chunk chunk  = event.getBlock().getChunk();
        int   chunkX = chunk.getX();
        int   chunkZ = chunk.getZ();
        ChunkKey key = ChunkKey.of(world.getName(), chunkX, chunkZ);

        // ── Frozen? Cancel immediately ─────────────────────────────────────
        // isFrozen() is a lock-free ConcurrentHashMap.contains() — O(1).
        if (freezeManager.isFrozen(key)) {
            event.setNewCurrent(event.getOldCurrent()); // suppress state change
            return;
        }

        // ── Ensure the per-tick counter reset task exists for this chunk ───
        // No-op after the first call; safe to call every event (cheap flag check).
        counterManager.ensureResetTask(plugin, world, chunkX, chunkZ, key);

        // ── Increment and check ────────────────────────────────────────────
        long count = counterManager.increment(key);
        int  limit = plugin.getConfigManager().getMaxUpdatesPerTick();

        if (count >= limit) {
            // Suppress this event immediately before delegating to the freeze manager.
            event.setNewCurrent(event.getOldCurrent());

            // freeze() is idempotent — safe to call multiple times per tick
            // (extra calls return early without scheduling another timer).
            freezeManager.freeze(world, chunkX, chunkZ, count);
        }
        // If under limit, allow the event to propagate normally.
    }
}
