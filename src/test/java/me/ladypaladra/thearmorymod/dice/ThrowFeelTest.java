package me.ladypaladra.thearmorymod.dice;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThrowFeelTest {

    private static final Vector3d FORWARD = new Vector3d(0, 0, -1);
    private static final Vector3d STILL = new Vector3d();

    @Test
    void theCurveStartsAtNothingAndEndsAtFull() {
        assertEquals(0.0, ThrowFeel.power(0.0));
        // A release almost at once barely throws: a twentieth of the hold gives a four-hundredth of the strength.
        assertTrue(ThrowFeel.power(0.05) < 0.003);
        assertEquals(1.0, ThrowFeel.power(ThrowFeel.FULL_CHARGE_SECONDS));
        assertEquals(1.0, ThrowFeel.power(5.0));
        double last = -1;
        for (int i = 0; i <= 100; i++) {
            double p = ThrowFeel.power(i / 100.0);
            assertTrue(p >= last);
            last = p;
        }
    }

    @Test
    void aReleaseAtOnceDropsTheDie() {
        double speed = speed(0.0, false, false, false);
        assertTrue(speed < ThrowFeel.DROP_SPEED * 1.07, speed + " blocks a second");
    }

    @Test
    void crouchingThrowsSofterAndSprintingHarder() {
        double crouch = speed(1.0, true, false, false);
        double stand = speed(1.0, false, false, false);
        double sprint = speed(1.0, false, true, false);
        assertTrue(crouch < stand && stand < sprint, crouch + " " + stand + " " + sprint);
    }

    @Test
    void theDieKeepsTheThrowersVelocityUpToALimit() {
        SplittableRandom rng = new SplittableRandom(1);
        Vector3d back = ThrowFeel.launchVelocity(0.5, FORWARD, new Vector3d(0, 0, 4), false, false, false, rng);
        Vector3d forward = ThrowFeel.launchVelocity(0.5, FORWARD, new Vector3d(0, 0, -4), false, false, false,
                new SplittableRandom(1));
        assertTrue(-forward.z > -back.z + 7.0, "running forward " + forward + ", back " + back);
        Vector3d wild = ThrowFeel.launchVelocity(0.0, FORWARD, new Vector3d(500, 0, 0), false, false, false, rng);
        assertTrue(wild.length() <= ThrowFeel.MAX_CARRIED_SPEED + ThrowFeel.DROP_SPEED * 1.1,
                "a claimed speed of 500 gave " + wild);
    }

    @Test
    void theAimStaysWithinItsCone() {
        SplittableRandom rng = new SplittableRandom(2);
        for (int i = 0; i < 10_000; i++) {
            Vector3d aim = ThrowFeel.jitter(FORWARD, rng);
            assertTrue(aim.angle(FORWARD) <= ThrowFeel.AIM_SPREAD + 1.0E-9);
        }
    }

    private static double speed(double power, boolean crouch, boolean sprint, boolean air) {
        return ThrowFeel.launchVelocity(power, FORWARD, STILL, crouch, sprint, air, new SplittableRandom(7)).length();
    }
}
