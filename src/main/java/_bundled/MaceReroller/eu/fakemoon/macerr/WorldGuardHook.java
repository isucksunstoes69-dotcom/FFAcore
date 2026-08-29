package eu.fakemoon.macerr;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Optional WorldGuard integration for regions excluded from reroll recipients. */
final class WorldGuardHook {

    private final MaceRRPlugin plugin;
    private boolean warnedMissing;
    private boolean warnedFailure;

    WorldGuardHook(MaceRRPlugin plugin) {
        this.plugin = plugin;
    }

    boolean available() {
        return plugin.getServer().getPluginManager().isPluginEnabled("WorldGuard");
    }

    List<String> missingRegionIds(List<String> configuredRegionIds) {
        if (!available()) return List.copyOf(configuredRegionIds);
        List<String> missing = new ArrayList<>();
        for (String configured : configuredRegionIds) {
            boolean found = false;
            for (World world : Bukkit.getWorlds()) {
                RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                        .get(BukkitAdapter.adapt(world));
                if (manager != null && manager.getRegions().keySet().stream()
                        .anyMatch(id -> id.equalsIgnoreCase(configured))) {
                    found = true;
                    break;
                }
            }
            if (!found) missing.add(configured);
        }
        return missing;
    }

    boolean allows(Player player, List<String> blockedRegionIds) {
        return allows(player.getLocation(), blockedRegionIds);
    }

    boolean allows(Location location, List<String> blockedRegionIds) {
        if (blockedRegionIds.isEmpty()) return true;
        if (!available()) {
            warnMissing();
            return false; // fail closed: configured exclusions must not be bypassed
        }
        try {
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(location.getWorld()));
            if (manager == null) return true;
            ApplicableRegionSet regions = manager.getApplicableRegions(BukkitAdapter.asBlockVector(location));
            for (ProtectedRegion region : regions.getRegions()) {
                if (blockedRegionIds.stream().anyMatch(id -> id.equalsIgnoreCase(region.getId()))) return false;
            }
            return true;
        } catch (Throwable ex) {
            if (!warnedFailure) {
                warnedFailure = true;
                plugin.getLogger().warning("WorldGuard region check failed; rerolls are paused for safety: "
                        + ex.getClass().getSimpleName());
            }
            return false;
        }
    }

    private void warnMissing() {
        if (warnedMissing) return;
        warnedMissing = true;
        plugin.getLogger().warning("blocked-regions is configured but WorldGuard is not installed; rerolls are paused.");
    }

    void resetWarnings() {
        warnedMissing = false;
        warnedFailure = false;
    }
}
