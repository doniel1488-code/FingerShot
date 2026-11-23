package io.github.doniel1488.fingershot.client;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Arm;
import net.minecraft.util.math.Vec3d;

public final class PointingFingerFeature extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {
    public PointingFingerFeature(PlayerEntityRenderer renderer) {
        super(renderer);
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumerProvider buffers, int light,
                       PlayerEntityRenderState state, float limbAngle, float limbDistance) {
        if (state.invisible || state.isSwimming || state.isGliding
                || FingerShotClient.poseWeight(state.id, 0) <= 0) {
            return;
        }
        Arm side = state.mainArm;
        ModelPart arm = side == Arm.RIGHT ? getContextModel().rightArm : getContextModel().leftArm;
        if (arm.visible) {
            renderFinger(matrices, buffers, arm, side, FingerShotClient.energy(state.id, 0));
        }
    }

    public static void renderFinger(MatrixStack matrices, VertexConsumerProvider buffers,
                                    ModelPart arm, Arm side, float power) {
        if (power <= 0) {
            return;
        }
        matrices.push();
        arm.applyTransform(matrices);
        double x = side == Arm.RIGHT ? 0.012 : -0.012;
        Vec3d base = new Vec3d(x, 9.6 / 16, -0.058);
        Vec3d tip = new Vec3d(x, (10.9 + 1.8 * power) / 16, -0.058);
        var core = buffers.getBuffer(EnergyRender.SOLID);
        EnergyRender.tube(core, matrices.peek(), base, tip, 0.042, 0.026, 6,
                EnergyRender.color(0.42f + 0.45f * power, 0.87f, 1, 1));

        var glow = buffers.getBuffer(EnergyRender.GLOW);
        EnergyRender.tube(glow, matrices.peek(), base, tip.add(0, 0.025, 0), 0.06, 0.042, 6,
                EnergyRender.color(0.34f, 0.67f, 1, 0.27f + 0.32f * power));
        matrices.pop();
    }
}
