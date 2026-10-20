package me.ladypaladra.thearmorymod;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps every Armory armour piece, shield and weapon listed under the game's own category as well
 * as the Armory's.
 *
 * <p>Other mods find gear by those vanilla categories. Hexcode picks the imbuement profile for an
 * item from the first of its categories that has one, and it has profiles for Items.Armors,
 * Items.Weapons and Items.Tools only, so a piece listed under an Armory category alone cannot be
 * imbued at all. 1.24.1 shipped 217 armour pieces and every shield that way. A missing category
 * reports nothing anywhere: the item loads, shows in the Armory tab and works in game.</p>
 */
class ItemCategoryAuthoringTest {

    private static final Path ITEM_ROOT = Path.of("src", "main", "resources", "Server", "Item", "Items");
    private static final Gson GSON = new Gson();

    private static final Map<String, String> VANILLA_CATEGORY = Map.of(
            "ArmoryCategory.ArmoryArmors", "Items.Armors",
            "ArmoryCategory.ArmoryShields", "Items.Weapons",
            "ArmoryCategory.ArmoryWeapons", "Items.Weapons"
    );

    @Test
    void armoryGearAlsoCarriesItsVanillaCategory() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (Path path : itemFiles()) {
            JsonObject item = read(path);
            String file = ITEM_ROOT.relativize(path).toString();
            JsonElement categories = item.get("Categories");

            if (categories == null) {
                // An item with no Categories inherits its parent's. One with no parent either is
                // in no creative tab, and armour like that is invisible to the mods above.
                if (item.has("Armor") && !item.has("Parent")) {
                    offenders.add(file + " is armour with no Categories and no Parent");
                }
                continue;
            }
            if (!categories.isJsonArray()) {
                offenders.add(file + " has Categories that is not a list");
                continue;
            }

            Set<String> present = new HashSet<>();
            for (JsonElement entry : categories.getAsJsonArray()) {
                if (entry.isJsonPrimitive()) {
                    present.add(entry.getAsString());
                }
            }
            for (Map.Entry<String, String> rule : VANILLA_CATEGORY.entrySet()) {
                if (present.contains(rule.getKey())) {
                    checked++;
                    if (!present.contains(rule.getValue())) {
                        offenders.add(file + " is in " + rule.getKey() + " but not " + rule.getValue());
                    }
                }
            }
        }

        assertTrue(checked > 1000, "Checked only " + checked + " Armory gear items, so this result is meaningless.");
        assertEquals(List.of(), offenders);
    }

    private static List<Path> itemFiles() throws IOException {
        try (Stream<Path> files = Files.walk(ITEM_ROOT)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".json"))
                    .toList();
        }
    }

    private static JsonObject read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            return root != null && root.isJsonObject() ? root.getAsJsonObject() : new JsonObject();
        }
    }
}
