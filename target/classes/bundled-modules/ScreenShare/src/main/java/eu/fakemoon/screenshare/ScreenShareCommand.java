package eu.fakemoon.screenshare;

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
import java.util.Map;

final class ScreenShareCommand implements CommandExecutor, TabCompleter {
    private final ScreenShareManager manager;
    private final Text text;
    ScreenShareCommand(ScreenShareManager manager, Text text) { this.manager = manager; this.text = text; }

    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length == 1 && sender instanceof Player player && args[0].equalsIgnoreCase("accept")) {
            if (!manager.accept(player)) text.send(player, "not-found", Map.of());
            return true;
        }
        if (args.length == 1 && sender instanceof Player player && args[0].equalsIgnoreCase("deny")) {
            if (!manager.deny(player)) text.send(player, "not-found", Map.of());
            return true;
        }
        if (args.length == 1 && sender instanceof Player player && args[0].equalsIgnoreCase("admit")) {
            if (!manager.admit(player)) text.send(player, "not-found", Map.of());
            return true;
        }
        if (!(sender instanceof Player staff) || !staff.hasPermission("screenshare.staff")) { text.send(sender, "not-staff", Map.of()); return true; }
        if (args.length == 0) { text.send(staff, "usage", Map.of()); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("note")) {
            if (args.length < 3) { text.send(staff, "usage", Map.of()); return true; }
            Player target = target(args[1]);
            if (target == null) { text.send(staff, "not-found", Map.of()); return true; }
            manager.addNote(staff, target, String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
            return true;
        }
        if (sub.equals("close") || sub.equals("unfreeze")) {
            Player target = args.length >= 2 ? target(args[1]) : findControlled(staff);
            if (target == null) { text.send(staff, "not-found", Map.of()); return true; }
            manager.closeForStaff(staff, target); return true;
        }
        if (sub.equals("inspect") || sub.equals("inventory") || sub.equals("ender")) {
            if (args.length < 2) { text.send(staff, "usage", Map.of()); return true; }
            Player target = target(args[1]);
            if (target == null) { text.send(staff, "not-found", Map.of()); return true; }
            manager.openInventory(staff, target, sub.equals("ender")); return true;
        }
        Player target = target(args[0]);
        if (target == null) { text.send(staff, "not-found", Map.of()); return true; }
        if (!manager.request(staff, target)) text.send(staff, "not-found", Map.of());
        return true;
    }

    private Player target(String name) { return Bukkit.getPlayerExact(name); }
    private Player findControlled(Player staff) { return Bukkit.getOnlinePlayers().stream().filter(p -> manager.canInspect(staff, p)).findFirst().orElse(null); }

    @Override public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("accept", "admit", "deny", "inspect", "inventory", "ender", "note", "close", "unfreeze"));
            Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
            return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && List.of("inspect", "inventory", "ender", "note", "close", "unfreeze").contains(args[0].toLowerCase(Locale.ROOT))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
