package eu.fakemoon.onevone;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class RoomListener implements Listener {

    private final OneVOnePlugin plugin;
    private final RoomManager rooms;
    private final SelectionManager selection;

    public RoomListener(OneVOnePlugin plugin, RoomManager rooms, SelectionManager selection) {
        this.plugin = plugin;
        this.rooms = rooms;
        this.selection = selection;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPortalClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) return;
        Room room = rooms.roomAtEntrance(event.getClickedBlock().getLocation());
        if (room == null) return;
        if (selection.isWand(event.getItem())
                || (event.getPlayer().hasPermission("onevone.admin") && event.getItem() != null
                && event.getItem().getType() == Material.WOODEN_AXE)) return;
        event.setCancelled(true);
        rooms.join(room, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArenaInteraction(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        Room room = rooms.roomAt(event.getClickedBlock().getLocation());
        if (room == null || !room.isBusy()) return;
        if (event.getAction() == Action.PHYSICAL) {
            event.setCancelled(true);
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // Prevent doors, buttons, containers and other arena blocks changing,
        // but do not cancel the event so custom weapon right-clicks still work.
        event.setUseInteractedBlock(Event.Result.DENY);
        if (isBlockMutator(event.getItem())) event.setUseItemInHand(Event.Result.DENY);
    }

    private boolean isBlockMutator(ItemStack item) {
        if (item == null) return false;
        String name = item.getType().name();
        if (name.endsWith("_AXE") || name.endsWith("_HOE") || name.endsWith("_SHOVEL")) return true;
        return switch (item.getType()) {
            case BONE_MEAL, FLINT_AND_STEEL, FIRE_CHARGE, HONEYCOMB, SHEARS,
                    WATER_BUCKET, LAVA_BUCKET, POWDER_SNOW_BUCKET -> true;
            default -> false;
        };
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;
        handleMovement(event.getPlayer(), event.getFrom(), event.getTo(), event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (rooms.isInternalTeleport(event.getPlayer().getUniqueId())) return;
        handleMovement(event.getPlayer(), event.getFrom(), event.getTo(), event);
    }

    private void handleMovement(Player player, Location from, Location to, Cancellable event) {
        Room participantRoom = rooms.roomOf(player.getUniqueId());
        for (Room room : rooms.all()) {
            boolean fromInside = room.region().contains(from);
            boolean toInside = room.region().contains(to);
            if (participantRoom == room) {
                if (!toInside) {
                    event.setCancelled(true);
                    Util.msg(player, room.state() == Room.State.LOOTING
                            ? "<yellow>Finish looting - you will leave when the timer ends."
                            : "<red>You cannot leave this 1v1 room yet.");
                    return;
                }
            } else if (!fromInside && toInside && RoomManager.eligible(player)) {
                event.setCancelled(true);
                Util.msg(player, "<yellow>Right-click the stained-glass entrance to join this room.");
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        Player attacker = null;
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity causing = byEntity.getDamageSource().getCausingEntity();
            if (causing instanceof Player player) attacker = player;
        }
        Room attackerRoom = attacker == null ? null : rooms.roomOf(attacker.getUniqueId());
        if (!(event.getEntity() instanceof Player victim)) {
            if (attackerRoom != null) event.setCancelled(true);
            return;
        }
        Room victimRoom = rooms.roomOf(victim.getUniqueId());
        if (victimRoom == null) {
            if (attackerRoom != null) event.setCancelled(true);
            return;
        }
        if (victimRoom.state() != Room.State.FIGHTING) {
            event.setCancelled(true);
            return;
        }
        if (event instanceof EntityDamageByEntityEvent) {
            boolean validDuelHit = attacker != null && attacker != victim && attackerRoom == victimRoom
                    && victimRoom.fighters().contains(attacker.getUniqueId())
                    && victimRoom.fighters().contains(victim.getUniqueId());
            if (!validDuelHit) event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        Room room = rooms.roomOf(dead.getUniqueId());
        if (room == null) return;
        if (room.state() == Room.State.FIGHTING) {
            // Duel deaths always create real loot, even if keepInventory is enabled globally.
            if (event.getKeepInventory()) mergeMissingInventoryDrops(event, dead);
            event.setKeepInventory(false);
            event.setKeepLevel(false);
        }
        rooms.recordDeath(room, dead);
    }

    private void mergeMissingInventoryDrops(PlayerDeathEvent event, Player dead) {
        // A plugin may already have inserted custom drops. Count them toward an
        // identical inventory stack, then add only the missing quantity so the
        // inventory is neither erased nor duplicated when keepInventory was on.
        int originalDropCount = event.getDrops().size();
        int[] consumed = new int[originalDropCount];
        for (ItemStack item : dead.getInventory().getContents()) {
            if (item == null || item.getType().isAir()
                    || item.containsEnchantment(Enchantment.VANISHING_CURSE)) continue;
            int missing = item.getAmount();
            for (int index = 0; index < originalDropCount && missing > 0; index++) {
                ItemStack existing = event.getDrops().get(index);
                if (!existing.isSimilar(item)) continue;
                int available = Math.max(0, existing.getAmount() - consumed[index]);
                int covered = Math.min(missing, available);
                consumed[index] += covered;
                missing -= covered;
            }
            if (missing > 0) {
                ItemStack copy = item.clone();
                copy.setAmount(missing);
                event.getDrops().add(copy);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Location back = rooms.pendingReturn(event.getPlayer().getUniqueId());
        if (back != null && back.getWorld() != null) {
            event.setRespawnLocation(back);
            // Runs after every respawn listener, then removes the pending return
            // only once the teleport really succeeds.
            Bukkit.getScheduler().runTask(plugin, () -> rooms.restoreOrEvacuate(event.getPlayer()));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> rooms.restoreOrEvacuate(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        rooms.handleQuit(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (rooms.isProtectedBlock(event.getBlock()) && !canBuild(event.getPlayer(), event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (rooms.isProtectedBlock(event.getBlockPlaced())
                && !canBuild(event.getPlayer(), event.getBlockPlaced())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (rooms.isProtectedBlock(event.getBlock())
                || rooms.isProtectedBlock(event.getBlock().getRelative(event.getDirection()))
                || event.getBlocks().stream().anyMatch(block -> rooms.isProtectedBlock(block)
                || rooms.isProtectedBlock(block.getRelative(event.getDirection())))) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (rooms.isProtectedBlock(event.getBlock())
                || rooms.isProtectedBlock(event.getBlock().getRelative(event.getDirection()))
                || event.getBlocks().stream().anyMatch(block -> rooms.isProtectedBlock(block)
                || rooms.isProtectedBlock(block.getRelative(event.getDirection().getOppositeFace())))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(rooms::isProtectedBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(rooms::isProtectedBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (rooms.isProtectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (rooms.isProtectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (rooms.isProtectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (rooms.isProtectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (rooms.isProtectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (rooms.isProtectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (rooms.isProtectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        if (rooms.isProtectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (rooms.isProtectedBlock(event.getToBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        org.bukkit.block.Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        if (rooms.isProtectedBlock(target) && !canBuild(event.getPlayer(), target)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (rooms.isProtectedBlock(event.getBlockClicked())
                && !canBuild(event.getPlayer(), event.getBlockClicked())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (rooms.isProtectedBlock(event.getBlock())
                || event.getBlocks().stream().anyMatch(state -> rooms.isProtectedBlock(state.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (event.getBlocks().stream().anyMatch(state -> rooms.isProtectedBlock(state.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpongeAbsorb(SpongeAbsorbEvent event) {
        if (rooms.isProtectedBlock(event.getBlock())
                || event.getBlocks().stream().anyMatch(state -> rooms.isProtectedBlock(state.getBlock()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (rooms.isProtectedBlock(event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[]{
                org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN,
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST}) {
            if (rooms.isProtectedBlock(event.getBlock().getRelative(face))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private boolean canBuild(Player player, org.bukkit.block.Block block) {
        if (rooms.isPortalBlock(block) || !player.hasPermission("onevone.build")) return false;
        Room room = rooms.roomAt(block.getLocation());
        return room != null && !room.isBusy();
    }
}
