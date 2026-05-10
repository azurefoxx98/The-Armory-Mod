package me.ladypaladra.thearmorymod.tailoring.service;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import me.ladypaladra.thearmorymod.tailoring.assets.ArmorVariantStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class ArmorTailoringProcessor {

    private ArmorTailoringProcessor() {
    }

    @Nullable
    public static TailoringResult createNextVariant(@Nullable ItemStack inputStack) {
        if (ItemStack.isEmpty(inputStack)) {
            return null;
        }

        String currentItemId = clean(inputStack.getItemId());

        if (currentItemId.isEmpty()) {
            return null;
        }

        List<String> variantGroup = collectValidArmorVariantGroup(currentItemId);

        if (variantGroup.size() < 2) {
            return null;
        }

        int currentIndex = variantGroup.indexOf(currentItemId);

        if (currentIndex < 0) {
            return null;
        }

        int nextIndex = wrapIndex(currentIndex + 1, variantGroup.size());
        String nextItemId = variantGroup.get(nextIndex);

        Item nextItem = Item.getAssetMap().getAsset(nextItemId);

        if (nextItem == null || nextItem == Item.UNKNOWN || nextItem.getArmor() == null) {
            return null;
        }

        ItemStack outputStack = createVariantStack(nextItemId, nextItem, inputStack);

        return new TailoringResult(currentItemId, nextItemId, outputStack);
    }

    public static boolean canTailor(@Nullable ItemStack inputStack) {
        return createNextVariant(inputStack) != null;
    }

    @Nonnull
    private static List<String> collectValidArmorVariantGroup(@Nonnull String currentItemId) {
        List<String> rawGroup = ArmorVariantStore
                .getCatalog()
                .findVariantGroup(currentItemId);

        if (rawGroup.isEmpty()) {
            return List.of();
        }

        List<String> result = new ArrayList<>(rawGroup.size());

        for (String itemIdRaw : rawGroup) {
            String itemId = clean(itemIdRaw);

            if (itemId.isEmpty()) {
                continue;
            }

            Item item = Item.getAssetMap().getAsset(itemId);

            if (item == null || item == Item.UNKNOWN) {
                continue;
            }

            if (item.getArmor() == null) {
                continue;
            }

            result.add(itemId);
        }

        return result;
    }

    @Nonnull
    private static ItemStack createVariantStack(
            @Nonnull String newItemId,
            @Nonnull Item newItem,
            @Nonnull ItemStack oldStack
    ) {
        int quantity = 1;

        double newMaxDurability = newItem.getMaxDurability();
        double oldMaxDurability = oldStack.getMaxDurability();
        double newDurability;

        if (newMaxDurability <= 0.0D || oldMaxDurability <= 0.0D) {
            newDurability = newMaxDurability;
        } else {
            double ratio = oldStack.getDurability() / oldMaxDurability;
            newDurability = Math.clamp(ratio * newMaxDurability, 0.0D, newMaxDurability);
        }

        ItemStack newStack = new ItemStack(
                newItemId,
                quantity,
                newDurability,
                newMaxDurability,
                oldStack.getMetadata()
        );

        newStack.setOverrideDroppedItemAnimation(oldStack.getOverrideDroppedItemAnimation());

        return newStack;
    }

    private static int wrapIndex(int index, int size) {
        if (size <= 0) {
            return 0;
        }

        int result = index % size;

        return result < 0 ? result + size : result;
    }

    @Nonnull
    private static String clean(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    public record TailoringResult(
            @Nonnull String previousItemId,
            @Nonnull String nextItemId,
            @Nonnull ItemStack outputStack
    ) {
    }
}