package me.ladypaladra.thearmorymod.scribing;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the small player-facing formatting grammar without touching engine types.
 */
public final class ScribingMarkup {

    public static final String UNCLOSED_BOLD = "You opened <b> but never closed it. Add </b>.";
    public static final String UNCLOSED_ITALIC = "You opened <i> but never closed it. Add </i>.";
    public static final String UNCLOSED_COLOUR = "You opened <color> but never closed it. Add </color>.";
    public static final String STRAY_BOLD = "There is a </b> with no <b> before it.";
    public static final String STRAY_ITALIC = "There is a </i> with no <i> before it.";
    public static final String STRAY_COLOUR = "There is a </color> with no <color...> before it.";
    public static final String COLOUR_NOT_ALLOWED = "%s is not one of the colours you can use. Pick one from the list.";
    public static final String COLOUR_MALFORMED = "A colour looks like <color is=\"#E8A93B\">. Check the quotes and the #.";

    private static final String COLOR_PREFIX = "<color";

    private ScribingMarkup() {
    }

    public enum Kind {
        TAG_UNCLOSED,
        TAG_STRAY_CLOSE,
        COLOUR_NOT_ALLOWED,
        COLOUR_MALFORMED,
        RAW_TOO_LONG,
        RENDERED_TOO_LONG,
        TOO_MANY_LINES,
        LINE_TOO_LONG
    }

    public record StyledRun(@Nonnull String text, boolean bold, boolean italic, @Nullable String color) {
    }

    public record Stats(int renderedChars, int lineCount, int longestLineChars) {
    }

    public record Problem(@Nonnull Kind kind, @Nonnull String message, int offset) {
    }

    public record Parsed(
            @Nonnull List<StyledRun> runs,
            @Nonnull Stats stats,
            @Nonnull List<Problem> problems
    ) {
        public Parsed {
            runs = List.copyOf(runs);
            problems = List.copyOf(problems);
        }

        public boolean ok() {
            return problems.isEmpty();
        }
    }

    private enum Tag {
        BOLD("b"),
        ITALIC("i"),
        COLOR("color");

        private final String name;

        Tag(@Nonnull String name) {
            this.name = name;
        }
    }

    private record OpenTag(@Nonnull Tag tag, int offset, @Nullable String color) {
    }

    /**
     * Flattens every text stretch to one styled run. This retires span and nesting
     * amplification as an abuse vector instead of merely mitigating it, since tree
     * depth stays one and run count cannot exceed the number of text stretches.
     */
    @Nonnull
    public static Parsed parse(@Nonnull String raw) {
        List<StyledRun> runs = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        List<OpenTag> stack = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int index = 0;

        while (index < raw.length()) {
            if (raw.charAt(index) != '<') {
                int codePoint = raw.codePointAt(index);
                text.appendCodePoint(codePoint);
                index += Character.charCount(codePoint);
                continue;
            }

            Token token = tokenAt(raw, index);
            if (token == null) {
                text.append('<');
                index++;
                continue;
            }

            flush(text, stack, runs);
            if (token.close()) {
                close(token.tag(), index, stack, problems);
            } else {
                if (token.problem() != null) {
                    problems.add(token.problem());
                }
                stack.add(new OpenTag(token.tag(), index, token.color()));
            }
            index += token.length();
        }

        flush(text, stack, runs);
        for (OpenTag open : stack) {
            problems.add(new Problem(Kind.TAG_UNCLOSED, unclosedMessage(open.tag()), open.offset()));
        }

        // Tags occupy no pixels. Counting raw markup would punish formatting and make
        // the screen counter disagree with the tooltip, so all stats use rendered text.
        Stats stats = measure(runs);
        if (!problems.isEmpty()) {
            return new Parsed(List.of(), stats, problems);
        }
        return new Parsed(runs, stats, List.of());
    }

    @Nullable
    private static Token tokenAt(@Nonnull String raw, int offset) {
        if (raw.startsWith("<b>", offset)) {
            return Token.open(Tag.BOLD, 3, null, null);
        }
        if (raw.startsWith("<i>", offset)) {
            return Token.open(Tag.ITALIC, 3, null, null);
        }
        if (raw.startsWith("</b>", offset)) {
            return Token.close(Tag.BOLD, 4);
        }
        if (raw.startsWith("</i>", offset)) {
            return Token.close(Tag.ITALIC, 4);
        }
        if (raw.startsWith("</color>", offset)) {
            return Token.close(Tag.COLOR, 8);
        }

        if (!raw.startsWith(COLOR_PREFIX, offset)) {
            return null;
        }
        int afterPrefix = offset + COLOR_PREFIX.length();
        if (afterPrefix >= raw.length()
                || raw.charAt(afterPrefix) != ' ' && raw.charAt(afterPrefix) != '>') {
            return null;
        }
        int end = raw.indexOf('>', afterPrefix);
        if (end < 0) {
            return null;
        }

        String whole = raw.substring(offset, end + 1);
        if (whole.length() == 20
                && whole.startsWith("<color is=\"#")
                && whole.endsWith("\">")
                && isSixHexDigits(whole, 12)) {
            String color = whole.substring(11, 18);
            Problem problem = ScribingPalette.isAllowed(color)
                    ? null
                    : new Problem(
                            Kind.COLOUR_NOT_ALLOWED,
                            COLOUR_NOT_ALLOWED.formatted(color),
                            offset
                    );
            return Token.open(Tag.COLOR, whole.length(), color, problem);
        }

        return Token.open(
                Tag.COLOR,
                whole.length(),
                null,
                new Problem(Kind.COLOUR_MALFORMED, COLOUR_MALFORMED, offset)
        );
    }

