package io.github.doniel1488.fingershot.client.mixin;

import io.github.doniel1488.fingershot.client.FingerShotClient;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.util.Arm;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntityModel.class)
abstract class PlayerEntityModelMixin {
    @Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V", at = @At("TAIL"))
    private void fingershot$pointArm(PlayerEntityRenderState state, CallbackInfo callback) {
        float weight = FingerShotClient.poseWeight(state.id, 0);
        if (weight <= 0 || state.isSwimming || state.isGliding || state.isUsingItem) {
            return;
        }

        PlayerEntityModel model = (PlayerEntityModel) (Object) this;
        boolean right = state.mainArm == Arm.RIGHT;
        var arm = right ? model.rightArm : model.leftArm;
        var sleeve = right ? model.rightSleeve : model.leftSleeve;
        float side = right ? 1 : -1;
        float recoil = FingerShotClient.recoil(state.id, 0);
        arm.pitch = MathHelper.lerp(weight, arm.pitch, model.head.pitch - 1.52f - 0.52f * recoil);
        arm.yaw = MathHelper.lerp(weight, arm.yaw, model.head.yaw - side * 0.12f);
        arm.roll = MathHelper.lerp(weight, arm.roll, side * 0.055f);
        sleeve.copyTransform(arm);
    }
}
