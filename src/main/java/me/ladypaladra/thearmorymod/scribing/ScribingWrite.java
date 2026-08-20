package me.ladypaladra.thearmorymod.scribing;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import org.bson.BsonDocument;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns validated text into engine messages and handles atomic item writes.
 */
public final class ScribingWrite {

    /**
     * Shared limit for every walk of an engine message tree in this mod. Our parser
     * flattens its own trees to one level, so this only affects trees written by other
     * code and keeps a pathological tree from overflowing the stack inside the engine tick.
     */
    public static final int MAX_TREE_DEPTH = 16;

    private ScribingWrite() {
    }

    public record Existing(@Nullable String name, @Nullable String description, boolean foreign) {
    }

    /**
     * Do not call Message.parse on player input. It decodes JSON rather than parsing
     * markup, so this method keeps player text raw and builds the tree itself.
     *
     * <p>Bold has no effect on the name. The tooltip's Label #Name declares
     * RenderBold: true, and instance metadata can add a style but cannot remove a
     * markup style. The screen leaves bold off the name, while this general conversion
     * still preserves the validated runs exactly.</p>
     */
    @Nullable
    public static Message toMessage(@Nonnull List<ScribingMarkup.StyledRun> runs) {
        Message root = null;
        for (ScribingMarkup.StyledRun run : runs) {
            Message message = Message.raw(run.text());
            if (run.color() != null) {
                message = message.color(run.color());
            }
            if (run.bold()) {
                message = message.bold(true);
            }
            if (run.italic()) {
                message = message.italic(true);
            }

            if (root == null) {
                root = message;
            } else {
                root = root.insert(message);
            }
        }
        return root;
    }

    /**
     * Reads stored text back into the markup the player originally typed. When an inscribed
     * item returns to the bench, its boxes are refilled exactly as they were.
     *
     * <p>This must walk the whole tree. Calling getRawText alone loses data. A styled name
     * is a Message tree, so {@code <color is="#E8A93B">Dawn</color>breaker} is stored with
     * "Dawn" at the root and "breaker" in a child. Calling getRawText on that root returns
     * only "Dawn". When the code read it that way, the box showed "Dawn", "breaker" and
     * every style disappeared, and the next press of Inscribe wrote the truncated value
     * back over the item. That caused silent data loss on an ordinary second visit.</p>
     *
     * <p>If we cannot reproduce some text, we report it as foreign instead of mangling it.
     * This includes another mod's translation key, ICU parameters, a link, monospace,
     * underline, and any colour outside the palette. The caller warns the player and leaves
     * the field blank. The player can then choose to overwrite it instead of having us
     * silently turn someone else's data into an approximation.</p>
     */
    @Nonnull
    public static Existing readExisting(@Nonnull ItemStack stack) {
        ItemDisplayMetadata display = stack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC);
        if (display == null) {
            return new Existing(null, null, false);
        }

