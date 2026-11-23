package io.github.doniel1488.fingershot.client.mixin;

import io.github.doniel1488.fingershot.client.FingerShotClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemRenderer.class)
abstract class HeldItemRendererMixin {
    @Shadow
    private void renderArmHoldingItem(MatrixStack matrices, VertexConsumerProvider buffers, int light,
                                                float equipProgress, float swingProgress, Arm arm) {
        throw new AssertionError();
    }

    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"), cancellable = true)
    private void fingershot$pointArm(AbstractClientPlayerEntity player, float delta, float pitch, Hand hand,
                                    float swing, ItemStack item, float equip, MatrixStack matrices,
                                    VertexConsumerProvider buffers, int light, CallbackInfo callback) {
        if (hand != Hand.MAIN_HAND || player.isUsingItem() || player.isInvisible()) {
            return;
        }
        float weight = FingerShotClient.poseWeight(player.getId(), delta);
        if (weight <= 0) {
            return;
        }
        Arm arm = player.getMainArm();
        float side = arm == Arm.RIGHT ? 1 : -1;
        float recoil = FingerShotClient.recoil(player.getId(), delta);

        matrices.push();
        matrices.translate(-side * 0.25 * weight, 0.15 * weight + recoil * 0.14,
                -0.19 * weight + recoil * 0.16);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * 19 * weight));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-20 * weight - recoil * 27));
        renderArmHoldingItem(matrices, buffers, light, equip * (1 - weight), swing * (1 - weight), arm);
        matrices.pop();
        callback.cancel();
    }
}
