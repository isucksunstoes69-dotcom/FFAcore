package eu.fakemoon.screenshare;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public class ScreenSharePlugin extends JavaPlugin {
    private ScreenShareManager manager;
    private Text text;

    @Override public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        text = new Text(this);
        manager = new ScreenShareManager(this, text);
        manager.start();
        getServer().getPluginManager().registerEvents(new ScreenShareListener(manager, text), this);
        ScreenShareCommand command = new ScreenShareCommand(manager, text);
        Objects.requireNonNull(getCommand("screenshare")).setExecutor(command);
        Objects.requireNonNull(getCommand("screenshare")).setTabCompleter(command);
        getLogger().info("ScreenShare enabled. Desktop capture is not provided; use the in-game inspection workflow.");
    }

    @Override public void onDisable() { if (manager != null) manager.shutdown(); }

    /** Reloads this module when it is embedded in MeowFFACore. */
    public void reloadModule() {
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        if (text != null) text.reload();
    }
}
