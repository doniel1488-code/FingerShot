package io.github.doniel1488.fingershot.server;

import io.github.doniel1488.fingershot.FingerShot;
import io.github.doniel1488.fingershot.ShotConfig;
import io.github.doniel1488.fingershot.net.ShotPackets;
import io.github.doniel1488.fingershot.net.ShotPackets.Impact;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

final class ShotRay {
    private static final TagKey<Block> SLICEABLE = TagKey.of(RegistryKeys.BLOCK, FingerShot.id("sliceable"));

    private record Target(LivingEntity entity, Vec3d point) {}

    static ShotPackets.Beam fire(ServerPlayerEntity player, ShotConfig config, float power) {
        ServerWorld world = player.getWorld();
        Vec3d eye = player.getEyePos();
        Vec3d direction = player.getRotationVec(1.0f).normalize();
        if (!finite(eye) || !finite(direction) || direction.lengthSquared() < 0.99) {
            return null;
        }

        Vec3d max = eye.add(direction.multiply(config.range));
        BlockHitResult blockHit = world.raycast(new RaycastContext(eye, max,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        Vec3d end = blockHit.getPos();
        Target target = config.affectEntities ? findTarget(world, player, eye, end) : null;
        Impact impact = Impact.AIR;
        BlockPos blockPos = BlockPos.ORIGIN;
        int stateId = 0;

        if (target != null) {
            impact = Impact.ENTITY;
            end = target.point();
            if (config.damage == 0 || target.entity().damage(world, player.getDamageSources().playerAttack(player),
                    config.damage * power)) {
                if (config.knockback > 0) {
                    target.entity().takeKnockback(config.knockback * power, -direction.x, -direction.z);
                }
            }
        } else if (blockHit.getType() == HitResult.Type.BLOCK) {
            impact = Impact.BLOCK;
            blockPos = blockHit.getBlockPos();
            BlockState state = world.getBlockState(blockPos);
            stateId = Block.getRawIdFromState(state);
            if (config.sliceBlocks && slice(world, player, blockPos, state)) {
                impact = Impact.SLICE;
            }
        }
        return new ShotPackets.Beam(player.getId(), eye, end, impact,
                blockPos, stateId, power, config.volume);
    }

    private static Target findTarget(ServerWorld world, ServerPlayerEntity shooter, Vec3d from, Vec3d to) {
        Box bounds = new Box(from, to).expand(1);
        double nearest = from.squaredDistanceTo(to);
        Target target = null;
        for (Entity candidate : world.getOtherEntities(shooter, bounds, entity ->
                entity instanceof LivingEntity living && living.isAlive() && living.canHit()
                        && !living.isSpectator() && !(living instanceof PlayerEntity player
                        && (player.isCreative() || !shooter.shouldDamagePlayer(player))))) {
            Box hitBox = candidate.getBoundingBox();
            Vec3d point = hitBox.contains(from) ? from : hitBox.raycast(from, to).orElse(null);
            if (point != null && from.squaredDistanceTo(point) < nearest) {
                nearest = from.squaredDistanceTo(point);
                target = new Target((LivingEntity) candidate, point);
            }
        }
        return target;
    }

    private static boolean slice(ServerWorld world, ServerPlayerEntity player, BlockPos pos, BlockState state) {
        return state.isIn(SLICEABLE)
                && state.isOpaqueFullCube()
                && state.getFluidState().isEmpty()
                && !state.hasBlockEntity()
                && state.getHardness(world, pos) >= 0
                && world.canEntityModifyAt(player, pos)
                && player.interactionManager.tryBreakBlock(pos)
                && world.getBlockState(pos) != state;
    }

    private static boolean finite(Vec3d vec) {
        return Double.isFinite(vec.x) && Double.isFinite(vec.y) && Double.isFinite(vec.z);
    }
}
