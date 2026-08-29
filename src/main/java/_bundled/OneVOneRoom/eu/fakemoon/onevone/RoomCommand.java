package eu.fakemoon.onevone;

import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class RoomCommand implements CommandExecutor, TabCompleter {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,24}");
    private static final List<String> SUBCOMMANDS = List.of("wand", "create", "entrance", "delete", "list", "stop");
    private static final List<String> COLORS = Arrays.stream(DyeColor.values())
            .map(color -> color.name().toLowerCase(Locale.ROOT)).toList();
    private static final long MAX_ENTRANCE_VOLUME = 20_000;

    private final RoomManager rooms;
    private final SelectionManager selection;

    public RoomCommand(OneVOnePlugin plugin, RoomManager rooms, SelectionManager selection) {
        this.rooms = rooms;
        this.selection = selection;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "wand" -> wand(sender);
            case "create" -> create(sender, args);
            case "entrance" -> entrance(sender, args);
            case "delete" -> delete(sender, args);
            case "list" -> list(sender);
            case "stop" -> stop(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        Util.msg(sender, "<gold>1v1 room commands:");
        sender.sendMessage(Util.mm("<gray> /1v1room wand <dark_gray>- selection wand (WorldEdit/FAWE also works)"));
        sender.sendMessage(Util.mm("<gray> /1v1room create <name> <dark_gray>- save the selected room"));
        sender.sendMessage(Util.mm("<gray> /1v1room entrance <name> <color> <dark_gray>- create its glass portal"));
        sender.sendMessage(Util.mm("<gray> /1v1room delete <name>"));
        sender.sendMessage(Util.mm("<gray> /1v1room list"));
        sender.sendMessage(Util.mm("<gray> /1v1room stop <name> <dark_gray>- reset and evacuate the room"));
    }

    private void wand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Util.msg(sender, "<red>Players only.");
            return;
        }
        selection.resetBuiltIn(player);
        player.getInventory().addItem(selection.wand());
        Util.msg(player, "<green>Wand given. Left click = pos1, right click = pos2."
                + (selection.worldEditPresent() ? " <gray>(WorldEdit selections work too)" : ""));
    }

    private Region requireSelection(Player player) {
        Region region = selection.selection(player);
        if (region == null) {
            Util.msg(player, "<red>No selection. Select the area with "
                    + (selection.worldEditPresent() ? "<white>//wand</white> or " : "")
                    + "<white>/1v1room wand</white> first.");
        }
        return region;
    }

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Util.msg(sender, "<red>Players only.");
            return;
        }
        if (args.length < 2) {
            Util.msg(sender, "<red>Usage: /1v1room create <name>");
            return;
        }
        String name = args[1].toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            Util.msg(sender, "<red>Room names may only use a-z, 0-9, _ and - (max 24 chars).");
            return;
        }
        if (rooms.get(name) != null) {
            Util.msg(sender, "<red>A room named <white>" + name + "</white> already exists.");
            return;
        }
        Region region = requireSelection(player);
        if (region == null) return;
        long footprint = (long) (region.maxX() - region.minX() + 1)
                * (region.maxZ() - region.minZ() + 1);
        if (footprint > RoomManager.MAX_ROOM_FOOTPRINT) {
            Util.msg(player, "<red>That room footprint is too large (max "
                    + RoomManager.MAX_ROOM_FOOTPRINT + " blocks).");
            return;
        }
        if (rooms.roomOverlaps(region, null)) {
            Util.msg(player, "<red>That selection overlaps an existing 1v1 room.");
            return;
        }
        if (rooms.roomTouchesEntrance(region)) {
            Util.msg(player, "<red>That selection overlaps an existing 1v1 entrance.");
            return;
        }
        rooms.create(name, region);
        Util.msg(player, "<green>Room <yellow>" + name + "</yellow> created: <white>" + region + "</white>");
        Util.msg(player, "<gray>Now select its doorway and run <white>/1v1room entrance " + name
                + " light_blue</white>.");
    }

    private void entrance(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Util.msg(sender, "<red>Players only.");
            return;
        }
        if (args.length < 3) {
            Util.msg(sender, "<red>Usage: /1v1room entrance <name> <color>");
            Util.msg(sender, "<gray>Colors: <white>" + String.join(", ", COLORS) + "</white>");
            return;
        }
        Room room = rooms.get(args[1]);
        if (room == null) {
            Util.msg(sender, "<red>No room named <white>" + args[1] + "</white>.");
            return;
        }
        if (room.isBusy()) {
            Util.msg(player, "<red>Stop or empty this room before changing its entrance.");
            return;
        }
        DyeColor color = parseColor(args[2]);
        if (color == null) {
            Util.msg(player, "<red>Unknown color. Use one of: <white>" + String.join(", ", COLORS) + "</white>");
            return;
        }
        Region region = requireSelection(player);
        if (region == null) return;
        if (region.volume() > MAX_ENTRANCE_VOLUME) {
            Util.msg(player, "<red>That entrance is huge (" + region.volume()
                    + " blocks) - select just the doorway (max " + MAX_ENTRANCE_VOLUME + ").");
            return;
        }
        if (!region.world().equals(room.region().world())) {
            Util.msg(player, "<red>The entrance must be in the same world as the room.");
            return;
        }
        if (rooms.entranceOverlaps(region, room)) {
            Util.msg(player, "<red>That selection overlaps another room entrance.");
            return;
        }
        if (rooms.entranceTouchesAnotherRoom(region, room)) {
            Util.msg(player, "<red>That entrance touches another 1v1 arena.");
            return;
        }
        if (rooms.containsTileState(region)) {
            Util.msg(player, "<red>The entrance contains a container, sign, or another data block."
                    + " <gray>Select only air/simple building blocks so no data is lost.");
            return;
        }
        Material material = Material.valueOf(color.name() + "_STAINED_GLASS");
        rooms.setEntrance(room, region, material);
        Util.msg(player, "<green>Entrance of <yellow>" + room.name() + "</yellow> is now <white>"
                + color.name().toLowerCase(Locale.ROOT) + " stained glass</white>.");
        Util.msg(player, "<gray>Players can right-click any portal block to enter. The display above it is live.");
    }

    private DyeColor parseColor(String input) {
        String normalized = input.toUpperCase(Locale.ROOT).replace('-', '_').replace(" ", "_");
        if (normalized.equals("LIGHTBLUE")) normalized = "LIGHT_BLUE";
        if (normalized.equals("LIGHTGRAY") || normalized.equals("SILVER")) normalized = "LIGHT_GRAY";
        try {
            return DyeColor.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void delete(CommandSender sender, String[] args) {
        Room room = roomArg(sender, args);
        if (room == null) return;
        rooms.delete(room);
        Util.msg(sender, "<green>Room <yellow>" + room.name()
                + "</yellow> deleted; its original entrance blocks were restored.");
    }

    private void list(CommandSender sender) {
        if (rooms.all().isEmpty()) {
            Util.msg(sender, "<gray>No rooms yet. Make a selection and run <white>/1v1room create <name></white>.");
            return;
        }
        Util.msg(sender, "<gold>1v1 rooms (" + rooms.all().size() + "):");
        for (Room room : rooms.all()) {
            String status;
            if (room.entrance() == null) {
                status = "<yellow>no entrance</yellow>";
            } else if (room.state() == Room.State.FIGHTING) {
                status = "<red>FIGHTING</red>";
            } else if (room.state() == Room.State.LOOTING) {
                long seconds = Math.max(0L, (room.lootEndsAtMillis() - System.currentTimeMillis() + 999L) / 1000L);
                status = "<gold>LOOTING " + seconds + "s</gold>";
            } else {
                status = "<green>WAITING</green> <dark_gray>(" + room.inside().size() + "/2)";
            }
            sender.sendMessage(Util.mm(" <yellow>" + room.name() + "</yellow> <dark_gray>-</dark_gray> " + status));
        }
    }

    private void stop(CommandSender sender, String[] args) {
        Room room = roomArg(sender, args);
        if (room == null) return;
        if (!room.isBusy()) {
            Util.msg(sender, "<red>No players or fight are active in <white>" + room.name() + "</white>.");
            return;
        }
        rooms.reset(room, true, true);
        Util.msg(sender, "<green>Room <yellow>" + room.name() + "</yellow> reset and evacuated.");
    }

    private Room roomArg(CommandSender sender, String[] args) {
        if (args.length < 2) {
            Util.msg(sender, "<red>Usage: /1v1room " + (args.length == 0 ? "" : args[0]) + " <name>");
            return null;
        }
        Room room = rooms.get(args[1]);
        if (room == null) Util.msg(sender, "<red>No room named <white>" + args[1] + "</white>.");
        return room;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("create") && !args[0].equalsIgnoreCase("wand")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return rooms.all().stream().map(Room::name).filter(name -> name.startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("entrance")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return COLORS.stream().filter(color -> color.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
