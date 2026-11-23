package io.github.doniel1488.fingershot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShotConfigTest {
    @Test
    void usesSafeBoundsAndDefaultsForNonFiniteValues() {
        var config = new ShotConfig();
        config.range = Double.NaN;
        config.chargeTicks = 0;
        config.cooldownTicks = 999;
        config.damage = -3;
        config.knockback = Double.POSITIVE_INFINITY;
        config.volume = 2;
        config.validate();
        assertEquals(128, config.range);
        assertEquals(8, config.chargeTicks);
        assertEquals(240, config.cooldownTicks);
        assertEquals(0, config.damage);
        assertEquals(2.3, config.knockback);
        assertEquals(1, config.volume);
    }

    @Test
    void upgradesOldDefaultsButPreservesCustomSettings() {
        var oldDefaults = new ShotConfig();
        oldDefaults.range = 30;
        oldDefaults.damage = 12;
        oldDefaults.knockback = 1.1;
        oldDefaults.validate();
        assertEquals(128, oldDefaults.range);
        assertEquals(64, oldDefaults.damage);
        assertEquals(2.3, oldDefaults.knockback);

        var custom = new ShotConfig();
        custom.range = 52;
        custom.damage = 20;
        custom.knockback = 0.75;
        custom.validate();
        assertEquals(52, custom.range);
        assertEquals(20, custom.damage);
        assertEquals(0.75, custom.knockback);
    }
}
