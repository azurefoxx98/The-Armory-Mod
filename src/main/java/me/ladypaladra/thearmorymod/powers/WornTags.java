package me.ladypaladra.thearmorymod.powers;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;

import javax.annotation.Nullable;

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

    static boolean hasTag(@Nullable ItemStack stack, String tag) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        Item item = stack.getItem();
        if (item == null) {
            return false;
        }

        AssetExtraInfo.Data data = item.getData();
        return data != null && data.getRawTags().containsKey(tag);
    }
}
