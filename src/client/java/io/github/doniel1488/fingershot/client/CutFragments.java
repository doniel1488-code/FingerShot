package io.github.doniel1488.fingershot.client;

import io.github.doniel1488.fingershot.geometry.BlockSlice;
import io.github.doniel1488.fingershot.geometry.BlockSlice.Face;
import io.github.doniel1488.fingershot.geometry.BlockSlice.Mesh;
import io.github.doniel1488.fingershot.geometry.BlockSlice.Plane;
import io.github.doniel1488.fingershot.geometry.BlockSlice.Vertex;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BlockModelPart;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

record CutFragments(BlockPos pos, Plane plane, Mesh mesh, int light, int born) {
    static final int LIFETIME = 28;
    private static final double EPSILON = 1e-5;
    private static final RenderLayer BLOCK_TEXTURE =
            RenderLayer.getEntitySolid(Identifier.ofVanilla("textures/atlas/blocks.png"));

    static CutFragments create(ClientWorld world, BlockPos pos, BlockState state, Vec3d hit, Vec3d direction, int tick) {
        var manager = MinecraftClient.getInstance().getBlockRenderManager();
        var random = Random.create(state.getRenderingSeed(pos));
        List<Face> faces = new ArrayList<>();
        var directions = EnumSet.noneOf(Direction.class);
        for (BlockModelPart part : manager.getModel(state).getParts(random)) {
            for (Direction side : Direction.values()) {
                if (!addFaces(world, pos, state, part.getQuads(side), faces, directions)) {
                    return null;
                }
            }
            if (!addFaces(world, pos, state, part.getQuads(null), faces, directions)) {
                return null;
            }
        }
        // Only cap a closed unit cube. Resource packs can replace a tagged block's model.
        if (faces.size() != 6 || directions.size() != 6) {
            return null;
        }
        Plane plane = BlockSlice.forImpact(hit.subtract(Vec3d.of(pos)), direction);
        int light = WorldRenderer.getLightmapCoordinates(world, pos);
        return BlockSlice.split(faces, plane).map(mesh -> new CutFragments(pos, plane, mesh, light, tick)).orElse(null);
    }

    private static boolean addFaces(ClientWorld world, BlockPos pos, BlockState state, List<BakedQuad> quads,
                                 List<Face> faces, EnumSet<Direction> directions) {
        var colors = MinecraftClient.getInstance().getBlockColors();
        for (BakedQuad quad : quads) {
            int[] data = quad.vertexData();
            int stride = data.length / 4;
            if (stride < 6 || data.length % 4 != 0 || !directions.add(quad.face())) {
                return false;
            }
            Vec3d normal = Vec3d.of(quad.face().getVector());
            int tint = quad.hasTint() ? colors.getColor(state, world, pos, quad.tintIndex()) : 0xFFFFFF;
            float shade = quad.shade() ? world.getBrightness(quad.face(), true) : 1;
            List<Vertex> vertices = new ArrayList<>(4);
            var corners = new java.util.HashSet<Vec3d>();
            for (int index = 0; index < 4; index++) {
                int offset = index * stride;
                Vec3d point = new Vec3d(Float.intBitsToFloat(data[offset]),
                        Float.intBitsToFloat(data[offset + 1]), Float.intBitsToFloat(data[offset + 2]));
                Vec3d corner = new Vec3d(Math.rint(point.x), Math.rint(point.y), Math.rint(point.z));
                if (!Double.isFinite(point.lengthSquared())
                        || corner.squaredDistanceTo(point) > EPSILON * EPSILON
                        || corner.x < 0 || corner.y < 0 || corner.z < 0
                        || corner.x > 1 || corner.y > 1 || corner.z > 1
                        || Math.abs(corner.subtract(BlockSlice.CENTER).dotProduct(normal) - 0.5) > EPSILON
                        || !corners.add(corner)) {
                    break;
                }
                int rgba = data[offset + 3];
                vertices.add(new Vertex(point, Float.intBitsToFloat(data[offset + 4]),
                        Float.intBitsToFloat(data[offset + 5]), component(rgba, 0) * component(tint, 16) * shade,
                        component(rgba, 8) * component(tint, 8) * shade,
                        component(rgba, 16) * component(tint, 0) * shade, component(rgba, 24)));
            }
            if (vertices.size() != 4) {
                return false;
            }
            faces.add(new Face(List.copyOf(vertices), normal, quad.lightEmission()));
        }
        return true;
    }

