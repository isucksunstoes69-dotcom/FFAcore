package eu.fakemoon.ffaweapons;

import org.bukkit.configuration.file.FileConfiguration;

/** All tunable weapon numbers, loaded from config.yml. Cooldowns are in ticks. */
public final class Cfg {

    public double dashSpearSpeed;
    public int dashSpearCd;
    public double dashSwordSpeed;
    public int dashSwordCd;
    public double dashMaceSpeed;
    public int dashMaceCd;
    public int adrenalineCd;
    public int adrenalineAbsorptionSeconds;
    public int adrenalineYellowHearts;
    public double wardenDamage;
    public int wardenRange;
    public int wardenCd;
    public double breezeSpeed;
    public int breezeDashes;
    public int breezeCd;
    public double venomChance;
    public int venomSeconds;
    public int venomAmplifier;
    public double grappleSpeed;
    public int grappleCd;
    public double cobwebChance;
    public int cobwebSeconds;
    public int levoCd;
    public double levoRadius;
    public int levoDuration;
    public double zeusChance;
    public double zeusDamage;
    public double lifestealChance;
    public double lifestealHeal;
    public int meowCd;
    public double meowSpeed;
    public int meowFlightTicks;
    public float meowExplosionPower;
    public int stunCd;
    public int stunSeconds;
    public int mirrorCd;
    public int mirrorDurationTicks;
    public double mirrorDamageMultiplier;
    public int pulseCd;
    public double pulseKnockback;
    public double pulseRadius;
    public int meowShieldBarrageCd;
    public int meowShieldBarrageCount;
    public double meowShieldBarrageSpeed;
    public float meowShieldBarrageExplosionPower;
    public double meowShieldBarrageDamage;
    public int meowShieldBarrageFlightTicks;
    public int meowShieldSummonCd;
    public int meowShieldSummonCount;
    public int meowShieldSummonDurationTicks;
    public double meowShieldSummonDamage;
    public int meowShieldSummonAttackInterval;
    public int meowShieldTargetWindowTicks;
    public int bbcCd;
    public int bbcRange;
    public double bbcDamage;
    public int thunderRange;
    public double thunderDamage;
    public int thunderCd;
    public int webcleaverRadius;
    public int webcleaverCd;

