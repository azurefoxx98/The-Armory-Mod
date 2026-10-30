package me.ladypaladra.thearmorymod.dice;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nonnull;
import java.util.random.RandomGenerator;

/**
 * The free tumble: the die turns on every axis, its spin changes at each bounce, and it shows the face that ends on
 * top.
 *
 * <p>The die leaves the hand rolling forward, the way a thrown die does, with some random wobble. At a bounce the
 * ground grips the die and pulls its spin toward rolling along the ground, and the knock adds a random twist that grows
 * with how hard it struck. In the air the spin fades slowly.</p>
 *
 * <p>It is fair by construction. The starting turn is drawn evenly over every turn, and nothing after that depends on
 * which face points where: each spin change comes from velocities and random draws alone. So the final turn is as even
 * as the first, and on a die whose faces are all alike, every face is as likely to end on top.</p>
 */
public final class TumbleMotion implements DieMotion {

    /**
     * The fastest a die turns, radians per second. At 30 ticks a second this is about 34 degrees a tick: faster, and a
     * client drawing one turn per tick sees noise instead of a spin.
     */
    static final double MAX_SPIN = 18.0;

    /**
     * A throw's starting spin: this much at rest, plus this much per block a second of throw speed.
     */
    static final double SPIN_BASE = 2.0;
    static final double SPIN_PER_SPEED = 1.3;

    /**
     * How much of the starting spin axis is random rather than a forward roll. Zero would be a wheel.
     */
    static final double WOBBLE = 0.45;

    /**
     * How strongly a bounce pulls the spin toward rolling along the ground, from 0 (ice) to 1 (full grip).
     */
    static final double GRIP = 0.55;

    /**
     * A bounce's random twist, radians per second for each block a second the die struck the surface with.
     */
    static final double KNOCK = 1.1;

    /**
     * How fast spin fades in the air, per second.
     */
    static final double AIR_DRAG = 0.12;

    private static final Vector3d UP = new Vector3d(0, 1, 0);

    @Override
    public void launch(@Nonnull Spin spin, @Nonnull Vector3dc velocity, @Nonnull RandomGenerator rng) {
        DieOrientation.uniform(rng, spin.orientation);
        Vector3d axis = new Vector3d(UP).cross(velocity);
        if (axis.lengthSquared() < 1.0E-8) {
            randomUnit(rng, axis);
        } else {
            axis.normalize();
        }
        Vector3d wobble = randomUnit(rng, new Vector3d()).mul(WOBBLE);
        axis.add(wobble).normalize();
        double rate = Math.min(MAX_SPIN, SPIN_BASE + SPIN_PER_SPEED * velocity.length());
        spin.angularVelocity.set(axis).mul(rate);
    }

    @Override
    public void tick(@Nonnull Spin spin, double dt) {
        DieMotion.integrate(spin, dt);
        spin.angularVelocity.mul(Math.exp(-AIR_DRAG * dt));
    }

    @Override
    public void bounce(@Nonnull Spin spin, @Nonnull Vector3dc before, @Nonnull Vector3dc after,
                       @Nonnull Vector3dc normal, double radius, @Nonnull RandomGenerator rng) {
        // Rolling without slipping on a surface with normal n at speed v along it turns at (n x v) / r.
        Vector3d along = new Vector3d(after).fma(-after.dot(normal), normal);
        Vector3d rolling = new Vector3d(normal).cross(along).div(Math.max(radius, 1.0E-3));
        spin.angularVelocity.lerp(rolling, GRIP);

        double struck = Math.abs(before.dot(normal));
        Vector3d knock = randomUnit(rng, new Vector3d()).mul(KNOCK * struck);
        spin.angularVelocity.add(knock);

        double rate = spin.angularVelocity.length();
        if (rate > MAX_SPIN) {
            spin.angularVelocity.mul(MAX_SPIN / rate);
        }
    }

    @Nonnull
    @Override
    public DieShape.Face result(@Nonnull Spin spin, @Nonnull DieShape shape, @Nonnull RandomGenerator rng) {
        return DieOrientation.topFace(spin.orientation, shape);
    }

    @Override
    public boolean topples() {
        return true;
    }

    /**
     * A direction drawn evenly over the sphere.
     */
    @Nonnull
    static Vector3d randomUnit(@Nonnull RandomGenerator rng, @Nonnull Vector3d dest) {
        double z = 2.0 * rng.nextDouble() - 1.0;
        double a = 2.0 * Math.PI * rng.nextDouble();
        double r = Math.sqrt(1.0 - z * z);
        return dest.set(r * Math.cos(a), r * Math.sin(a), z);
    }
}
