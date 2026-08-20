package me.ladypaladra.thearmorymod.alteration;

import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.protocol.ItemResourceType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps each alteration input family to the alteration recipes that consume it. The
 * custom page needs the reverse of what the bench window builds. The window starts
 * from an input item and lists every matching recipe. The page instead starts from a
 * family and paints every variant a family member can turn into, so a player can pick
 * any owned piece and see its alternatives without holding it in a slot.
 *
 * <p>The index is built lazily on first use rather than at plugin registration. Item
 * and recipe assets are not guaranteed loaded when a plugin registers, and the mod
 * already defers its family uniformity sweep for the same reason.
 */
public final class AlterationRecipeIndex {

    private static volatile boolean built = false;

    // Family ResourceTypeId to the alteration recipes keyed on it, insertion ordered so
    // the variant row keeps a stable, repeatable layout between opens.
    @Nonnull
    private static final Map<String, List<CraftingRecipe>> BY_FAMILY = new LinkedHashMap<>();

    private AlterationRecipeIndex() {
    }

    /**
     * Builds the index once. Cheap to call repeatedly, the first caller pays the sweep
     * and everyone after sees the cached map.
     */
    public static void ensureBuilt() {
        if (built) {
            return;
        }

        synchronized (AlterationRecipeIndex.class) {
            if (built) {
                return;
            }

            for (CraftingRecipe recipe : CraftingRecipe.getAssetMap().getAssetMap().values()) {
                if (!AlterationTransaction.isAlterationRecipe(recipe)) {
                    continue;
                }

                List<MaterialQuantity> inputMaterials = CraftingManager.getInputMaterials(recipe);
                if (inputMaterials.size() != 1) {
                    continue;
                }

                String familyId = inputMaterials.getFirst().getResourceTypeId();
                if (familyId == null) {
                    continue;
                }

                MaterialQuantity primaryOutput = recipe.getPrimaryOutput();
                if (primaryOutput == null || primaryOutput.getItemId() == null) {
                    continue;
                }

                BY_FAMILY.computeIfAbsent(familyId, key -> new ArrayList<>()).add(recipe);
            }

            built = true;
        }
    }

    /**
     * The alteration recipes for a family, or an empty list when the family has none.
     */
    @Nonnull
    public static List<CraftingRecipe> forFamily(@Nullable String familyId) {
        ensureBuilt();

        if (familyId == null) {
            return Collections.emptyList();
        }

        List<CraftingRecipe> recipes = BY_FAMILY.get(familyId);
        return recipes != null ? recipes : Collections.emptyList();
    }

    /**
     * The first of an item's resource families that has alteration recipes, or null when
     * the item is not alterable. Alteration families are recolor sets, so an alterable
     * item belongs to exactly one in practice, and taking the first keeps that simple.
     */
    @Nullable
    public static String findAlterableFamily(@Nullable Item item) {
        if (item == null) {
            return null;
        }

        ensureBuilt();

        ItemResourceType[] resourceTypes = item.getResourceTypes();
        if (resourceTypes == null) {
            return null;
        }

        for (ItemResourceType resourceType : resourceTypes) {
            if (resourceType != null && BY_FAMILY.containsKey(resourceType.id)) {
                return resourceType.id;
            }
        }

        return null;
    }

    /**
     * Whether the item can be altered at all, used to filter the owned gear list.
     */
    public static boolean isAlterable(@Nullable Item item) {
        return findAlterableFamily(item) != null;
    }
}
