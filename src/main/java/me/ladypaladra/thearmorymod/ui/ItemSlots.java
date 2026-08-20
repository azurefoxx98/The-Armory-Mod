package me.ladypaladra.thearmorymod.ui;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class ItemSlots {

    private ItemSlots() {
    }

    /**
     * One inventory stack, rewritten into the only shape a client will accept in a grid.
     * <p>
     * Keep the metadata null on the wire. This was the single most expensive lesson from
     * building this screen. Retail clients decode each slot's
     * {@link ItemStack} metadata into a fixed {@code ClientItemMetadata} shape, and mod
     * BSON throws before the UI loads, which the client reports as
     * {@code CustomUI Set command couldn't set value}, then disconnect the player.
     * A scribing bench's stacks are exactly the ones carrying an {@code ItemDisplay} bag,
     * so passing them straight through is guaranteed to hit it. Two shipping mods already
     * solved this the same way, and their solution is copied here rather than reinvented.
     * <p>
     * Nothing visible is lost. Id, quantity, durability and max durability are all
     * first-class fields on the constructor rather than metadata, so the native tooltip
     * still shows the real durability and the grid still draws the real stack size. The
     * inscribed description rides on the slot's own Description, which the native tooltip
     * reads, so there is one tooltip and it is the game's own. Name stays unset because it
     * is the field that collapses the native card.
     * <p>
     * Description is only set for a stack that actually carries one. Left null, the client
     * falls back to the item's own description, which keeps every ordinary item's tooltip
     * exactly as the game writes it, styling included.
     * <p>
     * Do not set a colour here. This is a measured rule, not a style choice. In game on
     * 2026-08-09, the first version disconnected the tester. It
     * marked the selected slot with {@code setOverlay(new PatchStyle().setColor(...))}
     * using the colour grammar {@code #e8a93b(0.32)}. The client refused the whole
     * {@code .Slots} write:
     * <pre>
     * CustomUI Set command couldn't set value. Selector: #PickerGrid.Slots
     *   -> Failed to convert JSON value (String) to specified type (UInt32Color)
     * </pre>
     * The parenthesised alpha is the markup parser's grammar and the runtime JSON
     * converter does not accept it. The engine's own runtime patch colour is
     * {@code TriggerVolumeInspectorPage.colorToHex}, plain
     * {@code String.format("#%02X%02X%02X", r, g, b)}: six digits, no alpha. So an opaque
     * colour is the only proven form, an opaque wash would hide the item icon it is
     * marking, and eight digit hex is untested on this field. Selection therefore rides on
     * {@code IsItemIncompatible}, a boolean that cannot fail a conversion.
     * <p>
     * Never call {@code setBackground} either. It silently kills the whole grid, as
     * measured by CrucibleUIProbe and recorded in its rules list.
     */
    @Nonnull
    public static ItemGridSlot display(
            @Nonnull ItemStack stack,
            boolean clickable,
            boolean dimmed,
            @Nullable String translatedDescription
    ) {
        ItemGridSlot slot = new ItemGridSlot(new ItemStack(
                stack.getItemId(),
                stack.getQuantity(),
                stack.getDurability(),
                stack.getMaxDurability(),
                null
        ));
        // Required for a slot to accept a press at all, which is what hyui's
        // ItemGridBuilder does for every slot it makes clickable. The desk's target slot
        // passes false: nothing listens to it, and a slot that looks pressable but does
        // nothing is worse than one that plainly is not.
        slot.setActivatable(clickable);

        // Everything the player did not choose steps back, so the chosen stack is the only
        // one at full strength. Trail of Orbis' StonePickerPage marks its selection exactly
        // this way, which is the whole reason this field is trusted over a colour.
        slot.setItemIncompatible(dimmed);

        // Never set Name here. In-game measurement on 2026-08-10 showed that Name
        // collapses the tooltip to a bare box, losing quality, id, slot, stat rows and
        // durability. Description alone preserves the complete native card, and the client
        // parses its markup, so only the instance description is supplied here. Every run,
        // including an unstyled one, must remain wrapped. Bare text attaches to the parsed
        // tree's root and renders before tagged children, which reorders the inscription.
        String markup = TooltipMarkup.wrap(TooltipMessages.flatten(stack.getDisplayDescription(), translatedDescription));
        if (!markup.isEmpty()) {
            slot.setDescription(markup);
        }
        return slot;
    }

    @Nullable
    public static String resolveDescriptionKey(
            @Nonnull ItemStack stack,
            @Nonnull PlayerRef playerRef
    ) {
        Message description = stack.getDisplayDescription();
        return description != null && description.getMessageId() != null
                ? I18nModule.get().getMessage(
                        playerRef.getLanguage(), description.getMessageId()
                )
                : null;
    }
}
