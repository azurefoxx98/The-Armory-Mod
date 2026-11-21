package me.ladypaladra.thearmorymod.dice;

import javax.annotation.Nonnull;

/**
 * One colour a die comes in: the picture every model of the die wears, and the light its shows are drawn in.
 *
 * <p>A colour and its light are two names because the two-tones cast white's light: their shows are white's, while
 * their pictures are their own.</p>
 *
 * @param key         the colour's word in the data, such as {@code red} or {@code blackwhite}
 * @param art         its word in item ids and file names, such as {@code Red} or {@code BlackWhite}
 * @param light       the word naming its effects and the rising number's pictures, such as {@code White}
 * @param glow        the light it casts, as red, green and blue from 0 to 255
 * @param texture     the picture every model of a die in this colour wears
 * @param darkTexture the same picture with every awake mark put out, worn while the Veil holds the die
 */
public record DieColour(@Nonnull String key, @Nonnull String art, @Nonnull String light, @Nonnull DieShape.Rgb glow,
                        @Nonnull String texture, @Nonnull String darkTexture) {
}
