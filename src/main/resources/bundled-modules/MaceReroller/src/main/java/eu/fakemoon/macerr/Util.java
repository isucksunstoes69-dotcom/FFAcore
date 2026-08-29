package eu.fakemoon.macerr;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Locale;

final class Util {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static Messages messages;

    private Util() {
    }

    static void initMessages(JavaPlugin plugin) {
        messages = new Messages(plugin);
    }

    static void reloadMessages() {
        if (messages != null) messages.reload();
    }

    static Component mm(String miniMessage) {
        return MM.deserialize(miniMessage).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    static void msg(CommandSender to, String miniMessage) {
        to.sendMessage(prefix().append(mm(miniMessage)));
    }

    static void broadcast(String miniMessage) {
        Bukkit.getServer().sendMessage(prefix().append(mm(miniMessage)));
    }

    static void msgKey(CommandSender to, String key, Map<String, ?> placeholders) {
        msg(to, format(key, placeholders));
    }

    static void broadcastKey(String key, Map<String, ?> placeholders) {
        broadcast(format(key, placeholders));
    }

    static Component textKey(String key, Map<String, ?> placeholders) {
        return mm(format(key, placeholders));
    }

    static String formatKey(String key, Map<String, ?> placeholders) {
        return format(key, placeholders);
    }

    private static Component prefix() {
        return mm(messages == null ? "<dark_gray>[<gold>MaceRR</gold>]</dark_gray> " : messages.get("prefix"));
    }

    private static String format(String key, Map<String, ?> placeholders) {
        return messages == null ? key : messages.format(key, placeholders);
    }

    static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    static String pretty(Material material) {
        String name = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    static String toBase64(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    static ItemStack fromBase64(String data) {
        if (data == null || data.isEmpty()) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
        } catch (Exception ex) {
            return null;
        }
    }

    /** Parses "1d2h30m", "90m", "45s", plain seconds, or "none"/"0". Returns -1 if invalid. */
    static long parseDurationSeconds(String input) {
        String s = input.toLowerCase(Locale.ROOT).trim();
        if (s.equals("none") || s.equals("0")) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*([dhms])").matcher(s);
        long total = 0;
        boolean any = false;
        while (m.find()) {
            any = true;
            long value = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "d" -> value * 86400;
                case "h" -> value * 3600;
                case "m" -> value * 60;
                default -> value;
            };
        }
        if (any) return total;
        try {
            long plain = Long.parseLong(s);
            return plain >= 0 ? plain : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    static ItemStack named(Material material, String nameMm, String... loreMm) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(mm(nameMm));
            if (loreMm.length > 0) meta.lore(Arrays.stream(loreMm).map(Util::mm).toList());
        });
        return item;
    }
}
