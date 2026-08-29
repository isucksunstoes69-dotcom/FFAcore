package eu.fakemoon.combatlog;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class CombatListener implements Listener {

    private final CombatLogPlugin plugin;
    private final CombatTagManager tags;
    private final Set<UUID> ignoredQuits = new HashSet<>();

    CombatListener(CombatLogPlugin plugin, CombatTagManager tags) {
        this.plugin = plugin;
        this.tags = tags;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || event.getFinalDamage() <= 0.0) return;
        Player attacker = resolveAttacker(event);
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        if (!canTag(attacker) || !canTag(victim)) return;

        ConfigValues values = plugin.values();
        if (values.worldGuardEnabled() && (plugin.regionHook().matches(attacker.getLocation(), values.ignoredRegions())
                || plugin.regionHook().matches(victim.getLocation(), values.ignoredRegions()))) {
            return;
        }

        tags.tag(attacker, victim);
        tags.tag(victim, attacker);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;
        handleBoundary(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        handleBoundary(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        ConfigValues values = plugin.values();
        if (!values.blockCommands() || !tags.isTagged(event.getPlayer())) return;
        String command = event.getMessage().substring(1).trim().toLowerCase(Locale.ROOT);
        int space = command.indexOf(' ');
        if (space >= 0) command = command.substring(0, space);
        int namespace = command.indexOf(':');
        if (namespace >= 0) command = command.substring(namespace + 1);
        if (!values.blockedCommands().contains(command)) return;
        event.setCancelled(true);
        if (!values.blockedCommandMessage().isEmpty()) {
            Text.message(event.getPlayer(), values.blockedCommandMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        String cause = event.getCause().name().toLowerCase(Locale.ROOT);
        if (plugin.values().ignoredKickCauses().contains(cause)) {
            ignoredQuits.add(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (plugin.isShuttingDown() || Bukkit.isStopping() || ignoredQuits.remove(playerId)) {
            tags.untag(playerId, false);
            return;
        }
        tags.handleLogout(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        tags.untag(event.getPlayer().getUniqueId(), false);
    }

    private void handleBoundary(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null || !changedBlock(event.getFrom(), to) || !tags.isTagged(player)) return;

        ConfigValues values = plugin.values();
        if (values.worldGuardEnabled() && values.preventTaggedEntry()
                && plugin.regionHook().matches(to, values.blockedRegions())) {
            event.setCancelled(true);
            event.setTo(event.getFrom());
            tags.warnBlockedRegion(player);
            return;
        }
        tags.updateLastSafe(player, to);
    }

    private boolean canTag(Player player) {
        ConfigValues values = plugin.values();
        if (player.hasPermission("combatlog.bypass")) return false;
        GameMode gameMode = player.getGameMode();
        if (values.ignoredGameModes().contains(gameMode)) return false;
        String world = player.getWorld().getName().toLowerCase(Locale.ROOT);
        return !values.ignoredWorlds().contains(world);
    }

    private static Player resolveAttacker(EntityDamageByEntityEvent event) {
        Entity causing = event.getDamageSource().getCausingEntity();
        Player fromCause = ownerOf(causing);
        if (fromCause != null) return fromCause;
        return ownerOf(event.getDamager());
    }

    private static Player ownerOf(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
            if (shooter instanceof Entity shooterEntity) return ownerOf(shooterEntity);
        }
        if (entity instanceof TNTPrimed tnt) return ownerOf(tnt.getSource());
        if (entity instanceof Tameable tameable) {
            AnimalTamer owner = tameable.getOwner();
            if (owner instanceof Player player) return player;
        }
        return null;
    }

    private static boolean changedBlock(Location from, Location to) {
        return from.getWorld() != to.getWorld()
                || from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ();
    }
}
