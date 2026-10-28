package me.ladypaladra.thearmorymod.dice;

import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * A die as a solid: its corners, its flat faces, where its weight sits and how hard it is to turn.
 *
 * <p>Built once per die from the corners the art exports. The faces are found from the corners themselves (every plane
 * through three corners that has all the others behind it), so the solid can never disagree with the drawn die. It is
 * then grown by the drawn edge bars' lip, a few thousandths of a block, so a landed die stands on the ground exactly
 * where its resting model does.</p>
 *
 * <p>Everything is in the flying model's frame. {@code centre} is where the weight sits, measured from the model's
 * middle: zero for every fair die, and moved only by the fairness test's loaded die.</p>
 */
public final class DieHull {

    private static final double PLANE_TOLERANCE = 1.0E-6;

    final Vector3d[] corners;
    final Vector3d[] normals;
    final double[] offsets;
    /**
     * The solid's edges, as pairs of corner indices: two corners joined where two faces meet.
     */
    final int[][] edges;
    final Vector3d centre;
    final Matrix3d inverseInertia;
    final double inradius;
    final double circumradius;

    private DieHull(Vector3d[] corners, Vector3d[] normals, double[] offsets, Vector3d centre, Matrix3d inverseInertia) {
        this.corners = corners;
        this.normals = normals;
        this.offsets = offsets;
        this.centre = centre;
        this.inverseInertia = inverseInertia;
        List<int[]> found = new ArrayList<>();
        for (int i = 0; i < corners.length; i++) {
            for (int j = i + 1; j < corners.length; j++) {
                int shared = 0;
                for (int k = 0; k < normals.length; k++) {
                    if (Math.abs(normals[k].dot(corners[i]) - offsets[k]) < 1.0E-5
                            && Math.abs(normals[k].dot(corners[j]) - offsets[k]) < 1.0E-5) {
                        shared++;
                    }
                }
                if (shared >= 2) {
                    found.add(new int[]{i, j});
                }
            }
        }
        this.edges = found.toArray(new int[0][]);
        double in = Double.MAX_VALUE;
        for (int i = 0; i < normals.length; i++) {
            in = Math.min(in, offsets[i] - normals[i].dot(centre));
        }
        double out = 0.0;
        for (Vector3d corner : corners) {
            out = Math.max(out, corner.distance(centre));
        }
        this.inradius = in;
        this.circumradius = out;
    }

    /**
     * The solid of {@code shape}, grown to the drawn die and weighted evenly.
     */
    @Nonnull
    public static DieHull of(@Nonnull DieShape shape) {
        return of(shape, new Vector3d());
    }

    /**
     * The solid of {@code shape} with its weight moved by {@code shift} blocks from the middle: a loaded die.
     */
    @Nonnull
    static DieHull of(@Nonnull DieShape shape, @Nonnull Vector3dc shift) {
        double grow = drawnScale(shape);
        Vector3d[] corners = new Vector3d[shape.corners().size()];
        for (int i = 0; i < corners.length; i++) {
            corners[i] = new Vector3d(shape.corners().get(i)).mul(grow);
        }
        List<Vector3d> normals = new ArrayList<>();
        List<Double> offsets = new ArrayList<>();
        findFaces(corners, normals, offsets);
        Vector3d[] n = normals.toArray(new Vector3d[0]);
        double[] d = new double[offsets.size()];
        for (int i = 0; i < d.length; i++) {
            d[i] = offsets.get(i);
        }
        Vector3d centre = new Vector3d();
        Matrix3d inertia = inertia(corners, n, d, centre);
        if (shift.lengthSquared() > 0.0) {
            // Moving the weight moves the turning point, and the inertia about it grows by the parallel axis rule.
            Vector3d s = new Vector3d(shift);
            centre.add(s);
            Matrix3d extra = new Matrix3d().scaling(s.lengthSquared()).sub(outer(s));
            inertia.add(extra);
        }
        return new DieHull(corners, n, d, centre, inertia.invert());
    }

    /**
     * How much the true solid must grow to stand where the drawn die stands. The resting heights are measured from the
     * drawn model, whose edge bars stand a little past the true faces. Each face up gives its own ratio, and they agree
     * to within a hair, so their mean is taken.
     */
    static double drawnScale(@Nonnull DieShape shape) {
        double sum = 0.0;
        for (DieShape.Face face : shape.faces()) {
            double lowest = 0.0;
            for (Vector3dc corner : shape.corners()) {
                lowest = Math.max(lowest, -corner.dot(face.normal()));
            }
            sum += face.restMiddle() / lowest;
        }
        double scale = sum / shape.faces().size();
        // A wild ratio means the data is wrong, not that the die is big: keep the true solid.
        return scale >= 1.0 && scale <= 1.1 ? scale : 1.0;
    }

