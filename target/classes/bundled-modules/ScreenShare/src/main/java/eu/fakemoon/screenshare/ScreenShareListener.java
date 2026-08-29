package eu.fakemoon.screenshare;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class ScreenShareListener implements Listener {
    private final ScreenShareManager manager;
    private final Text text;

    ScreenShareListener(ScreenShareManager manager, Text text) { this.manager = manager; this.text = text; }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!manager.isFrozen(event.getPlayer()) || event.getTo() == null) return;
        if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ()) event.setTo(event.getFrom());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && manager.isFrozen(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!manager.isFrozen(event.getPlayer())) return;
        String command = event.getMessage().trim().split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (!command.equals("/ss") && !command.equals("/screenshare")) {
            event.setCancelled(true);
            text.send(event.getPlayer(), "blocked-command", java.util.Map.of());
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ConsentHolder holder
                && event.getWhoClicked() instanceof Player player
                && player.getUniqueId().equals(holder.target())) {
            event.setCancelled(true);
            manager.handleConsentClick(player, event.getRawSlot());
            return;
        }
        if (event.getWhoClicked() instanceof Player player && manager.isFrozen(player)) event.setCancelled(true);
        if (event.getView().getTopInventory().getHolder(false) instanceof InspectionHolder) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && manager.isFrozen(player)) event.setCancelled(true);
        if (event.getView().getTopInventory().getHolder(false) instanceof InspectionHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { manager.onQuit(event.getPlayer()); }
}
