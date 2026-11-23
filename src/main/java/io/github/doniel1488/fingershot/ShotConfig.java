package io.github.doniel1488.fingershot;

public final class ShotConfig {
    private int format;
    public double range = 128;
    public double radius = 1.65;
    public int chargeTicks = 24;
    public int cooldownTicks = 32;
    public float damage = 64;
    public double knockback = 2.3;
    public boolean affectEntities = true;
    public boolean sliceBlocks = true;
    public float volume = 0.75f;

    public static ShotConfig load() {
        return ConfigFile.load("fingershot.json", ShotConfig.class, new ShotConfig().validate(), ShotConfig::validate);
    }

    ShotConfig validate() {
        if (format < 2) {
            if (range == 30) range = 128;
            if (damage == 12) damage = 64;
            if (knockback == 1.1) knockback = 2.3;
            format = 2;
        }
        range = ConfigFile.limit(range, 2, 256, 128);
        radius = ConfigFile.limit(radius, 0.65, 3, 1.65);
        chargeTicks = Math.clamp(chargeTicks, 8, 120);
        cooldownTicks = Math.clamp(cooldownTicks, 12, 240);
        damage = (float) ConfigFile.limit(damage, 0, 1000, 64);
        knockback = ConfigFile.limit(knockback, 0, 4, 2.3);
        volume = (float) ConfigFile.limit(volume, 0, 1, 0.75);
        return this;
    }
}
