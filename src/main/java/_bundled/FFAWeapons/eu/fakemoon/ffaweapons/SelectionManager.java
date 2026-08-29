package eu.fakemoon.ffaweapons;

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
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Region selection: prefers the admin's WorldEdit/FAWE selection, falls back to
 * the plugin's own golden-shovel wand (left click = pos1, right click = pos2).
 */
public final class SelectionManager implements Listener {

    private final NamespacedKey wandKey;
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();

    public SelectionManager(JavaPlugin plugin) {
        this.wandKey = new NamespacedKey(plugin, "wand");
    }

    public boolean worldEditPresent() {
        return Bukkit.getPluginManager().getPlugin("WorldEdit") != null
                || Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
    }

    public ItemStack wand() {
        ItemStack item = new ItemStack(Material.GOLDEN_SHOVEL);
        item.editMeta(meta -> {
            meta.displayName(Util.mm("<gold><bold>Blacklist Wand</bold></gold>"));
            meta.lore(List.of(
                    Util.mm("<gray>Left click a block: <white>pos1</white>"),
                    Util.mm("<gray>Right click a block: <white>pos2</white>")));
            meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.getType() == Material.GOLDEN_SHOVEL && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
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

    /** The admin's current selection: WorldEdit/FAWE first, then the built-in wand. */
    public Region selection(Player player) {
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
        Location a = pos1.get(player.getUniqueId());
        Location b = pos2.get(player.getUniqueId());
        if (a == null || b == null || a.getWorld() == null || !a.getWorld().equals(b.getWorld())) return null;
        return Region.of(a.getWorld().getName(),
                a.getBlockX(), a.getBlockY(), a.getBlockZ(),
                b.getBlockX(), b.getBlockY(), b.getBlockZ());
    }
}
