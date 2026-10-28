package me.ladypaladra.thearmorymod.dice;

import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Throws a die headless through {@link RigidFlight}, the class the game flies it with, onto worlds built for the test.
 */
final class RigidRoll {

    static final double DT = 1.0 / 30.0;

    /**
     * How one throw ended.
     */
    record Outcome(RigidFlight.Status status, DieShape.Face face, int ticks, int nudges, double floorY,
                   double travelled, int bounces, Vector3d middle) {
    }

    private RigidRoll() {
    }

    /**
     * Throws {@code shape} once from 1.5 blocks up with a random hold, look and run, as {@code DiceFlight} does.
     */
    static Outcome throwOnce(@Nonnull DieShape shape, @Nonnull DieHull hull, @Nonnull DieWorld world,
                             @Nonnull RandomGenerator rng) {
        double power = ThrowFeel.power(rng.nextDouble() * ThrowFeel.FULL_CHARGE_SECONDS * 1.2);
        double yaw = rng.nextDouble() * 2.0 * Math.PI;
        double pitch = Math.toRadians(-40.0 + 70.0 * rng.nextDouble());
        Vector3d look = new Vector3d(-Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch));
        Vector3d run = new Vector3d((rng.nextDouble() - 0.5) * 8.0, 0.0, (rng.nextDouble() - 0.5) * 8.0);
        Vector3d velocity = ThrowFeel.launchVelocity(power, look, run, rng.nextInt(4) == 0, rng.nextInt(4) == 0,
                rng.nextInt(6) == 0, rng);
        return fly(shape, hull, world, new Vector3d(0.0, 1.5, 0.0), velocity, null, rng);
    }

    /**
     * Flies one die from {@code start} with {@code velocity}. A null {@code orientation} draws the game's own start.
     */
    static Outcome fly(@Nonnull DieShape shape, @Nonnull DieHull hull, @Nonnull DieWorld world, @Nonnull Vector3d start,
                       @Nonnull Vector3d velocity, DieMotion.Spin orientation, @Nonnull RandomGenerator rng) {
        DieMotion.Spin spin = orientation;
        if (spin == null) {
            spin = new DieMotion.Spin();
            new TumbleMotion().launch(spin, velocity, rng);
        }
        RigidFlight flight = new RigidFlight(shape, hull, spin, start, velocity);
        Vector3d from = new Vector3d(flight.body.position);
        int bounces = 0;
        RigidFlight.Status status;
        do {
            status = flight.tick(world, DT, rng);
            if (flight.report.hardest > DiePhysics.BOUNCE_THRESHOLD) {
                bounces++;
            }
        } while (status == RigidFlight.Status.FLYING);
        double travelled = Math.hypot(flight.body.position.x - from.x, flight.body.position.z - from.z);
        DieShape.Face face = status == RigidFlight.Status.LANDED ? flight.face() : null;
        return new Outcome(status, face, flight.ticks, flight.nudges, flight.floorY(), travelled, bounces,
                flight.modelPosition(new Vector3d()));
    }

    /**
     * Flat ground: the top of the world's floor at y = 0, as wide as any throw can go.
     */
    static DieWorld floor() {
        return boxes(List.of(new double[]{-1000, -4, -1000, 1000, 0, 1000}));
    }

    /**
     * A world of the given boxes, each as its low corner then its high corner, with no water.
     */
    static DieWorld boxes(@Nonnull List<double[]> list) {
        return new Fixed(list, Double.NaN);
    }

    /**
     * Flat ground at y = 0 under water up to {@code surface}.
     */
    static DieWorld pool(double surface) {
        return new Fixed(List.of(new double[]{-1000, -4, -1000, 1000, 0, 1000}), surface);
    }

    /**
     * A staircase of full blocks climbing towards -Z, one block per step, solid down to the ground as built terrain
     * is, on flat ground.
     */
    static DieWorld stairs(int steps) {
        List<double[]> list = new ArrayList<>();
        list.add(new double[]{-1000, -4, -1000, 1000, 0, 1000});
        for (int i = 1; i <= steps; i++) {
            for (int y = 0; y < i; y++) {
                for (int x = -4; x < 4; x++) {
                    list.add(new double[]{x, y, -i, x + 1, y + 1, -i + 1});
                }
            }
        }
        return boxes(list);
    }

    private record Fixed(List<double[]> list, double surface) implements DieWorld {
        @Override
        public void solids(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                           @Nonnull Boxes out) {
            for (double[] b : list) {
                if (b[3] > minX && b[4] > minY && b[5] > minZ && b[0] < maxX && b[1] < maxY && b[2] < maxZ) {
                    out.add(b[0], b[1], b[2], b[3], b[4], b[5]);
                }
            }
        }

        @Override
        public double fluidTop(int x, int y, int z) {
            if (Double.isNaN(surface) || y < 0 || y >= Math.ceil(surface)) {
                return Double.NaN;
            }
            return Math.min(surface, y + 1.0);
        }
    }
}
