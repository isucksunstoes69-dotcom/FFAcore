package eu.fakemoon.combatlog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Component PREFIX = MINI.deserialize("<dark_gray>[<red>Combat</red>]</dark_gray> ");

    private Text() {
    }

    static Component render(String template, String player, String opponent, long seconds) {
        return MINI.deserialize(template,
                Placeholder.unparsed("player", player == null ? "Unknown" : player),
                Placeholder.unparsed("opponent", opponent == null ? "Unknown" : opponent),
                Placeholder.unparsed("seconds", Long.toString(seconds)));
    }

    static void message(CommandSender sender, String template) {
        sender.sendMessage(PREFIX.append(render(template, null, null, 0)));
    }

    static void message(CommandSender sender, String template, String player, String opponent, long seconds) {
        sender.sendMessage(PREFIX.append(render(template, player, opponent, seconds)));
    }
}
