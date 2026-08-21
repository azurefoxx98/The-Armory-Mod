package me.ladypaladra.thearmorymod.scribing;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.BsonValue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Keeps the sealed-inscription metadata in one place. When the engine moves item
 * metadata to components, the deprecated document access can be migrated here.
 *
 * <p>This class is split into two halves on purpose because that makes it testable. Every
 * rule lives in the document half, which takes and returns a {@link BsonDocument} and never
 * mentions an {@link ItemStack}. The stack half below is plumbing. It reads the document,
 * passes it to the rule, and copies the stack around the result.</p>
 *
 * <p>This isn't a stylistic choice. {@code new ItemStack(...)} calls
 * {@code Item.getAssetStore()} in its constructor. That value is null outside a running
 * server, so any test that builds a stack dies with a NullPointerException, and no
 * classpath fix can cure it. A BsonDocument needs nothing from the engine. This split lets
 * us assert the seal rules instead of relying on a code read. The project rule is to test
 * anything that can be expressed without the engine.</p>
 */
public final class ScribingSeal {

    private static final String KEY = "TheArmoryScribeSeal";
    private static final String PLAYER_NAME_KEY = "name";
    private static final String PLAYER_UUID_KEY = "uuid";
    private static final String EPOCH_MILLIS_KEY = "epochMillis";

    // Both fields are optional and are absent from every seal written before 2026-08-11.
    // read() deliberately requires neither one. A legacy seal is still valid. It put no
    // line on the item's tooltip, so breaking it must leave the display untouched.
    private static final String MARKED_KEY = "marked";
    private static final String PRIOR_DISPLAY_KEY = "priorDisplay";

    /**
     * This deliberately duplicates the literal value of {@code ItemDisplayMetadata.KEY},
     * and startup checks the duplicate instead of trusting it.
     *
     * <p>The document half of this class must remain loadable without a server. That is why
     * the class is split and why its rules can be tested at all. Reading
     * {@code ItemDisplayMetadata.KEY} would load that type inside a unit test. We have
     * already lost time to this exact problem: {@code ItemStack}'s static initializer dies
     * off-server because {@code HytaleLogger} needs its log manager first. A String
     * constant isn't worth that risk.</p>
     *
     * <p>So this value is copied, and {@code ScribingModule} checks the copy against the
     * engine's own constant at startup beside the palette check. This gives the value one
     * testable home plus a mechanical check, instead of one home that tests cannot reach.</p>
     */
    private static final String DISPLAY_KEY = "ItemDisplay";

    private ScribingSeal() {
    }

    public record Seal(
            @Nonnull String playerName,
            @Nonnull UUID playerUuid,
            long epochMillis
    ) {
    }

    // This is the document half. Every rule lives here, and every rule is tested.

    /**
     * The presence of the key is the sealed flag. Even a damaged payload blocks
     * inscription, so a damaged item cannot become an accidental way around the seal.
     */
    public static boolean isSealed(@Nullable BsonDocument metadata) {
        return metadata != null && metadata.containsKey(KEY);
    }

