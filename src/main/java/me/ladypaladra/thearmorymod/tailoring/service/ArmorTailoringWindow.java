package me.ladypaladra.thearmorymod.tailoring.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.protocol.packets.window.SetActiveAction;
import com.hypixel.hytale.protocol.packets.window.WindowAction;
import com.hypixel.hytale.protocol.packets.window.WindowType;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ItemContainerWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.inventory.container.filter.ResourceFilter;
import com.hypixel.hytale.server.core.inventory.ResourceQuantity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public final class ArmorTailoringWindow extends Window implements ItemContainerWindow {

    private static final short FUEL_SLOT = 0;
    private static final short INPUT_SLOT = 0;
    private static final short OUTPUT_SLOT = 0;

    private static final double TAILORING_FUEL_COST = 1.0D;
    private static final int MAX_PROCESS_PER_REFRESH = 64;
    private static final int MAX_STORED_FUEL = 8;

    private static final String WINDOW_ID = "ArmorTailoring";
    private static final String WINDOW_NAME = "server.window.armorTailoring.name";
    private static final String BLOCK_ITEM_ID = "Armor_Tailoring_Bench";

    @Nonnull
    private final JsonObject windowData = new JsonObject();

    @Nonnull
    private final SimpleItemContainer fuelContainer = new SimpleItemContainer((short) 1);

    @Nonnull
    private final SimpleItemContainer inputContainer = new SimpleItemContainer((short) 1);

    @Nonnull
    private final SimpleItemContainer outputContainer = new SimpleItemContainer((short) 1);

    @Nonnull
    private final CombinedItemContainer combinedItemContainer =
            new CombinedItemContainer(new ItemContainer[]{
                    fuelContainer,
                    inputContainer,
                    outputContainer
            });

    @Nullable
    private EventRegistration<?, ?> fuelChangeRegistration;

    @Nullable
    private EventRegistration<?, ?> inputChangeRegistration;

    @Nullable
    private EventRegistration<?, ?> outputChangeRegistration;

    @Nullable
    private Ref<EntityStore> openRef;

    @Nullable
    private Store<EntityStore> openStore;

    private double storedFuel = 0.0D;
    private boolean active = true;
    private boolean mutating = false;

    public ArmorTailoringWindow() {
        super(WindowType.Processing);

        setupFilters();
        setupInitialWindowData();
        refreshWindowData();
    }

    @Nonnull
    @Override
    public JsonObject getData() {
        return windowData;
    }

    @Nonnull
    @Override
    public CombinedItemContainer getItemContainer() {
        return combinedItemContainer;
    }

    @Override
    protected boolean onOpen0(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        this.openRef = ref;
        this.openStore = store;

        registerContainerEvents();

        tryProcessAndRefresh();

        return true;
    }

    @Override
    public void onClose0(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor
    ) {
        unregisterContainerEvents();

        refundContainer(ref, componentAccessor, fuelContainer);
        refundContainer(ref, componentAccessor, inputContainer);
        refundContainer(ref, componentAccessor, outputContainer);

        this.openRef = null;
        this.openStore = null;
        this.storedFuel = 0.0D;
        this.active = false;

        refreshWindowData();
    }

    @Override
    public void handleAction(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull WindowAction action
    ) {
        if (action instanceof SetActiveAction setActiveAction) {
            this.active = setActiveAction.state;

            if (this.active) {
                tryProcessAndRefresh();
            } else {
                refreshWindowData();
                invalidate();
            }

            return;
        }

        refreshWindowData();
        invalidate();
    }

    private void setupFilters() {
        fuelContainer.setSlotFilter(
                FilterActionType.ADD,
                FUEL_SLOT,
                new ResourceFilter(new ResourceQuantity(ArmorTailoringFuelRules.DYE_RESOURCE_TYPE_ID, 1))
        );

        inputContainer.setSlotFilter(
                FilterActionType.ADD,
                INPUT_SLOT,
                (actionType, container, slotIndex, itemStack) ->
                        itemStack == null || ArmorTailoringProcessor.canTailor(itemStack)
        );

        outputContainer.setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);
    }

    private void setupInitialWindowData() {
        windowData.addProperty("type", BenchType.Processing.ordinal());
        windowData.addProperty("id", WINDOW_ID);
        windowData.addProperty("name", WINDOW_NAME);
        windowData.addProperty("blockItemId", BLOCK_ITEM_ID);
        windowData.addProperty("tierLevel", 1);

        JsonArray fuel = new JsonArray();
        JsonObject fuelSlot = new JsonObject();
        fuelSlot.addProperty("icon", "Icons/ItemsGenerated/Dye.png");
        fuelSlot.addProperty("resourceTypeId", ArmorTailoringFuelRules.DYE_RESOURCE_TYPE_ID);
        fuel.add(fuelSlot);
        windowData.add("fuel", fuel);

        JsonArray input = new JsonArray();
        JsonObject inputSlot = new JsonObject();
        inputSlot.addProperty("icon", "Icons/ItemsGenerated/Armor.png");
        input.add(inputSlot);
        windowData.add("input", input);

        windowData.addProperty("outputSlotsCount", 1);
        windowData.addProperty("maxFuel", MAX_STORED_FUEL);
        windowData.addProperty("progress", 0.0F);
        windowData.addProperty("processingSlots", 0);
        windowData.addProperty("processingFuelSlots", 0);
    }

    private void registerContainerEvents() {
        unregisterContainerEvents();

        this.fuelChangeRegistration = fuelContainer.registerChangeEvent(
                EventPriority.LAST,
                event -> tryProcessAndRefresh()
        );

        this.inputChangeRegistration = inputContainer.registerChangeEvent(
                EventPriority.LAST,
                event -> tryProcessAndRefresh()
        );

        this.outputChangeRegistration = outputContainer.registerChangeEvent(
                EventPriority.LAST,
                event -> tryProcessAndRefresh()
        );
    }

    private void unregisterContainerEvents() {
        if (fuelChangeRegistration != null) {
            fuelChangeRegistration.unregister();
            fuelChangeRegistration = null;
        }

        if (inputChangeRegistration != null) {
            inputChangeRegistration.unregister();
            inputChangeRegistration = null;
        }

        if (outputChangeRegistration != null) {
            outputChangeRegistration.unregister();
            outputChangeRegistration = null;
        }
    }

    private void tryProcessAndRefresh() {
        if (mutating) {
            return;
        }

        mutating = true;

        try {
            if (active) {
                processAvailableInputs();
            }

            refreshWindowData();
            invalidate();
        } finally {
            mutating = false;
        }
    }

    private void processAvailableInputs() {
        int processed = 0;

        while (processed < MAX_PROCESS_PER_REFRESH) {
            ItemStack inputStack = inputContainer.getItemStack(INPUT_SLOT);

            ArmorTailoringProcessor.TailoringResult result =
                    ArmorTailoringProcessor.createNextVariant(inputStack);

            if (result == null) {
                break;
            }

            if (!canAddOutput(result.outputStack())) {
                break;
            }

            if (storedFuel < TAILORING_FUEL_COST) {
                double addedFuel = ArmorTailoringFuelRules.consumeOneFuel(fuelContainer);

                if (addedFuel <= 0.0D) {
                    break;
                }

                storedFuel = Math.min(MAX_STORED_FUEL, storedFuel + addedFuel);
                continue;
            }

            if (!removeOneInput(inputStack)) {
                break;
            }

            storedFuel = Math.max(0.0D, storedFuel - TAILORING_FUEL_COST);

            addOutput(result.outputStack());

            processed++;
        }
    }

    private boolean canAddOutput(@Nonnull ItemStack outputStack) {
        return outputContainer.canAddItemStacks(
                List.of(outputStack),
                false,
                false
        );
    }

    private void addOutput(@Nonnull ItemStack outputStack) {
        outputContainer.addItemStacks(
                List.of(outputStack),
                false,
                false,
                false
        );
    }

    private boolean removeOneInput(@Nullable ItemStack inputStack) {
        if (ItemStack.isEmpty(inputStack)) {
            return false;
        }

        int quantity = inputStack.getQuantity();

        ItemStack replacement = quantity <= 1
                ? null
                : inputStack.withQuantity(quantity - 1);

        return inputContainer.setItemStackForSlot(INPUT_SLOT, replacement, true).succeeded();
    }

    private void refreshWindowData() {
        float fuelPercent = MAX_STORED_FUEL <= 0
                ? 0.0F
                : (float) Math.clamp(storedFuel / (double) MAX_STORED_FUEL, 0.0D, 1.0D);

        boolean hasTailorableInput = ArmorTailoringProcessor.canTailor(inputContainer.getItemStack(INPUT_SLOT));
        boolean hasFuelAvailable = storedFuel >= TAILORING_FUEL_COST
                || ArmorTailoringFuelRules.isFuelStack(fuelContainer.getItemStack(FUEL_SLOT));

        boolean processing = active && hasTailorableInput && hasFuelAvailable;

        windowData.addProperty("active", active);
        windowData.addProperty("fuelTime", fuelPercent);
        windowData.addProperty("progress", processing ? 1.0F : 0.0F);
        windowData.addProperty("processingSlots", processing ? 1 : 0);
        windowData.addProperty("processingFuelSlots", processing ? 1 : 0);
    }

    private static void refundContainer(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor,
            @Nonnull ItemContainer source
    ) {
        ItemContainer target = InventoryComponent.getCombined(
                componentAccessor,
                ref,
                InventoryComponent.HOTBAR_FIRST
        );

        short capacity = source.getCapacity();

        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = source.getItemStack(slot);

            if (ItemStack.isEmpty(stack)) {
                continue;
            }

            SimpleItemContainer.addOrDropItemStack(componentAccessor, ref, target, stack);
            source.setItemStackForSlot(slot, null, true);
        }
    }
}