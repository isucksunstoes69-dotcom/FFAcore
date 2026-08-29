package eu.fakemoon.meowffa.core;

import eu.fakemoon.altarkits.AltarKitsPlugin;
import eu.fakemoon.combatlog.CombatLogPlugin;
import eu.fakemoon.ffaweapons.FFAWeaponsPlugin;
import eu.fakemoon.macerr.MaceRRPlugin;
import eu.fakemoon.onevone.OneVOnePlugin;
import eu.fakemoon.screenshare.ScreenSharePlugin;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Boots the formerly separate modules inside this plugin's lifecycle. */
final class EmbeddedModules {
    private final MeowFFACorePlugin host;
    private final Map<String, JavaPlugin> modules = new LinkedHashMap<>();
    private static MeowFFACorePlugin activeHost;

    EmbeddedModules(MeowFFACorePlugin host) { this.host = host; }

    void enable() {
        activeHost = host;
        start("Kits", allocate(KitsHost.class));
        start("FFAWeapons", allocate(FfaHost.class));
        start("MaceReroller", allocate(MaceHost.class));
        start("OneVOneRoom", allocate(OneVOneHost.class));
        start("ScreenShare", allocate(ScreenShareHost.class));
        start("CombatLog", allocate(CombatHost.class));
    }

    boolean isEnabled(String name) {
        JavaPlugin plugin = modules.get(name);
        return plugin != null && plugin.isEnabled();
    }

    java.util.List<String> complete(String command, org.bukkit.command.CommandSender sender, String[] args) {
        PluginCommand pluginCommand = host.getCommand(command);
        if (pluginCommand == null) return java.util.List.of();
        org.bukkit.command.TabCompleter completer = pluginCommand.getTabCompleter();
        if (completer == null && pluginCommand.getExecutor() instanceof org.bukkit.command.TabCompleter tab) completer = tab;
        if (completer == null) return java.util.List.of();
        java.util.List<String> result = completer.onTabComplete(sender, pluginCommand, command, args);
        if (result != null) return result;
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(java.util.Locale.ROOT);
        return org.bukkit.Bukkit.getOnlinePlayers().stream().map(org.bukkit.entity.Player::getName)
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)).toList();
    }

    void disable() {
        var copy = modules.values().toArray(JavaPlugin[]::new);
        for (int i = copy.length - 1; i >= 0; i--) {
            JavaPlugin module = copy[i];
            if (!module.isEnabled()) continue;
            try { module.setEnabled(false); } catch (Throwable error) {
                host.getLogger().warning("Embedded " + module.getName() + " shutdown failed: " + error.getMessage());
            }
        }
        modules.clear();
        activeHost = null;
    }

    void reload() {
        host.getServer().dispatchCommand(host.getServer().getConsoleSender(), "ffaweapons reload");
        host.getServer().dispatchCommand(host.getServer().getConsoleSender(), "macerr reload");
        host.getServer().dispatchCommand(host.getServer().getConsoleSender(), "combatlog reload");
        JavaPlugin screenShare = modules.get("ScreenShare");
        if (screenShare instanceof eu.fakemoon.screenshare.ScreenSharePlugin ss) ss.reloadModule();
    }

    private void start(String key, JavaPlugin module) {
        try {
            String path = "bundled-modules/" + key + "/";
            InputStream descriptorStream = resource(key, "plugin.yml");
            if (descriptorStream == null) throw new IOException("missing bundled plugin.yml");
            var descriptor = new org.bukkit.plugin.PluginDescriptionFile(descriptorStream);
            File data = new File(host.getDataFolder(), "modules/" + key);
            File legacy = new File(host.getDataFolder().getParentFile(), key);
            migrateLegacyData(legacy, data);
            if (!data.exists() && !data.mkdirs()) throw new IOException("could not create " + data);
            module.init(host.getPluginLoader(), host.getServer(), descriptor, data,
                    new File(host.getDataFolder(), "MeowFFACore-embedded.jar"),
                    EmbeddedModules.class.getClassLoader());
            if (key.equals("ScreenShare")) {
                String discord = host.getConfig().getString("discord-url", "");
                String current = module.getConfig().getString("discord-url", "");
                if (!discord.isBlank() && (current.isBlank() || current.contains("CHANGE-ME"))) {
                    module.getConfig().set("discord-url", discord);
                    module.saveConfig();
                }
            }
            module.setEnabled(true);
            modules.put(key, module);
            attachCommands(module, descriptor.getCommands().keySet());
            host.getLogger().info("Embedded " + key + " enabled.");
        } catch (Throwable error) {
            try { module.setEnabled(false); } catch (Throwable ignored) { }
            host.getLogger().severe("Embedded " + key + " failed to start: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private void migrateLegacyData(File legacy, File target) throws IOException {
        if (!legacy.isDirectory()) return;
        Files.walk(legacy.toPath()).forEach(source -> {
            try {
                Path relative = legacy.toPath().relativize(source);
                Path destination = target.toPath().resolve(relative);
                if (Files.isDirectory(source)) Files.createDirectories(destination);
                else if (Files.notExists(destination)) Files.copy(source, destination);
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        });
    }

    private void attachCommands(JavaPlugin module, Iterable<String> names) {
        for (String name : names) {
            PluginCommand embedded = module.getCommand(name);
            PluginCommand core = host.getCommand(name);
            if (embedded == null || core == null) continue;
            core.setExecutor(embedded.getExecutor());
            core.setTabCompleter(embedded.getTabCompleter());
        }
    }

    private static InputStream resource(String module, String name) {
        return EmbeddedModules.class.getClassLoader().getResourceAsStream("bundled-modules/" + module + "/" + name);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) {
        try {
            var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not allocate embedded module", error);
        }
    }

    private static final class KitsHost extends AltarKitsPlugin {
        @Override public InputStream getResource(String name) { return resource("Kits", name); }
        @Override public PluginCommand getCommand(String name) { return activeHost.getCommand(name); }
    }
    private static final class FfaHost extends FFAWeaponsPlugin {
        @Override public InputStream getResource(String name) { return resource("FFAWeapons", name); }
        @Override public PluginCommand getCommand(String name) { return activeHost.getCommand(name); }
    }
    private static final class MaceHost extends MaceRRPlugin {
        @Override public InputStream getResource(String name) { return resource("MaceReroller", name); }
        @Override public PluginCommand getCommand(String name) { return activeHost.getCommand(name); }
    }
    private static final class OneVOneHost extends OneVOnePlugin {
        @Override public InputStream getResource(String name) { return resource("OneVOneRoom", name); }
        @Override public PluginCommand getCommand(String name) { return activeHost.getCommand(name); }
    }
    private static final class ScreenShareHost extends ScreenSharePlugin {
        @Override public InputStream getResource(String name) { return resource("ScreenShare", name); }
        @Override public PluginCommand getCommand(String name) { return activeHost.getCommand(name); }
    }
    private static final class CombatHost extends CombatLogPlugin {
        @Override public InputStream getResource(String name) { return resource("CombatLog", name); }
        @Override public PluginCommand getCommand(String name) { return activeHost.getCommand(name); }
    }
}
