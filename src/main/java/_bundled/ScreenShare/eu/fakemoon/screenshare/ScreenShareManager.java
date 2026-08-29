package eu.fakemoon.screenshare;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ScreenShareManager {
    private static final class Session {
        final UUID staff;
        final UUID target;
        final long expiresAt;
        boolean accepted;
        Session(UUID staff, UUID target, long expiresAt) { this.staff = staff; this.target = target; this.expiresAt = expiresAt; }
    }

    private final JavaPlugin plugin;
    private final Text text;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, List<String>> notes = new HashMap<>();

    ScreenShareManager(JavaPlugin plugin, Text text) { this.plugin = plugin; this.text = text; }

    void start() {
        long period = 20L;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    boolean request(Player staff, Player target) {
        if (staff.getUniqueId().equals(target.getUniqueId()) || target.hasPermission("screenshare.bypass")) return false;
        if (sessions.containsKey(target.getUniqueId()) || sessions.containsKey(staff.getUniqueId())) return false;
        long expires = System.currentTimeMillis() + plugin.getConfig().getLong("session-duration-seconds", 600) * 1000L;
        Session session = new Session(staff.getUniqueId(), target.getUniqueId(), expires);
        sessions.put(target.getUniqueId(), session);
        showBar(session);
        openConsent(target);
        text.send(staff, "staff-requested", Map.of("target", target.getName()));
        text.send(target, "target-request", Map.of());
        audit("REQUEST", staff, target, "");
        return true;
    }

    private void openConsent(Player target) {
        ConsentHolder holder = new ConsentHolder(target.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 27, text.component("consent-title", Map.of()));
        holder.inventory(inventory);
        inventory.setItem(11, button(Material.LIME_WOOL, "consent-accept", "consent-accept-lore"));
        inventory.setItem(13, button(Material.YELLOW_WOOL, "consent-admit", "consent-admit-lore"));
        inventory.setItem(15, button(Material.RED_WOOL, "consent-deny", "consent-deny-lore"));
        target.openInventory(inventory);
    }

    private ItemStack button(Material material, String nameKey, String loreKey) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(text.component(nameKey, Map.of()));
            meta.lore(List.of(text.component(loreKey, Map.of())));
        });
        return item;
    }

    void handleConsentClick(Player target, int slot) {
        if (slot == 11) accept(target);
        else if (slot == 13) admit(target);
        else if (slot == 15) deny(target);
    }

    boolean accept(Player target) {
        Session session = sessions.get(target.getUniqueId());
        if (session == null) return false;
        session.accepted = true;
        target.closeInventory();
        Player staff = Bukkit.getPlayer(session.staff);
        if (staff != null) text.send(staff, "accepted", Map.of("target", target.getName()));
        sendDiscord(target, staff);
        audit("ACCEPT", staff, target, "");
        return true;
    }

    boolean admit(Player target) {
        Session session = sessions.get(target.getUniqueId());
        if (session == null) return false;
        Player staff = Bukkit.getPlayer(session.staff);
        target.closeInventory();
        if (staff != null) text.send(staff, "admitted", Map.of("target", target.getName()));
        text.send(target, "admitted-self", Map.of());
        sendDiscord(target, staff);
        close(target.getUniqueId(), false);
        audit("ADMIT", staff, target, "");
        return true;
    }

    private void sendDiscord(Player target, Player staff) {
        String url = plugin.getConfig().getString("discord-url", "").trim();
        if (url.isEmpty() || url.contains("CHANGE-ME")) return;
        Map<String, String> values = Map.of("url", url);
        if (target != null) text.send(target, "discord-link", values);
        if (staff != null) text.send(staff, "discord-link-staff", values);
    }

    boolean deny(Player target) {
        Session session = sessions.get(target.getUniqueId());
        if (session == null) return false;
        Player staff = Bukkit.getPlayer(session.staff);
        target.closeInventory();
        if (staff != null) text.send(staff, "denied", Map.of("target", target.getName()));
        close(target.getUniqueId(), false);
        audit("DENY", staff, target, "");
        return true;
    }

    boolean canInspect(Player staff, Player target) {
        Session session = sessions.get(target.getUniqueId());
        return session != null && session.accepted && session.staff.equals(staff.getUniqueId());
    }

    boolean isFrozen(Player player) { return sessions.containsKey(player.getUniqueId()); }

    void openInventory(Player staff, Player target, boolean ender) {
        if (!canInspect(staff, target)) { text.send(staff, "not-found", Map.of()); return; }
        InspectionHolder holder = new InspectionHolder(staff.getUniqueId(), target.getUniqueId(), ender);
        String key = ender ? "ender-title" : "inspection-title";
        Inventory inventory = Bukkit.createInventory(holder, ender ? 27 : 54,
                text.component(key, Map.of("target", target.getName())));
        holder.inventory(inventory);
        if (ender) {
            for (int i = 0; i < target.getEnderChest().getSize(); i++) inventory.setItem(i, clone(target.getEnderChest().getItem(i)));
        } else {
            for (int i = 0; i < 36; i++) inventory.setItem(i, clone(target.getInventory().getItem(i)));
            for (int i = 0; i < 4; i++) inventory.setItem(45 + i, clone(target.getInventory().getArmorContents()[3 - i]));
            inventory.setItem(49, clone(target.getInventory().getItemInOffHand()));
            inventory.setItem(53, infoItem(target));
        }
        staff.openInventory(inventory);
        audit(ender ? "ENDER_INSPECT" : "INVENTORY_INSPECT", staff, target, "");
    }

    private ItemStack infoItem(Player target) {
        ItemStack item = new ItemStack(Material.BOOK);
        item.editMeta(meta -> {
            meta.displayName(text.component("inspection-title", Map.of("target", target.getName())));
            List<String> lore = new ArrayList<>();
            lore.add("World: " + target.getWorld().getName());
            lore.add("Location: " + target.getLocation().getBlockX() + ", " + target.getLocation().getBlockY() + ", " + target.getLocation().getBlockZ());
            lore.add("Gamemode: " + target.getGameMode().name());
            lore.add("Health: " + String.format(java.util.Locale.ROOT, "%.1f", target.getHealth()));
            lore.add("Effects: " + target.getActivePotionEffects().size());
            if (plugin.getConfig().getBoolean("show-address-to-staff", false) && target.getAddress() != null) lore.add("Address: " + target.getAddress().getAddress().getHostAddress());
            meta.lore(lore.stream().map(Component::text).toList());
        });
        return item;
    }

    void addNote(Player staff, Player target, String note) {
        if (!canInspect(staff, target)) { text.send(staff, "not-found", Map.of()); return; }
        notes.computeIfAbsent(target.getUniqueId(), ignored -> new ArrayList<>()).add(staff.getName() + ": " + note);
        text.send(staff, "note-saved", Map.of());
        audit("NOTE", staff, target, note);
    }

    void closeForStaff(Player staff, Player target) {
        Session session = sessions.get(target.getUniqueId());
        if (session == null || !session.staff.equals(staff.getUniqueId())) { text.send(staff, "not-found", Map.of()); return; }
        close(target.getUniqueId(), true);
        text.send(staff, "closed", Map.of());
    }

    void onQuit(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) close(player.getUniqueId(), false);
        for (Session other : new ArrayList<>(sessions.values())) if (other.staff.equals(player.getUniqueId())) close(other.target, false);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Session session : new ArrayList<>(sessions.values())) {
            if (now >= session.expiresAt) {
                Player target = Bukkit.getPlayer(session.target);
                if (target != null) text.send(target, "expired", Map.of());
                close(session.target, false);
                continue;
            }
            BossBar bar = bars.get(session.target);
            if (bar != null) bar.setProgress(Math.max(0.0, Math.min(1.0, (session.expiresAt - now) / (double) (plugin.getConfig().getLong("session-duration-seconds", 600) * 1000L))));
        }
    }

    private void showBar(Session session) {
        BossBar bar = Bukkit.createBossBar("SCREENSHARE • FROZEN", BarColor.RED, BarStyle.SOLID);
        bar.setProgress(1.0);
        Player target = Bukkit.getPlayer(session.target), staff = Bukkit.getPlayer(session.staff);
        if (target != null) bar.addPlayer(target);
        if (staff != null) bar.addPlayer(staff);
        bars.put(session.target, bar);
    }

    private void close(UUID targetId, boolean notify) {
        Session session = sessions.remove(targetId);
        if (session == null) return;
        BossBar bar = bars.remove(targetId);
        if (bar != null) bar.removeAll();
        Player target = Bukkit.getPlayer(targetId);
        if (notify && target != null) text.send(target, "closed", Map.of());
        audit("CLOSE", Bukkit.getPlayer(session.staff), target, "");
    }

    void shutdown() { for (UUID target : new ArrayList<>(sessions.keySet())) close(target, false); }

    private static ItemStack clone(ItemStack item) { return item == null ? null : item.clone(); }

    private void audit(String action, Player staff, Player target, String detail) {
        if (!plugin.getConfig().getBoolean("audit-log", true)) return;
        String line = System.currentTimeMillis() + " | " + action + " | staff=" + (staff == null ? "-" : staff.getName()) + " | target=" + (target == null ? "-" : target.getName()) + " | " + detail.replace('\n', ' ');
        try { Files.writeString(new File(plugin.getDataFolder(), "audit.log").toPath(), line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException ex) { plugin.getLogger().warning("Could not write audit.log: " + ex.getMessage()); }
    }
}
