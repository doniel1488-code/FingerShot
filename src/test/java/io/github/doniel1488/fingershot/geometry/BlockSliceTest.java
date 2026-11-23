package io.github.doniel1488.fingershot.geometry;

import io.github.doniel1488.fingershot.geometry.BlockSlice.Face;
import io.github.doniel1488.fingershot.geometry.BlockSlice.Plane;
import io.github.doniel1488.fingershot.geometry.BlockSlice.Vertex;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockSliceTest {
    private static final List<Face> CUBE = List.of(
            face(p(0,0,0), p(0,0,1), p(0,1,1), p(0,1,0)),
            face(p(1,0,0), p(1,1,0), p(1,1,1), p(1,0,1)),
            face(p(0,0,0), p(1,0,0), p(1,0,1), p(0,0,1)),
            face(p(0,1,0), p(0,1,1), p(1,1,1), p(1,1,0)),
            face(p(0,0,0), p(0,1,0), p(1,1,0), p(1,0,0)),
            face(p(0,0,1), p(1,0,1), p(1,1,1), p(0,1,1)));

    @Test
    void bisectsCubeAndInterpolatesUvAndColor() {
        var plane = new Plane(p(0.25, 0, 0), p(1, 0, 0));
        var mesh = BlockSlice.split(CUBE, plane).orElseThrow();
        assertEquals(5, mesh.positive().size());
        assertEquals(5, mesh.negative().size());
        assertEquals(4, mesh.cap().size());
        for (var point : mesh.cap()) {
            assertEquals(0.25, point.x, 1e-8);
        }
        for (var face : mesh.positive()) {
            for (var vertex : face.vertices()) {
                assertTrue(plane.distance(vertex.point()) >= -1e-6);
                assertEquals(vertex.point().x, vertex.u(), 1e-6);
                assertEquals(vertex.point().z, vertex.v(), 1e-6);
                assertEquals(vertex.point().x, vertex.red(), 1e-6);
            }
        }
    }

    @Test
    void closesAnObliqueCutWithConsistentWinding() {
        var plane = new Plane(BlockSlice.CENTER, p(1, 1, 1).normalize());
        var mesh = BlockSlice.split(CUBE, plane).orElseThrow();
        assertEquals(6, mesh.cap().size());
        for (var point : mesh.cap()) {
            assertEquals(0, plane.distance(point), 1e-6);
        }
        var cap = mesh.cap();
        var winding = cap.get(1).subtract(cap.get(0)).crossProduct(cap.get(2).subtract(cap.get(0)));
        assertTrue(winding.dotProduct(plane.normal()) > 0);
        assertTrue(mesh.positive().stream().allMatch(face -> face.vertices().size() >= 3));
        assertTrue(mesh.negative().stream().allMatch(face -> face.vertices().size() >= 3));
    }

    @Test
    void rejectsEmptyOutsideAndTangentCuts() {
        assertTrue(BlockSlice.split(List.of(), new Plane(p(0.5, 0, 0), p(1, 0, 0))).isEmpty());
        assertTrue(BlockSlice.split(CUBE, new Plane(p(2, 0, 0), p(1, 0, 0))).isEmpty());
        assertTrue(BlockSlice.split(CUBE, new Plane(p(1, 0, 0), p(1, 0, 0))).isEmpty());
    }

    @Test
    void impactAndDirectionChangeCut() {
        var first = BlockSlice.forImpact(p(0, 0.1, 0.1), p(1, 0, 0));
        var second = BlockSlice.forImpact(p(0, 0.9, 0.9), p(1, 0, 0));
        var third = BlockSlice.forImpact(p(0, 0.1, 0.1), p(0, 1, 0));
        assertNotEquals(first.origin(), second.origin());
        assertNotEquals(first.normal(), third.normal());
        assertTrue(BlockSlice.split(CUBE, first).isPresent());
        assertTrue(BlockSlice.split(CUBE, second).isPresent());
    }

    private static Face face(Vec3d... points) {
        Vec3d normal = points[1].subtract(points[0]).crossProduct(points[2].subtract(points[0])).normalize();
        return new Face(Arrays.stream(points).map(point ->
                new Vertex(point, (float) point.x, (float) point.z, (float) point.x, 1, 1, 1)).toList(),
                normal, 0);
    }

    private static Vec3d p(double x, double y, double z) {
        return new Vec3d(x, y, z);
    }
}
