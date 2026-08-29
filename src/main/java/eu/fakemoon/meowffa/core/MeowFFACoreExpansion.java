package eu.fakemoon.meowffa.core;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

final class MeowFFACoreExpansion extends PlaceholderExpansion {
    private final CoreManager manager;
    MeowFFACoreExpansion(CoreManager manager) { this.manager = manager; }
    @Override public @NotNull String getIdentifier() { return "meowffa"; }
    @Override public @NotNull String getAuthor() { return "fakemoon"; }
    @Override public @NotNull String getVersion() { return "1.0.0"; }
    @Override public boolean persist() { return true; }
    @Override public String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";
        return switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "kills" -> String.valueOf(manager.killsValue(player));
            case "deaths" -> String.valueOf(manager.deathsValue(player));
            case "kd" -> manager.kdValue(player);
            case "streak", "killstreak" -> String.valueOf(manager.streak(player));
            case "health" -> String.valueOf(manager.healthValue(player));
            case "ping" -> String.valueOf(manager.pingValue(player));
            default -> null;
        };
    }
}
