package io.github.doniel1488.fingershot.server;

import io.github.doniel1488.fingershot.ShotConfig;
import io.github.doniel1488.fingershot.net.ShotPackets;
import io.github.doniel1488.fingershot.net.ShotPackets.Phase;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ShotServer {
    private static final Map<UUID, ChargeSession> SESSIONS = new HashMap<>();
    private static ShotConfig config;
    private static long tick;

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            config = ShotConfig.load();
            tick = 0;
            SESSIONS.clear();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SESSIONS.clear());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tick++;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                ChargeSession session = SESSIONS.get(player.getUuid());
                if (session != null && (!isAllowed(player) || session.expired(tick)) && session.cancel()) {
                    sync(player, Phase.CANCELLED, session);
                }
            }
        });
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            ChargeSession session = SESSIONS.get(player.getUuid());
            if (session != null) {
                session.cancel();
                sync(player, Phase.CANCELLED, session);
            }
        });
        EntityTrackingEvents.START_TRACKING.register((entity, watcher) -> {
            if (entity instanceof ServerPlayerEntity shooter) {
                ChargeSession session = SESSIONS.get(shooter.getUuid());
                if (session != null && session.elapsedCharge(tick) >= 0) {
                    send(watcher, state(shooter, Phase.CHARGING, session));
                }
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                send(handler.player, state(handler.player, Phase.READY, new ChargeSession())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                SESSIONS.remove(handler.player.getUuid()));
        ServerPlayNetworking.registerGlobalReceiver(ShotPackets.Input.ID,
                (packet, context) -> handle(context.player(), packet));
    }

    private static void handle(ServerPlayerEntity player, ShotPackets.Input packet) {
        ChargeSession session = SESSIONS.computeIfAbsent(player.getUuid(), unused -> new ChargeSession());
        switch (session.input(packet.intent(), tick, isAllowed(player), config.chargeTicks, config.cooldownTicks)) {
            case START -> sync(player, Phase.CHARGING, session);
            case CANCEL -> sync(player, Phase.CANCELLED, session);
            case FIRE -> {
                sync(player, Phase.FIRED, session);
                var shot = ShotRay.fire(player, config, session.power());
                if (shot != null) {
                    broadcast(player, shot);
                }
            }
            case REJECT -> send(player, state(player, Phase.CANCELLED, session));
            case IGNORE -> { }
        }
    }

    private static boolean isAllowed(ServerPlayerEntity player) {
        return player.isAlive() && !player.isSpectator() && !player.isUsingItem() && !player.isSleeping();
    }

    private static ShotPackets.State state(ServerPlayerEntity player, Phase phase, ChargeSession session) {
        return new ShotPackets.State(player.getId(), phase, config.chargeTicks,
                Math.max(0, session.elapsedCharge(tick)), session.remainingCooldown(tick), config.volume);
    }

    private static void sync(ServerPlayerEntity player, Phase phase, ChargeSession session) {
        broadcast(player, state(player, phase, session));
    }

    static void broadcast(ServerPlayerEntity player, CustomPayload packet) {
        send(player, packet);
        for (ServerPlayerEntity watcher : PlayerLookup.tracking(player)) {
            if (watcher != player) {
                send(watcher, packet);
            }
        }
    }

    private static void send(ServerPlayerEntity player, CustomPayload packet) {
        if (ServerPlayNetworking.canSend(player, packet.getId())) {
            ServerPlayNetworking.send(player, packet);
        }
    }
}
