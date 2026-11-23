package io.github.doniel1488.fingershot.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.block.Blocks;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

@SuppressWarnings("UnstableApiUsage")
public final class ShotClientGameTest implements FabricClientGameTest {
    private static final BlockPos TARGET = new BlockPos(1, 121, 5);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            var server = world.getServer();
            server.runCommand("gamerule doMobSpawning false");
            server.runCommand("fill -5 120 -9 8 125 9 minecraft:air");
            server.runCommand("fill -5 119 -9 8 119 9 minecraft:smooth_stone");
            server.runCommand("setblock 1 121 5 minecraft:cobblestone");
            server.runCommand("tp @a 1.5 120 0.5 0 0");
            server.runCommand("give @a minecraft:iron_pickaxe");
            world.getClientWorld().waitForChunksRender();
            context.waitTicks(5);

            context.runOnClient(client -> {
                client.onWindowFocusChanged(true);
                var state = client.world.getBlockState(TARGET);
                var hit = new Vec3d(1.55, 121.62, 5);
                if (!state.isOf(Blocks.COBBLESTONE)
                        || CutFragments.create(client.world, TARGET, state, hit, new Vec3d(0, 0, 1), 0) == null) {
                    throw new AssertionError("The actual cobblestone model must produce two fragments");
                }
            });

            context.getInput().holdKey(GLFW.GLFW_KEY_R);
            context.waitTicks(30);
            context.runOnClient(client -> {
                if (FingerShotClient.energy(client.player.getId(), 0) < 0.95f) {
                    throw new AssertionError("Holding the bound key must reach full charge");
                }
            });
            capture(context, "fingershot-first-person-charge");
            context.runOnClient(client -> client.options.setPerspective(Perspective.THIRD_PERSON_BACK));
            context.waitTicks(2);
            capture(context, "fingershot-third-person-charge");
            context.runOnClient(client -> client.options.setPerspective(Perspective.FIRST_PERSON));
            context.getInput().releaseKey(GLFW.GLFW_KEY_R);
            context.waitTicks(5);
            context.runOnClient(client -> {
                if (!client.world.getBlockState(TARGET).isAir()) {
                    throw new AssertionError("Releasing the key must slice the target block");
                }
            });
            capture(context, "fingershot-cut");
        }
    }

    private static void capture(ClientGameTestContext context, String name) {
        var options = TestScreenshotOptions.of(name).disableCounterPrefix();
        String directory = System.getenv("FINGERSHOT_CAPTURE_DIR");
        if (directory != null) {
            options = options.withDestinationDir(Path.of(directory));
        }
        context.takeScreenshot(options);
    }
}
