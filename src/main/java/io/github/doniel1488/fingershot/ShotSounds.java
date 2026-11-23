package io.github.doniel1488.fingershot;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;

public final class ShotSounds {
    public static SoundEvent CHARGE;
    public static SoundEvent FIRE;
    public static SoundEvent BLOCK;
    public static SoundEvent ENTITY;
    public static SoundEvent END;

    private static SoundEvent create(String name) {
        var id = FingerShot.id(name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    static void register() {
        CHARGE = create("charge");
        FIRE = create("fire");
        BLOCK = create("block");
        ENTITY = create("entity");
        END = create("end");
    }
}
