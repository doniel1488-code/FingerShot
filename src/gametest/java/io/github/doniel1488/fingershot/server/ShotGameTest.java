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
    public void hitsAnEntityBeforeTheBlock(TestContext context) {
        var shooter = shooter(context);
        context.setBlockState(BLOCK, Blocks.COBBLESTONE);
        var pig = context.spawnEntity(EntityType.PIG, new Vec3d(2.5, 2, 3.3));
        float health = pig.getHealth();
        var result = ShotRay.fire(shooter, new ShotConfig(), 0.5f);
        context.assertTrue(result != null && result.impact() == Impact.ENTITY, Text.literal("Expected an entity hit"));
        context.assertEquals(health - 6, pig.getHealth(), Text.literal("Half charge must deal half damage"));
        context.expectBlock(Blocks.COBBLESTONE, BLOCK);
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
