package me.ladypaladra.thearmorymod.scribing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Every value handed to an ICU date or time placeholder must arrive as a string.
 *
 * <p>This exists because the opposite shipped. The sealed panel passed a raw epoch long to
 * {@code {sealedAt, date}}. There is a param overload taking a long, so it compiled, looked
 * correct in review, and rendered the sentence with an empty space where the date belonged.
 * Nothing failed, nothing logged, and it was found by someone sealing an item and looking at
 * the panel.</p>
 *
 * <p>The engine's own screens convert first: an epoch becomes an {@code Instant}, then a zoned
 * time, then a string, and only then does it become a parameter. This asserts we do the same
 * everywhere rather than remembering to.</p>
 */
class LangDateParamTest {

    private static final Path LANG_ROOT = Path.of("src", "main", "resources", "Server", "Languages");
    private static final Path JAVA_ROOT = Path.of("src", "main", "java");

    /** Matches an ICU placeholder that formats as a date or a time, capturing the name. */
    private static final Pattern ICU_DATE =
            Pattern.compile("\\{\\s*([A-Za-z0-9_]+)\\s*,\\s*(?:date|time)\\b");

    @Test
    void everyIcuDateParameterIsGivenAString() throws IOException {
        Set<String> dateParams = new LinkedHashSet<>();
        assertTrue(Files.isDirectory(LANG_ROOT), "Language root " + LANG_ROOT.toAbsolutePath()
                + " not found, so this gate proved nothing.");

        try (Stream<Path> files = Files.walk(LANG_ROOT)) {
            for (Path lang : files.filter(Files::isRegularFile).toList()) {
                Matcher matcher = ICU_DATE.matcher(Files.readString(lang, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    dateParams.add(matcher.group(1));
                }
            }
        }

        // Having no date placeholder at all is a legitimate state rather than a broken scan, and
        // it is the state this mod is in: a {value, date} placeholder was measured in game to
        // render one fixed numeric format and to ignore its style argument entirely, so the
        // sealed panel builds its date from separate day, month and year parts instead. This
        // skips visibly rather than passing quietly, because a green tick on a check that
        // examined nothing is the exact failure this file is written to avoid.
        assumeFalse(
                dateParams.isEmpty(),
                "No language file uses an ICU date or time placeholder, so there is nothing for "
                        + "this gate to check. It stays because the day one is added it must be "
                        + "given a string."
        );

        List<String> offenders = new ArrayList<>();
        int scanned = 0;

        try (Stream<Path> files = Files.walk(JAVA_ROOT)) {
            for (Path java : files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                String body = Files.readString(java, StandardCharsets.UTF_8);
                for (String name : dateParams) {
                    Matcher call = Pattern.compile(
                            "\\.param\\(\\s*\"" + Pattern.quote(name) + "\"\\s*,").matcher(body);
                    while (call.find()) {
                        String argument = balancedArgument(body, call.end());
                        // A string conversion is what makes the value formattable. Requiring a
                        // marker rather than banning known epoch accessors is deliberate: a
                        // blacklist has to predict every accessor somebody adds later.
                        if (!argument.contains("toString") && !argument.contains("String")) {
                            offenders.add(JAVA_ROOT.relativize(java) + " gives " + name
                                    + " a non-string value:" + argument.replaceAll("\\s+", " "));
                        }
                    }
                }
            }
        }

        assertTrue(scanned > 0, "Scanned no Java files, so this gate passed for the wrong reason.");

        assertEquals(
                List.of(),
                offenders,
                "These call sites hand a non-string value to an ICU date or time placeholder. "
                        + "It compiles and renders nothing where the date belongs. Convert the "
                        + "value first, the way the engine's own screens do."
        );
    }

    /**
     * All twelve month names must exist, because the key that reaches them is built at runtime.
     *
     * <p>The sealed panel asks for {@code month} plus the month number, so a missing entry is
     * invisible until that month arrives and then renders nothing in the middle of the sentence.
     * A gap in December would not be seen until December. Checking the whole set costs
     * milliseconds and removes the calendar from the list of things that can surprise us.</p>
     */
    @Test
    void everyMonthNameExists() throws IOException {
        Path english = Path.of("src", "main", "resources", "Server", "Languages",
                "en-US", "server.lang");
        assertTrue(Files.isRegularFile(english),
                english.toAbsolutePath() + " not found, so this gate proved nothing.");

        String body = Files.readString(english, StandardCharsets.UTF_8);
        List<String> missing = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            String key = "customUI.scribingPage.month" + month;
            if (!Pattern.compile("(?m)^" + Pattern.quote(key) + "\\s*=\\s*\\S").matcher(body).find()) {
                missing.add(key);
            }
        }

        assertEquals(
                List.of(),
                missing,
                "These month names are missing or empty. The sealed panel builds this key from "
                        + "the month number, so each gap renders as nothing inside the sentence "
                        + "for one month of the year."
        );
    }

    /**
     * Reads a call argument by counting parentheses rather than by matching a closing one.
     *
     * <p>A lazy regex looked right and was wrong: it stopped at the first inner close, so
     * {@code Instant.ofEpochMilli(seal.epochMillis()).atZone(UTC).toString()} was read as
     * {@code Instant.ofEpochMilli(seal.epochMillis(} and the conversion at the end went unseen.
     * The gate then failed the very code it exists to require. Correctness here needs depth
     * counting, not a pattern.</p>
     *
     * <p>Parentheses inside string literals would fool this. No call site in this codebase
     * passes one, and the failure direction is a loud false alarm rather than a silent pass.</p>
     */
    private static String balancedArgument(String body, int from) {
        int depth = 1;
        for (int index = from; index < body.length(); index++) {
            char character = body.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
                if (depth == 0) {
                    return body.substring(from, index);
                }
            }
        }
        return body.substring(from);
    }
}
