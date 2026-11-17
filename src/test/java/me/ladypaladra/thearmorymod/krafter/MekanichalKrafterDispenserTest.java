package me.ladypaladra.thearmorymod.krafter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The explosive krafter's clock: a try every minute, a try that finds its top blocked waits a whole
 * minute more, and a dispense finishes when the Dispense clip has played.
 */
class MekanichalKrafterDispenserTest {

    private static final int TICK = 50;

    @Test
    void theFirstTryComesAfterExactlyOneMinute() {
        MekanichalKrafterDispenser dispenser = new MekanichalKrafterDispenser();

        int elapsed = 0;
        MekanichalKrafterDispenser.Step step = MekanichalKrafterDispenser.Step.NONE;
        while (step == MekanichalKrafterDispenser.Step.NONE) {
            step = dispenser.advance(TICK);
            elapsed += TICK;
        }

        assertEquals(MekanichalKrafterDispenser.Step.DUE, step);
        assertEquals(MekanichalKrafterDispenser.INTERVAL_MILLIS, elapsed);
    }

    @Test
    void aBlockedTopWaitsAWholeMinuteMore() {
        MekanichalKrafterDispenser dispenser = new MekanichalKrafterDispenser();
        assertEquals(MekanichalKrafterDispenser.Step.DUE, dispenser.advance(MekanichalKrafterDispenser.INTERVAL_MILLIS));

        // The top was not free, so no dispense began.
        assertFalse(dispenser.isDispensing());
        assertEquals(MekanichalKrafterDispenser.Step.NONE, dispenser.advance(MekanichalKrafterDispenser.INTERVAL_MILLIS - 1));
        assertEquals(MekanichalKrafterDispenser.Step.DUE, dispenser.advance(1));
    }

    @Test
    void aDispenseFinishesWhenTheClipHasPlayedAndTheNextMinuteStartsAfterIt() {
        MekanichalKrafterDispenser dispenser = new MekanichalKrafterDispenser();
        dispenser.advance(MekanichalKrafterDispenser.INTERVAL_MILLIS);
        dispenser.beginDispense();
        assertTrue(dispenser.isDispensing());

        assertEquals(MekanichalKrafterDispenser.Step.NONE, dispenser.advance(MekanichalKrafterDispenser.DISPENSE_MILLIS - 1));
        assertEquals(MekanichalKrafterDispenser.Step.FINISHED, dispenser.advance(1));
        assertFalse(dispenser.isDispensing());

        // The clock does not run during the clip, so the next try is a full minute after the barrel.
        assertEquals(MekanichalKrafterDispenser.INTERVAL_MILLIS, dispenser.getRemainingMillis());
    }

    @Test
    void aSavedClockComesBackWhereItStopped() {
        MekanichalKrafterDispenser dispenser = new MekanichalKrafterDispenser();
        dispenser.advance(20_000);

        MekanichalKrafterDispenser copy = (MekanichalKrafterDispenser) dispenser.clone();
        assertEquals(40_000, copy.getRemainingMillis());
        assertEquals(0, copy.getDispenseMillis());
    }
}
