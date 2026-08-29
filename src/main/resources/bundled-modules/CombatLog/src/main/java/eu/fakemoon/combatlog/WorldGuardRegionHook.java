package eu.fakemoon.combatlog;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;

import java.util.Locale;
import java.util.Set;

/** Kept in its own class so WorldGuard remains a truly optional dependency. */
final class WorldGuardRegionHook implements RegionHook {

    private final CombatLogPlugin plugin;
    private final RegionQuery query;
    private boolean warned;

    WorldGuardRegionHook(CombatLogPlugin plugin) {
        this.plugin = plugin;
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        this.query = container.createQuery();
    }

    @Override
    public boolean matches(Location location, Set<String> configuredRegions) {
        if (configuredRegions.isEmpty() || location.getWorld() == null) return false;
        try {
            ApplicableRegionSet applicable = query.getApplicableRegions(BukkitAdapter.adapt(location));
            String world = location.getWorld().getName().toLowerCase(Locale.ROOT);
            for (ProtectedRegion region : applicable.getRegions()) {
                String id = region.getId().toLowerCase(Locale.ROOT);
                if (configuredRegions.contains(id) || configuredRegions.contains(world + ':' + id)) return true;
            }
        } catch (Throwable ex) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("WorldGuard region query failed; continuing without a region match: "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
        return false;
    }

    @Override
    public boolean available() {
        return true;
    }
}
