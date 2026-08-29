package eu.fakemoon.ffaweapons;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class WeaponsGuiListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof WeaponsGui gui) {
            gui.click(event);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof WeaponsGui) {
            event.setCancelled(true);
        }
    }
}
