package eu.fakemoon.ffaweapons;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class WeaponsCommand implements CommandExecutor, TabCompleter {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,24}");

    private final FFAWeaponsPlugin plugin;
    private final Weapons weapons;
    private final BlacklistManager blacklist;
    private final SelectionManager selection;

    public WeaponsCommand(FFAWeaponsPlugin plugin, Weapons weapons,
                          BlacklistManager blacklist, SelectionManager selection) {
        this.plugin = plugin;
        this.weapons = weapons;
        this.blacklist = blacklist;
        this.selection = selection;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                Util.msg(sender, "<red>Players only.");
                return true;
            }
            WeaponsGui.open(weapons, player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "wand" -> wand(sender);
            case "blacklist" -> blacklist(sender, args);
            case "reload" -> {
                plugin.reloadConfig();
                plugin.getConfig().options().copyDefaults(true);
                plugin.saveConfig();
                weapons.rebuild();
                blacklist.load();
                Util.msg(sender, "<green>Config reloaded — weapons rebuilt. "
                        + "<gray>Already-given items use the new numbers; re-grab for fresh lore.");
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        Util.msg(sender, "<gold>FFA weapons commands:");
        sender.sendMessage(Util.mm("<gray> /ffaweapons <dark_gray>- open the weapons GUI"));
        sender.sendMessage(Util.mm("<gray> /ffaweapons wand <dark_gray>- selection wand (WorldEdit works too)"));
        sender.sendMessage(Util.mm("<gray> /ffaweapons blacklist add <name> <dark_gray>- weapons disabled in selection"));
        sender.sendMessage(Util.mm("<gray> /ffaweapons blacklist remove <name>"));
        sender.sendMessage(Util.mm("<gray> /ffaweapons blacklist list"));
        sender.sendMessage(Util.mm("<gray> /ffaweapons reload <dark_gray>- reload config.yml"));
    }

    private void wand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Util.msg(sender, "<red>Players only.");
            return;
        }
        player.getInventory().addItem(selection.wand());
        Util.msg(player, "<green>Wand given. Left click = pos1, right click = pos2."
                + (selection.worldEditPresent() ? " <gray>(WorldEdit selections work too)" : ""));
    }

    private void blacklist(CommandSender sender, String[] args) {
        String action = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "add" -> {
                if (!(sender instanceof Player player)) {
                    Util.msg(sender, "<red>Players only.");
                    return;
                }
                if (args.length < 3 || !NAME.matcher(args[2].toLowerCase(Locale.ROOT)).matches()) {
                    Util.msg(sender, "<red>Usage: /ffaweapons blacklist add <name> (a-z, 0-9, _ , -)");
                    return;
                }
                Region region = selection.selection(player);
                if (region == null) {
                    Util.msg(player, "<red>No selection. Use "
                            + (selection.worldEditPresent() ? "<white>//wand</white> or " : "")
                            + "<white>/ffaweapons wand</white> first.");
                    return;
                }
                blacklist.add(args[2], region);
                Util.msg(player, "<green>Blacklist zone <yellow>" + args[2].toLowerCase(Locale.ROOT)
                        + "</yellow> added: <white>" + region + "</white> — weapons are dead in there.");
            }
            case "remove" -> {
                if (args.length < 3) {
                    Util.msg(sender, "<red>Usage: /ffaweapons blacklist remove <name>");
                    return;
                }
                if (blacklist.remove(args[2])) {
                    Util.msg(sender, "<green>Blacklist zone <yellow>" + args[2].toLowerCase(Locale.ROOT) + "</yellow> removed.");
                } else {
                    Util.msg(sender, "<red>No zone named <white>" + args[2] + "</white>.");
                }
            }
            case "list" -> {
                if (blacklist.zones().isEmpty()) {
                    Util.msg(sender, "<gray>No blacklist zones — weapons work everywhere.");
                    return;
                }
                Util.msg(sender, "<gold>Blacklist zones (" + blacklist.zones().size() + "):");
                for (Map.Entry<String, Region> entry : blacklist.zones().entrySet()) {
                    sender.sendMessage(Util.mm(" <yellow>" + entry.getKey() + "</yellow> <dark_gray>-</dark_gray> <gray>"
                            + entry.getValue() + "</gray>"));
                }
            }
            default -> Util.msg(sender, "<red>Usage: /ffaweapons blacklist <add|remove|list> [name]");
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) {
            return List.of("wand", "blacklist", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("blacklist")) {
            return List.of("add", "remove", "list").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("blacklist") && args[1].equalsIgnoreCase("remove")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return blacklist.zones().keySet().stream().filter(n -> n.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
