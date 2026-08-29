package eu.fakemoon.macerr;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks one circulating bundle built from an administrator-configured pool.
 * Rerolls play a slot-machine title animation to everyone and end with the full
 * bundle landing on a player — random, or fixed by /rig.
 *
 * The circulating copy is given out EXACTLY as stored — no hidden tags — so
 * plugins/Skripts that compare the item keep working. Tracking is done by
 * content: same type + same display name (or, for unnamed items, similarity
 * ignoring durability).
 */
public final class RerollManager {

    /** Tick delays between animation frames — starts fast, slows to the reveal. */
    private static final int[] FRAME_DELAYS = {2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9};

    private final MaceRRPlugin plugin;
    private final WorldGuardHook worldGuard;
    private final Random random = new Random();

    private final List<ItemStack> pool = new ArrayList<>();
    /** Snapshot of the exact pool bundle currently in circulation. */
    private final List<ItemStack> activeItems = new ArrayList<>();
    private UUID holder;
    /** One-shot /rig target: when this victim dies, award the bundle to the recipient. */
    private UUID rigAfterDeathVictim;
    private UUID rigAfterDeathRecipient;
    private boolean enabled = true;
    /** True when a reroll found no one online — retried when someone joins. */
    private boolean pending;
    private boolean rolling;
    /** Bumped to abort in-flight animation frames (e.g. on /macerr disable). */
    private int generation;
    /** When each player last lost the item — feeds the no-repeat window. */
    private final Map<UUID, Long> lastHeld = new HashMap<>();
    private final AtomicLong saveSequence = new AtomicLong();
    private final Object ioLock = new Object();
    /** Guarded by ioLock; prevents an older async snapshot overwriting a newer one. */
    private long writtenSequence;

    public RerollManager(MaceRRPlugin plugin) {
        this.plugin = plugin;
        this.worldGuard = new WorldGuardHook(plugin);
    }

    // ---------------------------------------------------------------- state

    private File file() {
        return new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file());
        boolean legacyFormat = !yaml.contains("schema-version") && yaml.contains("item");
        if (legacyFormat) backupLegacyData();
        pool.clear();
        for (String encoded : yaml.getStringList("pool")) {
            ItemStack item = Util.fromBase64(encoded);
            if (!Util.isEmpty(item)) pool.add(item);
        }

        // Backward compatibility: the old format stored one template in "item".
        ItemStack legacyItem = Util.fromBase64(yaml.getString("item"));
        if (pool.isEmpty() && !Util.isEmpty(legacyItem)) pool.add(legacyItem.clone());

        enabled = yaml.getBoolean("enabled", true);
        pending = yaml.getBoolean("pending", false);
        String holderRaw = yaml.getString("active.holder", yaml.getString("holder"));
        try {
            holder = holderRaw == null || holderRaw.isEmpty() ? null : UUID.fromString(holderRaw);
        } catch (IllegalArgumentException ignored) {
            holder = null;
        }

        activeItems.clear();
        for (String encoded : yaml.getStringList("active.items")) {
            ItemStack item = Util.fromBase64(encoded);
            if (!Util.isEmpty(item)) activeItems.add(item);
        }
        // Also accepts the short-lived v2 single-active-item format.
        ItemStack previousActiveItem = Util.fromBase64(yaml.getString("active.item"));
        if (enabled && activeItems.isEmpty() && !Util.isEmpty(previousActiveItem)) activeItems.add(previousActiveItem);
        if (enabled && activeItems.isEmpty() && !Util.isEmpty(legacyItem)) activeItems.add(legacyItem.clone());
        // A disabled legacy reroller stored its template in "item" even though
        // no copy was circulating. Never treat that template as a live bundle.
        if (!enabled) {
            activeItems.clear();
            holder = null;
            pending = false;
        }
        var lastHeldSection = yaml.getConfigurationSection("last-held");
        if (lastHeldSection != null) {
            for (String key : lastHeldSection.getKeys(false)) {
                try {
                    lastHeld.put(UUID.fromString(key), lastHeldSection.getLong(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        // /reload or restart with the holder already online: restore their glow.
        if (holder != null) setGlow(holder, true);
    }

    /** Serializes on the main thread, writes async (sync writes can stall the server). */
    public void save() {
        String data = buildYaml().saveToString();
        long sequence = saveSequence.incrementAndGet();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            synchronized (ioLock) {
                if (sequence < writtenSequence) return;
                write(data);
                writtenSequence = sequence;
            }
        });
    }

    /** Synchronous save — used on plugin disable when async tasks no longer run. */
    public void saveNow() {
        String data = buildYaml().saveToString();
        long sequence = saveSequence.incrementAndGet();
        synchronized (ioLock) {
            write(data);
            writtenSequence = sequence;
        }
    }

    private YamlConfiguration buildYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 3);
        yaml.set("pool", pool.stream().map(Util::toBase64).toList());
        yaml.set("active.items", activeItems.stream().map(Util::toBase64).toList());
        yaml.set("active.holder", holder == null ? null : holder.toString());
        yaml.set("enabled", enabled);
        yaml.set("pending", pending);
        long window = noRepeatMillis();
        for (Map.Entry<UUID, Long> entry : lastHeld.entrySet()) {
            // Only keep entries that can still matter.
            if (window <= 0 || System.currentTimeMillis() - entry.getValue() < window) {
                yaml.set("last-held." + entry.getKey(), entry.getValue());
            }
        }
        return yaml;
    }

