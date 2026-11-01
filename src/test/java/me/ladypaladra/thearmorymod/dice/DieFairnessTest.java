package me.ladypaladra.thearmorymod.dice;

import org.joml.Quaterniond;
import org.joml.Vector3dc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every die, thrown through the real motion and the real shape data, shows every face equally often, and the test can
 * tell when one does not.
 *
 * <p>Sizes were set from a measured throw: about 1.4 microseconds each on the build machine, so 300,000 throws per die
 * cost well under a second. At that size a face favoured by one percent fails by a wide margin, and so does the usual
 * mistake of drawing the starting turn as three random angles. A fair die must not fail: the bar is a p-value of 0.001,
 * and the random draws are seeded, so the verdict does not change from run to run.</p>
 */
class DieFairnessTest {

    private static final int THROWS = 300_000;
    private static final double BAR = 0.001;

    private static DiceCatalog catalog;

    @BeforeAll
    static void load() throws Exception {
        catalog = DiceCatalog.load();
    }

    @Test
    void everyTumblingDieIsFair() {
        for (DieShape shape : catalog.shapes().values()) {
            double p = ChiSquare.pEven(counts(new TumbleMotion(), shape, THROWS, 11));
            assertTrue(p > BAR, shape.key() + " tumbles unfairly, p = " + p);
        }
    }

    @Test
    void everyFlatSpinningDieIsFair() {
        for (DieShape shape : catalog.shapes().values()) {
            double p = ChiSquare.pEven(counts(new FlatSpinMotion(), shape, THROWS / 3, 12));
            assertTrue(p > BAR, shape.key() + " spins unfairly, p = " + p);
        }
    }

    @Test
    void aDieWithOneFaceWeightedFails() {
        // One throw in a hundred forced onto the first face: a 1 that comes up 5.95 percent of the time, not 5.
        DieShape d20 = catalog.shape("D20");
        double p = ChiSquare.pEven(counts(new Loaded(new TumbleMotion(), 0.01), d20, THROWS, 13));
        assertTrue(p < BAR, "a weighted D20 passed, p = " + p);
    }

    @Test
    void aStartingTurnDrawnAsThreeAnglesFails() {
        for (String key : new String[]{"D6", "D8", "D20"}) {
            double p = ChiSquare.pEven(counts(new EulerStart(), catalog.shape(key), THROWS, 14));
            assertTrue(p < BAR, "a " + key + " started from three random angles passed, p = " + p);
        }
    }

    @Test
    void theFlightReallyBounces() {
        // A flight that ends at the first touch would make the fairness above say nothing about bounces.
        DieShape d6 = catalog.shape("D6");
        Counting motion = new Counting(new TumbleMotion());
        SplittableRandom rng = new SplittableRandom(15);
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            DiceFlight.throwOnce(motion, d6, rng);
        }
        double bounces = (double) motion.bounces / n;
        double ticks = (double) motion.ticks / n;
        assertTrue(bounces > 1.5, "only " + bounces + " bounces a throw");
        assertTrue(ticks > 15 && ticks < 300, ticks + " ticks a throw");
    }

    @Test
    void theChiSquareMatchesItsTable() {
        // 43.82 is the 0.001 critical value for 19 degrees of freedom, 16.27 for 3.
        assertTrue(Math.abs(ChiSquare.upperGamma(19 / 2.0, 43.82 / 2.0) - 0.001) < 2.0E-5);
        assertTrue(Math.abs(ChiSquare.upperGamma(3 / 2.0, 16.27 / 2.0) - 0.001) < 2.0E-5);
        assertTrue(Math.abs(ChiSquare.upperGamma(9 / 2.0, 3.325 / 2.0) - 0.95) < 2.0E-4);
    }

    private static long[] counts(DieMotion motion, DieShape shape, int throwsOf, long seed) {
        long[] counts = new long[shape.faces().size()];
        SplittableRandom rng = new SplittableRandom(seed);
        for (int i = 0; i < throwsOf; i++) {
            counts[DiceFlight.throwOnce(motion, shape, rng).index()]++;
        }
        return counts;
    }

    /**
     * A planted fault: some throws land on the first face whatever the die did.
     */
    private record Loaded(DieMotion inner, double weight) implements DieMotion {
        @Override
        public void launch(@Nonnull Spin spin, @Nonnull Vector3dc velocity, @Nonnull RandomGenerator rng) {
            inner.launch(spin, velocity, rng);
        }

        @Override
        public void tick(@Nonnull Spin spin, double dt) {
            inner.tick(spin, dt);
        }

        @Override
        public void bounce(@Nonnull Spin spin, @Nonnull Vector3dc before, @Nonnull Vector3dc after,
                           @Nonnull Vector3dc normal, double radius, @Nonnull RandomGenerator rng) {
            inner.bounce(spin, before, after, normal, radius, rng);
        }

        @Nonnull
        @Override
        public DieShape.Face result(@Nonnull Spin spin, @Nonnull DieShape shape, @Nonnull RandomGenerator rng) {
            DieShape.Face fair = inner.result(spin, shape, rng);
            return rng.nextDouble() < weight ? shape.faces().get(0) : fair;
        }

        @Override
        public boolean topples() {
            return inner.topples();
        }
    }

    /**
     * A planted fault: the tumble, but started from yaw, pitch and roll each drawn evenly, which is not an even turn.
     */
    private static final class EulerStart implements DieMotion {
        private final TumbleMotion inner = new TumbleMotion();

        @Override
        public void launch(@Nonnull Spin spin, @Nonnull Vector3dc velocity, @Nonnull RandomGenerator rng) {
            inner.launch(spin, velocity, rng);
            double tau = 2.0 * Math.PI;
            spin.orientation.set(new Quaterniond().rotationYXZ(rng.nextDouble() * tau, rng.nextDouble() * tau,
                    rng.nextDouble() * tau));
        }

        @Override
        public void tick(@Nonnull Spin spin, double dt) {
            inner.tick(spin, dt);
        }

        @Override
        public void bounce(@Nonnull Spin spin, @Nonnull Vector3dc before, @Nonnull Vector3dc after,
                           @Nonnull Vector3dc normal, double radius, @Nonnull RandomGenerator rng) {
            inner.bounce(spin, before, after, normal, radius, rng);
        }

        @Nonnull
        @Override
        public DieShape.Face result(@Nonnull Spin spin, @Nonnull DieShape shape, @Nonnull RandomGenerator rng) {
            return inner.result(spin, shape, rng);
        }

        @Override
        public boolean topples() {
            return true;
        }
    }

    private static final class Counting implements DieMotion {
        private final DieMotion inner;
        long bounces;
        long ticks;

        Counting(DieMotion inner) {
            this.inner = inner;
        }

        @Override
        public void launch(@Nonnull Spin spin, @Nonnull Vector3dc velocity, @Nonnull RandomGenerator rng) {
            inner.launch(spin, velocity, rng);
        }

        @Override
        public void tick(@Nonnull Spin spin, double dt) {
            ticks++;
            inner.tick(spin, dt);
        }

        @Override
        public void bounce(@Nonnull Spin spin, @Nonnull Vector3dc before, @Nonnull Vector3dc after,
                           @Nonnull Vector3dc normal, double radius, @Nonnull RandomGenerator rng) {
            bounces++;
            inner.bounce(spin, before, after, normal, radius, rng);
        }

        @Nonnull
        @Override
        public DieShape.Face result(@Nonnull Spin spin, @Nonnull DieShape shape, @Nonnull RandomGenerator rng) {
            return inner.result(spin, shape, rng);
        }

        @Override
        public boolean topples() {
            return inner.topples();
        }
    }
}
