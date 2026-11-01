package me.ladypaladra.thearmorymod.dice;

import org.joml.Matrix3d;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/**
 * A die as a rigid body: its true shape falling, striking, sliding and tipping against the blocks around it.
 *
 * <p>Each game tick is cut into small steps, more when the die moves fast, so even the smallest die cannot pass through
 * a block between two steps. In each step the corners that dig into a block, and the block edges that dig into the die,
 * become contacts. The contacts are solved together as impulses, with a bounce that loses most of its speed and a grip
 * that turns a slide into a roll, the way a die behaves on a table. Overlap is then pushed out separately, so pushing
 * never adds bounce.</p>
 *
 * <p>Nothing here knows which face carries which number. The die only ever turns by what it strikes, so a fair start
 * stays fair: see {@link RigidFlight}.</p>
 */
final class DiePhysics {

    /**
     * Blocks per second squared, the same pull the engine gives its thrown objects.
     */
    static final double GRAVITY = 20.0;

    /**
     * How much of its striking speed a die keeps as bounce. A die on wood or stone keeps a little under half.
     */
    static final double RESTITUTION = 0.42;

    /**
     * Striking speeds under this, in blocks per second, do not bounce at all, so a die that is settling stops instead
     * of chattering.
     */
    static final double BOUNCE_THRESHOLD = 0.7;

    /**
     * How strongly the surface grips a sliding die, against how hard it presses.
     */
    static final double FRICTION = 0.5;

    /**
     * How fast spin fades while the die touches something, per second: the loss a real die has in its corners and
     * edges, which a perfect solid would not have.
     */
    static final double CONTACT_SPIN_DRAG = 1.2;

    /**
     * How fast spin fades in the air, per second.
     */
    static final double AIR_SPIN_DRAG = 0.05;

    /**
     * How fast a spin about the touching surface's normal fades while the die touches it, per second.
     */
    static final double TWIST_DRAG = 8.0;

    /**
     * Water against stone: a die in water weighs this much less, and slows this fast per second.
     */
    static final double WATER_LIFT = 0.4;
    static final double WATER_DRAG = 3.0;

    static final int ITERATIONS = 10;
    static final int MIN_STEPS = 4;
    static final int MAX_STEPS = 160;

    /**
     * Overlap left alone, in blocks, so a resting die touches without jitter, and how much of the rest is pushed out
     * per step.
     */
    static final double SLOP = 0.0015;
    static final double PUSH = 0.5;

    private static final int MAX_CONTACTS = 48;
    private static final double EDGE_PROBE = 1.0E-3;

    /**
     * How much deeper than the nearest face of a block another face may be and still be the way a corner leaves it.
     * A corner never digs more than a few hundredths of a block in one step.
     */
    private static final double EXIT_SLACK = 0.1;

    /**
     * What one tick did.
     */
    static final class Report {
        /**
         * Whether the die touched anything in the last step.
         */
        boolean touching;
        /**
         * The hardest strike of the tick, blocks per second into the surface.
         */
        double hardest;
        /**
         * The surface's direction at that strike.
         */
        final Vector3d struckNormal = new Vector3d();
        /**
         * Where that strike touched: the die's point inside the struck block, so the block can be read.
         */
        final Vector3d struckPoint = new Vector3d();
        /**
         * Whether any part of the die was under water.
         */
        boolean inWater;
        /**
         * Level, away from the walls the die touched in the last step, as a unit vector, or zero when it touched none.
         */
        final Vector3d wall = new Vector3d();
    }

