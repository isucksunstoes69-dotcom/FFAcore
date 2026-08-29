package eu.fakemoon.onevone;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RoomManager {

    public static final long MAX_ROOM_FOOTPRINT = 40_000L;

    private final OneVOnePlugin plugin;
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Map<UUID, Location> pendingReturns = new HashMap<>();
    private final Set<UUID> internalTeleports = new HashSet<>();
    private final Set<String> pendingDeathResolution = new HashSet<>();
    private final NamespacedKey displayKey;
    private final Object ioLock = new Object();
    private BukkitTask ticker;

    public RoomManager(OneVOnePlugin plugin) {
        this.plugin = plugin;
        this.displayKey = new NamespacedKey(plugin, "room_display");
    }

    private File file() {
        return new File(plugin.getDataFolder(), "rooms.yml");
    }

    // ---------------------------------------------------------------- persistence

    public void load() {
        rooms.clear();
        removeTaggedDisplays();
        File file = file();
        if (!file.exists()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("rooms");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null) continue;
            String worldName = section.getString("world", "world");
            Region region = Region.fromList(worldName, section.getIntegerList("region"));
            if (region == null) continue;

            Room room = new Room(name.toLowerCase(Locale.ROOT), region);
            Region entrance = Region.fromList(worldName, section.getIntegerList("entrance"));
            room.setEntrance(entrance);
            Material material = parseGlass(section.getString("entrance-material", "LIGHT_BLUE_STAINED_GLASS"));
            room.setEntranceMaterial(material == null ? Material.LIGHT_BLUE_STAINED_GLASS : material);

            // Migrate a room that was sealed by the old barrier implementation.
            List<String> legacyClosed = section.getStringList("closed-snapshot");
            if (!legacyClosed.isEmpty()) restoreSnapshot(worldName, legacyClosed);

            List<String> original = section.getStringList("entrance-original");
            if (entrance != null && original.isEmpty()) original = captureSnapshot(entrance);
            room.setEntranceSnapshot(new ArrayList<>(original));
            rooms.put(room.name(), room);
            if (entrance != null) {
                fillEntrance(room);
                ensureDisplay(room);
            }
        }
        saveAll();
    }

    public void start() {
        if (ticker != null) ticker.cancel();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        for (Room room : rooms.values()) updateDisplay(room, true);
    }

    public void saveAll() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Room room : rooms.values()) {
            String path = "rooms." + room.name() + ".";
            yaml.set(path + "world", room.region().world());
            yaml.set(path + "region", room.region().toList());
            if (room.entrance() != null) {
                yaml.set(path + "entrance", room.entrance().toList());
                yaml.set(path + "entrance-material", room.entranceMaterial().name());
                yaml.set(path + "entrance-original", room.entranceSnapshot());
            }
        }
        // Room setup changes are rare and the file is tiny. A serialized write
        // prevents an older async snapshot from racing and overwriting a newer one.
        synchronized (ioLock) {
            write(yaml.saveToString());
        }
    }

    private void write(String data) {
        Path target = file().toPath();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, data, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save rooms.yml: " + ex.getMessage());
        }
    }

    public void flushSync() {
        // saveAll writes synchronously; retained as an explicit lifecycle hook.
    }

    // ---------------------------------------------------------------- rooms and setup

    public Room get(String name) {
        return rooms.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Room> all() {
        return rooms.values();
    }

    public Room create(String name, Region region) {
        Room room = new Room(name.toLowerCase(Locale.ROOT), region);
        rooms.put(room.name(), room);
        saveAll();
        return room;
    }

    public void delete(Room room) {
        reset(room, true, true);
        restoreEntrance(room);
        removeDisplay(room);
        rooms.remove(room.name());
        saveAll();
    }

    public void setEntrance(Room room, Region region, Material material) {
        restoreEntrance(room);
        removeDisplay(room);
        room.setEntrance(region);
        room.setEntranceMaterial(material);
        room.setEntranceSnapshot(captureSnapshot(region));
        fillEntrance(room);
        ensureDisplay(room);
        updateDisplay(room, true);
        saveAll();
    }

    public boolean entranceOverlaps(Region candidate, Room except) {
        for (Room room : rooms.values()) {
            if (room == except || room.entrance() == null) continue;
            if (overlaps(candidate, room.entrance())) return true;
        }
        return false;
    }

    public boolean roomOverlaps(Region candidate, Room except) {
        for (Room room : rooms.values()) {
            if (room != except && overlaps(candidate, room.region())) return true;
        }
        return false;
    }

    public boolean roomTouchesEntrance(Region candidate) {
        for (Room room : rooms.values()) {
            if (room.entrance() != null && overlaps(candidate, room.entrance())) return true;
        }
        return false;
    }

    public boolean entranceTouchesAnotherRoom(Region candidate, Room owner) {
        for (Room room : rooms.values()) {
            if (room != owner && overlaps(candidate, room.region())) return true;
        }
        return false;
    }

    public boolean containsTileState(Region region) {
        World world = Bukkit.getWorld(region.world());
        if (world == null) return false;
        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int y = region.minY(); y <= region.maxY(); y++) {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    if (world.getBlockAt(x, y, z).getState() instanceof TileState) return true;
                }
            }
        }
        return false;
    }

    private boolean overlaps(Region first, Region second) {
        return first.world().equals(second.world())
                && first.minX() <= second.maxX() && first.maxX() >= second.minX()
                && first.minY() <= second.maxY() && first.maxY() >= second.minY()
                && first.minZ() <= second.maxZ() && first.maxZ() >= second.minZ();
    }

    public static boolean eligible(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }

    public Room roomAtEntrance(Location location) {
        for (Room room : rooms.values()) {
            if (room.entrance() != null && room.entrance().contains(location)) return room;
        }
        return null;
    }

    public Room roomAt(Location location) {
        for (Room room : rooms.values()) {
            if (room.region().contains(location)) return room;
        }
        return null;
    }

    public Room roomOf(UUID playerId) {
        for (Room room : rooms.values()) {
            if (room.inside().contains(playerId) || room.fighters().contains(playerId)
                    || playerId.equals(room.lootWinner())) return room;
        }
        return null;
    }

    public boolean isPortalBlock(Block block) {
        return roomAtEntrance(block.getLocation()) != null;
    }

    public boolean isRoomBlock(Block block) {
        return roomAt(block.getLocation()) != null;
    }

    public boolean isProtectedBlock(Block block) {
        return isPortalBlock(block) || isRoomBlock(block);
    }

    public boolean isInternalTeleport(UUID playerId) {
        return internalTeleports.contains(playerId);
    }

    public int lootSeconds() {
        return Math.max(1, plugin.getConfig().getInt("loot-seconds", 30));
    }

    // ---------------------------------------------------------------- portal and fight lifecycle

    public void join(Room room, Player player) {
        if (!eligible(player)) {
            Util.msg(player, "<red>You must be in survival or adventure mode to join.");
            return;
        }
        Room current = roomOf(player.getUniqueId());
        if (current != null) {
            Util.msg(player, current == room
                    ? "<yellow>You are already inside this 1v1 room. Use <white>/1v1leave</white> while waiting."
                    : "<red>You are already in another 1v1 room.");
            return;
        }
        pruneWaiting(room);
        if (room.state() != Room.State.WAITING || room.inside().size() >= 2) {
            Util.msg(player, room.state() == Room.State.LOOTING
                    ? "<yellow>The winner is still looting. Try again shortly."
                    : "<red>A fight is already in progress in this room.");
            return;
        }

        int slot = room.inside().size();
        Location destination = findSafeSpawn(room, slot);
        if (destination == null) {
            Util.msg(player, "<red>This room has no safe teleport spot. Ask an admin to clear space inside it.");
            return;
        }

        UUID id = player.getUniqueId();
        room.inside().add(id);
        room.returnLocations().put(id, player.getLocation().clone());
        if (!teleportInternal(player, destination)) {
            room.inside().remove(id);
            room.returnLocations().remove(id);
            Util.msg(player, "<red>Could not teleport you into the room.");
            updateDisplay(room, true);
            return;
        }

        player.setFallDistance(0);
        player.setVelocity(player.getVelocity().zero());
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.35f);
        player.showTitle(Title.title(
                Util.mm("<gold><bold>1V1 ROOM</bold></gold>"),
                Util.mm(room.inside().size() == 1 ? "<gray>Waiting for an opponent..." : "<red>Opponent found!"),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1400), Duration.ofMillis(300))));
        updateDisplay(room, true);
        maybeStart(room);
    }

    public void leaveWaiting(Player player) {
        Room room = roomOf(player.getUniqueId());
        if (room == null) {
            Util.msg(player, "<gray>You are not in a 1v1 room.");
            return;
        }
        if (room.state() != Room.State.WAITING) {
            Util.msg(player, room.state() == Room.State.LOOTING
                    ? "<yellow>You will leave automatically when the loot timer ends."
                    : "<red>You cannot leave during a fight.");
            return;
        }
        UUID id = player.getUniqueId();
        Location back = room.returnLocations().remove(id);
        room.inside().remove(id);
        if (back == null) back = safeOutside(room);
        if (!teleportInternal(player, back) && back != null) {
            pendingReturns.put(id, back);
            Bukkit.getScheduler().runTask(plugin, () -> restoreOrEvacuate(player));
        }
        Util.msg(player, "<green>You left the 1v1 queue.");
        updateDisplay(room, true);
    }

    private void maybeStart(Room room) {
        if (room.state() != Room.State.WAITING) return;
        pruneWaiting(room);
        if (room.inside().size() != 2) return;

        room.fighters().clear();
        room.fighters().addAll(room.inside());
        room.deadFighters().clear();
        room.setState(Room.State.FIGHTING);

        List<String> names = new ArrayList<>();
        Title title = Title.title(
                Util.mm("<red><bold>FIGHT!</bold></red>"),
                Util.mm("<gray>Only one player leaves standing."),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(2000), Duration.ofMillis(500)));
        for (UUID id : room.fighters()) {
            Player fighter = Bukkit.getPlayer(id);
            if (fighter == null) continue;
            names.add(fighter.getName());
            fighter.setMetadata("1v1fight", new FixedMetadataValue(plugin, true));
            fighter.showTitle(title);
            fighter.playSound(fighter.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 0.7f);
        }
        if (names.size() == 2) {
            Util.broadcast("<gold>" + names.get(0) + "</gold> <gray>and</gray> <gold>" + names.get(1)
                    + "</gold> <gray>started a duel in <white>" + room.name() + "</white>!");
        }
        updateDisplay(room, true);
    }

    public void recordDeath(Room room, Player dead) {
        UUID id = dead.getUniqueId();
        stashReturn(room, id);
        room.inside().remove(id);
        dead.removeMetadata("1v1fight", plugin);

        if (room.state() == Room.State.WAITING) {
            updateDisplay(room, true);
            return;
        }
        if (room.state() == Room.State.LOOTING) {
            reset(room, true, true);
            return;
        }

        room.deadFighters().add(id);
        if (pendingDeathResolution.add(room.name())) {
            Bukkit.getScheduler().runTask(plugin, () -> resolveDeaths(room));
        }
        updateDisplay(room, true);
    }

    private void resolveDeaths(Room room) {
        pendingDeathResolution.remove(room.name());
        if (room.state() != Room.State.FIGHTING) return;
        List<Player> survivors = room.fighters().stream()
                .filter(id -> !room.deadFighters().contains(id))
                .map(Bukkit::getPlayer)
                .filter(player -> player != null && player.isOnline() && !player.isDead())
                .toList();
        if (survivors.size() == 1) {
            beginLoot(room, survivors.getFirst());
        } else if (survivors.isEmpty()) {
            Util.broadcast("<gray>The fight in room <white>" + room.name() + "</white> ended in a draw.");
            reset(room, true, true);
        }
    }

    public void handleQuit(Player player) {
        Room room = roomOf(player.getUniqueId());
        if (room == null) return;
        UUID id = player.getUniqueId();
        stashReturn(room, id);
        room.inside().remove(id);
        player.removeMetadata("1v1fight", plugin);

        if (room.state() == Room.State.WAITING) {
            updateDisplay(room, true);
        } else if (room.state() == Room.State.LOOTING) {
            // The quitting winner's original return point is already in
            // pendingReturns; do not replace it with a derived doorway exit.
            reset(room, false, true);
        } else {
            room.deadFighters().add(id);
            if (pendingDeathResolution.add(room.name())) {
                Bukkit.getScheduler().runTask(plugin, () -> resolveDeaths(room));
            }
        }
    }

    private void beginLoot(Room room, Player winner) {
        String loserName = room.fighters().stream()
                .filter(id -> !id.equals(winner.getUniqueId()))
                .map(Bukkit::getOfflinePlayer)
                .map(player -> player.getName() == null ? "their opponent" : player.getName())
                .findFirst().orElse("their opponent");

        for (UUID id : room.fighters()) {
            Player fighter = Bukkit.getPlayer(id);
            if (fighter != null) fighter.removeMetadata("1v1fight", plugin);
        }
        room.fighters().clear();
        room.deadFighters().clear();
        room.inside().clear();
        room.inside().add(winner.getUniqueId());
        room.setLootWinner(winner.getUniqueId());
        room.setLootEndsAtMillis(System.currentTimeMillis() + lootSeconds() * 1000L);
        room.setState(Room.State.LOOTING);

        winner.showTitle(Title.title(
                Util.mm("<green><bold>VICTORY!</bold></green>"),
                Util.mm("<gold>You have " + lootSeconds() + " seconds to loot."),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(2500), Duration.ofMillis(500))));
        winner.playSound(winner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        Util.broadcast("<gold>" + winner.getName() + "</gold> <gray>defeated <gold>" + loserName
                + "</gold> in room <white>" + room.name() + "</white>!");
        updateDisplay(room, true);
    }

    public void reset(Room room, boolean returnPlayers, boolean clearDrops) {
        Set<UUID> participants = new LinkedHashSet<>(room.inside());
        participants.addAll(room.fighters());
        if (room.lootWinner() != null) participants.add(room.lootWinner());
        List<UUID> retry = new ArrayList<>();
        for (UUID id : participants) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.removeMetadata("1v1fight", plugin);
            Location back = room.returnLocations().remove(id);
            if (back == null) back = pendingReturns.get(id);
            if (back == null) back = safeOutside(room);
            if (returnPlayers && player != null && player.isOnline()) {
                if (teleportInternal(player, back)) pendingReturns.remove(id);
                else if (back != null) {
                    pendingReturns.putIfAbsent(id, back);
                    retry.add(id);
                }
            } else if (back != null && (player == null || !player.isOnline())) {
                pendingReturns.putIfAbsent(id, back);
            }
        }
        room.inside().clear();
        room.fighters().clear();
        room.deadFighters().clear();
        room.returnLocations().clear();
        room.setLootWinner(null);
        room.setLootEndsAtMillis(0L);
        room.setState(Room.State.WAITING);
        pendingDeathResolution.remove(room.name());
        if (clearDrops) clearDroppedItems(room);
        updateDisplay(room, true);
        if (!retry.isEmpty() && plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> retry.stream()
                    .map(Bukkit::getPlayer)
                    .filter(player -> player != null && player.isOnline())
                    .forEach(this::restoreOrEvacuate));
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Room room : rooms.values()) {
            if (room.state() == Room.State.WAITING) pruneWaiting(room);
            if (room.state() == Room.State.LOOTING && now >= room.lootEndsAtMillis()) {
                reset(room, true, true);
            }
            updateDisplay(room, false);
        }
        // Keep retrying failed exits while the player is online and alive.
        for (UUID playerId : new ArrayList<>(pendingReturns.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline() && !player.isDead() && roomOf(playerId) == null) {
                restoreOrEvacuate(player);
            }
        }
    }

    private void pruneWaiting(Room room) {
        if (room.state() != Room.State.WAITING) return;
        List<UUID> stale = room.inside().stream().filter(id -> {
            Player player = Bukkit.getPlayer(id);
            return player == null || !player.isOnline() || !eligible(player)
                    || !room.region().contains(player.getLocation());
        }).toList();
        for (UUID id : stale) {
            room.inside().remove(id);
            Location back = room.returnLocations().remove(id);
            Player player = Bukkit.getPlayer(id);
            if (back != null && player != null && player.isOnline()) {
                if (!teleportInternal(player, back)) pendingReturns.put(id, back);
            } else if (back != null) {
                pendingReturns.put(id, back);
            }
        }
        if (!stale.isEmpty()) updateDisplay(room, true);
    }

    private void stashReturn(Room room, UUID playerId) {
        Location back = room.returnLocations().remove(playerId);
        if (back != null) pendingReturns.put(playerId, back);
    }

    public Location takePendingReturn(UUID playerId) {
        return pendingReturns.remove(playerId);
    }

    public Location pendingReturn(UUID playerId) {
        Location location = pendingReturns.get(playerId);
        return location == null ? null : location.clone();
    }

    public void restoreOrEvacuate(Player player) {
        UUID playerId = player.getUniqueId();
        Location pending = pendingReturns.get(playerId);
        if (pending != null) {
            if (teleportInternal(player, pending)) pendingReturns.remove(playerId, pending);
            return;
        }
        if (roomOf(playerId) != null) return;
        for (Room room : rooms.values()) {
            if (room.region().contains(player.getLocation())) {
                Location outside = safeOutside(room);
                if (!teleportInternal(player, outside) && outside != null) pendingReturns.put(playerId, outside);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- teleport positions

    private Location findSafeSpawn(Room room, int slot) {
        World world = Bukkit.getWorld(room.region().world());
        if (world == null) return null;
        Region region = room.region();
        long footprint = (long) (region.maxX() - region.minX() + 1)
                * (region.maxZ() - region.minZ() + 1);
        if (footprint > MAX_ROOM_FOOTPRINT) return null;
        int minX = Math.min(region.maxX(), region.minX() + 1);
        int maxX = Math.max(region.minX(), region.maxX() - 1);
        int minZ = Math.min(region.maxZ(), region.minZ() + 1);
        int maxZ = Math.max(region.minZ(), region.maxZ() - 1);
        boolean xAxis = (maxX - minX) >= (maxZ - minZ);
        double fraction = slot == 0 ? 0.25 : 0.75;
        int wantedX = xAxis ? (int) Math.round(minX + (maxX - minX) * fraction) : (minX + maxX) / 2;
        int wantedZ = xAxis ? (minZ + maxZ) / 2 : (int) Math.round(minZ + (maxZ - minZ) * fraction);

        List<int[]> candidates = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) candidates.add(new int[]{x, z});
        }
        candidates.sort(Comparator.comparingInt(point ->
                (point[0] - wantedX) * (point[0] - wantedX) + (point[1] - wantedZ) * (point[1] - wantedZ)));
        for (int[] point : candidates) {
            Location safe = safeAt(world, region, point[0], point[1]);
            if (safe == null) continue;
            boolean occupied = room.inside().stream().map(Bukkit::getPlayer)
                    .filter(player -> player != null && player.getWorld().equals(world))
                    .anyMatch(player -> player.getLocation().distanceSquared(safe) < 4.0);
            if (occupied) continue;
            double dx = (region.minX() + region.maxX() + 1) / 2.0 - safe.getX();
            double dz = (region.minZ() + region.maxZ() + 1) / 2.0 - safe.getZ();
            safe.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            return safe;
        }
        return null;
    }

    private Location safeAt(World world, Region region, int x, int z) {
        for (int y = region.minY() + 1; y < region.maxY(); y++) {
            Block floor = world.getBlockAt(x, y - 1, z);
            Block feet = world.getBlockAt(x, y, z);
            Block head = world.getBlockAt(x, y + 1, z);
            if (floor.getType().isSolid() && feet.isPassable() && head.isPassable()) {
                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    private Location safeOutside(Room room) {
        if (room.entrance() == null) return null;
        World world = Bukkit.getWorld(room.entrance().world());
        if (world == null) return null;
        Region entrance = room.entrance();
        double ex = (entrance.minX() + entrance.maxX() + 1) / 2.0;
        double ez = (entrance.minZ() + entrance.maxZ() + 1) / 2.0;
        double rx = (room.region().minX() + room.region().maxX() + 1) / 2.0;
        double rz = (room.region().minZ() + room.region().maxZ() + 1) / 2.0;
        double dx = ex - rx;
        double dz = ez - rz;
        double length = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
        int x = (int) Math.floor(ex + dx / length * 2.0);
        int z = (int) Math.floor(ez + dz / length * 2.0);
        int baseY = Math.max(world.getMinHeight() + 1, entrance.minY());
        for (int offset = 0; offset <= 8; offset++) {
            for (int direction : new int[]{1, -1}) {
                int y = baseY + offset * direction;
                if (y <= world.getMinHeight() || y >= world.getMaxHeight() - 1) continue;
                if (world.getBlockAt(x, y - 1, z).getType().isSolid()
                        && world.getBlockAt(x, y, z).isPassable()
                        && world.getBlockAt(x, y + 1, z).isPassable()) {
                    return new Location(world, x + 0.5, y, z + 0.5);
                }
            }
        }
        return world.getSpawnLocation();
    }

    private boolean teleportInternal(Player player, Location destination) {
        if (destination == null || destination.getWorld() == null) return false;
        internalTeleports.add(player.getUniqueId());
        try {
            return player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } finally {
            internalTeleports.remove(player.getUniqueId());
        }
    }

    // ---------------------------------------------------------------- entrance blocks

    private static Material parseGlass(String input) {
        Material material = Material.matchMaterial(input == null ? "" : input);
        return material != null && material.name().endsWith("_STAINED_GLASS") ? material : null;
    }

    private List<String> captureSnapshot(Region region) {
        World world = Bukkit.getWorld(region.world());
        if (world == null) return new ArrayList<>();
        List<String> snapshot = new ArrayList<>();
        forEachBlock(region, block -> snapshot.add(encode(block)));
        return snapshot;
    }

    private String encode(Block block) {
        return block.getX() + "," + block.getY() + "," + block.getZ() + "|"
                + block.getBlockData().getAsString();
    }

    private void fillEntrance(Room room) {
        if (room.entrance() == null) return;
        forEachBlock(room.entrance(), block -> block.setType(room.entranceMaterial(), false));
    }

    private void restoreEntrance(Room room) {
        if (room.entrance() != null && !room.entranceSnapshot().isEmpty()) {
            restoreSnapshot(room.entrance().world(), room.entranceSnapshot());
        }
        room.entranceSnapshot().clear();
    }

    private void restoreSnapshot(String worldName, List<String> snapshot) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        for (String entry : snapshot) {
            int separator = entry.indexOf('|');
            if (separator < 0) continue;
            String[] coordinates = entry.substring(0, separator).split(",");
            if (coordinates.length != 3) continue;
            try {
                Block block = world.getBlockAt(Integer.parseInt(coordinates[0]),
                        Integer.parseInt(coordinates[1]), Integer.parseInt(coordinates[2]));
                block.setBlockData(Bukkit.createBlockData(entry.substring(separator + 1)), false);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void forEachBlock(Region region, java.util.function.Consumer<Block> consumer) {
        World world = Bukkit.getWorld(region.world());
        if (world == null) return;
        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int y = region.minY(); y <= region.maxY(); y++) {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    consumer.accept(world.getBlockAt(x, y, z));
                }
            }
        }
    }

    private void clearDroppedItems(Room room) {
        World world = Bukkit.getWorld(room.region().world());
        if (world == null) return;
        for (Item item : world.getEntitiesByClass(Item.class)) {
            if (room.region().contains(item.getLocation())) item.remove();
        }
        for (ExperienceOrb orb : world.getEntitiesByClass(ExperienceOrb.class)) {
            if (room.region().contains(orb.getLocation())) orb.remove();
        }
    }

    // ---------------------------------------------------------------- entrance display

    private void ensureDisplay(Room room) {
        if (room.entrance() == null) return;
        TextDisplay current = room.display();
        if (current != null && current.isValid()) return;
        World world = Bukkit.getWorld(room.entrance().world());
        if (world == null) return;
        Region entrance = room.entrance();
        double x = (entrance.minX() + entrance.maxX() + 1) / 2.0;
        double y = entrance.maxY() + plugin.getConfig().getDouble("display.height-offset", 1.6);
        double z = (entrance.minZ() + entrance.maxZ() + 1) / 2.0;
        TextDisplay display = world.spawn(new Location(world, x, y, z), TextDisplay.class, entity -> {
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setShadowed(true);
            entity.setSeeThrough(false);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(105, 0, 0, 0));
            entity.setInvulnerable(true);
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING, room.name());
        });
        room.setDisplay(display);
        room.setLastDisplayText("");
    }

    private void updateDisplay(Room room, boolean force) {
        if (room.entrance() == null) return;
        ensureDisplay(room);
        TextDisplay display = room.display();
        if (display == null || !display.isValid()) return;
        int count = Math.min(2, room.inside().size());
        String status;
        if (room.state() == Room.State.FIGHTING) {
            status = "<red><bold>FIGHT IN PROGRESS</bold></red>";
        } else if (room.state() == Room.State.LOOTING) {
            long seconds = Math.max(0L, (room.lootEndsAtMillis() - System.currentTimeMillis() + 999L) / 1000L);
            status = "<gold><bold>LOOT TIME: " + seconds + "s</bold></gold>";
        } else if (count == 1) {
            status = "<yellow>Waiting for an opponent...</yellow>";
        } else {
            status = "<green>Right-click the glass to join</green>";
        }
        String text = "<gradient:#55E6FF:#FFFFFF><bold>1V1 ROOM</bold></gradient>\n"
                + "<gray>Players: <white><bold>" + count + "/2</bold></white></gray>\n" + status;
        if (force || !text.equals(room.lastDisplayText())) {
            display.text(Util.mm(text));
            room.setLastDisplayText(text);
        }
    }

    private void removeDisplay(Room room) {
        TextDisplay display = room.display();
        if (display != null) display.remove();
        room.setDisplay(null);
        room.setLastDisplayText("");
    }

    private void removeTaggedDisplays() {
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (display.getPersistentDataContainer().has(displayKey, PersistentDataType.STRING)) display.remove();
            }
        }
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
        for (Room room : rooms.values()) {
            reset(room, true, true);
            removeDisplay(room);
        }
        removeTaggedDisplays();
        flushSync();
    }
}
