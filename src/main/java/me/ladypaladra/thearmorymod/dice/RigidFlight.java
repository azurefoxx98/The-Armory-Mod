package me.ladypaladra.thearmorymod.dice;

import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nonnull;
import java.util.random.RandomGenerator;

/**
 * One thrown die flown as a rigid body until it lies still on a face.
 *
 * <p>A die is only ever read lying flat on a face. When it comes to rest on an edge or a corner, propped against a
 * wall or wedged in a gap, it is cocked, as a player at a table would say, and it is knocked: a small tip towards the
 * face nearest the ground and a little lift, as a hand would nudge it, until it falls flat. A die that stays cocked
 * through every nudge, or flies for too long, is called stuck and the caller lays it on its nearest face.</p>
 *
 * <p>It is fair by construction, for the same reason the tumble was: the starting turn is drawn evenly over every turn,
 * and the physics and the nudges only ever depend on the die's solid, never on which face carries which number. Every
 * die is a solid whose faces are all alike, so every face is as likely to end on top. The fairness test throws each die
 * through this very class.</p>
 *
 * <p>The game and the tests both drive it through {@link #tick}, so what the tests prove is what the game runs.</p>
 */
final class RigidFlight {

    /**
     * A die counts as still under this speed (blocks per second) and this spin (radians per second), held for this
     * many ticks while it touches something.
     */
    static final double STILL_SPEED = 0.06;
    static final double STILL_SPIN = 0.45;
    static final int STILL_TICKS = 8;

    /**
     * Ticks a die must stay still and cocked before it is nudged: long enough for a die tipping slowly off an edge to
     * fall by itself first.
     */
    static final int COCKED_TICKS = 15;

    /**
     * How far a face may lean from flat and still be the face the die lies on: 1.5 degrees. A die settled on a face
     * leans well under one, and one propped on a stair's lip leans about three, and must not count.
     */
    static final double FLAT = Math.cos(Math.toRadians(1.5));

    /**
     * A nudge: how fast it tips the die towards its nearest face, how much it lifts it, and how many are tried before
     * the die is called stuck.
     */
    static final double NUDGE_SPIN = 4.0;
    static final double NUDGE_LIFT = 1.2;
    static final int MAX_NUDGES = 6;

    /**
     * Blocks per second a nudge pushes a die away from a wall it leans on.
     */
    static final double WALL_PUSH = 1.5;

    /**
     * The longest a die may fly, in ticks at 30 a second.
     */
    static final int MAX_TICKS = 30 * 20;

    /**
     * How far a die may start inside a block before it is brought back out, in blocks.
     */
    static final double UNSTICK_DEPTH = 0.01;

    private static final ThreadLocal<DiePhysics.Scratch> SCRATCH = ThreadLocal.withInitial(DiePhysics.Scratch::new);

    enum Status {
        FLYING,
        LANDED,
        STUCK
    }

    final DieShape shape;
    final DieHull hull;
    final DieBody body;
    final DiePhysics.Report report = new DiePhysics.Report();
    int ticks;
    int stillTicks;
    int nudges;
    /**
     * Set once the die lies still on a face, from which the dice still flying knock against it as a fixed body.
     */
    boolean landed;
    /**
     * Whether the die was in water after the last tick, whether that tick took it in, and how fast it was falling
     * then, blocks per second: what the splash is made from.
     */
    boolean inWater;
    boolean enteredWater;
    double entrySpeed;
    private final Matrix3d turn = new Matrix3d();

    /**
     * @param modelPosition where the die's model middle starts, in the world
     * @param velocity      its launch velocity, blocks per second
     */
    RigidFlight(@Nonnull DieShape shape, @Nonnull DieHull hull, @Nonnull DieMotion.Spin spin,
                @Nonnull Vector3dc modelPosition, @Nonnull Vector3dc velocity) {
        this.shape = shape;
        this.hull = hull;
        this.body = new DieBody(spin);
        spin.orientation.transform(new Vector3d(hull.centre), body.position).add(modelPosition);
        body.velocity.set(velocity);
    }

