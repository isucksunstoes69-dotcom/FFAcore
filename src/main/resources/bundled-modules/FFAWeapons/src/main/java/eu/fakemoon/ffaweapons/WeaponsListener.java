package eu.fakemoon.ffaweapons;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Cat;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import io.papermc.paper.event.player.PlayerShieldDisableEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class WeaponsListener implements Listener {

    private static final String ABILITY_DISPLAY_TAG = "ffaw_display";
    private static final String READY_TOKEN = "\u0280\u1d07\u1d00\u1d05\u028f";

    private final JavaPlugin plugin;
    private final Weapons weapons;
    private final BlacklistManager blacklist;
    private final Random random = new Random();
    /** Authoritative ability cooldowns, independent of item switching/dropping. */
    private final Map<UUID, Map<String, AbilityCooldown>> cooldowns = new HashMap<>();
    /** Players currently receiving the combined cooldown action bar. */
    private final Set<UUID> cooldownBarVisible = new HashSet<>();
    /** Remaining breeze mace dash charges per player (absent = full). */
    private final Map<UUID, Integer> breezeCharges = new HashMap<>();
    /** Server ticks through which each player's Mirror Shield remains active. */
    private final Map<UUID, Long> mirrorActiveUntil = new HashMap<>();
    /** Prevents reflected damage from bouncing forever between two active mirrors. */
    private final Set<UUID> reflectionDamageTargets = new HashSet<>();
    /** Temporary ItemDisplays owned by shield animations. */
    private final Set<UUID> abilityDisplays = new HashSet<>();
    /** Per-player display groups make shield animation cleanup idempotent and exception-safe. */
    private final Map<UUID, List<ItemDisplay>> mirrorDisplays = new HashMap<>();
    private final Map<UUID, List<ItemDisplay>> pulseDisplays = new HashMap<>();
    /** Most recent living target hit by each player, used by Meow Shield summons. */
    private final Map<UUID, LastHit> lastHits = new HashMap<>();
    /** Cats belonging to each active Meow Shield summon group. */
    private final Map<UUID, Set<UUID>> summonedCatGroups = new HashMap<>();
    private final Map<UUID, BukkitRunnable> summonedCatTasks = new HashMap<>();
    /** Prevents summoned-cat damage from retriggering held-weapon passives. */
    private final Set<UUID> summonedCatDamageOwners = new HashSet<>();
    /** Cats currently flying for Meow Blade; removed explicitly during plugin shutdown. */
    private final Set<UUID> launchedCats = new HashSet<>();
    /** Invisible arrow carriers provide native projectile physics and collision for each cat. */
    private final Map<UUID, CatShot> catShots = new HashMap<>();
    /** Original block data for the temporary Levo Axe stained-glass bubbles. */
    private final Map<Block, BlockData> levoBlocks = new HashMap<>();
    /** Blocks reserved by bubbles that are charging/building, preventing overlapping tasks. */
    private final Set<Block> reservedLevoBlocks = new HashSet<>();

    private record CatShot(Player owner, Cat cat, float explosionPower, boolean trueDamage) {
    }

    private record LastHit(UUID target, long expiresAt) {
    }

    private record AbilityCooldown(long startedAt, long expiresAt) {
        long totalTicks() {
            return Math.max(1L, expiresAt - startedAt);
        }
    }

    public WeaponsListener(JavaPlugin plugin, Weapons weapons, BlacklistManager blacklist) {
        this.plugin = plugin;
        this.weapons = weapons;
        this.blacklist = blacklist;
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateCooldownActionBars, 1L, 2L);
        removeTaggedAbilityDisplays();
    }

    // ---------------------------------------------------------------- actives

    // Run after protection/Skript handlers as well.  Several servers cancel
    // PlayerInteractEvent before custom weapons see it (WorldGuard regions,
    // spawn protection and the legacy Skript weapon script).  A cancelled
    // interaction must not prevent the ability itself; we explicitly deny the
    // vanilla item/block use below after dispatching the ability.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        String id = weapons.idOf(item);
        if (id == null) return;
        // Shields are normally carried in the offhand; other active weapons
        // remain main-hand-only to avoid duplicate interaction events.
        if (event.getHand() != EquipmentSlot.HAND && !id.equals("mirror_shield")) return;
        // Raising Mirror Shield is its activation gesture; other weapons keep
        // the sneak + right-click gesture.
        if (!player.isSneaking() && !id.equals("mirror_shield")) return;

        if (weapons.isAdminOnly(id) && !player.hasPermission("ffaweapons.bbc")) {
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
            player.sendActionBar(Util.mm("<red>You are not allowed to use this admin weapon."));
            return;
        }

        Cfg cfg = weapons.cfg();
        Integer cooldownTicks = switch (id) {
            case "dash_spear" -> cfg.dashSpearCd;
            case "dash_sword" -> cfg.dashSwordCd;
            case "dash_mace" -> cfg.dashMaceCd;
            case "adrenaline_blade" -> cfg.adrenalineCd;
            case "warden_blade", "warden_sword" -> cfg.wardenCd;
            case "breeze_mace" -> cfg.breezeCd;
            case "grapple_bow" -> cfg.grappleCd;
            case "levo_axe" -> cfg.levoCd;
            case "meow_blade" -> cfg.meowCd;
            case "mirror_shield" -> cfg.mirrorCd;
            case "bbc_blade" -> cfg.bbcCd;
            case "thunder_spear" -> cfg.thunderCd;
            case "webcleaver" -> cfg.webcleaverCd;
            default -> null; // passive-only weapon
        };
        if (cooldownTicks == null) return;
        String cooldownId = cooldownKey(id);

        // Let Mirror Shield raise normally on the same click that activates it.
        if (!id.equals("mirror_shield")) event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        if (blacklist.blocked(player.getLocation())) {
            Util.msg(player, "<red>Weapon abilities are disabled here.");
            return;
        }
        long now = Bukkit.getCurrentTick();
        if (isOnCooldown(player, item, cooldownId, now)) return;

        // Commit Mirror Shield's cooldown before spawning any effects so its
        // timer exists on the exact click that activates the ability.
        if (id.equals("mirror_shield")) {
            beginAbilityCooldown(player, cooldownId, cooldownTicks, now);
            activateMirrorShield(player);
            playActivationFx(player, id);
            sendCooldownActionBar(player, now);
            return;
        }

        // Breeze mace: multiple dash charges, cooldown only once they're spent.
        if (id.equals("breeze_mace")) {
            dash(player, cfg.breezeSpeed);
            playActivationFx(player, id);
            int left = breezeCharges.getOrDefault(player.getUniqueId(), cfg.breezeDashes) - 1;
            if (left <= 0) {
                breezeCharges.remove(player.getUniqueId());
                startCooldown(player, item, cooldownId, cooldownTicks, now);
                sendCooldownActionBar(player, now);
            } else {
                breezeCharges.put(player.getUniqueId(), left);
                player.sendActionBar(Util.mm("<aqua>ᴅᴀsʜᴇs ʟᴇꜰᴛ: <white>" + left + "</white>"));
            }
            return;
        }

        boolean fired = switch (id) {
            case "dash_spear" -> dash(player, cfg.dashSpearSpeed);
            case "dash_sword" -> dash(player, cfg.dashSwordSpeed);
            case "dash_mace" -> dash(player, cfg.dashMaceSpeed);
            case "adrenaline_blade" -> adrenalineRush(player);
            case "grapple_bow" -> dash(player, cfg.grappleSpeed);
            case "warden_blade", "warden_sword" -> wardenBeam(player);
            case "levo_axe" -> levoBubble(player);
            case "meow_blade" -> launchCat(player);
            case "bbc_blade" -> bbcBlast(player);
            case "thunder_spear" -> thunderSmite(player);
            case "webcleaver" -> clearWebs(player);
            default -> false;
        };
        if (!fired) return;

        playActivationFx(player, id);
        // Ability cooldowns must not prevent either custom shield from being
        // used as an ordinary shield between ability activations.
        startCooldown(player, item, cooldownId, cooldownTicks, now);
    }

    /** Meow Shield abilities use the vanilla F key (swap hands). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMeowShieldSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        ItemStack shield = weapons.idOf(event.getOffHandItem()) != null
                && weapons.idOf(event.getOffHandItem()).equals("meow_shield")
                ? event.getOffHandItem()
                : event.getMainHandItem();
        if (!"meow_shield".equals(weapons.idOf(shield))) return;
        event.setCancelled(true);

        if (blacklist.blocked(player.getLocation())) {
            Util.msg(player, "<red>Weapon abilities are disabled here.");
            return;
        }

        Cfg cfg = weapons.cfg();
        boolean summon = player.isSneaking();
        String ability = summon ? "meow_shield_summon" : "meow_shield_barrage";
        int cooldown = summon ? cfg.meowShieldSummonCd : cfg.meowShieldBarrageCd;
        long now = Bukkit.getCurrentTick();
        if (isOnCooldown(player, shield, ability, now)) return;

        if (summon && findRecentMeowTarget(player) == null) {
            player.sendActionBar(Util.mm("<red>Hit an enemy first so your cats have a target."));
            return;
        }

        beginAbilityCooldown(player, ability, cooldown, now);
        if (summon) {
            summonMeowCats(player);
        } else {
            launchMeowShieldBarrage(player);
            playActivationFx(player, "meow_shield");
        }
        sendCooldownActionBar(player, now);
    }

    private boolean isOnCooldown(Player player, ItemStack item, String id, long now) {
        AbilityCooldown ability = cooldowns.getOrDefault(player.getUniqueId(), Map.of()).get(id);
        long abilityRemaining = ability == null ? 0L : Math.max(0L, ability.expiresAt() - now);
        int nativeTicks = player.hasCooldown(item) ? player.getCooldown(item) : 0;
        if (abilityRemaining <= 0 && nativeTicks <= 0) return false;
        sendCooldownActionBar(player, now);
        return true;
    }

    private void startCooldown(Player player, ItemStack item, String id, int cooldownTicks, long now) {
        startCooldown(player, item, id, cooldownTicks, now, true);
    }

    private void startCooldown(Player player, ItemStack item, String id, int cooldownTicks,
                               long now, boolean showNativeCooldown) {
        beginAbilityCooldown(player, id, cooldownTicks, now);
        if (showNativeCooldown) player.setCooldown(item, cooldownTicks);
    }

    private void beginAbilityCooldown(Player player, String id, int cooldownTicks, long now) {
        cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new LinkedHashMap<>())
                .put(id, new AbilityCooldown(now, now + Math.max(1, cooldownTicks)));
        sendCooldownActionBar(player, now);
    }

    private void updateCooldownActionBars() {
        long now = Bukkit.getCurrentTick();
        var players = cooldowns.entrySet().iterator();
        while (players.hasNext()) {
            Map.Entry<UUID, Map<String, AbilityCooldown>> entry = players.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (entry.getValue().isEmpty()) {
                players.remove();
                if (cooldownBarVisible.remove(entry.getKey()) && player != null) {
                    player.sendActionBar(Util.mm(""));
                }
                continue;
            }
            if (player != null && !hasTrackedWeapon(player, entry.getValue().keySet())) {
                players.remove();
                cooldownBarVisible.remove(entry.getKey());
                player.sendActionBar(Util.mm(""));
                continue;
            }
            if (player != null && player.isOnline()) sendCooldownActionBar(player, now);
        }
    }

    private boolean hasTrackedWeapon(Player player, Set<String> ids) {
        for (ItemStack item : player.getInventory().getContents()) {
            String id = weapons.idOf(item);
            if (id != null && ids.stream().anyMatch(cooldown -> cooldownWeaponId(cooldown).equals(cooldownKey(id)))) return true;
        }
        return false;
    }

    private record CooldownGradient(String activeStart, String activeEnd,
                                    String readyStart, String readyEnd) {
    }

    private void sendCooldownActionBar(Player player, long now) {
        Map<String, AbilityCooldown> active = cooldowns.get(player.getUniqueId());
        if (active == null || active.isEmpty()) return;
        StringBuilder bar = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, AbilityCooldown> entry : active.entrySet()) {
            if (shown++ > 0) bar.append(" <#D8DEE8>|</#D8DEE8> ");
            AbilityCooldown cooldown = entry.getValue();
            long remaining = Math.max(0L, cooldown.expiresAt() - now);
            CooldownGradient gradient = cooldownGradient(entry.getKey());
            if (remaining > 0L) {
                bar.append("<bold><gradient:")
                        .append(gradient.activeStart()).append(':').append(gradient.activeEnd()).append('>')
                        .append((remaining + 19L) / 20L).append("s</gradient></bold>");
            } else {
                bar.append("<bold><gradient:")
                        .append(gradient.readyStart()).append(':').append(gradient.readyEnd()).append('>')
                        .append(READY_TOKEN).append("</gradient></bold>");
            }
        }
        cooldownBarVisible.add(player.getUniqueId());
        player.sendActionBar(Util.mm(bar.toString()));
    }

    private CooldownGradient cooldownGradient(String id) {
        return switch (id) {
            case "dash_spear" -> new CooldownGradient("#55FFFF", "#0066AA", "#D9FFFF", "#55CCDD");
            case "dash_sword" -> new CooldownGradient("#55FF55", "#00AA00", "#E5FFE5", "#55CC55");
            case "dash_mace" -> new CooldownGradient("#FF5555", "#AA0000", "#FFE5E5", "#FF7777");
            case "adrenaline_blade" -> new CooldownGradient("#FFAA00", "#FF5555", "#FFF0C2", "#FF9966");
            case "warden_blade", "warden_sword", "levo_axe" -> new CooldownGradient("#55FFFF", "#0099AA", "#E0FFFF", "#66DDEE");
            case "breeze_mace" -> new CooldownGradient("#B9E8FF", "#5599DD", "#F0FAFF", "#A8D8FF");
            case "venom_sword" -> new CooldownGradient("#55FF55", "#228822", "#E5FFE5", "#66CC66");
            case "grapple_bow" -> new CooldownGradient("#AA55FF", "#550055", "#F1DDFF", "#BB77EE");
            case "cobweb_axe" -> new CooldownGradient("#FF55FF", "#AA00AA", "#FFE5FF", "#DD77DD");
            case "zeus_sword", "lightning_sword", "thunder_spear" -> new CooldownGradient("#FFFF55", "#FFAA00", "#FFFFE0", "#FFD866");
            case "lifesteal_sword" -> new CooldownGradient("#FF5555", "#880000", "#FFE5E5", "#EE7777");
            case "meow_blade" -> new CooldownGradient("#FF55FF", "#FF5599", "#FFE5F5", "#FF99CC");
            case "stun_axe" -> new CooldownGradient("#AACCFF", "#5577AA", "#EDF5FF", "#99BBEE");
            case "mirror_shield" -> new CooldownGradient("#55FFFF", "#22AAAA", "#E0FFFF", "#77DDDD");
            case "pulse_shield" -> new CooldownGradient("#FF55FF", "#8800AA", "#FFE5FF", "#DD88EE");
            case "meow_shield_barrage", "meow_shield_summon" -> new CooldownGradient("#FFAAEE", "#FF5599", "#FFE5F5", "#FF99CC");
            case "bbc_blade" -> new CooldownGradient("#CC55FF", "#550055", "#F5E5FF", "#CC88EE");
            case "webcleaver" -> new CooldownGradient("#FFFFFF", "#AAAAAA", "#FFFFFF", "#D8DEE8");
            default -> new CooldownGradient("#4E66D4", "#B9E8FF", "#FFF6CF", "#D8A13A");
        };
    }

    private void sendCooldownActionBarLegacy(Player player, long now) {
        Map<String, AbilityCooldown> active = cooldowns.get(player.getUniqueId());
        if (active == null || active.isEmpty()) return;
        StringBuilder bar = new StringBuilder();
        int shown = 0;
        int readyShown = 0;
        for (Map.Entry<String, AbilityCooldown> entry : active.entrySet()) {
            AbilityCooldown cooldown = entry.getValue();
            long remaining = Math.max(0L, cooldown.expiresAt() - now);
            if (shown++ > 0) bar.append(" <#D8DEE8>|</#D8DEE8> ");
            if (remaining > 0L) {
                bar.append("<bold><gradient:#4E66D4:#B9E8FF>")
                        .append((remaining + 19L) / 20L)
                        .append("s</gradient></bold>");
            } else {
                bar.append(readyShown++ % 2 == 0
                        ? "<bold><gradient:#FFF6CF:#D8A13A>ʀᴇᴀᴅʏ</gradient></bold>"
                        : "<bold><gradient:#FFFFFF:#CFDCEB>ʀᴇᴀᴅʏ</gradient></bold>");
            }
        }
        if (shown == 0) return;
        cooldownBarVisible.add(player.getUniqueId());
        // Keep every known ability visible: a bold blue timer while cooling
        // down, then bold small-caps READY until that ability is used again.
        String rendered = bar.toString()
                .replaceAll("(<bold><gradient:#FFF6CF:#D8A13A>).*?(</gradient></bold>)", "$1" + READY_TOKEN + "$2")
                .replaceAll("(<bold><gradient:#FFFFFF:#CFDCEB>).*?(</gradient></bold>)", "$1" + READY_TOKEN + "$2");
        player.sendActionBar(Util.mm(rendered));
    }

    private String cooldownLabel(String id) {
        return switch (id) {
            case "dash_spear" -> "Dash Spear";
            case "dash_sword" -> "Dash Sword";
            case "dash_mace" -> "Dash Mace";
            case "adrenaline_blade" -> "Adrenaline";
            case "warden_blade", "warden_sword" -> "Warden";
            case "breeze_mace" -> "Breeze";
            case "grapple_bow" -> "Grapple";
            case "levo_axe" -> "Levi";
            case "meow_blade" -> "Meow";
            case "mirror_shield" -> "Mirror";
            case "pulse_shield" -> "Pulse";
            case "meow_shield_barrage" -> "Meow Barrage";
            case "meow_shield_summon" -> "Meow Summon";
            case "stun_axe" -> "Stun";
            case "bbc_blade" -> "BBC";
            case "thunder_spear" -> "Thunder";
            case "webcleaver" -> "Webcleaver";
            default -> id.replace('_', ' ');
        };
    }

    private String cooldownKey(String id) {
        return id.equals("warden_sword") ? "warden_blade" : id;
    }

    private String cooldownWeaponId(String id) {
        return switch (id) {
            case "meow_shield_barrage", "meow_shield_summon" -> "meow_shield";
            default -> cooldownKey(id);
        };
    }

    private boolean dash(Player player, double speed) {
        player.setVelocity(player.getLocation().getDirection().multiply(speed));
        return true;
    }

    /** One bounded, weapon-specific burst for every successful active ability. */
    private void playActivationFx(Player player, String id) {
        World world = player.getWorld();
        Location feet = player.getLocation().add(0, 0.15, 0);
        Location center = player.getLocation().add(0, 1.0, 0);
        Vector direction = player.getEyeLocation().getDirection().normalize();

        switch (id) {
            case "dash_spear" -> {
                Particle.DustOptions dust = dust(65, 220, 255, 1.3f);
                dustRing(world, feet, 1.35, 24, dust, Particle.ELECTRIC_SPARK);
                dustLine(world, center, center.clone().add(direction.multiply(4.0)), 24,
                        dust, Particle.END_ROD);
                world.playSound(feet, Sound.ITEM_TRIDENT_RIPTIDE_1, 1.0f, 1.35f);
            }
            case "dash_sword" -> {
                Particle.DustOptions dust = dust(130, 255, 180, 1.25f);
                dustRing(world, feet, 1.45, 24, dust, Particle.SWEEP_ATTACK);
                world.spawnParticle(Particle.CRIT, center, 18, 0.55, 0.45, 0.55, 0.18);
                world.playSound(feet, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.25f);
            }
            case "dash_mace" -> {
                Particle.DustOptions dust = dust(255, 105, 35, 1.45f);
                dustRing(world, feet, 1.6, 26, dust, Particle.DUST_PLUME);
                world.spawnParticle(Particle.GUST_EMITTER_SMALL, feet, 1);
                world.playSound(feet, Sound.ITEM_MACE_SMASH_AIR, 1.1f, 0.85f);
            }
            case "adrenaline_blade" -> {
                dustRing(world, feet, 1.15, 24, dust(255, 45, 70, 1.35f), Particle.HEART);
                dustRing(world, center, 0.75, 18, dust(255, 190, 45, 1.1f), Particle.TOTEM_OF_UNDYING);
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, center, 24, 0.65, 0.8, 0.65, 0.25);
                world.playSound(center, Sound.ITEM_TOTEM_USE, 0.8f, 1.35f);
            }
            case "warden_blade", "warden_sword" -> {
                Particle.DustOptions dust = dust(35, 225, 205, 1.45f);
                dustRing(world, feet, 1.55, 28, dust, Particle.SCULK_SOUL);
                world.spawnParticle(Particle.SCULK_SOUL, center, 20, 0.55, 0.75, 0.55, 0.08);
            }
            case "breeze_mace" -> {
                Particle.DustOptions dust = dust(190, 245, 255, 1.2f);
                dustRing(world, feet, 1.45, 24, dust, Particle.SMALL_GUST);
                world.spawnParticle(Particle.CLOUD, center, 20, 0.6, 0.5, 0.6, 0.08);
                world.spawnParticle(Particle.GUST_EMITTER_SMALL, feet, 1);
                world.playSound(feet, Sound.ENTITY_BREEZE_WIND_BURST, 1.0f, 1.25f);
            }
            case "grapple_bow" -> {
                Particle.DustOptions dust = dust(195, 95, 255, 1.2f);
                dustRing(world, feet, 1.2, 20, dust, Particle.REVERSE_PORTAL);
                dustLine(world, player.getEyeLocation(),
                        player.getEyeLocation().add(direction.multiply(5.0)), 26,
                        dust, Particle.REVERSE_PORTAL);
                world.playSound(center, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.45f);
            }
            case "levo_axe" -> {
                dustRing(world, feet, 2.0, 30, dust(70, 195, 255, 1.5f), Particle.END_ROD);
                world.spawnParticle(Particle.BUBBLE_POP, center, 24, 1.0, 0.8, 1.0, 0.08);
            }
            case "meow_blade" -> {
                dustRing(world, feet, 1.2, 22, dust(255, 105, 190, 1.25f), Particle.HEART);
                world.spawnParticle(Particle.CHERRY_LEAVES, center, 18, 0.7, 0.7, 0.7, 0.05);
            }
            case "meow_shield" -> {
                dustRing(world, feet, 1.65, 28, dust(255, 105, 190, 1.45f), Particle.HEART);
                world.spawnParticle(Particle.FIREWORK, center, 28, 0.7, 0.8, 0.7, 0.08);
                world.playSound(center, Sound.ENTITY_CAT_AMBIENT, 1.2f, 1.25f);
            }
            case "mirror_shield" ->
                    dustRing(world, feet, 1.75, 28, dust(95, 245, 255, 1.45f), Particle.END_ROD);
            case "bbc_blade" -> {
                dustRing(world, feet, 1.7, 28, dust(125, 35, 190, 1.65f), Particle.REVERSE_PORTAL);
                world.spawnParticle(Particle.REVERSE_PORTAL, center, 30, 0.7, 0.9, 0.7, 0.12);
            }
            case "thunder_spear" -> {
                dustRing(world, feet, 1.5, 24, dust(255, 225, 55, 1.35f), Particle.ELECTRIC_SPARK);
                world.spawnParticle(Particle.ELECTRIC_SPARK, center, 24, 0.6, 0.8, 0.6, 0.18);
            }
            case "webcleaver" -> {
                dustRing(world, feet, 1.75, 26, dust(235, 245, 255, 1.3f), Particle.SWEEP_ATTACK);
                world.spawnParticle(Particle.ITEM_COBWEB, center, 20, 0.8, 0.55, 0.8, 0.08);
            }
            default -> {
            }
        }
    }

    private Particle.DustOptions dust(int red, int green, int blue, float size) {
        return new Particle.DustOptions(Color.fromRGB(red, green, blue), size);
    }

    private void flash(World world, Location location, Color color) {
        world.spawnParticle(Particle.FLASH, location, 1, 0, 0, 0, 0, color);
    }

    private void dustRing(World world, Location center, double radius, int requestedPoints,
                          Particle.DustOptions dust, Particle accent) {
        int points = Math.max(8, Math.min(32, requestedPoints));
        for (int point = 0; point < points; point++) {
            double angle = Math.PI * 2.0 * point / points;
            Location location = center.clone().add(Math.cos(angle) * radius, 0,
                    Math.sin(angle) * radius);
            world.spawnParticle(Particle.DUST, location, 1, 0, 0, 0, 0, dust);
            if (accent != null && point % 4 == 0) {
                world.spawnParticle(accent, location, 1, 0.03, 0.03, 0.03, 0.01);
            }
        }
    }

    private void dustLine(World world, Location start, Location end, int requestedPoints,
                          Particle.DustOptions dust, Particle accent) {
        Vector delta = end.toVector().subtract(start.toVector());
        int points = Math.max(2, Math.min(32, requestedPoints));
        for (int point = 0; point <= points; point++) {
            Location location = start.clone().add(delta.clone().multiply(point / (double) points));
            world.spawnParticle(Particle.DUST, location, 1, 0, 0, 0, 0, dust);
            if (accent != null && point % 3 == 0) {
                world.spawnParticle(accent, location, 1, 0.025, 0.025, 0.025, 0.01);
            }
        }
    }

    private boolean adrenalineRush(Player player) {
        Cfg cfg = weapons.cfg();
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        double cap = maxHealth == null ? 20.0 : maxHealth.getValue();
        player.setHealth(cap);

        // Absorption I supplies two yellow hearts; round up so the configured
        // number is always met. Replacing the old effect avoids the deprecated
        // forced-effect overload and keeps the ability's duration predictable.
        int amplifier = Math.max(0, (int) Math.ceil(cfg.adrenalineYellowHearts / 2.0) - 1);
        player.removePotionEffect(PotionEffectType.ABSORPTION);
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,
                cfg.adrenalineAbsorptionSeconds * 20, amplifier, true, true, true));
        player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0, 1.8, 0),
                8, 0.35, 0.35, 0.35);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.3f);
        return true;
    }

    private boolean activateMirrorShield(Player player) {
        Cfg cfg = weapons.cfg();
        UUID playerId = player.getUniqueId();
        long activeUntil = Bukkit.getCurrentTick() + cfg.mirrorDurationTicks;
        mirrorActiveUntil.put(playerId, activeUntil);

        World world = player.getWorld();
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(100, 255, 255), 1.5f);
        List<ItemDisplay> displays = beginShieldDisplayGroup(
                mirrorDisplays, playerId, cfg.mirrorDurationTicks);
        ItemStack displayItem = weapons.item("mirror_shield");
        for (int index = 0; index < 4; index++) {
            displays.add(spawnShieldDisplay(world, player.getLocation().add(0, 1.1, 0),
                    displayItem, Color.AQUA));
        }

        flash(world, player.getLocation().add(0, 1, 0), Color.AQUA);
        world.spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1, 0),
                30, 0.8, 1.0, 0.8, 0.05);
        world.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.3f, 1.5f);
        player.sendActionBar(Util.mm("<aqua>Mirror Shield active for <white>"
                + cfg.mirrorDurationTicks / 20 + "s</white>"));

        new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                Long expiry = mirrorActiveUntil.get(playerId);
                if (!player.isOnline() || player.isDead() || player.getWorld() != world
                        || expiry == null || expiry != activeUntil
                        || Bukkit.getCurrentTick() >= activeUntil || blacklist.blocked(player.getLocation())) {
                    if (expiry != null && expiry == activeUntil) mirrorActiveUntil.remove(playerId);
                    clearShieldDisplayGroup(mirrorDisplays, playerId, displays);
                    cancel();
                    return;
                }

                Location center = player.getLocation().add(0, 1.05, 0);
                for (int index = 0; index < displays.size(); index++) {
                    ItemDisplay display = displays.get(index);
                    if (!display.isValid() || display.getWorld() != player.getWorld()) continue;
                    double angle = elapsed * 0.18 + index * (Math.PI * 2 / displays.size());
                    double y = Math.sin(elapsed * 0.22 + index) * 0.25;
                    Location orbit = center.clone().add(Math.cos(angle) * 1.35, y,
                            Math.sin(angle) * 1.35);
                    display.teleport(orbit);
                    world.spawnParticle(Particle.DUST, orbit, 1, 0.04, 0.04, 0.04, 0, dust);
                    if ((elapsed + index) % 3 == 0) {
                        world.spawnParticle(Particle.ELECTRIC_SPARK, orbit, 1, 0.05, 0.05, 0.05, 0.01);
                    }
                }
                elapsed++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private ItemDisplay spawnShieldDisplay(World world, Location location, ItemStack item, Color glow) {
        ItemDisplay display = world.spawn(location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
            spawned.setBillboard(Display.Billboard.CENTER);
            spawned.setBrightness(new Display.Brightness(15, 15));
            spawned.setGlowing(true);
            spawned.setGlowColorOverride(glow);
            spawned.setTeleportDuration(1);
            spawned.setViewRange(32f);
            spawned.setGravity(false);
            spawned.setInvulnerable(true);
            spawned.setPersistent(false);
            spawned.addScoreboardTag(ABILITY_DISPLAY_TAG);
        });
        abilityDisplays.add(display.getUniqueId());
        return display;
    }

    private List<ItemDisplay> beginShieldDisplayGroup(Map<UUID, List<ItemDisplay>> groups,
                                                       UUID owner, long lifetimeTicks) {
        clearShieldDisplayGroup(groups, owner, null);
        List<ItemDisplay> displays = new ArrayList<>();
        groups.put(owner, displays);
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> clearShieldDisplayGroup(groups, owner, displays), Math.max(1L, lifetimeTicks));
        return displays;
    }

    private void clearShieldDisplayGroup(Map<UUID, List<ItemDisplay>> groups, UUID owner,
                                         List<ItemDisplay> expected) {
        List<ItemDisplay> current = groups.get(owner);
        if (current == null || expected != null && current != expected) return;
        groups.remove(owner);
        removeDisplays(current);
    }

    private void removeDisplays(List<ItemDisplay> displays) {
        for (ItemDisplay display : displays) {
            display.remove();
            abilityDisplays.remove(display.getUniqueId());
        }
    }

    private void removeTaggedAbilityDisplays() {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (!display.getScoreboardTags().contains(ABILITY_DISPLAY_TAG)) continue;
                display.remove();
                abilityDisplays.remove(display.getUniqueId());
            }
        }
    }

    /** Builds the Skript-style stained-glass shield sphere around the caster. */
    private boolean levoBubble(Player player) {
        Cfg cfg = weapons.cfg();
        Location center = player.getLocation().getBlock().getLocation().add(0.5, 0.5, 0.5);
        if (blacklist.blocked(center)) {
            player.sendActionBar(Util.mm("<red>That location is in a protected zone."));
            return false;
        }

        World world = player.getWorld();
        List<Block> shell = bubbleShell(world, center, cfg.levoRadius);
        if (shell.isEmpty()) {
            player.sendActionBar(Util.mm("<red>There is not enough open space for a bubble."));
            return false;
        }
        if (shell.stream().anyMatch(reservedLevoBlocks::contains)) {
            player.sendActionBar(Util.mm("<red>Another Levi bubble is already forming here."));
            return false;
        }
        reservedLevoBlocks.addAll(shell);

        List<List<Block>> layers = bubbleLayers(shell);
        Map<Block, BlockData> owned = new HashMap<>();
        Particle.DustOptions bubbleDust = dust(70, 195, 255, 1.8f);
        world.playSound(center, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1f, 1f);
        new BukkitRunnable() {
            private int activeTicks;
            private int chargeFrame;
            private int nextLayer;
            private int remainingLayers = layers.size();
            private boolean built;
            private boolean collapsing;

            @Override
            public void run() {
                if (!collapsing && (!player.isOnline() || blacklist.blocked(center))) {
                    restoreBubble(shell, owned, world, null);
                    reservedLevoBlocks.removeAll(shell);
                    cancel();
                    return;
                }
                if (collapsing) {
                    restoreBubble(layers.get(--remainingLayers), owned, world, bubbleDust);
                    world.playSound(center, Sound.BLOCK_GLASS_BREAK, 0.25f, 1.5f);
                    if (remainingLayers == 0) {
                        reservedLevoBlocks.removeAll(shell);
                        cancel();
                    }
                    return;
                }

                if (chargeFrame < 10) {
                    drawLeviCharge(world, center, chargeFrame++, bubbleDust);
                    return;
                }

                if (!built) {
                    for (Block block : layers.get(nextLayer++)) placeBubbleBlock(block, owned);
                    world.playSound(center, Sound.BLOCK_GLASS_PLACE, 0.2f, 1.6f);
                    if (nextLayer == layers.size()) {
                        built = true;
                        Location burst = center.clone().add(0, 1, 0);
                        dustRing(world, center.clone().add(0, 0.5, 0), cfg.levoRadius, 32,
                                dust(80, 205, 255, 1.4f), Particle.END_ROD);
                        world.spawnParticle(Particle.END_ROD, burst, 28, 1.2, 1.4, 1.2, 0.08);
                        flash(world, burst, Color.fromRGB(80, 205, 255));
                    }
                    return;
                }

                activeTicks += 2;
                if (activeTicks >= cfg.levoDuration * 20) collapsing = true;
            }
        }.runTaskTimer(plugin, 0L, 2L);
        return true;
    }

    private List<Block> bubbleShell(World world, Location center, double configuredRadius) {
        int radius = Math.max(1, (int) Math.round(configuredRadius));
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();
        List<Block> shell = new ArrayList<>();
        double inner = Math.max(0, radius - 1.0);
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    double distance = Math.sqrt(x * x + y * y + z * z);
                    if (distance < inner || distance > radius) continue;
                    Block block = world.getBlockAt(centerX + x, centerY + y, centerZ + z);
                    if (block.isEmpty()) shell.add(block);
                }
            }
        }
        shell.sort((left, right) -> Integer.compare(left.getY(), right.getY()));
        return shell;
    }

    private List<List<Block>> bubbleLayers(List<Block> shell) {
        List<List<Block>> layers = new ArrayList<>();
        int previousY = Integer.MIN_VALUE;
        for (Block block : shell) {
            if (block.getY() != previousY) {
                layers.add(new ArrayList<>());
                previousY = block.getY();
            }
            layers.getLast().add(block);
        }
        return layers;
    }

    private void placeBubbleBlock(Block block, Map<Block, BlockData> owned) {
        if (!block.isEmpty() || levoBlocks.containsKey(block)) return;
        BlockData original = block.getBlockData();
        levoBlocks.put(block, original);
        owned.put(block, original);
        block.setType(Material.LIGHT_BLUE_STAINED_GLASS, false);
    }

    private void restoreBubble(List<Block> blocks, Map<Block, BlockData> owned,
                               World world, Particle.DustOptions dust) {
        int restored = 0;
        for (int index = blocks.size() - 1; index >= 0; index--) {
            Block block = blocks.get(index);
            BlockData original = owned.remove(block);
            if (original == null) continue;
            levoBlocks.remove(block, original);
            if (original != null && block.getType() == Material.LIGHT_BLUE_STAINED_GLASS) {
                if (dust != null && restored++ % 4 == 0) {
                    world.spawnParticle(Particle.DUST, block.getLocation().toCenterLocation(),
                            1, 0, 0, 0, 0, dust);
                }
                block.setBlockData(original, false);
            }
        }
    }

    private void drawLeviCharge(World world, Location center, int frame, Particle.DustOptions dust) {
        double radius = (frame + 1) / 1.5 + 0.5;
        int points = Math.max(16, Math.min(32, (int) Math.round(radius * 16)));
        for (int point = 0; point < points; point++) {
            double angle = Math.PI * 2 * point / points;
            Location location = center.clone().add(Math.cos(angle) * radius, 0.5,
                    Math.sin(angle) * radius);
            world.spawnParticle(Particle.DUST, location, 1, 0, 0, 0, 0, dust);
        }
    }

    private boolean launchCat(Player player) {
        Cfg cfg = weapons.cfg();
        return launchCatProjectile(player, player.getEyeLocation().getDirection().normalize(),
                cfg.meowSpeed, cfg.meowExplosionPower, cfg.meowFlightTicks, false);
    }

    private boolean launchCatProjectile(Player player, Vector launchDirection, double speed,
                                        float explosionPower, int flightTicks, boolean trueDamage) {
        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector direction = launchDirection.clone().normalize();
        Location launch = eye.clone().add(direction.clone().multiply(0.8));
        Arrow arrow = player.launchProjectile(Arrow.class, direction.clone().multiply(speed));
        arrow.teleport(launch);
        arrow.setVelocity(direction.clone().multiply(speed));
        arrow.setInvisible(true);
        arrow.setDamage(0);
        arrow.setKnockbackStrength(0);
        arrow.setCritical(false);
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        arrow.setPersistent(false);
        arrow.setGravity(true);

        Cat cat = world.spawn(launch, Cat.class);
        launchedCats.add(cat.getUniqueId());
        cat.setAI(false);
        cat.setGravity(false);
        cat.setCollidable(false);
        cat.setInvulnerable(true);
        cat.setSilent(true);
        cat.setPersistent(false);
        arrow.addPassenger(cat);
        catShots.put(arrow.getUniqueId(), new CatShot(player, cat, explosionPower, trueDamage));
        world.playSound(launch, Sound.ENTITY_CAT_AMBIENT, 1.2f, 1.15f);
        Particle.DustOptions catTrailDust = dust(255, 95, 185, 1.0f);

        new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                CatShot shot = catShots.get(arrow.getUniqueId());
                if (shot == null) {
                    cancel();
                    return;
                }
                if (!arrow.isValid() || !cat.isValid() || cat.isDead() || !player.isOnline()) {
                    removeCatShot(arrow, shot);
                    cancel();
                    return;
                }
                Location location = arrow.getLocation();
                if (blacklist.blocked(location)) {
                    removeCatShot(arrow, shot);
                    cancel();
                    return;
                }
                if (elapsed >= flightTicks || arrow.isInBlock()) {
                    catShots.remove(arrow.getUniqueId());
                    detonateCatShot(arrow, shot, location);
                    cancel();
                    return;
                }
                Vector velocity = arrow.getVelocity();
                if (velocity.lengthSquared() > 0.001) {
                    Location facing = location.clone();
                    facing.setDirection(velocity);
                    cat.setRotation(facing.getYaw(), facing.getPitch());
                }
                world.spawnParticle(Particle.HEART, location.clone().add(0, 0.4, 0), 1, 0.08, 0.08, 0.08, 0);
                world.spawnParticle(Particle.DUST, location.clone().add(0, 0.25, 0),
                        2, 0.08, 0.08, 0.08, 0, catTrailDust);
                if (elapsed % 2 == 0) {
                    world.spawnParticle(Particle.END_ROD, location, 1, 0.05, 0.05, 0.05, 0.01);
                }
                elapsed++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    private boolean launchMeowShieldBarrage(Player player) {
        Cfg cfg = weapons.cfg();
        Vector base = player.getEyeLocation().getDirection().normalize();
        int count = Math.max(1, cfg.meowShieldBarrageCount);
        for (int index = 0; index < count; index++) {
            int shot = index;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || player.isDead() || blacklist.blocked(player.getLocation())) return;
                double spread = (shot - (count - 1) / 2.0) * 0.075;
                Vector direction = base.clone().rotateAroundY(spread);
                launchCatProjectile(player, direction, cfg.meowShieldBarrageSpeed,
                        cfg.meowShieldBarrageExplosionPower, cfg.meowShieldBarrageFlightTicks, true);
            }, index * 2L);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_CAT_AMBIENT, 1.4f, 1.1f);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCatImpact(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        CatShot shot = catShots.remove(arrow.getUniqueId());
        if (shot == null) return;
        detonateCatShot(arrow, shot, arrow.getLocation());
    }

    private void detonateCatShot(Arrow arrow, CatShot shot, Location location) {
        Cat cat = shot.cat();
        Player owner = shot.owner();
        float power = shot.explosionPower();
        World world = location.getWorld();
        launchedCats.remove(cat.getUniqueId());
        arrow.eject();
        if (arrow.isValid()) arrow.remove();
        if (cat.isValid()) cat.remove();
        if (blacklist.blocked(location)) return;
        dustRing(world, location, Math.max(1.2, power), 28,
                dust(255, 90, 180, 1.5f), Particle.FIREWORK);
        world.spawnParticle(Particle.CHERRY_LEAVES, location, 28, 0.9, 0.7, 0.9, 0.12);
        flash(world, location, Color.fromRGB(255, 90, 180));
        world.spawnParticle(Particle.EXPLOSION_EMITTER, location, 1);
        world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f);
        if (explosionTouchesProtectedTarget(world, location, power)) return;
        if (shot.trueDamage()) {
            double radius = Math.max(1.0, power * 2.0);
            double damage = weapons.cfg().meowShieldBarrageDamage;
            for (Entity entity : world.getNearbyEntities(location, radius, radius, radius)) {
                if (!(entity instanceof LivingEntity living) || living == owner || living.isDead()) continue;
                if (blacklist.blocked(living.getLocation())) continue;
                if (dealTrueDamage(living, owner, damage)) {
                    Vector push = living.getLocation().toVector().subtract(location.toVector());
                    if (push.lengthSquared() < 0.001) push = owner.getLocation().getDirection();
                    living.setVelocity(push.normalize().multiply(0.55).setY(0.35));
                }
            }
        } else {
            // false/false guarantees no fire or terrain damage from the explosion.
            world.createExplosion(location, power, false, false, owner);
        }
    }

    private void removeCatShot(Arrow arrow, CatShot shot) {
        catShots.remove(arrow.getUniqueId());
        launchedCats.remove(shot.cat().getUniqueId());
        arrow.eject();
        if (arrow.isValid()) arrow.remove();
        if (shot.cat().isValid()) shot.cat().remove();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void rememberLastHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target) || target == attacker || target.isDead()) return;
        if (summonedCatDamageOwners.contains(attacker.getUniqueId()) || event.getFinalDamage() <= 0) return;
        lastHits.put(attacker.getUniqueId(), new LastHit(target.getUniqueId(),
                Bukkit.getCurrentTick() + weapons.cfg().meowShieldTargetWindowTicks));
    }

    private LivingEntity findRecentMeowTarget(Player owner) {
        UUID ownerId = owner.getUniqueId();
        LastHit hit = lastHits.get(ownerId);
        if (hit == null || hit.expiresAt() <= Bukkit.getCurrentTick()) {
            lastHits.remove(ownerId);
            return null;
        }
        Entity entity = Bukkit.getEntity(hit.target());
        if (!(entity instanceof LivingEntity target) || target.isDead() || !target.isValid()
                || target.getWorld() != owner.getWorld() || target == owner) {
            lastHits.remove(ownerId);
            return null;
        }
        return target;
    }

    private void summonMeowCats(Player owner) {
        LivingEntity target = findRecentMeowTarget(owner);
        if (target == null) return;
        UUID ownerId = owner.getUniqueId();
        clearSummonedCats(ownerId);

        Cfg cfg = weapons.cfg();
        World world = owner.getWorld();
        Location origin = owner.getLocation().clone();
        Set<UUID> cats = new HashSet<>();
        Map<UUID, Location> spawnLocations = new HashMap<>();
        for (int index = 0; index < cfg.meowShieldSummonCount; index++) {
            double angle = index * (Math.PI * 2.0 / cfg.meowShieldSummonCount);
            double radius = 1.15 + (index % 2) * 0.45;
            Location belowGround = origin.clone().add(Math.cos(angle) * radius, -1.0,
                    Math.sin(angle) * radius);
            Cat cat = world.spawn(belowGround, Cat.class);
            cat.setAI(false);
            cat.setGravity(false);
            cat.setCollidable(false);
            cat.setInvulnerable(true);
            cat.setSilent(false);
            cat.setPersistent(false);
            cat.setInvisible(true);
            cat.setLyingDown(false);
            cat.setHeadUp(true);
            cats.add(cat.getUniqueId());
            spawnLocations.put(cat.getUniqueId(), belowGround);
        }
        summonedCatGroups.put(ownerId, cats);
        owner.getWorld().playSound(origin, Sound.ENTITY_CAT_AMBIENT, 1.6f, 0.75f);
        owner.getWorld().spawnParticle(Particle.POOF, origin.clone().add(0, 0.1, 0),
                36, 1.2, 0.15, 1.2, 0.08);

        BukkitRunnable task = new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                Set<UUID> active = summonedCatGroups.get(ownerId);
                if (active == null || !owner.isOnline() || owner.isDead()
                        || elapsed >= cfg.meowShieldSummonDurationTicks) {
                    clearSummonedCats(ownerId);
                    cancel();
                    return;
                }
                LivingEntity currentTarget = findRecentMeowTarget(owner);
                if (currentTarget == null) {
                    clearSummonedCats(ownerId);
                    cancel();
                    return;
                }

                double rise = Math.min(1.0, elapsed / 10.0);
                for (UUID catId : new HashSet<>(active)) {
                    Entity entity = Bukkit.getEntity(catId);
                    if (!(entity instanceof Cat cat) || !cat.isValid()) {
                        active.remove(catId);
                        continue;
                    }
                    if (elapsed < 12) {
                        Location base = spawnLocations.get(catId);
                        if (base != null) cat.teleport(base.clone().add(0, rise * 1.05, 0));
                        if (elapsed >= 10) cat.setInvisible(false);
                        world.spawnParticle(Particle.DUST, cat.getLocation().add(0, 0.2, 0),
                                3, 0.15, 0.2, 0.15, 0, dust(255, 105, 190, 1.0f));
                        world.spawnParticle(Particle.POOF, cat.getLocation(), 1,
                                0.12, 0.12, 0.12, 0.02);
                        continue;
                    }

                    Location targetLocation = currentTarget.getLocation().add(0, 0.8, 0);
                    Vector toTarget = targetLocation.toVector().subtract(cat.getLocation().toVector());
                    double distance = toTarget.length();
                    if (distance > 6.0) {
                        // Move directly rather than relying on Cat AI/physics: these summoned cats
                        // are non-AI and no-gravity entities, so direct movement keeps the chase reliable.
                        double step = Math.min(0.34, Math.max(0.12, distance * 0.10));
                        Location next = cat.getLocation().clone()
                                .add(toTarget.normalize().multiply(step));
                        cat.teleport(next);
                        Location facing = next.clone();
                        facing.setDirection(toTarget);
                        cat.setRotation(facing.getYaw(), facing.getPitch());
                    } else {
                        // Once close, circle and reposition around the target like a pack of dogs,
                        // instead of standing still. The small orbit radius keeps every cat in attack range.
                        int slot = Math.floorMod(catId.hashCode(), Math.max(1, cfg.meowShieldSummonCount));
                        double orbitAngle = elapsed * 0.075
                                + slot * (Math.PI * 2.0 / Math.max(1, cfg.meowShieldSummonCount));
                        double orbitRadius = 2.1 + (slot % 2) * 0.45;
                        Location desired = targetLocation.clone().add(
                                Math.cos(orbitAngle) * orbitRadius,
                                0.05 + Math.sin(orbitAngle * 1.7 + slot) * 0.12,
                                Math.sin(orbitAngle) * orbitRadius);
                        Vector toDesired = desired.toVector().subtract(cat.getLocation().toVector());
                        if (toDesired.lengthSquared() > 0.02) {
                            double step = Math.min(0.28, toDesired.length());
                            Location next = cat.getLocation().clone().add(toDesired.normalize().multiply(step));
                            cat.teleport(next);
                        }
                        Location facing = cat.getLocation().clone();
                        facing.setDirection(targetLocation.toVector().subtract(cat.getLocation().toVector()));
                        cat.setRotation(facing.getYaw(), facing.getPitch());
                    }
                    if (elapsed % cfg.meowShieldSummonAttackInterval == 0 && distance <= 3.0
                            && currentTarget.isValid() && !currentTarget.isDead()) {
                        summonedCatDamageOwners.add(ownerId);
                        try {
                            dealTrueDamage(currentTarget, owner, cfg.meowShieldSummonDamage);
                        } finally {
                            summonedCatDamageOwners.remove(ownerId);
                        }
                        world.spawnParticle(Particle.HEART, targetLocation, 3,
                                0.2, 0.3, 0.2, 0.05);
                        world.spawnParticle(Particle.CRIT, targetLocation, 5,
                                0.2, 0.3, 0.2, 0.08);
                        world.playSound(targetLocation, Sound.ENTITY_CAT_HURT, 0.45f, 1.35f);
                    }
                }
                elapsed++;
            }
        };
        summonedCatTasks.put(ownerId, task);
        task.runTaskTimer(plugin, 0L, 1L);
    }

    private void clearSummonedCats(UUID ownerId) {
        BukkitRunnable task = summonedCatTasks.remove(ownerId);
        if (task != null) task.cancel();
        Set<UUID> cats = summonedCatGroups.remove(ownerId);
        if (cats == null) return;
        for (UUID catId : cats) {
            Entity entity = Bukkit.getEntity(catId);
            if (entity != null) entity.remove();
        }
    }

    /** Avoid a native explosion when its radius would cross into a protected weapon zone. */
    private boolean explosionTouchesProtectedTarget(World world, Location location, float power) {
        double radius = Math.max(1.0, power * 2.0);
        for (Entity entity : world.getNearbyEntities(location, radius, radius, radius)) {
            if (entity instanceof LivingEntity && blacklist.blocked(entity.getLocation())) return true;
        }
        return false;
    }

    private boolean bbcBlast(Player player) {
        Cfg cfg = weapons.cfg();
        Entity target = player.getTargetEntity(cfg.bbcRange);
        if (!(target instanceof LivingEntity living) || living == player) {
            player.sendActionBar(Util.mm("<red>No target in range."));
            return false;
        }
        if (blacklist.blocked(living.getLocation())) {
            player.sendActionBar(Util.mm("<red>Your target is in a protected zone."));
            return false;
        }
        if (!dealTrueDamage(living, player, cfg.bbcDamage)) return false;

        Location location = living.getLocation();
        World world = living.getWorld();
        Particle.DustOptions blastDust = dust(125, 30, 185, 1.7f);
        dustLine(world, player.getEyeLocation(), location.clone().add(0, 1, 0), 32,
                blastDust, Particle.REVERSE_PORTAL);
        dustRing(world, location.clone().add(0, 0.2, 0), 2.0, 32,
                blastDust, Particle.REVERSE_PORTAL);
        world.strikeLightningEffect(location);
        flash(world, location.clone().add(0, 1, 0), Color.fromRGB(145, 45, 210));
        world.spawnParticle(Particle.EXPLOSION_EMITTER, location.clone().add(0, 1, 0), 1);
        world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f);
        Vector knockback = location.toVector().subtract(player.getLocation().toVector());
        if (knockback.lengthSquared() < 0.001) knockback = player.getLocation().getDirection();
        living.setVelocity(knockback.normalize().multiply(1.6).setY(0.7));
        return true;
    }

    private boolean wardenBeam(Player player) {
        Cfg cfg = weapons.cfg();
        World startingWorld = player.getWorld();
        startingWorld.playSound(player.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.5f, 1f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || player.isDead() || blacklist.blocked(player.getLocation())) return;
            World world = player.getWorld();
            Location eye = player.getEyeLocation();
            Vector direction = eye.getDirection().normalize();
            Set<UUID> hit = new HashSet<>();
            int visualHits = 0;
            for (int step = 1; step <= cfg.wardenRange; step++) {
                Location point = eye.clone().add(direction.clone().multiply(step));
                Block block = point.getBlock();
                if (!block.isPassable()) break;
                world.spawnParticle(Particle.SONIC_BOOM, point, 1);
                for (Entity entity : world.getNearbyEntities(point, 2, 2, 2)) {
                    if (entity == player || !(entity instanceof LivingEntity living)) continue;
                    if (!hit.add(entity.getUniqueId())) continue;
                    if (blacklist.blocked(living.getLocation())) continue;
                    living.damage(cfg.wardenDamage, player);
                    if (visualHits++ < 8) {
                        Location impact = living.getLocation().add(0, Math.min(1.0, living.getHeight() * 0.5), 0);
                        dustRing(world, impact, 0.75, 16,
                                dust(25, 220, 200, 1.25f), Particle.SCULK_SOUL);
                    }
                }
            }
            world.playSound(eye, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 1f);
        }, 15L);
        return true;
    }

    private boolean thunderSmite(Player player) {
        Cfg cfg = weapons.cfg();
        Entity target = player.getTargetEntity(cfg.thunderRange);
        if (!(target instanceof LivingEntity living) || living == player) {
            player.sendActionBar(Util.mm("<red>ɴᴏ ᴛᴀʀɢᴇᴛ ɪɴ ʀᴀɴɢᴇ"));
            return false;
        }
        if (blacklist.blocked(living.getLocation())) {
            player.sendActionBar(Util.mm("<red>ʏᴏᴜʀ ᴛᴀʀɢᴇᴛ ɪs ɪɴ ᴀ ᴘʀᴏᴛᴇᴄᴛᴇᴅ ᴢᴏɴᴇ"));
            return false;
        }
        Location start = player.getEyeLocation();
        Location impact = living.getLocation().add(0, Math.min(1.0, living.getHeight() * 0.5), 0);
        Particle.DustOptions thunderDust = dust(255, 225, 45, 1.4f);
        dustLine(living.getWorld(), start, impact, 30, thunderDust, Particle.ELECTRIC_SPARK);
        dustRing(living.getWorld(), living.getLocation().add(0, 0.15, 0), 1.35, 24,
                thunderDust, Particle.ELECTRIC_SPARK);
        flash(living.getWorld(), impact, Color.fromRGB(255, 230, 60));
        smite(living, player, cfg.thunderDamage);
        player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 1f, 1.2f);
        living.getWorld().playSound(impact, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.1f, 1.15f);
        return true;
    }

    /** Visual-only lightning (never places fire) + direct damage. */
    private void smite(LivingEntity victim, Player attacker, double damage) {
        victim.getWorld().strikeLightningEffect(victim.getLocation());
        victim.damage(damage, attacker);
    }

    /** Zeus lightning is true damage: it uses Paper's armor-bypassing sonic source for the damage transaction. */
    private void zeusStrike(LivingEntity victim, Player attacker, double damage) {
        if (!dealTrueDamage(victim, attacker, damage)) return;
        World world = victim.getWorld();
        Location impact = victim.getLocation().add(0, Math.min(1.0, victim.getHeight() * 0.5), 0);
        Particle.DustOptions zeusDust = dust(255, 235, 65, 1.45f);
        dustRing(world, victim.getLocation().add(0, 0.15, 0), 1.35, 24,
                zeusDust, Particle.ELECTRIC_SPARK);
        world.spawnParticle(Particle.ELECTRIC_SPARK, impact, 30, 0.65, 1.0, 0.65, 0.22);
        flash(world, impact, Color.fromRGB(255, 240, 90));
        world.playSound(impact, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.1f, 1.3f);
        world.strikeLightningEffect(victim.getLocation());
    }

    private boolean dealTrueDamage(LivingEntity victim, Player attacker, double damage) {
        if (damage <= 0 || victim.isDead() || victim.isInvulnerable()) return false;
        if (victim instanceof Player target
                && (target.getGameMode() == GameMode.CREATIVE || target.getGameMode() == GameMode.SPECTATOR)) {
            return false;
        }
        DamageSource source = DamageSource.builder(DamageType.SONIC_BOOM)
                .withCausingEntity(attacker)
                .withDirectEntity(attacker)
                .build();
        victim.damage(damage, source);
        return true;
    }

    private boolean clearWebs(Player player) {
        Cfg cfg = weapons.cfg();
        int radius = cfg.webcleaverRadius;
        World world = player.getWorld();
        Location center = player.getLocation();
        int removed = 0;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z > radius * radius) continue;
                    Block block = world.getBlockAt(center.getBlockX() + x, center.getBlockY() + y, center.getBlockZ() + z);
                    if (block.getType() != Material.COBWEB) continue;
                    block.setType(Material.AIR);
                    removed++;
                    if (removed <= 24) {
                        Location cleared = block.getLocation().toCenterLocation();
                        world.spawnParticle(Particle.ITEM_COBWEB, cleared, 3, 0.25, 0.25, 0.25, 0.06);
                        world.spawnParticle(Particle.POOF, cleared, 1, 0.15, 0.15, 0.15, 0.02);
                    }
                }
            }
        }
        if (removed == 0) {
            player.sendActionBar(Util.mm("<red>ɴᴏ ᴡᴇʙs ɴᴇᴀʀʙʏ"));
            return false;
        }
        world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.8f);
        player.sendActionBar(Util.mm("<aqua>ʀᴇᴍᴏᴠᴇᴅ <white>" + removed + "</white> ᴡᴇʙs"));
        return true;
    }

    // ---------------------------------------------------------------- passives

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMirrorShieldDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player defender)) return;
        if (reflectionDamageTargets.contains(defender.getUniqueId())) return;

        Long expiry = mirrorActiveUntil.get(defender.getUniqueId());
        long now = Bukkit.getCurrentTick();
        if (expiry == null) return;
        if (expiry <= now) {
            mirrorActiveUntil.remove(defender.getUniqueId());
            return;
        }

        LivingEntity attacker = reflectedAttacker(event);
        if (attacker == null || attacker == defender || attacker.isDead()) return;
        if (blacklist.blocked(defender.getLocation()) || blacklist.blocked(attacker.getLocation())) return;

        // Reflect the raw attack so the attacker's own armor is applied once.
        double reflectedDamage = event.getDamage() * weapons.cfg().mirrorDamageMultiplier;
        if (reflectedDamage <= 0) return;
        event.setCancelled(true);
        mirrorReflectionEffect(defender, attacker);

        reflectionDamageTargets.add(attacker.getUniqueId());
        try {
            attacker.damage(reflectedDamage, defender);
        } finally {
            reflectionDamageTargets.remove(attacker.getUniqueId());
        }
    }

    private LivingEntity reflectedAttacker(EntityDamageByEntityEvent event) {
        Entity causing = event.getDamageSource().getCausingEntity();
        if (causing instanceof LivingEntity living) return living;
        Entity damager = event.getDamager();
        if (damager instanceof LivingEntity living) return living;
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof LivingEntity shooter) return shooter;
        return null;
    }

    private void mirrorReflectionEffect(Player defender, LivingEntity attacker) {
        World world = defender.getWorld();
        Location start = defender.getLocation().add(0, 1, 0);
        Location end = attacker.getLocation().add(0, Math.min(1.0, attacker.getHeight() * 0.5), 0);
        Vector line = end.toVector().subtract(start.toVector());
        int points = Math.max(4, (int) Math.ceil(line.length() * 3));
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(120, 255, 255), 1.4f);
        for (int point = 0; point <= points; point++) {
            Location location = start.clone().add(line.clone().multiply(point / (double) points));
            world.spawnParticle(Particle.ELECTRIC_SPARK, location, 2, 0.05, 0.05, 0.05, 0.02);
            world.spawnParticle(Particle.DUST, location, 1, 0, 0, 0, 0, dust);
        }
        flash(world, start, Color.AQUA);
        world.playSound(start, Sound.ITEM_SHIELD_BLOCK, 1.4f, 1.8f);
        world.playSound(end, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 0.8f);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAdminWeaponHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        String id = weapons.idOf(attacker.getInventory().getItemInMainHand());
        if (id != null && weapons.isAdminOnly(id) && !attacker.hasPermission("ffaweapons.bbc")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (summonedCatDamageOwners.contains(attacker.getUniqueId())) return;
        if (reflectionDamageTargets.contains(victim.getUniqueId())) return;
        if (victim instanceof Player player && isMirrorActive(player)) return;
        // Zeus/BBC true damage is itself an EntityDamageByEntityEvent. Do not
        // recursively roll the held weapon's passive for that secondary hit.
        if (event.getDamageSource().getDamageType().equals(DamageType.SONIC_BOOM)) return;
        String id = weapons.idOf(attacker.getInventory().getItemInMainHand());
        if (id == null) return;
        if (weapons.isAdminOnly(id) && !attacker.hasPermission("ffaweapons.bbc")) {
            return;
        }
        if (blacklist.blocked(attacker.getLocation()) || blacklist.blocked(victim.getLocation())) return;
        Cfg cfg = weapons.cfg();

        switch (id) {
            case "venom_sword" -> {
                if (random.nextDouble() < cfg.venomChance) {
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON,
                            cfg.venomSeconds * 20, cfg.venomAmplifier));
                    Location impact = victim.getLocation().add(0, 0.9, 0);
                    dustRing(victim.getWorld(), victim.getLocation().add(0, 0.15, 0), 1.0, 22,
                            dust(65, 235, 75, 1.35f), Particle.WITCH);
                    victim.getWorld().spawnParticle(Particle.WITCH, impact,
                            18, 0.4, 0.55, 0.4, 0.12);
                    victim.getWorld().spawnParticle(Particle.ITEM_SLIME,
                            impact, 12, 0.3, 0.5, 0.3);
                    victim.getWorld().playSound(victim.getLocation(), Sound.ENTITY_SPIDER_HURT, 0.8f, 1.4f);
                }
            }
            case "cobweb_axe" -> {
                if (random.nextDouble() < cfg.cobwebChance) {
                    Block block = victim.getLocation().getBlock();
                    if (block.getType() == Material.AIR) {
                        block.setType(Material.COBWEB);
                        Location impact = victim.getLocation().add(0, 0.8, 0);
                        dustRing(victim.getWorld(), victim.getLocation().add(0, 0.15, 0), 1.0, 22,
                                dust(235, 245, 255, 1.2f), Particle.POOF);
                        victim.getWorld().spawnParticle(Particle.ITEM_COBWEB, impact,
                                22, 0.45, 0.6, 0.45, 0.08);
                        victim.getWorld().playSound(impact, Sound.BLOCK_COBWEB_PLACE, 1.0f, 1.2f);
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (block.getType() == Material.COBWEB) block.setType(Material.AIR);
                        }, cfg.cobwebSeconds * 20L);
                    }
                }
            }
            default -> {
            }
        }
    }

    /** Resolves delayed passives after other plugins have had a chance to cancel the original melee hit. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHitFinal(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (summonedCatDamageOwners.contains(attacker.getUniqueId())) return;
        if (reflectionDamageTargets.contains(victim.getUniqueId())) return;
        if (event.getDamageSource().getDamageType().equals(DamageType.SONIC_BOOM)) return;
        ItemStack item = attacker.getInventory().getItemInMainHand();
        String id = weapons.idOf(item);
        if (id == null) return;
        if (blacklist.blocked(attacker.getLocation()) || blacklist.blocked(victim.getLocation())) return;
        Cfg cfg = weapons.cfg();

        switch (id) {
            case "zeus_sword", "lightning_sword" -> {
                if (event.getFinalDamage() <= 0 || random.nextDouble() >= cfg.zeusChance) return;
                if (blacklist.blocked(victim.getLocation())) return;
                queueZeusStrike(event, attacker, victim, cfg.zeusDamage);
            }
            case "lifesteal_sword" -> {
                if (event.getFinalDamage() <= 0 || random.nextDouble() >= cfg.lifestealChance) return;
                queueLifesteal(event, attacker, victim, cfg.lifestealHeal);
            }
            case "stun_axe" -> {
                if (!(victim instanceof Player target) || !target.isBlocking()) return;
                if (blacklist.blocked(target.getLocation())) return;
                queueShieldStun(event, attacker, target, item, cfg.stunSeconds, cfg.stunCd);
            }
            default -> {
            }
        }
    }

    private void queueZeusStrike(EntityDamageByEntityEvent event, Player attacker, LivingEntity victim, double damage) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled() || !attacker.isOnline() || victim.isDead()) return;
            if (blacklist.blocked(attacker.getLocation()) || blacklist.blocked(victim.getLocation())) return;
            zeusStrike(victim, attacker, damage);
        });
    }

    private void queueLifesteal(EntityDamageByEntityEvent event, Player attacker,
                                LivingEntity victim, double heal) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled() || !attacker.isOnline() || attacker.isDead()) return;
            if (blacklist.blocked(attacker.getLocation())) return;
            var maxHealth = attacker.getAttribute(Attribute.MAX_HEALTH);
            double cap = maxHealth == null ? 20.0 : maxHealth.getValue();
            attacker.setHealth(Math.min(cap, attacker.getHealth() + heal));
            Location healed = attacker.getLocation().add(0, 1.2, 0);
            Particle.DustOptions bloodDust = dust(210, 20, 55, 1.25f);
            if (victim.getWorld() == attacker.getWorld()) {
                Location drained = victim.getLocation().add(0, Math.min(1.0, victim.getHeight() * 0.5), 0);
                dustLine(attacker.getWorld(), drained, healed, 24, bloodDust, Particle.HEART);
            }
            dustRing(attacker.getWorld(), attacker.getLocation().add(0, 0.15, 0),
                    0.9, 20, bloodDust, Particle.HEART);
            attacker.getWorld().spawnParticle(Particle.HEART, attacker.getLocation().add(0, 2, 0),
                    6, 0.3, 0.3, 0.3);
            attacker.getWorld().playSound(healed, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.35f);
        });
    }

    private void queueShieldStun(EntityDamageByEntityEvent event, Player attacker, Player target,
                                  ItemStack item, int shieldStunSeconds, int cooldownTicks) {
        boolean pulseWasRaised = "pulse_shield".equals(weapons.idOf(target.getActiveItem()));
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled() || !attacker.isOnline() || !target.isOnline()) return;
            if (blacklist.blocked(attacker.getLocation()) || blacklist.blocked(target.getLocation())) return;
            long now = Bukkit.getCurrentTick();
            if (isOnCooldown(attacker, item, "stun_axe", now)) return;
            if (pulseWasRaised) triggerPulseShield(target, attacker);
            ItemStack raisedShield = findRaisedShield(target);
            target.clearActiveItem();
            Location stunImpact = target.getLocation().add(0, 1.0, 0);
            Particle.DustOptions stunDust = dust(120, 175, 220, 1.45f);
            dustRing(target.getWorld(), target.getLocation().add(0, 0.2, 0), 1.25, 24,
                    stunDust, Particle.ELECTRIC_SPARK);
            target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, stunImpact,
                    28, 0.55, 0.75, 0.55, 0.2);
            flash(target.getWorld(), stunImpact, Color.fromRGB(125, 185, 235));
            target.getWorld().spawnParticle(Particle.POOF, stunImpact,
                    14, 0.4, 0.55, 0.4, 0.08);
            int shieldCooldown = shieldStunSeconds * 20;
            // Registered custom shields use their own UseCooldown groups, so
            // cool down both the raised stack's group and vanilla shields.
            if (raisedShield != null) target.setCooldown(raisedShield, shieldCooldown);
            target.setCooldown(Material.SHIELD, shieldCooldown);
            startCooldown(attacker, item, "stun_axe", cooldownTicks, now);
            attacker.getWorld().playSound(attacker.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 1f);
        });
    }

    private boolean isMirrorActive(Player player) {
        Long expiry = mirrorActiveUntil.get(player.getUniqueId());
        if (expiry == null) return false;
        if (expiry > Bukkit.getCurrentTick()) return true;
        mirrorActiveUntil.remove(player.getUniqueId());
        return false;
    }

    /** Handles Pulse Shield before cancelling the vanilla roll for our deterministic Stun Axe. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onShieldDisable(PlayerShieldDisableEvent event) {
        Entity damager = event.getDamager();
        boolean customStun = damager instanceof Player attacker
                && "stun_axe".equals(weapons.idOf(attacker.getInventory().getItemInMainHand()));

        if ((!event.isCancelled() || customStun) && damager instanceof LivingEntity enemy) {
            Player defender = event.getPlayer();
            if ("pulse_shield".equals(weapons.idOf(defender.getActiveItem()))) {
                triggerPulseShield(defender, enemy);
            }
        }
        if (customStun) event.setCancelled(true);
    }

    private ItemStack findRaisedShield(Player player) {
        ItemStack active = player.getActiveItem();
        if (active.getType() == Material.SHIELD) return active;
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main.getType() == Material.SHIELD) return main;
        ItemStack off = player.getInventory().getItemInOffHand();
        return off.getType() == Material.SHIELD ? off : null;
    }

    private void triggerPulseShield(Player defender, LivingEntity enemy) {
        if (enemy == defender || enemy.isDead()) return;
        if (blacklist.blocked(defender.getLocation()) || blacklist.blocked(enemy.getLocation())) return;

        Cfg cfg = weapons.cfg();
        long now = Bukkit.getCurrentTick();
        AbilityCooldown pulseCooldown = cooldowns.getOrDefault(defender.getUniqueId(), Map.of())
                .get("pulse_shield");
        if (pulseCooldown != null && pulseCooldown.expiresAt() > now) {
            sendCooldownActionBar(defender, now);
            return;
        }
        beginAbilityCooldown(defender, "pulse_shield", cfg.pulseCd, now);

        Vector knockback = enemy.getLocation().toVector().subtract(defender.getLocation().toVector());
        if (knockback.lengthSquared() < 0.001) knockback = defender.getLocation().getDirection();
        knockback.setY(0).normalize().multiply(cfg.pulseKnockback).setY(0.65);
        Vector appliedKnockback = knockback;
        // Apply after the triggering attack finishes so vanilla hit knockback
        // cannot overwrite the pulse velocity later in the same server tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!enemy.isValid() || enemy.isDead()) return;
            if (blacklist.blocked(defender.getLocation()) || blacklist.blocked(enemy.getLocation())) return;
            enemy.setVelocity(appliedKnockback);
        });
        pulseShieldAnimation(defender, cfg.pulseRadius);
        // Keep the cooldown as the final action-bar packet on the trigger tick.
        sendCooldownActionBar(defender, now);
    }

    private void pulseShieldAnimation(Player defender, double maximumRadius) {
        World world = defender.getWorld();
        Location center = defender.getLocation().add(0, 1, 0);
        ItemStack displayItem = weapons.item("pulse_shield");
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(220, 80, 255), 1.7f);
        UUID defenderId = defender.getUniqueId();
        List<ItemDisplay> displays = beginShieldDisplayGroup(pulseDisplays, defenderId, 15L);
        for (int index = 0; index < 8; index++) {
            displays.add(spawnShieldDisplay(world, center, displayItem, Color.FUCHSIA));
        }

        world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1);
        world.spawnParticle(Particle.GUST_EMITTER_LARGE, center, 1);
        flash(world, center, Color.FUCHSIA);
        world.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 1.6f);
        world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1f, 1.8f);

        new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                if (elapsed >= 15) {
                    clearShieldDisplayGroup(pulseDisplays, defenderId, displays);
                    cancel();
                    return;
                }
                double radius = Math.max(0.5, maximumRadius * elapsed / 14.0);
                for (int index = 0; index < displays.size(); index++) {
                    double angle = index * (Math.PI * 2 / displays.size()) + elapsed * 0.08;
                    Location location = center.clone().add(Math.cos(angle) * radius,
                            Math.sin(elapsed * 0.35 + index) * 0.35,
                            Math.sin(angle) * radius);
                    ItemDisplay display = displays.get(index);
                    if (display.isValid()) display.teleport(location);
                    world.spawnParticle(Particle.DUST, location, 2, 0.08, 0.08, 0.08, 0, dust);
                    world.spawnParticle(Particle.ELECTRIC_SPARK, location, 2, 0.08, 0.08, 0.08, 0.03);
                }
                elapsed++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        clearPlayerAbilityState(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClearCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().trim();
        String command = message.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        if (command.equals("/clear") || command.equals("/minecraft:clear")) {
            clearPlayerAbilityState(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        String id = weapons.idOf(event.getItemDrop().getItemStack());
        if (id == null) return;
        String cooldownId = cooldownKey(id);
        Map<String, AbilityCooldown> active = cooldowns.get(player.getUniqueId());
        if (active == null) return;
        active.keySet().removeIf(key -> cooldownWeaponId(key).equals(cooldownId));
        if (active.isEmpty()) {
            cooldowns.remove(player.getUniqueId());
            cooldownBarVisible.remove(player.getUniqueId());
            player.sendActionBar(Util.mm(""));
        } else {
            sendCooldownActionBar(player, Bukkit.getCurrentTick());
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        // A second clear after the respawn packet prevents the client from
        // retaining an action bar that was visible on the death screen.
        Bukkit.getScheduler().runTask(plugin, () -> event.getPlayer().sendActionBar(Util.mm("")));
    }

    private void clearPlayerAbilityState(Player player) {
        UUID playerId = player.getUniqueId();
        cooldowns.remove(playerId);
        cooldownBarVisible.remove(playerId);
        breezeCharges.remove(playerId);
        lastHits.remove(playerId);
        clearSummonedCats(playerId);
        mirrorActiveUntil.remove(playerId);
        reflectionDamageTargets.remove(playerId);
        clearShieldDisplayGroup(mirrorDisplays, playerId, null);
        clearShieldDisplayGroup(pulseDisplays, playerId, null);
        player.sendActionBar(Util.mm(""));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearPlayerAbilityState(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBubbleBreak(BlockBreakEvent event) {
        if (levoBlocks.containsKey(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBubblePistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(levoBlocks::containsKey)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBubblePistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(levoBlocks::containsKey)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBubbleExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(levoBlocks::containsKey);
    }

    /** Called by the plugin at shutdown so temporary cats and glass cannot remain in the world. */
    public void cleanup() {
        for (UUID owner : new HashSet<>(mirrorDisplays.keySet())) {
            clearShieldDisplayGroup(mirrorDisplays, owner, null);
        }
        for (UUID owner : new HashSet<>(pulseDisplays.keySet())) {
            clearShieldDisplayGroup(pulseDisplays, owner, null);
        }
        for (UUID displayId : new HashSet<>(abilityDisplays)) {
            Entity display = Bukkit.getEntity(displayId);
            if (display != null) display.remove();
        }
        abilityDisplays.clear();
        removeTaggedAbilityDisplays();
        mirrorActiveUntil.clear();
        reflectionDamageTargets.clear();
        for (Map.Entry<UUID, CatShot> entry : new HashMap<>(catShots).entrySet()) {
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity instanceof Arrow arrow) removeCatShot(arrow, entry.getValue());
            else if (entry.getValue().cat().isValid()) entry.getValue().cat().remove();
        }
        catShots.clear();
        for (UUID catId : new HashSet<>(launchedCats)) {
            Entity entity = Bukkit.getEntity(catId);
            if (entity != null) entity.remove();
        }
        launchedCats.clear();
        for (Map.Entry<Block, BlockData> entry : new HashMap<>(levoBlocks).entrySet()) {
            Block block = entry.getKey();
            if (block.getType() == Material.LIGHT_BLUE_STAINED_GLASS) {
                block.setBlockData(entry.getValue(), false);
            }
        }
        levoBlocks.clear();
        reservedLevoBlocks.clear();
        cooldowns.clear();
        cooldownBarVisible.clear();
        breezeCharges.clear();
        lastHits.clear();
        for (UUID owner : new HashSet<>(summonedCatGroups.keySet())) clearSummonedCats(owner);
        summonedCatTasks.clear();
        summonedCatGroups.clear();
        summonedCatDamageOwners.clear();
    }
}
