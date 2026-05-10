package me.ladypaladra.thearmorymod.tailoring.component;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import me.ladypaladra.thearmorymod.tailoring.ArmorTailoringModule;
import me.ladypaladra.thearmorymod.tailoring.service.ArmorTailoringService;
import me.ladypaladra.thearmorymod.tailoring.window.ArmorTailoringBenchWindow;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ArmorTailoringBenchBlock implements Component<ChunkStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    @Nonnull
    public static final BuilderCodec<ArmorTailoringBenchBlock> CODEC;

    private static final short ARMOR_INPUT_SLOT = 0;
    private static final short FUEL_SLOT = 0;

    @Nullable
    private SimpleItemContainer armorInputContainer;

    @Nullable
    private SimpleItemContainer fuelContainer;

    @Nullable
    private SimpleItemContainer outputContainer;

    @Nullable
    private transient CombinedItemContainer combinedItemContainer;

    private transient boolean initialized;

    @Nonnull
    private final transient Map<UUID, ArmorTailoringBenchWindow> windows = new ConcurrentHashMap<>();

    public ArmorTailoringBenchBlock() {
    }

    @Nonnull
    public static ComponentType<ChunkStore, ArmorTailoringBenchBlock> getComponentType() {
        return ArmorTailoringModule.getArmorTailoringBenchBlockComponentType();
    }

    public void ensureInitialized(@Nullable BlockModule.BlockStateInfo blockStateInfo) {
        if (armorInputContainer == null) {
            armorInputContainer = new SimpleItemContainer((short) 1);
        }

        if (fuelContainer == null) {
            fuelContainer = new SimpleItemContainer((short) 1);
        }

        if (outputContainer == null) {
            outputContainer = new SimpleItemContainer((short) 1);
        }

        if (combinedItemContainer == null) {
            combinedItemContainer = new CombinedItemContainer(new ItemContainer[]{
                    fuelContainer,
                    armorInputContainer,
                    outputContainer
            });
        }

        if (initialized) {
            return;
        }

        armorInputContainer.setSlotFilter(
                FilterActionType.ADD,
                ARMOR_INPUT_SLOT,
                (actionType, container, slot, itemStack) ->
                        itemStack == null || ArmorTailoringService.isTailorableArmor(itemStack)
        );

        fuelContainer.setSlotFilter(
                FilterActionType.ADD,
                FUEL_SLOT,
                (actionType, container, slot, itemStack) ->
                        itemStack == null || ArmorTailoringService.isTailoringFuel(itemStack)
        );

        outputContainer.setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);

        armorInputContainer.registerChangeEvent(EventPriority.LAST, event -> {
            markDirty(blockStateInfo);
            refreshOpenWindows();
        });

        fuelContainer.registerChangeEvent(EventPriority.LAST, event -> {
            markDirty(blockStateInfo);
            refreshOpenWindows();
        });

        outputContainer.registerChangeEvent(EventPriority.LAST, event -> {
            markDirty(blockStateInfo);
            refreshOpenWindows();
        });

        initialized = true;
    }

    @Nonnull
    public CombinedItemContainer getItemContainer() {
        if (combinedItemContainer == null) {
            ensureInitialized(null);
        }

        assert combinedItemContainer != null;
        return combinedItemContainer;
    }

    @Nonnull
    public TailoringResult tailorOne() {
        if (armorInputContainer == null || fuelContainer == null || outputContainer == null) {
            ensureInitialized(null);
        }

        assert armorInputContainer != null;
        assert fuelContainer != null;
        assert outputContainer != null;

        ItemStack armorStack = armorInputContainer.getItemStack(ARMOR_INPUT_SLOT);
        if (ItemStack.isEmpty(armorStack)) {
            return TailoringResult.MISSING_ARMOR;
        }

        if (!ArmorTailoringService.isTailorableArmor(armorStack)) {
            return TailoringResult.INVALID_ARMOR;
        }

        ItemStack fuelStack = fuelContainer.getItemStack(FUEL_SLOT);
        if (!ArmorTailoringService.isTailoringFuel(fuelStack)) {
            return TailoringResult.MISSING_FUEL;
        }

        String nextVariantId = ArmorTailoringService.findNextVariantId(armorStack.getItemId());
        if (nextVariantId == null || nextVariantId.isBlank()) {
            return TailoringResult.NO_VARIANT_FOUND;
        }

        ItemStack outputStack = ArmorTailoringService.createVariantStack(nextVariantId, armorStack);

        if (!outputContainer.canAddItemStacks(List.of(outputStack), false, false)) {
            return TailoringResult.OUTPUT_FULL;
        }

        if (!consumeOneFromSlot(fuelContainer, FUEL_SLOT)) {
            return TailoringResult.MISSING_FUEL;
        }

        if (!consumeOneFromSlot(armorInputContainer, ARMOR_INPUT_SLOT)) {
            return TailoringResult.MISSING_ARMOR;
        }

        var addTransaction = outputContainer.addItemStack(outputStack);
        if (!ItemStack.isEmpty(addTransaction.getRemainder())) {
            LOGGER.atWarning().log("Armor tailoring output had unexpected remainder: %s", addTransaction.getRemainder());
            return TailoringResult.OUTPUT_FULL;
        }

        return TailoringResult.SUCCESS;
    }

    public boolean canTailor() {
        if (armorInputContainer == null || fuelContainer == null || outputContainer == null) {
            return false;
        }

        ItemStack armorStack = armorInputContainer.getItemStack(ARMOR_INPUT_SLOT);
        ItemStack fuelStack = fuelContainer.getItemStack(FUEL_SLOT);

        if (!ArmorTailoringService.isTailorableArmor(armorStack)) {
            return false;
        }

        if (!ArmorTailoringService.isTailoringFuel(fuelStack)) {
            return false;
        }

        String nextVariantId = ArmorTailoringService.findNextVariantId(armorStack.getItemId());
        if (nextVariantId == null || nextVariantId.isBlank()) {
            return false;
        }

        ItemStack outputStack = ArmorTailoringService.createVariantStack(nextVariantId, armorStack);
        return outputContainer.canAddItemStacks(List.of(outputStack), false, false);
    }

    public void addWindow(@Nonnull UUID uuid, @Nonnull ArmorTailoringBenchWindow window) {
        windows.put(uuid, window);
    }

    public void removeWindow(@Nonnull UUID uuid, @Nonnull ArmorTailoringBenchWindow window) {
        windows.remove(uuid, window);
    }

    public boolean hasWindow(@Nonnull UUID uuid) {
        return windows.containsKey(uuid);
    }

    private void refreshOpenWindows() {
        for (ArmorTailoringBenchWindow window : windows.values()) {
            window.refreshState();
        }
    }

    private static boolean consumeOneFromSlot(
            @Nonnull ItemContainer container,
            short slot
    ) {
        ItemStack stack = container.getItemStack(slot);
        if (ItemStack.isEmpty(stack)) {
            return false;
        }

        int quantity = stack.getQuantity();

        if (quantity <= 1) {
            return container.setItemStackForSlot(slot, null, true).succeeded();
        }

        return container.setItemStackForSlot(slot, stack.withQuantity(quantity - 1), true).succeeded();
    }

    private static void markDirty(@Nullable BlockModule.BlockStateInfo blockStateInfo) {
        if (blockStateInfo != null) {
            blockStateInfo.markNeedsSaving();
        }
    }

    @Nullable
    @Override
    public Component<ChunkStore> clone() {
        ArmorTailoringBenchBlock clone = new ArmorTailoringBenchBlock();

        clone.armorInputContainer = this.armorInputContainer == null ? null : this.armorInputContainer.clone();
        clone.fuelContainer = this.fuelContainer == null ? null : this.fuelContainer.clone();
        clone.outputContainer = this.outputContainer == null ? null : this.outputContainer.clone();

        return clone;
    }

    public enum TailoringResult {
        SUCCESS,
        MISSING_ARMOR,
        INVALID_ARMOR,
        MISSING_FUEL,
        NO_VARIANT_FOUND,
        OUTPUT_FULL
    }

    static {
        var builder = BuilderCodec.builder(ArmorTailoringBenchBlock.class, ArmorTailoringBenchBlock::new);

        builder.append(
                        new KeyedCodec<>("ArmorInputContainer", SimpleItemContainer.CODEC),
                        (state, container) -> state.armorInputContainer = container,
                        state -> state.armorInputContainer
                )
                .documentation("Armor input slot for the tailoring bench.")
                .add();

        builder.append(
                        new KeyedCodec<>("FuelContainer", SimpleItemContainer.CODEC),
                        (state, container) -> state.fuelContainer = container,
                        state -> state.fuelContainer
                )
                .documentation("Dye/fuel slot for the tailoring bench.")
                .add();

        builder.append(
                        new KeyedCodec<>("OutputContainer", SimpleItemContainer.CODEC),
                        (state, container) -> state.outputContainer = container,
                        state -> state.outputContainer
                )
                .documentation("Output slot for tailored armor.")
                .add();

        CODEC = builder.build();
    }
}