    private static float component(int color, int shift) {
        return (color >>> shift & 255) / 255f;
    }

    void render(MatrixStack matrices, VertexConsumerProvider buffers, Vec3d camera, float time) {
        float age = time - born;
        if (age < 0 || age >= LIFETIME) {
            return;
        }
        renderHalf(matrices, buffers, camera, age, 1, mesh.positive());
        renderHalf(matrices, buffers, camera, age, -1, mesh.negative());
    }

    private void renderHalf(MatrixStack matrices, VertexConsumerProvider buffers, Vec3d camera,
                            float age, int side, List<Face> faces) {
        float t = age / LIFETIME;
        float scale = 1 - Math.clamp((t - 0.68f) / 0.32f, 0, 1);
        double separation = (0.025 + 0.82 * t) * side;
        Vec3d center = Vec3d.ofCenter(pos).subtract(camera).add(plane.normal().multiply(separation))
                .add(0, 0.48 * t - 0.82 * t * t, 0);
        matrices.push();
        matrices.translate(center.x, center.y, center.z);
        var axis = plane.normal();
        var spin = axis.crossProduct(new Vec3d(0, 1, 0)).normalize();
        if (spin.lengthSquared() < EPSILON) {
            spin = new Vec3d(1, 0, 0);
        }
        matrices.multiply(new Quaternionf().rotationAxis(t * side * 1.12f,
                (float) spin.x, (float) spin.y, (float) spin.z));
        matrices.scale(scale, scale, scale);
        matrices.translate(-0.5, -0.5, -0.5);
        MatrixStack.Entry entry = matrices.peek();
        VertexConsumer surface = buffers.getBuffer(BLOCK_TEXTURE);
        for (Face face : faces) {
            int packedLight = face.emission() > 0
                    ? (light & ~0xFFFF) | Math.max(light & 0xFFFF, face.emission() << 4) : light;
            List<Vertex> polygon = face.vertices();
            for (int index = 1; index + 1 < polygon.size(); index++) {
                surfaceVertex(surface, entry, polygon.getFirst(), face.normal(), packedLight);
                surfaceVertex(surface, entry, polygon.get(index), face.normal(), packedLight);
                surfaceVertex(surface, entry, polygon.get(index + 1), face.normal(), packedLight);
                surfaceVertex(surface, entry, polygon.get(index + 1), face.normal(), packedLight);
            }
        }

        VertexConsumer cap = buffers.getBuffer(EnergyRender.SOLID);
        List<Vec3d> capPoints = side > 0 ? mesh.cap().reversed() : mesh.cap();
        Vec3d capNormal = plane.normal().multiply(-side);
        Vec3d capCenter = Vec3d.ZERO;
        for (Vec3d point : capPoints) {
            capCenter = capCenter.add(point);
        }
        capCenter = capCenter.multiply(1.0 / capPoints.size());
        float heat = 1 - t;
        int centerColor = EnergyRender.color(0.18f + 0.56f * heat, 0.23f + 0.7f * heat, 0.55f + 0.45f * heat, 1);
        int edgeColor = EnergyRender.color(0.12f + 0.22f * heat, 0.75f + 0.25f * heat, 1, 1);
        for (int index = 0; index < capPoints.size(); index++) {
            Vec3d a = capPoints.get(index);
            Vec3d b = capPoints.get((index + 1) % capPoints.size());
            Vec3d innerA = a.lerp(capCenter, 0.085);
            Vec3d innerB = b.lerp(capCenter, 0.085);
            EnergyRender.quad(cap, entry, capCenter, innerA, innerB, innerB, centerColor, capNormal);
            EnergyRender.quad(cap, entry, innerA, a, b, innerB, edgeColor, capNormal);
        }
        matrices.pop();
    }

    private static void surfaceVertex(VertexConsumer out, MatrixStack.Entry entry, Vertex vertex,
                                      Vec3d normal, int light) {
        Vec3d p = vertex.point();
        out.vertex(entry, (float) p.x, (float) p.y, (float) p.z)
                .color(vertex.red(), vertex.green(), vertex.blue(), vertex.alpha())
                .texture(vertex.u(), vertex.v()).overlay(OverlayTexture.DEFAULT_UV).light(light)
                .normal(entry, (float) normal.x, (float) normal.y, (float) normal.z);
    }
}
