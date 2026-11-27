package ru.governix.mahoyu;

import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AdaptationManager {
    private static final int MAX_STAGE = 4;
    private static final long ADAPTATION_INTERVAL = 30L;
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private AdaptationManager() { }

    public static boolean toggle(ServerPlayerEntity player) {
        State state = STATES.computeIfAbsent(player.getUuid(), ignored -> new State());
        state.enabled = !state.enabled;
        if (state.enabled) {
            state.type = "ещё нет";
            state.stage = 0;
            state.lastAdaptTick = -ADAPTATION_INTERVAL;
        }
        return state.enabled;
    }

    public static boolean isEnabled(PlayerEntity player) {
        State state = STATES.get(player.getUuid());
        return state != null && state.enabled;
    }

    public static float reduceDamage(PlayerEntity player, DamageSource source, float amount) {
        State state = STATES.get(player.getUuid());
        if (state == null || !state.enabled || state.stage <= 0 || player.getWorld().isClient) {
            return amount;
        }
        String incomingType = classify(source);
        if (!incomingType.equals(state.type)) {
            return amount;
        }
        float reduction = Math.min(0.80f, state.stage * 0.20f);
        return amount * (1.0f - reduction);
    }

    public static void recordDamage(ServerPlayerEntity player, DamageSource source, ServerWorld world) {
        State state = STATES.get(player.getUuid());
        if (state == null || !state.enabled) {
            return;
        }

        String incomingType = classify(source);
        long now = world.getTime();
        if (!incomingType.equals(state.type)) {
            state.type = incomingType;
            state.stage = 1;
            state.lastAdaptTick = now;
            announce(player, state, true);
            burst(world, player);
            return;
        }

        if (state.stage < MAX_STAGE && now - state.lastAdaptTick >= ADAPTATION_INTERVAL) {
            state.stage++;
            state.lastAdaptTick = now;
            announce(player, state, false);
            burst(world, player);
        }
    }

    public static void renderWheel(ServerWorld world, ServerPlayerEntity player) {
        State state = STATES.get(player.getUuid());
        if (state == null || !state.enabled) {
            return;
        }
        double rotation = world.getTime() * 0.12;
        double y = player.getY() + player.getHeight() + 0.45;
        for (int i = 0; i < 8; i++) {
            double angle = rotation + Math.PI * 2.0 * i / 8.0;
            double x = player.getX() + Math.cos(angle) * 0.55;
            double z = player.getZ() + Math.sin(angle) * 0.55;
            world.spawnParticles(ParticleTypes.ENCHANT, x, y, z, 1, 0.0, 0.015, 0.0, 0.0);
        }
    }

    private static void burst(ServerWorld world, ServerPlayerEntity player) {
        world.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + player.getHeight() * 0.7,
                player.getZ(), 16, 0.45, 0.45, 0.45, 0.02);
    }

    private static void announce(ServerPlayerEntity player, State state, boolean changedType) {
        int percent = state.stage * 20;
        String prefix = changedType ? "§6Новая адаптация" : "§eАдаптация усилена";
        player.sendMessage(Text.literal(prefix + ": §f" + state.type + " §7[" + state.stage + "/" + MAX_STAGE
                + "] §a-" + percent + "% урона"), true);
    }

    private static String classify(DamageSource source) {
        if (source.isIn(DamageTypeTags.IS_FIRE)) return "огонь";
        if (source.isIn(DamageTypeTags.IS_EXPLOSION)) return "взрыв";
        if (source.isIn(DamageTypeTags.IS_PROJECTILE)) return "снаряды";
        if (source.isIn(DamageTypeTags.IS_FALL)) return "падение";
        String name = source.getName().toLowerCase(Locale.ROOT);
        if (name.contains("magic") || name.contains("indirect") || name.contains("wither")) return "магия";
        if (source.getAttacker() != null) return "ближний бой";
        return "прочее";
    }

    private static final class State {
        private boolean enabled;
        private String type = "ещё нет";
        private int stage;
        private long lastAdaptTick;
    }
}
