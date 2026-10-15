package me.ladypaladra.thearmorymod.scribing;

import com.hypixel.hytale.server.core.modules.i18n.parser.LangFileParser;
import com.ibm.icu.text.MessageFormat;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks shipped catalogues with the engine parser and ICU message grammar. */
class TranslationCatalogueTest {

    private static final Path ROOT = Path.of("src/main/resources/Server/Languages");
    private static final String PREFIX = "customUI.scribingPage.";

    @Test
    void everyLanguageCoversEnglishAndPreservesParameters() throws IOException {
        Map<String, String> englishServer = read(ROOT.resolve("en-US/server.lang"));
        Map<String, String> englishClient = read(ROOT.resolve("en-US/client.lang"));
        int checked = 0;
        try (Stream<Path> directories = Files.list(ROOT)) {
            for (Path language : directories.filter(Files::isDirectory).toList()) {
                String locale = language.getFileName().toString();
                compare(englishServer, read(language.resolve("server.lang")), locale);
                compare(englishClient, read(language.resolve("client.lang")), locale);
                checked++;
            }
        }
        assertTrue(checked >= 2, "English and Spanish must both be checked.");
    }

    @Test
    void incompleteCataloguesAndExportArtifactsAreRejected() throws IOException {
        Map<String, String> english = read(ROOT.resolve("en-US/server.lang"));
        Map<String, String> spanish = read(ROOT.resolve("es-ES/server.lang"));
        Map<String, String> missing = new HashMap<>(spanish);
        missing.remove("items.Scribing_Table.name");
        assertThrows(AssertionError.class, () -> compare(english, missing, "es-ES"));
        Map<String, String> lostParameter = new HashMap<>(spanish);
        lostParameter.put("customUI.alterationPage.stockFull", "Lleno.");
        assertThrows(AssertionError.class, () -> compare(english, lostParameter, "es-ES"));
        assertThrows(AssertionError.class, () -> parse("key = One\nkey = Two\n", "duplicate"));
        assertThrows(AssertionError.class, () -> parse("items.Hepta\\_Axe.name = Axe\n", "escape"));
        assertThrows(AssertionError.class, () -> parse("key = **Bold**\n", "markdown"));
        assertThrows(AssertionError.class, () -> parse("key =\n", "empty"));
    }

    @Test
    void countersKeepTheirCountsAndPluralForms() throws IOException {
        Map<String, String> english = read(ROOT.resolve("en-US/server.lang"));
        Map<String, String> spanish = read(ROOT.resolve("es-ES/server.lang"));
        for (int count : new int[] {0, 1, 2, ScribingConfig.NAME_MAX_CHARS}) {
            Map<String, Object> values = Map.of("count", count, "countText", Integer.toString(count),
                    "raw", Integer.toString(count), "max", Integer.toString(ScribingConfig.NAME_MAX_RAW_CHARS));
            assertEquals(count + " " + (count == 1 ? "character" : "characters")
                            + " \u2022 " + count + " / 200 typed",
                    format(english, "nameCountBypass", values, "en-US"));
            assertEquals(count + " " + (count == 1 ? "car\u00e1cter" : "caracteres")
                            + " \u2022 " + count + " / 200 escritos",
                    format(spanish, "nameCountBypass", values, "es-ES"));
        }
        for (int lines : new int[] {0, 1, 2, ScribingConfig.DESC_MAX_LINES}) {
            Map<String, Object> values = Map.of("lines", lines, "linesText", Integer.toString(lines),
                    "count", 1, "countText", "1", "raw", "8", "max", "1200");
            assertEquals(lines + " " + (lines == 1 ? "line" : "lines")
                            + " \u2022 1 character \u2022 8 / 1200 typed",
                    format(english, "descriptionCountBypass", values, "en-US"));
            assertEquals(lines + " " + (lines == 1 ? "l\u00ednea" : "l\u00edneas")
                            + " \u2022 1 car\u00e1cter \u2022 8 / 1200 escritos",
                    format(spanish, "descriptionCountBypass", values, "es-ES"));
        }
        assertEquals("6 / 6 l\u00edneas \u2022 300 / 300",
                format(spanish, "descriptionCount", Map.of("lines", 6, "maxLines", 6,
                        "count", 300, "max", 300), "es-ES"));
        assertEquals("48 / 48", format(spanish, "nameCount", Map.of("count", 48, "max", 48), "es-ES"));
    }

