package io.github.doniel1488.fingershot.client;

import io.github.doniel1488.fingershot.ShotSounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.random.Random;

final class ChargeSound extends MovingSoundInstance {
    private final int player;
    private final FingerShotClient.Pose pose;
    private final float maxVolume;

    ChargeSound(int player, FingerShotClient.Pose pose, float volume) {
        super(ShotSounds.CHARGE, SoundCategory.PLAYERS, Random.create());
        this.player = player;
        this.pose = pose;
        maxVolume = volume * pose.volume();
        repeat = true;
        repeatDelay = 0;
        this.volume = maxVolume * 0.2f;
        pitch = 0.8f;
        var world = MinecraftClient.getInstance().world;
        if (world != null) {
            updatePosition(world.getEntityById(player));
        }
    }

    @Override
    public void tick() {
        var world = MinecraftClient.getInstance().world;
        if (world == null || FingerShotClient.pose(player) != pose) {
            setDone();
            return;
        }
        if (!updatePosition(world.getEntityById(player))) {
            setDone();
            return;
        }
        float power = pose.charge(0);
        volume = maxVolume * (0.2f + 0.65f * power);
        pitch = 0.8f + 0.65f * power;
    }

    private boolean updatePosition(Entity entity) {
        if (entity == null) {
            return false;
        }
        var hand = ShotEffects.handPosition(entity, entity.getEyePos(), entity.getRotationVec(1));
        x = hand.x;
        y = hand.y;
        z = hand.z;
        return true;
    }
}
