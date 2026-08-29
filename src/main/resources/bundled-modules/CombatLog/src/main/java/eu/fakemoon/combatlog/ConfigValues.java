package eu.fakemoon.combatlog;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

record ConfigValues(
        int durationSeconds,
        Set<String> ignoredWorlds,
        Set<GameMode> ignoredGameModes,
        boolean worldGuardEnabled,
        boolean worldGuardRequired,
        Set<String> ignoredRegions,
        Set<String> blockedRegions,
        boolean preventTaggedEntry,
        boolean bossBarEnabled,
        String bossBarTitle,
        BossBar.Color bossBarColor,
        BossBar.Overlay bossBarOverlay,
        int bossBarUpdateTicks,
        boolean logoutEnabled,
        String logoutPunishment,
        String logoutBroadcast,
        Set<String> ignoredKickCauses,
        boolean blockCommands,
        Set<String> blockedCommands,
        String taggedMessage,
        String expiredMessage,
        String blockedRegionMessage,
        String blockedCommandMessage
) {

    static ConfigValues load(CombatLogPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        int duration = clamp(config.getInt("combat.duration-seconds", 15), 1, 3600);
        int updateTicks = clamp(config.getInt("bossbar.update-ticks", 2), 1, 20);

        EnumSet<GameMode> ignoredModes = EnumSet.noneOf(GameMode.class);
        for (String raw : config.getStringList("combat.ignored-gamemodes")) {
            try {
                ignoredModes.add(GameMode.valueOf(raw.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Ignoring unknown game mode in config: " + raw);
            }
        }

        BossBar.Color color = parseEnum(
                BossBar.Color.class,
                config.getString("bossbar.color", "RED"),
                BossBar.Color.RED,
                plugin,
                "bossbar.color"
        );
        BossBar.Overlay overlay = parseEnum(
                BossBar.Overlay.class,
                config.getString("bossbar.overlay", "PROGRESS"),
                BossBar.Overlay.PROGRESS,
                plugin,
                "bossbar.overlay"
        );

        return new ConfigValues(
                duration,
                normalized(config.getStringList("combat.ignored-worlds")),
                Set.copyOf(ignoredModes),
                config.getBoolean("worldguard.enabled", true),
                config.getBoolean("worldguard.required", false),
                normalized(config.getStringList("worldguard.ignored-regions")),
                normalized(config.getStringList("worldguard.blocked-regions")),
                config.getBoolean("worldguard.prevent-tagged-entry", true),
                config.getBoolean("bossbar.enabled", true),
                config.getString("bossbar.title", "<red><bold>COMBAT</bold> <white><seconds>s"),
                color,
                overlay,
                updateTicks,
                config.getBoolean("logout.enabled", true),
                config.getString("logout.punishment", "KILL").toUpperCase(Locale.ROOT),
                config.getString("logout.broadcast", "<red><player> logged out during combat."),
                normalized(config.getStringList("logout.ignored-kick-causes")),
                config.getBoolean("restrictions.block-commands", false),
                commandRoots(config.getStringList("restrictions.blocked-commands")),
                config.getString("messages.tagged", "<red>You are in combat with <white><opponent></white>!"),
                config.getString("messages.expired", "<green>You are no longer in combat."),
                config.getString("messages.blocked-region", "<red>You cannot enter that region while in combat."),
                config.getString("messages.blocked-command", "<red>You cannot use that command while in combat.")
        );
    }

    private static Set<String> normalized(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : values) {
            String value = raw.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) out.add(value);
        }
        return Set.copyOf(out);
    }

    private static Set<String> commandRoots(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : values) {
            String value = raw.trim().toLowerCase(Locale.ROOT);
            while (value.startsWith("/")) value = value.substring(1);
            int space = value.indexOf(' ');
            if (space >= 0) value = value.substring(0, space);
            int namespace = value.indexOf(':');
            if (namespace >= 0) value = value.substring(namespace + 1);
            if (!value.isEmpty()) out.add(value);
        }
        return Set.copyOf(out);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback,
                                                    CombatLogPlugin plugin, String path) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid " + path + " value '" + raw + "'; using " + fallback.name() + '.');
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
