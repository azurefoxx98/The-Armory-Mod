package me.ladypaladra.thearmorymod.largebench.window;

import com.google.gson.JsonArray;
import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.builtin.crafting.component.CraftingManager.InputRemovalType;
import com.hypixel.hytale.builtin.crafting.window.CraftingWindow;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.protocol.BenchRequirement;
import com.hypixel.hytale.protocol.ItemSoundEvent;
import com.hypixel.hytale.protocol.packets.window.ChangeBlockAction;
import com.hypixel.hytale.protocol.packets.window.CraftRecipeAction;
import com.hypixel.hytale.protocol.packets.window.SelectSlotAction;
import com.hypixel.hytale.protocol.packets.window.WindowAction;
import com.hypixel.hytale.protocol.packets.window.WindowType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.StructuralCraftingBench;
import com.hypixel.hytale.server.core.asset.type.item.config.BlockGroup;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ItemContainerWindow;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public final class LargeStructuralCraftingWindow extends CraftingWindow implements ItemContainerWindow {

    private static final short MAX_OPTIONS = 256;

    @Nonnull
    private final SimpleItemContainer inputContainer = new SimpleItemContainer((short) 1);

    @Nonnull
    private final SimpleItemContainer optionsContainer = new SimpleItemContainer(MAX_OPTIONS);

    @Nonnull
    private final CombinedItemContainer combinedItemContainer =
            new CombinedItemContainer(inputContainer, optionsContainer);

    @Nonnull
    private final Int2ObjectMap<String> optionSlotToRecipeMap = new Int2ObjectOpenHashMap<>();

    private int selectedSlot = 0;

    @Nullable
    private EventRegistration<Void, ItemContainer.ItemContainerChangeEvent> inventoryRegistration;

    public LargeStructuralCraftingWindow(
            int x,
            int y,
            int z,
            int rotationIndex,
            @Nonnull BlockType blockType,
            @Nonnull BenchBlock benchBlock
    ) {
        super(WindowType.StructuralCrafting, x, y, z, rotationIndex, blockType, benchBlock);

        this.inputContainer.registerChangeEvent(event -> this.updateRecipes());
        this.inputContainer.setSlotFilter(FilterActionType.ADD, (short) 0, this::isValidInput);

        this.optionsContainer.setGlobalFilter(FilterType.DENY_ALL);

        this.windowData.addProperty("selected", this.selectedSlot);

        StructuralCraftingBench structuralBench = (StructuralCraftingBench) this.bench;
        this.windowData.addProperty("allowBlockGroupCycling", structuralBench.shouldAllowBlockGroupCycling());
        this.windowData.addProperty("alwaysShowInventoryHints", structuralBench.shouldAlwaysShowInventoryHints());
        this.windowData.addProperty("maxOptions", MAX_OPTIONS);
    }

    private boolean isValidInput(
            FilterActionType filterActionType,
            ItemContainer itemContainer,
            short slot,
            ItemStack itemStack
    ) {
        if (filterActionType != FilterActionType.ADD) {
            return true;
        }

        ObjectList<CraftingRecipe> matchingRecipes = this.getMatchingRecipes(itemStack);
        return matchingRecipes != null && !matchingRecipes.isEmpty();
    }

    @Nonnull
    @Override
    public ItemContainer getItemContainer() {
        return this.combinedItemContainer;
    }

    @Override
    protected boolean onOpen0(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        super.onOpen0(ref, store);

        CombinedItemContainer playerInventory = InventoryComponent.getCombined(
                store,
                ref,
                InventoryComponent.HOTBAR_FIRST
        );

        this.inventoryRegistration = playerInventory.registerChangeEvent(event -> {
            this.windowData.add(
                    "inventoryHints",
                    CraftingManager.generateInventoryHints(
                            CraftingPlugin.getBenchRecipes(this.bench),
                            0,
                            playerInventory
                    )
            );

            this.invalidate();
        });

        this.windowData.add(
                "inventoryHints",
                CraftingManager.generateInventoryHints(
                        CraftingPlugin.getBenchRecipes(this.bench),
                        0,
                        playerInventory
                )
        );

        this.updateRecipes();
        return true;
    }

    @Override
    public void onClose0(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor
    ) {
        super.onClose0(ref, componentAccessor);

        CombinedItemContainer playerInventory = InventoryComponent.getCombined(
                componentAccessor,
                ref,
                InventoryComponent.HOTBAR_FIRST
        );

        List<ItemStack> itemStacks = this.inputContainer.dropAllItemStacks();

        SimpleItemContainer.addOrDropItemStacks(
                componentAccessor,
                ref,
                playerInventory,
                itemStacks
        );

        CraftingManager craftingManager = componentAccessor.getComponent(
                ref,
                CraftingManager.getComponentType()
        );

        if (craftingManager != null) {
            craftingManager.cancelAllCrafting(ref, componentAccessor);
        }

        if (this.inventoryRegistration != null) {
            this.inventoryRegistration.unregister();
            this.inventoryRegistration = null;
        }
    }

    @Override
    public void handleAction(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull WindowAction action
    ) {
        CraftingManager craftingManager = store.getComponent(ref, CraftingManager.getComponentType());
        if (craftingManager == null) {
            return;
        }

        switch (action) {
            case SelectSlotAction selectAction -> {
                int capacity = this.optionsContainer.getCapacity();
                int newSlot = Math.clamp(selectAction.slot, 0, capacity - 1);

                if (newSlot != this.selectedSlot) {
                    this.selectedSlot = newSlot;
                    this.windowData.addProperty("selected", this.selectedSlot);
                    this.invalidate();
                }

                return;
            }
            case CraftRecipeAction craftAction -> {
                ItemStack output = this.optionsContainer.getItemStack((short) this.selectedSlot);
                if (output == null || output.isEmpty()) {
                    return;
                }

                String recipeId = this.optionSlotToRecipeMap.get(this.selectedSlot);
                if (recipeId == null || recipeId.isBlank()) {
                    return;
                }

                CraftingRecipe recipe = CraftingRecipe.getAssetMap().getAsset(recipeId);
                if (recipe == null) {
                    return;
                }

                MaterialQuantity primaryOutput = recipe.getPrimaryOutput();
                if (primaryOutput != null && primaryOutput.getItemId() != null) {
                    Item primaryOutputItem = Item.getAssetMap().getAsset(primaryOutput.getItemId());

                    if (primaryOutputItem != null) {
                        SoundUtil.playItemSoundEvent(ref, store, primaryOutputItem, ItemSoundEvent.Drop);
                    }
                }

                int quantity = Math.max(1, craftAction.quantity);

                if (craftingManager.queueCraft(
                        ref,
                        store,
                        this,
                        0,
                        recipe,
                        quantity,
                        this.inputContainer,
                        InputRemovalType.ORDERED
                )) {
                    this.updateQueueSize(craftingManager.getRemainingQueueSize());
                }

                this.invalidate();
                return;
            }
            case ChangeBlockAction changeBlockAction -> {
                StructuralCraftingBench structuralBench = (StructuralCraftingBench) this.bench;

                if (structuralBench.shouldAllowBlockGroupCycling()) {
                    this.changeBlockType(ref, changeBlockAction.down, store);
                }
            }
            default -> {
            }
        }

    }

    private void changeBlockType(
            @Nonnull Ref<EntityStore> ref,
            boolean down,
            @Nonnull Store<EntityStore> store
    ) {
        ItemStack item = this.inputContainer.getItemStack((short) 0);
        if (item == null || item.isEmpty()) {
            return;
        }

        BlockGroup set = BlockGroup.findItemGroup(item.getItem());
        if (set == null || set.size() <= 0) {
            return;
        }

        int currentIndex = -1;

        for (int i = 0; i < set.size(); i++) {
            if (set.get(i).equals(item.getItem().getId())) {
                currentIndex = i;
                break;
            }
        }

        if (currentIndex == -1) {
            return;
        }

        int newIndex = down
                ? (currentIndex - 1 + set.size()) % set.size()
                : (currentIndex + 1) % set.size();

        String next = set.get(newIndex);
        Item desiredItem = Item.getAssetMap().getAsset(next);

        if (desiredItem == null) {
            return;
        }

        this.inputContainer.replaceItemStackInSlot(
                (short) 0,
                item,
                new ItemStack(next, item.getQuantity())
        );

        SoundUtil.playItemSoundEvent(ref, store, desiredItem, ItemSoundEvent.Drop);
    }

    private void updateRecipes() {
        this.invalidate();

        this.optionsContainer.clear();
        this.optionSlotToRecipeMap.clear();

        ItemStack inputStack = this.inputContainer.getItemStack((short) 0);
        ObjectList<CraftingRecipe> matchingRecipes = this.getMatchingRecipes(inputStack);

        if (matchingRecipes == null || matchingRecipes.isEmpty()) {
            this.windowData.addProperty("dividerIndex", 0);
            this.windowData.add("optionSlotRecipes", new JsonArray());
            return;
        }

        StructuralCraftingBench structuralBench = (StructuralCraftingBench) this.bench;
        sortRecipes(matchingRecipes, structuralBench);

        int dividerIndex;

        for (dividerIndex = 0; dividerIndex < matchingRecipes.size(); dividerIndex++) {
            CraftingRecipe recipe = matchingRecipes.get(dividerIndex);

            if (!hasHeaderCategory(structuralBench, recipe)) {
                break;
            }
        }

        this.windowData.addProperty("dividerIndex", dividerIndex);

        short optionIndex = 0;
        int maxOptions = this.optionsContainer.getCapacity();

        for (CraftingRecipe match : matchingRecipes) {
            if (optionIndex >= maxOptions) {
                break;
            }

            BenchRequirement[] requirements = match.getBenchRequirement();
            if (requirements == null) {
                continue;
            }

            for (BenchRequirement requirement : requirements) {
                if (requirement.type == this.bench.getType()
                        && requirement.id.equals(this.bench.getId())) {

                    List<ItemStack> output = CraftingManager.getOutputItemStacks(match);

                    if (!output.isEmpty() && output.getFirst() != null && !output.getFirst().isEmpty()) {
                        this.optionsContainer.setItemStackForSlot(optionIndex, output.getFirst(), false);
                        this.optionSlotToRecipeMap.put(optionIndex, match.getId());
                        optionIndex++;
                    }

                    break;
                }
            }
        }

        JsonArray optionSlotRecipes = new JsonArray();

        for (int i = 0; i < this.optionsContainer.getCapacity(); i++) {
            String recipeId = this.optionSlotToRecipeMap.get(i);

            if (recipeId != null) {
                optionSlotRecipes.add(recipeId);
            }
        }

        this.windowData.add("optionSlotRecipes", optionSlotRecipes);

        if (this.selectedSlot >= this.optionsContainer.getCapacity()) {
            this.selectedSlot = 0;
            this.windowData.addProperty("selected", this.selectedSlot);
        }
    }

    @Nullable
    private ObjectList<CraftingRecipe> getMatchingRecipes(@Nullable ItemStack inputStack) {
        if (inputStack == null || inputStack.isEmpty()) {
            return null;
        }

        List<CraftingRecipe> recipes = CraftingPlugin.getBenchRecipes(
                this.bench.getType(),
                this.bench.getId()
        );

        if (recipes.isEmpty()) {
            return null;
        }

        ObjectList<CraftingRecipe> matchingRecipes = new ObjectArrayList<>();

        for (CraftingRecipe recipe : recipes) {
            List<MaterialQuantity> inputMaterials = CraftingManager.getInputMaterials(recipe);

            if (inputMaterials.size() == 1
                    && CraftingManager.matches(inputMaterials.getFirst(), inputStack)) {
                matchingRecipes.add(recipe);
            }
        }

        return matchingRecipes.isEmpty() ? null : matchingRecipes;
    }

    private static void sortRecipes(
            @Nonnull ObjectList<CraftingRecipe> matching,
            @Nonnull StructuralCraftingBench structuralBench
    ) {
        matching.sort((a, b) -> {
            boolean aHasHeaderCategory = hasHeaderCategory(structuralBench, a);
            boolean bHasHeaderCategory = hasHeaderCategory(structuralBench, b);

            if (aHasHeaderCategory != bHasHeaderCategory) {
                return aHasHeaderCategory ? -1 : 1;
            }

            int categoryA = getSortingPriority(structuralBench, a);
            int categoryB = getSortingPriority(structuralBench, b);
            int categoryCompare = Integer.compare(categoryA, categoryB);

            return categoryCompare != 0
                    ? categoryCompare
                    : a.getId().compareTo(b.getId());
        });
    }

    private static boolean hasHeaderCategory(
            @Nonnull StructuralCraftingBench bench,
            @Nonnull CraftingRecipe recipe
    ) {
        BenchRequirement[] requirements = recipe.getBenchRequirement();
        if (requirements == null) {
            return false;
        }

        for (BenchRequirement requirement : requirements) {
            if (requirement.type == bench.getType()
                    && requirement.id.equals(bench.getId())
                    && requirement.categories != null) {

                for (String category : requirement.categories) {
                    if (bench.isHeaderCategory(category)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static int getSortingPriority(
            @Nonnull StructuralCraftingBench bench,
            @Nonnull CraftingRecipe recipe
    ) {
        int priority = Integer.MAX_VALUE;

        BenchRequirement[] requirements = recipe.getBenchRequirement();
        if (requirements == null) {
            return priority;
        }

        for (BenchRequirement requirement : requirements) {
            if (requirement.type == bench.getType()
                    && requirement.id.equals(bench.getId())
                    && requirement.categories != null) {

                for (String category : requirement.categories) {
                    priority = Math.min(priority, bench.getCategoryIndex(category));
                }

                break;
            }
        }

        return priority;
    }
}