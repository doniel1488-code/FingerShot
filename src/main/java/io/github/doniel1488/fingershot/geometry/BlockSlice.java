package io.github.doniel1488.fingershot.geometry;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class BlockSlice {
    private static final double EPSILON = 1e-6;
    public static final Vec3d CENTER = new Vec3d(0.5, 0.5, 0.5);

    public record Vertex(Vec3d point, float u, float v, float red, float green, float blue, float alpha) {
        Vertex interpolate(Vertex other, double fraction) {
            float t = (float) fraction;
            return new Vertex(point.lerp(other.point, fraction), blend(u, other.u, t), blend(v, other.v, t),
                    blend(red, other.red, t), blend(green, other.green, t), blend(blue, other.blue, t),
                    blend(alpha, other.alpha, t));
        }

        private static float blend(float a, float b, float t) {
            return a + (b - a) * t;
        }
    }

    public record Face(List<Vertex> vertices, Vec3d normal, int emission) {}
    public record Plane(Vec3d origin, Vec3d normal) {
        double distance(Vec3d point) {
            return point.subtract(origin).dotProduct(normal);
        }
    }

    public record Mesh(List<Face> positive, List<Face> negative, List<Vec3d> cap) {}

    public static Plane forImpact(Vec3d relativeHit, Vec3d beam) {
        Vec3d axis = Math.abs(beam.y) > 0.93 ? new Vec3d(0, 0, 1) : new Vec3d(0, 1, 0);
        Vec3d across = beam.crossProduct(axis).normalize();
        Vec3d upright = across.crossProduct(beam).normalize();
        Vec3d normal = across.multiply(0.82).add(upright.multiply(0.57)).normalize();
        double inset = Math.clamp(relativeHit.subtract(CENTER).dotProduct(normal), -0.30, 0.30);
        return new Plane(CENTER.add(normal.multiply(inset)), normal);
    }

    public static Optional<Mesh> split(List<Face> faces, Plane plane) {
        List<Face> positive = new ArrayList<>();
        List<Face> negative = new ArrayList<>();
        List<Vec3d> seam = new ArrayList<>();
        boolean hasPositive = false;
        boolean hasNegative = false;

        for (Face face : faces) {
            for (Vertex vertex : face.vertices) {
                double distance = plane.distance(vertex.point);
                hasPositive |= distance > EPSILON;
                hasNegative |= distance < -EPSILON;
            }
            clip(face, plane, 1, seam).ifPresent(positive::add);
            clip(face, plane, -1, seam).ifPresent(negative::add);
        }
        if (!hasPositive || !hasNegative || positive.isEmpty() || negative.isEmpty() || seam.size() < 3) {
            return Optional.empty();
        }

        Vec3d centroid = Vec3d.ZERO;
        for (Vec3d point : seam) {
            centroid = centroid.add(point);
        }
        Vec3d center = centroid.multiply(1.0 / seam.size());
        Vec3d u = seam.getFirst().subtract(center).normalize();
        Vec3d v = plane.normal.crossProduct(u).normalize();
        seam.sort(Comparator.comparingDouble(point -> {
            Vec3d delta = point.subtract(center);
            return Math.atan2(delta.dotProduct(v), delta.dotProduct(u));
        }));
        if (areaNormal(seam).lengthSquared() < EPSILON * EPSILON) {
            return Optional.empty();
        }
        return Optional.of(new Mesh(List.copyOf(positive), List.copyOf(negative), List.copyOf(seam)));
    }

    private static Optional<Face> clip(Face face, Plane plane, int side, List<Vec3d> seam) {
        List<Vertex> source = face.vertices;
        if (source.isEmpty()) {
            return Optional.empty();
        }
        List<Vertex> points = new ArrayList<>();
        Vertex previous = source.getLast();
        double last = plane.distance(previous.point) * side;

        for (Vertex current : source) {
            double next = plane.distance(current.point) * side;
            if ((last < -EPSILON && next > EPSILON) || (last > EPSILON && next < -EPSILON)) {
                Vertex crossing = previous.interpolate(current, last / (last - next));
                points.add(crossing);
                addUnique(seam, crossing.point);
            }
            if (next >= -EPSILON) {
                points.add(current);
                if (Math.abs(next) <= EPSILON) {
                    addUnique(seam, current.point);
                }
            }
            previous = current;
            last = next;
        }

        if (points.size() < 3 || areaNormal(points.stream().map(Vertex::point).toList())
                .lengthSquared() < EPSILON * EPSILON) {
            return Optional.empty();
        }
        return Optional.of(new Face(List.copyOf(points), face.normal, face.emission));
    }

    private static Vec3d areaNormal(List<Vec3d> polygon) {
        Vec3d area = Vec3d.ZERO;
        Vec3d origin = polygon.getFirst();
        for (int index = 1; index + 1 < polygon.size(); index++) {
            area = area.add(polygon.get(index).subtract(origin)
                    .crossProduct(polygon.get(index + 1).subtract(origin)));
        }
        return area;
    }

    private static void addUnique(List<Vec3d> points, Vec3d point) {
        for (Vec3d existing : points) {
            if (existing.squaredDistanceTo(point) < EPSILON * EPSILON) {
                return;
            }
        }
        points.add(point);
    }
}
