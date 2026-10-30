package me.ladypaladra.thearmorymod.dice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
            // A texture swapped in at run time is never checked by the asset loader: a wrong path draws nothing.
            assertNotNull(DiceCatalogTest.class.getResource("/Common/" + shape.texture()), shape.texture());
            assertNotNull(DiceCatalogTest.class.getResource("/Common/" + shape.darkTexture()), shape.darkTexture());
        }
    }

    @Test
    void aFaceWhoseTurnDoesNotBringItUpIsRejected() throws IOException {
        String good = shippedText();
        // The D3's first face is +X, carried up by a quarter turn about Z. Reversing that turn sends it down.
        String bad = good.replaceFirst("0\\.707107,\\s*0\\.707107", "-0.707107, 0.707107");
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