    /**
     * The working memory of one solver, reused every step.
     */
    static final class Scratch {
        final DieWorld.Boxes boxes = new DieWorld.Boxes();
        final double[] px = new double[MAX_CONTACTS];
        final double[] py = new double[MAX_CONTACTS];
        final double[] pz = new double[MAX_CONTACTS];
        final Vector3d[] normal = vectors();
        final Vector3d[] arm = vectors();
        final Vector3d[] t1 = vectors();
        final Vector3d[] t2 = vectors();
        final double[] depth = new double[MAX_CONTACTS];
        final double[] target = new double[MAX_CONTACTS];
        final double[] kn = new double[MAX_CONTACTS];
        final double[] k1 = new double[MAX_CONTACTS];
        final double[] k2 = new double[MAX_CONTACTS];
        final double[] ln = new double[MAX_CONTACTS];
        final double[] l1 = new double[MAX_CONTACTS];
        final double[] l2 = new double[MAX_CONTACTS];
        final double[] lp = new double[MAX_CONTACTS];
        int count;
        int cornerCount;
        final Vector3d[] world = new Vector3d[16];
        final Vector3d[] planeN = new Vector3d[32];
        final double[] planeD = new double[32];
        final Matrix3d turn = new Matrix3d();
        final Matrix3d turnBack = new Matrix3d();
        final Matrix3d inverseInertia = new Matrix3d();
        final Vector3d pushV = new Vector3d();
        final Vector3d pushW = new Vector3d();
        final Vector3d a = new Vector3d();
        final Vector3d b = new Vector3d();
        final Vector3d c = new Vector3d();

        Scratch() {
            for (int i = 0; i < world.length; i++) {
                world[i] = new Vector3d();
            }
            for (int i = 0; i < planeN.length; i++) {
                planeN[i] = new Vector3d();
            }
        }

        private static Vector3d[] vectors() {
            Vector3d[] out = new Vector3d[MAX_CONTACTS];
            for (int i = 0; i < out.length; i++) {
                out[i] = new Vector3d();
            }
            return out;
        }
    }

    private DiePhysics() {
    }

    /**
     * Moves the die through one game tick of {@code dt} seconds.
     */
    static void tick(@Nonnull DieBody body, @Nonnull DieHull hull, @Nonnull DieWorld world, double dt,
                     @Nonnull Scratch s, @Nonnull Report report) {
        report.touching = false;
        report.hardest = 0.0;
        report.inWater = false;

        double travel = body.velocity.length() * dt + body.spin.angularVelocity.length() * hull.circumradius * dt;
        int steps = (int) Math.ceil(travel / (0.25 * hull.inradius));
        steps = Math.max(MIN_STEPS, Math.min(MAX_STEPS, steps));
        double h = dt / steps;

        // One look at the blocks covers the whole tick: the region the die can reach in it.
        double reach = hull.circumradius + travel + 0.05;
        s.boxes.clear();
        world.solids(body.position.x - reach, body.position.y - reach, body.position.z - reach,
                body.position.x + reach, body.position.y + reach, body.position.z + reach, s.boxes);

        double submerged = submerged(body, hull, world);
        report.inWater = submerged > 0.0;

        for (int step = 0; step < steps; step++) {
            body.velocity.y -= GRAVITY * (1.0 - WATER_LIFT * submerged) * h;
            if (submerged > 0.0) {
                double slow = Math.exp(-WATER_DRAG * submerged * h);
                body.velocity.mul(slow);
                body.spin.angularVelocity.mul(slow);
            }

            body.spin.orientation.get(s.turn);
            // The inertia in world axes: turned into the body's frame, applied, turned back.
            s.inverseInertia.set(s.turn).mul(hull.inverseInertia).mul(s.turnBack.set(s.turn).transpose());
            collide(body, hull, s);
            if (s.count > 0) {
                report.touching = true;
                prepare(body, s, report);
                for (int i = 0; i < ITERATIONS; i++) {
                    solveVelocity(body, s);
                }
                s.pushV.zero();
                s.pushW.zero();
                for (int i = 0; i < ITERATIONS; i++) {
                    solvePush(s, h);
                }
            } else {
                report.touching = false;
                report.wall.zero();
                s.pushV.zero();
                s.pushW.zero();
            }

            body.position.fma(h, body.velocity).fma(h, s.pushV);
            Vector3d spin = body.spin.angularVelocity;
            double savedX = spin.x;
            double savedY = spin.y;
            double savedZ = spin.z;
            spin.add(s.pushW);
            DieMotion.integrate(body.spin, h);
            spin.set(savedX, savedY, savedZ);

            spin.mul(Math.exp(-(s.count > 0 ? CONTACT_SPIN_DRAG : AIR_SPIN_DRAG) * h));
            if (s.count > 0) {
                // A point contact cannot resist a spin about its own normal, so a die on one corner would spin like a
                // top for seconds. A real die touches with a small patch that grinds that spin away.
                Vector3d n = s.a.zero();
                for (int c = 0; c < s.count; c++) {
                    n.add(s.normal[c]);
                }
                if (n.lengthSquared() > 1.0E-12) {
                    n.normalize();
                    double about = spin.dot(n);
                    spin.fma(-about * (1.0 - Math.exp(-TWIST_DRAG * h)), n);
                }
            }
        }
    }

