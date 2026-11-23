package io.github.doniel1488.fingershot.server;

import io.github.doniel1488.fingershot.FingerShot;
import io.github.doniel1488.fingershot.ShotConfig;
import io.github.doniel1488.fingershot.net.ShotPackets;
import io.github.doniel1488.fingershot.net.ShotPackets.Impact;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ShotRay {
    private static final TagKey<Block> FRAGMENT_MODELS = TagKey.of(RegistryKeys.BLOCK, FingerShot.id("sliceable"));
    private static final double STEP = 0.5;
    private static final int BLOCK_BUDGET = 8192;

    static ShotPackets.Beam fire(ServerPlayerEntity player, ShotConfig config, float power) {
        ServerWorld world = player.getWorld();
        Vec3d eye = player.getEyePos();
        Vec3d direction = player.getRotationVec(1).normalize();
        if (!finite(eye) || !finite(direction) || direction.lengthSquared() < 0.99) {
            return null;
        }

        double radius = config.radius * (0.62 + 0.38 * power);
        var trace = new CutPath(player, world, eye, direction, radius, config.range);
        Vec3d end = config.sliceBlocks ? trace.carve() : trace.stopAtFirstBlock();
        boolean hitEntity = config.affectEntities && hitEntities(player, world, eye, end, direction, radius, config, power);
        Impact impact = trace.broken > 0 ? Impact.SLICE : trace.blocked ? Impact.BLOCK
                : hitEntity ? Impact.ENTITY : Impact.AIR;
        return new ShotPackets.Beam(player.getId(), eye, end, impact, (float) radius, power,
                config.volume, trace.fragments);
    }

    private static boolean hitEntities(ServerPlayerEntity shooter, ServerWorld world, Vec3d from, Vec3d to,
                                       Vec3d direction, double radius, ShotConfig config, float power) {
        double length = from.distanceTo(to);
        boolean hit = false;
        for (var candidate : world.getOtherEntities(shooter, new Box(from, to).expand(radius), entity ->
                entity instanceof LivingEntity living && living.isAlive() && living.canHit()
                        && !living.isSpectator() && !(living instanceof PlayerEntity player
                        && (player.isCreative() || !shooter.shouldDamagePlayer(player))))) {
            var target = (LivingEntity) candidate;
            Box box = target.getBoundingBox();
            double along = box.getCenter().subtract(from).dotProduct(direction);
            if (along < 0 || along > length) {
                continue;
            }
            Vec3d center = from.add(direction.multiply(along));
            double x = Math.clamp(center.x, box.minX, box.maxX) - center.x;
            double y = Math.clamp(center.y, box.minY, box.maxY) - center.y;
            double z = Math.clamp(center.z, box.minZ, box.maxZ) - center.z;
            if (x * x + y * y + z * z > radius * radius) {
                continue;
            }
            hit = true;
            if ((config.damage == 0 || target.damage(world, shooter.getDamageSources().playerAttack(shooter),
                    config.damage * power)) && config.knockback > 0) {
                target.takeKnockback(config.knockback * power, -direction.x, -direction.z);
            }
        }
        return hit;
    }

    private static final class CutPath {
        private final ServerPlayerEntity player;
        private final ServerWorld world;
        private final Vec3d start;
        private final Vec3d direction;
        private final double radius;
        private final double range;
        private final Set<BlockPos> visited = new HashSet<>();
        private final List<ShotPackets.Fragment> fragments = new ArrayList<>();
        private double nextFragment;
        private int broken;
        private boolean blocked;

        CutPath(ServerPlayerEntity player, ServerWorld world, Vec3d start, Vec3d direction, double radius, double range) {
            this.player = player;
            this.world = world;
            this.start = start;
            this.direction = direction;
            this.radius = radius;
            this.range = range;
        }

        Vec3d carve() {
            int samples = (int) Math.ceil(range / STEP);
            for (int index = 0; index <= samples; index++) {
                double distance = Math.min(range, index * STEP);
                Vec3d point = start.add(direction.multiply(distance));
                BlockPos core = BlockPos.ofFloored(point);
                if (!world.isChunkLoaded(core) || !clearCore(core, distance) || broken >= BLOCK_BUDGET) {
                    return point;
                }
                carveCrossSection(point, distance);
            }
            return start.add(direction.multiply(range));
        }

        Vec3d stopAtFirstBlock() {
            Vec3d end = start;
            for (double distance = STEP; distance <= range + STEP; distance += STEP) {
                Vec3d next = start.add(direction.multiply(Math.min(distance, range)));
                if (!world.isChunkLoaded(BlockPos.ofFloored(next))) {
                    break;
                }
                var hit = world.raycast(new RaycastContext(end, next, RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE, player));
                if (hit.getType() == HitResult.Type.BLOCK) {
                    blocked = true;
                    return hit.getPos();
                }
                end = next;
            }
            return end;
        }

        private boolean clearCore(BlockPos pos, double distance) {
            BlockState state = world.getBlockState(pos);
            if (state.isAir() || state.isLiquid() || state.getCollisionShape(world, pos).isEmpty()) {
                return true;
            }
            if (!visited.add(pos) || !cut(pos, state, distance)) {
                blocked = true;
                return false;
            }
            return true;
        }

        private void carveCrossSection(Vec3d point, double distance) {
            BlockPos first = BlockPos.ofFloored(point.x - radius, point.y - radius, point.z - radius);
            BlockPos last = BlockPos.ofFloored(point.x + radius, point.y + radius, point.z + radius);
            for (BlockPos mutable : BlockPos.iterate(first, last)) {
                if (broken >= BLOCK_BUDGET) {
                    return;
                }
                Vec3d delta = Vec3d.ofCenter(mutable).subtract(start);
                double along = delta.dotProduct(direction);
                double radialSquared = delta.lengthSquared() - along * along;
                if (along < 0 || along > range || Math.abs(along - distance) > STEP * 0.5 + 1e-7
                        || radialSquared > radius * radius || !world.isChunkLoaded(mutable)) {
                    continue;
                }
                BlockPos pos = mutable.toImmutable();
                if (visited.add(pos)) {
                    cut(pos, world.getBlockState(pos), along);
                }
            }
        }

        private boolean cut(BlockPos pos, BlockState state, double along) {
            if (state.isAir() || state.isLiquid() || state.hasBlockEntity() || state.getHardness(world, pos) < 0
                    || !world.canEntityModifyAt(player, pos)
                    || !player.interactionManager.tryBreakBlock(pos) || world.getBlockState(pos) == state) {
                return false;
            }
            broken++;
            if (fragments.size() < ShotPackets.MAX_FRAGMENTS && along >= nextFragment
                    && state.isIn(FRAGMENT_MODELS) && state.isOpaqueFullCube()) {
                fragments.add(new ShotPackets.Fragment(pos, Block.getRawIdFromState(state)));
                nextFragment = along + Math.max(3, range / ShotPackets.MAX_FRAGMENTS);
            }
            return true;
        }
    }

    private static boolean finite(Vec3d vec) {
        return Double.isFinite(vec.x) && Double.isFinite(vec.y) && Double.isFinite(vec.z);
    }
}
