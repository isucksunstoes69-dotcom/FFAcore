package eu.fakemoon.ffaweapons;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/** Op-only weapon cabinet: click a weapon to receive a copy. */
public final class WeaponsGui implements InventoryHolder {

    private static final int[] SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    private final Weapons weapons;
    private final Inventory inv;
    private final String[] bySlot = new String[36];

    private WeaponsGui(Weapons weapons, Player player) {
        this.weapons = weapons;
        this.inv = Bukkit.createInventory(this, 36,
                Util.mm("<bold><gradient:#FF5555:#5555FF>ꜰꜰᴀ ᴡᴇᴀᴘᴏɴs</gradient></bold>"));
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        filler.editMeta(meta -> meta.displayName(Util.mm(" ")));
        for (int slot = 0; slot < 36; slot++) inv.setItem(slot, filler);
        int i = 0;
        for (String id : weapons.ids()) {
            if (weapons.isAdminOnly(id) && !player.hasPermission("ffaweapons.bbc")) continue;
            if (i >= SLOTS.length) break;
            bySlot[SLOTS[i]] = id;
            inv.setItem(SLOTS[i], weapons.item(id));
            i++;
        }
    }

    public static void open(Weapons weapons, Player player) {
        player.openInventory(new WeaponsGui(weapons, player).inv);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inv;
    }

    public void click(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        String id = slot >= 0 && slot < bySlot.length ? bySlot[slot] : null;
        if (id == null) return;
        if (weapons.isAdminOnly(id) && !player.hasPermission("ffaweapons.bbc")) {
            Util.msg(player, "<red>You are not allowed to receive this admin weapon.");
            return;
        }
        ItemStack item = weapons.item(id);
        if (item == null) return;
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            Util.msg(player, "<red>Your inventory is full.");
            return;
        }
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1f);
    }
}
