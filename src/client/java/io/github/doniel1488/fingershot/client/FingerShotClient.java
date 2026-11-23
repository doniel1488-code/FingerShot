package io.github.doniel1488.fingershot.client;

import io.github.doniel1488.fingershot.FingerShot;
import io.github.doniel1488.fingershot.net.ShotPackets;
import io.github.doniel1488.fingershot.net.ShotPackets.Intent;
import io.github.doniel1488.fingershot.net.ShotPackets.Phase;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

public final class FingerShotClient implements ClientModInitializer {
    private static final Map<Integer, Pose> POSES = new HashMap<>();
    private static final int RECOIL_TICKS = 11;
    private static ClientSettings config;
    private static KeyBinding key;
    private static ClientWorld world;
    private static int tick;
    private static int cooldown;
    private static int cooldownLength;
    private static boolean held;
    private static boolean awaitingRelease;

    record Pose(Phase phase, int started, int chargeTicks, float volume) {
        float elapsed(float delta) {
            return Math.max(0, tick - started + delta);
        }

        float charge(float delta) {
            return Math.clamp(elapsed(delta) / chargeTicks, 0, 1);
        }
    }

    @Override
    public void onInitializeClient() {
        config = ClientSettings.load();
        key = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.fingershot.fire",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.fingershot"));
        ClientTickEvents.END_CLIENT_TICK.register(FingerShotClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(null));
        ClientPlayNetworking.registerGlobalReceiver(ShotPackets.State.ID, (packet, context) -> {
            resetIfWorldChanged(context.client());
            receiveState(context.client(), packet);
        });
        ClientPlayNetworking.registerGlobalReceiver(ShotPackets.Beam.ID, (packet, context) -> {
            resetIfWorldChanged(context.client());
            if (world != null) {
                ShotEffects.playBeam(world, packet, tick, config);
            }
        });
        WorldRenderEvents.AFTER_ENTITIES.register(context -> ShotEffects.render(context, tick));
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, FingerShot.id("charge"), FingerShotClient::hud);
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof PlayerEntityRenderer playerRenderer) {
                helper.register(new PointingFingerFeature(playerRenderer));
            }
        });
    }

    private static void receiveState(MinecraftClient client, ShotPackets.State packet) {
        if (packet.phase() == Phase.CHARGING || packet.phase() == Phase.FIRED) {
            Pose newPose = new Pose(packet.phase(), tick, packet.chargeTicks(), packet.volume());
            POSES.put(packet.player(), newPose);
            if (packet.phase() == Phase.CHARGING && packet.volume() * config.volume > 0) {
                client.getSoundManager().play(new ChargeSound(packet.player(), newPose, config.volume));
            }
        } else {
            POSES.remove(packet.player());
        }
        if (client.player != null && packet.player() == client.player.getId()) {
            cooldown = packet.cooldownTicks();
            if (packet.phase() == Phase.FIRED || cooldown > cooldownLength) {
                cooldownLength = cooldown;
            }
        }
    }

    static Pose pose(int player) {
        return POSES.get(player);
    }

    public static float poseWeight(int player, float delta) {
        Pose pose = POSES.get(player);
        if (pose == null) {
            return 0;
        }
        float age = pose.elapsed(delta);
        float time = pose.phase == Phase.CHARGING ? Math.min(1, age / 5) : 1 - Math.min(1, age / RECOIL_TICKS);
        return time * time * (3 - 2 * time);
    }

    public static float recoil(int player, float delta) {
        Pose pose = POSES.get(player);
        if (pose == null || pose.phase != Phase.FIRED) {
            return 0;
        }
        return (float) Math.sin(Math.PI * Math.min(1, pose.elapsed(delta) / RECOIL_TICKS))
                * poseWeight(player, delta);
    }

    public static float energy(int player, float delta) {
        Pose pose = POSES.get(player);
        return pose == null ? 0 : pose.phase == Phase.FIRED
                ? poseWeight(player, delta) : 0.25f + pose.charge(delta) * 0.75f;
    }

    private static void resetIfWorldChanged(MinecraftClient client) {
        if (client.world != world) {
            reset(client.world);
        }
    }

    private static void reset(ClientWorld nextWorld) {
        world = nextWorld;
        held = false;
        awaitingRelease = false;
        cooldown = 0;
        cooldownLength = 0;
        POSES.clear();
        ShotEffects.clear();
    }

    private static void tick(MinecraftClient client) {
        tick++;
        resetIfWorldChanged(client);
        if (client.player == null || world == null) {
            return;
        }
        if (cooldown > 0) {
            cooldown--;
        }
        ShotEffects.tick(tick);
        POSES.entrySet().removeIf(entry -> {
            Pose pose = entry.getValue();
            Entity entity = world.getEntityById(entry.getKey());
            if (!(entity instanceof PlayerEntity player) || !player.isAlive()
                    || (pose.phase == Phase.FIRED && pose.elapsed(0) >= RECOIL_TICKS)
                    || pose.elapsed(0) > 620) {
                return true;
            }
            if (pose.phase == Phase.CHARGING) {
                ShotEffects.charge(world, player, pose.charge(0), config, tick);
            }
            return false;
        });

        boolean down = key.isPressed();
        if (client.currentScreen != null || !client.isWindowFocused() || !client.player.isAlive()
                || client.player.isSpectator() || client.player.isUsingItem() || client.player.isSleeping()) {
            if (held) {
                send(Intent.CANCEL);
            }
            held = false;
            awaitingRelease = true;
            return;
        }
        if (awaitingRelease) {
            awaitingRelease = down;
            return;
        }
        if (down && !held && cooldown == 0) {
            send(Intent.START);
            held = true;
        } else if (!down && held) {
            send(Intent.RELEASE);
            held = false;
        } else if (down && cooldown > 0) {
            awaitingRelease = true;
        }
    }

    private static void send(Intent intent) {
        if (ClientPlayNetworking.canSend(ShotPackets.Input.ID)) {
            ClientPlayNetworking.send(new ShotPackets.Input(intent));
        }
    }

    private static void hud(DrawContext draw, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.currentScreen != null || client.options.hudHidden) {
            return;
        }
        Pose pose = pose(client.player.getId());
        boolean charging = pose != null && pose.phase == Phase.CHARGING;
        if (!charging && cooldown == 0) {
            return;
        }
        float delta = counter.getTickProgress(false);
        float progress = charging ? pose.charge(delta) :
                1 - Math.clamp((cooldown - delta) / Math.max(1, cooldownLength), 0, 1);
        int center = draw.getScaledWindowWidth() / 2;
        int y = draw.getScaledWindowHeight() / 2 + 24;
        int color = charging ? 0xFF69E9FF : 0xFFFFAA67;
        draw.fill(center - 30, y, center + 30, y + 4, 0xB0152433);
        draw.fill(center - 29, y + 1, center - 29 + Math.round(58 * progress), y + 3, color);
        if (charging) {
            int threshold = Math.max(4, pose.chargeTicks / 4) * 58 / pose.chargeTicks;
            draw.fill(center - 29 + threshold, y, center - 28 + threshold, y + 4, 0xCCFFFFFF);
        }
        String status = charging ? progress >= 1 ? "full" : "charging" : "cooldown";
        draw.drawCenteredTextWithShadow(client.textRenderer, Text.translatable("hud.fingershot." + status),
                center, y + 7, 0xFFF5F8FF);
    }
}
