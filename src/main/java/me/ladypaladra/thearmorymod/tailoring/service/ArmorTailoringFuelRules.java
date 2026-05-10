package me.ladypaladra.thearmorymod.tailoring.service;

import com.hypixel.hytale.protocol.ItemResourceType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.ResourceQuantity;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ResourceTransaction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class ArmorTailoringFuelRules {

    public static final String DYE_RESOURCE_TYPE_ID = "Dye";

    public static final short FUEL_SLOT = 0;

    private static final double FALLBACK_FUEL_PER_DYE = 1.0D;

    private ArmorTailoringFuelRules() {
    }

    public static boolean isFuelStack(@Nullable ItemStack stack) {
        if (ItemStack.isEmpty(stack)) {
            return false;
        }

        stack.getItem();

        ItemResourceType[] resourceTypes = stack.getItem().getResourceTypes();

        if (resourceTypes == null) {
            return false;
        }

        for (ItemResourceType resourceType : resourceTypes) {
            if (resourceType != null && DYE_RESOURCE_TYPE_ID.equals(resourceType.id)) {
                return true;
            }
        }

        return false;
    }

    public static double getFuelValue(@Nonnull ItemStack stack) {
        stack.getItem();

        double fuelQuality = stack.getItem().getFuelQuality();

        if (fuelQuality > 0.0D) {
            return fuelQuality;
        }

        return FALLBACK_FUEL_PER_DYE;
    }

    public static double consumeOneFuel(@Nonnull ItemContainer fuelContainer) {
        ItemStack stack = fuelContainer.getItemStack(FUEL_SLOT);

        if (!isFuelStack(stack)) {
            return 0.0D;
        }

        assert stack != null;
        double fuelValue = getFuelValue(stack);

        ResourceTransaction transaction = fuelContainer.removeResource(
                new ResourceQuantity(DYE_RESOURCE_TYPE_ID, 1),
                true,
                true,
                true
        );

        if (transaction.getRemainder() > 0) {
            return 0.0D;
        }

        return fuelValue;
    }
}
