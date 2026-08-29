package eu.fakemoon.onevone;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;

/** Axis-aligned block region in one world (inclusive bounds). */
public record Region(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static Region of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Region(world,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(Location loc) {
        World w = loc.getWorld();
        if (w == null || !w.getName().equals(world)) return false;
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public List<Integer> toList() {
        return List.of(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static Region fromList(String world, List<Integer> list) {
        if (list == null || list.size() != 6) return null;
        return Region.of(world, list.get(0), list.get(1), list.get(2), list.get(3), list.get(4), list.get(5));
    }

    @Override
    public String toString() {
        return world + " (" + minX + "," + minY + "," + minZ + ") -> (" + maxX + "," + maxY + "," + maxZ + ")";
    }
}
