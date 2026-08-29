package eu.fakemoon.combatlog;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class CombatLogPlugin extends JavaPlugin {

    private ConfigValues values;
    private RegionHook regionHook = new NoopRegionHook();
    private CombatTagManager tags;
    private boolean shuttingDown;

    @Override
    public void onEnable() {
        shuttingDown = false;
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        values = ConfigValues.load(this);
        rebuildRegionHook();
        if (values.worldGuardRequired() && !regionHook.available()) {
            getLogger().severe("WorldGuard is required by config but is not available. Disabling CombatLog.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        tags = new CombatTagManager(this);
        getServer().getPluginManager().registerEvents(new CombatListener(this, tags), this);

        CombatLogCommand command = new CombatLogCommand(this, tags);
        Objects.requireNonNull(getCommand("combatlog")).setExecutor(command);
        Objects.requireNonNull(getCommand("combatlog")).setTabCompleter(command);
        tags.startTicker();

        getLogger().info("CombatLog enabled with a " + values.durationSeconds() + " second PvP tag"
                + (regionHook.available() ? " and WorldGuard support." : "."));
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        if (tags != null) tags.shutdown();
    }

    boolean reloadSettings() {
        ConfigValues previousValues = values;
        RegionHook previousRegionHook = regionHook;
        reloadConfig();
        getConfig().options().copyDefaults(true);
        values = ConfigValues.load(this);
        rebuildRegionHook();
        if (values.worldGuardRequired() && !regionHook.available()) {
            // Keep the last known-good runtime settings. The file remains as the
            // operator wrote it, so a full restart will correctly refuse to load.
            values = previousValues;
            regionHook = previousRegionHook;
            return false;
        }
        if (tags != null) tags.startTicker();
        return true;
    }

    ConfigValues values() {
        return values;
    }

    RegionHook regionHook() {
        return regionHook;
    }

    boolean isShuttingDown() {
        return shuttingDown;
    }

    private void rebuildRegionHook() {
        regionHook = new NoopRegionHook();
        if (!values.worldGuardEnabled()) return;

        Plugin worldGuard = getServer().getPluginManager().getPlugin("WorldGuard");
        if (worldGuard == null || !worldGuard.isEnabled()) {
            if (!values.ignoredRegions().isEmpty() || !values.blockedRegions().isEmpty()) {
                getLogger().warning("WorldGuard is not installed. Combat tagging still works, but configured region rules are inactive.");
            }
            return;
        }

        try {
            regionHook = new WorldGuardRegionHook(this);
        } catch (Throwable ex) {
            getLogger().severe("Could not initialize WorldGuard support: "
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            regionHook = new NoopRegionHook();
        }
    }
}
