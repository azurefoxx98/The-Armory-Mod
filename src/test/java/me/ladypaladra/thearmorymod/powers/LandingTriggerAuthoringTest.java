package me.ladypaladra.thearmorymod.powers;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Keeps every landing effect an armour piece names real and readable.
 *
 * <p>The engine never checks the value of an item tag, so a misspelt effect name loads without a
 * word and the piece simply does nothing when its wearer lands. The server logs it once at the
 * first landing, which nobody is watching for, so the build catches it instead.</p>
 */
class LandingTriggerAuthoringTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources", "Server", "Item");
    private static final Path ITEM_ROOT = RESOURCES.resolve("Items");
    private static final Path ROOT_INTERACTIONS = RESOURCES.resolve("RootInteractions");
    private static final Gson GSON = new Gson();

    @Test
    void everyLandingEffectAnItemNamesExistsWithReadableSettings() throws IOException {
        Map<String, Path> roots = new HashMap<>();
        try (Stream<Path> stream = Files.walk(ROOT_INTERACTIONS)) {
            stream.filter(path -> path.toString().endsWith(".json"))
                    .forEach(path -> roots.put(stem(path), path));
        }

        List<String> offenders = new ArrayList<>();
        int named = 0;
        for (Path path : files(ITEM_ROOT)) {
            JsonObject tags = read(path).getAsJsonObject("Tags");
            JsonElement values = tags != null ? tags.get(WornTags.ON_LANDING) : null;
            if (values == null) {
                continue;
            }

            String file = ITEM_ROOT.relativize(path).toString();
            if (!values.isJsonArray() || values.getAsJsonArray().isEmpty()) {
                offenders.add(file + " has " + WornTags.ON_LANDING + " without a list of landing effects");
                continue;
            }

            for (JsonElement value : values.getAsJsonArray()) {
                named++;
                String id = value.isJsonPrimitive() ? value.getAsString().trim() : "";
                Path root = roots.get(id);
                if (root == null) {
                    offenders.add(file + " names landing effect \"" + id + "\", and no root interaction has that name");
                    continue;
                }

                List<String> problems = new ArrayList<>();
                LandingTrigger.Settings settings = LandingTrigger.Settings.read(rootTags(root), problems::add);
                for (String problem : problems) {
                    offenders.add(ROOT_INTERACTIONS.relativize(root) + ": " + problem);
                }
                if (settings.hit() != null && !roots.containsKey(settings.hit())) {
                    offenders.add(ROOT_INTERACTIONS.relativize(root) + ": " + LandingTrigger.HIT_TAG + " names \"" + settings.hit() + "\", and no root interaction has that name");
                }
            }
        }

        assumeTrue(named > 0, "No item names a landing effect, so there is nothing to check.");
        assertTrue(offenders.isEmpty(), String.join("\n", offenders));
    }

    private static Map<String, String[]> rootTags(Path root) throws IOException {
        JsonObject tags = read(root).getAsJsonObject("Tags");
        Map<String, String[]> result = new HashMap<>();
        if (tags == null) {
            return result;
        }
        for (Map.Entry<String, JsonElement> entry : tags.entrySet()) {
            if (!entry.getValue().isJsonArray()) {
                continue;
            }
            List<String> values = new ArrayList<>();
            for (JsonElement value : entry.getValue().getAsJsonArray()) {
                values.add(value.isJsonPrimitive() ? value.getAsString() : value.toString());
            }
            result.put(entry.getKey(), values.toArray(String[]::new));
        }
        return result;
    }

    private static List<Path> files(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static JsonObject read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, JsonObject.class);
        }
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        return name.substring(0, name.length() - ".json".length());
    }
}