    /**
     * The face whose outward direction points most nearly straight down, as an index into {@code normals}.
     */
    int lowestFace(@Nonnull Matrix3d turn) {
        int best = 0;
        double bestDown = Double.NEGATIVE_INFINITY;
        Vector3d world = new Vector3d();
        for (int i = 0; i < normals.length; i++) {
            turn.transform(normals[i], world);
            if (-world.y > bestDown) {
                bestDown = -world.y;
                best = i;
            }
        }
        return best;
    }

    private static void findFaces(Vector3d[] corners, List<Vector3d> normals, List<Double> offsets) {
        int n = corners.length;
        Vector3d ab = new Vector3d();
        Vector3d ac = new Vector3d();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                for (int k = j + 1; k < n; k++) {
                    corners[j].sub(corners[i], ab);
                    corners[k].sub(corners[i], ac);
                    Vector3d normal = new Vector3d(ab).cross(ac);
                    if (normal.length() < 1.0E-9) {
                        continue;
                    }
                    normal.normalize();
                    double offset = normal.dot(corners[i]);
                    boolean below = true;
                    boolean above = true;
                    for (Vector3d corner : corners) {
                        double side = normal.dot(corner) - offset;
                        below &= side <= PLANE_TOLERANCE;
                        above &= side >= -PLANE_TOLERANCE;
                    }
                    if (!below && !above) {
                        continue;
                    }
                    if (!below) {
                        normal.negate();
                        offset = -offset;
                    }
                    boolean known = false;
                    for (Vector3d other : normals) {
                        known |= other.dot(normal) > 1.0 - 1.0E-9;
                    }
                    if (!known) {
                        normals.add(normal);
                        offsets.add(offset);
                    }
                }
            }
        }
    }

    /**
     * The inertia per unit of mass about the centre of weight, for an even solid, from tetrahedra fanned out of each
     * face to an inner point. Sets {@code centre} to the centre of weight.
     */
    private static Matrix3d inertia(Vector3d[] corners, Vector3d[] normals, double[] offsets, Vector3d centre) {
        Vector3d inner = new Vector3d();
        for (Vector3d corner : corners) {
            inner.add(corner);
        }
        inner.div(corners.length);

        double volume = 0.0;
        Vector3d weighted = new Vector3d();
        Matrix3d covariance = new Matrix3d().zero();
        for (int f = 0; f < normals.length; f++) {
            List<Vector3d> ring = faceRing(corners, normals[f], offsets[f]);
            for (int t = 1; t + 1 < ring.size(); t++) {
                Vector3d a = new Vector3d(ring.get(0)).sub(inner);
                Vector3d b = new Vector3d(ring.get(t)).sub(inner);
                Vector3d c = new Vector3d(ring.get(t + 1)).sub(inner);
                double det = Math.abs(a.dot(new Vector3d(b).cross(c)));
                double v = det / 6.0;
                volume += v;
                weighted.fma(v, new Vector3d(a).add(b).add(c).div(4.0));
                // The second moment of a tetrahedron with one corner on the origin: det/120 times
                // (sum of each corner's outer product plus the outer product of their sum).
                Vector3d sum = new Vector3d(a).add(b).add(c);
                Matrix3d m = outer(a).add(outer(b)).add(outer(c)).add(outer(sum)).scale(det / 120.0);
                covariance.add(m);
            }
        }
        Vector3d mean = new Vector3d(weighted).div(volume);
        // Move the second moment from the inner point to the centre of weight, then per unit of volume.
        covariance.sub(outer(mean).scale(volume)).scale(1.0 / volume);
        centre.set(inner).add(mean);
        double trace = covariance.m00 + covariance.m11 + covariance.m22;
        return new Matrix3d().scaling(trace).sub(covariance);
    }

    /**
     * The corners lying on a face, in order around it.
     */
    private static List<Vector3d> faceRing(Vector3d[] corners, Vector3d normal, double offset) {
        List<Vector3d> on = new ArrayList<>();
        Vector3d middle = new Vector3d();
        for (Vector3d corner : corners) {
            if (Math.abs(normal.dot(corner) - offset) < 1.0E-5) {
                on.add(corner);
                middle.add(corner);
            }
        }
        middle.div(on.size());
        Vector3d u = new Vector3d(on.get(0)).sub(middle).normalize();
        Vector3d w = new Vector3d(normal).cross(u);
        on.sort((p, q) -> Double.compare(angle(p, middle, u, w), angle(q, middle, u, w)));
        return on;
    }

    private static double angle(Vector3d p, Vector3d middle, Vector3d u, Vector3d w) {
        Vector3d d = new Vector3d(p).sub(middle);
        return Math.atan2(d.dot(w), d.dot(u));
    }

    private static Matrix3d outer(Vector3d v) {
        return new Matrix3d(
                v.x * v.x, v.x * v.y, v.x * v.z,
                v.y * v.x, v.y * v.y, v.y * v.z,
                v.z * v.x, v.z * v.y, v.z * v.z);
    }
}