    private void write(String data) {
        try {
            Files.writeString(file().toPath(), data, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save data.yml: " + ex.getMessage());
        }
    }

    private void backupLegacyData() {
        File source = file();
        File backup = new File(plugin.getDataFolder(), "data-v1-backup.yml");
        if (!source.isFile() || backup.exists()) return;
        try {
            Files.copy(source.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
            plugin.getLogger().info("Backed up legacy item data to data-v1-backup.yml before migration.");
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not back up legacy data.yml: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- no-repeat

    private long noRepeatMillis() {
        long seconds = Util.parseDurationSeconds(plugin.getConfig().getString("no-repeat", "10m"));
        return seconds <= 0 ? 0 : seconds * 1000L;
    }

    private void markHeld(UUID id) {
        if (id != null) lastHeld.put(id, System.currentTimeMillis());
    }

    /** True when this player is not standing in a configured WorldGuard exclusion region. */
    public boolean canReceive(Player player) {
        return worldGuard.allows(player, plugin.getConfig().getStringList("blocked-regions"));
    }

    /** Withdraws the current bundle when its holder enters a blocked region. */
    public boolean onHolderEnteredBlockedRegion(Player player) {
        return onHolderEnteredBlockedRegion(player, player.getLocation());
    }

    public boolean onHolderEnteredBlockedRegion(Player player, Location location) {
        if (!enabled || rolling || holder == null || !holder.equals(player.getUniqueId())) return false;
        if (!plugin.getConfig().getBoolean("reroll-holder-in-blocked-region", true)) return false;
        if (worldGuard.allows(location, plugin.getConfig().getStringList("blocked-regions"))) return false;
        String name = player.getName();
        withdrawBundle();
        Util.broadcastKey("event.holder-blocked", Map.of("player", name));
        return reroll(Bukkit.getConsoleSender(), null);
    }

    public boolean worldGuardAvailable() {
        return worldGuard.available();
    }

    public List<String> blockedRegions() {
        return List.copyOf(plugin.getConfig().getStringList("blocked-regions"));
    }

    public List<String> missingBlockedRegions() {
        return worldGuard.missingRegionIds(blockedRegions());
    }

    public void configReloaded(CommandSender feedback) {
        worldGuard.resetWarnings();
        List<String> regions = blockedRegions();
        if (regions.isEmpty()) {
            Util.msgKey(feedback, "error.reload-no-regions", Map.of());
        } else if (!worldGuard.available()) {
            Util.msgKey(feedback, "error.reload-no-worldguard", Map.of());
        } else {
            List<String> missing = missingBlockedRegions();
            if (missing.isEmpty()) {
                Util.msgKey(feedback, "error.reload-ok", Map.of("regions", String.join(", ", regions)));
            } else {
                Util.msgKey(feedback, "error.reload-missing-regions", Map.of("regions", String.join(", ", missing)));
            }
        }
    }

    public void logRegionStatus() {
        List<String> regions = blockedRegions();
        if (regions.isEmpty()) {
            plugin.getLogger().info("WorldGuard recipient exclusions are disabled (blocked-regions is empty).");
        } else if (!worldGuard.available()) {
            plugin.getLogger().warning("blocked-regions is set to " + regions
                    + " but WorldGuard is unavailable; rerolls will pause for safety.");
        } else {
            List<String> missing = missingBlockedRegions();
            if (missing.isEmpty()) {
                plugin.getLogger().info("WorldGuard recipient exclusions active for region IDs: " + regions);
            } else {
                plugin.getLogger().warning("WorldGuard is connected, but configured region IDs were not found: "
                        + missing + ". Define them before expecting recipient exclusion.");
            }
        }
    }

    /**
     * Random-roll candidates: players outside excluded WorldGuard regions and the no-repeat
     * window. If every allowed player is still on cooldown, falls back to all
     * allowed players so the item never gets stuck.
     */
    private List<Player> eligible(List<Player> online) {
        List<Player> allowed = online.stream().filter(this::canReceive).toList();
        if (allowed.isEmpty()) return List.of();
        long window = noRepeatMillis();
        if (window <= 0) return allowed;
        long now = System.currentTimeMillis();
        List<Player> out = allowed.stream()
                .filter(p -> now - lastHeld.getOrDefault(p.getUniqueId(), 0L) >= window)
                .toList();
        return out.isEmpty() ? allowed : out;
    }

    public List<ItemStack> pool() {
        return pool.stream().map(ItemStack::clone).toList();
    }

    public void setPool(List<ItemStack> items) {
        pool.clear();
        for (ItemStack item : items) {
            if (!Util.isEmpty(item)) pool.add(item.clone());
        }
        save();
    }

    public boolean hasPool() {
        return !pool.isEmpty();
    }

    public int poolSize() {
        return pool.size();
    }

    public List<ItemStack> activeItems() {
        return activeItems.stream().map(ItemStack::clone).toList();
    }

    public UUID holder() {
        return holder;
    }

    public void clearHolder() {
        setGlow(holder, false);
        markHeld(holder);
        holder = null;
        save();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isPending() {
        return pending;
    }

    public boolean isRolling() {
        return rolling;
    }

    public String itemName() {
        if (activeItems.isEmpty()) return "weapon bundle";
        if (activeItems.size() == 1) return itemName(activeItems.getFirst());
        return activeItems.size() + "-item weapon bundle";
    }

    public String poolItemName(ItemStack item) {
        return itemName(item);
    }

    private String itemName(ItemStack item) {
        if (item == null) return "reroll item";
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.displayName() != null) return Util.plain(meta.displayName());
        return Util.pretty(item.getType());
    }

    // ---------------------------------------------------------------- glow

    /** The holder glows so everyone can see who has the item. */
    private void setGlow(UUID id, boolean glow) {
        if (id == null) return;
        Player player = Bukkit.getPlayer(id);
        if (player != null) player.setGlowing(glow);
    }

    /** Re-applies the holder's glow (after restarts/reloads and rejoins). */
    public void refreshGlow(Player player) {
        if (player.getUniqueId().equals(holder)) player.setGlowing(true);
    }

    // ---------------------------------------------------------------- item identity

    /**
     * Whether this item is "the" circulating item. Matched by content, not by a
     * hidden tag: tags break Skript item comparisons, and Skript-recreated items
     * would lose them. Named templates match on type + display name (survives
     * durability changes); unnamed ones on similarity ignoring durability.
     */
    public boolean isTracked(ItemStack item) {
        if (Util.isEmpty(item)) return false;
        for (ItemStack active : activeItems) {
            if (matches(item, active)) return true;
        }
        return false;
    }

    private static boolean matches(ItemStack item, ItemStack active) {
        if (item.getType() != active.getType()) return false;
        Component templateName = active.hasItemMeta() ? active.getItemMeta().displayName() : null;
        if (templateName != null) {
            ItemMeta meta = item.getItemMeta();
            Component name = meta == null ? null : meta.displayName();
            return name != null && Util.plain(name).equals(Util.plain(templateName));
        }
        return similarIgnoringDamage(item, active);
    }

    private static boolean similarIgnoringDamage(ItemStack a, ItemStack b) {
        ItemStack ca = a.clone();
        ItemStack cb = b.clone();
        ca.editMeta(meta -> {
            if (meta instanceof Damageable damageable) damageable.setDamage(0);
        });
        cb.editMeta(meta -> {
            if (meta instanceof Damageable damageable) damageable.setDamage(0);
        });
        return ca.isSimilar(cb);
    }

    // ---------------------------------------------------------------- transfers

    /** The holder threw the item away — it vanishes and rerolls immediately. */
    public void onThrownAway(Player player) {
        player.setGlowing(false);
        markHeld(player.getUniqueId());
        Util.broadcast("<gold>" + player.getName() + "</gold> <gray>threw the <gold>" + itemName()
                + "</gold> away — rerolling!");
        takeBack();
        reroll(Bukkit.getConsoleSender(), null);
    }

    /** Only the selected holder may collect bundle items that landed at their feet. */
    public boolean canPickUp(Player player) {
        return holder == null || holder.equals(player.getUniqueId());
    }

    /** Someone claimed a ground bundle that did not yet have a holder. */
    public void onPickedUp(Player player) {
        if (holder != null && holder.equals(player.getUniqueId())) return;
        setGlow(holder, false);
        holder = player.getUniqueId();
        player.setGlowing(true);
        save();
        Util.broadcast("<gold>" + player.getName() + "</gold> <gray>picked up the <gold>" + itemName() + "</gold>!");
    }

    /** A ground copy was destroyed (void, cactus, lava, despawn...) — reroll it. */
    public void onDroppedItemLost() {
        Util.broadcast("<gray>The <gold>" + itemName() + "</gold> was destroyed — rerolling!");
        takeBack();
        reroll(Bukkit.getConsoleSender(), null);
    }

    /** A tracked weapon ran out of durability — withdraw and reroll the bundle. */
    public void onItemBroken(Player player) {
        if (!enabled) return;
        Util.broadcast("<gold>" + player.getName() + "</gold> <gray>broke a weapon from the <gold>"
                + itemName() + "</gold> — rerolling the bundle!");
        takeBack();
        reroll(Bukkit.getConsoleSender(), null);
    }

    /** Removes tracked items from a player's inventory and cursor. */
    public boolean strip(Player player) {
        boolean any = false;
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isTracked(contents[slot])) {
                inv.setItem(slot, null);
                any = true;
            }
        }
        InventoryView view = player.getOpenInventory();
        if (view.getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING
                || view.getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.WORKBENCH) {
            Inventory top = view.getTopInventory();
            for (int slot = 0; slot < top.getSize(); slot++) {
                if (isTracked(top.getItem(slot))) {
                    top.setItem(slot, null);
                    any = true;
                }
            }
        }
        if (isTracked(view.getCursor())) {
            view.setCursor(null);
            any = true;
        }
        return any;
    }

    /** Any tracked copy currently lying on the ground somewhere? */
    public boolean hasGroundItem() {
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (isTracked(item.getItemStack())) return true;
            }
        }
        return false;
    }

