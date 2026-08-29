package eu.fakemoon.meowffa.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

final class CoreText {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final JavaPlugin plugin;
    private YamlConfiguration messages;
    CoreText(JavaPlugin plugin) { this.plugin = plugin; reload(); }
    void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        messages = YamlConfiguration.loadConfiguration(file);
    }
    Component component(String key, Map<String, ?> values) {
        String raw = messages.getString(key, key);
        for (var e : values.entrySet()) raw = raw.replace("%" + e.getKey() + "%", String.valueOf(e.getValue()));
        return MM.deserialize(raw).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
    void send(CommandSender sender, String key, Map<String, ?> values) {
        sender.sendMessage(component("prefix", Map.of()).append(component(key, values)));
    }
}
