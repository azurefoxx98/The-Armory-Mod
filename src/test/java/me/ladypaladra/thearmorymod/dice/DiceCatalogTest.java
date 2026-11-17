package me.ladypaladra.thearmorymod.dice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiceCatalogTest {

    @Test
    void theShippedDataHoldsEveryDie() throws IOException {
        DiceCatalog catalog = DiceCatalog.load();
        assertEquals(List.of("D3", "D4", "D6", "D8", "D10", "D20", "D100_Tens", "D100_Units"),
                List.copyOf(catalog.shapes().keySet()));
        assertEquals(6, catalog.shape("D3").faces().size());
        assertEquals(20, catalog.shape("D20").faces().size());
        assertEquals(2, catalog.shapesOf(DieType.D100).size());
    }

    @Test
    void everyModelTheDataNamesShipsWithThePlugin() throws IOException {
        // The data and the models come from one build. A model it names that is not in the jar loads as nothing.
        for (DieShape shape : DiceCatalog.load().shapes().values()) {
            assertModel(shape.flyingModel());
            for (DieShape.Face face : shape.faces()) {
                assertModel(face.restModel());
            }
        }
    }

    @Test
    void everyColourShipsItsPicturesAndItsShows() throws IOException {
        // A picture or an effect named at run time is never checked by the asset loader: a wrong path draws nothing,
        // and a missing effect only logs a warning once.
        for (DieColour colour : DiceCatalog.load().colours().values()) {
            assertNotNull(DiceCatalogTest.class.getResource("/Common/" + colour.texture()), colour.texture());
            assertNotNull(DiceCatalogTest.class.getResource("/Common/" + colour.darkTexture()), colour.darkTexture());
            String light = colour.light();
            for (String system : List.of("Awaken_" + light, "Bounce_" + light, "Release_" + light,
                    "PickUp_Spark_" + light, "Trail_" + light, "Trail_Faint_" + light, "Trail_Bright_" + light,
                    "Favour_Minor_" + light, "Favour_Major_" + light, "Favour_Grand_" + light,
                    "Band_VeryHigh_" + light, "Band_High_" + light, "Band_Low_" + light, "Band_VeryLow_" + light,
                    "Charge_Faint_" + light, "Charge_Gather_" + light, "Charge_Swirl_" + light,
                    "Charge_Full_" + light)) {
                String path = "/Server/Particles/Armory_Dice/Armory_Dice_" + system + ".particlesystem";
                assertNotNull(DiceCatalogTest.class.getResource(path), colour.key() + ": no " + path);
            }
        }
    }

    @Test
    void everyDieComesInEveryColour() throws IOException {
        DiceCatalog catalog = DiceCatalog.load();
        assertEquals(13, catalog.colours().size());
        for (DieType type : DieType.values()) {
            Set<String> worn = new HashSet<>();
            for (DieItem item : catalog.items().values()) {
                if (item.type() == type && Set.copyOf(item.colours()).size() == 1) {
                    worn.add(item.colour(0).key());
                }
            }
            assertEquals(catalog.colours().keySet(), worn, type + " is missing a colour");
        }
        // The crafted D100 is its October pair: a yellow tens die and a pink units die.
        DieItem d100 = catalog.item("Armory_Dice_D100");
        assertNotNull(d100);
        assertEquals(List.of("yellow", "pink"), d100.colours().stream().map(DieColour::key).toList());
        assertEquals(92, catalog.items().size());
    }

    @Test
    void everyItemShipsItsFileAndItsIcon() throws IOException {
        for (String id : DiceCatalog.load().items().keySet()) {
            assertNotNull(DiceCatalogTest.class.getResource("/Server/Item/Items/Armory_Dice/" + id + ".json"),
                    "no item file for " + id);
            assertNotNull(DiceCatalogTest.class.getResource("/Common/Icons/ItemsGenerated/" + id + ".png"),
                    "no icon for " + id);
        }
    }

    @Test
    void aDieSavedUnderAnIdNoLongerShippedWearsItsCraftedColour() throws IOException {
        DiceCatalog catalog = DiceCatalog.load();
        assertEquals("cyan", catalog.colourOf("Armory_Dice_D6_Teal", "D6").key());
        assertEquals("red", catalog.colourOf("Armory_Dice_D6_Red", "D6").key());
        assertEquals("pink", catalog.colourOf("Armory_Dice_D100", "D100_Units").key());
        assertEquals("pink", catalog.colourOf(null, "D100_Units").key());
    }

    @Test
    void anItemWithTooFewColoursIsRejected() throws IOException {
        JsonObject data = JsonParser.parseString(shippedText()).getAsJsonObject();
        JsonArray one = new JsonArray();
        one.add("yellow");
        data.getAsJsonObject("items").getAsJsonObject("Armory_Dice_D100").add("colours", one);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DiceCatalog.parse(new StringReader(data.toString())));
        assertTrue(e.getMessage().contains("Armory_Dice_D100"), e.getMessage());
    }

    @Test
    void aDieWithoutItsCraftedItemIsRejected() throws IOException {
        JsonObject data = JsonParser.parseString(shippedText()).getAsJsonObject();
        data.getAsJsonObject("items").remove("Armory_Dice_D8");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DiceCatalog.parse(new StringReader(data.toString())));
        assertTrue(e.getMessage().contains("Armory_Dice_D8"), e.getMessage());
    }

    @Test
    void aFaceWhoseTurnDoesNotBringItUpIsRejected() throws IOException {
        String good = shippedText();
        // The D3's first face that does not already point up, given no turn at all: it stays where it points.
        JsonObject data = JsonParser.parseString(good).getAsJsonObject();
        JsonObject planted = null;
        for (JsonElement face : data.getAsJsonObject("dice").getAsJsonObject("D3").getAsJsonArray("results")) {
            if (face.getAsJsonObject().getAsJsonArray("normal").get(1).getAsDouble() < 0.5) {
                planted = face.getAsJsonObject();
                break;
            }
        }
        assertNotNull(planted, "the D3 has no face to plant the fault on");
        JsonArray none = new JsonArray();
        for (double c : new double[]{0.0, 0.0, 0.0, 1.0}) {
            none.add(c);
        }
        planted.add("restTurn", none);
        String bad = data.toString();
        assertTrue(!bad.equals(good), "the planted fault did not land");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DiceCatalog.parse(new StringReader(bad)));
        assertTrue(e.getMessage().contains("straight up"), e.getMessage());
    }

    @Test
    void aLabelNoDieCarriesIsRejected() throws IOException {
        String bad = shippedText().replaceFirst("\"result\": \"20\"", "\"result\": \"21\"");
        assertThrows(IllegalArgumentException.class, () -> DiceCatalog.parse(new StringReader(bad)));
    }

    private static void assertModel(String id) {
        String path = id.contains("_Rest_") ? "/Server/Models/Armory_Dice/Rest/" + id + ".json"
                : "/Server/Models/Armory_Dice/" + id + ".json";
        assertNotNull(DiceCatalogTest.class.getResource(path), "no model asset " + path);
    }

    private static String shippedText() throws IOException {
        try (InputStream in = DiceCatalog.class.getResourceAsStream(DiceCatalog.RESOURCE)) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