    /**
     * How far the die, where it stands now, digs into the blocks around it: 0 when it is clear of them.
     */
    static double deepest(@Nonnull DieBody body, @Nonnull DieHull hull, @Nonnull DieWorld world, @Nonnull Scratch s) {
        double reach = hull.circumradius + 0.05;
        s.boxes.clear();
        world.solids(body.position.x - reach, body.position.y - reach, body.position.z - reach,
                body.position.x + reach, body.position.y + reach, body.position.z + reach, s.boxes);
        body.spin.orientation.get(s.turn);
        collide(body, hull, s);
        double deepest = 0.0;
        for (int c = 0; c < s.count; c++) {
            deepest = Math.max(deepest, s.depth[c]);
        }
        return deepest;
    }

    /**
     * How deep the die sits in water, from 0 (dry) to 1 (under).
     */
    static double submerged(@Nonnull DieBody body, @Nonnull DieHull hull, @Nonnull DieWorld world) {
        int x = (int) Math.floor(body.position.x);
        int y = (int) Math.floor(body.position.y);
        int z = (int) Math.floor(body.position.z);
        double top = world.fluidTop(x, y, z);
        if (Double.isNaN(top)) {
            // The die's middle may sit in dry air just over a cell of water.
            top = world.fluidTop(x, y - 1, z);
            if (Double.isNaN(top)) {
                return 0.0;
            }
        }
        double low = body.position.y - hull.inradius;
        double high = body.position.y + hull.inradius;
        return Math.max(0.0, Math.min(1.0, (top - low) / (high - low)));
    }

    /**
     * Finds every point where the die and a block overlap: the die's corners inside a block, and the stretches of a
     * block's open edges inside the die.
     */
    static void collide(@Nonnull DieBody body, @Nonnull DieHull hull, @Nonnull Scratch s) {
        s.count = 0;
        int corners = hull.corners.length;
        s.cornerCount = corners;
        for (int i = 0; i < corners; i++) {
            s.turn.transform(hull.corners[i], s.world[i].set(0.0)).sub(turnedCentre(hull, s)).add(body.position);
        }
        int planes = hull.normals.length;
        for (int k = 0; k < planes; k++) {
            s.turn.transform(hull.normals[k], s.planeN[k]);
            // A face's offset measured from the centre of weight, then carried to the die's place in the world.
            s.planeD[k] = hull.offsets[k] - hull.normals[k].dot(hull.centre) + s.planeN[k].dot(body.position);
        }

        DieWorld.Boxes boxes = s.boxes;
        for (int b = 0; b < boxes.count && s.count < MAX_CONTACTS; b++) {
            int o = 6 * b;
            double x0 = boxes.v[o];
            double y0 = boxes.v[o + 1];
            double z0 = boxes.v[o + 2];
            double x1 = boxes.v[o + 3];
            double y1 = boxes.v[o + 4];
            double z1 = boxes.v[o + 5];

            for (int i = 0; i < corners && s.count < MAX_CONTACTS; i++) {
                Vector3d p = s.world[i];
                if (p.x <= x0 || p.y <= y0 || p.z <= z0 || p.x >= x1 || p.y >= y1 || p.z >= z1) {
                    continue;
                }
                cornerContact(body, p, x0, y0, z0, x1, y1, z1, boxes, s);
            }

            for (int[] edge : hull.edges) {
                if (s.count >= MAX_CONTACTS) {
                    break;
                }
                dieEdgeContact(body, s.world[edge[0]], s.world[edge[1]], x0, y0, z0, x1, y1, z1, boxes, s);
            }

            for (int axis = 0; axis < 3 && s.count < MAX_CONTACTS; axis++) {
                for (int side = 0; side < 4 && s.count < MAX_CONTACTS; side++) {
                    edgeContact(axis, side, x0, y0, z0, x1, y1, z1, boxes, planes, s);
                }
            }
        }
    }

