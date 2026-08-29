package eu.fakemoon.screenshare;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

final class ConsentHolder implements InventoryHolder {
    private final UUID target;
    private Inventory inventory;

    ConsentHolder(UUID target) { this.target = target; }
    UUID target() { return target; }
    void inventory(Inventory inventory) { this.inventory = inventory; }
    @Override public Inventory getInventory() { return inventory; }
}
