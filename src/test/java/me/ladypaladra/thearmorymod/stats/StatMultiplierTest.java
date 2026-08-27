package me.ladypaladra.thearmorymod.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatMultiplierTest {

    private static final float DELTA = 0.00001F;

    @Test
    void equalMaximaAreUnmodified() {
        assertEquals(1.0F, StatMultiplier.fromMaxima(10.0F, 10.0F), DELTA);
    }

    @Test
    void maximaWithinTheEqualityToleranceAreUnmodified() {
        float effectiveMax = 10.0F + StatMultiplier.EPSILON / 2.0F;

        assertEquals(1.0F, StatMultiplier.fromMaxima(10.0F, effectiveMax), DELTA);
    }

    @Test
    void oneArmourPieceAddsItsAuthoredBonus() {
        assertEquals(1.15F, StatMultiplier.fromMaxima(10.0F, 1.5F), DELTA);
    }

    @Test
    void fourWardenPiecesCombineTheirAuthoredBonuses() {
        assertEquals(1.60F, StatMultiplier.fromMaxima(10.0F, 6.0F), DELTA);
    }

    @Test
    void engineeringRigAddsItsAuthoredBonus() {
        assertEquals(1.20F, StatMultiplier.fromMaxima(10.0F, 2.0F), DELTA);
    }

    @Test
    void zeroBaseMaximumIsUnmodified() {
        assertEquals(1.0F, StatMultiplier.fromMaxima(0.0F, 2.0F), DELTA);
    }

    @Test
    void negativeBaseMaximumIsUnmodified() {
        assertEquals(1.0F, StatMultiplier.fromMaxima(-10.0F, 2.0F), DELTA);
    }

    @Test
    void debuffSurvivesWithoutBeingClampedAway() {
        assertEquals(0.7F, StatMultiplier.fromMaxima(10.0F, -3.0F), DELTA);
    }

    @Test
    void overLargeDebuffStopsAtZeroInsteadOfInverting() {
        assertEquals(0.0F, StatMultiplier.fromMaxima(10.0F, -15.0F), DELTA);
    }

    @Test
    void exactlyOneMultiplicativeSumIsIndistinguishableFromNoModifier() {
        assertEquals(1.0F, StatMultiplier.fromMaxima(10.0F, 10.0F), DELTA);
    }

    @Test
    void anInfiniteEffectiveMaximumIsUnmodifiedRatherThanInfinite() {
        // An amount too large for a float parses to infinity instead of being rejected, and
        // this value is written straight into live jump force and sprint speed.
        assertEquals(1.0F, StatMultiplier.fromMaxima(10.0F, Float.POSITIVE_INFINITY), DELTA);
        assertEquals(1.0F, StatMultiplier.fromMaxima(10.0F, Float.NEGATIVE_INFINITY), DELTA);
    }

    @Test
    void anUndefinedEffectiveMaximumIsUnmodifiedRatherThanUndefined() {
        assertEquals(1.0F, StatMultiplier.fromMaxima(10.0F, Float.NaN), DELTA);
    }
}
