package me.ladypaladra.thearmorymod.dice;

import org.joml.Vector3d;

import java.util.random.RandomGenerator;

/**
 * A headless stand-in for the engine's flight of a thrown die, so the motion can be run thousands of times in a test.
 *
 * <p>It does what the engine's projectile physics does on flat ground, at the same fixed step: gravity, a bounce that
 * reflects the velocity on the floor and scales it by the bounciness, and rest once a bounce is slower than the limit.
 * The turn is the real {@link DieMotion}, fed the same velocities the plugin feeds it in game. Walls are left out: they
 * change where a die goes, not which face it shows.</p>
 */
final class DiceFlight {

    static final double DT = 1.0 / 30.0;
    static final double GRAVITY = 20.0;
    static final double BOUNCINESS = 0.6;
    /**
     * Blocks per tick under which a bounce ends the flight, the engine's {@code BounceLimit}.
     */
    static final double BOUNCE_LIMIT = 0.03;
    static final int MAX_TICKS = 900;

    private DiceFlight() {
    }

    /**
     * Throws {@code shape} once with a random hold, look and run, and returns the face it shows.
     */
    static DieShape.Face throwOnce(DieMotion motion, DieShape shape, RandomGenerator rng) {
        DieMotion.Spin spin = new DieMotion.Spin();
        double power = ThrowFeel.power(rng.nextDouble() * ThrowFeel.FULL_CHARGE_SECONDS * 1.2);
        double yaw = rng.nextDouble() * 2.0 * Math.PI;
        double pitch = Math.toRadians(-40.0 + 70.0 * rng.nextDouble());
        Vector3d look = new Vector3d(-Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch));
        Vector3d run = new Vector3d((rng.nextDouble() - 0.5) * 8.0, 0.0, (rng.nextDouble() - 0.5) * 8.0);
        Vector3d velocity = ThrowFeel.launchVelocity(power, look, run, rng.nextInt(4) == 0, rng.nextInt(4) == 0,
                rng.nextInt(6) == 0, rng);
        motion.launch(spin, velocity, rng);

        Vector3d position = new Vector3d(0.0, 1.5, 0.0);
        Vector3d before = new Vector3d();
        Vector3d up = new Vector3d(0.0, 1.0, 0.0);
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            velocity.y -= GRAVITY * DT;
            position.fma(DT, velocity);
            if (position.y <= shape.radius() && velocity.y < 0.0) {
                position.y = shape.radius();
                before.set(velocity);
                velocity.y = -velocity.y;
                velocity.mul(BOUNCINESS);
                if (velocity.length() * DT < BOUNCE_LIMIT) {
                    break;
                }
                motion.bounce(spin, before, velocity, up, shape.radius(), rng);
            }
            motion.tick(spin, DT);
        }
        return motion.result(spin, shape, rng);
    }
}
