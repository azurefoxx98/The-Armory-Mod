package me.ladypaladra.thearmorymod.scribing;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies output-integrity filtering and tooltip-size policy to parsed text.
 */
public final class ScribingText {

    public static final String RAW_TOO_LONG = "That is a lot of tags. Shorten it.";

    private ScribingText() {
    }

    public record Limits(int maxRawChars, int maxRenderedChars, int maxLines, int maxLineChars) {

        @Nonnull
        public static Limits forName(boolean bypass) {
            return bypass
                    ? unlimited()
                    : new Limits(
                            ScribingConfig.NAME_MAX_RAW_CHARS,
                            ScribingConfig.NAME_MAX_CHARS,
                            Integer.MAX_VALUE,
                            Integer.MAX_VALUE
                    );
        }

        @Nonnull
        public static Limits forDescription(boolean bypass) {
            return bypass
                    ? unlimited()
                    : new Limits(
                            ScribingConfig.DESC_MAX_RAW_CHARS,
                            ScribingConfig.DESC_MAX_TOTAL_CHARS,
                            ScribingConfig.DESC_MAX_LINES,
                            ScribingConfig.DESC_MAX_LINE_CHARS
                    );
        }

        @Nonnull
        public static Limits unlimited() {
            return new Limits(
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE
            );
        }
    }

    public sealed interface Result permits Result.Ok, Result.Fail {

        record Ok(
                @Nonnull List<ScribingMarkup.StyledRun> runs,
                @Nonnull ScribingMarkup.Stats stats,
                boolean empty
        ) implements Result {
            public Ok {
                runs = List.copyOf(runs);
            }
        }

        record Fail(@Nonnull List<ScribingMarkup.Problem> problems) implements Result {
            public Fail {
                problems = List.copyOf(problems);
            }
        }
    }

    @Nonnull
    public static Result validateName(@Nonnull String raw, @Nonnull Limits limits) {
        return validate(raw, limits, true);
    }

    @Nonnull
    public static Result validateDescription(@Nonnull String raw, @Nonnull Limits limits) {
        return validate(raw, limits, false);
    }

    @Nonnull
    private static Result validate(@Nonnull String raw, @Nonnull Limits limits, boolean name) {
        int rawChars = raw.length();
        if (rawChars > limits.maxRawChars()) {
            return fail(ScribingMarkup.Kind.RAW_TOO_LONG, RAW_TOO_LONG, 0);
        }

        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        ScribingMarkup.Parsed parsed = ScribingMarkup.parse(normalized);
        if (!parsed.ok()) {
            return new Result.Fail(parsed.problems());
        }

        List<ScribingMarkup.StyledRun> filtered = filterAndNormalize(parsed.runs(), name);
        ScribingMarkup.Stats stats = ScribingMarkup.measure(filtered);
        if (stats.renderedChars() > limits.maxRenderedChars()) {
            String subject = name ? "name" : "description";
            return fail(
                    ScribingMarkup.Kind.RENDERED_TOO_LONG,
                    "That " + subject + " is " + stats.renderedChars() + " characters. The limit is "
                            + limits.maxRenderedChars() + ". Tags do not count.",
                    0
            );
        }
        if (stats.lineCount() > limits.maxLines()) {
            return fail(
                    ScribingMarkup.Kind.TOO_MANY_LINES,
                    "That description is " + stats.lineCount() + " lines. The limit is "
                            + limits.maxLines() + ".",
                    0
            );
        }

        LineOverflow overflow = firstLineOverflow(filtered, limits.maxLineChars());
        if (overflow != null) {
            return fail(
                    ScribingMarkup.Kind.LINE_TOO_LONG,
                    "Line " + overflow.line() + " is " + overflow.characters()
                            + " characters. The limit is " + limits.maxLineChars() + ".",
                    0
            );
        }

        return new Result.Ok(filtered, stats, stats.renderedChars() == 0);
    }

