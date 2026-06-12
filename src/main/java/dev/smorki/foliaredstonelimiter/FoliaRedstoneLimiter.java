package dev.smorki.foliaredstonelimiter;

import dev.smorki.foliaredstonelimiter.command.FRLCommand;
import dev.smorki.foliaredstonelimiter.config.ConfigManager;
import dev.smorki.foliaredstonelimiter.listener.RedstoneListener;
import dev.smorki.foliaredstonelimiter.scheduler.RegionFreezeManager;
import dev.smorki.foliaredstonelimiter.scheduler.TickCounterManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

/**
 * Entry point for FoliaRedstoneLimiter.
 *
 * <h2>Architecture overview</h2>
 * <pre>
 *  ┌─────────────────────────────────────────────────────────────┐
 *  │                    FoliaRedstoneLimiter                     │
 *  │                                                             │
 *  │  ConfigManager ←── AsyncScheduler (disk I/O off hot-path)  │
 *  │       ↓                                                     │
 *  │  RedstoneListener (runs on each chunk's region thread)      │
 *  │       ↓ count >= limit                                      │
 *  │  RegionFreezeManager.freeze()                               │
 *  │       ↓ runDelayed on RegionScheduler                       │
 *  │  unfreeze() — fires on same region thread N seconds later   │
 *  └─────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Why no GlobalRegionScheduler?</h3>
 * <p>The legacy Bukkit scheduler (and Folia's {@code GlobalRegionScheduler})
 * run tasks on a single global tick loop that cannot safely access chunk data
 * across regions. All timing tasks here are pinned to individual chunks via
 * {@code RegionScheduler}, ensuring region-local thread safety without locks.
 */
public final class FoliaRedstoneLimiter extends JavaPlugin {

    private static final String TARGET_FOLIA_VERSION = "1.21.11";

    private ConfigManager     configManager;
    private RegionFreezeManager freezeManager;
    private TickCounterManager  counterManager;

    /** Shared logger with [FRL] prefix for every console line. */
    private Logger frlLogger;

    @Override
    public void onEnable() {
        frlLogger = getLogger(); // Bukkit.getLogger() alternative; uses plugin name prefix

        // ── 1. Save default config (copies config.yml from jar if absent) ──
        saveDefaultConfig();

        // ── 2. Initialise managers ─────────────────────────────────────────
        configManager  = new ConfigManager(this);
        freezeManager  = new RegionFreezeManager(this);
        counterManager = new TickCounterManager();

        // ── 3. Load config asynchronously (first load) ────────────────────
        //    getAsyncScheduler().runNow() returns immediately; the lambda
        //    executes on a pooled async thread — I/O never touches a region thread.
        Bukkit.getAsyncScheduler().runNow(this, task -> configManager.reload());

        // ── 4. Register event listener ────────────────────────────────────
        getServer().getPluginManager().registerEvents(
                new RedstoneListener(this, freezeManager, counterManager), this);

        // ── 5. Register command ───────────────────────────────────────────
        FRLCommand frlCommand = new FRLCommand(this);
        var cmd = getCommand("frl");
        if (cmd != null) {
            cmd.setExecutor(frlCommand);
            cmd.setTabCompleter(frlCommand);
        }

        // ── 6. Startup banner ─────────────────────────────────────────────
        logBanner("ENABLED");
    }

    @Override
    public void onDisable() {
        // Clear in-memory state so a /reload doesn't carry stale frozen chunks.
        if (freezeManager  != null) freezeManager.clearAll();
        if (counterManager != null) counterManager.clearAll();

        logBanner("DISABLED");
    }

    // ── Banner ─────────────────────────────────────────────────────────────

    /**
     * Prints the Smorki-branded startup/reload/shutdown banner to the console.
     * Uses {@code Bukkit.getLogger()} so output is always prefixed with the
     * server's log timestamp — readable in plain log files and log aggregators.
     *
     * @param status one of: "ENABLED", "RELOADED", "DISABLED"
     */
    public void logBanner(String status) {
        Logger log = Bukkit.getLogger();
        log.info("--------------------------------------------------");
        log.info(" ____           _     _                   _     _           _ _           ");
        log.info("|  _ \\ ___  __| |___| |_ ___  _ __   ___| |   (_)_ __ ___ (_) |_ ___ _ __");
        log.info("| |_) / _ \\/ _` / __| __/ _ \\| '_ \\ / _ \\ |   | | '_ ` _ \\| | __/ _ \\ '__|");
        log.info("|  _ <  __/ (_| \\__ \\ || (_) | | | |  __/ |___| | | | | | | | ||  __/ |   ");
        log.info("|_| \\_\\___|\\__,_|___/\\__\\___/|_| |_|\\___|_____|_|_| |_| |_|_|\\__\\___|_|   ");
        log.info("[RedstoneLimiter] v" + getDescription().getVersion() + " (Target: Folia " + TARGET_FOLIA_VERSION + ")");
        log.info("[RedstoneLimiter] Engineered by Smorki");
        log.info("[RedstoneLimiter] Status: [" + status + "]");
        log.info("--------------------------------------------------");
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    public ConfigManager     getConfigManager()  { return configManager;  }
    public RegionFreezeManager getFreezeManager() { return freezeManager; }
    public TickCounterManager  getCounterManager(){ return counterManager;}

    /**
     * Returns the plugin's dedicated logger.
     * All internal classes should use this for the consistent "[FRL] " prefix.
     */
    public Logger getFRLLogger() { return frlLogger; }
}
