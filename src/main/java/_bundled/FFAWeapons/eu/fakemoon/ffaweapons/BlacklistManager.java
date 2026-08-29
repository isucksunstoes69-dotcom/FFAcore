package eu.fakemoon.ffaweapons;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Named zones where weapon abilities and passives are disabled (e.g. spawn). */
public final class BlacklistManager {

    private final JavaPlugin plugin;
    private final Map<String, Region> zones = new LinkedHashMap<>();
    private final AtomicReference<String> pendingSave = new AtomicReference<>();
    private final Object ioLock = new Object();

    public BlacklistManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private File file() {
        return new File(plugin.getDataFolder(), "blacklist.yml");
    }

    public void load() {
        zones.clear();
        File file = file();
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("zones");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null) continue;
            Region region = Region.fromList(section.getString("world", "world"), section.getIntegerList("region"));
            if (region != null) zones.put(name.toLowerCase(Locale.ROOT), region);
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Region> entry : zones.entrySet()) {
            yaml.set("zones." + entry.getKey() + ".world", entry.getValue().world());
            yaml.set("zones." + entry.getKey() + ".region", entry.getValue().toList());
        }
        pendingSave.set(yaml.saveToString());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String data = pendingSave.getAndSet(null);
            if (data == null) return;
            synchronized (ioLock) {
                write(data);
            }
        });
    }

    private void write(String data) {
        try {
            Files.writeString(file().toPath(), data, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save blacklist.yml: " + ex.getMessage());
        }
    }

    public void flushSync() {
        String data = pendingSave.getAndSet(null);
        if (data == null) return;
        synchronized (ioLock) {
            write(data);
        }
    }

    public void add(String name, Region region) {
        zones.put(name.toLowerCase(Locale.ROOT), region);
        save();
    }

    public boolean remove(String name) {
        boolean removed = zones.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) save();
        return removed;
    }

    public Map<String, Region> zones() {
        return zones;
    }

    /** True if weapon behavior is disabled at this location. */
    public boolean blocked(Location loc) {
        for (Region region : zones.values()) {
            if (region.contains(loc)) return true;
        }
        return false;
    }
}
