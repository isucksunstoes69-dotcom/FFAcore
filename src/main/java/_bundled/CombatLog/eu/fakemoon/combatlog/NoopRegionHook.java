package eu.fakemoon.combatlog;

import org.bukkit.Location;

import java.util.Set;

final class NoopRegionHook implements RegionHook {

    @Override
    public boolean matches(Location location, Set<String> configuredRegions) {
        return false;
    }

    @Override
    public boolean available() {
        return false;
    }
}
