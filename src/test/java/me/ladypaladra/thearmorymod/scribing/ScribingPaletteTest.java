package me.ladypaladra.thearmorymod.scribing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScribingPaletteTest {

    /**
     * The text colours of the eleven qualities in the shipped archive plus the engine's own
     * Default, which is registered in code and has no JSON file. Read out of the 0.5.9
     * archive rather than recalled. This is the control: on an unmodified server the guard
     * must take nothing away, and every other test here is only meaningful if this one holds.
     */
    private static final List<String> SHIPPED_QUALITY_COLOURS = List.of(
            "#c9d2dd",
            "#ce1624",
            "#bb2f2c",
            "#8b339e",
            "#c9d2dd",
            "#bb8a2c",
            "#2770b7",
            "#3b7a8f",
            "#ce1624",
            "#269edc",
            "#3e9049",
            "#c9d2dd"
    );

    // The palette is process-wide state resolved once at boot, so a test that leaves a colour
    // withdrawn would change what a later test in this JVM sees. Reset on both sides rather
    // than trusting execution order.
    @BeforeEach
    @AfterEach
    void clearResolution() {
        ScribingPalette.resolveAgainstQualities(List.of(), List.of());
    }

    @Test
    void anUnmodifiedServerKeepsEveryColour() {
        List<String> authored = ScribingPalette.allowed();

        List<String> withdrawn = ScribingPalette.resolveAgainstQualities(
                SHIPPED_QUALITY_COLOURS,
                List.of(ScribingWrite.SEAL_MARK_COLOR)
        );

        assertEquals(List.of(), withdrawn);
        assertEquals(authored, ScribingPalette.allowed());
        assertFalse(ScribingPalette.isWithdrawn(ScribingWrite.SEAL_MARK_COLOR));
    }

    /**
     * The defect this whole change exists for. A quality that happens to use one of our
     * colours used to throw out of start and take the mod offline. It must now cost exactly
     * one swatch.
     */
    @Test
    void aCollidingQualityWithdrawsOnlyThatSwatch() {
        int authoredCount = ScribingPalette.allowed().size();

        List<String> withdrawn = ScribingPalette.resolveAgainstQualities(
                List.of("#ffffff"),
                List.of()
        );

        assertEquals(List.of("#ffffff"), withdrawn);
        assertTrue(ScribingPalette.isWithdrawn("#ffffff"));
        assertFalse(ScribingPalette.isAllowed("#ffffff"));
        assertFalse(ScribingPalette.allowed().contains("#ffffff"));

        assertEquals(authoredCount - 1, ScribingPalette.allowed().size());
        assertTrue(ScribingPalette.isAllowed("#E8A93B"));
    }

    /**
     * The comparison that a case-sensitive implementation would get wrong. Our authored list
     * spells the gold as {@code #E8A93B}, while a quality colour is rebuilt from the engine's
     * own bytes with {@code %02x} and therefore always arrives lower case. Matching exactly
     * would let the collision through and put the impersonation back.
     */
    @Test
    void collisionIgnoresTheCaseTheEngineEmits() {
        List<String> withdrawn = ScribingPalette.resolveAgainstQualities(
                List.of("#e8a93b"),
                List.of()
        );

        assertEquals(List.of("#E8A93B"), withdrawn);
        assertFalse(ScribingPalette.isAllowed("#E8A93B"));
        assertFalse(ScribingPalette.isAllowed("#e8a93b"));
    }

    /**
     * The seal marker is not on the legend, so it cannot be withdrawn from it. It still has
     * to be reported, because the drawing site reads that answer to decide whether to fall
     * back to an uncoloured line.
     */
    @Test
    void theSealColourIsFlaggedWithoutShrinkingThePalette() {
        int authoredCount = ScribingPalette.allowed().size();

        List<String> withdrawn = ScribingPalette.resolveAgainstQualities(
                List.of(ScribingWrite.SEAL_MARK_COLOR),
                List.of(ScribingWrite.SEAL_MARK_COLOR)
        );

        assertEquals(List.of(ScribingWrite.SEAL_MARK_COLOR), withdrawn);
        assertTrue(ScribingPalette.isWithdrawn(ScribingWrite.SEAL_MARK_COLOR));
        assertEquals(authoredCount, ScribingPalette.allowed().size());
    }

    /**
     * The worst case a server can produce still has to boot. Losing every colour leaves the
     * Scribing Table usable for plain text, which is a far smaller loss than the mod refusing
     * to start.
     */
    @Test
    void aServerThatCollidesWithEverythingStillResolves() {
        List<String> authored = ScribingPalette.allowed();

        List<String> withdrawn = ScribingPalette.resolveAgainstQualities(authored, List.of());

        assertEquals(authored, withdrawn);
        assertEquals(List.of(), ScribingPalette.allowed());
        for (String colour : authored) {
            assertFalse(ScribingPalette.isAllowed(colour), colour);
        }
    }

    @Test
    void resolvingAgainAfterACollisionRestoresTheColour() {
        ScribingPalette.resolveAgainstQualities(List.of("#ffffff"), List.of());
        assertFalse(ScribingPalette.isAllowed("#ffffff"));

        ScribingPalette.resolveAgainstQualities(SHIPPED_QUALITY_COLOURS, List.of());

        assertTrue(ScribingPalette.isAllowed("#ffffff"));
    }

    /**
     * The two refusals a player can hit have to read differently. The legend is markup fixed
     * at build time while a withdrawal is decided at boot, so a withdrawn swatch is still
     * painted on screen. Reusing the "not one of the colours you can use" sentence would tell
     * the player something the list in front of them contradicts.
     */
    @Test
    void aWithdrawnColourIsRefusedWithItsOwnSentence() {
        String markup = "<color is=\"#ffffff\">x</color>";
        assertTrue(ScribingMarkup.parse(markup).ok());

        ScribingPalette.resolveAgainstQualities(List.of("#ffffff"), List.of());

        ScribingMarkup.Parsed refused = ScribingMarkup.parse(markup);
        assertFalse(refused.ok());
        assertEquals(1, refused.problems().size());
        assertEquals(ScribingMarkup.Kind.COLOUR_NOT_ALLOWED, refused.problems().getFirst().kind());
        assertTrue(
                refused.problems().getFirst().message().contains("item rarity"),
                refused.problems().getFirst().message()
        );

        ScribingMarkup.Parsed neverOffered = ScribingMarkup.parse("<color is=\"#123456\">x</color>");
        assertFalse(neverOffered.ok());
        assertTrue(
                neverOffered.problems().getFirst().message().contains("not one of the colours"),
                neverOffered.problems().getFirst().message()
        );
    }

    @Test
    void arbitraryHexIsStillRefusedWhetherOrNotAnythingCollided() {
        assertFalse(ScribingPalette.isAllowed("#123456"));

        ScribingPalette.resolveAgainstQualities(List.of("#ffffff"), List.of());

        assertFalse(ScribingPalette.isAllowed("#123456"));
    }
}
