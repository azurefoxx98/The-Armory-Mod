package me.ladypaladra.thearmorymod.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * These tests treat the privacy boundary as one unit.
 *
 * <p>{@code safeToken} is the only function that decides what may leave this mod in a crash
 * report. Everything else in the telemetry path is plumbing. A leak here would put a
 * player's writing on a third party's servers, which is more serious than an embarrassing
 * plumbing mistake. The Scribing Table exists so people can type text, and that text is
 * the mod's core feature that this filter must stop.</p>
 *
 * <p>The function is package private, rather than private, only so this test can reach it.
 * That is a deliberate trade, and the document half of the seal makes the same one.</p>
 */
class ArmoryTelemetrySafeTokenTest {

    @Test
    void keepsOurOwnEventTypeConstants() {
        assertEquals("Seal", ArmoryTelemetry.safeToken("Seal"));
        assertEquals("SealConfirm", ArmoryTelemetry.safeToken("SealConfirm"));
        assertEquals("scribing", ArmoryTelemetry.safeToken("scribing"));
        assertEquals("WithdrawKit", ArmoryTelemetry.safeToken("WithdrawKit"));
        assertEquals("select_input_2", ArmoryTelemetry.safeToken("select_input_2"));
    }

    @Test
    void rejectsAnythingCarryingWhitespaceOrPunctuation() {
        // Each of these could plausibly appear in a name or description box on the Scribing
        // Table. That is exactly why the filter uses a whitelist.
        assertEquals("unknown", ArmoryTelemetry.safeToken("Sword of the Fallen"));
        assertEquals("unknown", ArmoryTelemetry.safeToken("Larsonix's blade"));
        assertEquals("unknown", ArmoryTelemetry.safeToken("hello, world"));
        assertEquals("unknown", ArmoryTelemetry.safeToken("line one\nline two"));
        assertEquals("unknown", ArmoryTelemetry.safeToken("tab\there"));
    }

    @Test
    void rejectsColourMarkupAndTranslationKeys() {
        // Player text on this bench becomes markup once the palette is used. An item's own
        // description appears as a translation key.
        assertEquals("unknown", ArmoryTelemetry.safeToken("#39f4f4"));
        assertEquals("unknown", ArmoryTelemetry.safeToken("server.items.Sword_Adamantite_Blue.description"));
        assertEquals("unknown", ArmoryTelemetry.safeToken("<color=#ff0000>red</color>"));
    }

    @Test
    void rejectsEmptyNullAndOverlongValues() {
        assertEquals("unknown", ArmoryTelemetry.safeToken(null));
        assertEquals("unknown", ArmoryTelemetry.safeToken(""));

        // The cap is 40. A token exactly at the limit survives, while one character past it
        // does not. A value long enough to matter is carrying something rather than naming
        // something.
        String atLimit = "a".repeat(40);
        String pastLimit = "a".repeat(41);
        assertEquals(atLimit, ArmoryTelemetry.safeToken(atLimit));
        assertEquals("unknown", ArmoryTelemetry.safeToken(pastLimit));
    }

    @Test
    void rejectsNonLatinText() {
        // The mod ships to servers in every locale, and the description box accepts every
        // character the client can produce. None of that text is an identifier.
        assertEquals("unknown", ArmoryTelemetry.safeToken("épée"));
        assertEquals("unknown", ArmoryTelemetry.safeToken("剣"));
        assertEquals("unknown", ArmoryTelemetry.safeToken("меч"));
    }
}
