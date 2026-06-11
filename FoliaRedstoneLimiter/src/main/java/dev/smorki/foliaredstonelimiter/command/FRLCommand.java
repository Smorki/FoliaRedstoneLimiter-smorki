package dev.smorki.foliaredstonelimiter.command;

import dev.smorki.foliaredstonelimiter.FoliaRedstoneLimiter;
import dev.smorki.foliaredstonelimiter.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Handles the {@code /frl} command family.
 *
 * <h3>Async reload pattern</h3>
 * <p>Config I/O is dispatched via {@code Server.getAsyncScheduler()} so the
 * command never blocks any region thread (or the legacy main thread) while
 * reading from disk. After the async read completes, a confirmation message
 * is sent back to the sender.
 *
 * <p>Note: {@code sender.sendMessage()} is thread-safe in Folia — the
 * Adventure audience API queues packets correctly from any thread.
 */
public class FRLCommand implements CommandExecutor, TabCompleter {

    private final FoliaRedstoneLimiter plugin;

    public FRLCommand(FoliaRedstoneLimiter plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        // ── Permission gate ────────────────────────────────────────────────
        if (!sender.hasPermission("frl.admin")) {
            MessageUtil.send(sender, plugin.getConfigManager().getMsgNoPermission());
            return true;
        }

        if (args.length == 0) {
            MessageUtil.send(sender, plugin.getConfigManager().getMsgUnknownCmd());
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "reload" -> {
                /*
                 * Dispatch config reload to the async scheduler.
                 *
                 * getAsyncScheduler().runNow() runs on a Folia-managed async
                 * thread pool — safe for blocking I/O and guaranteed NOT to
                 * run on any region thread.
                 */
                Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> {
                    plugin.getConfigManager().reload();
                    plugin.logBanner("RELOADED");
                    // sendMessage is safe from async threads in Adventure / Folia.
                    MessageUtil.send(sender, plugin.getConfigManager().getMsgReloadSuccess());
                });
            }

            default -> MessageUtil.send(sender, plugin.getConfigManager().getMsgUnknownCmd());
        }

        return true;
    }

    /** Tab-complete the 'reload' sub-command. */
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String label,
                                                @NotNull String[] args) {
        if (!sender.hasPermission("frl.admin")) return List.of();
        if (args.length == 1) return List.of("reload");
        return List.of();
    }
}
