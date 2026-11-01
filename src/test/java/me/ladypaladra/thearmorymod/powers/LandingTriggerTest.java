package me.ladypaladra.thearmorymod.powers;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandingTriggerTest {

    private static LandingTrigger.Settings read(Map<String, String[]> tags, List<String> problems) {
        return LandingTrigger.Settings.read(tags, problems::add);
    }

    @Test
    void anEffectWithNoSettingsTakesTheDefaults() {
        List<String> problems = new ArrayList<>();
        assertEquals(LandingTrigger.Settings.DEFAULT, read(Map.of(), problems));
        assertEquals(LandingTrigger.Settings.DEFAULT, read(null, problems));
        assertTrue(problems.isEmpty());
    }

    @Test
    void settingsAreReadFromTheRootTags() {
        List<String> problems = new ArrayList<>();
        LandingTrigger.Settings settings = read(Map.of(
                LandingTrigger.MIN_FALL_TAG, new String[]{"7.5"},
                LandingTrigger.PIECES_TAG, new String[]{" 4 "},
                LandingTrigger.RADIUS_TAG, new String[]{"6"},
                LandingTrigger.HIT_TAG, new String[]{" Hit "},
                LandingTrigger.RECHARGE_TAG, new String[]{"2.5"}
        ), problems);

        assertEquals(new LandingTrigger.Settings(7.5, 4, LandingTrigger.DEFAULT_SCALE_PER_BLOCK, LandingTrigger.DEFAULT_MAX_SCALE, 6.0, "Hit", 2.5), settings);
        assertTrue(problems.isEmpty());
    }

    @Test
    void aValueThatIsNotANumberFallsBackAndIsReported() {
        List<String> problems = new ArrayList<>();
        LandingTrigger.Settings settings = read(Map.of(
                LandingTrigger.MIN_FALL_TAG, new String[]{"five"},
                LandingTrigger.PIECES_TAG, new String[]{"2.5"}
        ), problems);

        assertEquals(LandingTrigger.Settings.DEFAULT, settings);
        assertEquals(2, problems.size(), problems.toString());
    }

    @Test
    void impossibleValuesFallBackAndAreReported() {
        for (String[] bad : List.of(
                new String[]{LandingTrigger.MIN_FALL_TAG, "-1"},
                new String[]{LandingTrigger.MIN_FALL_TAG, "NaN"},
                new String[]{LandingTrigger.MIN_FALL_TAG, "Infinity"},
                new String[]{LandingTrigger.PIECES_TAG, "0"},
                new String[]{LandingTrigger.PIECES_TAG, ""},
                new String[]{LandingTrigger.SCALE_PER_BLOCK_TAG, "-0.5"},
                new String[]{LandingTrigger.MAX_SCALE_TAG, "0.5"},
                new String[]{LandingTrigger.RADIUS_TAG, "0"},
                new String[]{LandingTrigger.RECHARGE_TAG, "-1"}
        )) {
            List<String> problems = new ArrayList<>();
            assertEquals(LandingTrigger.Settings.DEFAULT, read(Map.of(bad[0], new String[]{bad[1]}), problems), Arrays.toString(bad));
            assertEquals(1, problems.size(), Arrays.toString(bad));
        }
    }

    @Test
    void aTagWithNoValueCountsAsUnset() {
        List<String> problems = new ArrayList<>();
        assertEquals(LandingTrigger.Settings.DEFAULT, read(Map.of(LandingTrigger.MIN_FALL_TAG, new String[0]), problems));
        assertTrue(problems.isEmpty());
    }

    @Test
    void theShortestFallFiresAndAnythingShorterDoesNot() {
        LandingTrigger.Settings settings = new LandingTrigger.Settings(5.0, 1, 0.1, 3.0, 4.0, null, 0.0);
        assertTrue(settings.firesFor(1, 5.0));
        assertTrue(settings.firesFor(1, 40.0));
        assertFalse(settings.firesFor(1, 4.999));
        assertFalse(settings.firesFor(1, 0.0));
    }

    @Test
    void theDefaultIsAboveAStandingJump() {
        // A standing jump comes down from about 2.3 blocks: jump force 11.8 against the fall speed
        // measured by HyPilot, 19.2 blocks a second after a 6-block fall.
        double gravity = 19.2 * 19.2 / (2 * 6.0);
        double jumpApex = 11.8 * 11.8 / (2 * gravity);
        assertFalse(LandingTrigger.Settings.DEFAULT.firesFor(4, jumpApex));
    }

    @Test
    void theFallScaleIsOneAtTheShortestFallAndGrowsPerBlockUpToItsCap() {
        LandingTrigger.Settings settings = new LandingTrigger.Settings(7.0, 4, 0.1, 3.0, 4.0, null, 0.0);
        assertEquals(1.0f, settings.scaleFor(7.0), 1e-6);
        assertEquals(1.5f, settings.scaleFor(12.0), 1e-6);
        assertEquals(3.0f, settings.scaleFor(27.0), 1e-6);
        assertEquals(3.0f, settings.scaleFor(200.0), 1e-6);
        assertEquals(1.0f, settings.scaleFor(3.0), 1e-6);
    }

    @Test
    void aScaleOfZeroPerBlockKeepsTheDamageFlat() {
        assertEquals(1.0f, new LandingTrigger.Settings(7.0, 1, 0.0, 3.0, 4.0, null, 0.0).scaleFor(50.0), 1e-6);
    }

    @Test
    void aRechargeHoldsTheEffectBackUntilItsSecondsHavePassed() {
        LandingTrigger.Settings settings = new LandingTrigger.Settings(7.0, 1, 0.1, 3.0, 4.0, null, 2.5);
        assertTrue(settings.recharged(null, 10_000L));
        assertFalse(settings.recharged(10_000L, 12_499L));
        assertTrue(settings.recharged(10_000L, 12_500L));
        assertTrue(LandingTrigger.Settings.DEFAULT.recharged(10_000L, 10_000L));
    }

    @Test
    void aSetNeedsEveryPieceItAsksFor() {
        LandingTrigger.Settings fullSet = new LandingTrigger.Settings(5.0, 4, 0.1, 3.0, 4.0, null, 0.0);
        assertFalse(fullSet.firesFor(3, 20.0));
        assertTrue(fullSet.firesFor(4, 20.0));
    }

    @Test
    void piecesOfDifferentItemsNamingOneEffectCountTogether() {
        Map<String, Integer> counts = LandingTrigger.countPieces(List.of(
                new String[]{"Praetorian"},
                new String[]{"Praetorian"},
                new String[]{"Praetorian", "Other"},
                new String[]{"Elite"}
        ));

        assertEquals(Map.of("Praetorian", 3, "Other", 1, "Elite", 1), counts);
        assertEquals(List.of("Praetorian", "Other", "Elite"), List.copyOf(counts.keySet()));
    }

    @Test
    void aPieceNamingAnEffectTwiceCountsOnce() {
        assertEquals(Map.of("Praetorian", 1), LandingTrigger.countPieces(List.<String[]>of(new String[]{"Praetorian", " Praetorian "})));
    }

    @Test
    void blankAndMissingValuesNameNothing() {
        List<String[]> worn = new ArrayList<>();
        worn.add(null);
        worn.add(new String[0]);
        worn.add(new String[]{"", "  ", null});
        assertTrue(LandingTrigger.countPieces(worn).isEmpty());
    }

    @Test
    void onlyEffectsThatExistAndQualifyFire() {
        Map<String, Integer> counts = LandingTrigger.countPieces(List.of(
                new String[]{"Set"}, new String[]{"Set"}, new String[]{"Set"}, new String[]{"Set"},
                new String[]{"Missing"}
        ));
        LandingTrigger.Settings set = new LandingTrigger.Settings(5.0, 4, 0.1, 3.0, 4.0, null, 0.0);
        Map<String, LandingTrigger.Settings> known = Map.of("Set", set);

        assertEquals(Map.of("Set", set), LandingTrigger.firing(counts, known::get, 6.0));
        assertEquals(Map.of(), LandingTrigger.firing(counts, known::get, 4.0));
    }
}
