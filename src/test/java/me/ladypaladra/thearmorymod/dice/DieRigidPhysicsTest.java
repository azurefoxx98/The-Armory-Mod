package me.ladypaladra.thearmorymod.dice;

import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The true physics behaves like a die: it falls off a corner or an edge onto a face, stands where its resting model
 * stands, never passes through a floor or a wall however small and fast it is, comes down stairs, sinks in water, and
 * never bounces higher than it fell.
 */
class DieRigidPhysicsTest {

    private static final Vector3d DOWN = new Vector3d(0.0, -1.0, 0.0);

    private static DiceCatalog catalog;

    @BeforeAll
    static void load() throws Exception {
        catalog = DiceCatalog.load();
    }

    @Test
    void aDieOnACornerOrAnEdgeFallsOntoAFace() {
        for (DieShape shape : catalog.shapes().values()) {
            DieHull hull = catalog.hull(shape.key());
            Vector3d corner = new Vector3d(hull.corners[0]).sub(hull.centre);
            Vector3d edge = new Vector3d(corner).add(nearestCorner(hull, 0)).mul(0.5);
            for (Vector3d pointDown : List.of(corner, edge)) {
                String what = shape.key() + (pointDown == corner ? " on a corner" : " on an edge");
                // Tipped two degrees off the balance, it must fall flat by itself, with no nudge.
                RigidRoll.Outcome tipped = standOn(shape, hull, pointDown, 2.0, RigidRoll.floor());
                assertEquals(RigidFlight.Status.LANDED, tipped.status(), what + ", tipped");
                assertEquals(0, tipped.nudges(), what + ", tipped, needed a nudge");
                // Balanced exactly, the physics may hold it, and then the nudge must bring it down.
                RigidRoll.Outcome balanced = standOn(shape, hull, pointDown, 0.0, RigidRoll.floor());
                assertEquals(RigidFlight.Status.LANDED, balanced.status(), what + ", balanced");
            }
        }
    }

    @Test
    void aLandedDieStandsWhereItsRestingModelStands() {
        DieWorld floor = RigidRoll.floor();
        for (DieShape shape : catalog.shapes().values()) {
            DieHull hull = catalog.hull(shape.key());
            SplittableRandom rng = new SplittableRandom(31);
            for (int i = 0; i < 60; i++) {
                RigidRoll.Outcome o = RigidRoll.throwOnce(shape, hull, floor, rng);
                assertNotNull(o.face(), shape.key() + " did not land");
                assertEquals(0.0, o.floorY(), 0.003, shape.key() + " lies above or in the ground");
                // The resting model is swapped in at the ground with its own middle height: the die must already be
                // there, or the swap would be seen as a jump.
                assertEquals(o.face().restMiddle(), o.middle().y - o.floorY(), 0.004,
                        shape.key() + " face " + o.face().label() + " lies at another height than its resting model");
            }
        }
    }

    @Test
    void noDieEverPassesThroughAFloorOrAWall() {
        // A floor a tenth of a block thick with nothing under it, and a wall an eighth thick.
        DieWorld slab = RigidRoll.boxes(List.of(new double[]{-1000, -0.1, -1000, 1000, 0, 1000}));
        DieWorld pane = RigidRoll.boxes(List.of(new double[]{-1000, -4, -1000, 1000, 0, 1000},
                new double[]{-50, 0, -2.125, 50, 6, -2.0}));
        for (DieShape shape : catalog.shapes().values()) {
            DieHull hull = catalog.hull(shape.key());
            SplittableRandom rng = new SplittableRandom(32);
            for (int i = 0; i < 40; i++) {
                double speed = 30.0 + 30.0 * rng.nextDouble();
                RigidRoll.Outcome dropped = RigidRoll.fly(shape, hull, slab, new Vector3d(0.0, 3.0, 0.0),
                        new Vector3d(0.0, -speed, 0.0), null, rng);
                assertEquals(RigidFlight.Status.LANDED, dropped.status(), shape.key() + " dropped at " + speed);
                assertTrue(dropped.floorY() > -0.005, shape.key() + " fell through a thin floor at " + speed);
                RigidRoll.Outcome thrown = RigidRoll.fly(shape, hull, pane, new Vector3d(0.0, 1.0, 0.0),
                        new Vector3d(0.0, 1.0, -speed), null, rng);
                assertEquals(RigidFlight.Status.LANDED, thrown.status(), shape.key() + " thrown at a wall");
                assertTrue(thrown.middle().z > -2.0, shape.key() + " passed through a thin wall at " + speed);
            }
        }
    }