    /**
     * Flies the die one tick of {@code dt} seconds and says where it stands.
     */
    @Nonnull
    Status tick(@Nonnull DieWorld world, double dt, @Nonnull RandomGenerator rng) {
        ticks++;
        double falling = Math.max(0.0, -body.velocity.y);
        DiePhysics.tick(body, hull, world, dt, SCRATCH.get(), report);
        enteredWater = report.inWater && !inWater;
        if (enteredWater) {
            entrySpeed = falling;
        }
        inWater = report.inWater;
        boolean still = report.touching && body.velocity.length() < STILL_SPEED
                && body.spin.angularVelocity.length() < STILL_SPIN;
        stillTicks = still ? stillTicks + 1 : 0;
        if (stillTicks >= STILL_TICKS && lyingFace() >= 0) {
            landed = true;
            return Status.LANDED;
        }
        if (stillTicks >= COCKED_TICKS) {
            if (nudges >= MAX_NUDGES) {
                landed = true;
                return Status.STUCK;
            }
            nudge(rng);
        }
        if (ticks >= MAX_TICKS) {
            landed = true;
            return Status.STUCK;
        }
        return Status.FLYING;
    }

    /**
     * Knocks this die and every other die thrown with it apart where they meet, so dice thrown together clack off
     * one another and never come to lie inside each other. Each die counts as a ball halfway between its inner and
     * outer size: close to its true shape, and the same whichever way it is turned, so no face is favoured. A die that
     * has already landed stays put and only the flying one moves.
     */
    void knockAgainst(@Nonnull Iterable<RigidFlight> others, @Nonnull RandomGenerator rng) {
        if (landed) {
            return;
        }
        double mine = 0.5 * (hull.inradius + hull.circumradius);
        Vector3d between = new Vector3d();
        for (RigidFlight other : others) {
            if (other == this) {
                continue;
            }
            double reach = mine + 0.5 * (other.hull.inradius + other.hull.circumradius);
            other.body.position.sub(body.position, between);
            double distance = between.length();
            if (distance >= reach || distance < 1.0E-9) {
                continue;
            }
            Vector3d n = between.div(distance);
            double overlap = reach - distance;
            double closing = new Vector3d(body.velocity).sub(other.body.velocity).dot(n);
            if (other.landed) {
                body.position.fma(-overlap, n);
                if (closing > 0.0) {
                    body.velocity.fma(-(1.0 + DiePhysics.RESTITUTION) * closing, n);
                }
            } else {
                body.position.fma(-0.5 * overlap, n);
                other.body.position.fma(0.5 * overlap, n);
                if (closing > 0.0) {
                    double share = 0.5 * (1.0 + DiePhysics.RESTITUTION) * closing;
                    body.velocity.fma(-share, n);
                    other.body.velocity.fma(share, n);
                    other.stillTicks = 0;
                }
            }
            if (closing > 0.0) {
                // A knock between two dice sets them turning, as the edges they strike with catch.
                body.spin.angularVelocity.add(TumbleMotion.randomUnit(rng, new Vector3d()).mul(TumbleMotion.KNOCK * closing));
            }
            stillTicks = 0;
        }
    }

    /**
     * Brings a die released inside a block back towards {@code clear}, a point in open air, to the farthest place along
     * the way where it is free. The throw starts at the hand, which can be inside a wall the thrower stands against, and the
     * eye cannot.
     *
     * @return whether the die had to be moved
     */
    boolean unstick(@Nonnull DieWorld world, @Nonnull Vector3dc clear) {
        DiePhysics.Scratch s = SCRATCH.get();
        if (DiePhysics.deepest(body, hull, world, s) <= UNSTICK_DEPTH) {
            return false;
        }
        Vector3d start = new Vector3d(body.position);
        Vector3d free = new Vector3d(clear);
        double low = 0.0;
        double high = 1.0;
        for (int i = 0; i < 12; i++) {
            double mid = 0.5 * (low + high);
            start.lerp(free, mid, body.position);
            if (DiePhysics.deepest(body, hull, world, s) <= UNSTICK_DEPTH) {
                high = mid;
            } else {
                low = mid;
            }
        }
        start.lerp(free, high, body.position);
        return true;
    }

