package eu.fakemoon.onevone;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.TextDisplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Room {

    public enum State {
        WAITING,
        FIGHTING,
        LOOTING
    }

    private final String name;
    private Region region;
    private Region entrance;
    private Material entranceMaterial = Material.LIGHT_BLUE_STAINED_GLASS;
    private final Set<UUID> fighters = new LinkedHashSet<>();
    private final Set<UUID> inside = new LinkedHashSet<>();
    private final Set<UUID> deadFighters = new HashSet<>();
    private final Map<UUID, Location> returnLocations = new HashMap<>();
    /** Original block data from before this entrance became stained glass. */
    private List<String> entranceSnapshot = new ArrayList<>();
    private State state = State.WAITING;
    private UUID lootWinner;
    private long lootEndsAtMillis;
    private TextDisplay display;
    private String lastDisplayText = "";

    public Room(String name, Region region) {
        this.name = name;
        this.region = region;
    }

    public String name() {
        return name;
    }

    public Region region() {
        return region;
    }

    public void setRegion(Region region) {
        this.region = region;
    }

    public Region entrance() {
        return entrance;
    }

    public void setEntrance(Region entrance) {
        this.entrance = entrance;
    }

    public Material entranceMaterial() {
        return entranceMaterial;
    }

    public void setEntranceMaterial(Material entranceMaterial) {
        this.entranceMaterial = entranceMaterial;
    }

    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public boolean isActive() {
        return state == State.FIGHTING;
    }

    public boolean isBusy() {
        return state != State.WAITING || !inside.isEmpty();
    }

    public Set<UUID> fighters() {
        return fighters;
    }

    public Set<UUID> inside() {
        return inside;
    }

    public Set<UUID> deadFighters() {
        return deadFighters;
    }

    public Map<UUID, Location> returnLocations() {
        return returnLocations;
    }

    public List<String> entranceSnapshot() {
        return entranceSnapshot;
    }

    public void setEntranceSnapshot(List<String> snapshot) {
        this.entranceSnapshot = snapshot;
    }

    public UUID lootWinner() {
        return lootWinner;
    }

    public void setLootWinner(UUID lootWinner) {
        this.lootWinner = lootWinner;
    }

    public long lootEndsAtMillis() {
        return lootEndsAtMillis;
    }

    public void setLootEndsAtMillis(long lootEndsAtMillis) {
        this.lootEndsAtMillis = lootEndsAtMillis;
    }

    public TextDisplay display() {
        return display;
    }

    public void setDisplay(TextDisplay display) {
        this.display = display;
    }

    public String lastDisplayText() {
        return lastDisplayText;
    }

    public void setLastDisplayText(String lastDisplayText) {
        this.lastDisplayText = lastDisplayText;
    }
}
