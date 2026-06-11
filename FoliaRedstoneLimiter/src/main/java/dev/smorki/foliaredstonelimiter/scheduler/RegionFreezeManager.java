package dev.smorki.foliaredstonelimiter.scheduler;

import dev.smorki.foliaredstonelimiter.FoliaRedstoneLimiter;
import dev.smorki.foliaredstonelimiter.util.ChunkKey;
import dev.smorki.foliaredstonelimiter.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages the lifecycle of chunk-level redstone freezes.
 *
 * <h3>Why ConcurrentHashMap?</h3>
 * <p>Folia assigns each region its own thread. Multiple regions can call
 * {@link #freeze} or {@link #isFrozen} at the same time, so a plain
 * {@link java.util.HashSet} protected by {@code synchronized} would create
 * unnecessary contention. {@link ConcurrentHashMap#newKeySet()} is a
 * lock-striped, wait-free structure that handles concurrent reads/writes
 * with minimal overhead.
 *
 * <h3>Why RegionScheduler for unfreeze?</h3>
 * <p>When the freeze timer expires we must remove the chunk from the frozen
 * set. That removal is a write operation that could race with the event
 * listener's {@link #isFrozen} check. Since both sides run on the same
 * region thread (the region that owns this chunk), {@code RegionScheduler}
 * serialises the unfreeze naturally — no explicit locking needed.
 */
public class RegionFreezeManager {

    private final FoliaRedstoneLimiter plugin;
    private final Logger log;

    /**
     * Set of currently-frozen chunks.
     * ConcurrentHashMap.newKeySet() → thread-safe, lock-free reads.
     */
    private final Set<ChunkKey> frozenChunks = ConcurrentHashMap.newKeySet();

    public RegionFreezeManager(FoliaRedstoneLimiter plugin) {
        this.plugin = plugin;
        this.log    = plugin.getFRLLogger();
    }

    /**
     * Freezes the given chunk for the configured duration.
     *
     * <p>This method is always called from the chunk's own region thread
     * (via the {@link dev.smorki.foliaredstonelimiter.listener.RedstoneListener}),
     * so the add + scheduler dispatch is effectively single-threaded for this
     * specific chunk. The {@code add} is still lock-free for correctness with
     * other region threads reading via {@link #isFrozen}.
     *
     * @param world  the world that owns the chunk
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @param count  redstone update count that triggered the freeze
     */
    public void freeze(World world, int chunkX, int chunkZ, long count) {
        ChunkKey key = ChunkKey.of(world.getName(), chunkX, chunkZ);

        // Idempotency: do nothing if already frozen (prevents timer stacking).
        if (!frozenChunks.add(key)) return;

        int limit   = plugin.getConfigManager().getMaxUpdatesPerTick();
        int seconds = plugin.getConfigManager().getFreezeDurationSeconds();

        // Notify all online operators via the async-safe broadcast helper.
        MessageUtil.broadcastOps(
                plugin.getConfigManager().getMsgFreezeAlert(),
                MessageUtil.freezePlaceholders(world.getName(), chunkX, chunkZ, count, limit, seconds)
        );

        log.warning("[FRL] Chunk [" + chunkX + ", " + chunkZ + "] in world '"
                + world.getName() + "' frozen for " + seconds + "s ("
                + count + " updates/tick, limit=" + limit + ").");

        /*
         * Schedule the unfreeze on THIS chunk's region thread.
         *
         * RegionScheduler.runDelayed() guarantees the lambda executes
         * inside the same region that owns (world, chunkX, chunkZ).
         * Using delayTicks = seconds * 20L converts seconds → game ticks.
         *
         * BukkitRunnable is explicitly avoided because it schedules on the
         * legacy main thread, which does not exist as a single entity in Folia.
         */
        long delayTicks = seconds * 20L;
        Bukkit.getRegionScheduler().runDelayed(
                plugin,
                world,
                chunkX,
                chunkZ,
                scheduledTask -> unfreeze(key),   // runs on the chunk's region thread
                delayTicks
        );
    }

    /**
     * Removes the freeze on the given chunk.
     *
     * <p>Called exclusively from the region-scheduled lambda above, so this
     * write and any concurrent {@link #isFrozen} reads are naturally serialised
     * for this specific chunk.
     */
    private void unfreeze(ChunkKey key) {
        frozenChunks.remove(key);
        log.info("[FRL] Chunk [" + key.chunkX() + ", " + key.chunkZ()
                + "] in world '" + key.world() + "' has been unfrozen.");
    }

    /**
     * Returns {@code true} if the given chunk is currently serving a freeze penalty.
     *
     * <p>Called at the redstone event hot-path (every redstone update), so this
     * must be as cheap as possible. ConcurrentHashMap.contains() is O(1) and
     * never blocks readers — ideal for the high-frequency event listener.
     */
    public boolean isFrozen(ChunkKey key) {
        return frozenChunks.contains(key);
    }

    /**
     * Clears all freeze state on plugin disable / reload.
     * Should only be called from the async scheduler context.
     */
    public void clearAll() {
        frozenChunks.clear();
        log.info("[FRL] All freeze records cleared.");
    }
}
