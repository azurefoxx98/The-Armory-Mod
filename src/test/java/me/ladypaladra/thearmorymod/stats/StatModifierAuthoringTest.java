package me.ladypaladra.thearmorymod.stats;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the stat readers' asset assumptions enforceable as new items are authored.
 *
 * <p>The runtime cannot report a missing stat asset because its index simply never resolves.
 * It also cannot tell modifier types apart after the engine has folded every modifier into one
 * effective maximum. A multiplier stat read with an additive modifier, or an amount stat read
 * with a multiplicative one, silently applies the wrong number. These checks fail at authoring
 * time while the original JSON still makes the cause visible.</p>
 */
class StatModifierAuthoringTest {

    private static final Path RESOURCE_ROOT = Path.of("src", "main", "resources");
    private static final String MULTIPLICATIVE = "Multiplicative";
    private static final String ADDITIVE = "Additive";
    private static final Gson GSON = new Gson();

    @Test
    void everyDeclaredStatHasAnAsset() throws IOException {
        assertTrue(
                !jsonFiles().isEmpty(),
                "Scanned no JSON files at all, so this result is meaningless."
        );

        for (String statId : ArmoryStatIds.ALL) {
            Path statAsset = RESOURCE_ROOT.resolve(
                    Path.of("Server", "Entity", "Stats", statId + ".json")
            );
            assertTrue(
                    Files.isRegularFile(statAsset),
                    "Declared stat " + statId + " has no asset at " + statAsset + ". Its index "
                            + "will never resolve, so its multiplier will stay at 1.0 without "
                            + "reporting the missing asset."
            );
        }
    }

    @Test
    void armoryStatsUseOnlyTheModifierTypeTheirReaderNeeds() throws IOException {
        List<Path> files = jsonFiles();
        assertTrue(
                !files.isEmpty(),
                "Scanned no JSON files at all, so this result is meaningless."
        );

        List<String> offenders = new ArrayList<>();
        int qualifyingModifiers = 0;
        for (Path path : files) {
            byte[] raw = Files.readAllBytes(path);
            if (!mentionsDeclaredStat(raw)) {
                continue;
            }
            JsonElement root;
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                root = GSON.fromJson(reader, JsonElement.class);
            }
            qualifyingModifiers += inspect(
                    root,
                    RESOURCE_ROOT.relativize(path).toString(),
                    offenders
            );
        }

        // Both kinds are authored: the multiplier stats from earlier deliveries, and the night
        // amounts on 11 pumpkin helms and 2 void goblin pieces.
        assertTrue(
                qualifyingModifiers > 0,
                "Found no modifiers for the declared Armory stats, so this gate proved nothing."
        );

        assertEquals(
                List.of(),
                offenders,
                "Armory stat modifiers must be arrays of objects whose CalculationType matches "
                        + "the stat's reader. A stat in ArmoryStatIds.MULTIPLIERS is read by "
                        + "dividing its effective maximum by its base maximum, which only recovers "
                        + "the authored amount while every modifier is Multiplicative. A stat in "
                        + "ArmoryStatIds.AMOUNTS is read by subtracting the base maximum, which "
                        + "only recovers it while every modifier is Additive. See "
                        + "StatMultiplier.fromMaxima and StatAddition.fromMaxima."
        );
    }

    private static List<Path> jsonFiles() throws IOException {
        try (Stream<Path> files = Files.walk(RESOURCE_ROOT)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".json"))
                    .toList();
        }
    }

    /**
     * Answers whether a file is worth parsing, by looking for a declared stat id in its raw
     * bytes.
     *
     * <p>This is a completeness filter rather than a sample. A file can only break the rule by
     * using one of the declared ids as a key, so a file whose bytes never contain one cannot
     * hold a violation and skipping it drops no coverage.</p>
     *
     * <p>It exists because the resources tree is 67 MB across roughly 1500 JSON files, and one
     * prefab alone is 47 MB. A parsed tree costs several times its size on disk, so parsing
     * every file exhausted the test heap and took the whole test run down with it. The ids are
     * ASCII, so the bytes are compared directly and no decoding is attempted, which also keeps
     * a file with unexpected encoding from failing here rather than where it matters.</p>
     */
    private static boolean mentionsDeclaredStat(byte[] raw) {
        for (String statId : ArmoryStatIds.ALL) {
            if (containsBytes(raw, statId.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBytes(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[start + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static int inspect(JsonElement element, String file, List<String> offenders) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
            return 0;
        }

        if (element.isJsonArray()) {
            int count = 0;
            for (JsonElement child : element.getAsJsonArray()) {
                count += inspect(child, file, offenders);
            }
            return count;
        }

        JsonObject object = element.getAsJsonObject();
        int count = 0;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (isModifierMap(entry.getKey()) && entry.getValue().isJsonObject()) {
                count += inspectModifierMap(entry.getValue().getAsJsonObject(), file, offenders);
            }
            count += inspect(entry.getValue(), file, offenders);
        }
        return count;
    }

    private static int inspectModifierMap(
            JsonObject modifierMap,
            String file,
            List<String> offenders
    ) {
        int count = 0;
        for (Map.Entry<String, JsonElement> entry : modifierMap.entrySet()) {
            String statId = entry.getKey();
            if (!ArmoryStatIds.ALL.contains(statId)) {
                continue;
            }

            JsonElement modifiers = entry.getValue();
            if (!modifiers.isJsonArray()) {
                offenders.add(file + ", " + statId + ", value was " + modifiers);
                continue;
            }

            int index = 0;
            for (JsonElement modifier : modifiers.getAsJsonArray()) {
                count++;
                if (!modifier.isJsonObject()) {
                    offenders.add(file + ", " + statId + ", element " + index
                            + " was " + modifier);
                    index++;
                    continue;
                }

                String expected = ArmoryStatIds.AMOUNTS.contains(statId) ? ADDITIVE : MULTIPLICATIVE;
                JsonElement calculationType = modifier.getAsJsonObject().get("CalculationType");
                if (calculationType == null) {
                    offenders.add(file + ", " + statId + ", element " + index
                            + " had no CalculationType");
                } else if (!calculationType.isJsonPrimitive()
                        || !expected.equals(calculationType.getAsString())) {
                    offenders.add(file + ", " + statId + ", element " + index
                            + " had CalculationType " + calculationType + ", expected " + expected);
                }
                index++;
            }
        }
        return count;
    }

    private static boolean isModifierMap(String key) {
        return "StatModifiers".equals(key) || "RawStatModifiers".equals(key);
    }
}