        Read name = read(display.getName());
        Read description = read(display.getDescription());
        return new Existing(name.markup(), description.markup(), name.foreign() || description.foreign());
    }

    /**
     * Returns only the visible characters in a message, without styling or markup, so they
     * can be compared with text a player typed.
     *
     * <p>Do not use getAnsiMessage for this. It is a console renderer and inserts ANSI
     * escape codes between runs. A search for "dawnbreaker" would then miss a name where
     * "Dawn" and "breaker" have different colours. getRawText is not suitable either,
     * because it returns only the root run.</p>
     */
    @Nonnull
    public static String plainText(@Nullable Message message) {
        if (message == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        appendPlain(message.getFormattedMessage(), text, 0);
        return text.toString();
    }

    private static void appendPlain(@Nullable FormattedMessage node, @Nonnull StringBuilder text, int depth) {
        if (node == null || depth > MAX_TREE_DEPTH) {
            return;
        }
        if (node.rawText != null) {
            text.append(node.rawText);
        }
        if (node.children != null) {
            for (FormattedMessage child : node.children) {
                appendPlain(child, text, depth + 1);
            }
        }
    }

    private record Read(@Nullable String markup, boolean foreign) {
    }

    @Nonnull
    private static Read read(@Nullable Message message) {
        if (message == null) {
            return new Read(null, false);
        }

        List<ScribingMarkup.StyledRun> runs = new ArrayList<>();
        boolean[] foreign = {false};
        collect(message.getFormattedMessage(), false, false, null, runs, foreign, 0);

        if (foreign[0]) {
            return new Read(null, true);
        }
        if (runs.isEmpty()) {
            return new Read(null, false);
        }
        return new Read(ScribingMarkup.toMarkup(runs), false);
    }

    /**
     * Flattens a stored tree into the same one-level run list produced by the parser.
     * Children inherit every value they do not override, matching the engine's rendering,
     * so trees written by other code still read correctly.
     */
    private static void collect(
            @Nullable FormattedMessage node,
            boolean bold,
            boolean italic,
            @Nullable String color,
            @Nonnull List<ScribingMarkup.StyledRun> runs,
            @Nonnull boolean[] foreign,
            int depth
    ) {
        if (node == null || foreign[0]) {
            return;
        }

        // Our parser flattens trees to one level, so anything deeper came from another mod.
        // The depth limit keeps a pathological tree on an item handed to the player from
        // taking down the server with a stack overflow. Once the tree exceeds that limit,
        // we treat it as foreign just like any other text we cannot reproduce.
        if (depth > MAX_TREE_DEPTH) {
            foreign[0] = true;
            return;
        }

        if (node.messageId != null
                || node.link != null
                || Boolean.TRUE.equals(node.monospace)
                || Boolean.TRUE.equals(node.underlined)
                || node.params != null && !node.params.isEmpty()
                || node.messageParams != null && !node.messageParams.isEmpty()) {
            foreign[0] = true;
            return;
        }

        boolean effectiveBold = node.bold != null ? node.bold : bold;
        boolean effectiveItalic = node.italic != null ? node.italic : italic;
        String effectiveColor = node.color != null ? node.color : color;

        // We cannot offer a colour for editing if we would reject that colour on input.
        // Re-inscribing it would fail validation for text the player never chose.
        if (effectiveColor != null && !ScribingPalette.isAllowed(effectiveColor)) {
            foreign[0] = true;
            return;
        }

        if (node.rawText != null && !node.rawText.isEmpty()) {
            runs.add(new ScribingMarkup.StyledRun(
                    node.rawText,
                    effectiveBold,
                    effectiveItalic,
                    effectiveColor
            ));
        }

        if (node.children != null) {
            for (FormattedMessage child : node.children) {
                collect(child, effectiveBold, effectiveItalic, effectiveColor, runs, foreign, depth + 1);
            }
        }
    }

    /**
     * Replaces ItemDisplay while preserving every unrelated metadata key and both
     * durability values verbatim.
     *
     * <p>This is a pure builder and must never throw. It did throw for about an hour and
     * disconnected a tester in the middle of a play-test. The server log recorded the
     * failure at 2026-08-11 11:49:05. A guard had been added here with the message "a sealed
     * item cannot be inscribed", based on the idea that the call site could not overlook a
     * refusal. That reasoning failed in two ways:</p>
     *
     * <ul>
     *   <li>The callers had not been enumerated. There are three, and the third is
     *       {@code ScribingPage.previewStack}. It is a read-only path that builds a
     *       hypothetical stack for the live tooltip card. Sealing an item succeeds, the
     *       page repaints, the preview asks what the item would look like, and the throw
     *       fires on the success path. The stack was
     *       {@code handleSealConfirm -> repaintAll -> paintDraft -> previewStack -> apply}.</li>
     *   <li>Throwing is the wrong mechanism here. This code runs inside the engine's tick.
     *       The exception appeared as
     *       {@code TickInteractionManagerSystem: Exception while ticking entity
     *       interactions! Removing!}. The engine then removed the player's entity and
     *       disconnected them. On a path the player can reach, an exception is a crash,
     *       not a refusal.</li>
     * </ul>
     *
     * <p>The invariant still holds where the write happens. Both commit paths in
     * {@code ScribingPage} check {@link ScribingSeal#isSealed} and respond through the
     * status strip. Enforce it at the commit, never in this builder.</p>
     */
    @Nonnull
    @SuppressWarnings("deprecation")
    public static ItemStack apply(
            @Nonnull ItemStack original,
            @Nullable Message name,
            @Nullable Message description
    ) {
        BsonDocument metadata = original.getMetadata();

        // Clone the metadata before touching it. getMetadata returns the input stack's own
        // document, so an in-place edit would mutate the item the player is still holding.
        // This exact refusal-path bug shipped in commit 3e8bc91. Cloning also keeps the
        // original and replacement stacks from sharing a live document.
        BsonDocument replacementMetadata = metadata == null ? null : metadata.clone();
        if (replacementMetadata != null) {
            // Removing the record restores the vanilla fallback. Keeping a record with two
            // null fields does not.
            replacementMetadata.remove(ItemDisplayMetadata.KEY);
            if (replacementMetadata.isEmpty()) {
                replacementMetadata = null;
            }
        }

        ItemStack base = new ItemStack(
                original.getItemId(),
                original.getQuantity(),
                original.getDurability(),
                original.getMaxDurability(),
                replacementMetadata
        );
        if (name == null && description == null) {
            return base;
        }
        return base.withMetadata(
                ItemDisplayMetadata.KEYED_CODEC,
                new ItemDisplayMetadata(name, description)
        );
    }

    /**
     * The colour for the seal line on the item's tooltip. It comes from measurements, not
     * an arbitrary choice.
     *
     * <p>It starts from the Scribe Lock's own cyan. In Lady's {@code Scribe_Lock.png} the
     * crystal is exactly {@code #00ffff}. It accounts for 24 of the 442 opaque pixels and is
     * the most common colour in the file. But {@code #00ffff} is used as a text colour in
     * zero of the 355 shipped UI documents, so raw crystal cyan would be the only pure cyan
     * text in the game. That kind of mismatch is how the invented rarity accent bar was
     * spotted as foreign on sight. Hytale does use vivid accent text, specifically
     * {@code #39f493} in its keepsake screen, {@code MemoriesCategory.ui}. Its dark channel
     * is {@code 0x39} instead of {@code 0x00}, which keeps it from reading as terminal green.
     * This value takes the lock's hue and gives it that same structure. It is checked against
     * the live item quality colours at startup by
     * {@code ScribingPalette.assertNoQualityCollision}, which is why no count is repeated
     * here.</p>
     *
     * <p>Do not add this colour to {@link ScribingPalette}. This line belongs to the mod and
     * is never player input. Widening the palette would alter a legend that was tuned by eye
     * and signed off. The colour still goes through the startup rarity check. Leaving it out
     * of the palette once also left it out of the only assertion that prevents text we draw
     * from impersonating an item rarity. That rule applies to everything we paint, not just
     * the palette. See
     * {@code ScribingPalette.assertNoQualityCollision(Collection, Collection)}.</p>
     */
    public static final String SEAL_MARK_COLOR = "#39f4f4";

    private static final String SEAL_MARK_KEY = "server.customUI.scribingPage.tooltipSealed";

    /**
     * Builds the inscription, its tooltip seal line, and the seal before the caller replaces
     * the inventory slot. This prevents the player from ever receiving the inscription
     * without its paid seal.
     *
     * <p>Keep these two writes in this order. The prior display comes from the unmarked
     * write, so breaking the seal restores the player's exact text and none of ours. If it
     * were captured from the marked write or after composing the marker, our seal line would
     * be stored as if the player had typed it. Preventing that failure is the reason this
     * whole mechanism exists.</p>
     *
     * <p>Read the BSON instead of retaining the Message. The encoded sub-document is a
     * snapshot, while {@link Message#insert} may return the same instance on which it was
     * called. A Message reference held across the composition below can therefore change
     * underneath us. Bytes cannot.</p>
     */
    @Nonnull
    @SuppressWarnings("deprecation")
    public static ItemStack applyAndSeal(
            @Nonnull ItemStack original,
            @Nullable Message name,
            @Nullable Message description,
            @Nullable Message tooltipBase,
            @Nonnull String playerName,
            @Nonnull UUID playerUuid,
            long epochMillis
    ) {
        ItemStack plain = apply(original, name, description);
        BsonDocument plainMetadata = plain.getMetadata();
        BsonDocument priorDisplay =
                plainMetadata != null && plainMetadata.isDocument(ScribingSeal.displayKey())
                        ? plainMetadata.getDocument(ScribingSeal.displayKey())
                        : null;

        ItemStack shown = apply(original, name, sealMark(description, tooltipBase));
        return ScribingSeal.seal(shown, playerName, playerUuid, epochMillis, priorDisplay, true);
    }

    /**
     * Adds the seal line below the player's description, with a blank row between them.
     *
     * <p>In-game testing on 2026-08-07 confirmed that an embedded newline renders as a real
     * line break in the tooltip, and {@code Message.raw} carries it intact from server to
     * client. Three lines came out as three lines. {@code ScribingText} already caps the line
     * count because of that result, so the two rows added here are bounded. They use the same
     * mechanism as the player's own text.</p>
     *
     * <p>The line says only "Sealed". It includes neither the name nor the date by design,
     * not because of a limitation. The tooltip tells the player that the item is sealed. To
     * learn who sealed it and when, the player must visit the Scribing Table, which gives the
     * bench a reason to be walked to. An earlier version put the full sentence here, making
     * it feel as though the table had nothing left to say. Do not add the sealer attribution
     * back to this line.</p>
     *
     * <p>The line uses a translation instead of composed English so each viewer sees it in
     * their own language. In-game case 5 of {@code /scrivprobe} confirmed that a stored
     * translation key renders on this exact path.</p>
     *
     * <p>Do not make the line italic. Right alignment was asked for, and it is impossible
     * here rather than declined. Inspection of the 0.5.7 jar found that
     * {@code FormattedMessage} has exactly thirteen data fields, and none controls alignment. Alignment is a {@code LabelStyle.HorizontalAlignment}
     * property on the widget. This widget is the client's own {@code ItemTooltip.ui}
     * description Label, which no mod can restyle. The tooltip's right aligned Durability
     * and Cursed lines are drawn by the client from the item's own state. That is the same
     * barrier the marker encountered when it tried to use the Cursed block. The styling
     * controls that do travel and remain unused are {@code bold} and {@code monospace}.</p>
     *
     * <p>The fallback base prevents a one-word marker from hiding a real description. With
     * nothing written, the marker would otherwise become the entire description and replace
     * whatever the item says about itself. That trade was acceptable when the line contained
     * a sentence, but it is not acceptable now that the line is one word. The caller passes
     * the description the item will show after the write, already checked for renderability.
     * In-game testing on 2026-08-09 showed that if an item's description key has no language
     * entry, the raw key is drawn as literal text. Null means there was nothing worth
     * keeping.</p>
     */
    @Nonnull
    private static Message sealMark(
            @Nullable Message description,
            @Nullable Message tooltipBase
    ) {
        Message mark = Message.translation(SEAL_MARK_KEY).color(SEAL_MARK_COLOR);
        if (description != null) {
            // The blank row is the root's raw text and the mark is its child, so the row is
            // drawn before the line rather than after it. This project measured root text as
            // rendering before children on the markup path. It is safe for insert to mutate
            // this Message because the caller just built it.
            return description.insert(Message.raw("\n\n").insert(mark));
        }
        if (tooltipBase == null) {
            return mark;
        }
        // Use join here, not insert. This is about mutation, not style. tooltipBase came from
        // ItemStack.getDisplayDescription, which may return the item asset's own Message
        // instance. Since insert mutates its receiver, using it here could edit the item type
        // for every player on the server. join creates a new parent and leaves both parts
        // alone. Keep it confined to this branch. Every inscription takes the path above,
        // which preserves the shape already proven in production.
        return Message.join(tooltipBase, Message.raw("\n\n"), mark);
    }

    /**
     * Replaces the slot only after comparing its current stack with expected while holding
     * the container write lock, which makes the stale-target guard atomic. This also bypasses
     * slot filters, so the armour slot filter does not reject renaming worn armour.
     */
    public static boolean commit(
            @Nonnull ItemContainer container,
            short slot,
            @Nonnull ItemStack expected,
            @Nonnull ItemStack replacement
    ) {
        return container.replaceItemStackInSlot(slot, expected, replacement).succeeded();
    }
}
