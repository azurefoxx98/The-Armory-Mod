package me.ladypaladra.thearmorymod.powers;

import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NightBonusSystemTest {

    private static final int SUNSET = WorldTimeResource.SUNRISE_SECONDS + WorldTimeResource.DAYTIME_SECONDS;

    @Test
    void theEngineDaySpansFourFortySevenFiftyNineToNineteenElevenFiftyNine() {
        // The split the bonus follows, written out so a change to the engine's constants is seen.
        // The engine works the 60/40 split out in float, which truncates each boundary one second
        // short of 04:48 and 19:12.
        assertEquals(4 * 3600 + 48 * 60 - 1, WorldTimeResource.SUNRISE_SECONDS);
        assertEquals(19 * 3600 + 12 * 60 - 1, SUNSET);
    }

    @Test
    void midnightAndTheHourBeforeSunriseAreNight() {
        assertTrue(NightBonusSystem.isNightSecond(0));
        assertTrue(NightBonusSystem.isNightSecond(WorldTimeResource.SUNRISE_SECONDS - 1));
    }

    @Test
    void sunriseToTheLastSecondBeforeSunsetIsDay() {
        assertFalse(NightBonusSystem.isNightSecond(WorldTimeResource.SUNRISE_SECONDS));
        assertFalse(NightBonusSystem.isNightSecond(12 * 3600));
        assertFalse(NightBonusSystem.isNightSecond(SUNSET - 1));
    }

    @Test
    void sunsetToTheEndOfTheDayIsNight() {
        assertTrue(NightBonusSystem.isNightSecond(SUNSET));
        assertTrue(NightBonusSystem.isNightSecond(WorldTimeResource.SECONDS_PER_DAY - 1));
    }

    @Test
    void aWorldWithoutAClockGivesNoBonus() {
        assertFalse(NightBonusSystem.isNight(null));
    }
}
