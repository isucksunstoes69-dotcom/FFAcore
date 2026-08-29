package eu.fakemoon.macerr;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MaceRRCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("item", "items", "pool", "reroll", "status", "reload", "enable", "disable");

    private final MaceRRPlugin plugin;
    private final RerollManager manager;

    public MaceRRCommand(MaceRRPlugin plugin, RerollManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "item", "items", "pool" -> {
                if (!(sender instanceof Player player)) {
                    Util.msgKey(sender, "command.players-only", Map.of());
                    return true;
                }
                ItemGui.open(manager, player);
            }
            case "reroll" -> {
                if (manager.reroll(sender, null)) {
                    Util.msgKey(sender, "command.reroll-started", Map.of());
                }
            }
            case "disable" -> manager.disable(sender);
            case "enable" -> manager.enable(sender);
            case "reload" -> {
                plugin.reloadConfig();
                Util.reloadMessages();
                plugin.getConfig().options().copyDefaults(true);
                plugin.saveConfig();
                manager.configReloaded(sender);
            }
            case "status" -> {
                Util.msg(sender, "<gold>MaceReroller status:");
                sender.sendMessage(Util.mm("<gray> Enabled: <white>" + (manager.isEnabled() ? "yes" : "no") + "</white>"));
                sender.sendMessage(Util.mm("<gray> Pool entries: <white>" + manager.poolSize() + "</white>"));
                sender.sendMessage(Util.mm("<gray> Active bundle: <white>"
                        + (manager.activeItems().isEmpty() ? "none" : manager.itemName()) + "</white>"));
                sender.sendMessage(Util.mm("<gray> WorldGuard: <white>"
                        + (manager.worldGuardAvailable() ? "connected" : "not installed") + "</white>"));
                sender.sendMessage(Util.mm("<gray> Blocked regions: <white>"
                        + (manager.blockedRegions().isEmpty() ? "none" : String.join(", ", manager.blockedRegions()))
                        + "</white>"));
                if (manager.worldGuardAvailable() && !manager.missingBlockedRegions().isEmpty()) {
                    sender.sendMessage(Util.mm("<gray> Missing region IDs: <red>"
                            + String.join(", ", manager.missingBlockedRegions()) + "</red>"));
                }
                int shown = 0;
                for (var item : manager.pool()) {
                    if (shown++ >= 10) {
                        sender.sendMessage(Util.mm("<dark_gray>  ...and " + (manager.poolSize() - 10) + " more"));
                        break;
                    }
                    sender.sendMessage(Util.mm("<dark_gray>  - <gray>" + item.getAmount() + "x <white>"
                            + manager.poolItemName(item) + "</white>"));
                }
                String holderName = "nobody";
                if (manager.holder() != null) {
                    OfflinePlayer holder = Bukkit.getOfflinePlayer(manager.holder());
                    holderName = holder.getName() == null ? manager.holder().toString() : holder.getName();
                }
                sender.sendMessage(Util.mm("<gray> Holder: <white>" + holderName + "</white>"));
                if (manager.hasGroundItem()) {
                    sender.sendMessage(Util.mm("<gray> The item is lying on the ground somewhere."));
                }
                if (manager.isPending()) {
                    sender.sendMessage(Util.mm("<gray> Waiting for a player to join to reroll."));
                }
                if (manager.isRolling()) {
                    sender.sendMessage(Util.mm("<gray> A reroll is in progress right now."));
                }
            }
            default -> {
                Util.msg(sender, "<gold>MaceReroller commands:");
                sender.sendMessage(Util.mm("<gray> /macerr item <dark_gray>- edit the 45-slot weapon bundle"));
                sender.sendMessage(Util.mm("<gray> /macerr reroll <dark_gray>- send every pool item to a random player"));
                sender.sendMessage(Util.mm("<gray> /macerr status <dark_gray>- who holds it"));
                sender.sendMessage(Util.mm("<gray> /macerr reload <dark_gray>- reload config and region IDs"));
                sender.sendMessage(Util.mm("<gray> /macerr disable <dark_gray>- stop rerolls and remove the bundle"));
                sender.sendMessage(Util.mm("<gray> /macerr enable <dark_gray>- turn the reroller back on"));
                sender.sendMessage(Util.mm("<gray> /rig <player> <dark_gray>- reroll that secretly lands on them"));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
