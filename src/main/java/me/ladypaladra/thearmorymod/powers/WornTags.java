package me.ladypaladra.thearmorymod.powers;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers whether a worn armour piece carries an on or off power, declared as an item tag in
 * the same shape the parry system reads: {@code "ArmoryWaterBreathing": []}.
 *
 * <p>Powers with a strength are carried as stats instead, so the engine sums them and prints
 * them on the tooltip. A tag is for a power that is simply on while the piece is worn.</p>
 */
public final class WornTags {

    public static final String WATER_BREATHING = "ArmoryWaterBreathing";

    public static final String SPORE_BURST = "ArmorySporeBurst";

    /**
     * Unlike the others this tag carries values: the root interactions to run when the wearer
     * lands. See {@link LandingTrigger}.
     */
    public static final String ON_LANDING = "ArmoryOnLanding";

    private WornTags() {
    }

    public static boolean wears(@Nullable InventoryComponent.Armor armor, String tag) {
        if (armor == null) {
            return false;
        }

        ItemContainer container = armor.getInventory();
        if (container == null) {
            return false;
        }

        for (short slot = 0; slot < container.getCapacity(); slot++) {
            if (hasTag(container.getItemStack(slot), tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The values of a tag on each worn piece that carries it, in slot order. Returns an empty list
     * when nothing worn carries the tag, so the common case allocates nothing.
     */
    public static List<String[]> wornValues(@Nullable InventoryComponent.Armor armor, String tag) {
        if (armor == null) {
            return List.of();
        }

        ItemContainer container = armor.getInventory();
        if (container == null) {
            return List.of();
        }

        List<String[]> values = null;
        for (short slot = 0; slot < container.getCapacity(); slot++) {
            String[] pieceValues = tagValues(container.getItemStack(slot), tag);
            if (pieceValues != null) {
                if (values == null) {
                    values = new ArrayList<>();
                }
                values.add(pieceValues);
            }
        }
        return values == null ? List.of() : values;
    }

    static boolean hasTag(@Nullable ItemStack stack, String tag) {
        AssetExtraInfo.Data data = data(stack);
        return data != null && data.getRawTags().containsKey(tag);
    }

    @Nullable
    private static String[] tagValues(@Nullable ItemStack stack, String tag) {
        AssetExtraInfo.Data data = data(stack);
        return data != null ? data.getRawTags().get(tag) : null;
    }

    @Nullable
    private static AssetExtraInfo.Data data(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        Item item = stack.getItem();
        return item != null ? item.getData() : null;
    }
}
