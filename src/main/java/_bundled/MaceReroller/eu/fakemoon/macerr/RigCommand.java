package eu.fakemoon.macerr;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class RigCommand implements CommandExecutor {

    private final RerollManager manager;

    public RigCommand(RerollManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (args.length < 1 || args.length > 3) {
            Util.msgKey(sender, "command.usage-rig", Map.of());
            return true;
        }
        boolean immediate = args[0].equalsIgnoreCase("now");
        int victimIndex = immediate ? 1 : 0;
        if (args.length <= victimIndex) {
            Util.msgKey(sender, "command.usage-rig", Map.of());
            return true;
        }
        Player victim = Bukkit.getPlayerExact(args[victimIndex]);
        if (victim == null) {
            Util.msgKey(sender, "command.player-offline", Map.of("player", args[victimIndex]));
            return true;
        }
        if (immediate) {
            if (manager.reroll(sender, victim)) Util.msgKey(sender, "command.rig-started", Map.of("player", victim.getName()));
            return true;
        }
        Player recipient = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
        if (recipient == null) { Util.msgKey(sender, "command.usage-rig", Map.of()); return true; }
        manager.rigAfterDeath(sender, victim, recipient);
        return true;
    }
}
