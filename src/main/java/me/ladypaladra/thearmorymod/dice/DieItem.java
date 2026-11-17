package me.ladypaladra.thearmorymod.dice;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * One dice item: which die it is and the colour of each physical die it throws, in the order of the die type's
 * shapes. Every colour of a die is its own item, because an item's held model and icon come from its own file.
 *
 * @param itemId  the item's id, such as {@code Armory_Dice_D6_Red}
 * @param type    the die it is
 * @param colours one colour per shape of {@code type}, such as the yellow tens and pink units of the crafted D100
 */
public record DieItem(@Nonnull String itemId, @Nonnull DieType type, @Nonnull List<DieColour> colours) {

    /**
     * The colour of the physical die thrown as {@code part}, its place in the type's shapes.
     */
    @Nonnull
    public DieColour colour(int part) {
        return colours.get(part);
    }

    /**
     * The colour of the physical die of shape {@code shapeKey}, such as {@code D100_Units}.
     */
    @Nonnull
    public DieColour colourOf(@Nonnull String shapeKey) {
        int part = type.shapeKeys().indexOf(shapeKey);
        return colours.get(Math.max(0, part));
    }
}
