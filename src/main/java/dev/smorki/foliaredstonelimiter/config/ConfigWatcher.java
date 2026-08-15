package dev.smorki.foliaredstonelimiter.config;

import dev.smorki.foliaredstonelimiter.FoliaRedstoneLimiter;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Watches {@code config.yml} for on-disk changes and reloads it automatically.
 *
 * <h3>Why polling instead of WatchService?</h3>
 * <p>{@link java.nio.file.WatchService} needs a thread permanently blocked on
 * {@code take()}, and many editors save via write-temp-then-atomic-rename,
 * which a naive file-level watcher misses entirely. Polling the file's
 * last-modified timestamp on Folia's async scheduler is:
 * <ul>
 *   <li>cheap — a single {@code stat()} syscall per interval,</li>
 *   <li>robust — atomic replace-saves simply yield a new mtime,</li>
 *   <li>thread-clean — no extra threads; the task is cancelled on disable.</li>
 * </ul>
 *
 * <h3>Settle window (debounce)</h3>
 * <p>A change is only applied after the file's mtime stays identical across
 * two consecutive polls, guaranteeing the writer has finished before we read
 * a single byte. Worst-case detection latency: ~2 × {@link #POLL_INTERVAL_SECONDS}.
 */
public class ConfigWatcher {

    /** Polling cadence for the file mtime check. */
    private static final long POLL_INTERVAL_SECONDS = 2L;

    private final FoliaRedstoneLimiter plugin;
    private final Logger log;
    private final Path configPath;

    private ScheduledTask task;

    /** mtime applied by the most recent successful load. */
    private long lastKnownModified;

    /**
     * mtime observed on the previous poll when it differed from
     * {@link #lastKnownModified}. Only applied once it survives a full
     * interval unchanged (settle window). Accessed solely by the single,
     * non-overlapping scheduled task — no atomics needed.
     */
    private long pendingModified = -1L;

    public ConfigWatcher(FoliaRedstoneLimiter plugin) {
        this.plugin     = plugin;
        this.log        = plugin.getFRLLogger();
        this.configPath = plugin.getDataFolder().toPath().resolve("config.yml");
    }

    /**
     * Starts polling. Call from {@code onEnable()} after the data folder
     * exists (i.e. after {@code saveDefaultConfig()}).
     */
    public void start() {
        // Baseline: the file as it exists at startup must NOT be treated as a "change".
        lastKnownModified = currentModified();

        task = Bukkit.getAsyncScheduler().runAtFixedRate(
                plugin,
                scheduledTask -> poll(),
                POLL_INTERVAL_SECONDS,  // initial delay
                POLL_INTERVAL_SECONDS,  // period
                TimeUnit.SECONDS);
    }

    /** Cancels the polling task. Call from {@code onDisable()}. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ── Polling ──────────────────────────────────────────────────────────

    private void poll() {
        // Honour the toggle. Note the self-disabling flow works: an edit that
        // sets auto-reload=false is still detected (flag was true), the reload
        // applies it, and polling stops from the next interval onward.
        if (!plugin.getConfigManager().isAutoReloadEnabled()) return;

        long mtime = currentModified();
        if (mtime < 0) return; // file missing or unreadable right now

        if (mtime == lastKnownModified) {
            pendingModified = -1L;
            return;
        }
        if (mtime != pendingModified) {
            // File just changed (or is still being written) — wait for it to settle.
            pendingModified = mtime;
            return;
        }

        // mtime stable across two consecutive polls → the writer finished. Apply.
        pendingModified   = -1L;
        lastKnownModified = mtime;
        plugin.getConfigManager().reload(); // already on the async scheduler — I/O is safe here
        log.info("[FRL] config.yml change detected — configuration reloaded automatically.");
    }

    /** Returns the file's mtime in millis, or {@code -1} if missing/unreadable. */
    private long currentModified() {
        try {
            if (!Files.exists(configPath)) return -1L;
            return Files.getLastModifiedTime(configPath).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }
}
