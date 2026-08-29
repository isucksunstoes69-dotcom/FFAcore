package eu.fakemoon.meowffa.core;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CoreCommand implements CommandExecutor, TabCompleter {
    private final MeowFFACorePlugin plugin;
    private final CoreManager manager;
    private final CoreText text;
    CoreCommand(MeowFFACorePlugin plugin, CoreManager manager, CoreText text) { this.plugin = plugin; this.manager = manager; this.text = text; }

    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        String invoked = command.getName().toLowerCase(Locale.ROOT);
        if (isModule(invoked)) {
            bridge(sender, invoked, args);
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (invoked.equals("killstreak") || invoked.equals("ks")) { if (sender instanceof Player p) text.send(p, "killstreak", Map.of("streak", manager.streak(p))); return true; }
        if (invoked.equals("stats") || invoked.equals("statistics")) return stats(sender, args);
        if (invoked.equals("afk")) return afk(sender, args);
        if (invoked.equals("afkpool")) return afkPool(sender, args);
        if (invoked.equals("daily")) { if (args.length > 0 && args[0].equalsIgnoreCase("rewards")) return dailyRewards(sender); if (sender instanceof Player p) manager.claimDaily(p); return true; }
        if (invoked.equals("discord")) { text.send(sender, "discord", Map.of("url", plugin.getConfig().getString("discord-url", ""))); return true; }
        if (invoked.equals("store")) { text.send(sender, "store", Map.of("url", plugin.getConfig().getString("store-url", ""))); return true; }
        if (invoked.equals("tokens")) return tokens(sender, args);
        if (invoked.equals("bounty")) return bounty(sender, args);
        if (invoked.equals("setspawn")) { if (!(sender instanceof Player p) || !sender.hasPermission("meowffa.admin")) { text.send(sender, "admin", Map.of()); return true; } manager.setSpawn(p); text.send(p, "spawn", Map.of()); return true; }
        if (sub.equals("reload")) { if (!sender.hasPermission("meowffa.admin")) text.send(sender, "admin", Map.of()); else { plugin.reloadAll(); text.send(sender, "reloaded", Map.of()); } return true; }
        if (sub.equals("spawn")) { if (!(sender instanceof Player p)) return true; teleportSpawn(p); return true; }
        if (sub.equals("afk")) return afk(sender, Arrays.copyOfRange(args, 1, args.length));
        if (sub.equals("daily")) { if (args.length > 1 && args[1].equalsIgnoreCase("rewards")) return dailyRewards(sender); if (sender instanceof Player p) manager.claimDaily(p); return true; }
        if (sub.equals("afkpool")) return afkPool(sender, Arrays.copyOfRange(args, 1, args.length));
        if (sub.equals("discord")) { text.send(sender, "discord", Map.of("url", plugin.getConfig().getString("discord-url", ""))); return true; }
        if (sub.equals("store")) { text.send(sender, "store", Map.of("url", plugin.getConfig().getString("store-url", ""))); return true; }
        if (sub.equals("killstreak") || sub.equals("ks")) { if (sender instanceof Player p) text.send(p, "killstreak", Map.of("streak", manager.streak(p))); return true; }
        if (sub.equals("stats") || sub.equals("statistics")) return stats(sender, Arrays.copyOfRange(args, 1, args.length));
        if (sub.equals("sb")) { if (sender instanceof Player p) manager.toggleScoreboard(p); return true; }
        if (sub.equals("tokens")) return tokens(sender, Arrays.copyOfRange(args, 1, args.length));
        if (sub.equals("bounty")) return bounty(sender, Arrays.copyOfRange(args, 1, args.length));
        if (sub.equals("setspawn")) { if (!(sender instanceof Player p) || !sender.hasPermission("meowffa.admin")) { text.send(sender, "admin", Map.of()); return true; } manager.setSpawn(p); text.send(p, "spawn", Map.of()); return true; }
        if (isModule(sub)) { bridge(sender, sub, Arrays.copyOfRange(args, 1, args.length)); return true; }
        if (sub.isEmpty() && sender instanceof Player p) { teleportSpawn(p); return true; }
        text.send(sender, "usage", Map.of()); return true;
    }

    private void teleportSpawn(Player p) { var spawn = manager.spawn(); if (spawn == null) text.send(p, "spawn-not-set", Map.of()); else { p.teleport(spawn); text.send(p, "spawn", Map.of()); } }
    private boolean afk(CommandSender sender, String[] args) {
        if (args.length == 0 && sender instanceof Player p) { var target = manager.afk(); if (target == null) text.send(p, "afk-not-set", Map.of()); else { p.teleport(target); text.send(p, "afk", Map.of()); } return true; }
        if (!(sender instanceof Player p) || !p.hasPermission("meowffa.admin")) { text.send(sender, "admin", Map.of()); return true; }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("set")) { manager.setAfk(p); text.send(p, "afk-set", Map.of()); return true; }
        if (action.equals("npc")) { manager.spawnAfkNpc(p); text.send(p, "afk-npc-set", Map.of()); return true; }
        if (action.equals("remove")) { manager.removeAfkNpc(); text.send(p, "afk-npc-removed", Map.of()); return true; }
        return true;
    }
    private boolean dailyRewards(CommandSender sender) {
        if (!(sender instanceof Player p) || !p.hasPermission("meowffa.admin")) { text.send(sender, "admin", Map.of()); return true; }
        manager.openRewardGui(p, RewardGuiHolder.Type.DAILY); return true;
    }
    private boolean afkPool(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p) || !p.hasPermission("meowffa.admin")) { text.send(sender, "admin", Map.of()); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("wand")) { p.getInventory().addItem(manager.afkWand()); return true; }
        if (args[0].equalsIgnoreCase("create")) { if (!manager.createAfkPool(p)) p.sendMessage("§cMake a WorldEdit selection or set both wand positions first."); else p.sendMessage("§aAFK pool created."); return true; }
        if (args[0].equalsIgnoreCase("rewards")) { manager.openRewardGui(p, RewardGuiHolder.Type.AFK); return true; }
        if (args[0].equalsIgnoreCase("remove")) { manager.removeAfkPool(); p.sendMessage("§eAFK pool removed."); return true; }
        return true;
    }
    private boolean tokens(CommandSender sender, String[] args) {
        if (args.length >= 3 && args[0].equalsIgnoreCase("give") && sender.hasPermission("meowffa.tokens.admin")) { Player target = Bukkit.getPlayerExact(args[1]); try { int amount = Integer.parseInt(args[2]); if (target != null && amount > 0) { manager.addTokens(target, amount); text.send(sender, "tokens-given", Map.of("player", target.getName(), "tokens", amount)); text.send(target, "tokens-received", Map.of("tokens", amount)); } } catch (NumberFormatException ignored) {} return true; }
        if (sender instanceof Player p) text.send(p, "tokens", Map.of("tokens", manager.tokens(p))); return true;
    }
    private boolean stats(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) {
                text.send(p, "killstreak", Map.of("streak", manager.streak(p)));
            }
            return true;
        }
        if (!args[0].equalsIgnoreCase("reset")) { text.send(sender, "usage", Map.of()); return true; }
        Player target;
        boolean other = args.length >= 2;
        if (other) {
            if (!sender.hasPermission("meowffa.stats.admin")) { text.send(sender, "admin", Map.of()); return true; }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { sender.sendMessage("§cThat player must be online."); return true; }
        } else {
            if (!(sender instanceof Player p)) { sender.sendMessage("§cSpecify an online player."); return true; }
            target = p;
        }
        manager.resetStats(target);
        text.send(sender, other ? "stats-reset-other" : "stats-reset", Map.of("player", target.getName()));
        if (other && target != sender) text.send(target, "stats-reset", Map.of("player", target.getName()));
        return true;
    }
    private boolean bounty(CommandSender sender, String[] args) {
        if (!(sender instanceof Player setter)) return true;
        if (args.length >= 2 && args[0].equalsIgnoreCase("set")) { Player target = Bukkit.getPlayerExact(args[1]); if (target == null || args.length < 3) return true; try { int amount = Integer.parseInt(args[2]); if (manager.setBounty(setter, target, amount)) text.send(setter, "bounty-set", Map.of("player", target.getName(), "amount", amount)); } catch (NumberFormatException ignored) {} return true; }
        if (args.length >= 1) { Player target = Bukkit.getPlayerExact(args[0]); if (target != null) { int amount = manager.bounty(target); text.send(setter, amount == 0 ? "bounty-none" : "bounty-view", Map.of("player", target.getName(), "amount", amount)); } }
        return true;
    }
    private void bridge(CommandSender sender, String module, String[] args) {
        String command = moduleCommand(module);
        String full = command + (args.length == 0 ? "" : " " + String.join(" ", args));
        String pluginName = modulePlugin(module);
        if (!plugin.embeddedEnabled(pluginName) && !Bukkit.getPluginManager().isPluginEnabled(pluginName)) { text.send(sender, "bridge-missing", Map.of("module", pluginName)); return; }
        Bukkit.dispatchCommand(sender, full);
    }
    @Override public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        if (command.getName().equalsIgnoreCase("afk")) return args.length <= 1 ? List.of("set", "npc", "remove").stream().filter(s -> s.startsWith(args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT))).toList() : List.of();
        if (command.getName().equalsIgnoreCase("afkpool")) return args.length <= 1 ? List.of("wand", "create", "rewards", "remove").stream().filter(s -> s.startsWith(args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT))).toList() : List.of();
        List<String> options = List.of("spawn", "afk", "afkpool", "daily", "discord", "store", "tokens", "bounty", "killstreak", "stats", "statistics", "sb", "ss", "screenshare", "macerr", "mace", "ffa", "ffaweapons", "1v1", "1v1room", "onevoneroom", "kits", "kitmenu", "kitlayouts", "kitlayout", "layouts", "kit", "coinshop", "kitshop", "shop", "coins", "balance", "bal", "rig", "combatlog", "combat", "combattag", "setspawn");
        if (args.length == 1) return options.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("afk")) return List.of("set", "npc", "remove").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("afkpool")) return List.of("wand", "create", "rewards", "remove").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("daily")) return List.of("rewards").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && (args[0].equalsIgnoreCase("stats") || args[0].equalsIgnoreCase("statistics"))) return List.of("reset").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        String module = args[0].toLowerCase(Locale.ROOT);
        if (isModule(module)) {
            String[] moduleArgs = Arrays.copyOfRange(args, 1, args.length);
            return plugin.embeddedComplete(moduleCommand(module), sender, moduleArgs);
        }
        return new ArrayList<>();
    }

    private static boolean isModule(String value) {
        return List.of("ss", "screenshare", "macerr", "mace", "ffa", "ffaweapons", "1v1", "1v1room", "onevoneroom",
                "kits", "kitmenu", "kitlayouts", "kitlayout", "layouts", "kit", "coinshop", "kitshop", "shop",
                "coins", "balance", "bal", "rig", "combatlog", "combat", "combattag").contains(value);
    }

    private static String moduleCommand(String module) {
        return switch (module) {
            case "ss", "screenshare" -> "screenshare";
            case "macerr", "mace" -> "macerr";
            case "ffa", "ffaweapons" -> "ffaweapons";
            case "1v1", "1v1room", "onevoneroom" -> "1v1room";
            case "kitmenu", "kits" -> "kits";
            case "kitlayout", "layouts", "kitlayouts" -> "kitlayouts";
            case "kit" -> "kit";
            case "kitshop", "shop", "coinshop" -> "coinshop";
            case "balance", "bal", "coins" -> "coins";
            default -> module;
        };
    }

    private static String modulePlugin(String module) {
        return switch (moduleCommand(module)) {
            case "screenshare" -> "ScreenShare";
            case "macerr", "rig" -> "MaceReroller";
            case "ffaweapons" -> "FFAWeapons";
            case "1v1room" -> "OneVOneRoom";
            case "kits", "kitlayouts", "kit", "coinshop", "coins" -> "Kits";
            case "combatlog" -> "CombatLog";
            default -> module;
        };
    }
}