    private static boolean isSixHexDigits(@Nonnull String text, int start) {
        if (start + 6 > text.length()) {
            return false;
        }
        for (int index = start; index < start + 6; index++) {
            char value = text.charAt(index);
            boolean hex = value >= '0' && value <= '9'
                    || value >= 'a' && value <= 'f'
                    || value >= 'A' && value <= 'F';
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static void close(
            @Nonnull Tag tag,
            int offset,
            @Nonnull List<OpenTag> stack,
            @Nonnull List<Problem> problems
    ) {
        for (int index = stack.size() - 1; index >= 0; index--) {
            if (stack.get(index).tag() == tag) {
                // Shipped text permits nesting, so an out-of-order close removes the
                // nearest matching tag and leaves intervening tags open. Any genuine
                // mismatch is then reported precisely as an unclosed tag at the end.
                stack.remove(index);
                return;
            }
        }
        problems.add(new Problem(Kind.TAG_STRAY_CLOSE, strayMessage(tag), offset));
    }

    private static void flush(
            @Nonnull StringBuilder text,
            @Nonnull List<OpenTag> stack,
            @Nonnull List<StyledRun> runs
    ) {
        if (text.isEmpty()) {
            return;
        }

        boolean bold = false;
        boolean italic = false;
        String color = null;
        for (OpenTag open : stack) {
            switch (open.tag()) {
                case BOLD -> bold = true;
                case ITALIC -> italic = true;
                case COLOR -> color = open.color();
            }
        }
        runs.add(new StyledRun(text.toString(), bold, italic, color));
        text.setLength(0);
    }

    /**
     * The inverse of {@link #parse}, and the reason it has to exist: what we store is a
     * Message tree, but what the player edits is markup. Reopening the bench on an item
     * inscribed as {@code <color is="#E8A93B">Dawn</color>breaker} has to put those exact
     * characters back in the box, or the second visit silently drops every run after the
     * first along with all of its formatting.
     *
     * <p>Round tripping is exact rather than approximate. A run's text can never contain a
     * sequence {@link #parse} would read as a tag, because parse would have consumed it as
     * one instead of emitting it as text, so re-parsing this output reproduces the same
     * runs. That is what {@code ScribingMarkupTest} asserts.</p>
     */
    @Nonnull
    public static String toMarkup(@Nonnull List<StyledRun> runs) {
        StringBuilder markup = new StringBuilder();
        for (StyledRun run : runs) {
            if (run.color() != null) {
                markup.append("<color is=\"").append(run.color()).append("\">");
            }
            if (run.bold()) {
                markup.append("<b>");
            }
            if (run.italic()) {
                markup.append("<i>");
            }
            markup.append(run.text());
            if (run.italic()) {
                markup.append("</i>");
            }
            if (run.bold()) {
                markup.append("</b>");
            }
            if (run.color() != null) {
                markup.append("</color>");
            }
        }
        return markup.toString();
    }

    /**
     * Package visible so {@link ScribingText} measures filtered runs with this exact
     * routine rather than a second copy of it. Two implementations of one counting rule
     * drift the first time the rule changes and nothing catches it.
     */
    @Nonnull
    static Stats measure(@Nonnull List<StyledRun> runs) {
        int rendered = 0;
        int lines = 1;
        int currentLine = 0;
        int longest = 0;
        for (StyledRun run : runs) {
            for (int offset = 0; offset < run.text().length();) {
                int codePoint = run.text().codePointAt(offset);
                offset += Character.charCount(codePoint);
                rendered++;
                if (codePoint == '\n') {
                    lines++;
                    longest = Math.max(longest, currentLine);
                    currentLine = 0;
                } else {
                    currentLine++;
                }
            }
        }
        return new Stats(rendered, lines, Math.max(longest, currentLine));
    }

    @Nonnull
    private static String unclosedMessage(@Nonnull Tag tag) {
        return switch (tag) {
            case BOLD -> UNCLOSED_BOLD;
            case ITALIC -> UNCLOSED_ITALIC;
            case COLOR -> UNCLOSED_COLOUR;
        };
    }

    @Nonnull
    private static String strayMessage(@Nonnull Tag tag) {
        return switch (tag) {
            case BOLD -> STRAY_BOLD;
            case ITALIC -> STRAY_ITALIC;
            case COLOR -> STRAY_COLOUR;
        };
    }

    private record Token(
            @Nonnull Tag tag,
            boolean close,
            int length,
            @Nullable String color,
            @Nullable Problem problem
    ) {
        @Nonnull
        private static Token open(
                @Nonnull Tag tag,
                int length,
                @Nullable String color,
                @Nullable Problem problem
        ) {
            return new Token(tag, false, length, color, problem);
        }

        @Nonnull
        private static Token close(@Nonnull Tag tag, int length) {
            return new Token(tag, true, length, null, null);
        }
    }
}
