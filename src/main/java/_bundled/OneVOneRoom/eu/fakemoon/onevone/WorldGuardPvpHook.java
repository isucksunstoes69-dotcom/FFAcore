package eu.fakemoon.onevone;

import com.sk89q.worldguard.bukkit.protection.events.DisallowedPVPEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Loaded only when WorldGuard is present. */
final class WorldGuardPvpHook implements Listener {

    private final RoomManager rooms;

    WorldGuardPvpHook(RoomManager rooms) {
        this.rooms = rooms;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDisallowedPvp(DisallowedPVPEvent event) {
        Room room = rooms.roomOf(event.getDefender().getUniqueId());
        if (room != null && room.state() == Room.State.FIGHTING
                && rooms.roomOf(event.getAttacker().getUniqueId()) == room
                && room.fighters().contains(event.getDefender().getUniqueId())
                && room.fighters().contains(event.getAttacker().getUniqueId())) {
            // Cancelling WorldGuard's dedicated denial event allows only this
            // registered duel; it does not un-cancel unrelated Bukkit events.
            event.setCancelled(true);
        }
    }
}