    /**
     * One of the die's edges running through a box. Depth inside a box is least at the box's faces and grows towards
     * its middle, so an edge whose two corners each sit barely inside, one by the top and one by the front, can be
     * buried deep between them. The contact goes at the edge's deepest point, and leaves the box as a corner would.
     */
    private static void dieEdgeContact(DieBody body, Vector3d p, Vector3d q, double x0, double y0, double z0,
                                       double x1, double y1, double z1, DieWorld.Boxes boxes, Scratch s) {
        double t0 = 0.0;
        double t1 = 1.0;
        double[] from = {p.x, p.y, p.z};
        double[] span = {q.x - p.x, q.y - p.y, q.z - p.z};
        double[] lo = {x0, y0, z0};
        double[] hi = {x1, y1, z1};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(span[axis]) < 1.0E-12) {
                if (from[axis] <= lo[axis] || from[axis] >= hi[axis]) {
                    return;
                }
                continue;
            }
            double ta = (lo[axis] - from[axis]) / span[axis];
            double tb = (hi[axis] - from[axis]) / span[axis];
            t0 = Math.max(t0, Math.min(ta, tb));
            t1 = Math.min(t1, Math.max(ta, tb));
            if (t0 >= t1) {
                return;
            }
        }
        double a = t0;
        double b = t1;
        for (int i = 0; i < 24 && b - a > 1.0E-5; i++) {
            double m1 = a + (b - a) / 3.0;
            double m2 = b - (b - a) / 3.0;
            if (boxDepth(from, span, m1, lo, hi) < boxDepth(from, span, m2, lo, hi)) {
                a = m1;
            } else {
                b = m2;
            }
        }
        double t = 0.5 * (a + b);
        // At either end the deepest point is a corner, which has its own contact already.
        if (t < 1.0E-3 || t > 1.0 - 1.0E-3) {
            return;
        }
        Vector3d point = s.b.set(from[0] + span[0] * t, from[1] + span[1] * t, from[2] + span[2] * t);
        cornerContact(body, point, x0, y0, z0, x1, y1, z1, boxes, s);
    }

    private static double boxDepth(double[] from, double[] span, double t, double[] lo, double[] hi) {
        double depth = Double.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++) {
            double v = from[axis] + span[axis] * t;
            depth = Math.min(depth, Math.min(v - lo[axis], hi[axis] - v));
        }
        return depth;
    }

    private static Vector3d turnedCentre(DieHull hull, Scratch s) {
        return s.turn.transform(hull.centre, s.c.set(0.0));
    }

    /**
     * A die corner inside a box leaves through the box's nearest face that is not covered by another block. Without
     * that check, a die sliding over the seam between two floor blocks would be shoved sideways by the hidden side face
     * of the next block.
     */
    private static void cornerContact(DieBody body, Vector3d p, double x0, double y0, double z0, double x1, double y1,
                                      double z1, DieWorld.Boxes boxes, Scratch s) {
        double[] pen = {p.x - x0, x1 - p.x, p.y - y0, y1 - p.y, p.z - z0, z1 - p.z};
        int fallback = 0;
        for (int f = 1; f < 6; f++) {
            if (pen[f] < pen[fallback]) {
                fallback = f;
            }
        }
        int best = -1;
        for (int f = 0; f < 6; f++) {
            // A far face is never a way out: a corner in the floor under a wall would otherwise leave through the
            // floor's underside, and the die with it.
            if (pen[f] > pen[fallback] + EXIT_SLACK || (best >= 0 && pen[f] >= pen[best])) {
                continue;
            }
            double out = pen[f] + EDGE_PROBE;
            double qx = p.x + (f == 0 ? -out : f == 1 ? out : 0.0);
            double qy = p.y + (f == 2 ? -out : f == 3 ? out : 0.0);
            double qz = p.z + (f == 4 ? -out : f == 5 ? out : 0.0);
            if (!boxes.holds(qx, qy, qz, 0.0)) {
                best = f;
            }
        }
        if (best < 0) {
            best = fallback;
        }
        int n = s.count++;
        s.px[n] = p.x;
        s.py[n] = p.y;
        s.pz[n] = p.z;
        s.normal[n].set(best == 0 ? -1 : best == 1 ? 1 : 0, best == 2 ? -1 : best == 3 ? 1 : 0,
                best == 4 ? -1 : best == 5 ? 1 : 0);
        s.depth[n] = pen[best];
    }

    /**
     * One of a box's twelve edges: {@code axis} is the direction it runs, {@code side} picks which of the four edges
     * along that direction. Only an open edge counts, one whose two faces are both uncovered: the seam between two
     * floor blocks is not an edge a die can catch on, a stair's lip is.
     */
    private static void edgeContact(int axis, int side, double x0, double y0, double z0, double x1, double y1,
                                    double z1, DieWorld.Boxes boxes, int planes, Scratch s) {
        int u = (axis + 1) % 3;
        int w = (axis + 2) % 3;
        boolean highU = (side & 1) != 0;
        boolean highW = (side & 2) != 0;
        double[] lo = {x0, y0, z0};
        double[] hi = {x1, y1, z1};
        double cu = highU ? hi[u] : lo[u];
        double cw = highW ? hi[w] : lo[w];
        double su = highU ? 1.0 : -1.0;
        double sw = highW ? 1.0 : -1.0;
        double mid = 0.5 * (lo[axis] + hi[axis]);

        // Just outside face U next to the edge, and just outside face W next to it: both must be open air.
        double[] q = new double[3];
        q[axis] = mid;
        q[u] = cu + su * EDGE_PROBE;
        q[w] = cw - sw * EDGE_PROBE;
        if (boxes.holds(q[0], q[1], q[2], 0.0)) {
            return;
        }
        q[u] = cu - su * EDGE_PROBE;
        q[w] = cw + sw * EDGE_PROBE;
        if (boxes.holds(q[0], q[1], q[2], 0.0)) {
            return;
        }

        Vector3d a = s.a;
        Vector3d d = s.b;
        setAxis(a, axis, lo[axis], u, cu, w, cw);
        setAxis(d, axis, hi[axis] - lo[axis], u, 0.0, w, 0.0);
        double t0 = 0.0;
        double t1 = 1.0;
        for (int k = 0; k < planes; k++) {
            Vector3d n = s.planeN[k];
            double num = s.planeD[k] - n.dot(a);
            double den = n.dot(d);
            if (Math.abs(den) < 1.0E-12) {
                if (num < 0.0) {
                    return;
                }
                continue;
            }
            double t = num / den;
            if (den > 0.0) {
                t1 = Math.min(t1, t);
            } else {
                t0 = Math.max(t0, t);
            }
            if (t0 >= t1) {
                return;
            }
        }
        // Besides leaving through one of its own faces, the die can clear the edge by moving straight off either of the
        // block's two open faces: up off a stair's tread, or out from its riser. How far each takes is how far the die
        // reaches past that face's plane.
        double reachU = Double.MAX_VALUE;
        double reachW = Double.MAX_VALUE;
        for (int i = 0; i < s.cornerCount; i++) {
            Vector3d p = s.world[i];
            reachU = Math.min(reachU, su * component(p, u));
            reachW = Math.min(reachW, sw * component(p, w));
        }
        double depthU = su * cu - reachU;
        double depthW = sw * cw - reachW;
        // Where the edge enters and leaves the die it lies on the die's surface, at no depth at all: the push belongs
        // at the edge's deepest point inside the die. Depth along the edge rises then falls, so a narrowing search
        // finds its top.
        double from = t0;
        double to = t1;
        for (int i = 0; i < 24 && to - from > 1.0E-5; i++) {
            double m1 = from + (to - from) / 3.0;
            double m2 = to - (to - from) / 3.0;
            if (insideAt(a, d, m1, planes, s) < insideAt(a, d, m2, planes, s)) {
                from = m1;
            } else {
                to = m2;
            }
        }
        edgePoint(a, d, 0.5 * (from + to), planes, u, su, depthU, w, sw, depthW, s);
    }

    /**
     * How deep a point of the edge lies inside the die: its distance to the die's nearest face, negative outside.
     */
    private static double insideAt(Vector3d a, Vector3d d, double t, int planes, Scratch s) {
        double x = a.x + d.x * t;
        double y = a.y + d.y * t;
        double z = a.z + d.z * t;
        double inside = Double.MAX_VALUE;
        for (int k = 0; k < planes; k++) {
            Vector3d n = s.planeN[k];
            inside = Math.min(inside, s.planeD[k] - (n.x * x + n.y * y + n.z * z));
        }
        return inside;
    }

    private static double component(Vector3d v, int axis) {
        return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
    }

    private static void setAxis(Vector3d v, int axis, double along, int u, double cu, int w, double cw) {
        double[] out = new double[3];
        out[axis] = along;
        out[u] = cu;
        out[w] = cw;
        v.set(out[0], out[1], out[2]);
    }

    /**
     * A point of a block edge inside the die is cleared the shortest way: the die moving back off one of its own faces,
     * or straight off one of the block's two open faces. A sharp die impaled on a stair's lip is lifted off it, where
     * pushing it along its own steep face would shove it sideways and leave it hanging there.
     */
    private static void edgePoint(Vector3d a, Vector3d d, double t, int planes, int u, double su, double depthU, int w,
                                  double sw, double depthW, Scratch s) {
        double x = a.x + d.x * t;
        double y = a.y + d.y * t;
        double z = a.z + d.z * t;
        int best = 0;
        double bestDepth = Double.MAX_VALUE;
        for (int k = 0; k < planes; k++) {
            Vector3d n = s.planeN[k];
            double inside = s.planeD[k] - (n.x * x + n.y * y + n.z * z);
            if (inside < bestDepth) {
                bestDepth = inside;
                best = k;
            }
        }
        if (bestDepth <= 0.0) {
            return;
        }
        int c = s.count++;
        s.px[c] = x;
        s.py[c] = y;
        s.pz[c] = z;
        if (depthU > 0.0 && depthU < bestDepth && depthU <= depthW) {
            axisNormal(s.normal[c], u, su);
            s.depth[c] = depthU;
        } else if (depthW > 0.0 && depthW < bestDepth) {
            axisNormal(s.normal[c], w, sw);
            s.depth[c] = depthW;
        } else {
            s.normal[c].set(s.planeN[best]).negate();
            s.depth[c] = bestDepth;
        }
    }

    private static void axisNormal(Vector3d dest, int axis, double sign) {
        dest.set(axis == 0 ? sign : 0.0, axis == 1 ? sign : 0.0, axis == 2 ? sign : 0.0);
    }

    /**
     * Readies each contact: its lever from the centre of weight, its two sliding directions, how hard each direction
     * is to push, and the bounce it should end with.
     */
    private static void prepare(DieBody body, Scratch s, Report report) {
        report.wall.zero();
        for (int c = 0; c < s.count; c++) {
            if (Math.abs(s.normal[c].y) < 0.7) {
                report.wall.add(s.normal[c].x, 0.0, s.normal[c].z);
            }
        }
        if (report.wall.lengthSquared() > 1.0E-12) {
            report.wall.normalize();
        }
        for (int c = 0; c < s.count; c++) {
            Vector3d r = s.arm[c].set(s.px[c], s.py[c], s.pz[c]).sub(body.position);
            Vector3d n = s.normal[c];
            tangents(n, s.t1[c], s.t2[c]);
            s.kn[c] = effectiveMass(r, n, s);
            s.k1[c] = effectiveMass(r, s.t1[c], s);
            s.k2[c] = effectiveMass(r, s.t2[c], s);
            double vn = pointVelocity(body, r, s.a).dot(n);
            s.target[c] = vn < -BOUNCE_THRESHOLD ? -RESTITUTION * vn : 0.0;
            if (-vn > report.hardest) {
                report.hardest = -vn;
                report.struckNormal.set(n);
                report.struckPoint.set(s.px[c], s.py[c], s.pz[c]);
            }
            s.ln[c] = 0.0;
            s.l1[c] = 0.0;
            s.l2[c] = 0.0;
            s.lp[c] = 0.0;
        }
    }

    private static void solveVelocity(DieBody body, Scratch s) {
        Vector3d v = s.a;
        for (int c = 0; c < s.count; c++) {
            Vector3d r = s.arm[c];
            Vector3d n = s.normal[c];
            pointVelocity(body, r, v);
            double vn = v.dot(n);
            double next = Math.max(0.0, s.ln[c] + (s.target[c] - vn) / s.kn[c]);
            double dn = next - s.ln[c];
            s.ln[c] = next;
            applyImpulse(body, r, n, dn, s);

            double limit = FRICTION * s.ln[c];
            pointVelocity(body, r, v);
            double n1 = clamp(s.l1[c] - v.dot(s.t1[c]) / s.k1[c], limit);
            applyImpulse(body, r, s.t1[c], n1 - s.l1[c], s);
            s.l1[c] = n1;
            pointVelocity(body, r, v);
            double n2 = clamp(s.l2[c] - v.dot(s.t2[c]) / s.k2[c], limit);
            applyImpulse(body, r, s.t2[c], n2 - s.l2[c], s);
            s.l2[c] = n2;
        }
    }

    /**
     * Pushes overlap out with a velocity of its own that is used for one step and then forgotten.
     */
    private static void solvePush(Scratch s, double h) {
        Vector3d cross = s.b;
        for (int c = 0; c < s.count; c++) {
            Vector3d r = s.arm[c];
            Vector3d n = s.normal[c];
            double vn = s.pushV.dot(n) + cross.set(s.pushW).cross(r).dot(n);
            double want = PUSH * Math.max(0.0, s.depth[c] - SLOP) / h;
            double next = Math.max(0.0, s.lp[c] + (want - vn) / s.kn[c]);
            double dp = next - s.lp[c];
            s.lp[c] = next;
            s.pushV.fma(dp, n);
            cross.set(r).cross(n).mul(dp);
            s.inverseInertia.transform(cross);
            s.pushW.add(cross);
        }
    }

    private static Vector3d pointVelocity(DieBody body, Vector3d r, Vector3d dest) {
        return dest.set(body.spin.angularVelocity).cross(r).add(body.velocity);
    }

    private static void applyImpulse(DieBody body, Vector3d r, Vector3d direction, double amount, Scratch s) {
        if (amount == 0.0) {
            return;
        }
        body.velocity.fma(amount, direction);
        Vector3d turn = s.c.set(r).cross(direction).mul(amount);
        s.inverseInertia.transform(turn);
        body.spin.angularVelocity.add(turn);
    }

    private static double effectiveMass(Vector3d r, Vector3d n, Scratch s) {
        Vector3d rn = new Vector3d(r).cross(n);
        s.inverseInertia.transform(rn);
        return 1.0 + rn.cross(r).dot(n);
    }

    private static void tangents(Vector3d n, Vector3d t1, Vector3d t2) {
        if (Math.abs(n.x) < 0.57) {
            t1.set(0.0, n.z, -n.y);
        } else {
            t1.set(n.y, -n.x, 0.0);
        }
        t1.normalize();
        t2.set(n).cross(t1);
    }

    private static double clamp(double value, double limit) {
        return Math.max(-limit, Math.min(limit, value));
    }
}
