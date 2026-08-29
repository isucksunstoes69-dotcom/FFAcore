package eu.fakemoon.ffaweapons;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public class FFAWeaponsPlugin extends JavaPlugin {

    private Weapons weapons;
    private BlacklistManager blacklist;
    private SelectionManager selection;
    private WeaponsListener weaponsListener;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        saveDefaultConfig();
        migrateLegacyWardenConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        weapons = new Weapons(this);
        blacklist = new BlacklistManager(this);
        blacklist.load();
        selection = new SelectionManager(this);

        getServer().getPluginManager().registerEvents(selection, this);
        weaponsListener = new WeaponsListener(this, weapons, blacklist);
        getServer().getPluginManager().registerEvents(weaponsListener, this);
        getServer().getPluginManager().registerEvents(new WeaponsGuiListener(), this);

        WeaponsCommand command = new WeaponsCommand(this, weapons, blacklist, selection);
        Objects.requireNonNull(getCommand("ffaweapons")).setExecutor(command);
        Objects.requireNonNull(getCommand("ffaweapons")).setTabCompleter(command);

        getLogger().info("FFAWeapons enabled with " + weapons.ids().size() + " weapons. WorldEdit/FAWE: "
                + (selection.worldEditPresent() ? "detected" : "not installed, using built-in wand"));
    }

    @Override
    public void onDisable() {
        if (weaponsListener != null) weaponsListener.cleanup();
        if (blacklist != null) blacklist.flushSync();
    }

    /** Preserve any existing Warden Sword tuning when upgrading to the Warden Blade name. */
    private void migrateLegacyWardenConfig() {
        String oldRoot = "weapons.warden-sword";
        String newRoot = "weapons.warden-blade";
        if (getConfig().contains(newRoot, true) || !getConfig().contains(oldRoot, true)) return;
        for (String key : new String[]{"damage", "range", "cooldown-seconds"}) {
            getConfig().set(newRoot + "." + key, getConfig().get(oldRoot + "." + key));
        }
    }
}
