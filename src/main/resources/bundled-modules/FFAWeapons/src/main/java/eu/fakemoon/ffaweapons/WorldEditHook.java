package eu.fakemoon.ffaweapons;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.entity.Player;

/**
 * Isolated so the class only loads when WorldEdit/FAWE is actually installed —
 * call sites guard with a plugin-presence check and catch Throwable.
 */
final class WorldEditHook {

    private WorldEditHook() {
    }

    /** The player's current //wand selection as [x1,y1,z1,x2,y2,z2], or null. */
    static int[] selection(Player player) {
        try {
            LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
            Region region = session.getSelection(BukkitAdapter.adapt(player.getWorld()));
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            return new int[]{min.x(), min.y(), min.z(), max.x(), max.y(), max.z()};
        } catch (IncompleteRegionException ex) {
            return null;
        }
    }
}
