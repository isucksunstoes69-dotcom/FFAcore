package eu.fakemoon.combatlog;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class CombatLogCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ADMIN_SUBCOMMANDS = List.of("reload", "tag", "untag");

    private final CombatLogPlugin plugin;
    private final CombatTagManager tags;

    CombatLogCommand(CombatLogPlugin plugin, CombatTagManager tags) {
        this.plugin = plugin;
        this.tags = tags;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("status")) {
            Player target;
            if (args.length >= 2 && sender.hasPermission("combatlog.admin")) {
                target = Bukkit.getPlayerExact(args[1]);
            } else if (sender instanceof Player player && sender.hasPermission("combatlog.status")) {
                target = player;
            } else {
                Text.message(sender, "<red>Use <white>/combatlog status <player></white>.");
                return true;
            }
            if (target == null) {
                Text.message(sender, "<red>That player is not online.");
                return true;
            }
            long seconds = tags.remainingSeconds(target.getUniqueId());
            if (seconds == 0) {
                Text.message(sender, "<green><player> is not in combat.", target.getName(), null, 0);
            } else {
                Text.message(sender, "<red><player> is in combat for another <white><seconds>s</white>.",
                        target.getName(), null, seconds);
            }
            return true;
        }

        if (!sender.hasPermission("combatlog.admin")) {
            Text.message(sender, "<red>You do not have permission.");
            return true;
        }

        switch (sub) {
            case "reload" -> {
                boolean ready = plugin.reloadSettings();
                if (ready) {
                    Text.message(sender, "<green>Config reloaded. Combat time: <white>"
                            + plugin.values().durationSeconds() + "s</white>.");
                } else {
                    Text.message(sender, "<red>Config requires WorldGuard, but WorldGuard is unavailable.");
                }
            }
            case "tag" -> {
                if (args.length < 2) {
                    Text.message(sender, "<red>Usage: /combatlog tag <player> [seconds]");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    Text.message(sender, "<red>That player is not online.");
                    return true;
                }
                int seconds = plugin.values().durationSeconds();
                if (args.length >= 3) {
                    try {
                        seconds = Math.max(1, Math.min(3600, Integer.parseInt(args[2])));
                    } catch (NumberFormatException ex) {
                        Text.message(sender, "<red>Seconds must be a whole number from 1 to 3600.");
                        return true;
                    }
                }
                tags.tag(target, null, seconds);
                Text.message(sender, "<green>Tagged <white><player></white> for <white><seconds>s</white>.",
                        target.getName(), null, seconds);
            }
            case "untag" -> {
                if (args.length < 2) {
                    Text.message(sender, "<red>Usage: /combatlog untag <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    Text.message(sender, "<red>That player is not online.");
                    return true;
                }
                tags.untag(target.getUniqueId(), false);
                Text.message(sender, "<green>Removed <white><player></white>'s combat tag.", target.getName(), null, 0);
            }
            default -> {
                Text.message(sender, "<gold>CombatLog commands:");
                sender.sendMessage(Text.render("<gray>/combatlog status [player]", null, null, 0));
                sender.sendMessage(Text.render("<gray>/combatlog reload", null, null, 0));
                sender.sendMessage(Text.render("<gray>/combatlog tag <player> [seconds]", null, null, 0));
                sender.sendMessage(Text.render("<gray>/combatlog untag <player>", null, null, 0));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>();
            values.add("status");
            if (sender.hasPermission("combatlog.admin")) values.addAll(ADMIN_SUBCOMMANDS);
            String input = args[0].toLowerCase(Locale.ROOT);
            return values.stream().filter(value -> value.startsWith(input)).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("status")
                || args[0].equalsIgnoreCase("tag") || args[0].equalsIgnoreCase("untag"))) {
            String input = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(input)).toList();
        }
        return List.of();
    }
}
