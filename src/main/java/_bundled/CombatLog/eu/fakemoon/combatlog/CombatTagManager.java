package eu.fakemoon.combatlog;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class CombatTagManager {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final CombatLogPlugin plugin;
    private final Map<UUID, State> tags = new HashMap<>();
    private BukkitTask ticker;

    CombatTagManager(CombatLogPlugin plugin) {
        this.plugin = plugin;
    }

    void startTicker() {
        if (ticker != null) ticker.cancel();
        int period = plugin.values().bossBarUpdateTicks();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    void tag(Player player, Player opponent) {
        tag(player, opponent == null ? null : opponent.getUniqueId(), plugin.values().durationSeconds());
    }

    void tag(Player player, UUID opponentId, int durationSeconds) {
        long now = System.nanoTime();
        State state = tags.get(player.getUniqueId());
        boolean newlyTagged = state == null || state.expiresAtNanos <= now;
        if (state != null && state.expiresAtNanos <= now) finish(player.getUniqueId(), false);

        long durationNanos = Math.max(1, durationSeconds) * NANOS_PER_SECOND;
        if (newlyTagged) {
            state = new State();
            tags.put(player.getUniqueId(), state);
        }
        state.startedAtNanos = now;
        state.expiresAtNanos = now + durationNanos;
        state.durationNanos = durationNanos;
        state.opponentId = opponentId;
        if (!isBlocked(player.getLocation())) state.lastSafeLocation = player.getLocation().clone();

        updateBar(player, state, now);
        if (newlyTagged && !plugin.values().taggedMessage().isEmpty()) {
            Text.message(player, plugin.values().taggedMessage(), player.getName(), opponentName(opponentId), durationSeconds);
        }
    }

    boolean isTagged(Player player) {
        return isTagged(player.getUniqueId());
    }

    boolean isTagged(UUID playerId) {
        State state = tags.get(playerId);
        if (state == null) return false;
        if (state.expiresAtNanos <= System.nanoTime()) {
            finish(playerId, true);
            return false;
        }
        return true;
    }

    long remainingSeconds(UUID playerId) {
        State state = tags.get(playerId);
        if (state == null) return 0;
        long remaining = state.expiresAtNanos - System.nanoTime();
        if (remaining <= 0) {
            finish(playerId, true);
            return 0;
        }
        return ceilSeconds(remaining);
    }

    void untag(UUID playerId, boolean announceExpired) {
        finish(playerId, announceExpired);
    }

    void updateLastSafe(Player player, Location location) {
        State state = tags.get(player.getUniqueId());
        if (state != null && !isBlocked(location)) state.lastSafeLocation = location.clone();
    }

    Location lastSafeLocation(Player player) {
        State state = tags.get(player.getUniqueId());
        return state == null || state.lastSafeLocation == null ? null : state.lastSafeLocation.clone();
    }

    void warnBlockedRegion(Player player) {
        State state = tags.get(player.getUniqueId());
        long now = System.nanoTime();
        if (state != null && now - state.lastBlockedWarningNanos < NANOS_PER_SECOND) return;
        if (state != null) state.lastBlockedWarningNanos = now;
        if (!plugin.values().blockedRegionMessage().isEmpty()) {
            Text.message(player, plugin.values().blockedRegionMessage());
        }
    }

    boolean handleLogout(Player player) {
        State state = tags.get(player.getUniqueId());
        if (state == null) return false;
        if (state.expiresAtNanos <= System.nanoTime()) {
            finish(player.getUniqueId(), false);
            return false;
        }

        finish(player.getUniqueId(), false);
        ConfigValues values = plugin.values();
        if (!values.logoutEnabled()) return false;

        if (values.logoutPunishment().equals("KILL") && !player.isDead() && player.getHealth() > 0.0) {
            player.setHealth(0.0);
        }
        if (!values.logoutBroadcast().isEmpty()) {
            Bukkit.getServer().sendMessage(Text.render(values.logoutBroadcast(), player.getName(), opponentName(state.opponentId), 0));
        }
        return true;
    }

    void shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (UUID playerId : new ArrayList<>(tags.keySet())) finish(playerId, false);
        tags.clear();
    }

    private void tick() {
        long now = System.nanoTime();
        for (Map.Entry<UUID, State> entry : new ArrayList<>(tags.entrySet())) {
            UUID playerId = entry.getKey();
            State state = entry.getValue();
            Player player = Bukkit.getPlayer(playerId);
            if (state.expiresAtNanos <= now) {
                finish(playerId, true);
            } else if (player == null || !player.isOnline()) {
                finish(playerId, false);
            } else {
                updateBar(player, state, now);
            }
        }
    }

    private void updateBar(Player player, State state, long now) {
        ConfigValues values = plugin.values();
        if (!values.bossBarEnabled()) {
            hideBar(player, state);
            return;
        }

        long remainingNanos = Math.max(1, state.expiresAtNanos - now);
        long seconds = ceilSeconds(remainingNanos);
        float progress = (float) Math.max(0.0, Math.min(1.0,
                (double) remainingNanos / (double) state.durationNanos));
        var title = Text.render(values.bossBarTitle(), player.getName(), opponentName(state.opponentId), seconds);

        if (state.bar == null) {
            state.bar = BossBar.bossBar(title, progress, values.bossBarColor(), values.bossBarOverlay());
            player.showBossBar(state.bar);
        } else {
            state.bar.name(title);
            state.bar.progress(progress);
            state.bar.color(values.bossBarColor());
            state.bar.overlay(values.bossBarOverlay());
        }
    }

    private void finish(UUID playerId, boolean announceExpired) {
        State state = tags.remove(playerId);
        if (state == null) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            hideBar(player, state);
            if (announceExpired && !plugin.values().expiredMessage().isEmpty()) {
                Text.message(player, plugin.values().expiredMessage());
            }
        }
    }

    private void hideBar(Player player, State state) {
        if (state.bar == null) return;
        player.hideBossBar(state.bar);
        state.bar = null;
    }

    private boolean isBlocked(Location location) {
        ConfigValues values = plugin.values();
        return values.worldGuardEnabled()
                && plugin.regionHook().matches(location, values.blockedRegions());
    }

    private static long ceilSeconds(long nanos) {
        return Math.max(1, (nanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
    }

    private static String opponentName(UUID opponentId) {
        if (opponentId == null) return "Unknown";
        Player opponent = Bukkit.getPlayer(opponentId);
        return opponent == null ? "Unknown" : opponent.getName();
    }

    private static final class State {
        private long startedAtNanos;
        private long expiresAtNanos;
        private long durationNanos;
        private UUID opponentId;
        private BossBar bar;
        private Location lastSafeLocation;
        private long lastBlockedWarningNanos;
    }
}