    public void load(FileConfiguration c) {
        dashSpearSpeed = c.getDouble("weapons.dash-spear.dash-speed", 3.0);
        dashSpearCd = c.getInt("weapons.dash-spear.cooldown-seconds", 15) * 20;
        dashSwordSpeed = c.getDouble("weapons.dash-sword.dash-speed", 2.5);
        dashSwordCd = c.getInt("weapons.dash-sword.cooldown-seconds", 20) * 20;
        dashMaceSpeed = c.getDouble("weapons.dash-mace.dash-speed", 3.5);
        dashMaceCd = c.getInt("weapons.dash-mace.cooldown-seconds", 20) * 20;
        adrenalineCd = c.getInt("weapons.adrenaline-blade.cooldown-seconds", 35) * 20;
        adrenalineAbsorptionSeconds = c.getInt("weapons.adrenaline-blade.absorption-seconds", 12);
        adrenalineYellowHearts = Math.max(1, c.getInt("weapons.adrenaline-blade.yellow-hearts", 4));
        wardenDamage = c.contains("weapons.warden-blade.damage", true)
                ? c.getDouble("weapons.warden-blade.damage", 8.0)
                : c.getDouble("weapons.warden-sword.damage", 8.0);
        wardenRange = c.contains("weapons.warden-blade.range", true)
                ? c.getInt("weapons.warden-blade.range", 15)
                : c.getInt("weapons.warden-sword.range", 15);
        wardenCd = (c.contains("weapons.warden-blade.cooldown-seconds", true)
                ? c.getInt("weapons.warden-blade.cooldown-seconds", 30)
                : c.getInt("weapons.warden-sword.cooldown-seconds", 30)) * 20;
        breezeSpeed = c.getDouble("weapons.breeze-mace.dash-speed", 3.0);
        breezeDashes = Math.max(1, c.getInt("weapons.breeze-mace.dashes", 3));
        breezeCd = c.getInt("weapons.breeze-mace.cooldown-seconds", 15) * 20;
        venomChance = c.getDouble("weapons.venom-sword.chance", 0.30);
        venomSeconds = c.getInt("weapons.venom-sword.poison-seconds", 4);
        venomAmplifier = c.getInt("weapons.venom-sword.poison-amplifier", 1);
        grappleSpeed = c.getDouble("weapons.grapple-bow.dash-speed", 3.0);
        grappleCd = c.getInt("weapons.grapple-bow.cooldown-seconds", 10) * 20;
        cobwebChance = c.getDouble("weapons.cobweb-axe.chance", 0.30);
        cobwebSeconds = c.getInt("weapons.cobweb-axe.web-seconds", 4);
        levoCd = c.getInt("weapons.levo-axe.cooldown-seconds", 60) * 20;
        levoRadius = c.getDouble("weapons.levo-axe.radius", 8.0);
        levoDuration = c.getInt("weapons.levo-axe.duration-seconds", 30);
        zeusChance = c.getDouble("weapons.zeus-sword.chance", 0.10);
        zeusDamage = c.getDouble("weapons.zeus-sword.damage", 5.0);
        lifestealChance = c.getDouble("weapons.lifesteal-sword.chance", 0.10);
        lifestealHeal = c.getDouble("weapons.lifesteal-sword.heal", 2.0);
        meowCd = c.getInt("weapons.meow-blade.cooldown-seconds", 20) * 20;
        meowSpeed = c.getDouble("weapons.meow-blade.cat-speed", 0.65);
        meowFlightTicks = c.getInt("weapons.meow-blade.flight-seconds", 3) * 20;
        meowExplosionPower = (float) c.getDouble("weapons.meow-blade.explosion-power", 2.5);
        stunCd = c.getInt("weapons.stun-axe.cooldown-seconds", 15) * 20;
        stunSeconds = c.getInt("weapons.stun-axe.shield-stun-seconds", 7);
        mirrorCd = c.getInt("weapons.mirror-shield.cooldown-seconds", 40) * 20;
        mirrorDurationTicks = c.getInt("weapons.mirror-shield.duration-seconds", 3) * 20;
        mirrorDamageMultiplier = c.getDouble("weapons.mirror-shield.reflected-damage-multiplier", 1.0);
        pulseCd = c.getInt("weapons.pulse-shield.cooldown-seconds", 60) * 20;
        pulseKnockback = c.getDouble("weapons.pulse-shield.knockback", 2.2);
        pulseRadius = c.getDouble("weapons.pulse-shield.visual-radius", 5.0);
        meowShieldBarrageCd = c.getInt("weapons.meow-shield.barrage-cooldown-seconds", 25) * 20;
        meowShieldBarrageCount = Math.max(1, c.getInt("weapons.meow-shield.barrage-count", 6));
        meowShieldBarrageSpeed = c.getDouble("weapons.meow-shield.barrage-cat-speed", 0.72);
        meowShieldBarrageExplosionPower = (float) c.getDouble("weapons.meow-shield.barrage-explosion-power", 2.2);
        meowShieldBarrageDamage = Math.max(0.0, c.getDouble("weapons.meow-shield.barrage-damage", 4.0));
        meowShieldBarrageFlightTicks = c.getInt("weapons.meow-shield.barrage-flight-seconds", 3) * 20;
        meowShieldSummonCd = c.getInt("weapons.meow-shield.summon-cooldown-seconds", 45) * 20;
        meowShieldSummonCount = Math.max(1, c.getInt("weapons.meow-shield.summon-count", 8));
        meowShieldSummonDurationTicks = Math.max(1, c.getInt("weapons.meow-shield.summon-duration-seconds", 10)) * 20;
        meowShieldSummonDamage = Math.max(0.0, c.getDouble("weapons.meow-shield.summon-damage", 1.0));
        meowShieldSummonAttackInterval = Math.max(1, c.getInt("weapons.meow-shield.summon-attack-interval-ticks", 20));
        meowShieldTargetWindowTicks = Math.max(1, c.getInt("weapons.meow-shield.last-target-seconds", 30)) * 20;
        bbcCd = c.getInt("weapons.bbc-blade.cooldown-seconds", 20) * 20;
        bbcRange = c.getInt("weapons.bbc-blade.range", 30);
        bbcDamage = c.getDouble("weapons.bbc-blade.damage", 20.0);
        thunderRange = c.getInt("weapons.thunder-spear.range", 30);
        thunderDamage = c.getDouble("weapons.thunder-spear.damage", 5.0);
        thunderCd = c.getInt("weapons.thunder-spear.cooldown-seconds", 25) * 20;
        webcleaverRadius = c.getInt("weapons.webcleaver.radius", 10);
        webcleaverCd = c.getInt("weapons.webcleaver.cooldown-seconds", 10) * 20;
    }
}
