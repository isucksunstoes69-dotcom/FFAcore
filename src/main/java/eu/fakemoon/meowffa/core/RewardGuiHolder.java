package eu.fakemoon.meowffa.core;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class RewardGuiHolder implements InventoryHolder {
    enum Type { DAILY, AFK }
    private final Type type;
    private Inventory inventory;
    RewardGuiHolder(Type type) { this.type = type; }
    Type type() { return type; }
    void inventory(Inventory inventory) { this.inventory = inventory; }
    @Override public Inventory getInventory() { return inventory; }
}