    @Test
    void everyValidationReasonHasTranslatableParameters() throws IOException {
        Map<String, String> english = read(ROOT.resolve("en-US/server.lang"));
        Map<String, String> spanish = read(ROOT.resolve("es-ES/server.lang"));
        Set<String> checked = new LinkedHashSet<>();
        for (String raw : List.of("<b>x", "<i>x", "<color is=\"#E8A93B\">x",
                "x</b>", "x</i>", "x</color>", "<color is=\"#123456\">x</color>",
                "<color is=wrong>x</color>")) {
            for (ScribingMarkup.Problem problem : ScribingMarkup.parse(raw).problems()) {
                checkProblem(problem, english, spanish);
                checked.add(problem.translationKey());
            }
        }
        List<ScribingText.Result> limits = List.of(
                ScribingText.validateName("x".repeat(201), ScribingText.Limits.forName(false)),
                ScribingText.validateName("x".repeat(49), ScribingText.Limits.forName(false)),
                ScribingText.validateDescription("x".repeat(301), ScribingText.Limits.forDescription(false)),
                ScribingText.validateDescription("a\n".repeat(6) + "a", ScribingText.Limits.forDescription(false)),
                ScribingText.validateDescription("x".repeat(61), ScribingText.Limits.forDescription(false))
        );
        for (ScribingText.Result result : limits) {
            ScribingText.Result.Fail fail = (ScribingText.Result.Fail) result;
            for (ScribingMarkup.Problem problem : fail.problems()) {
                checkProblem(problem, english, spanish);
                checked.add(problem.translationKey());
            }
        }
        assertEquals(Set.of("errorTagUnclosed", "errorTagStrayClose", "errorColorNotAllowed",
                "errorColorMalformed", "errorRawTooLong", "errorNameTooLong",
                "errorDescriptionTooLong", "errorTooManyLines", "errorLineTooLong"), checked);
    }

    @Test
    void withdrawnColorsKeepTheirSpecificTranslatedReason() throws IOException {
        Map<String, String> english = read(ROOT.resolve("en-US/server.lang"));
        Map<String, String> spanish = read(ROOT.resolve("es-ES/server.lang"));
        try {
            ScribingPalette.resolveAgainstQualities(List.of("#ffffff"), List.of());
            ScribingMarkup.Problem problem = ScribingMarkup.parse("<color is=\"#ffffff\">x</color>")
                    .problems().getFirst();
            assertEquals("errorColorWithdrawn", problem.translationKey());
            checkProblem(problem, english, spanish);
        } finally {
            ScribingPalette.resolveAgainstQualities(List.of(), List.of());
        }
    }

    @Test
    void copyableExamplesKeepThreeLinesAndWorkingMarkup() throws IOException {
        for (String locale : List.of("en-US", "es-ES")) {
            String example = read(ROOT.resolve(locale + "/server.lang")).get(PREFIX + "legendExample");
            assertEquals(3, example.split("\n", -1).length);
            assertFalse(example.contains("\\"), "An export escape survived in " + locale);
            assertTrue(ScribingMarkup.parse(example).ok());
        }
    }

    private static void checkProblem(ScribingMarkup.Problem problem, Map<String, String> english,
                                     Map<String, String> spanish) {
        String key = PREFIX + problem.translationKey();
        assertTrue(english.containsKey(key), key);
        assertTrue(spanish.containsKey(key), key);
        MessageFormat source = new MessageFormat(english.get(key), Locale.US);
        assertEquals(source.getArgumentNames(), problem.parameters().keySet(), key);
        assertEquals(problem.message(), source.format(problem.parameters()), key);
        assertFalse(new MessageFormat(spanish.get(key), Locale.forLanguageTag("es-ES"))
                .format(problem.parameters()).isBlank(), key);
    }

    private static String format(Map<String, String> entries, String key,
                                 Map<String, Object> values, String locale) {
        return new MessageFormat(entries.get(PREFIX + key), Locale.forLanguageTag(locale)).format(values);
    }

    private static void compare(Map<String, String> english, Map<String, String> translated, String locale) {
        assertFalse(english.isEmpty());
        assertEquals(english.keySet(), translated.keySet(), locale);
        for (Map.Entry<String, String> entry : english.entrySet()) {
            MessageFormat source = new MessageFormat(entry.getValue(), Locale.US);
            MessageFormat target = new MessageFormat(translated.get(entry.getKey()), Locale.forLanguageTag(locale));
            assertEquals(source.getArgumentNames(), target.getArgumentNames(), locale + ": " + entry.getKey());
        }
    }

    private static Map<String, String> read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
    }

    private static Map<String, String> parse(String body, String source) throws IOException {
        assertFalse(body.startsWith("\uFEFF"), source);
        assertFalse(body.contains("\uFFFD"), source);
        assertFalse(body.contains("**"), source);
        assertFalse(body.contains("\\_"), source);
        assertFalse(body.contains("\\["), source);
        Set<String> keys = new LinkedHashSet<>();
        for (String line : body.lines().toList()) {
            String clean = line.strip();
            if (clean.isEmpty() || clean.startsWith("#")) {
                continue;
            }
            int equals = clean.indexOf('=');
            assertTrue(equals > 0, source + ": " + clean);
            String key = clean.substring(0, equals).strip();
            assertTrue(key.matches("[A-Za-z0-9_.]+"), key);
            assertTrue(keys.add(key), source + ": duplicate " + key);
            assertFalse(clean.substring(equals + 1).isBlank(), source + ": empty " + key);
        }
        try (BufferedReader reader = new BufferedReader(new StringReader(body))) {
            Map<String, String> parsed = LangFileParser.parse(source, reader);
            assertEquals(keys, parsed.keySet(), source);
            return parsed;
        }
    }
}
