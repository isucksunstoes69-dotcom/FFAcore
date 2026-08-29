package eu.fakemoon.meowffa.core;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class MeowFFACorePlugin extends JavaPlugin {
    private CoreManager manager;
    private CoreText text;
    private EmbeddedModules embedded;
    private MeowFFACoreExpansion placeholders;
    @Override public void onEnable() {
        getDataFolder().mkdirs(); saveDefaultConfig(); text = new CoreText(this); manager = new CoreManager(this, text); manager.start();
        getServer().getPluginManager().registerEvents(new CoreListener(manager), this);
        CoreCommand command = new CoreCommand(this, manager, text);
        for (String name : new String[]{"meowffacore", "spawn", "afk", "afkpool", "daily", "discord", "store", "tokens", "bounty", "killstreak", "stats"}) { if (getCommand(name) != null) { Objects.requireNonNull(getCommand(name)).setExecutor(command); Objects.requireNonNull(getCommand(name)).setTabCompleter(command); } }
        embedded = new EmbeddedModules(this);
        embedded.enable();
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) { placeholders = new MeowFFACoreExpansion(manager); placeholders.register(); }
        getLogger().info("MeowFFACore enabled with all modules embedded. Module commands are available directly.");
    }
    @Override public void onDisable() { if (placeholders != null) placeholders.unregister(); if (embedded != null) embedded.disable(); if (manager != null) manager.shutdown(); }
    void reloadAll() { reloadConfig(); text.reload(); manager.reloadSettings(); if (embedded != null) embedded.reload(); }
    boolean embeddedEnabled(String name) { return embedded != null && embedded.isEnabled(name); }
    java.util.List<String> embeddedComplete(String command, org.bukkit.command.CommandSender sender, String[] args) {
        return embedded == null ? java.util.List.of() : embedded.complete(command, sender, args);
    }
}
