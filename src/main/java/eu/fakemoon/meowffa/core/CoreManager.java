package eu.fakemoon.meowffa.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

final class CoreManager {
    private final JavaPlugin plugin;
    private final CoreText text;
    private final Map<UUID, Integer> tokens = new HashMap<>();
    private final Map<UUID, Integer> streaks = new HashMap<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private final Map<UUID, Long> daily = new HashMap<>();
    private final Map<UUID, Integer> bounties = new HashMap<>();
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, Set<String>> sidebarEntries = new HashMap<>();
    private final Set<UUID> scoreboardHidden = new HashSet<>();
    private final Map<UUID, int[]> afkSelections = new HashMap<>();
    private final Map<UUID, Long> afkRewardTimes = new HashMap<>();
    private final Set<UUID> afkPlayers = new HashSet<>();
    private final File dataFile;
    private final NamespacedKey afkNpcKey;

    CoreManager(JavaPlugin plugin, CoreText text) {
        this.plugin = plugin;
        this.text = text;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.afkNpcKey = new NamespacedKey(plugin, "afk_warp_npc");
        load();
    }

    void start() {
        long period = Math.max(10L, plugin.getConfig().getLong("scoreboard.update-ticks", 20L));
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, period);
        if (plugin.getServer().getPluginManager().isPluginEnabled("TAB")) plugin.getLogger().info("TAB detected; MeowFFACore is supplying the sidebar, tab header/footer, and health nametags.");
    }

    Location spawn() { return readLocation("spawn"); }
    Location afk() { return readLocation("afk"); }

    Location deathRespawn() {
        String worldName = plugin.getConfig().getString("death-respawn.world", "world");
        World world = Bukkit.getWorld(worldName); return world == null ? null : new Location(world,
                plugin.getConfig().getDouble("death-respawn.x", 662), plugin.getConfig().getDouble("death-respawn.y", 108),
                plugin.getConfig().getDouble("death-respawn.z", 999));
    }

    private Location readLocation(String root) {
        String worldName = plugin.getConfig().getString(root + ".world", "");
        World world = Bukkit.getWorld(worldName);
        if (world == null || worldName.isBlank()) return null;
        return new Location(world, plugin.getConfig().getDouble(root + ".x"), plugin.getConfig().getDouble(root + ".y"), plugin.getConfig().getDouble(root + ".z"), (float) plugin.getConfig().getDouble(root + ".yaw"), (float) plugin.getConfig().getDouble(root + ".pitch"));
    }

    void setSpawn(Player player) { saveLocation("spawn", player.getLocation()); }
    void setAfk(Player player) { saveLocation("afk", player.getLocation()); }

    ItemStack afkWand() {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        var meta = wand.getItemMeta(); meta.displayName(Component.text("AFK Pool Wand"));
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "afk_pool_wand"), PersistentDataType.BYTE, (byte) 1); wand.setItemMeta(meta); return wand;
    }
    boolean isAfkWand(ItemStack item) { return item != null && item.getType() == Material.BLAZE_ROD && item.getItemMeta() != null && item.getItemMeta().getPersistentDataContainer().has(new NamespacedKey(plugin, "afk_pool_wand"), PersistentDataType.BYTE); }
    void setAfkSelection(Player player, int point, org.bukkit.block.Block block) {
        int[] current = afkSelections.computeIfAbsent(player.getUniqueId(), ignored -> new int[7]);
        if (point == 1) { current[0] = block.getX(); current[1] = block.getY(); current[2] = block.getZ(); current[6] |= 1; }
        else { current[3] = block.getX(); current[4] = block.getY(); current[5] = block.getZ(); current[6] |= 2; }
    }
    boolean createAfkPool(Player player) {
        int[] selected = null;
        if (Bukkit.getPluginManager().isPluginEnabled("WorldEdit") || Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit")) selected = CoreWorldEdit.selection(player);
        if (selected == null) selected = afkSelections.get(player.getUniqueId());
        if (selected == null || selected.length < 6 || (selected.length > 6 && selected[6] != 3)) return false;
        plugin.getConfig().set("afkpool.world", player.getWorld().getName()); plugin.getConfig().set("afkpool.min", List.of(selected[0], selected[1], selected[2])); plugin.getConfig().set("afkpool.max", List.of(selected[3], selected[4], selected[5])); plugin.getConfig().set("afkpool.enabled", true); plugin.saveConfig(); return true;
    }
    void removeAfkPool() { plugin.getConfig().set("afkpool.enabled", false); plugin.saveConfig(); }

    void openRewardGui(Player player, RewardGuiHolder.Type type) {
        RewardGuiHolder holder = new RewardGuiHolder(type); Inventory inv = Bukkit.createInventory(holder, 54, Component.text(type == RewardGuiHolder.Type.DAILY ? "Daily Rewards" : "AFK Pool Rewards")); holder.inventory(inv);
        List<ItemStack> rewards = rewards(type); for (int i = 0; i < Math.min(54, rewards.size()); i++) if (rewards.get(i) != null) inv.setItem(i, rewards.get(i).clone()); player.openInventory(inv);
    }
    List<ItemStack> rewards(RewardGuiHolder.Type type) { List<?> raw = plugin.getConfig().getList(type == RewardGuiHolder.Type.DAILY ? "daily.rewards" : "afkpool.rewards", List.of()); List<ItemStack> out = new ArrayList<>(); for (Object value : raw) if (value instanceof ItemStack stack) out.add(stack); return out; }
    void saveRewardGui(RewardGuiHolder holder) { List<ItemStack> out = new ArrayList<>(); for (ItemStack item : holder.getInventory().getContents()) if (item != null && item.getType() != Material.AIR) out.add(item.clone()); plugin.getConfig().set(holder.type() == RewardGuiHolder.Type.DAILY ? "daily.rewards" : "afkpool.rewards", out); plugin.saveConfig(); }

    private ItemStack randomReward(RewardGuiHolder.Type type) { List<ItemStack> list = rewards(type); return list.isEmpty() ? null : list.get(ThreadLocalRandom.current().nextInt(list.size())).clone(); }
    private boolean insideAfkPool(Player player) {
        if (!plugin.getConfig().getBoolean("afkpool.enabled", false) || !player.getWorld().getName().equalsIgnoreCase(plugin.getConfig().getString("afkpool.world", ""))) return false;
        List<Integer> min = plugin.getConfig().getIntegerList("afkpool.min"), max = plugin.getConfig().getIntegerList("afkpool.max"); if (min.size() < 3 || max.size() < 3) return false;
        Location l = player.getLocation(); return l.getBlockX() >= Math.min(min.get(0), max.get(0)) && l.getBlockX() <= Math.max(min.get(0), max.get(0)) && l.getBlockY() >= Math.min(min.get(1), max.get(1)) && l.getBlockY() <= Math.max(min.get(1), max.get(1)) && l.getBlockZ() >= Math.min(min.get(2), max.get(2)) && l.getBlockZ() <= Math.max(min.get(2), max.get(2));
    }
    private void tickAfkReward(Player player) {
        UUID uuid = player.getUniqueId();
        if (!insideAfkPool(player)) {
            if (afkPlayers.remove(uuid)) player.sendActionBar(Component.empty());
            afkRewardTimes.remove(uuid);
            return;
        }
        long interval = Math.max(1, plugin.getConfig().getLong("afkpool.reward-interval-seconds", 300)) * 1000L;
        long now = System.currentTimeMillis();
        if (afkPlayers.add(uuid)) { afkRewardTimes.put(uuid, now + interval); text.send(player, "afk-entered", Map.of()); }
        long nextReward = afkRewardTimes.getOrDefault(uuid, now + interval);
        if (now >= nextReward) {
            ItemStack reward = randomReward(RewardGuiHolder.Type.AFK);
            if (reward != null) { player.getInventory().addItem(reward); text.send(player, "afk-reward", Map.of()); }
            nextReward = now + interval; afkRewardTimes.put(uuid, nextReward);
        }
        long remaining = Math.max(0L, (nextReward - now + 999L) / 1000L);
        player.sendActionBar(text.component("afk-next-reward", Map.of("time", remaining)));
    }
    private void saveLocation(String root, Location l) {
        plugin.getConfig().set(root + ".world", l.getWorld().getName());
        plugin.getConfig().set(root + ".x", l.getX()); plugin.getConfig().set(root + ".y", l.getY()); plugin.getConfig().set(root + ".z", l.getZ());
        plugin.getConfig().set(root + ".yaw", l.getYaw()); plugin.getConfig().set(root + ".pitch", l.getPitch());
        plugin.saveConfig();
    }

    void spawnAfkNpc(Player player) {
        removeAfkNpc();
        Villager npc = (Villager) player.getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
        npc.getPersistentDataContainer().set(afkNpcKey, PersistentDataType.BYTE, (byte) 1);
        npc.customName(CoreTextMini.mm("<gradient:#55E6FF:#8B7CFF><bold>✦ AFK REWARDS ✦</bold></gradient>"));
        npc.setCustomNameVisible(true); npc.setInvulnerable(true); npc.setAI(false); npc.setSilent(true); npc.setCollidable(false); npc.setRemoveWhenFarAway(false);
        plugin.getConfig().set("afk.npc-uuid", npc.getUniqueId().toString()); plugin.saveConfig();
    }

    void removeAfkNpc() {
        String raw = plugin.getConfig().getString("afk.npc-uuid", "");
        if (raw != null && !raw.isBlank()) try {
            Entity entity = Bukkit.getEntity(UUID.fromString(raw));
            if (entity != null) entity.remove();
        } catch (IllegalArgumentException ignored) { }
        plugin.getConfig().set("afk.npc-uuid", ""); plugin.saveConfig();
    }

    void interactAfkNpc(PlayerInteractEntityEvent event) {
        if (!event.getRightClicked().getPersistentDataContainer().has(afkNpcKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        Location target = afk();
        if (target == null) { text.send(event.getPlayer(), "afk-not-set", Map.of()); return; }
        event.getPlayer().teleport(target); text.send(event.getPlayer(), "afk", Map.of());
    }

    int tokens(Player p) { return tokens.getOrDefault(p.getUniqueId(), plugin.getConfig().getInt("tokens.starting-balance", 0)); }
    int streak(Player p) { return streaks.getOrDefault(p.getUniqueId(), 0); }
    void addTokens(Player p, int amount) { tokens.put(p.getUniqueId(), Math.max(0, tokens(p) + amount)); save(); }
    void resetStats(Player p) {
        UUID uuid = p.getUniqueId();
        kills.put(uuid, 0);
        deaths.put(uuid, 0);
        streaks.put(uuid, 0);
        try {
            p.setStatistic(org.bukkit.Statistic.PLAYER_KILLS, 0);
            p.setStatistic(org.bukkit.Statistic.DEATHS, 0);
        } catch (IllegalArgumentException ignored) { }
        save();
    }

    boolean claimDaily(Player p) {
        long cooldown = plugin.getConfig().getLong("daily.cooldown-hours", 24L) * 3600000L;
        long remaining = daily.getOrDefault(p.getUniqueId(), 0L) + cooldown - System.currentTimeMillis();
        if (remaining > 0) { text.send(p, "daily-cooldown", Map.of("time", formatDuration(remaining))); return false; }
        daily.put(p.getUniqueId(), System.currentTimeMillis()); ItemStack reward = randomReward(RewardGuiHolder.Type.DAILY);
        if (reward != null) { p.getInventory().addItem(reward); text.send(p, "daily-reward", Map.of()); }
        else { int amount = plugin.getConfig().getInt("daily.tokens", 10); addTokens(p, amount); text.send(p, "daily-claimed", Map.of("tokens", amount)); }
        save(); return true;
    }

    void death(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        deaths.put(victim.getUniqueId(), deathsFor(victim) + 1);
        streaks.put(victim.getUniqueId(), 0);
        Player killer = victim.getKiller();
        if (killer != null) {
            kills.put(killer.getUniqueId(), killsFor(killer) + 1);
            int next = streak(killer) + 1; streaks.put(killer.getUniqueId(), next); text.send(killer, "killstreak", Map.of("streak", next));
            int reward = bounties.getOrDefault(victim.getUniqueId(), 0);
            if (reward > 0) { bounties.remove(victim.getUniqueId()); addTokens(killer, reward); Bukkit.broadcast(text.component("bounty-claimed", Map.of("killer", killer.getName(), "victim", victim.getName(), "amount", reward))); }
        }
        save();
    }

    boolean setBounty(Player setter, Player target, int amount) {
        if (amount < plugin.getConfig().getInt("bounties.minimum", 10) || setter.equals(target) || tokens(setter) < amount) return false;
        addTokens(setter, -amount); bounties.put(target.getUniqueId(), bounties.getOrDefault(target.getUniqueId(), 0) + amount); save(); return true;
    }
    int bounty(Player target) { return bounties.getOrDefault(target.getUniqueId(), 0); }
    void toggleScoreboard(Player player) {
        text.send(player, "scoreboard-tab", Map.of());
    }

    void tick() { for (Player p : Bukkit.getOnlinePlayers()) { updateTab(p); updateHealthObjective(p); tickAfkReward(p); } }
    private void updateTab(Player p) {
        if (!plugin.getConfig().getBoolean("tab.enabled", true)) return;
        p.setPlayerListHeaderFooter(legacy(lines("tab.header", p)), legacy(lines("tab.footer", p)));
    }
    private String lines(String path, Player p) {
        StringBuilder out = new StringBuilder();
        for (String line : plugin.getConfig().getStringList(path)) { if (!out.isEmpty()) out.append("\n"); out.append(line.replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size())).replace("%max%", String.valueOf(Bukkit.getMaxPlayers())).replace("%player%", p.getName()).replace("%store%", plugin.getConfig().getString("store-url", "")).replace("%discord%", plugin.getConfig().getString("discord-url", ""))); }
        return out.toString();
    }

    private void updateHealthObjective(Player player) {
        if (!plugin.getConfig().getBoolean("scoreboard.health-under-name", true)) return;
        // TAB owns the below-name objective when installed. Re-registering a
        // vanilla health objective here would overwrite TAB's fancy-value
        // (health + streak + ping) on every refresh tick.
        if (plugin.getServer().getPluginManager().isPluginEnabled("TAB")) return;
        Scoreboard board = player.getScoreboard();
        String name = plugin.getConfig().getString("scoreboard.health-objective", "Health");
        if (name == null || name.isBlank() || name.length() > 16) name = "Health";
        Objective health = board.getObjective(name);
        if (health != null && !Criteria.HEALTH.equals(health.getTrackedCriteria())) {
            health.unregister();
            health = null;
        }
        if (health == null) {
            try {
                health = board.registerNewObjective(name, Criteria.HEALTH,
                        CoreTextMini.mm(plugin.getConfig().getString("scoreboard.health-display-name", "<#FCD05C>❤ <white>")));
            } catch (IllegalArgumentException ignored) {
                return;
            }
        }
        health.displayName(CoreTextMini.mm(plugin.getConfig().getString("scoreboard.health-display-name", "<#FCD05C>❤ <white>")));
        health.setDisplaySlot(DisplaySlot.BELOW_NAME);
        health.setRenderType(RenderType.HEARTS);
    }

    private void updateScoreboard(Player p) {
        if (!plugin.getConfig().getBoolean("scoreboard.enabled", true) || scoreboardHidden.contains(p.getUniqueId())) return;
        Scoreboard board = boards.computeIfAbsent(p.getUniqueId(), ignored -> Bukkit.getScoreboardManager().getNewScoreboard());
        Objective objective = board.getObjective("meowffa");
        if (objective == null) objective = board.registerNewObjective("meowffa", "dummy", Component.text("MeowFFA"));
        objective.displayName(CoreTextMini.mm(plugin.getConfig().getString("scoreboard.title", "MeowFFA"))); objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        Set<String> oldEntries = sidebarEntries.computeIfAbsent(p.getUniqueId(), ignored -> new HashSet<>());
        for (String entry : oldEntries) board.resetScores(entry);
        oldEntries.clear();
        String[] lines = {"§b" + p.getName(), "", "§aKills: §f" + killsFor(p), "§cDeaths: §f" + deathsFor(p), "§6Streak: §f" + streak(p), "§eTokens: §f" + tokens(p), "§dBounty: §f" + bounty(p), "§8meowffa"};
        for (int i = 0; i < lines.length; i++) { String entry = lines[i] + "§" + Integer.toHexString(i); oldEntries.add(entry); objective.getScore(entry).setScore(lines.length - i); }
        if (plugin.getConfig().getBoolean("scoreboard.health-under-name", true)) {
            Objective health = board.getObjective("meowffa_health");
            if (health == null) health = board.registerNewObjective("meowffa_health", Criteria.HEALTH, CoreTextMini.mm(plugin.getConfig().getString("scoreboard.health-label", "<red>❤")));
            health.displayName(CoreTextMini.mm(plugin.getConfig().getString("scoreboard.health-label", "<red>❤"))); health.setDisplaySlot(DisplaySlot.BELOW_NAME); health.setRenderType(RenderType.HEARTS);
        }
        p.setScoreboard(board);
    }

    private static String legacy(String value) { return LegacyComponentSerializer.legacySection().serialize(CoreTextMini.mm(value)); }
    private int killsFor(Player p) { return kills.getOrDefault(p.getUniqueId(), statistic(p, "PLAYER_KILLS")); }
    private int deathsFor(Player p) { return deaths.getOrDefault(p.getUniqueId(), statistic(p, "DEATHS")); }
    int killsValue(Player p) { return killsFor(p); }
    int deathsValue(Player p) { return deathsFor(p); }
    String kdValue(Player p) { int k = killsFor(p), d = deathsFor(p); if (d == 0) return String.valueOf(k); return String.format(java.util.Locale.ROOT, "%.2f", (double) k / d); }
    int healthValue(Player p) { return Math.max(0, (int) Math.ceil(p.getHealth())); }
    int pingValue(Player p) { return Math.max(0, p.getPing()); }
    private int statistic(Player p, String name) { try { return p.getStatistic(org.bukkit.Statistic.valueOf(name)); } catch (Exception ignored) { return 0; } }
    private static String formatDuration(long ms) { long s = Math.max(1, ms / 1000); long h = s / 3600; long m = (s % 3600) / 60; return h > 0 ? h + "h " + m + "m" : m + "m " + (s % 60) + "s"; }
    void save() { YamlConfiguration y = new YamlConfiguration(); for (var e : tokens.entrySet()) y.set("tokens." + e.getKey(), e.getValue()); for (var e : streaks.entrySet()) y.set("streaks." + e.getKey(), e.getValue()); for (var e : kills.entrySet()) y.set("kills." + e.getKey(), e.getValue()); for (var e : deaths.entrySet()) y.set("deaths." + e.getKey(), e.getValue()); for (var e : daily.entrySet()) y.set("daily." + e.getKey(), e.getValue()); for (var e : bounties.entrySet()) y.set("bounties." + e.getKey(), e.getValue()); try { y.save(dataFile); } catch (IOException ex) { plugin.getLogger().warning("Could not save core data: " + ex.getMessage()); } }
    private void load() { YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile); loadInt(y, "tokens", tokens); loadInt(y, "streaks", streaks); loadInt(y, "kills", kills); loadInt(y, "deaths", deaths); loadInt(y, "bounties", bounties); var d = y.getConfigurationSection("daily"); if (d != null) for (String k : d.getKeys(false)) try { daily.put(UUID.fromString(k), d.getLong(k)); } catch (Exception ignored) {} }
    private void loadInt(YamlConfiguration y, String root, Map<UUID, Integer> map) { var s = y.getConfigurationSection(root); if (s == null) return; for (String k : s.getKeys(false)) try { map.put(UUID.fromString(k), s.getInt(k)); } catch (Exception ignored) {} }
    void shutdown() { save(); }
    void reloadSettings() { plugin.reloadConfig(); text.reload(); }
    private static final class CoreTextMini { static Component mm(String value) { return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(value == null ? "" : value); } }
}
