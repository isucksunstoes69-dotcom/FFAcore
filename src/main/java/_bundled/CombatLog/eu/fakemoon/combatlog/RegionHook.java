package eu.fakemoon.combatlog;

import org.bukkit.Location;

import java.util.Set;

interface RegionHook {

    boolean matches(Location location, Set<String> configuredRegions);

    boolean available();
}
