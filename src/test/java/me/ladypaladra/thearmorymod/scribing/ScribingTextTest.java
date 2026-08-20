package me.ladypaladra.thearmorymod.scribing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScribingTextTest {

    @Test
    void fourHundredOpenTagsHitTheRawCapBeforeParsing() {
        ScribingText.Result.Fail fail = assertInstanceOf(
                ScribingText.Result.Fail.class,
                ScribingText.validateName("<b>".repeat(400), ScribingText.Limits.forName(false))
        );

        assertEquals(1, fail.problems().size());
        assertEquals(ScribingMarkup.Kind.RAW_TOO_LONG, fail.problems().getFirst().kind());
    }

    @Test
    void c0ControlsDisappearFromRenderedText() {
        assertRendered("a\u0000\u0001\u001Fb", "ab");
    }

    @Test
    void deleteDisappearsFromRenderedText() {
        assertRendered("a\u007Fb", "ab");
    }

    @Test
    void unicodeLineSeparatorsDisappearFromRenderedText() {
        assertRendered("a\u2028\u2029b", "ab");
    }

    /**
     * The engine's own rule for player text rejects the C1 block alongside C0 and 0x7F.
     * We mirror it, so these must not survive into an item every other player will see.
     */
    @Test
    void c1ControlsDisappearFromRenderedText() {
        assertRendered("a\u0080\u008a\u009fb", "ab");
    }

    @Test
    void zeroWidthAndBidiControlsDisappearFromRenderedText() {
        assertRendered(
                "a\u200B\u200C\u200D\u200E\u200F\u202A\u202B\u202C\u202D\u202E"
                        + "\u2066\u2067\u2068\u2069b",
                "ab"
        );
    }

    @Test
    void nameNewlineBecomesOneSpace() {
        ScribingText.Result.Ok ok = ok(
                ScribingText.validateName("first\nsecond", ScribingText.Limits.forName(false))
        );

        assertEquals("first second", joined(ok.runs()));
    }

    @Test
    void whitespaceOnlyNameIsEmpty() {
        ScribingText.Result.Ok ok = ok(
                ScribingText.validateName("   \r\n  ", ScribingText.Limits.forName(false))
        );

        assertTrue(ok.empty());
        assertTrue(ok.runs().isEmpty());
    }

    @Test
    void tagsWithoutTextAreEmpty() {
        ScribingText.Result.Ok ok = ok(
                ScribingText.validateName("<b></b>", ScribingText.Limits.forName(false))
        );

        assertTrue(ok.empty());
    }

    @Test
    void nameLengthMessageUsesActualAndConfiguredNumbers() {
        ScribingText.Result.Fail fail = failed(ScribingText.validateName(
                "n".repeat(61),
                new ScribingText.Limits(200, 48, Integer.MAX_VALUE, Integer.MAX_VALUE)
        ));

        assertEquals(
                "That name is 61 characters. The limit is 48. Tags do not count.",
                fail.problems().getFirst().message()
        );
    }

    @Test
    void descriptionLengthMessageUsesActualAndConfiguredNumbers() {
        ScribingText.Result.Fail fail = failed(ScribingText.validateDescription(
                "d".repeat(340),
                new ScribingText.Limits(1200, 300, 20, 400)
        ));

        assertEquals(
                "That description is 340 characters. The limit is 300. Tags do not count.",
                fail.problems().getFirst().message()
        );
    }

    @Test
    void descriptionLineCountMessageUsesActualAndConfiguredNumbers() {
        String raw = String.join("\n", List.of("1", "2", "3", "4", "5", "6", "7", "8"));
        ScribingText.Result.Fail fail = failed(ScribingText.validateDescription(
                raw,
                new ScribingText.Limits(1200, 300, 6, 60)
        ));

        assertEquals(
                "That description is 8 lines. The limit is 6.",
                fail.problems().getFirst().message()
        );
    }

    @Test
    void descriptionLineLengthMessageUsesLineAndActualNumbers() {
        String raw = "short\nshort\n" + "x".repeat(74);
        ScribingText.Result.Fail fail = failed(ScribingText.validateDescription(
                raw,
                new ScribingText.Limits(1200, 300, 6, 60)
        ));

        assertEquals(
                "Line 3 is 74 characters. The limit is 60.",
                fail.problems().getFirst().message()
        );
    }

    @Test
    void unlimitedLimitsAcceptFiveThousandCharacterName() {
        ScribingText.Result.Ok ok = ok(ScribingText.validateName(
                "x".repeat(5000),
                ScribingText.Limits.unlimited()
        ));

        assertEquals(5000, ok.stats().renderedChars());
    }

    private static void assertRendered(String raw, String expected) {
        ScribingText.Result.Ok ok = ok(
                ScribingText.validateDescription(raw, ScribingText.Limits.unlimited())
        );
        assertEquals(expected, joined(ok.runs()));
        assertEquals(expected.codePointCount(0, expected.length()), ok.stats().renderedChars());
    }

    private static ScribingText.Result.Ok ok(ScribingText.Result result) {
        return assertInstanceOf(ScribingText.Result.Ok.class, result);
    }

    private static ScribingText.Result.Fail failed(ScribingText.Result result) {
        return assertInstanceOf(ScribingText.Result.Fail.class, result);
    }

    private static String joined(List<ScribingMarkup.StyledRun> runs) {
        return runs.stream().map(ScribingMarkup.StyledRun::text).reduce("", String::concat);
    }
}
