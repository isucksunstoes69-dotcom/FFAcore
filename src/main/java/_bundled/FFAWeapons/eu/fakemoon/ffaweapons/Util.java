package eu.fakemoon.ffaweapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

final class Util {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Component PREFIX = mm("<dark_gray>[<gradient:#FF5555:#5555FF>ꜰꜰᴀ</gradient>]</dark_gray> ");

    private Util() {
    }

    static Component mm(String miniMessage) {
        return MM.deserialize(miniMessage).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    static void msg(CommandSender to, String miniMessage) {
        to.sendMessage(PREFIX.append(mm(miniMessage)));
    }
}
