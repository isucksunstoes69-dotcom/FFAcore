package eu.fakemoon.meowffa.core;

import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;

final class CoreListener implements Listener {
    private final CoreManager manager;
    CoreListener(CoreManager manager) { this.manager = manager; }
    @EventHandler public void onDeath(PlayerDeathEvent event) { manager.death(event); }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) { var location = manager.deathRespawn(); if (location != null) event.setRespawnLocation(location); }
    @EventHandler public void onJoin(PlayerJoinEvent event) { manager.tick(); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { manager.save(); }
    @EventHandler public void onEntityInteract(PlayerInteractEntityEvent event) { manager.interactAfkNpc(event); }
    @EventHandler public void onWand(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getPlayer().hasPermission("meowffa.admin")) || !manager.isAfkWand(event.getItem()) || event.getAction() == Action.PHYSICAL || event.getClickedBlock() == null) return;
        manager.setAfkSelection(event.getPlayer(), event.getAction() == Action.LEFT_CLICK_BLOCK ? 1 : 2, event.getClickedBlock()); event.setCancelled(true);
        event.getPlayer().sendActionBar(net.kyori.adventure.text.Component.text(event.getAction() == Action.LEFT_CLICK_BLOCK ? "AFK pool position 1 set" : "AFK pool position 2 set"));
    }
    @EventHandler public void onRewardClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RewardGuiHolder)) return;
        if (event.isShiftClick() || event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY || event.getClick() == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK) event.setCancelled(true);
    }
    @EventHandler public void onRewardDrag(InventoryDragEvent event) { if (event.getView().getTopInventory().getHolder() instanceof RewardGuiHolder) event.setCancelled(true); }
    @EventHandler public void onRewardClose(InventoryCloseEvent event) { if (event.getInventory().getHolder() instanceof RewardGuiHolder holder) manager.saveRewardGui(holder); }
}
