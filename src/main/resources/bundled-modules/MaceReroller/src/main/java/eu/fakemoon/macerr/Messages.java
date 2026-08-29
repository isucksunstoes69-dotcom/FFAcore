package eu.fakemoon.macerr;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

/** Loads all configurable MaceReroller text from messages.yml. */
final class Messages {

    private final JavaPlugin plugin;
    private FileConfiguration config;

    Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        config = YamlConfiguration.loadConfiguration(file);
    }

    String get(String key) {
        return config.getString(key, key);
    }

    String format(String key, Map<String, ?> placeholders) {
        String value = get(key);
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            value = value.replace("%" + entry.getKey() + "%", String.valueOf(entry.getValue()));
        }
        return value;
    }
}
