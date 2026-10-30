package me.ladypaladra.thearmorymod.dice;

import javax.annotation.Nonnull;

/**
 * How good a roll was, from its place between the lowest and the highest face. Each band has its own number colours
 * and its own touch in the roll's show. The two extremes are the Avatar's Favour and the Veil.
 */
public enum RollBand {
    FAVOUR("Favour", false),
    VERY_HIGH("VeryHigh", true),
    HIGH("High", true),
    MIDDLE("Middle", true),
    LOW("Low", true),
    VERY_LOW("VeryLow", false),
    VEIL("Veil", false);

    private final String artName;
    private final boolean inDieColour;

    RollBand(String artName, boolean inDieColour) {
        this.artName = artName;
        this.inDieColour = inDieColour;
    }

    /**
     * The band's name in the art's file names, such as {@code VeryHigh}.
     */
    @Nonnull
    public String artName() {
        return artName;
    }

    /**
     * The texture the big number's tiles wear in this band. The middle bands are drawn in the die's own colour. The
     * extremes and the very low band have one look for every die.
     */
    @Nonnull
    public String numberTexture(@Nonnull String dieColour) {
        String colour = inDieColour ? "_" + capitalise(dieColour) : "";
        return "Items/Armory_Dice/Number/Armory_Dice_Number_" + artName + colour + "_Texture.png";
    }

    @Nonnull
    public static RollBand of(int value, int sides) {
        if (value >= sides) {
            return FAVOUR;
        }
        if (value <= 1) {
            return VEIL;
        }
        double place = (value - 1.0) / (sides - 1.0);
        if (place >= 0.85) {
            return VERY_HIGH;
        }
        if (place >= 0.6) {
            return HIGH;
        }
        if (place > 0.4) {
            return MIDDLE;
        }
        if (place > 0.15) {
            return LOW;
        }
        return VERY_LOW;
    }

    /**
     * How big the extremes' show is, by how rare they are: either extreme of a D20 is 1 in 20, of a D100 1 in 100.
     */
    public enum Tier {
        MINOR("Minor"),
        MAJOR("Major"),
        GRAND("Grand");

        private final String artName;

        Tier(String artName) {
            this.artName = artName;
        }

        @Nonnull
        public String artName() {
            return artName;
        }

        @Nonnull
        public static Tier of(@Nonnull DieType type) {
            return switch (type) {
                case D100 -> GRAND;
                case D20 -> MAJOR;
                default -> MINOR;
            };
        }
    }

    @Nonnull
    static String capitalise(@Nonnull String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
