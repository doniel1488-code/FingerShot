package ru.governix.mahoyu;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TechniqueManager {
    private static final Map<UUID, Boolean> YUJI_MODE = new HashMap<>();
    private static final Map<String, Long> COOLDOWNS = new HashMap<>();
    private static final List<PendingHit> PENDING_HITS = new ArrayList<>();
    private static final int MAX_SWEEP_TARGETS = 8;

    private TechniqueManager() { }

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(TechniqueManager::tick);
    }

    public static boolean toggleYuji(ServerPlayerEntity player) {
        boolean enabled = !YUJI_MODE.getOrDefault(player.getUuid(), false);
        YUJI_MODE.put(player.getUuid(), enabled);
        if (!enabled) {
            player.removeStatusEffect(StatusEffects.STRENGTH);
            player.removeStatusEffect(StatusEffects.SPEED);
        }
        return enabled;
    }

    public static int divergentFist(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        if (!ready(player, "divergent", 24)) return 0;
        LivingEntity target = findTarget(player, 4.5);
        if (target == null) {
            player.sendMessage(Text.literal("§7Нет цели перед тобой."), true);
            return 0;
        }
        hit(player, target, 4.0f);
        Vec3d center = target.getBoundingBox().getCenter();
        world.spawnParticles(ParticleTypes.CRIT, center.x, center.y, center.z, 10, 0.25, 0.3, 0.25, 0.12);
        PENDING_HITS.add(new PendingHit(world.getRegistryKey(), target.getId(), player.getUuid(), 7, 6.5f));
        world.playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 0.9f, 0.7f);
        player.sendMessage(Text.literal("§cDivergent Fist — второй удар догоняет цель!"), true);
        setCooldown(player, "divergent", 24);
        return 1;
    }

    public static int blackFlash(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        if (!ready(player, "blackflash", 100)) return 0;
        LivingEntity target = findTarget(player, 4.8);
        if (target == null) {
            player.sendMessage(Text.literal("§7Нет цели перед тобой."), true);
            return 0;
        }
        hit(player, target, 14.0f);
        Vec3d center = target.getBoundingBox().getCenter();
        world.spawnParticles(ParticleTypes.CRIT, center.x, center.y, center.z, 35, 0.45, 0.45, 0.45, 0.35);
        world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, center.x, center.y, center.z, 18, 0.3, 0.4, 0.3, 0.04);
        world.spawnParticles(ParticleTypes.FLASH, center.x, center.y, center.z, 1, 0, 0, 0, 0);
        world.playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1.5f, 0.55f);
        knockback(target, player.getRotationVec(1.0f), 1.0);
        player.sendMessage(Text.literal("§5§lBLACK FLASH! §r§dПроклятая энергия попала в идеальный момент."), true);
        setCooldown(player, "blackflash", 100);
        return 1;
    }

    public static int shrine(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        if (!ready(player, "shrine", 120)) return 0;
        List<LivingEntity> targets = targetsInCone(player, 10.0, 0.72, MAX_SWEEP_TARGETS);
        if (targets.isEmpty()) {
            player.sendMessage(Text.literal("§7В зоне разреза никого нет."), true);
            return 0;
        }
        Vec3d look = player.getRotationVec(1.0f);
        for (LivingEntity target : targets) {
            hit(player, target, 10.5f);
            Vec3d center = target.getBoundingBox().getCenter();
            world.spawnParticles(ParticleTypes.SWEEP_ATTACK, center.x, center.y, center.z, 5, 0.25, 0.25, 0.25, 0.0);
            world.spawnParticles(ParticleTypes.CRIT, center.x, center.y, center.z, 12, 0.3, 0.3, 0.3, 0.08);
            knockback(target, look, 0.35);
        }
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.2f, 0.7f);
        player.sendMessage(Text.literal("§cShrine: §fразрезов нанесено — " + targets.size()), true);
        setCooldown(player, "shrine", 120);
        return targets.size();
    }

    public static int reverseCursedTechnique(ServerPlayerEntity player) {
        if (!ready(player, "rct", 160)) return 0;
        float before = player.getHealth();
        player.heal(8.0f);
        player.removeStatusEffect(StatusEffects.POISON);
        player.removeStatusEffect(StatusEffects.WITHER);
        ServerWorld world = player.getServerWorld();
        world.spawnParticles(ParticleTypes.HEART, player.getX(), player.getY() + 1.0, player.getZ(),
                12, 0.45, 0.5, 0.45, 0.02);
        player.sendMessage(Text.literal("§aОбратная проклятая техника: +" + Math.round(player.getHealth() - before)
                + " HP. Яд и иссушение сняты."), true);
        setCooldown(player, "rct", 160);
        return 1;
    }

    public static int piercingBlood(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        if (!ready(player, "blood", 90)) return 0;
        LivingEntity target = findTarget(player, 13.0);
        if (target == null) {
            player.sendMessage(Text.literal("§7Пронзающая кровь не нашла цель."), true);
            return 0;
        }
        hit(player, target, 8.5f);
        Vec3d start = player.getEyePos();
        Vec3d end = target.getBoundingBox().getCenter();
        Vec3d line = end.subtract(start);
        for (int i = 1; i <= 12; i++) {
            Vec3d point = start.add(line.multiply(i / 12.0));
            world.spawnParticles(ParticleTypes.CRIT, point.x, point.y, point.z, 2, 0.02, 0.02, 0.02, 0.02);
        }
        world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR, end.x, end.y, end.z, 10, 0.2, 0.2, 0.2, 0.1);
        knockback(target, player.getRotationVec(1.0f), 0.65);
        player.sendMessage(Text.literal("§cPiercing Blood!"), true);
        setCooldown(player, "blood", 90);
        return 1;
    }

    private static void tick(ServerWorld world) {
        long now = world.getTime();
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (YUJI_MODE.getOrDefault(player.getUuid(), false) && now % 40 == 0) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 80, 1, true, false, true));
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 80, 0, true, false, true));
            }
            if (AdaptationManager.isEnabled(player) && now % 3 == 0) {
                AdaptationManager.renderWheel(world, player);
            }
        }

        Iterator<PendingHit> iterator = PENDING_HITS.iterator();
        while (iterator.hasNext()) {
            PendingHit pending = iterator.next();
            if (!pending.dimension.equals(world.getRegistryKey())) continue;
            pending.ticksLeft--;
            if (pending.ticksLeft <= 0) {
                var target = world.getEntityById(pending.targetId);
                ServerPlayerEntity attacker = world.getServer().getPlayerManager().getPlayer(pending.attacker);
                if (target instanceof LivingEntity living && living.isAlive() && attacker != null && attacker.isAlive()) {
                    hit(attacker, living, pending.damage);
                    Vec3d center = living.getBoundingBox().getCenter();
                    world.spawnParticles(ParticleTypes.CRIT, center.x, center.y, center.z, 18,
                            0.3, 0.35, 0.3, 0.2);
                    world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, center.x, center.y, center.z, 8,
                            0.2, 0.25, 0.2, 0.02);
                }
                iterator.remove();
            }
        }
    }

    private static boolean ready(ServerPlayerEntity player, String ability, long cooldownSeconds) {
        String key = player.getUuid() + ":" + ability;
        long now = player.getServerWorld().getTime();
        long nextUse = COOLDOWNS.getOrDefault(key, 0L);
        if (now < nextUse) {
            long seconds = (nextUse - now + 19) / 20;
            player.sendMessage(Text.literal("§7Техника перезаряжается: §e" + seconds + " сек."), true);
            return false;
        }
        return true;
    }

    private static void setCooldown(ServerPlayerEntity player, String ability, long seconds) {
        COOLDOWNS.put(player.getUuid() + ":" + ability, player.getServerWorld().getTime() + seconds * 20L);
    }

    private static LivingEntity findTarget(ServerPlayerEntity player, double range) {
        ServerWorld world = player.getServerWorld();
        Vec3d eye = player.getEyePos();
        Vec3d direction = player.getRotationVec(1.0f);
        Box search = player.getBoundingBox().expand(range, range * 0.65, range);
        return world.getEntitiesByClass(LivingEntity.class, search,
                        entity -> entity != player && entity.isAlive() && !entity.isSpectator())
                .stream()
                .filter(entity -> {
                    Vec3d delta = entity.getBoundingBox().getCenter().subtract(eye);
                    double distance = delta.length();
                    return distance > 0.0 && distance <= range && delta.normalize().dotProduct(direction) >= 0.78;
                })
                .min(Comparator.comparingDouble(player::squaredDistanceTo))
                .orElse(null);
    }

    private static List<LivingEntity> targetsInCone(ServerPlayerEntity player, double range,
                                                     double minDot, int maxTargets) {
        ServerWorld world = player.getServerWorld();
        Vec3d eye = player.getEyePos();
        Vec3d direction = player.getRotationVec(1.0f);
        Box search = player.getBoundingBox().expand(range, range * 0.5, range);
        List<LivingEntity> targets = new ArrayList<>(world.getEntitiesByClass(LivingEntity.class, search,
                entity -> entity != player && entity.isAlive() && !entity.isSpectator()));
        targets.removeIf(entity -> {
            Vec3d delta = entity.getBoundingBox().getCenter().subtract(eye);
            double distance = delta.length();
            return distance <= 0.0 || distance > range || delta.normalize().dotProduct(direction) < minDot;
        });
        targets.sort(Comparator.comparingDouble(player::squaredDistanceTo));
        if (targets.size() > maxTargets) return new ArrayList<>(targets.subList(0, maxTargets));
        return targets;
    }

    private static void hit(ServerPlayerEntity attacker, LivingEntity target, float damage) {
        ServerWorld world = attacker.getServerWorld();
        target.damage(world, attacker.getDamageSources().playerAttack(attacker), damage);
    }

    private static void knockback(LivingEntity target, Vec3d direction, double strength) {
        target.addVelocity(direction.x * strength, 0.22 * strength, direction.z * strength);
    }

    private static final class PendingHit {
        private final net.minecraft.registry.RegistryKey<World> dimension;
        private final int targetId;
        private final UUID attacker;
        private int ticksLeft;
        private final float damage;

        private PendingHit(net.minecraft.registry.RegistryKey<World> dimension, int targetId,
                           UUID attacker, int ticksLeft, float damage) {
            this.dimension = dimension;
            this.targetId = targetId;
            this.attacker = attacker;
            this.ticksLeft = ticksLeft;
            this.damage = damage;
        }
    }
}
