package eu.fakemoon.macerr;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /macerr item — a 45-slot bundle editor. Every entry is given to the selected
 * player on each reroll; the protected bottom row explains the behavior.
 */
public final class ItemGui implements InventoryHolder {

    private static final int EDITABLE_SLOTS = 45;

    private final RerollManager manager;
    private final Inventory inv;

    private ItemGui(RerollManager manager) {
        this.manager = manager;
        this.inv = Bukkit.createInventory(this, 54, Util.textKey("gui.title", Map.of()));

        List<ItemStack> entries = manager.pool();
        for (int slot = 0; slot < Math.min(entries.size(), EDITABLE_SLOTS); slot++) {
            inv.setItem(slot, entries.get(slot));
        }

        ItemStack filler = Util.named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = EDITABLE_SLOTS; slot < inv.getSize(); slot++) inv.setItem(slot, filler);
        inv.setItem(49, Util.named(Material.HOPPER,
                Util.formatKey("gui.bundle-title", Map.of()),
                Util.formatKey("gui.bundle-lore-1", Map.of()),
                Util.formatKey("gui.bundle-lore-2", Map.of()),
                Util.formatKey("gui.bundle-lore-3", Map.of())));
    }

    public static void open(RerollManager manager, Player player) {
        player.openInventory(new ItemGui(manager).inv);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inv;
    }

    public void click(InventoryClickEvent event) {
        int raw = event.getRawSlot();
        if (raw >= EDITABLE_SLOTS && raw < inv.getSize()) {
            event.setCancelled(true);
        }
    }

    public void drag(InventoryDragEvent event) {
        for (int raw : event.getRawSlots()) {
            if (raw >= EDITABLE_SLOTS && raw < inv.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    public void closed(InventoryCloseEvent event) {
        List<ItemStack> entries = new ArrayList<>();
        for (int slot = 0; slot < EDITABLE_SLOTS; slot++) {
            ItemStack item = inv.getItem(slot);
            if (!Util.isEmpty(item)) entries.add(item.clone());
        }
        manager.setPool(entries);
        if (entries.isEmpty()) {
            Util.msgKey(event.getPlayer(), "gui.cleared", Map.of());
        } else {
            Util.msgKey(event.getPlayer(), "gui.saved", Map.of(
                    "count", entries.size(), "items", entries.size() == 1 ? "item" : "items"));
        }
    }
}
