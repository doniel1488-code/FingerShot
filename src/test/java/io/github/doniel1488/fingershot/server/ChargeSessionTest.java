package io.github.doniel1488.fingershot.server;

import io.github.doniel1488.fingershot.net.ShotPackets.Intent;
import org.junit.jupiter.api.Test;

import static io.github.doniel1488.fingershot.server.ChargeSession.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class ChargeSessionTest {
    private final ChargeSession session = new ChargeSession();

    private ChargeSession.Result input(Intent intent, long tick) {
        return session.input(intent, tick, true, 24, 32);
    }

    @Test
    void shortChargeCancelsWithoutCooldown() {
        assertEquals(START, input(Intent.START, 1));
        assertEquals(CANCEL, input(Intent.RELEASE, 5));
        assertEquals(START, input(Intent.START, 6));
    }

    @Test
    void consumesChargeOnceAndUsesServerCooldown() {
        assertEquals(START, input(Intent.START, 1));
        assertEquals(FIRE, input(Intent.RELEASE, 13));
        assertEquals(0.5f, session.power());
        assertEquals(IGNORE, input(Intent.RELEASE, 13));
        assertEquals(32, session.remainingCooldown(13));
        assertEquals(REJECT, input(Intent.START, 44));
        assertEquals(START, input(Intent.START, 45));
    }

    @Test
    void validatesEligibilityAndChargeExpiry() {
        assertEquals(REJECT, session.input(Intent.START, 1, false, 24, 32));
        assertEquals(START, input(Intent.START, 2));
        assertEquals(CANCEL, session.input(Intent.RELEASE, 20, false, 24, 32));
        assertEquals(START, input(Intent.START, 21));
        assertTrue(session.expired(21 + ChargeSession.MAX_HOLD_TICKS));
        assertEquals(CANCEL, input(Intent.RELEASE, 21 + ChargeSession.MAX_HOLD_TICKS));
    }

    @Test
    void limitsRepeatedPacketsPerTick() {
        assertEquals(START, input(Intent.START, 1));
        assertEquals(IGNORE, input(Intent.START, 1));
        assertEquals(IGNORE, input(Intent.START, 1));
        assertEquals(IGNORE, input(Intent.CANCEL, 1));
        assertEquals(CANCEL, input(Intent.CANCEL, 2));
    }
}
