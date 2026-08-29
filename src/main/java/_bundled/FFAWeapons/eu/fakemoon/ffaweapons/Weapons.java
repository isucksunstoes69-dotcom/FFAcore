package eu.fakemoon.ffaweapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.UseCooldownComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The weapon roster. Items carry a PDC id (survives kits, chests, serialization)
 * and their own cooldown group so same-material weapons never share a cooldown
 * display. Lore is generated in the exact style of the weapons Skript: gray
 * small-caps enchant lines, strikethrough separators, white/bold ability text,
 * aqua stats — with all numbers pulled live from config.yml.
 */
public final class Weapons {

    private record Ench(Enchantment enchantment, int level) {
    }

    private static final String SEP = "<dark_gray><strikethrough>                    </strikethrough>";
    private static final String[] ROMAN = {"", "ɪ", "ɪɪ", "ɪɪɪ", "ɪᴠ", "ᴠ"};
    private static final Map<Enchantment, String> ENCH_NAMES = Map.ofEntries(
            Map.entry(Enchantment.SHARPNESS, "sʜᴀʀᴘɴᴇss"),
            Map.entry(Enchantment.SWEEPING_EDGE, "sᴡᴇᴇᴘɪɴɢ ᴇᴅɢᴇ"),
            Map.entry(Enchantment.FIRE_ASPECT, "ꜰɪʀᴇ ᴀsᴘᴇᴄᴛ"),
            Map.entry(Enchantment.UNBREAKING, "ᴜɴʙʀᴇᴀᴋɪɴɢ"),
            Map.entry(Enchantment.MENDING, "ᴍᴇɴᴅɪɴɢ"),
            Map.entry(Enchantment.POWER, "ᴘᴏᴡᴇʀ"),
            Map.entry(Enchantment.INFINITY, "ɪɴꜰɪɴɪᴛʏ"),
            Map.entry(Enchantment.WIND_BURST, "ᴡɪɴᴅ ʙᴜʀsᴛ"),
            Map.entry(Enchantment.DENSITY, "ᴅᴇɴsɪᴛʏ"),
            Map.entry(Enchantment.LUNGE, "ʟᴜɴɢᴇ"));

    private final JavaPlugin plugin;
    private final NamespacedKey idKey;
    private final Cfg cfg = new Cfg();
    private final Map<String, ItemStack> weapons = new LinkedHashMap<>();
    private static final Pattern LEGACY_ID = Pattern.compile("(?:cid|id):([a-z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    public Weapons(JavaPlugin plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "weapon");
        rebuild();
    }

    public Cfg cfg() {
        return cfg;
    }

    /** (Re)loads config values and rebuilds all items with fresh lore. */
    public void rebuild() {
        cfg.load(plugin.getConfig());
        weapons.clear();
        buildAll();
    }

    public ItemStack item(String id) {
        ItemStack item = weapons.get(id);
        return item == null ? null : item.clone();
    }

    public Collection<String> ids() {
        return weapons.keySet();
    }

    /** Whether this weapon is restricted to a separately permissioned administrator. */
    public boolean isAdminOnly(String id) {
        return "bbc_blade".equals(id);
    }

    /** The weapon id of an item, or null if it isn't one of ours. */
    public String idOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        var meta = item.getItemMeta();
        String current = meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        if (current != null) return current;

