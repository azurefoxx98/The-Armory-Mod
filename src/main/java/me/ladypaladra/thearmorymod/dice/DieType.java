package me.ladypaladra.thearmorymod.dice;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * The seven dice a player carries. Each is one item, and each but the D100 is one physical die. The D100 is thrown as
 * a pair, a tens die and a units die, and read the way a table reads it: the two added, with 00 and 0 making 100.
 */
public enum DieType {
    D3("Armory_Dice_D3", 3, "D3"),
    D4("Armory_Dice_D4", 4, "D4"),
    D6("Armory_Dice_D6", 6, "D6"),
    D8("Armory_Dice_D8", 8, "D8"),
    D10("Armory_Dice_D10", 10, "D10"),
    D20("Armory_Dice_D20", 20, "D20"),
    D100("Armory_Dice_D100", 100, "D100_Tens", "D100_Units");

    private final String itemId;
    private final int sides;
    private final List<String> shapeKeys;

    DieType(String itemId, int sides, String... shapeKeys) {
        this.itemId = itemId;
        this.sides = sides;
        this.shapeKeys = List.of(shapeKeys);
    }

    @Nonnull
    public String itemId() {
        return itemId;
    }

    /**
     * The highest result. Every die starts at 1.
     */
    public int sides() {
        return sides;
    }

    /**
     * The physical dice one throw of this item sends out, in reading order: one key, or the tens and units of a D100.
     */
    @Nonnull
    public List<String> shapeKeys() {
        return shapeKeys;
    }

    /**
     * Reads the faces that ended on top, one per shape key in the same order, as the number a player rolled.
     *
     * @throws IllegalArgumentException when a label is not one this die carries
     */
    public int read(@Nonnull List<String> labels) {
        if (labels.size() != shapeKeys.size()) {
            throw new IllegalArgumentException(this + " reads " + shapeKeys.size() + " faces, given " + labels.size());
        }
        int value;
        if (this == D100) {
            int tens = parse(labels.get(0));
            int units = parse(labels.get(1));
            if (tens % 10 != 0 || tens > 90 || units > 9) {
                throw new IllegalArgumentException("D100 faces " + labels);
            }
            value = tens + units;
            if (value == 0) {
                value = 100;
            }
        } else {
            value = parse(labels.get(0));
            // A D10 carries 0 for its ten, as real ones do.
            if (this == D10 && value == 0) {
                value = 10;
            }
        }
        if (value < 1 || value > sides) {
            throw new IllegalArgumentException(this + " cannot show " + labels);
        }
        return value;
    }

    @Nullable
    public static DieType byItemId(@Nullable String itemId) {
        if (itemId == null) {
            return null;
        }
        for (DieType type : values()) {
            if (type.itemId.equals(itemId)) {
                return type;
            }
        }
        return null;
    }

    private static int parse(String label) {
        try {
            return Integer.parseInt(label);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a die label: " + label, e);
        }
    }
}
