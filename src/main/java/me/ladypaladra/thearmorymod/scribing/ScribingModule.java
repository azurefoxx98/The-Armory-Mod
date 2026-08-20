package me.ladypaladra.thearmorymod.scribing;

import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import me.ladypaladra.thearmorymod.scribing.page.ScribingPage;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ScribingModule {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private ScribingModule() {
    }

    public static void register(@Nonnull JavaPlugin plugin) {
        // The table has no block entity because it keeps no per-block state. An optional
        // cost comes out of the player's inventory at write time rather than being stocked
        // in the bench, so there is nothing to persist or migrate. The shipped
        // Bench_Memories demonstrates this setup with a placed custom-page bench that
        // declares neither BlockType.Bench nor BlockEntity. We don't use registerSimple
        // because it has no engine callers and discards the interaction context.
        OpenCustomUIInteraction.registerCustomPageSupplier(
                plugin,
                ScribingPage.class,
                ScribingConfig.PAGE_ID,
                (OpenCustomUIInteraction.CustomPageSupplier) (ref, accessor, playerRef, context)
                        -> new ScribingPage(playerRef)
        );
        PermissionsModule.registerPermission(ScribingConfig.BYPASS_PERMISSION);
    }

    public static void verifyPalette() {
        Map<String, ItemQuality> qualities = ItemQuality.getAssetMap().getAssetMap();
        List<String> colors = new ArrayList<>();
        List<String> named = new ArrayList<>();
        for (Map.Entry<String, ItemQuality> entry : qualities.entrySet()) {
            ItemQuality quality = entry.getValue();
            if (quality == null) {
                continue;
            }
            Color color = quality.getTextColor();
            if (color != null) {
                String hex = String.format(
                        "#%02x%02x%02x",
                        color.red & 0xFF,
                        color.green & 0xFF,
                        color.blue & 0xFF
                );
                colors.add(hex);
                named.add(entry.getKey() + " " + hex);
            }
        }

        // We log both counts because they answer different questions. Only one of them is
        // reassuring on its own. A check over an empty asset store passes silently and
        // looks exactly like a check that examined everything, so the log must say how
        // much it actually inspected. Read the actual count from the boot log. No expected
        // number is written here because a number in a comment is what goes stale.
        LOGGER.atInfo().log(
                "Scribing palette check examined %d item qualities carrying %d colours.",
                qualities.size(),
                colors.size()
        );
        if (qualities.isEmpty()) {
            LOGGER.atWarning().log(
                    "Scribing palette check was skipped because no item qualities were loaded."
            );
            return;
        }

        // Log the set itself, not only its size. This fixes a real miss. The docstring on
        // assertNoQualityCollision once listed all eleven colours by hand. The live count
        // moved to twelve, but that stale list went unnoticed until someone read a startup
        // line on 2026-08-11. A count proves the check ran. The names and colours show what
        // it ran against, which the stale comment only appeared to answer. This log line is
        // now the only home for that fact.
        //
        // If the number looks surprising, the shipped archive contains eleven quality
        // assets under Server/Item/Qualities/. The twelfth is
        // ItemQuality.DEFAULT_ITEM_QUALITY, id "Default". Engine code registers it without
        // a JSON file, so enumerating gamedata alone under-counts what must be checked.
        LOGGER.atInfo().log("Scribing palette checked against: %s", String.join(", ", named));

        // Include the seal marker because this mod paints that colour onto an item tooltip
        // while deliberately keeping it out of the player-facing palette. That choice had
        // quietly left it outside the one check that stops our text from impersonating a
        // rarity. The rule covers every colour we draw, not only the ones we offer.
        ScribingPalette.assertNoQualityCollision(
                colors,
                List.of(ScribingWrite.SEAL_MARK_COLOR)
        );
    }

    /**
     * Checks the one value that {@link ScribingSeal} has to duplicate.
     *
     * <p>The seal's document half restores the item's {@code ItemDisplay} entry when staff
     * break a marked seal, so it needs the key by name. It cannot read
     * {@code ItemDisplayMetadata.KEY} without loading an engine type inside the unit tests.
     * Those tests are the whole reason the document half exists, and an engine static
     * initializer has already died off-server here. So we copy the value and check it in
     * this method, where a real server is running and the engine's own constant is safe to
     * read.</p>
     *
     * <p>Read {@code KEYED_CODEC.getKey()}, not {@code ItemDisplayMetadata.KEY}. That
     * distinction is the whole value of this method. {@code KEY} is a
     * {@code public static final String}, so javac inlines it into our jar at build time.
     * Comparing against it would check our own copy against our own copy. Such a gate could
     * never fail and would falsely look like coverage, which is worse than having no gate.
     * {@code getKey()} is a virtual call on the codec instance built by the running engine,
     * so it reports the key this server actually stores under. If a game update moved the
     * key, the next boot would catch it. In the measured 0.5.7 jar, the codec is constructed
     * as {@code new KeyedCodec("ItemDisplay", CODEC)}, which is also the field's
     * ConstantValue. The two agree today, and this check exists for the day they don't.</p>
     *
     * <p>This throws instead of logging. If the key ever diverges, breaking a seal would
     * silently write the restored display under a key that nothing reads. The player's
     * item would still show our marker, and their own text would be gone. Refusing to boot
     * is the right response. Unlike the seal's runtime paths, this method runs at startup
     * rather than inside the engine tick, so it cannot disconnect anybody.</p>
     */
    public static void verifyDisplayKey() {
        String engineKey = ItemDisplayMetadata.KEYED_CODEC.getKey();
        if (!engineKey.equals(ScribingSeal.displayKey())) {
            throw new IllegalStateException(
                    "ItemDisplayMetadata.KEY is \"" + engineKey + "\" but ScribingSeal copies \""
                            + ScribingSeal.displayKey() + "\". Breaking a seal would not restore the"
                            + " player's own name and description. Update ScribingSeal.DISPLAY_KEY."
            );
        }
        LOGGER.atInfo().log("Scribing seal display key check passed against \"%s\".", engineKey);
    }
}
