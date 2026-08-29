package eu.fakemoon.onevone;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Region selection supports WorldEdit/FAWE and the plugin's own golden-axe wand.
 */
public final class SelectionManager implements Listener {

    private final NamespacedKey wandKey;
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();

    public SelectionManager(OneVOnePlugin plugin) {
        this.wandKey = new NamespacedKey(plugin, "wand");
    }

    public boolean worldEditPresent() {
        return Bukkit.getPluginManager().getPlugin("WorldEdit") != null
                || Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
    }

    public ItemStack wand() {
        ItemStack item = new ItemStack(Material.GOLDEN_AXE);
        item.editMeta(meta -> {
            meta.displayName(Util.mm("<gold><bold>1v1 Room Wand</bold></gold>"));
            meta.lore(List.of(
                    Util.mm("<gray>Left click a block: <white>pos1</white>"),
                    Util.mm("<gray>Right click a block: <white>pos2</white>")));
            meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    boolean isWand(ItemStack item) {
        return item != null && item.getType() == Material.GOLDEN_AXE && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    public void resetBuiltIn(Player player) {
        pos1.remove(player.getUniqueId());
        pos2.remove(player.getUniqueId());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || !isWand(event.getItem())) return;
        Player player = event.getPlayer();
        Location loc = event.getClickedBlock().getLocation();
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            pos1.put(player.getUniqueId(), loc);
            Util.msg(player, "<green>pos1</green> <gray>set to <white>"
                    + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + "</white>");
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            pos2.put(player.getUniqueId(), loc);
            Util.msg(player, "<green>pos2</green> <gray>set to <white>"
                    + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + "</white>");
        }
    }

    /** A completed built-in selection is explicit and wins over stale WorldEdit selections. */
    public Region selection(Player player) {
        Location a = pos1.get(player.getUniqueId());
        Location b = pos2.get(player.getUniqueId());
        if (a != null && b != null && a.getWorld() != null && a.getWorld().equals(b.getWorld())) {
            return Region.of(a.getWorld().getName(),
                    a.getBlockX(), a.getBlockY(), a.getBlockZ(),
                    b.getBlockX(), b.getBlockY(), b.getBlockZ());
        }
        if (worldEditPresent()) {
            try {
                int[] sel = WorldEditHook.selection(player);
                if (sel != null) {
                    return Region.of(player.getWorld().getName(), sel[0], sel[1], sel[2], sel[3], sel[4], sel[5]);
                }
            } catch (Throwable ignored) {
                // WorldEdit API mismatch — fall through to the wand.
            }
        }
        return null;
    }
}
