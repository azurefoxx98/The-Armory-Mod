package me.ladypaladra.thearmorymod.dice;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * How a die's faces read as a number, and how a number picks its band and its art.
 */
class DieResultTest {

    @Test
    void aD10ReadsItsZeroAsTen() {
        assertEquals(10, DieType.D10.read(List.of("0")));
        assertEquals(7, DieType.D10.read(List.of("7")));
    }

    @Test
    void aD100ReadsItsPairAsATableDoes() {
        assertEquals(100, DieType.D100.read(List.of("00", "0")));
        assertEquals(1, DieType.D100.read(List.of("00", "1")));
        assertEquals(10, DieType.D100.read(List.of("10", "0")));
        assertEquals(99, DieType.D100.read(List.of("90", "9")));
        assertThrows(IllegalArgumentException.class, () -> DieType.D100.read(List.of("5", "0")));
    }

    @Test
    void everyResultFrom1ToTheHighestCanBeRolled() throws IOException {
        DiceCatalog catalog = DiceCatalog.load();
        for (DieType type : DieType.values()) {
            Set<Integer> seen = new HashSet<>();
            List<DieShape> dice = catalog.shapesOf(type);
            if (dice.size() == 1) {
                dice.get(0).faces().forEach(f -> seen.add(type.read(List.of(f.label()))));
            } else {
                for (DieShape.Face t : dice.get(0).faces()) {
                    for (DieShape.Face u : dice.get(1).faces()) {
                        seen.add(type.read(List.of(t.label(), u.label())));
                    }
                }
            }
            assertEquals(type.sides(), seen.size(), type + " cannot roll every number");
        }
    }

    @Test
    void theExtremesAreTheFavourAndTheVeil() {
        assertEquals(RollBand.FAVOUR, RollBand.of(20, 20));
        assertEquals(RollBand.VEIL, RollBand.of(1, 20));
        assertEquals(RollBand.FAVOUR, RollBand.of(100, 100));
        assertEquals(RollBand.VEIL, RollBand.of(1, 3));
        assertEquals(RollBand.MIDDLE, RollBand.of(2, 3));
        assertEquals(RollBand.VERY_HIGH, RollBand.of(19, 20));
        assertEquals(RollBand.VERY_LOW, RollBand.of(2, 20));
    }

    @Test
    void everyBandOfEveryDieHasItsNumberArt() throws IOException {
        DiceCatalog catalog = DiceCatalog.load();
        for (DieType type : DieType.values()) {
            String colour = catalog.shapesOf(type).get(0).colour();
            for (int v = 1; v <= type.sides(); v++) {
                String texture = RollBand.of(v, type.sides()).numberTexture(colour);
                assertNotNull(getClass().getResource("/Common/" + texture), "no art " + texture);
            }
        }
    }
}
