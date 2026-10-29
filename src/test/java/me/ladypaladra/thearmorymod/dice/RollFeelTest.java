package me.ladypaladra.thearmorymod.dice;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The feel of each roll, headless: which of a block's sounds a strike plays, the trail and the glow by strength, the
 * pickup's flight, the fidgets' timing, and what the physics tells the ground about each strike and each splash.
 */
class RollFeelTest {

    @Test
    void hardBlocksRingAndSoftBlocksThud() {
        assertEquals(GroundSound.Kind.HIT, GroundSound.kind("Wood", true, true));
        assertEquals(GroundSound.Kind.HIT, GroundSound.kind("Stone", true, true));
        assertEquals(GroundSound.Kind.LAND, GroundSound.kind("Sand", true, true));
        assertEquals(GroundSound.Kind.LAND, GroundSound.kind("Grass", true, true));
        // A set lacking its preferred sound plays the other; one with neither plays none.
        assertEquals(GroundSound.Kind.LAND, GroundSound.kind("Glass", false, true));
        assertEquals(GroundSound.Kind.HIT, GroundSound.kind("Bush", true, false));
        assertEquals(GroundSound.Kind.HIT, GroundSound.kind("Sand", true, false));
        assertNull(GroundSound.kind("Water", false, false));
    }

    @Test
    void aStrikeIsAsLoudAsItIsHardWithinBounds() {
        assertEquals(0.15f, GroundSound.volume(0.0));
        assertEquals(0.8f, GroundSound.volume(100.0));
        float last = 0;
        for (int i = 0; i <= 80; i++) {
            float v = GroundSound.volume(i / 10.0);
            assertTrue(v >= last);
            last = v;
        }
        assertTrue(!GroundSound.dusts(1.0) && GroundSound.dusts(3.0) && !GroundSound.heavy(3.0) && GroundSound.heavy(8.0));
    }

    @Test
    void theTrailFollowsTheSameBandsAsTheReleaseSound() {
        // The charge file plays its whoosh from a hold of 0.45 s and its hard throw from 0.85 s (tools/dice/gamedata.py),
        // and a hold's strength is its square: the trail must change at the same holds, or a hard whoosh would leave a
        // faint trail.
        assertEquals("Faint", ThrowFeel.trail(ThrowFeel.power(0.44)));
        assertNull(ThrowFeel.trail(ThrowFeel.power(0.45)));
        assertNull(ThrowFeel.trail(ThrowFeel.power(0.83)));
        assertEquals("Bright", ThrowFeel.trail(ThrowFeel.power(0.85)));
        assertEquals("Bright", ThrowFeel.trail(1.0));
    }

    @Test
    void theGlowRisesWithTheThrowsStrength() {
        assertEquals(0.0f, DiceCharge.value(0.0));
        assertEquals(DiceCharge.FULL, DiceCharge.value(ThrowFeel.FULL_CHARGE_SECONDS));
        assertEquals(DiceCharge.FULL, DiceCharge.value(30.0));
        float last = -1;
        for (int i = 0; i <= 120; i++) {
            double s = i / 100.0;
            float v = DiceCharge.value(s);
            assertEquals(Math.round(ThrowFeel.power(s) * 100.0), v, 1.0E-6);
            assertTrue(v >= last);
            last = v;
        }
        // Full is reached only at the full charge, never early: the flash marks the strongest throw.
        assertTrue(DiceCharge.value(ThrowFeel.FULL_CHARGE_SECONDS * 0.99) < DiceCharge.FULL);
    }

    @Test
    void aPickedUpDieArcsIntoTheHandAndShrinks() {
        Vector3d start = new Vector3d(4, 64, 2);
        Vector3d hand = new Vector3d(0, 65.3, 0);
        assertTrue(PickUpFlight.place(start, hand, 0.0, new Vector3d()).distance(start) < 1.0E-9);
        assertTrue(PickUpFlight.place(start, hand, 1.0, new Vector3d()).distance(hand) < 1.0E-9);
        Vector3d middle = PickUpFlight.place(start, hand, 0.5, new Vector3d());
        double line = (start.y + hand.y) / 2.0;
        assertEquals(line + PickUpFlight.ARC, middle.y, 1.0E-9);
        assertEquals(0.7f, PickUpFlight.size(0.7f, 0.2));
        assertEquals(Math.round(0.7f * PickUpFlight.END_SCALE * 50.0f) / 50.0f, PickUpFlight.size(0.7f, 1.0));
    }

    @Test
    void theHardestStrikeIsKeptWhereItTouched() throws Exception {
        DiceCatalog catalog = DiceCatalog.load();
        DieShape shape = catalog.shapes().get("D6");
        // A raised floor, its top at y = 5, away from the origin: a point never set would read as the origin and fail.
        RigidFlight flight = new RigidFlight(shape, catalog.hull("D6"), spin(new SplittableRandom(4)),
                new Vector3d(3.3, 6.2, -2.6), new Vector3d(0, -6, 0));
        DieWorld floor = RigidRoll.boxes(java.util.List.of(new double[]{-1000, 1, -1000, 1000, 5, 1000}));
        SplittableRandom rng = new SplittableRandom(5);
        for (int i = 0; i < 60; i++) {
            flight.tick(floor, RigidRoll.DT, rng);
            if (flight.report.hardest > 2.0) {
                // The die strikes the floor's top from above: the point is at the floor under the die, the surface up.
                Vector3d at = flight.report.struckPoint;
                assertEquals(5.0, at.y, 0.06, "struck at " + at);
                assertTrue(Math.hypot(at.x - 3.3, at.z + 2.6) < shape.radius() + 0.05, "struck at " + at);
                assertTrue(flight.report.struckNormal.y > 0.9, "facing " + flight.report.struckNormal);
                return;
            }
        }
        throw new AssertionError("the die never struck the floor");
    }

    @Test
    void aDieFallingIntoWaterSplashesOnce() throws Exception {
        DiceCatalog catalog = DiceCatalog.load();
        DieShape shape = catalog.shapes().get("D20");
        RigidFlight flight = new RigidFlight(shape, catalog.hull("D20"), spin(new SplittableRandom(6)),
                new Vector3d(0, 4.0, 0), new Vector3d(0, -2, 0));
        DieWorld pool = RigidRoll.pool(2.0);
        SplittableRandom rng = new SplittableRandom(7);
        int splashes = 0;
        double speed = 0;
        for (int i = 0; i < 300 && !flight.landed; i++) {
            flight.tick(pool, RigidRoll.DT, rng);
            if (flight.enteredWater) {
                splashes++;
                speed = flight.entrySpeed;
            }
        }
        assertEquals(1, splashes);
        assertTrue(speed > 4.0, "entered at " + speed + " blocks a second");
        assertTrue(flight.inWater);
    }

    private static DieMotion.Spin spin(SplittableRandom rng) {
        DieMotion.Spin spin = new DieMotion.Spin();
        new TumbleMotion().launch(spin, new Vector3d(0, -1, 0), rng);
        return spin;
    }
}
