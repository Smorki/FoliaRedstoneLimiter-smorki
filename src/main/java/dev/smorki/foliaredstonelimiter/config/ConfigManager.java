package dev.smorki.foliaredstonelimiter.config;

import dev.smorki.foliaredstonelimiter.FoliaRedstoneLimiter;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe configuration cache for FoliaRedstoneLimiter.
 *
 * <p>Folia's regionized architecture means multiple region threads can read
 * config values simultaneously. Rather than synchronizing every read, we store
 * all hot-path values in {@code volatile}-backed {@link AtomicReference} /
 * {@link AtomicInteger} fields so readers never block and writers publish
 * changes atomically in a single operation.
 *
 * <p>The actual disk I/O ({@link #reload()}) is intentionally dispatched on
 * the async scheduler — config files can be slow to read and must never stall
 * a region thread.
 */
public class ConfigManager {

    private final FoliaRedstoneLimiter plugin;

    // AtomicInteger gives lock-free reads from any region thread.
    private final AtomicInteger maxUpdatesPerTick = new AtomicInteger(500);
    private final AtomicInteger freezeDurationSeconds = new AtomicInteger(30);

    // Whether ConfigWatcher should apply on-disk changes automatically.
    private final AtomicBoolean autoReloadEnabled = new AtomicBoolean(true);

    // AtomicReference ensures the list reference is published as a whole;
    // individual region threads will never see a half-constructed list.
    private final AtomicReference<List<String>> bypassWorlds =
            new AtomicReference<>(List.of());

    // Message templates – updated atomically so readers always get a consistent snapshot.
    private final AtomicReference<String> msgFreezeAlert  = new AtomicReference<>("");
    private final AtomicReference<String> msgReloadSuccess = new AtomicReference<>("");
    private final AtomicReference<String> msgNoPermission  = new AtomicReference<>("");
    private final AtomicReference<String> msgUnknownCmd    = new AtomicReference<>("");

    public ConfigManager(FoliaRedstoneLimiter plugin) {
        this.plugin = plugin;
    }

    /**
     * Reloads config from disk.
     *
     * <p><b>Must be called from the async scheduler thread.</b>
     * Folia's {@code Server.getAsyncScheduler()} guarantees this method
     * never runs on a region or main thread, keeping I/O off the hot path.
     */
    public void reload() {
        // Re-read the file from disk (Bukkit copies defaults automatically)
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        maxUpdatesPerTick.set(cfg.getInt("max-redstone-updates-per-tick", 500));
        freezeDurationSeconds.set(cfg.getInt("freeze-duration-seconds", 30));
        autoReloadEnabled.set(cfg.getBoolean("auto-reload", true));
        bypassWorlds.set(List.copyOf(cfg.getStringList("bypass-worlds")));

        msgFreezeAlert.set(cfg.getString("messages.freeze-alert", ""));
        msgReloadSuccess.set(cfg.getString("messages.reload-success", ""));
        msgNoPermission.set(cfg.getString("messages.no-permission", ""));
        msgUnknownCmd.set(cfg.getString("messages.unknown-command", ""));
    }

    // ── Accessors (lock-free reads, safe from any thread) ──────────────────

    public int getMaxUpdatesPerTick()     { return maxUpdatesPerTick.get(); }
    public int getFreezeDurationSeconds() { return freezeDurationSeconds.get(); }
    public boolean isAutoReloadEnabled()  { return autoReloadEnabled.get(); }
    public List<String> getBypassWorlds() { return bypassWorlds.get(); }

    public String getMsgFreezeAlert()   { return msgFreezeAlert.get(); }
    public String getMsgReloadSuccess() { return msgReloadSuccess.get(); }
    public String getMsgNoPermission()  { return msgNoPermission.get(); }
    public String getMsgUnknownCmd()    { return msgUnknownCmd.get(); }
}
