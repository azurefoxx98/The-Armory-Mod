package me.ladypaladra.thearmorymod.tailoring.service;

import com.hypixel.hytale.protocol.ItemResourceType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import me.ladypaladra.thearmorymod.tailoring.assets.ArmorVariantConfig;
import me.ladypaladra.thearmorymod.tailoring.assets.ArmorVariantStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ArmorTailoringService {

    public static final String DYE_RESOURCE_TYPE_ID = "Dye";
    public static final String TAILORING_DYE_RESOURCE_TYPE_ID = "TailoringDye";

    private ArmorTailoringService() {
    }

    public static boolean isTailorableArmor(@Nullable ItemStack stack) {
        if (ItemStack.isEmpty(stack)) {
            return false;
        }

        String itemId = clean(stack.getItemId());
        if (itemId.isEmpty()) {
            return false;
        }

        Item item = Item.getAssetMap().getAsset(itemId);
        if (item == null || item == Item.UNKNOWN || item.getArmor() == null) {
            return false;
        }

        ArmorVariantConfig config = ArmorVariantStore.getCatalog().findByItemId(itemId);
        return config != null && !config.isUnknown();
    }

    public static boolean isTailoringFuel(@Nullable ItemStack stack) {
        if (ItemStack.isEmpty(stack)) {
            return false;
        }

        String itemId = clean(stack.getItemId());
        if (itemId.isEmpty()) {
            return false;
        }

        Item item = Item.getAssetMap().getAsset(itemId);
        if (item == null || item == Item.UNKNOWN) {
            return false;
        }

        ItemResourceType[] resourceTypes = item.getResourceTypes();
        if (resourceTypes != null) {
            for (ItemResourceType resourceType : resourceTypes) {
                if (resourceType == null || resourceType.id == null) {
                    continue;
                }

                if (DYE_RESOURCE_TYPE_ID.equals(resourceType.id)
                        || TAILORING_DYE_RESOURCE_TYPE_ID.equals(resourceType.id)) {
                    return true;
                }
            }
        }

        String normalized = itemId.toLowerCase(Locale.ROOT);
        return normalized.contains("dye")
                || normalized.contains("tint")
                || normalized.contains("pigment");
    }

    @Nullable
    public static String findNextVariantId(@Nullable String currentItemIdRaw) {
        String currentItemId = clean(currentItemIdRaw);
        if (currentItemId.isEmpty()) {
            return null;
        }

        List<String> group = findValidVariantGroup(currentItemId);
        if (group.size() < 2) {
            return null;
        }

        int currentIndex = group.indexOf(currentItemId);
        if (currentIndex < 0) {
            return null;
        }

        int nextIndex = wrapIndex(currentIndex + 1, group.size());
        return group.get(nextIndex);
    }

    @Nonnull
    public static List<String> findValidVariantGroup(@Nullable String currentItemIdRaw) {
        String currentItemId = clean(currentItemIdRaw);
        if (currentItemId.isEmpty()) {
            return List.of();
        }

        List<String> rawGroup = ArmorVariantStore.getCatalog().findVariantGroup(currentItemId);
        if (rawGroup.size() < 2) {
            return List.of();
        }

        List<String> validGroup = new ArrayList<>(rawGroup.size());

        for (String itemIdRaw : rawGroup) {
            String itemId = clean(itemIdRaw);
            if (itemId.isEmpty()) {
                continue;
            }

            Item item = Item.getAssetMap().getAsset(itemId);
            if (item == null || item == Item.UNKNOWN || item.getArmor() == null) {
                continue;
            }

            validGroup.add(itemId);
        }

        return validGroup;
    }

    @Nonnull
    public static ItemStack createVariantStack(
            @Nonnull String newItemId,
            @Nonnull ItemStack oldStack
    ) {
        Item newItem = Item.getAssetMap().getAsset(newItemId);
        if (newItem == null || newItem == Item.UNKNOWN) {
            return new ItemStack(newItemId, 1);
        }

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
}