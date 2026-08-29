package eu.fakemoon.macerr;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class MaceRRPlugin extends JavaPlugin {

    private RerollManager manager;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        saveDefaultConfig();
        Util.initMessages(this);
        getConfig().options().copyDefaults(true);
        saveConfig();
        manager = new RerollManager(this);
        manager.load();
        manager.logRegionStatus();

        getServer().getPluginManager().registerEvents(new RerollListener(manager), this);

        Objects.requireNonNull(getCommand("rig")).setExecutor(new RigCommand(manager));
        MaceRRCommand macerr = new MaceRRCommand(this, manager);
        Objects.requireNonNull(getCommand("macerr")).setExecutor(macerr);
        Objects.requireNonNull(getCommand("macerr")).setTabCompleter(macerr);

        // A pending roll can resume when somebody walks out of a blocked region;
        // checking once per second avoids a hot PlayerMoveEvent listener.
        getServer().getScheduler().runTaskTimer(this, manager::retryPending, 20L, 20L);

        getLogger().info("MaceReroller enabled.");
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.saveNow();
    }

    public RerollManager manager() {
        return manager;
    }
}
