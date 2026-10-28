package me.ladypaladra.thearmorymod.dice;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nonnull;
import java.util.random.RandomGenerator;

/**
 * Turns how a player throws into how the die leaves the hand.
 *
 * <p>Holding right click longer throws harder, on a curve that starts flat: a release almost at once barely lets go
 * of the die, the way an opening hand drops it, and the strength climbs faster the longer the hold. Crouching makes a
 * careful roll at the feet, sprinting or jumping throws harder, and the die keeps the thrower's own velocity, so a
 * throw while running back falls short and one while running forward goes far, smoothly rather than in steps. A small
 * random wobble in aim and strength makes no two throws the same.</p>
 */
public final class ThrowFeel {

    /**
     * Seconds of holding for a full throw.
     */
    public static final double FULL_CHARGE_SECONDS = 1.0;

    /**
     * How sharply the strength curve starts flat. 1 is a straight line, 2 a parabola.
     */
    static final double CURVE = 2.0;

    /**
     * Blocks per second of a release at once, and of a full throw. A full throw from standing lands about 8 blocks out
     * on flat ground before its bounces.
     */
    public static final double DROP_SPEED = 0.6;
    public static final double FULL_SPEED = 11.0;

    static final double CROUCH = 0.55;
    static final double SPRINT = 1.15;
    static final double AIRBORNE = 1.1;

    /**
     * The thrower's own velocity counts up to this many blocks per second. It is reported by the client, so a client
     * claiming a wild speed cannot fling a die across the world.
     */
    static final double MAX_CARRIED_SPEED = 12.0;

    /**
     * Random spread: strength within this fraction either way, aim within this many radians of the look.
     */
    static final double STRENGTH_SPREAD = 0.06;
    static final double AIM_SPREAD = Math.toRadians(3.0);

    private ThrowFeel() {
    }

    /**
     * The throw strength, 0 to 1, of a hold of {@code seconds}.
     */
    public static double power(double seconds) {
        double t = Math.max(0.0, Math.min(1.0, seconds / FULL_CHARGE_SECONDS));
        return Math.pow(t, CURVE);
    }

    /**
     * The die's launch velocity, blocks per second.
     *
     * @param power          the throw strength from {@link #power}
     * @param look           where the thrower looks, a unit vector
     * @param throwerVelocity the thrower's own velocity, blocks per second
     */
    @Nonnull
    public static Vector3d launchVelocity(double power, @Nonnull Vector3dc look, @Nonnull Vector3dc throwerVelocity,
                                          boolean crouching, boolean sprinting, boolean airborne,
                                          @Nonnull RandomGenerator rng) {
        double speed = DROP_SPEED + (FULL_SPEED - DROP_SPEED) * power;
        if (crouching) {
            speed *= CROUCH;
        } else if (sprinting) {
            speed *= SPRINT;
        }
        if (airborne) {
            speed *= AIRBORNE;
        }
        speed *= 1.0 + STRENGTH_SPREAD * (2.0 * rng.nextDouble() - 1.0);

        Vector3d aim = jitter(look, rng);
        Vector3d velocity = aim.mul(speed);

        Vector3d carried = new Vector3d(throwerVelocity);
        double carriedSpeed = carried.length();
        if (carriedSpeed > MAX_CARRIED_SPEED) {
            carried.mul(MAX_CARRIED_SPEED / carriedSpeed);
        }
        return velocity.add(carried);
    }

    /**
     * {@code look} turned by a random angle up to {@link #AIM_SPREAD}, spread evenly over that cone.
     */
    @Nonnull
    static Vector3d jitter(@Nonnull Vector3dc look, @Nonnull RandomGenerator rng) {
        Vector3d dir = new Vector3d(look).normalize();
        Vector3d side = Math.abs(dir.y) < 0.99 ? new Vector3d(0, 1, 0).cross(dir) : new Vector3d(1, 0, 0).cross(dir);
        side.normalize();
        Vector3d other = new Vector3d(dir).cross(side);
        double cosMax = Math.cos(AIM_SPREAD);
        double cos = 1.0 - rng.nextDouble() * (1.0 - cosMax);
        double sin = Math.sqrt(Math.max(0.0, 1.0 - cos * cos));
        double a = 2.0 * Math.PI * rng.nextDouble();
        return dir.mul(cos).fma(sin * Math.cos(a), side).fma(sin * Math.sin(a), other).normalize();
    }
}
