package dev.smorki.foliaredstonelimiter.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Centralises all message parsing and dispatch so no other class imports
 * Adventure or MiniMessage directly — swapping parsers only requires
 * changes here.
 */
public final class MessageUtil {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private MessageUtil() {}

    /** Parse a MiniMessage string with optional tag resolvers and send to a sender. */
    public static void send(CommandSender sender, String template, TagResolver... resolvers) {
        sender.sendMessage(parse(template, resolvers));
    }

    /** Parse and broadcast a message to all online operators. */
    public static void broadcastOps(String template, TagResolver... resolvers) {
        Component msg = parse(template, resolvers);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp() || p.hasPermission("frl.admin")) {
                p.sendMessage(msg);
            }
        }
        // Also log to console so logs contain all freeze events.
        Bukkit.getConsoleSender().sendMessage(msg);
    }

    /**
     * Resolve a template with named placeholders.
     *
     * <p>Example: {@code parse("{world} froze", Placeholder.unparsed("world", "overworld"))}
     */
    public static Component parse(String template, TagResolver... resolvers) {
        if (resolvers.length == 0) return MM.deserialize(template);
        return MM.deserialize(template, TagResolver.resolver(resolvers));
    }

    /** Convenience builder for string placeholders used in freeze alerts. */
    public static TagResolver[] freezePlaceholders(
            String world, int chunkX, int chunkZ, long count, int limit, int seconds) {
        return new TagResolver[]{
                Placeholder.unparsed("world",   world),
                Placeholder.unparsed("chunk_x", String.valueOf(chunkX)),
                Placeholder.unparsed("chunk_z", String.valueOf(chunkZ)),
                Placeholder.unparsed("count",   String.valueOf(count)),
                Placeholder.unparsed("limit",   String.valueOf(limit)),
                Placeholder.unparsed("seconds", String.valueOf(seconds))
        };
    }
}
