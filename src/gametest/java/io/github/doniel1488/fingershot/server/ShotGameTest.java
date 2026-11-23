package io.github.doniel1488.fingershot.server;

import io.github.doniel1488.fingershot.ShotConfig;
import io.github.doniel1488.fingershot.net.ShotPackets.Impact;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

public final class ShotGameTest {
    private static final BlockPos BLOCK = new BlockPos(2, 2, 4);

    @GameTest
    public void slicesAndDropsCobblestoneOnce(TestContext context) {
        var shooter = shooter(context);
        context.setBlockState(BLOCK, Blocks.COBBLESTONE);
        var result = ShotRay.fire(shooter, new ShotConfig(), 1);
        context.assertTrue(result != null && result.impact() == Impact.SLICE, Text.literal("Expected a slice"));
        context.expectBlock(Blocks.AIR, BLOCK);
        ShotRay.fire(shooter, new ShotConfig(), 1);

        Box vicinity = new Box(context.getAbsolutePos(BLOCK)).expand(1);
        int loot = context.getWorld().getEntitiesByClass(ItemEntity.class, vicinity,
                        item -> item.getStack().isOf(Items.COBBLESTONE)).stream()
                .mapToInt(item -> item.getStack().getCount()).sum();
        context.assertEquals(1, loot, Text.literal("Shot must drop cobblestone exactly once"));
        context.complete();
    }

    @GameTest
    public void preservesContainersAndStopsAtObstacles(TestContext context) {
        var shooter = shooter(context);
        context.setBlockState(BLOCK, Blocks.CHEST);
        var pig = context.spawnEntity(EntityType.PIG, new Vec3d(2.5, 2, 5.5));
        float health = pig.getHealth();
        var result = ShotRay.fire(shooter, new ShotConfig(), 1);
        context.assertTrue(result != null && result.impact() == Impact.BLOCK, Text.literal("Expected a block hit"));
        context.expectBlock(Blocks.CHEST, BLOCK);
        context.assertEquals(health, pig.getHealth(), Text.literal("Chest must shield the pig"));
        context.complete();
    }

    @GameTest
    public void carvesMultipleLayersAndHitsTargetsBehindThem(TestContext context) {
        var shooter = shooter(context);
        BlockPos rear = new BlockPos(2, 2, 6);
        BlockPos side = new BlockPos(3, 2, 5);
        BlockPos outside = new BlockPos(5, 2, 5);
        context.setBlockState(BLOCK, Blocks.COBBLESTONE);
        context.setBlockState(rear, Blocks.OBSIDIAN);
        context.setBlockState(side, Blocks.STONE_BRICKS);
        context.setBlockState(outside, Blocks.STONE);

        var front = context.spawnEntity(EntityType.PIG, new Vec3d(2.5, 2, 2.8));
        var back = context.spawnEntity(EntityType.PIG, new Vec3d(2.5, 2, 7.2));
        ShotConfig config = new ShotConfig();
        config.damage = 6;
        float frontHealth = front.getHealth();
        float backHealth = back.getHealth();
        var result = ShotRay.fire(shooter, config, 1);

        context.assertTrue(result != null && result.impact() == Impact.SLICE, Text.literal("Expected a cut through terrain"));
        context.expectBlock(Blocks.AIR, BLOCK);
        context.expectBlock(Blocks.AIR, rear);
        context.expectBlock(Blocks.AIR, side);
        context.expectBlock(Blocks.STONE, outside);
        context.assertEquals(frontHealth - 6, front.getHealth(), Text.literal("Front target must be hit once"));
        context.assertEquals(backHealth - 6, back.getHealth(), Text.literal("Rear target must be hit once"));
        context.complete();
    }

    @GameTest
    public void stopsAtBedrockAndRespectsBlockToggle(TestContext context) {
        var shooter = shooter(context);
        BlockPos rear = new BlockPos(2, 2, 6);
        context.setBlockState(BLOCK, Blocks.BEDROCK);
        context.setBlockState(rear, Blocks.STONE);
        ShotRay.fire(shooter, new ShotConfig(), 1);
        context.expectBlock(Blocks.BEDROCK, BLOCK);
        context.expectBlock(Blocks.STONE, rear);

        context.setBlockState(BLOCK, Blocks.COBBLESTONE);
        ShotConfig config = new ShotConfig();
        config.sliceBlocks = false;
        var result = ShotRay.fire(shooter, config, 1);
        context.assertTrue(result != null && result.impact() == Impact.BLOCK, Text.literal("Expected a solid impact"));
        context.expectBlock(Blocks.COBBLESTONE, BLOCK);
        context.expectBlock(Blocks.STONE, rear);
        context.complete();
    }

    @SuppressWarnings("removal")
    private static ServerPlayerEntity shooter(TestContext context) {
        var player = context.createMockCreativeServerPlayerInWorld();
        player.changeGameMode(GameMode.SURVIVAL);
        player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        Vec3d position = context.getAbsolute(new Vec3d(2.5, 1, 1.5));
        player.refreshPositionAndAngles(position.x, position.y, position.z, 0, 0);
        return player;
    }
}
