package me.ladypaladra.thearmorymod.dice;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FidgetClockTest {

    private static final String DIE = "Armory_Dice_D6";
    private static final List<FidgetClock.Fidget> FIDGETS = List.of(
            new FidgetClock.Fidget("Fidget_Hop", 2.2), new FidgetClock.Fidget("Fidget_Read", 2.5),
            new FidgetClock.Fidget("Fidget_Spin", 2.6), new FidgetClock.Fidget("Fidget_Toss", 2.0));
    private static final long TICK = 33_333_333L;      // a server tick, a thirtieth of a second

    /**
     * Plays the clock for {@code seconds} of ticks under one condition, from {@code start}, and returns every act with
     * its time in seconds since the clock began.
     */
    private static List<double[]> run(FidgetClock clock, SplittableRandom rng, long start, double seconds,
                                      String item, List<FidgetClock.Fidget> fidgets, boolean still, boolean free,
                                      List<String> played) {
        List<double[]> acts = new ArrayList<>();
        for (long t = start; t < start + (long) (seconds * 1e9); t += TICK) {
            FidgetClock.Act act = clock.tick(t, item, fidgets, still, free, rng);
            if (act != FidgetClock.Act.NONE) {
                acts.add(new double[]{t / 1e9, act.ordinal()});
                if (act == FidgetClock.Act.PLAY) {
                    played.add(clock.chosen());
                }
            }
        }
        return acts;
    }

    @Test
    void aStillHandWaitsTwelveToTwentyFourSecondsForItsFirstFidget() {
        for (int seed = 0; seed < 200; seed++) {
            FidgetClock clock = new FidgetClock();
            List<String> played = new ArrayList<>();
            List<double[]> acts = run(clock, new SplittableRandom(seed), 0, 30, DIE, FIDGETS, true, true, played);
            double first = acts.get(0)[0];
            assertTrue(first >= FidgetClock.MIN_WAIT && first <= FidgetClock.MAX_WAIT + 0.05, "first at " + first);
            assertEquals(FidgetClock.Act.PLAY.ordinal(), (int) acts.get(0)[1]);
        }
    }

    @Test
    void fidgetsFollowEachOtherWithTheWaitBetweenAndNeverTheSameTwice() {
        FidgetClock clock = new FidgetClock();
        List<String> played = new ArrayList<>();
        List<double[]> acts = run(clock, new SplittableRandom(7), 0, 3600, DIE, FIDGETS, true, true, played);
        assertTrue(played.size() > 100, played.size() + " fidgets in an hour");
        for (int i = 1; i < played.size(); i++) {
            assertNotEquals(played.get(i - 1), played.get(i), "the same fidget twice at " + i);
            double gap = acts.get(i)[0] - acts.get(i - 1)[0];
            assertTrue(gap >= FidgetClock.MIN_WAIT + 2.0, "only " + gap + " s between two");
        }
        for (FidgetClock.Fidget f : FIDGETS) {
            assertTrue(played.contains(f.name()), f.name() + " never played");
        }
    }

    @Test
    void movingRestartsTheWaitSoNoFidgetComesAsAPlayerStops() {
        for (int seed = 0; seed < 100; seed++) {
            FidgetClock clock = new FidgetClock();
            SplittableRandom rng = new SplittableRandom(seed);
            List<String> played = new ArrayList<>();
            run(clock, rng, 0, 10, DIE, FIDGETS, true, true, played);
            run(clock, rng, 10_000_000_000L, 20, DIE, FIDGETS, false, true, played);
            List<double[]> acts = run(clock, rng, 30_000_000_000L, 30, DIE, FIDGETS, true, true, played);
            assertTrue(acts.get(0)[0] - 30 >= FidgetClock.MIN_WAIT, "a fidget " + (acts.get(0)[0] - 30)
                    + " s after stopping");
        }
    }

    @Test
    void walkingOffStopsAFidgetAtOnce() {
        FidgetClock clock = new FidgetClock();
        SplittableRandom rng = new SplittableRandom(3);
        List<String> played = new ArrayList<>();
        List<double[]> acts = run(clock, rng, 0, 25, DIE, FIDGETS, true, true, played);
        double started = acts.get(0)[0];
        long move = (long) ((started + 0.5) * 1e9);
        assertEquals(FidgetClock.Act.STOP, clock.tick(move, DIE, FIDGETS, false, true, rng));
        assertEquals(FidgetClock.Act.NONE, clock.tick(move + TICK, DIE, FIDGETS, false, true, rng));
    }

    @Test
    void switchingItemStopsAFidgetAndAnItemWithoutFidgetsNeverPlays() {
        FidgetClock clock = new FidgetClock();
        SplittableRandom rng = new SplittableRandom(4);
        List<String> played = new ArrayList<>();
        List<double[]> acts = run(clock, rng, 0, 25, DIE, FIDGETS, true, true, played);
        long next = (long) ((acts.get(0)[0] + 0.5) * 1e9);
        assertEquals(FidgetClock.Act.STOP, clock.tick(next, "Weapon_Sword_Iron", List.of(), true, true, rng));
        List<String> none = new ArrayList<>();
        assertTrue(run(clock, rng, next + TICK, 120, "Weapon_Sword_Iron", List.of(), true, true, none).isEmpty());
    }

    @Test
    void aThrowStartingMidFidgetIsNeverCutByAStop() {
        FidgetClock clock = new FidgetClock();
        SplittableRandom rng = new SplittableRandom(5);
        List<String> played = new ArrayList<>();
        List<double[]> acts = run(clock, rng, 0, 25, DIE, FIDGETS, true, true, played);
        long t = (long) ((acts.get(0)[0] + 0.3) * 1e9);
        // The throw is held, then the player walks off while the throw's animation still plays.
        assertEquals(FidgetClock.Act.NONE, clock.tick(t, DIE, FIDGETS, true, false, rng));
        assertEquals(FidgetClock.Act.NONE, clock.tick(t + TICK, DIE, FIDGETS, false, false, rng));
        assertEquals(FidgetClock.Act.NONE, clock.tick(t + 2 * TICK, DIE, FIDGETS, false, true, rng));
    }

    @Test
    void theShowcasePlaysEveryFidgetInTurnASecondApart() {
        FidgetClock clock = new FidgetClock();
        clock.showcase(true);
        List<String> played = new ArrayList<>();
        List<double[]> acts = run(clock, new SplittableRandom(8), 0, 20, DIE, FIDGETS, true, true, played);
        assertTrue(played.size() >= FIDGETS.size(), played.toString());
        for (int i = 0; i < FIDGETS.size(); i++) {
            assertEquals(FIDGETS.get(i).name(), played.get(i));
        }
        // The tick a die first appears in the hand counts as the item changing, so the wait starts one tick later.
        assertTrue(acts.get(0)[0] <= FidgetClock.SHOWCASE_WAIT + TICK / 1e9 + 0.05, "first at " + acts.get(0)[0]);
        for (int i = 1; i < acts.size(); i++) {
            double gap = acts.get(i)[0] - acts.get(i - 1)[0] - FIDGETS.get((i - 1) % FIDGETS.size()).seconds();
            assertTrue(Math.abs(gap - FidgetClock.SHOWCASE_WAIT) < 0.05, "a gap of " + gap + " s after the clip");
        }
        clock.showcase(false);
        List<double[]> calm = run(clock, new SplittableRandom(8), 20_000_000_000L, 10, DIE, FIDGETS, true, true,
                new ArrayList<>());
        assertTrue(calm.isEmpty(), "turned off, the next fidget waits the usual 12 s at least");
    }

    @Test
    void aSetWithOneFidgetPlaysItEveryTime() {
        FidgetClock clock = new FidgetClock();
        List<FidgetClock.Fidget> one = List.of(new FidgetClock.Fidget("Fidget_Toss", 2.0));
        List<String> played = new ArrayList<>();
        run(clock, new SplittableRandom(6), 0, 300, DIE, one, true, true, played);
        assertFalse(played.isEmpty());
        assertTrue(played.stream().allMatch("Fidget_Toss"::equals));
    }
}
