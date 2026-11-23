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
        assertEquals(30, config.range);
        assertEquals(8, config.chargeTicks);
        assertEquals(240, config.cooldownTicks);
        assertEquals(0, config.damage);
        assertEquals(1.1, config.knockback);
        assertEquals(1, config.volume);
    }
}
