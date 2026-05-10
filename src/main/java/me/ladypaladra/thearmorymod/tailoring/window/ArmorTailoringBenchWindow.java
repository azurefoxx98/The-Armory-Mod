package me.ladypaladra.thearmorymod.tailoring.window;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.protocol.packets.window.SetActiveAction;
import com.hypixel.hytale.protocol.packets.window.WindowAction;
import com.hypixel.hytale.protocol.packets.window.WindowType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.player.windows.BlockWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ItemContainerWindow;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.tailoring.component.ArmorTailoringBenchBlock;
import me.ladypaladra.thearmorymod.tailoring.service.ArmorTailoringService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class ArmorTailoringBenchWindow extends BlockWindow implements ItemContainerWindow {

    private static final String WINDOW_ID = "ArmorTailoring";
    private static final String FALLBACK_NAME = "server.items.ArmorTailoringBench.name";

    @Nonnull
    private final ArmorTailoringBenchBlock benchState;

    @Nullable
    private final BlockModule.BlockStateInfo blockStateInfo;

    @Nonnull
    private final JsonObject windowData = new JsonObject();

    @Nonnull
    private ItemContainer itemContainer;

    private boolean active;

    public ArmorTailoringBenchWindow(
            @Nonnull ArmorTailoringBenchBlock benchState,
            @Nullable BlockModule.BlockStateInfo blockStateInfo,
            int x,
            int y,
            int z,
            int rotationIndex,
            @Nonnull BlockType blockType
    ) {
        super(WindowType.Processing, x, y, z, rotationIndex, blockType);

        this.benchState = benchState;
        this.blockStateInfo = blockStateInfo;

        this.benchState.ensureInitialized(blockStateInfo);
        this.itemContainer = this.benchState.getItemContainer();

        rebuildStaticData();
        rebuildDynamicData();
    }

    @Nonnull
    @Override
    public JsonObject getData() {
        return windowData;
    }

    @Nonnull
    @Override
    public ItemContainer getItemContainer() {
        return itemContainer;
    }

    @Override
    protected boolean onOpen0(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        this.benchState.ensureInitialized(blockStateInfo);
        this.itemContainer = this.benchState.getItemContainer();

        rebuildDynamicData();
        return true;
    }

    @Override
    protected void onClose0(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor
    ) {
        this.active = false;
        rebuildDynamicData();
    }

    @Override
    public void handleAction(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull WindowAction action
    ) {
        if (action instanceof SetActiveAction setActiveAction) {
            handleSetActive(ref, store, setActiveAction.state);
            return;
        }

        rebuildDynamicData();
        invalidate();
    }

    public void refreshState() {
        rebuildDynamicData();
        invalidate();
    }

    private void handleSetActive(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            boolean requestedActive
    ) {
        if (!requestedActive) {
            this.active = false;
            rebuildDynamicData();
            invalidate();
            return;
        }

        this.active = true;
        rebuildDynamicData();
        invalidate();

        ArmorTailoringBenchBlock.TailoringResult result = benchState.tailorOne();

        this.active = false;
        rebuildDynamicData();
        invalidate();

        sendResultMessage(ref, store, result);
    }

    private void rebuildStaticData() {
        Item blockItem = this.blockType.getItem();

        windowData.addProperty("type", BenchType.Processing.ordinal());
        windowData.addProperty("id", WINDOW_ID);
        windowData.addProperty("name", blockItem == null ? FALLBACK_NAME : blockItem.getTranslationKey());
        windowData.addProperty("blockItemId", blockItem == null ? "" : blockItem.getId());
        windowData.addProperty("tierLevel", 1);

        JsonArray inputSlots = new JsonArray();
        JsonObject armorInput = new JsonObject();
        armorInput.addProperty("icon", "");
        inputSlots.add(armorInput);
        windowData.add("input", inputSlots);

        JsonArray fuelSlots = new JsonArray();
        JsonObject fuelInput = new JsonObject();
        fuelInput.addProperty("icon", "");
        fuelInput.addProperty("resourceTypeId", ArmorTailoringService.DYE_RESOURCE_TYPE_ID);
        fuelSlots.add(fuelInput);
        windowData.add("fuel", fuelSlots);

        windowData.addProperty("outputSlotsCount", 1);
    }

    private void rebuildDynamicData() {
        windowData.addProperty("active", active);
        windowData.addProperty("progress", active ? 1.0F : 0.0F);
        windowData.addProperty("fuelTime", benchState.canTailor() ? 1.0F : 0.0F);
        windowData.addProperty("maxFuel", 1);
        windowData.addProperty("processingFuelSlots", active ? 1 : 0);
        windowData.addProperty("processingSlots", active ? 1 : 0);
        windowData.addProperty("canTailor", benchState.canTailor());
    }

    private static void sendResultMessage(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull ArmorTailoringBenchBlock.TailoringResult result
    ) {
        if (result == ArmorTailoringBenchBlock.TailoringResult.SUCCESS) {
            return;
        }

        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        String message = switch (result) {
            case MISSING_ARMOR -> "Put an armor piece in the input slot.";
            case INVALID_ARMOR -> "That item is not a valid armor variant.";
            case MISSING_FUEL -> "Put dye in the fuel slot.";
            case NO_VARIANT_FOUND -> "No armor variant found for that item.";
            case OUTPUT_FULL -> "The output slot is full.";
            case SUCCESS -> "";
        };

        playerRef.sendMessage(Message.raw(message));
    }
}