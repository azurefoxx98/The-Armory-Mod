package me.ladypaladra.thearmorymod.scribing;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private static final List<String> AUTHORED = List.of(
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

    /**
     * Colours this server will not draw, because a live item quality already uses them.
     *
     * <p>Empty until {@link #resolveAgainstQualities} runs, so anything that reads the
     * palette without a running server, unit tests above all, sees the authored set. It is
     * replaced whole rather than mutated, and it is volatile, because startup resolves it on
     * the boot thread and every later read happens on another one.</p>
     */
    private static volatile Set<String> withdrawn = Set.of();

    private ScribingPalette() {
    }

    /**
     * The colours this server actually offers, in legend order.
     */
    @Nonnull
    public static List<String> allowed() {
        Set<String> gone = withdrawn;
        if (gone.isEmpty()) {
            return AUTHORED;
        }
        List<String> live = new ArrayList<>(AUTHORED.size());
        for (String entry : AUTHORED) {
            if (!gone.contains(normalise(entry))) {
                live.add(entry);
            }
        }
        return List.copyOf(live);
    }

    public static boolean isAllowed(@Nonnull String hex) {
        String key = normalise(hex);
        if (withdrawn.contains(key)) {
            return false;
        }
        for (String entry : AUTHORED) {
            if (normalise(entry).equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a colour this mod draws has been withdrawn on this server.
     *
     * <p>Asked by the callers that paint a colour we never offered the player, so they can
     * fall back instead of impersonating a rarity. {@link #isAllowed} cannot answer for them
     * because it is false for those colours either way.</p>
     */
    public static boolean isWithdrawn(@Nonnull String hex) {
        return withdrawn.contains(normalise(hex));
    }

    /**
     * Withdraws every colour this mod draws that a live item quality already uses, and
     * reports what went.
     *
     * <p>The rule this enforces is that no text we draw may look like an item rarity. A
     * server owner can edit a quality and another mod can register one, so the set has to be
     * read at startup rather than assumed.</p>
     *
     * <p>This used to throw, and that was the wrong severity. The colours are cosmetic, the
     * trigger is content we do not control, and the whole mod went down with them. A player
     * reported exactly that on 2026-08-21: the mod refused to load and the message was about
     * colours, on a server where something else had registered a quality. Nothing in the
     * eleven shipped qualities or the engine default collides, which is why it only happened
     * to one person. Withdrawing the colour keeps the rule intact, because a colour we do not
     * offer cannot be used to impersonate anything, and it costs that server one swatch
     * instead of the recipes, the armour, the benches and the parry.</p>
     *
     * <p>The count of live qualities is not written down here. A docstring on this method
     * once listed all eleven colours by hand, the live count moved to twelve, and the stale
     * list went unnoticed until a startup log line was read on 2026-08-11. The twelfth is
     * {@code ItemQuality.DEFAULT_ITEM_QUALITY}, id {@code "Default"}, which engine code
     * registers without a JSON file, so enumerating gamedata alone under-counts the set this
     * guard protects against. {@code ScribingModule.resolvePalette} logs the live set on every
     * boot, which is the one home for that fact.</p>
     *
     * @param qualityTextColors text colours of every item quality registered on this server
     * @param alsoDrawn         colours this mod renders outside the player-facing palette
     * @return the withdrawn colours in authored order, empty when nothing collided
     */
    @Nonnull
    public static List<String> resolveAgainstQualities(
            @Nonnull Collection<String> qualityTextColors,
            @Nonnull Collection<String> alsoDrawn
    ) {
        Set<String> live = new LinkedHashSet<>();
        for (String color : qualityTextColors) {
            if (color != null) {
                live.add(normalise(color));
            }
        }

        List<String> ours = new ArrayList<>(AUTHORED);
        ours.addAll(alsoDrawn);

        Set<String> hits = new LinkedHashSet<>();
        List<String> reported = new ArrayList<>();
        for (String ourColor : ours) {
            if (ourColor == null) {
                continue;
            }
            String key = normalise(ourColor);
            // hits.add guards the report as well as the set, so a colour listed twice across
            // the palette and the also-drawn set is withdrawn once and warned about once.
            if (live.contains(key) && hits.add(key)) {
                reported.add(ourColor);
            }
        }

        withdrawn = Set.copyOf(hits);
        return List.copyOf(reported);
    }

    /**
     * Compares the way the palette has always compared, case-insensitively. The authored
     * list mixes cases and a quality colour arrives lower case from the engine's own bytes,
     * so a case-sensitive match would miss {@code #E8A93B} and let it through.
     */
    @Nonnull
    private static String normalise(@Nonnull String hex) {
        return hex.trim().toLowerCase(Locale.ROOT);
    }
}
