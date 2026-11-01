package me.ladypaladra.thearmorymod.dice;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nonnull;
import java.util.random.RandomGenerator;

/**
 * The flat spin: the fallback for a client that does not draw an entity's roll.
 *
 * <p>The die spins end over end about the level axis across its flight, so its turn only ever holds a yaw and a pitch.
 * Such a die cannot reach every face by turning, so the result is drawn evenly by the server instead, and the die is
 * laid on that face when it stops.</p>
 */
public final class FlatSpinMotion implements DieMotion {

    @Override
    public void launch(@Nonnull Spin spin, @Nonnull Vector3dc velocity, @Nonnull RandomGenerator rng) {
        double heading = Math.atan2(-velocity.x(), -velocity.z());
        spin.orientation.rotationYXZ(heading, rng.nextDouble() * 2.0 * Math.PI, 0.0);
        double rate = Math.min(TumbleMotion.MAX_SPIN,
                TumbleMotion.SPIN_BASE + TumbleMotion.SPIN_PER_SPEED * velocity.length());
        // The level axis across the flight, so the die tumbles forward.
        spin.angularVelocity.set(-Math.cos(heading), 0.0, Math.sin(heading)).mul(rate);
    }

    @Override
    public void tick(@Nonnull Spin spin, double dt) {
        DieMotion.integrate(spin, dt);
        spin.angularVelocity.mul(Math.exp(-TumbleMotion.AIR_DRAG * dt));
    }

    @Override
    public void bounce(@Nonnull Spin spin, @Nonnull Vector3dc before, @Nonnull Vector3dc after,
                       @Nonnull Vector3dc normal, double radius, @Nonnull RandomGenerator rng) {
        // Only the rate changes: the axis stays level, or a roll would creep in.
        double rate = spin.angularVelocity.length();
        if (rate < 1.0E-9) {
            return;
        }
        Vector3d along = new Vector3d(after).fma(-after.dot(normal), normal);
        double rolling = along.length() / Math.max(radius, 1.0E-3);
        double struck = Math.abs(before.dot(normal));
        double next = rate + (rolling - rate) * TumbleMotion.GRIP + TumbleMotion.KNOCK * struck * (rng.nextDouble() - 0.5);
        next = Math.max(0.0, Math.min(TumbleMotion.MAX_SPIN, next));
        spin.angularVelocity.mul(next / rate);
    }

    @Nonnull
    @Override
    public DieShape.Face result(@Nonnull Spin spin, @Nonnull DieShape shape, @Nonnull RandomGenerator rng) {
        return shape.faces().get(rng.nextInt(shape.faces().size()));
    }

    @Override
    public boolean topples() {
        return false;
    }
}
