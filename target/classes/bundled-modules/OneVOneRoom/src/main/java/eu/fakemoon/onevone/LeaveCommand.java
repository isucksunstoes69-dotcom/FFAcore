package eu.fakemoon.onevone;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class LeaveCommand implements CommandExecutor {

    private final RoomManager rooms;

    public LeaveCommand(RoomManager rooms) {
        this.rooms = rooms;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            Util.msg(sender, "<red>Players only.");
            return true;
        }
        rooms.leaveWaiting(player);
        return true;
    }
}
