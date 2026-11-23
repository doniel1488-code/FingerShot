package io.github.doniel1488.fingershot.client.mixin;

import io.github.doniel1488.fingershot.client.FingerShotClient;
import io.github.doniel1488.fingershot.client.PointingFingerFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntityRenderer.class)
abstract class PlayerEntityRendererMixin {
    @Inject(method = "renderArm", at = @At("TAIL"))
    private void fingershot$renderFirstPersonFinger(MatrixStack matrices, VertexConsumerProvider buffers, int light,
                                                   Identifier skin, ModelPart arm, boolean sleeve, CallbackInfo callback) {
        var client = MinecraftClient.getInstance();
        var player = client.player;
        if (player == null || player.isUsingItem() || player.isInvisible()) {
            return;
        }
        var model = ((PlayerEntityRenderer) (Object) this).getModel();
        Arm side = arm == model.rightArm ? Arm.RIGHT : Arm.LEFT;
        if (side == player.getMainArm()) {
            float delta = client.getRenderTickCounter().getTickProgress(false);
            PointingFingerFeature.renderFinger(matrices, buffers, arm, side,
                    FingerShotClient.energy(player.getId(), delta));
        }
    }
}
