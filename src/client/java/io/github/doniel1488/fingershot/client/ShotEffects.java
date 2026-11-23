package io.github.doniel1488.fingershot.client;

import io.github.doniel1488.fingershot.ShotSounds;
import io.github.doniel1488.fingershot.net.ShotPackets;
import io.github.doniel1488.fingershot.net.ShotPackets.Impact;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Arm;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;

import java.util.ArrayDeque;
import java.util.Deque;

final class ShotEffects {
    private static final int BEAM_LIFETIME = 12;
    private static final int MAX_BEAMS = 48;
    private static final Deque<Beam> BEAMS = new ArrayDeque<>();
    private static final Deque<CutFragments> FRAGMENTS = new ArrayDeque<>();
    private static final DustParticleEffect CYAN = new DustParticleEffect(0x5BEFFF, 0.75f);
    private static final DustParticleEffect VIOLET = new DustParticleEffect(0xA383FF, 0.6f);
    private static final Random RANDOM = Random.create();

    private record Beam(Vec3d start, Vec3d end, Vec3d direction, int born, float power,
                        double intensity, float volume, Impact impact) {}

    static Vec3d handPosition(Entity entity, Vec3d eye, Vec3d direction) {
        Vec3d side = direction.crossProduct(new Vec3d(0, 1, 0)).normalize();
        if (side.lengthSquared() < 0.5) {
            Vec3d horizontalLook = entity.getRotationVec(1).withAxis(net.minecraft.util.math.Direction.Axis.Y, 0);
            side = horizontalLook.lengthSquared() > 0.01 ? EnergyRender.perpendicular(horizontalLook.normalize())
                    : new Vec3d(1, 0, 0);
        }
        double armSide = entity instanceof PlayerEntity player && player.getMainArm() == Arm.LEFT ? -1 : 1;
        return eye.add(side.multiply(0.25 * armSide)).add(0, -0.27, 0).add(direction.multiply(0.52));
    }

    static void charge(ClientWorld world, PlayerEntity player, float progress, ClientSettings config, int tick) {
        if (config.effects == 0 || tick % (progress > 0.85 ? 1 : 2) != 0) {
            return;
        }
        Vec3d direction = player.getRotationVec(1).normalize();
        Vec3d hand = handPosition(player, player.getEyePos(), direction);
        int count = Math.max(1, (int) (config.effects * (1 + 3 * progress)));
        for (int i = 0; i < count; i++) {
            Vec3d offset = noise(0.27 + progress * 0.18);
            particle(world, i % 2 == 0 ? CYAN : VIOLET, hand.add(offset), offset.multiply(-0.22));
        }
        if (progress > 0.75 && tick % 4 == 0) {
            particle(world, ParticleTypes.ELECTRIC_SPARK, hand, noise(0.13));
        }
    }

    static void playBeam(ClientWorld world, ShotPackets.Beam shot, int tick, ClientSettings config) {
        Vec3d direction = shot.end().subtract(shot.start()).normalize();
        var shooter = world.getEntityById(shot.player());
        Vec3d start = shooter == null || shot.start().squaredDistanceTo(shot.end()) < 0.5
                ? shot.start() : handPosition(shooter, shot.start(), direction);
        float volume = shot.volume() * config.volume;
        sound(world, start, ShotSounds.FIRE, volume, 0.88f + 0.32f * shot.power());

        if (BEAMS.size() >= MAX_BEAMS) {
            BEAMS.removeFirst();
        }
        BEAMS.addLast(new Beam(start, shot.end(), direction, tick, shot.power(), config.effects, volume, shot.impact()));

        if (shot.impact() == Impact.ENTITY) {
            sound(world, shot.end(), ShotSounds.ENTITY, volume * 0.85f, 1);
        } else if (shot.impact() != Impact.AIR) {
            sound(world, shot.end(), ShotSounds.BLOCK, volume * 0.8f, 1);
        }
        if (shot.impact() != Impact.AIR) {
            var state = Block.getStateFromRawId(shot.blockState());
            if (shot.impact() == Impact.SLICE) {
                var fragments = CutFragments.create(world, shot.block(), state, shot.end(), direction, tick);
                if (fragments != null) {
                    while (FRAGMENTS.size() * 2 + 2 > config.maxFragments) {
                        FRAGMENTS.removeFirst();
                    }
                    FRAGMENTS.addLast(fragments);
                }
            }
            impactParticles(world, shot, state, config.effects);
        }
    }

