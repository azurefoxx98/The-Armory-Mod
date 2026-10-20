package me.ladypaladra.thearmorymod.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatAdditionTest {

    private static final float DELTA = 0.00001F;

    @Test
    void anUnmodifiedStatCarriesNothing() {
        assertEquals(0.0F, StatAddition.fromMaxima(0.0F, 0.0F), DELTA);
    }

    @Test
    void onePumpkinHelmCarriesItsNightMana() {
        assertEquals(50.0F, StatAddition.fromMaxima(0.0F, 50.0F), DELTA);
    }

    @Test
    void theVoidCloakAndHoodCombineTheirNightHealth() {
        assertEquals(20.0F, StatAddition.fromMaxima(0.0F, 20.0F), DELTA);
    }

    @Test
    void aPenaltyIsKept() {
        assertEquals(-10.0F, StatAddition.fromMaxima(0.0F, -10.0F), DELTA);
    }

    @Test
    void anUnreadableMaximumCarriesNothing() {
        assertEquals(0.0F, StatAddition.fromMaxima(0.0F, Float.POSITIVE_INFINITY), DELTA);
        assertEquals(0.0F, StatAddition.fromMaxima(0.0F, Float.NaN), DELTA);
    }

    @Test
    void aBaseOtherThanZeroIsSubtracted() {
        assertEquals(5.0F, StatAddition.fromMaxima(10.0F, 15.0F), DELTA);
    }
}
