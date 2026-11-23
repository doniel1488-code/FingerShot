package io.github.doniel1488.fingershot;

public final class ShotConfig {
    public double range = 30;
    public int chargeTicks = 24;
    public int cooldownTicks = 32;
    public float damage = 12;
    public double knockback = 1.1;
    public boolean affectEntities = true;
    public boolean sliceBlocks = true;
    public float volume = 0.75f;

    public static ShotConfig load() {
        return ConfigFile.load("fingershot.json", ShotConfig.class, new ShotConfig(), ShotConfig::validate);
    }

    ShotConfig validate() {
        range = ConfigFile.limit(range, 2, 64, 30);
        chargeTicks = Math.clamp(chargeTicks, 8, 120);
        cooldownTicks = Math.clamp(cooldownTicks, 12, 240);
        damage = (float) ConfigFile.limit(damage, 0, 100, 12);
        knockback = ConfigFile.limit(knockback, 0, 4, 1.1);
        volume = (float) ConfigFile.limit(volume, 0, 1, 0.75);
        return this;
    }
}