    /** Pulls the item out of circulation: all ground copies + every online inventory. */
    private void takeBack() {
        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                if (isTracked(item.getItemStack())) item.remove();
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            strip(player);
        }
        setGlow(holder, false);
        markHeld(holder);
        holder = null;
        save();
    }

    /** Removes every active bundle item before a death/logout-triggered reroll. */
    public void withdrawBundle() {
        takeBack();
    }

    public boolean rigAfterDeath(CommandSender feedback, Player victim, Player recipient) {
        if (!enabled || !hasPool()) { Util.msg(feedback, "<red>The reroller is disabled or its item pool is empty."); return false; }
        if (rolling) { Util.msg(feedback, "<red>A reroll is already in progress."); return false; }
        if (victim.getUniqueId().equals(recipient.getUniqueId())) { Util.msg(feedback, "<red>The victim and recipient must be different players."); return false; }
        if (!canReceive(recipient)) { Util.msg(feedback, "<red>" + recipient.getName() + " is in a blocked WorldGuard region."); return false; }
        rigAfterDeathVictim = victim.getUniqueId();
        rigAfterDeathRecipient = recipient.getUniqueId();
        Util.msg(feedback, "<green>When <gold>" + victim.getName() + "</gold> dies, the bundle will be given to <gold>" + recipient.getName() + "</gold>.");
        return true;
    }

    public boolean isRiggedAfterDeath(Player victim) {
        return rigAfterDeathVictim != null && rigAfterDeathVictim.equals(victim.getUniqueId());
    }

    public boolean triggerRiggedDeath(Player victim) {
        if (!isRiggedAfterDeath(victim)) return false;
        UUID recipientId = rigAfterDeathRecipient;
        rigAfterDeathVictim = null;
        rigAfterDeathRecipient = null;
        Player recipient = recipientId == null ? null : Bukkit.getPlayer(recipientId);
        withdrawBundle();
        reroll(Bukkit.getConsoleSender(), recipient != null && recipient.isOnline() && canReceive(recipient) ? recipient : null);
        return true;
    }

    // ---------------------------------------------------------------- enable/disable

    public void disable(CommandSender feedback) {
        String removedName = itemName();
        enabled = false;
        generation++; // aborts any in-flight animation
        rolling = false;
        pending = false;
        takeBack();
        activeItems.clear();
        save();
        Util.msg(feedback, "<yellow>Reroller disabled — the <gold>" + removedName + "</gold> was removed from circulation.");
    }

    public void enable(CommandSender feedback) {
        enabled = true;
        save();
        Util.msg(feedback, "<green>Reroller enabled. Send the bundle out with <white>/macerr reroll</white>.");
    }

    // ---------------------------------------------------------------- rerolling

    /**
     * Starts a reroll: takes the bundle back, then plays the slot animation and hands
     * every pool entry out. If {@code rigged} is non-null the roll is guaranteed to land on
     * them (viewers can't tell). Returns false if it couldn't start.
     */
    public boolean reroll(CommandSender feedback, Player rigged) {
        if (!enabled) {
            Util.msg(feedback, "<red>The reroller is disabled — <white>/macerr enable</white> first.");
            return false;
        }
        if (pool.isEmpty()) {
            takeBack();
            activeItems.clear();
            save();
            Util.msg(feedback, "<red>The reroll weapon bundle is empty — add items with <white>/macerr item</white> first.");
            return false;
        }
        if (rolling) {
            Util.msg(feedback, "<red>A reroll is already in progress.");
            return false;
        }
        if (rigged != null && !canReceive(rigged)) {
            Util.msg(feedback, "<red>" + rigged.getName() + " is in a blocked WorldGuard region and cannot receive the "
                    + itemName() + ".");
            return false;
        }
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (rigged == null && !online.isEmpty() && eligible(online).isEmpty()) {
            pending = true;
            save();
            Util.msg(feedback, "<red>No player is in an allowed world. The reroll is waiting.");
            return false;
        }
        pending = false;
        takeBack();
        activeItems.clear();
        for (ItemStack item : pool) activeItems.add(item.clone());
        save();
        rolling = true;
        frame(0, rigged == null ? null : rigged.getUniqueId(), generation);
        return true;
    }

    /** Retries a pending roll after a player walks out of a blocked region. */
    public void retryPending() {
        if (!enabled || !pending || rolling || pool.isEmpty()) return;
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (!eligible(online).isEmpty()) reroll(Bukkit.getConsoleSender(), null);
    }

    private void frame(int index, UUID riggedId, int gen) {
        if (gen != generation) return; // aborted by /macerr disable
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        List<Player> allowed = online.stream().filter(this::canReceive).toList();
        if (allowed.isEmpty()) {
            rolling = false;
            pending = true;
            save();
            return;
        }
        if (index >= FRAME_DELAYS.length) {
            reveal(online, riggedId);
            return;
        }
        Player shown = allowed.get(random.nextInt(allowed.size()));
        Title title = Title.title(
                Util.mm("<yellow><bold>" + shown.getName() + "</bold></yellow>"),
                Util.mm("<gray>Rerolling the <gold>" + itemName() + "</gold>..."),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(600), Duration.ZERO));
        for (Player viewer : online) {
            viewer.showTitle(title);
            viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.6f);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> frame(index + 1, riggedId, gen), FRAME_DELAYS[index]);
    }

    private void reveal(List<Player> online, UUID riggedId) {
        rolling = false;
        Player rigged = riggedId == null ? null : Bukkit.getPlayer(riggedId);
        Player winner;
        if (rigged != null && rigged.isOnline() && canReceive(rigged)) {
            winner = rigged; // /rig bypasses the no-repeat window
        } else {
            List<Player> pool = eligible(online);
            if (pool.isEmpty()) {
                pending = true;
                save();
                return;
            }
            winner = pool.get(random.nextInt(pool.size()));
        }
        give(winner);

        Title title = Title.title(
                Util.mm("<green><bold>" + winner.getName() + "</bold></green>"),
                Util.mm("<gray>now holds the <gold>" + itemName() + "</gold>!"),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(600)));
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.showTitle(title);
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        }
        Util.broadcast("<gold>" + winner.getName() + "</gold> <gray>now holds the <gold>" + itemName() + "</gold>!");
    }

    private void give(Player winner) {
        // Every pool entry is given exactly as stored — no hidden tags, so
        // Skript abilities keep working. Overflow remains reserved for this winner.
        if (activeItems.isEmpty()) return;
        holder = winner.getUniqueId();
        winner.setGlowing(true);
        for (ItemStack active : activeItems) {
            Map<Integer, ItemStack> leftover = winner.getInventory().addItem(active.clone());
            for (ItemStack item : leftover.values()) {
                winner.getWorld().dropItem(winner.getLocation(), item);
            }
        }
        save();
    }
}
