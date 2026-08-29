package eu.fakemoon.onevone;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public class OneVOnePlugin extends JavaPlugin {

    private RoomManager rooms;
    private SelectionManager selection;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        saveDefaultConfig();
        rooms = new RoomManager(this);
        rooms.load();
        selection = new SelectionManager(this);

        getServer().getPluginManager().registerEvents(selection, this);
        getServer().getPluginManager().registerEvents(new RoomListener(this, rooms, selection), this);
        if (getConfig().getBoolean("worldguard-override-duel-pvp", true)
                && getServer().getPluginManager().isPluginEnabled("WorldGuard")) {
            try {
                getServer().getPluginManager().registerEvents(new WorldGuardPvpHook(rooms), this);
                getLogger().info("WorldGuard duel-PvP integration enabled.");
            } catch (Throwable error) {
                getLogger().warning("Could not enable WorldGuard duel-PvP integration: " + error.getMessage());
            }
        }

        RoomCommand command = new RoomCommand(this, rooms, selection);
        Objects.requireNonNull(getCommand("1v1room")).setExecutor(command);
        Objects.requireNonNull(getCommand("1v1room")).setTabCompleter(command);
        Objects.requireNonNull(getCommand("1v1leave")).setExecutor(new LeaveCommand(rooms));
        rooms.start();
        getServer().getOnlinePlayers().forEach(rooms::restoreOrEvacuate);

        getLogger().info("OneVOneRoom enabled with " + rooms.all().size() + " room(s). WorldEdit/FAWE: "
                + (selection.worldEditPresent() ? "detected" : "not installed, using built-in wand"));
    }

    @Override
    public void onDisable() {
        if (rooms != null) rooms.shutdown();
    }
}