    private static void impactParticles(ClientWorld world, ShotPackets.Beam shot, BlockState state, double effects) {
        int count = (int) (effects * (8 + shot.power() * 14));
        boolean wood = state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.LOGS);
        boolean soil = state.isIn(BlockTags.DIRT);
        boolean entity = shot.impact() == Impact.ENTITY;
        ParticleEffect chips = new BlockStateParticleEffect(ParticleTypes.BLOCK, state);
        for (int i = 0; i < count; i++) {
            ParticleEffect material = entity || state.isAir() ? CYAN : i % 3 != 0 ? chips
                    : wood ? ParticleTypes.ELECTRIC_SPARK : soil ? ParticleTypes.POOF : ParticleTypes.SMOKE;
            particle(world, material, shot.end().add(noise(0.24)), noise(wood ? 0.21 : 0.13).add(0, 0.045, 0));
        }
        if (effects > 0) {
            particle(world, ParticleTypes.FLASH, shot.end(), Vec3d.ZERO);
        }
    }

    static void tick(int tick) {
        while (!FRAGMENTS.isEmpty() && tick - FRAGMENTS.getFirst().born() >= CutFragments.LIFETIME) {
            FRAGMENTS.removeFirst();
        }
        while (!BEAMS.isEmpty() && tick - BEAMS.getFirst().born() >= BEAM_LIFETIME) {
            Beam beam = BEAMS.removeFirst();
            var world = net.minecraft.client.MinecraftClient.getInstance().world;
            if (world != null) {
                sound(world, beam.end, ShotSounds.END, beam.volume * 0.34f, 1);
            }
        }
    }

    static void clear() {
        BEAMS.clear();
        FRAGMENTS.clear();
    }

    static void render(WorldRenderContext context, int tick) {
        MatrixStack matrices = context.matrixStack();
        var buffers = context.consumers();
        if (matrices == null || buffers == null || (BEAMS.isEmpty() && FRAGMENTS.isEmpty())) {
            return;
        }
        Vec3d camera = context.camera().getPos();
        float time = tick + context.tickCounter().getTickProgress(false);
        for (CutFragments fragments : FRAGMENTS) {
            fragments.render(matrices, buffers, camera, time);
        }
        VertexConsumer glow = buffers.getBuffer(EnergyRender.GLOW);
        for (Beam beam : BEAMS) {
            renderBeam(matrices, glow, camera, time, beam);
        }
    }

    private static void renderBeam(MatrixStack matrices, VertexConsumer glow, Vec3d camera, float time, Beam beam) {
        float age = time - beam.born;
        if (age < 0 || age >= BEAM_LIFETIME || beam.intensity <= 0) {
            return;
        }
        float fade = 1 - age / BEAM_LIFETIME;
        double radius = (0.05 + 0.105 * beam.power) * (0.35 + 0.65 * fade) * Math.min(1.4, beam.intensity);
        Vec3d local = beam.start.subtract(camera);
        Vec3d end = beam.end.subtract(beam.start);
        matrices.push();
        matrices.translate(local.x, local.y, local.z);
        var entry = matrices.peek();
        EnergyRender.tube(glow, entry, Vec3d.ZERO, end, radius * 1.9, radius * 1.35, 7,
                EnergyRender.color(0.37f, 0.24f, 1, fade * 0.25f));
        EnergyRender.tube(glow, entry, Vec3d.ZERO, end, radius, radius * 0.75, 7,
                EnergyRender.color(0.08f, 0.84f, 1, fade * 0.65f));
        EnergyRender.tube(glow, entry, Vec3d.ZERO, end, radius * 0.36, radius * 0.25, 6,
                EnergyRender.color(0.88f, 1, 1, fade * 0.99f));
        if (beam.direction.lengthSquared() > 0.5) {
            renderArcs(glow, entry, beam, end, radius, age, fade);
            if (beam.impact != Impact.AIR) {
                renderShockwave(glow, entry, end, beam.direction, age, fade);
            }
        }
        matrices.pop();
    }

    private static void renderArcs(VertexConsumer glow, MatrixStack.Entry entry, Beam beam, Vec3d end,
                                   double radius, float age, float fade) {
        Vec3d u = EnergyRender.perpendicular(beam.direction);
        Vec3d v = beam.direction.crossProduct(u);
        Vec3d previous = Vec3d.ZERO;
        int steps = Math.clamp((int) end.length() * 2, 8, 40);
        for (int step = 1; step <= steps; step++) {
            double t = (double) step / steps;
            double angle = t * 35 + age * 1.7 + (step % 2) * 0.9;
            Vec3d next = end.multiply(t).add(EnergyRender.around(u, v, angle)
                    .multiply(radius * (1.65 + (step % 3) * 0.45) * Math.sin(Math.PI * t)));
            EnergyRender.tube(glow, entry, previous, next, radius * 0.10, radius * 0.10, 3,
                    EnergyRender.color(0.66f, 0.83f, 1, fade * 0.75f));
            previous = next;
        }
    }

    private static void renderShockwave(VertexConsumer glow, MatrixStack.Entry entry, Vec3d center,
                                        Vec3d direction, float age, float fade) {
        Vec3d u = EnergyRender.perpendicular(direction);
        Vec3d v = direction.crossProduct(u);
        Vec3d origin = center.subtract(direction.multiply(0.06));
        double outer = 0.12 + age * 0.12;
        double inner = outer - 0.05 * fade;
        int color = EnergyRender.color(0.45f, 0.85f, 1, fade * 0.7f);
        for (int i = 0; i < 20; i++) {
            Vec3d a = EnergyRender.around(u, v, i * Math.PI / 10);
            Vec3d b = EnergyRender.around(u, v, (i + 1) * Math.PI / 10);
            EnergyRender.quad(glow, entry, origin.add(a.multiply(outer)), origin.add(b.multiply(outer)),
                    origin.add(b.multiply(inner)), origin.add(a.multiply(inner)), color, direction.negate());
        }
    }

    private static void sound(ClientWorld world, Vec3d p, SoundEvent sound, float volume, float pitch) {
        if (volume > 0) {
            world.playSoundClient(p.x, p.y, p.z, sound, SoundCategory.PLAYERS, volume, pitch, false);
        }
    }

    private static void particle(ClientWorld world, ParticleEffect effect, Vec3d p, Vec3d velocity) {
        world.addParticleClient(effect, p.x, p.y, p.z, velocity.x, velocity.y, velocity.z);
    }

    private static Vec3d noise(double scale) {
        return new Vec3d(RANDOM.nextTriangular(0, scale), RANDOM.nextTriangular(0, scale),
                RANDOM.nextTriangular(0, scale));
    }
}
