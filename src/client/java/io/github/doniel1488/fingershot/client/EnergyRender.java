package io.github.doniel1488.fingershot.client;

import io.github.doniel1488.fingershot.FingerShot;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

final class EnergyRender {
    private static final net.minecraft.util.Identifier PIXEL = FingerShot.id("textures/effect/white.png");
    static final RenderLayer SOLID = RenderLayer.getEntityCutoutNoCull(PIXEL);
    static final RenderLayer GLOW = RenderLayer.getEntityTranslucent(PIXEL);

    static int color(float red, float green, float blue, float alpha) {
        return Math.round(alpha * 255) << 24 | Math.round(red * 255) << 16
                | Math.round(green * 255) << 8 | Math.round(blue * 255);
    }

    static void quad(VertexConsumer out, MatrixStack.Entry entry, Vec3d a, Vec3d b, Vec3d c, Vec3d d,
                     int color, Vec3d normal) {
        vertex(out, entry, a, color, normal);
        vertex(out, entry, b, color, normal);
        vertex(out, entry, c, color, normal);
        vertex(out, entry, d, color, normal);
    }

    private static void vertex(VertexConsumer out, MatrixStack.Entry entry, Vec3d p, int color, Vec3d normal) {
        out.vertex(entry, (float) p.x, (float) p.y, (float) p.z).color(color).texture(0.5f, 0.5f)
                .overlay(OverlayTexture.DEFAULT_UV).light(LightmapTextureManager.MAX_LIGHT_COORDINATE)
                .normal(entry, (float) normal.x, (float) normal.y, (float) normal.z);
    }

    static void tube(VertexConsumer out, MatrixStack.Entry entry, Vec3d from, Vec3d to,
                     double radiusStart, double radiusEnd, int sides, int color) {
        Vec3d direction = to.subtract(from).normalize();
        if (direction.lengthSquared() < 0.5) {
            return;
        }
        Vec3d u = perpendicular(direction);
        Vec3d v = direction.crossProduct(u);
        for (int side = 0; side < sides; side++) {
            Vec3d a = around(u, v, (2 * Math.PI * side) / sides);
            Vec3d b = around(u, v, (2 * Math.PI * (side + 1)) / sides);
            quad(out, entry, from.add(a.multiply(radiusStart)), from.add(b.multiply(radiusStart)),
                    to.add(b.multiply(radiusEnd)), to.add(a.multiply(radiusEnd)), color, a.add(b).normalize());
        }
    }

    static void sphere(VertexConsumer out, MatrixStack.Entry entry, Vec3d center, double radius, int color) {
        int rings = 8;
        int sides = 12;
        for (int ring = 0; ring < rings; ring++) {
            double low = Math.PI * ring / rings;
            double high = Math.PI * (ring + 1) / rings;
            for (int side = 0; side < sides; side++) {
                double left = Math.TAU * side / sides;
                double right = Math.TAU * (side + 1) / sides;
                Vec3d a = spherePoint(low, left);
                Vec3d b = spherePoint(low, right);
                Vec3d c = spherePoint(high, right);
                Vec3d d = spherePoint(high, left);
                quad(out, entry, center.add(a.multiply(radius)), center.add(b.multiply(radius)),
                        center.add(c.multiply(radius)), center.add(d.multiply(radius)), color,
                        a.add(b).add(c).add(d).normalize());
            }
        }
    }

    private static Vec3d spherePoint(double latitude, double longitude) {
        double width = Math.sin(latitude);
        return new Vec3d(width * Math.cos(longitude), Math.cos(latitude), width * Math.sin(longitude));
    }

    static Vec3d perpendicular(Vec3d direction) {
        Vec3d axis = Math.abs(direction.y) > 0.94 ? new Vec3d(0, 0, 1) : new Vec3d(0, 1, 0);
        return direction.crossProduct(axis).normalize();
    }

    static Vec3d around(Vec3d u, Vec3d v, double angle) {
        return u.multiply(Math.cos(angle)).add(v.multiply(Math.sin(angle)));
    }
}
