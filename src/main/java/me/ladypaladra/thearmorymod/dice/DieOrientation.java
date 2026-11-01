package me.ladypaladra.thearmorymod.dice;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.random.RandomGenerator;

/**
 * The turn maths of a die: drawing a fair starting turn, finding the face on top, and the yaw that lays a die on its
 * resting model with the least visible jump.
 *
 * <p>Quaternions follow the engine's own convention: an entity's yaw, pitch and roll compose as
 * {@code rotationYXZ(yaw, pitch, roll)}, yaw 0 faces -Z, and a positive yaw turns about +Y the right-handed way.</p>
 */
public final class DieOrientation {

    private DieOrientation() {
    }

    /**
     * A turn drawn evenly over every possible turn (Shoemake's method).
     *
     * <p>This is what makes a tumbling die fair. Its flight then turns it by an amount that depends only on the throw
     * and the bounces, never on which face points where, and turning an even spread of turns by any fixed turn leaves
     * it even. Drawing three random angles instead would not be even, and the fairness test catches that.</p>
     */
    @Nonnull
    public static Quaterniond uniform(@Nonnull RandomGenerator rng, @Nonnull Quaterniond dest) {
        double u1 = rng.nextDouble();
        double u2 = rng.nextDouble() * 2.0 * Math.PI;
        double u3 = rng.nextDouble() * 2.0 * Math.PI;
        double a = Math.sqrt(1.0 - u1);
        double b = Math.sqrt(u1);
        return dest.set(a * Math.sin(u2), a * Math.cos(u2), b * Math.sin(u3), b * Math.cos(u3));
    }

    /**
     * The face whose direction, turned by {@code orientation}, points most nearly straight up.
     */
    @Nonnull
    public static DieShape.Face topFace(@Nonnull Quaterniondc orientation, @Nonnull DieShape shape) {
        Vector3d turned = new Vector3d();
        DieShape.Face best = shape.faces().get(0);
        double bestUp = Double.NEGATIVE_INFINITY;
        for (DieShape.Face face : shape.faces()) {
            orientation.transform(face.normal(), turned);
            if (turned.y > bestUp) {
                bestUp = turned.y;
                best = face;
            }
        }
        return best;
    }

    /**
     * The yaw that, applied to {@code face}'s resting turn, comes closest to {@code orientation}.
     *
     * <p>A resting model is the die already turned face up, so a landed die only needs a yaw. Of all the yaws, the one
     * taken is the twist about the vertical left over once the face is brought up, which is the one that keeps the
     * die's marks where a viewer last saw them.</p>
     */
    public static double restYaw(@Nonnull Quaterniondc orientation, @Nonnull DieShape.Face face) {
        Quaterniond r = new Quaterniond(orientation).mul(new Quaterniond(face.restTurn()).conjugate());
        if (Math.abs(r.y) < 1.0E-12 && Math.abs(r.w) < 1.0E-12) {
            return 0.0;
        }
        return wrap(2.0 * Math.atan2(r.y, r.w));
    }

    /**
     * The turn of a die lying on {@code face} with {@code yaw}, in the flying model's frame: the end pose of a topple.
     */
    @Nonnull
    public static Quaterniond resting(double yaw, @Nonnull DieShape.Face face, @Nonnull Quaterniond dest) {
        return dest.rotationY(yaw).mul(face.restTurn());
    }

    /**
     * Folds an angle into [-pi, pi).
     */
    public static double wrap(double angle) {
        double a = (angle + Math.PI) % (2.0 * Math.PI);
        if (a < 0) {
            a += 2.0 * Math.PI;
        }
        return a - Math.PI;
    }
}
