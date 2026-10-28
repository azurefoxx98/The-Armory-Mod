package me.ladypaladra.thearmorymod.dice;

import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every die, thrown through the true physics onto flat ground, shows every face equally often, never lands cocked, and
 * the test can tell a loaded die from a fair one.
 *
 * <p>Sizes come from a measured throw: 0.2 to 0.5 milliseconds each, so 8,000 throws per die take a few seconds, run
 * die by die on a few threads. At that size a die whose weight sits five hundredths of its inner radius off centre
 * (under a hundredth of a block) fails by a wide margin, measured on the D4, D6, D10 and D20. A fair die must not fail:
 * the bar is a p-value of 0.001, and every draw is seeded, so the verdict never changes between runs.</p>
 *
 * <p>The tumble's planted fault, a starting turn drawn as three random angles, is not used here: measured, the true
 * physics mixes a die so thoroughly over its bounces that even that start comes out fair. The fairness no longer rests
 * on the start alone, and the fault worth catching is a die that is physically loaded.</p>
 */
class DieRigidFairnessTest {

    private static final int THROWS = 8_000;
    private static final double BAR = 0.001;
    private static final double LOAD = 0.05;

    private static DiceCatalog catalog;

    @BeforeAll
    static void load() throws Exception {
        catalog = DiceCatalog.load();
    }

    @Test
    void everyDieIsFairAndNeverReadCocked() throws Exception {
        List<String> keys = new ArrayList<>(catalog.shapes().keySet());
        List<Tally> tallies = run(keys, key -> catalog.hull(key), 21);
        for (int i = 0; i < keys.size(); i++) {
            Tally tally = tallies.get(i);
            double p = ChiSquare.pEven(tally.counts);
            assertTrue(p > BAR, keys.get(i) + " lands unfairly, p = " + p);
            assertEquals(0, tally.stuck, keys.get(i) + " stayed cocked through every nudge " + tally.stuck + " times");
            assertEquals(THROWS, tally.landed, keys.get(i) + " did not land flat every time");
        }
    }

    @Test
    void aLoadedDieFails() throws Exception {
        List<String> keys = List.of("D4", "D6", "D10", "D20");
        // The weight moved towards the face under the first result, so the first result comes up more often.
        List<Tally> tallies = run(keys, key -> {
            DieShape shape = catalog.shape(key);
            double reach = catalog.hull(key).inradius * LOAD;
            return DieHull.of(shape, new Vector3d(shape.faces().get(0).normal()).mul(-reach));
        }, 22);
        for (int i = 0; i < keys.size(); i++) {
            double p = ChiSquare.pEven(tallies.get(i).counts);
            assertTrue(p < BAR, "a loaded " + keys.get(i) + " passed, p = " + p);
        }
    }

    private interface HullOf {
        DieHull of(String key);
    }

    private static final class Tally {
        long[] counts;
        int landed;
        int stuck;
    }

    private static List<Tally> run(List<String> keys, HullOf hulls, long seed) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(4,
                Runtime.getRuntime().availableProcessors() / 2)));
        try {
            List<Future<Tally>> futures = new ArrayList<>();
            for (int k = 0; k < keys.size(); k++) {
                String key = keys.get(k);
                long dieSeed = seed * 100 + k;
                futures.add(pool.submit(() -> {
                    DieShape shape = catalog.shape(key);
                    DieHull hull = hulls.of(key);
                    DieWorld floor = RigidRoll.floor();
                    SplittableRandom rng = new SplittableRandom(dieSeed);
                    Tally tally = new Tally();
                    tally.counts = new long[shape.faces().size()];
                    for (int i = 0; i < THROWS; i++) {
                        RigidRoll.Outcome o = RigidRoll.throwOnce(shape, hull, floor, rng);
                        if (o.face() != null) {
                            tally.counts[o.face().index()]++;
                            tally.landed++;
                        } else {
                            tally.stuck++;
                        }
                    }
                    return tally;
                }));
            }
            List<Tally> out = new ArrayList<>();
            for (Future<Tally> future : futures) {
                out.add(future.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
