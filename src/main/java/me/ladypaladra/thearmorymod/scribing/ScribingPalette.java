package me.ladypaladra.thearmorymod.scribing;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The Scribing Table offers these tooltip-safe colours. It rejects arbitrary hex because
 * matching the card background, separator, or a rarity would allow deceptive text.
 */
public final class ScribingPalette {

    // Every entry comes from Hytale and has proven legible on the tooltip card. This is
    // also the order used by the screen legend, so players see exactly what the parser
    // permits. We exclude #696969 because it is the default description colour and adds
    // nothing. We exclude #25262c because it matches the separator and near-background
    // colour, which makes text invisible. We exclude #838383 because it imitates the ID
    // line.
    private static final List<String> ALLOWED = List.of(
            "#ffffff",
            "#E8A93B",
            "#bca57a",
            "#4ade80",
            "#1eab1e",
            "#05c09a",
            "#7a9cc6",
            "#ccaa00",
            "#be1717",
            "#b61a82"
    );

    private ScribingPalette() {
    }

    @Nonnull
    public static List<String> allowed() {
        return ALLOWED;
    }

    public static boolean isAllowed(@Nonnull String hex) {
        return ALLOWED.stream().anyMatch(entry -> entry.equalsIgnoreCase(hex));
    }

    /**
     * Rejects any colour drawn by this mod if it imitates an item rarity. A server owner
     * can edit the palette later, so checking the live quality colours at startup prevents
     * that collision from being reintroduced accidentally.
     *
     * <p>This docstring used to list all eleven quality colours by hand, and that list went
     * stale. That is why the list is gone. The count moved to twelve, and nobody noticed
     * until a startup log line was read on 2026-08-11. Never copy a value into a comment
     * when it already has a source of truth. {@code ScribingModule.verifyPalette} logs the
     * live set on every boot, giving it one home that cannot go stale. The set is not just
     * gamedata. Measurements taken on 2026-08-11 found exactly eleven assets under
     * {@code Server/Item/Qualities/} in the shipped archive. The twelfth is
     * {@code ItemQuality.DEFAULT_ITEM_QUALITY}, id {@code "Default"}, which engine code
     * registers without a JSON file. Enumerating the archive would therefore have
     * under-counted the set this assertion protects against.</p>
     */
    public static void assertNoQualityCollision(@Nonnull Collection<String> qualityTextColors) {
        assertNoQualityCollision(qualityTextColors, List.of());
    }

    /**
     * Performs the same check for colours this mod paints but does not offer to players.
     *
     * <p>This overload closes a hole that the seal marker opened in the original check. The
     * tooltip's "Sealed" line uses a colour deliberately kept out of {@link #ALLOWED}
     * because it is the mod's own line, not player input. That also made it the only colour
     * we painted onto an item tooltip without verifying it. If a future game version added
     * a rarity at that value, our seal would quietly impersonate it. The rule has always
     * been that no text we draw may look like a rarity, while the palette only covered most
     * of what we draw.
     *
     * @param alsoDrawn colours this mod renders outside the player-facing palette
     */
    public static void assertNoQualityCollision(
            @Nonnull Collection<String> qualityTextColors,
            @Nonnull Collection<String> alsoDrawn
    ) {
        List<String> ours = new ArrayList<>(ALLOWED);
        ours.addAll(alsoDrawn);
        for (String ourColor : ours) {
            for (String qualityColor : qualityTextColors) {
                if (qualityColor != null && ourColor.equalsIgnoreCase(qualityColor)) {
                    throw new IllegalStateException(
                            "Quality colour " + qualityColor + " collides with the colour "
                                    + ourColor + " that this mod draws on item text."
                    );
                }
            }
        }
    }
}
