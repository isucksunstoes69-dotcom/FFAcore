package eu.fakemoon.screenshare;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class Text {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final JavaPlugin plugin;
    private YamlConfiguration messages;

    Text(JavaPlugin plugin) { this.plugin = plugin; reload(); }

    void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        messages = YamlConfiguration.loadConfiguration(file);
        try (InputStream stream = plugin.getResource("messages.yml")) {
            if (stream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                messages.addDefaults(defaults);
                messages.options().copyDefaults(true);
                messages.save(file);
            }
        } catch (Exception error) {
            plugin.getLogger().warning("Could not merge ScreenShare message defaults: " + error.getMessage());
        }
    }

    Component component(String key, Map<String, ?> values) {
        String raw = messages.getString(key, key);
        for (var entry : values.entrySet()) raw = raw.replace("%" + entry.getKey() + "%", String.valueOf(entry.getValue()));
        return MM.deserialize(raw).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    void send(CommandSender sender, String key, Map<String, ?> values) {
        sender.sendMessage(component("prefix", Map.of()).append(component(key, values)));
    }
}