    /**
     * The solid's face lying flat on the ground, as an index into the hull's faces, or -1 when the die is cocked.
     */
    int lyingFace() {
        body.spin.orientation.get(turn);
        int lowest = hull.lowestFace(turn);
        Vector3d down = turn.transform(hull.normals[lowest], new Vector3d());
        return -down.y >= FLAT ? lowest : -1;
    }

    /**
     * The result the die shows: the face, or for the D4 the corner, pointing most nearly straight up.
     */
    @Nonnull
    DieShape.Face face() {
        return DieOrientation.topFace(body.spin.orientation, shape);
    }

    /**
     * The height of the die's lowest corner: the top of whatever it lies on.
     */
    double floorY() {
        body.spin.orientation.get(turn);
        double low = Double.MAX_VALUE;
        Vector3d p = new Vector3d();
        Vector3d centre = turn.transform(hull.centre, new Vector3d());
        for (Vector3d corner : hull.corners) {
            turn.transform(corner, p).sub(centre);
            low = Math.min(low, p.y);
        }
        return body.position.y + low;
    }

    /**
     * Where the die's model middle is: the point the client turns the model about.
     */
    @Nonnull
    Vector3d modelPosition(@Nonnull Vector3d dest) {
        return body.spin.orientation.transform(new Vector3d(hull.centre), dest).negate().add(body.position);
    }

    /**
     * The die's box in the world right now, as tight as its turned corners, about its centre of weight.
     */
    void bounds(@Nonnull Vector3d min, @Nonnull Vector3d max) {
        body.spin.orientation.get(turn);
        min.set(Double.MAX_VALUE);
        max.set(-Double.MAX_VALUE);
        Vector3d p = new Vector3d();
        Vector3d centre = turn.transform(hull.centre, new Vector3d());
        for (Vector3d corner : hull.corners) {
            turn.transform(corner, p).sub(centre);
            min.min(p);
            max.max(p);
        }
    }

    /**
     * Knocks a cocked die: a tip towards the face nearest the ground, a little lift, a push away from any wall it leans
     * on and a little randomness, so it falls one way or the other the way a nudged die does. A die that the first two
     * nudges did not free is tipped a random way instead, since tipping towards the nearest face can mean tipping into
     * the very wall it leans on.
     */
    void nudge(@Nonnull RandomGenerator rng) {
        nudges++;
        stillTicks = 0;
        body.spin.orientation.get(turn);
        Vector3d down = turn.transform(hull.normals[hull.lowestFace(turn)], new Vector3d());
        Vector3d axis = new Vector3d(down).cross(0.0, -1.0, 0.0);
        if (nudges > 2) {
            axis.set(0.0);
        }
        // Each nudge a little firmer than the last: a small die propped in the corner of a step and its riser sits in a
        // hollow, and a gentle knock only rocks it back in.
        double firmer = 1.0 + 0.4 * (nudges - 1);
        Vector3d wall = report.wall;
        if (wall.lengthSquared() > 1.0E-8) {
            body.velocity.add(wall.x * WALL_PUSH * firmer, 0.0, wall.z * WALL_PUSH * firmer);
        }
        if (axis.lengthSquared() < 1.0E-10) {
            TumbleMotion.randomUnit(rng, axis);
            axis.y = 0.0;
        }
        if (axis.lengthSquared() < 1.0E-10) {
            axis.set(1.0, 0.0, 0.0);
        }
        axis.normalize().mul(NUDGE_SPIN * firmer);
        Vector3d jitter = TumbleMotion.randomUnit(rng, new Vector3d()).mul(0.25 * NUDGE_SPIN);
        body.spin.angularVelocity.add(axis).add(jitter);
        body.velocity.add((rng.nextDouble() - 0.5) * 0.4, NUDGE_LIFT * firmer, (rng.nextDouble() - 0.5) * 0.4);
    }
}