    @Test
    void aDieComesDownStairsAndRestsOnAStep() {
        DieWorld stairs = RigidRoll.stairs(4);
        for (DieShape shape : catalog.shapes().values()) {
            DieHull hull = catalog.hull(shape.key());
            SplittableRandom rng = new SplittableRandom(33);
            for (int i = 0; i < 40; i++) {
                Vector3d velocity = new Vector3d((rng.nextDouble() - 0.5) * 2.0, 1.0 + rng.nextDouble(),
                        1.0 + 4.0 * rng.nextDouble());
                RigidRoll.Outcome o = RigidRoll.fly(shape, hull, stairs, new Vector3d(0.0, 5.0, -3.5), velocity, null,
                        rng);
                assertEquals(RigidFlight.Status.LANDED, o.status(), shape.key() + " on the stairs");
                double step = Math.rint(o.floorY());
                assertEquals(step, o.floorY(), 0.003, shape.key() + " rests between two steps");
                assertTrue(step >= 0.0 && step <= 4.0, shape.key() + " left the stairs downward");
            }
        }
    }

    @Test
    void aDieSinksInWaterAndRestsOnTheBottom() {
        DieShape shape = catalog.shape("D20");
        DieHull hull = catalog.hull("D20");
        SplittableRandom rng = new SplittableRandom(34);
        RigidRoll.Outcome wet = RigidRoll.fly(shape, hull, RigidRoll.pool(3.0), new Vector3d(0.0, 2.5, 0.0),
                new Vector3d(), null, rng);
        assertEquals(RigidFlight.Status.LANDED, wet.status());
        assertEquals(0.0, wet.floorY(), 0.003, "the die did not reach the bottom of the water");
        // Water slows the fall itself: the first touch of the bottom comes later than on dry ground.
        int dry = ticksToBottom(shape, hull, RigidRoll.floor());
        int under = ticksToBottom(shape, hull, RigidRoll.pool(3.0));
        assertTrue(under > dry * 1.3, "water did not slow the fall: " + under + " against " + dry + " ticks");
    }

    private static int ticksToBottom(DieShape shape, DieHull hull, DieWorld world) {
        DieMotion.Spin spin = new DieMotion.Spin();
        RigidFlight flight = new RigidFlight(shape, hull, spin, new Vector3d(0.0, 2.5, 0.0), new Vector3d());
        SplittableRandom rng = new SplittableRandom(37);
        for (int t = 1; t < 300; t++) {
            flight.tick(world, RigidRoll.DT, rng);
            if (flight.report.hardest > 0.0) {
                return t;
            }
        }
        return 300;
    }