    /**
     * Returns a valid seal, or null if the document is unsealed or the payload is damaged.
     * Use {@link #isSealed(BsonDocument)} when you only need to enforce the seal.
     */
    @Nullable
    public static Seal read(@Nullable BsonDocument metadata) {
        if (metadata == null) {
            return null;
        }

        BsonValue value = metadata.get(KEY);
        if (value == null || !value.isDocument()) {
            return null;
        }

        BsonDocument payload = value.asDocument();
        if (!payload.isString(PLAYER_NAME_KEY)
                || !payload.isString(PLAYER_UUID_KEY)
                || !payload.isInt64(EPOCH_MILLIS_KEY)) {
            return null;
        }

        try {
            return new Seal(
                    payload.getString(PLAYER_NAME_KEY).getValue(),
                    UUID.fromString(payload.getString(PLAYER_UUID_KEY).getValue()),
                    payload.getInt64(EPOCH_MILLIS_KEY).getValue()
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Returns a copy of the document with the new seal and every existing entry. We clone
     * the input instead of editing it because the caller's document belongs to an item
     * somebody is still holding. That exact mistake shipped once already in commit 3e8bc91.
     */
    @Nonnull
    public static BsonDocument sealed(
            @Nullable BsonDocument metadata,
            @Nonnull String playerName,
            @Nonnull UUID playerUuid,
            long epochMillis
    ) {
        return sealed(metadata, playerName, playerUuid, epochMillis, null, false);
    }

    /**
     * Creates the same seal, while also recording that this mod added a line to the item's
     * own tooltip and what the display looked like beforehand.
     *
     * <p>This matters because the description is both what the player sees and where we
     * store their text. That is the whole difficulty with the tooltip marker.
     * {@code ScribingWrite.readExisting} loads the stored description straight back into
     * the edit boxes. If the marker were appended to that description, staff breaking the
     * seal would load the marker into the boxes, and the next Inscribe would save it as if
     * the player had typed it. This has the same shape as the {@code getRawText} bug.
     * Reading stored text and writing it into the widgets are two halves that must change
     * together.</p>
     *
     * <p>We store the complete previous {@code ItemDisplay} sub-document instead of the
     * player's markup so the operation is lossless. Markup can only represent text this mod
     * could have produced. Another mod's translation key, ICU parameters, or off-palette
     * colour would otherwise come back mangled or blank. A BSON sub-document comes back
     * byte for byte, no matter what wrote it.</p>
     *
     * @param priorDisplay the item's {@code ItemDisplay} entry before the marker was added,
     *                     or null when it had none, in which case breaking the seal removes
     *                     the entry entirely and the vanilla name and description return
     */
    @Nonnull
    public static BsonDocument sealed(
            @Nullable BsonDocument metadata,
            @Nonnull String playerName,
            @Nonnull UUID playerUuid,
            long epochMillis,
            @Nullable BsonDocument priorDisplay,
            boolean marked
    ) {
        BsonDocument replacement = metadata == null ? new BsonDocument() : metadata.clone();
        BsonDocument payload = new BsonDocument()
                .append(PLAYER_NAME_KEY, new BsonString(playerName))
                .append(PLAYER_UUID_KEY, new BsonString(playerUuid.toString()))
                .append(EPOCH_MILLIS_KEY, new BsonInt64(epochMillis));
        if (marked) {
            payload.append(MARKED_KEY, BsonBoolean.TRUE);
            if (priorDisplay != null) {
                // Clone this for the same reason as the outer document. The caller's copy
                // belongs to a stack somebody is still holding.
                payload.append(PRIOR_DISPLAY_KEY, priorDisplay.clone());
            }
        }
        replacement.put(KEY, payload);
        return replacement;
    }

    /**
     * Returns a copy of the document without its seal while preserving every other entry,
     * or null if nothing remains. An absent document restores the vanilla fallback, but an
     * empty record does not. Both other writers in this package take the same care.
     *
     * <p>If the seal says it marked the tooltip, this also restores the display exactly as
     * it was before the marker. All three cases are tested. A recorded prior display is
     * restored verbatim. A marked seal without a prior display removes the entry so the
     * vanilla name and description return. A seal written before the marker existed has no
     * flag, so the display remains untouched.</p>
     */
    @Nullable
    public static BsonDocument unsealed(@Nullable BsonDocument metadata) {
        if (metadata == null) {
            return null;
        }
        BsonDocument replacement = metadata.clone();

        BsonValue seal = replacement.get(KEY);
        if (seal != null && seal.isDocument()) {
            BsonDocument payload = seal.asDocument();
            // Check isBoolean before getBoolean because a damaged payload must not throw
            // here. Players can reach this path, and an exception on such a path causes a
            // disconnect rather than a refusal. This feature has already proved that once.
            if (payload.isBoolean(MARKED_KEY) && payload.getBoolean(MARKED_KEY).getValue()) {
                BsonValue prior = payload.get(PRIOR_DISPLAY_KEY);
                if (prior != null && prior.isDocument()) {
                    replacement.put(DISPLAY_KEY, prior.asDocument().clone());
                } else {
                    replacement.remove(DISPLAY_KEY);
                }
            }
        }

        replacement.remove(KEY);
        return replacement.isEmpty() ? null : replacement;
    }

    /**
     * Returns the metadata key this class restores for {@code ItemDisplayMetadata}.
     * {@code ScribingModule} uses it for the startup check. See {@link #DISPLAY_KEY}.
     */
    @Nonnull
    public static String displayKey() {
        return DISPLAY_KEY;
    }

    /**
     * Whether the key above still matches the running engine, decided once at startup.
     *
     * <p>Volatile because startup writes it on the boot thread and every read happens on a
     * player's thread. It starts true so that anything reading it without a running server,
     * the unit tests above all, behaves exactly as it always has.</p>
     */
    private static volatile boolean displayKeyTrusted = true;

    /**
     * False when the engine renamed the display key underneath us.
     *
     * <p>Only sealing and seal breaking read this. Plain inscribing goes through the engine's
     * own typed display API and never touches the raw key, so it stays available.</p>
     */
    public static boolean isDisplayKeyTrusted() {
        return displayKeyTrusted;
    }

    /**
     * Settles whether sealing runs, from the key the running engine actually reports.
     *
     * <p>One decision point taking the engine's answer, rather than a one-way switch. A
     * one-way switch cannot be undone, which makes the behaviour untestable without a
     * test-only backdoor in production code, and a backdoor is a worse thing to ship than
     * this parameter. It also mirrors how the palette resolves, so both startup answers work
     * the same way.</p>
     *
     * <p>Package private so nothing outside this feature can turn sealing on or off. Pass
     * null when the key could not be read at all, which is not a trusted state either.</p>
     */
    static void resolveDisplayKey(@Nullable String engineKey) {
        displayKeyTrusted = DISPLAY_KEY.equals(engineKey);
    }

    // This is the stack half. It is only plumbing, contains no rules, and isn't unit testable.

    @SuppressWarnings("deprecation")
    public static boolean isSealed(@Nonnull ItemStack stack) {
        return isSealed(stack.getMetadata());
    }

    @Nullable
    @SuppressWarnings("deprecation")
    public static Seal read(@Nonnull ItemStack stack) {
        return read(stack.getMetadata());
    }

    /**
     * Returns a replacement stack with the new seal and every existing metadata entry.
     * The input stack and its document remain untouched.
     */
    @Nonnull
    @SuppressWarnings("deprecation")
    public static ItemStack seal(
            @Nonnull ItemStack original,
            @Nonnull String playerName,
            @Nonnull UUID playerUuid,
            long epochMillis
    ) {
        return copyWithMetadata(
                original,
                sealed(original.getMetadata(), playerName, playerUuid, epochMillis)
        );
    }

    /**
     * Does the same while recording the pre-marker display so {@link #breakSeal} can
     * restore it. Only {@code ScribingWrite.applyAndSeal} calls this overload because that
     * is the only place that knows how the display looked before the marker was added.
     */
    @Nonnull
    @SuppressWarnings("deprecation")
    public static ItemStack seal(
            @Nonnull ItemStack original,
            @Nonnull String playerName,
            @Nonnull UUID playerUuid,
            long epochMillis,
            @Nullable BsonDocument priorDisplay,
            boolean marked
    ) {
        return copyWithMetadata(
                original,
                sealed(original.getMetadata(), playerName, playerUuid, epochMillis, priorDisplay, marked)
        );
    }

    /**
     * Returns a replacement stack without its seal and preserves every other entry.
     */
    @Nonnull
    @SuppressWarnings("deprecation")
    public static ItemStack breakSeal(@Nonnull ItemStack original) {
        return copyWithMetadata(original, unsealed(original.getMetadata()));
    }

    @Nonnull
    private static ItemStack copyWithMetadata(
            @Nonnull ItemStack original,
            @Nullable BsonDocument metadata
    ) {
        return new ItemStack(
                original.getItemId(),
                original.getQuantity(),
                original.getDurability(),
                original.getMaxDurability(),
                metadata
        );
    }
}
