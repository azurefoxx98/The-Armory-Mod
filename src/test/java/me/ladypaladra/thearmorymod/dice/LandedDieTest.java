package me.ladypaladra.thearmorymod.dice;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandedDieTest {

    @Test
    void aDieCanBeTakenOnceOnly() {
        // Two players pressing F in the same tick both reach the pickup before the die is removed: the claim is what
        // lets only the first one have it.
        LandedDie die = die();
        assertFalse(die.claimed());
        assertTrue(die.claim());
        assertFalse(die.claim());
        assertTrue(die.claimed());
    }

    @Test
    void aCopyKeepsTheClaimAndTheRestingPlace() {
        LandedDie die = die();
        die.claim();
        LandedDie copy = (LandedDie) die.clone();
        assertTrue(copy.claimed());
        assertFalse(copy.claim());
        assertEquals(64.0, copy.restY());
        assertEquals(1.25, copy.restYaw());
    }

    @Test
    void aDieSavedWithoutARestingPlaceSaysSo() {
        LandedDie old = new LandedDie();
        assertTrue(Double.isNaN(old.restY()));
        assertTrue(Double.isNaN(old.restYaw()));
    }

    private static LandedDie die() {
        return new LandedDie("Armory_Dice_D20", "D20", 3, UUID.randomUUID(), null, 64.0, 1.25);
    }
}
