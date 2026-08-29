package eu.fakemoon.screenshare;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

final class InspectionHolder implements InventoryHolder {
    private final UUID staff;
    private final UUID target;
    private final boolean ender;
    private Inventory inventory;

    InspectionHolder(UUID staff, UUID target, boolean ender) {
        this.staff = staff; this.target = target; this.ender = ender;
    }

    UUID staff() { return staff; }
    UUID target() { return target; }
    boolean ender() { return ender; }
    void inventory(Inventory inventory) { this.inventory = inventory; }

    @Override public @NotNull Inventory getInventory() { return inventory; }
}
