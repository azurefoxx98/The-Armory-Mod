package me.ladypaladra.thearmorymod.stats.systems;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import me.ladypaladra.thearmorymod.stats.util.ProjectileMatchUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectileMatchUtilTest {

    private static final List<String> ARROW_PROJECTILE_PATTERNS = List.of(
            "Arrow*",
            "*Arrow",
            "*arrow*"
    );

    // Register both family tags before the production matcher resolves and caches their
    // indexes. The test items mirror the expanded tags that asset loading supplies.
    private static final TaggedItem BOW = taggedItem(
            "TestShortbow",
            Map.of("Type", new String[] {"Weapon"}, "Family", new String[] {"Bow"})
    );
    private static final TaggedItem CROSSBOW = taggedItem(
            "TestCrossbow",
            Map.of("Type", new String[] {"Weapon"}, "Family", new String[] {"Crossbow"})
    );

    @Test
    void arrowPatternsMatchPrefixSuffixAndMixedCase() {
        assertTrue(ProjectileMatchUtil.matchesAnyIgnoreCase("Arrow_Flint", ARROW_PROJECTILE_PATTERNS));
        assertTrue(ProjectileMatchUtil.matchesAnyIgnoreCase("Projectile_Arrow", ARROW_PROJECTILE_PATTERNS));
        assertTrue(ProjectileMatchUtil.matchesAnyIgnoreCase("projectile_ARROW_flint", ARROW_PROJECTILE_PATTERNS));
        assertFalse(ProjectileMatchUtil.matchesAnyIgnoreCase("Projectile_Bolt", ARROW_PROJECTILE_PATTERNS));
    }

    @Test
    void bowAndCrossbowFamilyTagsMatch() {
        assertTrue(ArrowDamageBonusSystem.matchesBowFamily(BOW));
        assertTrue(ArrowDamageBonusSystem.matchesBowFamily(CROSSBOW));
    }

    @Test
    void rainbowArmourIdIsNotTreatedAsABow() throws IOException {
        String itemId = "RookEliteHelmRainbow";
        TaggedItem armour = taggedItem(itemId, readTags(itemId));

        // This is the false positive from the old id glob. The production matcher now
        // ignores the id and reads the asset's expanded family tags instead.
        assertTrue(ProjectileMatchUtil.matchesAnyIgnoreCase(itemId, List.of("*bow*")));
        assertFalse(ArrowDamageBonusSystem.matchesBowFamily(armour));
    }

    private static Map<String, String[]> readTags(String itemId) throws IOException {
        Path path = Path.of(
                "src",
                "main",
                "resources",
                "Server",
                "Item",
                "Items",
                itemId + ".json"
        );
        Map<String, String[]> tags = new LinkedHashMap<>();

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject tagObject = JsonParser.parseReader(reader)
                    .getAsJsonObject()
                    .getAsJsonObject("Tags");
            for (Map.Entry<String, JsonElement> entry : tagObject.entrySet()) {
                JsonArray values = entry.getValue().getAsJsonArray();
                String[] tagValues = new String[values.size()];
                for (int index = 0; index < values.size(); index++) {
                    tagValues[index] = values.get(index).getAsString();
                }
                tags.put(entry.getKey(), tagValues);
            }
        }

        return tags;
    }

    private static TaggedItem taggedItem(String itemId, Map<String, String[]> tags) {
        return new TaggedItem(itemId, tags);
    }

    private static final class TaggedItem extends Item {

        private TaggedItem(String itemId, Map<String, String[]> tags) {
            super(itemId);
            data = new AssetExtraInfo.Data(Item.class, itemId, null);
            data.putTags(tags);
        }
    }
}
