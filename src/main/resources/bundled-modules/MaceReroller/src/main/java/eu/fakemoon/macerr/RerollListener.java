package eu.fakemoon.macerr;

import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class RerollListener implements Listener {

    private final RerollManager manager;

    public RerollListener(RerollManager manager) {
        this.manager = manager;
    }

    // ------------------------------------------------------------ reroll triggers

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Server shutdown also fires quit for everyone — the holder keeps the item
        // across restarts, only real logouts reroll it.
        if (Bukkit.isStopping() || !manager.isEnabled()) return;
        Player player = event.getPlayer();
        boolean had = manager.strip(player);
        if (had || player.getUniqueId().equals(manager.holder())) {
            manager.withdrawBundle();
            manager.reroll(Bukkit.getConsoleSender(), null);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Anti-dupe: only the recorded holder may bring the item back online.
        if (event.getPlayer().getUniqueId().equals(manager.holder())) {
            manager.refreshGlow(event.getPlayer());
        } else {
            manager.strip(event.getPlayer());
        }
        if (manager.isEnabled() && manager.isPending() && manager.hasPool()
                && manager.canReceive(event.getPlayer())) {
            manager.reroll(Bukkit.getConsoleSender(), null);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        manager.onHolderEnteredBlockedRegion(event.getPlayer());
        if (manager.isEnabled() && manager.isPending() && manager.hasPool()
                && manager.canReceive(event.getPlayer())) {
            manager.reroll(Bukkit.getConsoleSender(), null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()
                && event.getFrom().getWorld().equals(event.getTo().getWorld()))) return;
        manager.onHolderEnteredBlockedRegion(event.getPlayer(), event.getTo());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        manager.onHolderEnteredBlockedRegion(event.getPlayer(), event.getTo());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!manager.isEnabled()) return;
        boolean inDrops = event.getDrops().removeIf(manager::isTracked);
        boolean held = manager.strip(event.getEntity()); // covers keepInventory
        if (manager.isRiggedAfterDeath(event.getEntity())) {
            manager.triggerRiggedDeath(event.getEntity());
            return;
        }
        if (inDrops || held || event.getEntity().getUniqueId().equals(manager.holder())) {
            manager.withdrawBundle();
            manager.reroll(Bukkit.getConsoleSender(), null);
        }
    }

    @EventHandler
    public void onItemBreak(PlayerItemBreakEvent event) {
        if (manager.isEnabled() && manager.isTracked(event.getBrokenItem())) {
            manager.onItemBroken(event.getPlayer());
        }
    }

    /**
     * A tracked ground copy was removed from the world — void, cactus, lava, fire,
     * explosion, despawn... anything but being picked up, chunk-unloaded, merged,
     * or removed by a plugin (that includes our own take-back) triggers a reroll.
     */
    @EventHandler
    public void onEntityRemove(EntityRemoveEvent event) {
        if (!manager.isEnabled()) return;
        if (!(event.getEntity() instanceof Item item)) return;
        if (!manager.isTracked(item.getItemStack())) return;
        EntityRemoveEvent.Cause cause = event.getCause();
        if (cause == EntityRemoveEvent.Cause.PICKUP
                || cause == EntityRemoveEvent.Cause.UNLOAD
                || cause == EntityRemoveEvent.Cause.MERGE
                || cause == EntityRemoveEvent.Cause.PLUGIN) {
            return;
        }
        manager.onDroppedItemLost();
    }

    // ------------------------------------------------------------ moving the item

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (!manager.isTracked(event.getItemDrop().getItemStack())) return;
        if (!manager.isEnabled()) return;
        // Dropping it doesn't leave a ground item: it vanishes and rerolls.
        // (Removing the entity fires EntityRemoveEvent with cause PLUGIN, which
        // the destruction handler ignores — no double reroll.)
        event.getItemDrop().remove();
        manager.onThrownAway(event.getPlayer());
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!manager.isTracked(event.getItem().getItemStack())) return;
        if (event.getEntity() instanceof Player player) {
            if (!manager.canPickUp(player)) {
                event.setCancelled(true);
                Util.msg(player, "<red>That weapon bundle belongs to the selected holder.");
                return;
            }
            manager.onPickedUp(player);
        } else {
            event.setCancelled(true); // no mob theft
        }
    }

    @EventHandler
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (manager.isTracked(event.getItem().getItemStack())) event.setCancelled(true);
    }

    // ------------------------------------------------------------ storage blocking

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder(false) instanceof ItemGui gui) {
            gui.click(event);
            return;
        }
        boolean blocked = false;
        if (event.getRawSlot() >= 0 && event.getRawSlot() < top.getSize()) {
            // Interacting inside the container itself.
            if (manager.isTracked(event.getCursor())) blocked = true;
            if (event.getClick() == ClickType.NUMBER_KEY
                    && manager.isTracked(event.getView().getBottomInventory().getItem(event.getHotbarButton()))) {
                blocked = true;
            }
            if (event.getClick() == ClickType.SWAP_OFFHAND
                    && manager.isTracked(event.getWhoClicked().getInventory().getItemInOffHand())) {
                blocked = true;
            }
        } else if (event.isShiftClick() && manager.isTracked(event.getCurrentItem())) {
            blocked = true; // shift-clicking it from the player inventory into the container
        }
        if (blocked) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                Util.msg(player, "<red>You can't store this item.");
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder(false) instanceof ItemGui gui) {
            gui.drag(event);
            return;
        }
        if (!manager.isTracked(event.getOldCursor())) return;
        for (int raw : event.getRawSlots()) {
            if (raw < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ItemGui gui) {
            gui.closed(event);
        }
    }

    @EventHandler
    public void onFrameInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame)) return;
        if (manager.isTracked(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
            Util.msg(event.getPlayer(), "<red>You can't store this item.");
        }
    }

    @EventHandler
    public void onArmorStandInteract(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof ArmorStand)) return;
        ItemStack held = event.getPlayer().getInventory().getItem(event.getHand());
        if (manager.isTracked(held)) {
            event.setCancelled(true);
            Util.msg(event.getPlayer(), "<red>You can't store this item.");
        }
    }
}