    @Test
    void diceThrownTogetherNeverLieInsideEachOther() {
        // A crouched handful of five D100 pairs: ten dice leaving the hand side by side, as the game throws them.
        DieShape tens = catalog.shape("D100_Tens");
        DieShape units = catalog.shape("D100_Units");
        DieWorld floor = RigidRoll.floor();
        SplittableRandom rng = new SplittableRandom(38);
        for (int round = 0; round < 20; round++) {
            List<RigidFlight> bodies = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                DieShape shape = i % 2 == 0 ? tens : units;
                Vector3d velocity = new Vector3d((rng.nextDouble() - 0.5) * 1.5, 1.0, -3.0 - rng.nextDouble());
                DieMotion.Spin spin = new DieMotion.Spin();
                new TumbleMotion().launch(spin, velocity, rng);
                bodies.add(new RigidFlight(shape, catalog.hull(shape.key()), spin,
                        new Vector3d((i - 4.5) * 0.18, 1.4, 0.0), velocity));
            }
            for (int t = 0; t < RigidFlight.MAX_TICKS && !bodies.stream().allMatch(b -> b.landed); t++) {
                for (RigidFlight b : bodies) {
                    if (!b.landed) {
                        b.knockAgainst(bodies, rng);
                        b.tick(floor, RigidRoll.DT, rng);
                    }
                }
            }
            for (int i = 0; i < bodies.size(); i++) {
                RigidFlight a = bodies.get(i);
                assertTrue(a.landed && a.lyingFace() >= 0, "round " + round + ": die " + i + " did not land flat");
                for (int j = i + 1; j < bodies.size(); j++) {
                    RigidFlight b = bodies.get(j);
                    double reach = 0.5 * (a.hull.inradius + a.hull.circumradius + b.hull.inradius + b.hull.circumradius);
                    double apart = a.body.position.distance(b.body.position);
                    assertTrue(apart > reach - 0.02, "round " + round + ": dice " + i + " and " + j + " lie inside "
                            + "each other, " + apart + " apart for " + reach);
                }
            }
        }
    }

    @Test
    void aBounceNeverGainsHeight() {
        for (DieShape shape : catalog.shapes().values()) {
            DieHull hull = catalog.hull(shape.key());
            DieMotion.Spin spin = new DieMotion.Spin();
            spin.orientation.rotationTo(new Vector3d(hull.normals[0]), DOWN);
            double start = 2.0;
            RigidFlight flight = new RigidFlight(shape, hull, spin, new Vector3d(0.0, start, 0.0), new Vector3d());
            DieWorld floor = RigidRoll.floor();
            SplittableRandom rng = new SplittableRandom(35);
            boolean struck = false;
            double highest = 0.0;
            for (int t = 0; t < 120; t++) {
                flight.tick(floor, RigidRoll.DT, rng);
                struck |= flight.report.touching;
                if (struck) {
                    highest = Math.max(highest, flight.floorY());
                }
            }
            double fall = start - hull.inradius;
            double allowed = fall * DiePhysics.RESTITUTION * DiePhysics.RESTITUTION + 0.02;
            assertTrue(highest < allowed, shape.key() + " bounced to " + highest + " after a fall of " + fall);
        }
    }

    /**
     * Stands a die still with {@code pointDown} (in its own frame) straight down, tilted by {@code tilt} degrees, just
     * over the ground, and lets it go.
     */
    private static RigidRoll.Outcome standOn(DieShape shape, DieHull hull, Vector3dc pointDown, double tilt,
                                             DieWorld world) {
        DieMotion.Spin spin = new DieMotion.Spin();
        spin.orientation.rotationTo(new Vector3d(pointDown).normalize(), DOWN);
        if (tilt != 0.0) {
            spin.orientation.premul(new org.joml.Quaterniond().rotationX(Math.toRadians(tilt))
                    .mul(new org.joml.Quaterniond().rotationZ(Math.toRadians(tilt * 0.7))));
        }
        RigidFlight probe = new RigidFlight(shape, hull, spin, new Vector3d(), new Vector3d());
        double lift = -probe.floorY() + 0.0005;
        DieMotion.Spin still = new DieMotion.Spin();
        still.orientation.set(spin.orientation);
        return RigidRoll.fly(shape, hull, world, new Vector3d(0.0, lift, 0.0), new Vector3d(), still,
                new SplittableRandom(36));
    }

    private static Vector3d nearestCorner(DieHull hull, int from) {
        Vector3d best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < hull.corners.length; i++) {
            double d = hull.corners[i].distance(hull.corners[from]);
            if (i != from && d < bestDistance) {
                bestDistance = d;
                best = hull.corners[i];
            }
        }
        return new Vector3d(best).sub(hull.centre);
    }
}
