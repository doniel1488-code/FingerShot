package io.github.doniel1488.fingershot.net;

import io.github.doniel1488.fingershot.FingerShot;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public final class ShotPackets {
    public enum Phase { READY, CHARGING, FIRED, CANCELLED }
    public enum Intent { START, RELEASE, CANCEL }
    public enum Impact { AIR, BLOCK, ENTITY, SLICE }

    public record Input(Intent intent) implements CustomPayload {
        public static final Id<Input> ID = new Id<>(FingerShot.id("input_v2"));
        public static final PacketCodec<RegistryByteBuf, Input> CODEC = PacketCodec.of(
                (input, buf) -> buf.writeEnumConstant(input.intent),
                buf -> new Input(buf.readEnumConstant(Intent.class)));

        @Override public Id<Input> getId() { return ID; }
    }

    public record State(int player, Phase phase, int chargeTicks, int elapsedTicks, int cooldownTicks, float volume)
            implements CustomPayload {
        public static final Id<State> ID = new Id<>(FingerShot.id("state"));
        public static final PacketCodec<RegistryByteBuf, State> CODEC = PacketCodec.of(
                (state, buf) -> {
                    buf.writeVarInt(state.player);
                    buf.writeEnumConstant(state.phase);
                    buf.writeVarInt(state.chargeTicks);
                    buf.writeVarInt(state.elapsedTicks);
                    buf.writeVarInt(state.cooldownTicks);
                    buf.writeFloat(state.volume);
                },
                buf -> new State(buf.readVarInt(), buf.readEnumConstant(Phase.class),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readFloat()));

        @Override public Id<State> getId() { return ID; }
    }

    public static final int MAX_FRAGMENTS = 24;
    public record Fragment(BlockPos pos, int state) {}

    public record Beam(int player, Vec3d start, Vec3d end, Impact impact, float radius,
                       float power, float volume, List<Fragment> fragments) implements CustomPayload {
        public static final Id<Beam> ID = new Id<>(FingerShot.id("beam_v2"));
        public static final PacketCodec<RegistryByteBuf, Beam> CODEC = PacketCodec.of(
                (beam, buf) -> {
                    buf.writeVarInt(beam.player);
                    buf.writeVec3d(beam.start);
                    buf.writeVec3d(beam.end);
                    buf.writeEnumConstant(beam.impact);
                    buf.writeFloat(beam.radius);
                    buf.writeFloat(beam.power);
                    buf.writeFloat(beam.volume);
                    buf.writeVarInt(beam.fragments.size());
                    for (Fragment fragment : beam.fragments) {
                        buf.writeBlockPos(fragment.pos);
                        buf.writeVarInt(fragment.state);
                    }
                },
                buf -> {
                    int player = buf.readVarInt();
                    Vec3d start = buf.readVec3d();
                    Vec3d end = buf.readVec3d();
                    Impact impact = buf.readEnumConstant(Impact.class);
                    float radius = buf.readFloat();
                    float power = buf.readFloat();
                    float volume = buf.readFloat();
                    int count = buf.readVarInt();
                    if (count < 0 || count > MAX_FRAGMENTS) {
                        throw new IllegalArgumentException("Invalid fragment count: " + count);
                    }
                    List<Fragment> fragments = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) {
                        fragments.add(new Fragment(buf.readBlockPos(), buf.readVarInt()));
                    }
                    return new Beam(player, start, end, impact, radius, power, volume, fragments);
                });

        public Beam {
            fragments = List.copyOf(fragments);
        }

        @Override public Id<Beam> getId() { return ID; }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(Input.ID, Input.CODEC);
        PayloadTypeRegistry.playS2C().register(State.ID, State.CODEC);
        PayloadTypeRegistry.playS2C().register(Beam.ID, Beam.CODEC);
    }
}
