package dev.smorki.foliaredstonelimiter.scheduler;

import dev.smorki.foliaredstonelimiter.FoliaRedstoneLimiter;
import dev.smorki.foliaredstonelimiter.util.ChunkKey;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-chunk redstone update counts and schedules per-tick resets.
 *
 * <h3>LongAdder vs AtomicLong</h3>
 * <p>{@link java.util.concurrent.atomic.LongAdder} is preferred over
 * {@link java.util.concurrent.atomic.AtomicLong} for counters under high
 * contention: it maintains a set of cells that are summed on {@code sum()},
 * dramatically reducing CAS failures when many threads increment the same
 * counter. In Folia each chunk is owned by a single region thread, so
 * contention here is typically zero — but LongAdder is still the idiomatic
 * choice for counters that are incremented far more often than read.
 *
 * <h3>Tick-reset strategy</h3>
 * <p>Each chunk's counter is reset by a {@code RegionScheduler.runAtFixedRate}
 * task that runs every 1 tick inside the chunk's own region. This means:
 * <ul>
 *   <li>The reset runs on exactly the same thread that increments the counter,
 *       eliminating all write-write races for the reset operation.</li>
 *   <li>Different chunks reset independently — a slow region does not delay
 *       resets in other regions.</li>
 * </ul>
 */
public class TickCounterManager {

    /** Keeps a live counter for every chunk that has seen at least one redstone event. */
    private final ConcurrentHashMap<ChunkKey, java.util.concurrent.atomic.LongAdder> counters =
            new ConcurrentHashMap<>();

    /**
     * Increments the counter for the given chunk and returns the new total.
     *
     * <p>Called from the event listener on the chunk's region thread.
     * {@link java.util.concurrent.atomic.LongAdder#increment()} is always
     * wait-free in the uncontended (single-thread-per-chunk) case.
     */
    public long increment(ChunkKey key) {
        // computeIfAbsent is atomic: only one LongAdder is ever created per key.
        var adder = counters.computeIfAbsent(key, k -> new java.util.concurrent.atomic.LongAdder());
        adder.increment();
        return adder.sum();
    }

    /**
     * Resets the counter for a chunk to zero.
     *
     * <p>Called from the per-chunk region-scheduled tick reset task.
     * {@link java.util.concurrent.atomic.LongAdder#reset()} is safe here because
     * the reset and the increment always run on the same region thread.
     */
    public void reset(ChunkKey key) {
        var adder = counters.get(key);
        if (adder != null) adder.reset();
    }

    /**
     * Registers a per-tick reset task for a chunk the first time it generates
     * a redstone event.
     *
     * <p>Uses {@code RegionScheduler.runAtFixedRate} (period = 1 tick) so the
     * reset always fires on the chunk's own region thread — no cross-region
     * synchronisation required.
     *
     * @param plugin  plugin instance required by the Folia scheduler API
     * @param world   chunk's world
     * @param chunkX  chunk X
     * @param chunkZ  chunk Z
     * @param key     pre-built key for this chunk
     */
    public void ensureResetTask(FoliaRedstoneLimiter plugin, World world, int chunkX, int chunkZ, ChunkKey key) {
        // Only register once — tracked via a separate flag set.
        if (resetTaskRegistered.contains(key)) return;
        if (!resetTaskRegistered.add(key)) return; // double-check after add (CAS equivalent)

        /*
         * runAtFixedRate(plugin, world, chunkX, chunkZ, initialDelayTicks, periodTicks, task)
         *
         * Period = 1 tick: resets the counter every tick, providing a fresh
         * sliding window. The initial delay is also 1 tick so the first event
         * in a tick is not immediately wiped before the listener can act on it.
         */
        Bukkit.getRegionScheduler().runAtFixedRate(
                plugin,
                world,
                chunkX,
                chunkZ,
                scheduledTask -> reset(key),
                1L,  // initial delay
                1L   // period
        );
    }

    private final java.util.Set<ChunkKey> resetTaskRegistered = ConcurrentHashMap.newKeySet();

    /**
     * Removes all counter and task-tracking state.
     * Called on disable / reload; subsequent events re-register fresh tasks.
     */
    public void clearAll() {
        counters.clear();
        resetTaskRegistered.clear();
    }
}
