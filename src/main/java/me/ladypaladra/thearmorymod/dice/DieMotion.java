package me.ladypaladra.thearmorymod.dice;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nonnull;
import java.util.random.RandomGenerator;

/**
 * How a die turns while the engine flies it. The engine moves the die and bounces it off blocks, and turns a
 * projectile on two axes only, so the turn is ours.
 *
 * <p>Two motions share this one shape. The free tumble turns the die on every axis and reads the face that ends on
 * top. The flat spin turns it end over end only, for a client that does not draw an entity's roll, and has the server
 * draw the result evenly instead. Swapping one for the other changes nothing else.</p>
 */
public interface DieMotion {

    /**
     * Sets the die's starting turn and spin as it leaves the hand.
     *
     * @param velocity the die's launch velocity, blocks per second
     */
    void launch(@Nonnull Spin spin, @Nonnull Vector3dc velocity, @Nonnull RandomGenerator rng);

    /**
     * Turns the die through one step of flight.
     */
    void tick(@Nonnull Spin spin, double dt);

    /**
     * Changes the spin at a bounce, from the velocity just before and just after it.
     *
     * @param normal the contact's normal, pointing away from the block
     * @param radius the die's radius in blocks
     */
    void bounce(@Nonnull Spin spin, @Nonnull Vector3dc before, @Nonnull Vector3dc after, @Nonnull Vector3dc normal,
                double radius, @Nonnull RandomGenerator rng);

    /**
     * The face the die shows once it stops.
     */
    @Nonnull
    DieShape.Face result(@Nonnull Spin spin, @Nonnull DieShape shape, @Nonnull RandomGenerator rng);

    /**
     * Whether this motion turns the die on all three axes, so a settling die may visibly topple onto its face. The
     * flat spin cannot show a topple without roll, so its die lies down at once.
     */
    boolean topples();

    /**
     * A die's turn in flight and how fast it turns. Spin is in world axes, radians per second.
     */
    final class Spin {
        public final Quaterniond orientation = new Quaterniond();
        public final Vector3d angularVelocity = new Vector3d();
    }

    /**
     * Turns {@code orientation} by {@code angularVelocity} held for {@code dt}, about world axes.
     */
    static void integrate(@Nonnull Spin spin, double dt) {
        double rate = spin.angularVelocity.length();
        double angle = rate * dt;
        if (angle < 1.0E-12) {
            return;
        }
        Quaterniond step = new Quaterniond().fromAxisAngleRad(
                spin.angularVelocity.x / rate, spin.angularVelocity.y / rate, spin.angularVelocity.z / rate, angle);
        spin.orientation.premul(step).normalize();
    }
}
