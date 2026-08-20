package me.ladypaladra.thearmorymod.scribing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScribingMarkupTest {

    /**
     * The property that keeps a second visit to the bench from destroying an inscription.
     * What is stored is a run list, what the player edits is markup, so the two conversions
     * have to be exact inverses or reopening an item quietly rewrites it.
     */
    @Test
    void markupRoundTripsThroughRunsUnchanged() {
        String[] sources = {
                "<color is=\"#E8A93B\">Dawn</color>breaker",
                "<i><color is=\"#4ade80\">both</color></i>",
                "plain text with no tags at all",
                "HP < 50 and <x>unknown</x> stay literal",
                "<b>bold</b> then <i>italic</i> then plain",
                "line one\nline two\nline three"
        };

        for (String source : sources) {
            ScribingMarkup.Parsed first = ScribingMarkup.parse(source);
            assertTrue(first.ok(), source);

            String rebuilt = ScribingMarkup.toMarkup(first.runs());
            ScribingMarkup.Parsed second = ScribingMarkup.parse(rebuilt);

            assertTrue(second.ok(), rebuilt);
            assertEquals(first.runs(), second.runs(), source);
            assertEquals(first.stats(), second.stats(), source);
        }
    }

    @Test
    void toMarkupRebuildsTheOriginalCharactersForASingleColouredRun() {
        ScribingMarkup.Parsed parsed = ScribingMarkup.parse("<color is=\"#E8A93B\">Dawn</color>breaker");

        assertEquals("<color is=\"#E8A93B\">Dawn</color>breaker", ScribingMarkup.toMarkup(parsed.runs()));
    }

    @Test
    void goldPrefixProducesTwoRunsAndElevenRenderedCharacters() {
        ScribingMarkup.Parsed parsed = ScribingMarkup.parse(
                "<color is=\"#E8A93B\">Dawn</color>breaker"
        );

        assertTrue(parsed.ok());
        assertEquals(2, parsed.runs().size());
        assertEquals("Dawn", parsed.runs().get(0).text());
        assertEquals("#E8A93B", parsed.runs().get(0).color());
        assertEquals("breaker", parsed.runs().get(1).text());
        assertNull(parsed.runs().get(1).color());
        assertEquals(11, parsed.stats().renderedChars());
    }

    @Test
    void nestedItalicAndColourFlattenToOneRun() {
        ScribingMarkup.Parsed parsed = ScribingMarkup.parse(
                "<i><color is=\"#4ade80\">both</color></i>"
        );

        assertTrue(parsed.ok());
        assertEquals(1, parsed.runs().size());
        assertTrue(parsed.runs().getFirst().italic());
        assertEquals("#4ade80", parsed.runs().getFirst().color());
    }

    @Test
    void lessThanComparisonStaysLiteral() {
        ScribingMarkup.Parsed parsed = ScribingMarkup.parse("HP < 50");

        assertTrue(parsed.ok());
        assertEquals(1, parsed.runs().size());
        assertEquals("HP < 50", parsed.runs().getFirst().text());
        assertEquals(7, parsed.stats().renderedChars());
    }

    @Test
    void unknownTagsStayLiteral() {
        ScribingMarkup.Parsed parsed = ScribingMarkup.parse("<x>hello</x>");

        assertTrue(parsed.ok());
        assertEquals("<x>hello</x>", parsed.runs().getFirst().text());
    }

    @Test
    void unclosedBoldReportsTheTag() {
        ScribingMarkup.Parsed parsed = ScribingMarkup.parse("<b>oops");

        assertFalse(parsed.ok());
        assertEquals(1, parsed.problems().size());
        assertEquals(ScribingMarkup.Kind.TAG_UNCLOSED, parsed.problems().getFirst().kind());
        assertTrue(parsed.problems().getFirst().message().contains("<b>"));
    }

    @Test
    void loneColourCloseIsStray() {
        ScribingMarkup.Parsed parsed = ScribingMarkup.parse("</color>");

        assertEquals(1, parsed.problems().size());
        assertEquals(ScribingMarkup.Kind.TAG_STRAY_CLOSE, parsed.problems().getFirst().kind());
    }

    @Test
    void unlistedColourIsRefused() {
        ScribingMarkup.Parsed parsed = ScribingMarkup.parse(
                "<color is=\"#123456\">x</color>"
        );

        assertEquals(1, parsed.problems().size());
        assertEquals(ScribingMarkup.Kind.COLOUR_NOT_ALLOWED, parsed.problems().getFirst().kind());
        assertTrue(parsed.problems().getFirst().message().contains("#123456"));
    }

    @Test
    void malformedColourIsRefused() {
        ScribingMarkup.Parsed parsed = ScribingMarkup.parse("<color is=E8A93B>x</color>");

        assertEquals(1, parsed.problems().size());
        assertEquals(ScribingMarkup.Kind.COLOUR_MALFORMED, parsed.problems().getFirst().kind());
    }

    @Test
    void emptyBoldParsesToZeroRenderedCharacters() {
        ScribingMarkup.Parsed parsed = ScribingMarkup.parse("<b></b>");

        assertTrue(parsed.ok());
        assertTrue(parsed.runs().isEmpty());
        assertEquals(0, parsed.stats().renderedChars());
    }

    @Test
    void fortyNestedTagsStillProduceOneRun() {
        String raw = "<i>".repeat(40) + "word" + "</i>".repeat(40);
        ScribingMarkup.Parsed parsed = ScribingMarkup.parse(raw);

        assertTrue(parsed.ok());
        assertEquals(1, parsed.runs().size());
        assertTrue(parsed.runs().getFirst().italic());
    }

    @Test
    void lowercasePaletteHexIsAccepted() {
        ScribingMarkup.Parsed parsed = ScribingMarkup.parse(
                "<color is=\"#e8a93b\">gold</color>"
        );

        assertTrue(parsed.ok());
        assertEquals("#e8a93b", parsed.runs().getFirst().color());
    }

    @Test
    void threeLineTextReportsThreeLines() {
        ScribingMarkup.Parsed parsed = ScribingMarkup.parse("one\ntwo\nthree");

        assertEquals(3, parsed.stats().lineCount());
    }

    @Test
    void longestLineIgnoresTags() {
        ScribingMarkup.Parsed parsed = ScribingMarkup.parse("a\n<b>longest</b>\nmid");

        assertEquals(7, parsed.stats().longestLineChars());
    }
}