    /**
     * Filtering is deliberately not permission scoped. These controls can damage the
     * render for every player who later sees the item, so this protects output
     * integrity rather than deciding how much the writer is trusted.
     */
    @Nonnull
    private static List<ScribingMarkup.StyledRun> filterAndNormalize(
            @Nonnull List<ScribingMarkup.StyledRun> runs,
            boolean name
    ) {
        List<Glyph> glyphs = new ArrayList<>();
        for (ScribingMarkup.StyledRun run : runs) {
            for (int offset = 0; offset < run.text().length();) {
                int codePoint = run.text().codePointAt(offset);
                offset += Character.charCount(codePoint);
                if (isDisallowed(codePoint)) {
                    continue;
                }
                if (name && codePoint == '\n') {
                    codePoint = ' ';
                }
                glyphs.add(new Glyph(codePoint, run.bold(), run.italic(), run.color()));
            }
        }

        int first = 0;
        while (first < glyphs.size() && Character.isWhitespace(glyphs.get(first).codePoint())) {
            first++;
        }
        int last = glyphs.size();
        while (last > first && Character.isWhitespace(glyphs.get(last - 1).codePoint())) {
            last--;
        }

        List<ScribingMarkup.StyledRun> result = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Glyph style = null;
        boolean previousSpace = false;
        for (int index = first; index < last; index++) {
            Glyph glyph = glyphs.get(index);
            if (glyph.codePoint() == ' ' && previousSpace) {
                continue;
            }
            previousSpace = glyph.codePoint() == ' ';

            if (style == null || !style.sameStyle(glyph)) {
                appendRun(result, text, style);
                style = glyph;
            }
            text.appendCodePoint(glyph.codePoint());
        }
        appendRun(result, text, style);
        return List.copyOf(result);
    }

    /**
     * The first three lines mirror the engine's own house rule for player text,
     * MessageUtil.containsControlCharacters, which rejects everything under 0x20, 0x7F,
     * and the C1 block from 0x80 to 0x9F. Our one deliberate divergence is permitting a
     * newline, because a real line break is the feature.
     *
     * <p>The rest goes further than the engine does, and each range earns its place. A
     * line or paragraph separator can render as a break and would walk straight around
     * the line cap. A bidirectional override inside an item name visually scrambles the
     * text drawn around it for every player who later looks at that item.</p>
     */
    private static boolean isDisallowed(int codePoint) {
        return codePoint >= 0x0000 && codePoint <= 0x001F && codePoint != '\n'
                || codePoint == 0x007F
                || codePoint >= 0x0080 && codePoint <= 0x009F
                || codePoint >= 0x200B && codePoint <= 0x200F
                || codePoint >= 0x202A && codePoint <= 0x202E
                || codePoint == 0x2028
                || codePoint == 0x2029
                || codePoint >= 0x2066 && codePoint <= 0x2069;
    }

    private static void appendRun(
            @Nonnull List<ScribingMarkup.StyledRun> result,
            @Nonnull StringBuilder text,
            @Nullable Glyph style
    ) {
        if (style == null || text.isEmpty()) {
            return;
        }
        result.add(new ScribingMarkup.StyledRun(
                text.toString(),
                style.bold(),
                style.italic(),
                style.color()
        ));
        text.setLength(0);
    }

    @Nullable
    private static LineOverflow firstLineOverflow(
            @Nonnull List<ScribingMarkup.StyledRun> runs,
            int limit
    ) {
        int line = 1;
        int characters = 0;
        for (ScribingMarkup.StyledRun run : runs) {
            for (int offset = 0; offset < run.text().length();) {
                int codePoint = run.text().codePointAt(offset);
                offset += Character.charCount(codePoint);
                if (codePoint == '\n') {
                    if (characters > limit) {
                        return new LineOverflow(line, characters);
                    }
                    line++;
                    characters = 0;
                } else {
                    characters++;
                }
            }
        }
        return characters > limit ? new LineOverflow(line, characters) : null;
    }

    @Nonnull
    private static Result.Fail fail(@Nonnull ScribingMarkup.Kind kind, @Nonnull String message, int offset) {
        return new Result.Fail(List.of(new ScribingMarkup.Problem(kind, message, offset)));
    }

    private record Glyph(int codePoint, boolean bold, boolean italic, @Nullable String color) {
        private boolean sameStyle(@Nonnull Glyph other) {
            return bold == other.bold
                    && italic == other.italic
                    && java.util.Objects.equals(color, other.color);
        }
    }

    private record LineOverflow(int line, int characters) {
    }
}
