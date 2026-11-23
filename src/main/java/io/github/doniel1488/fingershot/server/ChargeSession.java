package io.github.doniel1488.fingershot.server;

import io.github.doniel1488.fingershot.net.ShotPackets.Intent;

final class ChargeSession {
    static final int MAX_HOLD_TICKS = 20 * 30;
    private static final int MAX_PACKETS_PER_TICK = 3;

    enum Result { IGNORE, START, CANCEL, FIRE, REJECT }

    private long start = -1;
    private long readyAt;
    private long lastPacketTick = -1;
    private int packets;
    private float power;

    Result input(Intent intent, long tick, boolean allowed, int fullCharge, int cooldown) {
        if (lastPacketTick != tick) {
            lastPacketTick = tick;
            packets = 0;
        }
        if (++packets > MAX_PACKETS_PER_TICK) {
            return Result.IGNORE;
        }
        if (!allowed) {
            return cancel() ? Result.CANCEL : Result.REJECT;
        }
        if (intent == Intent.START) {
            if (start >= 0) {
                return Result.IGNORE;
            }
            if (tick < readyAt) {
                return Result.REJECT;
            }
            start = tick;
            return Result.START;
        }
        if (start < 0) {
            return Result.IGNORE;
        }
        long held = tick - start;
        start = -1;
        if (intent == Intent.CANCEL || held < minimumCharge(fullCharge) || held >= MAX_HOLD_TICKS) {
            return Result.CANCEL;
        }
        readyAt = tick + cooldown;
        power = Math.min(1f, (float) held / fullCharge);
        return Result.FIRE;
    }

    static int minimumCharge(int fullCharge) {
        return Math.max(4, fullCharge / 4);
    }

    boolean cancel() {
        boolean charging = start >= 0;
        start = -1;
        return charging;
    }

    boolean expired(long tick) {
        return start >= 0 && tick - start >= MAX_HOLD_TICKS;
    }

    int remainingCooldown(long tick) {
        return (int) Math.max(0, readyAt - tick);
    }

    float power() {
        return power;
    }
}
