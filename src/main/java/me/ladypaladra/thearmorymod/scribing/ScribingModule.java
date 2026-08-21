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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /**
     * Settles which Scribing colours this server will offer, and never fails.
     *
     * <p>Named for what it does. It used to be called verifyPalette and it used to throw,
     * which took the whole mod offline on any server whose other content happened to use one
     * of our colours. The caller still runs it inside a boundary that rethrows, so the name
     * has to say plainly that nothing here escapes, or a later reader will assume the old
     * behaviour is still available to lean on.</p>
     */
    public static void resolvePalette() {
        try {
            resolvePaletteOrThrow();
        } catch (Throwable throwable) {
            // The claim that this never fails has to be true, not merely intended. Reading the
            // asset map is a call into engine internals and the docstring above promises the
            // caller that nothing escapes, so the promise is enforced here rather than assumed.
            //
            // Nothing is withdrawn when the read fails. The alternative, withdrawing the whole
            // palette because we could not check it, would disable every colour on a server
            // over a fault that has nothing to do with its content. What is left unguarded is
            // cosmetic and needs a player to deliberately pick the one colour that matches a
            // rarity, which is a smaller harm than the feature going dark for everyone.
            LOGGER.atSevere().withCause(throwable).log(
                    "The Scribing palette check could not run, so no colour was withdrawn."
                            + " The Armory started normally."
            );
        }
    }

    private static void resolvePaletteOrThrow() {
        Map<String, ItemQuality> qualities = ItemQuality.getAssetMap().getAssetMap();
        List<String> colors = new ArrayList<>();
        List<String> named = new ArrayList<>();
        // Which qualities use a given colour, so a withdrawal can name the one it collided
        // with. Without this the warning would print a hex code and leave the server owner
        // to go looking for whatever registered it.
        Map<String, List<String>> byColor = new LinkedHashMap<>();
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
                byColor.computeIfAbsent(hex.toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                        .add(entry.getKey());
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
        // resolveAgainstQualities once listed all eleven colours by hand. The live count
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
        List<String> withdrawn = ScribingPalette.resolveAgainstQualities(
                colors,
                List.of(ScribingWrite.SEAL_MARK_COLOR)
        );
        if (withdrawn.isEmpty()) {
            return;
        }

        // One line per colour, naming the quality, because this is the only signal a server
        // owner gets that a swatch on the legend will be refused. It is a warning and not an
        // error on purpose. The mod is running and everything except that one colour works.
        for (String color : withdrawn) {
            List<String> owners = byColor.getOrDefault(color.toLowerCase(Locale.ROOT), List.of());
            LOGGER.atWarning().log(
                    "Scribing withdrew %s because item quality %s already draws text in it."
                            + " The Armory started normally. On this server that colour cannot be"
                            + " chosen at the Scribing Table, and text already written in it stays"
                            + " readable but can no longer be edited.",
                    color,
                    String.join(", ", owners)
            );
        }
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
     * <p>This does not throw, and that is the whole point of it. A plugin that throws out of
     * start is marked FAILED, and PluginManager turns any failed plugin into a
     * shutdownServer with MOD_ERROR, so the entire server refuses to boot rather than just
     * this mod. The trigger here would be a game update renaming the key, which would hit
     * every server running The Armory on the same day. Taking all of them down over one
     * feature is not a trade this mod gets to make on an owner's behalf.</p>
     *
     * <p>Sealing switches off instead. Plain inscribing keeps working because it goes through
     * the engine's own typed display API and never touches the raw key. The two operations
     * that would lose a player's text, sealing and breaking a seal, are refused at the page
     * with a message. Nothing is ever written under a key that nothing reads, which was the
     * only thing the throw actually bought.</p>
     */
    public static void resolveDisplayKey() {
        try {
            resolveDisplayKeyOrThrow();
        } catch (Throwable throwable) {
            // Same guarantee as resolvePalette. Sealing is switched off because a fault we
            // cannot explain is not a state to seal items in.
            ScribingSeal.resolveDisplayKey(null);
            LOGGER.atSevere().withCause(throwable).log(
                    "The Scribing display key check could not run, so sealing is off."
                            + " Inscribing still works and The Armory started normally."
            );
        }
    }

    private static void resolveDisplayKeyOrThrow() {
        String engineKey;
        try {
            engineKey = ItemDisplayMetadata.KEYED_CODEC.getKey();
        } catch (Throwable throwable) {
            // Reading the codec is a virtual call into engine internals. If it fails we know
            // nothing about the key, and not knowing is exactly the case where sealing must
            // not run.
            ScribingSeal.resolveDisplayKey(null);
            LOGGER.atSevere().withCause(throwable).log(
                    "Could not read the engine's ItemDisplay key, so Scribing sealing is off."
                            + " Inscribing still works and The Armory started normally."
            );
            return;
        }

        ScribingSeal.resolveDisplayKey(engineKey);

        if (!ScribingSeal.isDisplayKeyTrusted()) {
            LOGGER.atSevere().log(
                    "The engine's ItemDisplay key is \"%s\" but this mod copies \"%s\". Sealing"
                            + " and breaking seals are off, because they would not restore a"
                            + " player's own name and description. Inscribing still works and The"
                            + " Armory started normally. Update ScribingSeal.DISPLAY_KEY.",
                    engineKey,
                    ScribingSeal.displayKey()
            );
            return;
        }

        LOGGER.atInfo().log("Scribing seal display key check passed against \"%s\".", engineKey);
    }
}