        // Older Skript kits stored the id in a hidden lore line instead of
        // PDC. Keep those items usable after upgrading to this plugin.
        StringBuilder text = new StringBuilder();
        if (meta.hasDisplayName()) {
            text.append(PlainTextComponentSerializer.plainText().serialize(meta.displayName())).append('\n');
        }
        if (meta.hasLore()) {
            for (Component line : meta.lore()) {
                text.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
            }
        }
        Matcher matcher = LEGACY_ID.matcher(text);
        if (!matcher.find()) return null;
        return switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
            case "flight_sword" -> "dash_sword";
            case "lightning_sword" -> "zeus_sword";
            case "levi_axe", "gravity_axe" -> "levo_axe";
            case "warden_sword" -> "warden_blade";
            default -> matcher.group(1).toLowerCase(Locale.ROOT);
        };
    }

    private static String spd(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String pct(double chance) {
        return Math.round(chance * 100) + "%";
    }

    private void buildAll() {
        register("dash_spear", Material.NETHERITE_SPEAR,
                "<bold><gradient:#55FFFF:#0066AA>ᴅᴀsʜ sᴘᴇᴀʀ</gradient></bold>",
                List.of(new Ench(Enchantment.SHARPNESS, 5), new Ench(Enchantment.LUNGE, 3),
                        new Ench(Enchantment.UNBREAKING, 3), new Ench(Enchantment.MENDING, 1)),
                List.of("<white>sʜɪғᴛ + ʀɪɢʜᴛ ᴄʟɪᴄᴋ ᴛᴏ",
                        "<bold><color:#55FFFF>ᴅᴀsʜ ꜰᴏʀᴡᴀʀᴅ!</color></bold>"),
                "<aqua>sᴘᴇᴇᴅ: <white>" + spd(cfg.dashSpearSpeed) + "  <aqua>ᴄᴅ: <white>" + cfg.dashSpearCd / 20 + "s",
                cfg.dashSpearCd);

        register("dash_sword", Material.NETHERITE_SWORD,
                "<bold><gradient:#55FF55:#00AA00>ᴅᴀsʜ sᴡᴏʀᴅ</gradient></bold>",
                List.of(new Ench(Enchantment.SHARPNESS, 5), new Ench(Enchantment.SWEEPING_EDGE, 3),
                        new Ench(Enchantment.FIRE_ASPECT, 2), new Ench(Enchantment.UNBREAKING, 3),
                        new Ench(Enchantment.MENDING, 1)),
                List.of("<white>ᴀɪᴍ ᴜᴘ ᴏʀ ᴅᴏᴡɴ, sʜɪғᴛ + ʀɪɢʜᴛ ᴄʟɪᴄᴋ",
                        "<bold><color:#55FF55>ᴛᴏ ᴅᴀsʜ ɪɴ ᴛʜᴀᴛ ᴅɪʀᴇᴄᴛɪᴏɴ!</color></bold>"),
                "<aqua>sᴘᴇᴇᴅ: <white>" + spd(cfg.dashSwordSpeed) + "  <aqua>ᴄᴅ: <white>" + cfg.dashSwordCd / 20 + "s",
                cfg.dashSwordCd);

        register("dash_mace", Material.MACE,
                "<bold><gradient:#FF5555:#AA0000>ᴅᴀsʜ ᴍᴀᴄᴇ</gradient></bold>",
                List.of(new Ench(Enchantment.WIND_BURST, 3), new Ench(Enchantment.DENSITY, 5),
                        new Ench(Enchantment.UNBREAKING, 3), new Ench(Enchantment.MENDING, 1)),
                List.of("<white>ᴀɪᴍ ᴜᴘ ᴏʀ ᴅᴏᴡɴ, sʜɪғᴛ + ʀɪɢʜᴛ ᴄʟɪᴄᴋ",
                        "<bold><color:#FF5555>ᴛᴏ ᴅᴀsʜ ɪɴ ᴛʜᴀᴛ ᴅɪʀᴇᴄᴛɪᴏɴ!</color></bold>"),
                "<aqua>sᴘᴇᴇᴅ: <white>" + spd(cfg.dashMaceSpeed) + "  <aqua>ᴄᴅ: <white>" + cfg.dashMaceCd / 20 + "s",
                cfg.dashMaceCd);

        register("adrenaline_blade", Material.NETHERITE_SWORD,
                "<bold><gradient:#FFAA00:#FF5555>ᴀᴅʀᴇɴᴀʟɪɴᴇ ʙʟᴀᴅᴇ</gradient></bold>",
                List.of(new Ench(Enchantment.SHARPNESS, 5), new Ench(Enchantment.UNBREAKING, 3),
                        new Ench(Enchantment.MENDING, 1)),
                List.of("<white>sʜɪғᴛ + ʀɪɢʜᴛ ᴄʟɪᴄᴋ ᴛᴏ",
                        "<bold><color:#FFAA00>ʀᴇsᴛᴏʀᴇ ꜰᴜʟʟ ʜᴇᴀʟᴛʜ + ʏᴇʟʟᴏᴡ ʜᴇᴀʀᴛs!</color></bold>"),
                "<aqua>ʏᴇʟʟᴏᴡ ʜᴇᴀʀᴛs: <white>" + cfg.adrenalineYellowHearts + "  <aqua>ᴄᴅ: <white>"
                        + cfg.adrenalineCd / 20 + "s",
                cfg.adrenalineCd);

        register("warden_blade", Material.NETHERITE_SWORD,
                "<bold><gradient:#29DFEB:#0B5B63>ᴡᴀʀᴅᴇɴ ʙʟᴀᴅᴇ</gradient></bold>",
                List.of(new Ench(Enchantment.SHARPNESS, 5), new Ench(Enchantment.FIRE_ASPECT, 2),
                        new Ench(Enchantment.UNBREAKING, 3), new Ench(Enchantment.MENDING, 1)),
                List.of("<white>sʜɪғᴛ + ʀɪɢʜᴛ ᴄʟɪᴄᴋ ᴛᴏ sʜᴏᴏᴛ ᴀ",
                        "<bold><color:#29DFEB>ᴡᴀʀᴅᴇɴ ʙᴇᴀᴍ!</color></bold>"),
                "<aqua>ᴅᴍɢ: <white>" + spd(cfg.wardenDamage / 2) + "❤  <aqua>ʀᴀɴɢᴇ: <white>" + cfg.wardenRange
                        + "ʙ  <aqua>ᴄᴅ: <white>" + cfg.wardenCd / 20 + "s",
                cfg.wardenCd);

        register("breeze_mace", Material.MACE,
                "<bold><gradient:#A0E8FF:#5580FF>ʙʀᴇᴇᴢᴇ ᴍᴀᴄᴇ</gradient></bold>",
                List.of(new Ench(Enchantment.WIND_BURST, 2), new Ench(Enchantment.DENSITY, 5),
                        new Ench(Enchantment.UNBREAKING, 3), new Ench(Enchantment.MENDING, 1)),
                List.of("<white>sʜɪғᴛ + ʀɪɢʜᴛ ᴄʟɪᴄᴋ ᴛᴏ",
                        "<bold><color:#A0E8FF>ᴅᴀsʜ ꜰᴏʀᴡᴀʀᴅ!</color></bold>",
                        "<white>ʜᴀs <bold><color:#A0E8FF>" + cfg.breezeDashes + " ᴄʜᴀʀɢᴇs!</color></bold>"),
                "<aqua>ᴅᴀsʜᴇs: <white>" + cfg.breezeDashes + "  <aqua>sᴘᴇᴇᴅ: <white>" + spd(cfg.breezeSpeed)
                        + "  <aqua>ᴄᴅ: <white>" + cfg.breezeCd / 20 + "s",
                cfg.breezeCd);

        register("venom_sword", Material.NETHERITE_SWORD,
                "<bold><gradient:#7CFF55:#116611>ᴠᴇɴᴏᴍ sᴡᴏʀᴅ</gradient></bold>",
                List.of(new Ench(Enchantment.SHARPNESS, 5), new Ench(Enchantment.UNBREAKING, 3),
                        new Ench(Enchantment.MENDING, 1)),
                List.of("<white>ʜɪᴛs ʜᴀᴠᴇ ᴀ <bold><color:#7CFF55>" + pct(cfg.venomChance) + "</color></bold> ᴄʜᴀɴᴄᴇ",
                        "<white>ᴛᴏ <bold><color:#7CFF55>ᴘᴏɪsᴏɴ ʏᴏᴜʀ ᴛᴀʀɢᴇᴛ!</color></bold>"),
                "<aqua>ᴄʜᴀɴᴄᴇ: <white>" + pct(cfg.venomChance) + "  <aqua>ᴘᴏɪsᴏɴ ᴛɪᴍᴇ: <white>" + cfg.venomSeconds + "s",
                0);

        register("grapple_bow", Material.BOW,
                "<bold><gradient:#AA00AA:#550055>ɢʀᴀᴘᴘʟᴇ ʙᴏᴡ</gradient></bold>",
                List.of(new Ench(Enchantment.POWER, 5), new Ench(Enchantment.INFINITY, 1),
                        new Ench(Enchantment.UNBREAKING, 3), new Ench(Enchantment.MENDING, 1)),
                List.of("<white>sʜɪғᴛ + ʀɪɢʜᴛ ᴄʟɪᴄᴋ ᴛᴏ",
                        "<bold><color:#AA00AA>ʟᴀᴜɴᴄʜ ʏᴏᴜʀsᴇʟꜰ ꜰᴏʀᴡᴀʀᴅ!</color></bold>"),
                "<aqua>sᴘᴇᴇᴅ: <white>" + spd(cfg.grappleSpeed) + "  <aqua>ᴄᴅ: <white>" + cfg.grappleCd / 20 + "s",
                cfg.grappleCd);

        register("cobweb_axe", Material.NETHERITE_AXE,
                "<bold><gradient:#FF55FF:#AA00AA>ᴄᴏʙᴡᴇʙ ᴀxᴇ</gradient></bold>",
                List.of(new Ench(Enchantment.SHARPNESS, 5), new Ench(Enchantment.UNBREAKING, 3),
                        new Ench(Enchantment.MENDING, 1)),
                List.of("<white>ᴇᴀᴄʜ ʜɪᴛ ʜᴀs ᴀ <bold><color:#FF55FF>" + pct(cfg.cobwebChance) + "</color></bold> ᴄʜᴀɴᴄᴇ",
                        "<white>ᴛᴏ <bold><color:#FF55FF>ᴡᴇʙ ʏᴏᴜʀ ᴛᴀʀɢᴇᴛ ɪɴ ᴘʟᴀᴄᴇ!</color></bold>"),
                "<aqua>ᴄʜᴀɴᴄᴇ: <white>" + pct(cfg.cobwebChance) + "  <aqua>ᴡᴇʙ ᴛɪᴍᴇ: <white>" + cfg.cobwebSeconds + "s",
                0);

        register("levo_axe", Material.NETHERITE_AXE,
                "<bold><gradient:#55FFFF:#5580FF>ʟᴇᴠɪ ᴀxᴇ</gradient></bold>",
                List.of(new Ench(Enchantment.SHARPNESS, 5), new Ench(Enchantment.UNBREAKING, 3),
                        new Ench(Enchantment.MENDING, 1)),
                List.of("<white>sʜɪғᴛ + ʀɪɢʜᴛ ᴄʟɪᴄᴋ ᴛᴏ ʙᴜɪʟᴅ ᴀ",
                        "<bold><color:#55FFFF>ʟᴇᴠɪᴛᴀᴛɪᴏɴ sʜɪᴇʟᴅ ʙᴜʙʙʟᴇ!</color></bold>"),
                "<aqua>ʀᴀᴅɪᴜs: <white>" + spd(cfg.levoRadius) + "ʙ  <aqua>ᴅᴜʀᴀᴛɪᴏɴ: <white>"
                        + cfg.levoDuration + "s  <aqua>ᴄᴅ: <white>" + cfg.levoCd / 20 + "s",
                cfg.levoCd);

        register("zeus_sword", Material.NETHERITE_SWORD,
                "<bold><gradient:#FFFF55:#FFaa00>ᴢᴇᴜs sᴡᴏʀᴅ</gradient></bold>",
                List.of(new Ench(Enchantment.SHARPNESS, 5), new Ench(Enchantment.FIRE_ASPECT, 2),
                        new Ench(Enchantment.UNBREAKING, 3), new Ench(Enchantment.MENDING, 1)),
                List.of("<white>ʜɪᴛs ʜᴀᴠᴇ ᴀ <bold><color:#FFFF55>" + pct(cfg.zeusChance) + "</color></bold> ᴄʜᴀɴᴄᴇ",
                        "<white>ᴛᴏ <bold><color:#FFFF55>sᴛʀɪᴋᴇ ᴡɪᴛʜ ᴀʀᴍᴏʀ-ɪɢɴᴏʀɪɴɢ ʟɪɢʜᴛɴɪɴɢ!</color></bold>"),
                "<aqua>ᴄʜᴀɴᴄᴇ: <white>" + pct(cfg.zeusChance) + "  <aqua>ᴅᴍɢ: <white>" + spd(cfg.zeusDamage / 2) + "❤",
                0);

        register("lifesteal_sword", Material.NETHERITE_SWORD,
                "<bold><gradient:#FF5577:#880022>ʟɪꜰᴇsᴛᴇᴀʟ sᴡᴏʀᴅ</gradient></bold>",
                List.of(new Ench(Enchantment.SHARPNESS, 5), new Ench(Enchantment.UNBREAKING, 3),
                        new Ench(Enchantment.MENDING, 1)),
                List.of("<white>ʜɪᴛs ʜᴀᴠᴇ ᴀ <bold><color:#FF5577>" + pct(cfg.lifestealChance) + "</color></bold> ᴄʜᴀɴᴄᴇ",
                        "<white>ᴛᴏ <bold><color:#FF5577>ʜᴇᴀʟ ʏᴏᴜ ꜰᴏʀ 1 ʜᴇᴀʀᴛ!</color></bold>"),
                "<aqua>ᴄʜᴀɴᴄᴇ: <white>" + pct(cfg.lifestealChance) + "  <aqua>ʜᴇᴀʟ: <white>"
                        + spd(cfg.lifestealHeal / 2) + "❤",
                0);

        register("meow_blade", Material.NETHERITE_SWORD,
                "<bold><gradient:#FFAAEE:#FF55AA>ᴍᴇᴏᴡ ʙʟᴀᴅᴇ</gradient></bold>",
                List.of(new Ench(Enchantment.SHARPNESS, 5), new Ench(Enchantment.UNBREAKING, 3),
                        new Ench(Enchantment.MENDING, 1)),
                List.of("<white>sʜɪғᴛ + ʀɪɢʜᴛ ᴄʟɪᴄᴋ ᴛᴏ sʜᴏᴏᴛ ᴀ ᴄᴀᴛ",
                        "<bold><color:#FF55AA>ᴛʜᴀᴛ ɢᴏᴇs ʙᴏᴏᴍ!</color></bold>"),
                "<aqua>ʙᴏᴏᴍ: <white>" + spd(cfg.meowExplosionPower) + "  <aqua>ᴄᴅ: <white>"
                        + cfg.meowCd / 20 + "s",
                cfg.meowCd);

        register("stun_axe", Material.NETHERITE_AXE,
                "<bold><gradient:#AAAAAA:#5555FF>sᴛᴜɴ ᴀxᴇ</gradient></bold>",
                List.of(new Ench(Enchantment.SHARPNESS, 5), new Ench(Enchantment.UNBREAKING, 3),
                        new Ench(Enchantment.MENDING, 1)),
                List.of("<white>ʜɪᴛ ᴀ ʙʟᴏᴄᴋɪɴɢ ᴘʟᴀʏᴇʀ ᴛᴏ",
                        "<bold><color:#AAAAFF>sᴛᴜɴ ᴛʜᴇɪʀ sʜɪᴇʟᴅ ꜰᴏʀ " + cfg.stunSeconds + "s!</color></bold>"),
                "<aqua>sʜɪᴇʟᴅ sᴛᴜɴ: <white>" + cfg.stunSeconds + "s  <aqua>ᴄᴅ: <white>"
                        + cfg.stunCd / 20 + "s",
                cfg.stunCd);

        register("mirror_shield", Material.SHIELD,
                "<bold><gradient:#FFFFFF:#55FFFF>ᴍɪʀʀᴏʀ sʜɪᴇʟᴅ</gradient></bold>",
                List.of(new Ench(Enchantment.UNBREAKING, 3), new Ench(Enchantment.MENDING, 1)),
                List.of("<white>sʜɪғᴛ + ʀɪɢʜᴛ ᴄʟɪᴄᴋ ᴛᴏ ᴀᴄᴛɪᴠᴀᴛᴇ",
                        "<bold><color:#55FFFF>ʀᴇғʟᴇᴄᴛ ᴀʟʟ ᴀᴛᴛᴀᴄᴋs ʙᴀᴄᴋ!</color></bold>"),
                "<aqua>ᴅᴜʀᴀᴛɪᴏɴ: <white>" + cfg.mirrorDurationTicks / 20 + "s  <aqua>ᴄᴅ: <white>"
                        + cfg.mirrorCd / 20 + "s",
                cfg.mirrorCd);

        register("pulse_shield", Material.SHIELD,
                "<bold><gradient:#AA55FF:#FF55FF>ᴘᴜʟsᴇ sʜɪᴇʟᴅ</gradient></bold>",
                List.of(new Ench(Enchantment.UNBREAKING, 3), new Ench(Enchantment.MENDING, 1)),
                List.of("<white>ᴡʜᴇɴ ᴀɴ ᴇɴᴇᴍʏ sᴛᴜɴs ʏᴏᴜʀ sʜɪᴇʟᴅ,",
                        "<bold><color:#FF55FF>ᴘᴜʟsᴇ ᴛʜᴇᴍ ᴀᴡᴀʏ!</color></bold>"),
                "<aqua>ᴋɴᴏᴄᴋʙᴀᴄᴋ: <white>" + spd(cfg.pulseKnockback) + "  <aqua>ᴄᴅ: <white>"
                        + cfg.pulseCd / 20 + "s",
                cfg.pulseCd);

        register("meow_shield", Material.SHIELD,
                "<bold><gradient:#FFAAEE:#FF55AA>ᴍᴇᴏᴡ sʜɪᴇʟᴅ</gradient></bold>",
                List.of(new Ench(Enchantment.UNBREAKING, 3), new Ench(Enchantment.MENDING, 1)),
                List.of("<white>ғ: sʜᴏᴏᴛ ᴀ ʙᴀʀʀᴀɢᴇ ᴏғ ᴇxᴘʟᴏᴅɪɴɢ ᴄᴀᴛs",
                        "<white>sʜɪғᴛ + ғ: sᴜᴍᴍᴏɴ 8 ᴄᴀᴛs ғᴏʀ 10s",
                        "<bold><color:#FF55AA>ᴛʜᴇʏ ᴄʜᴀsᴇ ᴛʜᴇ ʟᴀsᴛ ᴇɴᴇᴍʏ ʏᴏᴜ ʜɪᴛ!</color></bold>"),
                "<aqua>ʙᴀʀʀᴀɢᴇ ᴄᴅ: <white>" + cfg.meowShieldBarrageCd / 20 + "s  <aqua>sᴜᴍᴍᴏɴ ᴄᴅ: <white>"
                        + cfg.meowShieldSummonCd / 20 + "s",
                0);

        register("thunder_spear", Material.NETHERITE_SPEAR,
                "<bold><gradient:#FFFF55:#FFaa00>ᴛʜᴜɴᴅᴇʀ sᴘᴇᴀʀ</gradient></bold>",
                List.of(new Ench(Enchantment.SHARPNESS, 5), new Ench(Enchantment.LUNGE, 3),
                        new Ench(Enchantment.UNBREAKING, 3), new Ench(Enchantment.MENDING, 1)),
                List.of("<white>sʜɪғᴛ + ʀɪɢʜᴛ ᴄʟɪᴄᴋ ᴛᴏ",
                        "<bold><color:#FFFF55>sᴍɪᴛᴇ ᴡʜᴏᴇᴠᴇʀ ʏᴏᴜ'ʀᴇ ᴀɪᴍɪɴɢ ᴀᴛ!</color></bold>"),
                "<aqua>ʀᴀɴɢᴇ: <white>" + cfg.thunderRange + "ʙ  <aqua>ᴅᴍɢ: <white>" + spd(cfg.thunderDamage / 2)
                        + "❤  <aqua>ᴄᴅ: <white>" + cfg.thunderCd / 20 + "s",
                cfg.thunderCd);

        register("webcleaver", Material.NETHERITE_AXE,
                "<bold><gradient:#FFFFFF:#8899AA>ᴡᴇʙᴄʟᴇᴀᴠᴇʀ</gradient></bold>",
                List.of(new Ench(Enchantment.SHARPNESS, 5), new Ench(Enchantment.UNBREAKING, 3),
                        new Ench(Enchantment.MENDING, 1)),
                List.of("<white>sʜɪғᴛ + ʀɪɢʜᴛ ᴄʟɪᴄᴋ ᴛᴏ",
                        "<bold><color:#DDDDFF>ᴄʟᴇᴀʀ ᴇᴠᴇʀʏ ᴡᴇʙ ɴᴇᴀʀʙʏ!</color></bold>"),
                "<aqua>ʀᴀᴅɪᴜs: <white>" + cfg.webcleaverRadius + "ʙ  <aqua>ᴄᴅ: <white>" + cfg.webcleaverCd / 20 + "s",
                cfg.webcleaverCd);

        register("bbc_blade", Material.NETHERITE_SWORD,
                "<bold><gradient:#550055:#FF55FF>ʙʙᴄ ʙʟᴀᴅᴇ</gradient></bold>",
                List.of(new Ench(Enchantment.SHARPNESS, 10), new Ench(Enchantment.FIRE_ASPECT, 2),
                        new Ench(Enchantment.UNBREAKING, 3), new Ench(Enchantment.MENDING, 1)),
                List.of("<bold><color:#FF5555>ᴀᴅᴍɪɴ ᴏɴʟʏ</color></bold><white> — sʜɪғᴛ + ʀɪɢʜᴛ ᴄʟɪᴄᴋ",
                        "<bold><color:#FF55FF>ᴛᴏ ᴄᴀʟʟ ᴀɴ ᴀᴅᴍɪɴ ʙʟᴀsᴛ!</color></bold>"),
                "<aqua>ʀᴀɴɢᴇ: <white>" + cfg.bbcRange + "ʙ  <aqua>ᴅᴍɢ: <white>" + spd(cfg.bbcDamage / 2)
                        + "❤  <aqua>ᴄᴅ: <white>" + cfg.bbcCd / 20 + "s",
                cfg.bbcCd);
    }

    private void register(String id, Material material, String nameMm, List<Ench> enchants,
                          List<String> descriptionMm, String statsMm, int cooldownTicks) {
        ItemStack item = new ItemStack(material);
        for (Ench ench : enchants) {
            item.addUnsafeEnchantment(ench.enchantment(), ench.level());
        }
        // Lore laid out exactly like the weapons Skript: enchant list, separator,
        // ability description, separator, stats.
        List<String> lore = new ArrayList<>();
        for (Ench ench : enchants) {
            String name = ENCH_NAMES.getOrDefault(ench.enchantment(), ench.enchantment().getKey().getKey());
            String roman = ench.level() > 1 && ench.level() < ROMAN.length ? " " + ROMAN[ench.level()] : "";
            lore.add("<gray>" + name + roman);
        }
        lore.add(SEP);
        lore.addAll(descriptionMm);
        lore.add(SEP);
        lore.add(statsMm);

        item.editMeta(meta -> {
            meta.displayName(Util.mm(nameMm));
            meta.lore(lore.stream().map(Util::mm).toList());
            // Hide technical/enchantment details while keeping the custom name and lore visible.
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_STORED_ENCHANTS,
                    ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE,
                    ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
            if (cooldownTicks > 0) {
                // Own cooldown group so same-material weapons don't share the display.
                UseCooldownComponent cooldown = meta.getUseCooldown();
                cooldown.setCooldownGroup(new NamespacedKey(plugin, id));
                cooldown.setCooldownSeconds(cooldownTicks / 20f);
                meta.setUseCooldown(cooldown);
            }
        });
        weapons.put(id, item);
    }
}
