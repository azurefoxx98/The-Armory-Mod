package me.ladypaladra.thearmorymod.alteration;

import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.BenchRequirement;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.protocol.ItemResourceType;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.BsonDocument;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owned crafting path for the Alteration Table. The engine crafting path rebuilds
 * outputs from the recipe asset, so a normal alteration would reset durability and
 * drop ItemStack metadata. This transaction routes the craft ourselves so the input
 * item's durability and metadata carry onto the cosmetic variant, charges a fuel
 * cost persisted on the bench block, and plays a use animation on success.
 */
public final class AlterationTransaction {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Prefix for this feature's language keys. It matches the one on
     * {@code AlterationPage} because both surfaces speak to the same player about the same
     * bench, so their strings share a namespace in {@code server.lang}.
     */
    private static final String LANG_PREFIX = "server.customUI.alterationPage.";

    // Guards the one time family uniformity sweep. The sweep is informational because
    // wear carries as a fraction, so families that mix base durabilities are handled
    // correctly. The log explains a surprising durability number on an altered item
    // without requiring the investigation to start from scratch.
    private static volatile boolean familyCheckDone = false;

    private AlterationTransaction() {
    }

    /**
     * Whether a recipe belongs on the alteration path, keyed on the mod's own bench
     * id. Anything else keeps the engine crafting path.
     *
     * <p>Matching on {@link BenchType#StructuralCrafting} alone is not enough, and used
     * to be the bug here. Structural crafting is also what the vanilla Builder's
     * Workbench is, so the loose test pulled every vanilla building recipe onto the
     * alteration path. That put 72 vanilla resource families into
     * {@link AlterationRecipeIndex}, which is why planks and bricks used to show up as
     * alterable gear. The bench id is what actually separates the two.
     */
    public static boolean isAlterationRecipe(@Nonnull CraftingRecipe recipe) {
        BenchRequirement[] requirements = recipe.getBenchRequirement();

        if (requirements == null) {
            return false;
        }

        for (BenchRequirement requirement : requirements) {
            if (requirement.type == BenchType.StructuralCrafting
                    && AlterationConfig.BENCH_ID.equals(requirement.id)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Banks one held Alteration Kit into the table's fuel stock. The stock feeds the
     * live gauge, one kit at a time, when the gauge runs dry, so a right click deposit
     * never touches charges directly. Returns true when the interaction was handled, so
     * the caller skips opening the bench. A full stock counts as handled even though it
     * consumes nothing, holding a kit always talks to the table and never opens the
     * page. Returns false only when the deposit could not run at all, letting the caller
     * fall through to the normal open path. The one kit is only ever removed after every
     * refusal path has returned, so a refusal spends nothing.
     */
    public static boolean depositKit(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull World world,
            @Nonnull Vector3i targetBlock
    ) {
        // a. Resolve the charge component, attaching a fresh one if the table predates
        // this feature. A null here means the block is not a real table entity.
        AlterationTableBlock bench = resolveBenchComponent(world, targetBlock.x, targetBlock.y, targetBlock.z);
        if (bench == null) {
            notify(store, ref, Message.translation(LANG_PREFIX + "benchMissing"), NotificationStyle.Danger);
            return false;
        }

        // Migrate a table saved under the old nine charge gauge before reading its stock.
        bench.normalize();

        // b. Refuse a deposit that would overflow the stock. A full stock consumes
        // nothing but still counts as handled, so the page does not pop open over it.
        if (bench.getFuelStock() >= AlterationConfig.FUEL_STOCK_CAP) {
            notify(
                    store,
                    ref,
                    Message.translation(LANG_PREFIX + "stockFull")
                            .param("stock", bench.getFuelStock())
                            .param("max", AlterationConfig.FUEL_STOCK_CAP),
                    NotificationStyle.Default
            );
            return true;
        }

        // c. Consume one kit from the player. On failure we spent nothing, so the caller
        // opens the page as if this never ran.
        CombinedItemContainer playerInventory = InventoryComponent.getCombined(
                store,
                ref,
                InventoryComponent.HOTBAR_FIRST
        );

        ItemStackTransaction fuelRemoval = playerInventory.removeItemStack(
                new ItemStack(AlterationConfig.FUEL_ITEM_ID, 1)
        );

        if (!fuelRemoval.succeeded()) {
            return false;
        }

        // d. Bank the kit into stock and save. The gauge is untouched, it will draw from
        // stock the next time an alteration empties it.
        bench.addFuelStock(1);
        saveBench(world, targetBlock.x, targetBlock.y, targetBlock.z);

        notify(
                store,
                ref,
                Message.translation(LANG_PREFIX + "kitStocked")
                        .param("stock", bench.getFuelStock())
                        .param("charges", bench.getCharges())
                        .param("max", AlterationConfig.MAX_CHARGES),
                NotificationStyle.Default
        );
        return true;
    }

    /**
     * Runs a single alteration for the custom page. The page has already picked the
     * exact input stack in the player's inventory or armor and resolved the target
     * output, and it owns the charge economy, so this method is purely the item
     * transfer. It fires the same {@link CraftRecipeEvent} pair the bench window fires,
     * removes the exact input stack from its slot so durability and metadata survive,
     * builds the variant through {@link #buildOutput}, hands it back to the player with
     * any overflow dropped, and plays the use animation. Returns true only when the
     * output was delivered, so the caller consumes a charge only on a real alteration.
     * Every refusal path returns before the input is removed.
     */
    public static boolean processFromInventory(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull ItemContainer inputContainer,
            short slotIndex,
            @Nonnull String expectedItemId,
            @Nonnull CraftingRecipe recipe,
            @Nonnull String outputItemId,
            int quantity
    ) {
        runFamilyUniformityCheckOnce();

        // a. Re-read the exact stack the page recorded and confirm it has not changed
        // under the player since the list was painted. A mismatch means a stale click,
        // the caller repaints and asks the player to pick again.
        ItemStack input = inputContainer.getItemStack(slotIndex);
        if (input == null || input.isEmpty() || !expectedItemId.equals(input.getItemId())) {
            return false;
        }

        // b. Guard the recipe pairing. The page only offers variants from the input's
        // own family, this just refuses a malformed round trip rather than trusting it.
        List<MaterialQuantity> inputMaterials = CraftingManager.getInputMaterials(recipe);
        if (inputMaterials.size() != 1 || !CraftingManager.matches(inputMaterials.getFirst(), input)) {
            return false;
        }

        MaterialQuantity primaryOutput = recipe.getPrimaryOutput();
        if (primaryOutput == null || !outputItemId.equals(primaryOutput.getItemId())) {
            return false;
        }

        int craftQuantity = Math.max(1, quantity);

        // c. Pre event. A cancel here aborts before anything is consumed.
        CraftRecipeEvent.Pre preEvent = new CraftRecipeEvent.Pre(recipe, craftQuantity);
        store.invoke(ref, preEvent);
        if (preEvent.isCancelled()) {
            return false;
        }

        // d. Build the variant before the input leaves its slot, carrying durability and
        // metadata verbatim for a uniform family.
        ItemStack output = buildOutput(input, outputItemId, input.getQuantity());

        // e. Remove the exact input stack from its slot, then deliver the output. Order
        // matches the bench path so a Post cancel leaves the input gone and the output
        // withheld.
        ItemStackSlotTransaction inputRemoval = inputContainer.removeItemStackFromSlot(slotIndex, input, input.getQuantity());
        if (!inputRemoval.succeeded()) {
            return false;
        }

        CraftRecipeEvent.Post postEvent = new CraftRecipeEvent.Post(recipe, craftQuantity);
        store.invoke(ref, postEvent);

        if (!postEvent.isCancelled()) {
            // Put the variant back into the slot the input came from, so a worn piece
            // stays worn and a bag piece keeps its place. The slot was just emptied, so
            // this only fails on a slot filter, and then the normal pickup path takes
            // over with any overflow dropped.
            if (!inputContainer.addItemStackToSlot(slotIndex, output).succeeded()) {
                deliverOutput(ref, store, output);
            }
        }

        if (AlterationConfig.USE_ANIMATION_ID != null) {
            AnimationUtils.playAnimation(ref, AnimationSlot.Action, AlterationConfig.USE_ANIMATION_ID, true, store);
        }

        return true;
    }

    /**
     * Builds the variant stack. When the input and output items share a base
     * MaxDurability, which is true for every uniform recolor family, this behaves
     * exactly like a verbatim carry: wear and any modified maximum ride across
     * unchanged. Broad families like the vanilla iron armor sets mix very different
     * base durabilities, so there the wear fraction and modification factor carry
     * instead of the raw numbers. Altering can then never smuggle one item's
     * durability budget onto an item with a different base.
     */
    @Nonnull
    public static ItemStack buildOutput(@Nonnull ItemStack input, @Nonnull String outputId, int quantity) {
        BsonDocument metadata = buildTransferMetadata(input);

        Item outputItem = Item.getAssetMap().getAsset(outputId);
        double outputBase = outputItem != null ? outputItem.getMaxDurability() : 0.0;
        double inputBase = input.getItem().getMaxDurability();
        double inputMax = input.getMaxDurability();

        // Unbreakable on either side means there is no wear information to carry.
        if (outputBase <= 0 || inputBase <= 0 || inputMax <= 0) {
            return new ItemStack(outputId, quantity, metadata);
        }

        double modificationFactor = inputMax / inputBase;
        double newMax = outputBase * modificationFactor;
        double wearFraction = input.getDurability() / inputMax;
        double newDurability = Math.max(0.0, Math.min(newMax * wearFraction, newMax));

        return new ItemStack(outputId, quantity, newDurability, newMax, metadata);
    }

    /**
     * Clones the input metadata and strips the excluded keys. Returns null when there
     * is nothing left to carry so the output gets a clean stack.
     */
    @Nullable
    @SuppressWarnings("deprecation")
    public static BsonDocument buildTransferMetadata(@Nonnull ItemStack input) {
        BsonDocument metadata = input.getMetadata();
        if (metadata == null) {
            return null;
        }

        // Clone before touching anything. getMetadata hands back the input stack's own
        // document, so stripping keys straight off it would edit the item the player is
        // still holding, and on a refusal after this point they would get their item
        // back with data missing. The copy also stops the output and the input sharing
        // one document, which matters on the refund path where both stacks stay alive.
        BsonDocument transfer = metadata.clone();

        for (String excludedKey : AlterationConfig.EXCLUDED_METADATA_KEYS) {
            transfer.remove(excludedKey);
        }

        return transfer.isEmpty() ? null : transfer;
    }

    /**
     * Delivers the output using the same sequence the engine uses in
     * CraftingManager#giveOutput so the item lands in the player's configured pickup
     * container or drops if it does not fit.
     */
    private static void deliverOutput(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull ItemStack output
    ) {
        if (output.isEmpty()) {
            return;
        }

        PlayerSettings playerSettings = store.getComponent(ref, PlayerSettings.getComponentType());
        if (playerSettings == null) {
            playerSettings = PlayerSettings.defaults();
        }

        ItemContainer container = InventoryUtils.getContainerForItemPickup(
                ref,
                output.getItem(),
                playerSettings,
                store
        );

        SimpleItemContainer.addOrDropItemStack(store, ref, container, output);
    }

    /**
     * Coordinate form of the bench resolver, shared by the craft path, which reads the
     * position off the open window, and the pre load path, which reads it off the
     * interaction target block.
     */
    @Nullable
    private static AlterationTableBlock resolveBenchComponent(
            @Nonnull World world,
            int x,
            int y,
            int z
    ) {
        AlterationTableBlock existing = BlockModule.getComponent(
                AlterationTableBlock.getComponentType(),
                world,
                x,
                y,
                z
        );

        if (existing != null) {
            return existing;
        }

        Ref<ChunkStore> blockRef = BlockModule.getBlockEntity(world, x, y, z);
        if (blockRef == null || !blockRef.isValid()) {
            return null;
        }

        return world.getChunkStore().getStore().ensureAndGetComponent(blockRef, AlterationTableBlock.getComponentType());
    }

    private static void saveBench(
            @Nonnull World world,
            int x,
            int y,
            int z
    ) {
        Ref<ChunkStore> blockRef = BlockModule.getBlockEntity(world, x, y, z);

        if (blockRef == null || !blockRef.isValid()) {
            return;
        }

        Store<ChunkStore> chunkStore = world.getChunkStore().getStore();

        BlockModule.BlockStateInfo blockStateInfo = chunkStore.getComponent(
                blockRef,
                BlockModule.BlockStateInfo.getComponentType()
        );

        if (blockStateInfo != null) {
            blockStateInfo.markNeedsSaving(chunkStore);
        }
    }

    /**
     * Sends a notification to the acting player.
     *
     * <p>This takes a Message and not a String on purpose. An earlier version took the text
     * raw, which put English on the screen of every player whatever language they run, and
     * the three call sites in this class all did exactly that. Pass
     * {@code Message.translation(LANG_PREFIX + key)} so the client resolves it against the
     * viewer's own language file. There is deliberately no String overload to fall back to.
     */
    private static void notify(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Message message,
            @Nonnull NotificationStyle style
    ) {
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        NotificationUtil.sendNotification(playerRef.getPacketHandler(), message, style);
    }

    private static void runFamilyUniformityCheckOnce() {
        if (familyCheckDone) {
            return;
        }

        synchronized (AlterationTransaction.class) {
            if (familyCheckDone) {
                return;
            }

            familyCheckDone = true;

            try {
                checkFamilyDurabilityUniformity();
            } catch (RuntimeException exception) {
                LOGGER.atWarning().withCause(exception).log("Alteration family uniformity check failed to run.");
            }
        }
    }

    /**
     * Sweeps every alteration recipe input family and, per family, gathers the base
     * MaxDurability of each member item. Any family whose members disagree gets one
     * log line. Wear carries as a fraction, so mixed base durabilities are handled
     * correctly. The line explains a surprising durability number on an altered item
     * without requiring the investigation to start from scratch.
     */
    private static void checkFamilyDurabilityUniformity() {
        Set<String> alterationFamilies = new HashSet<>();

        for (CraftingRecipe recipe : CraftingRecipe.getAssetMap().getAssetMap().values()) {
            if (!isAlterationRecipe(recipe)) {
                continue;
            }

            List<MaterialQuantity> inputMaterials = CraftingManager.getInputMaterials(recipe);
            if (inputMaterials.size() != 1) {
                continue;
            }

            String familyId = inputMaterials.getFirst().getResourceTypeId();
            if (familyId != null) {
                alterationFamilies.add(familyId);
            }
        }

        if (alterationFamilies.isEmpty()) {
            return;
        }

        Map<String, Set<Double>> familyDurabilities = new HashMap<>();

        for (Item item : Item.getAssetMap().getAssetMap().values()) {
            ItemResourceType[] resourceTypes = item.getResourceTypes();
            if (resourceTypes == null) {
                continue;
            }

            for (ItemResourceType resourceType : resourceTypes) {
                if (resourceType != null && alterationFamilies.contains(resourceType.id)) {
                    familyDurabilities
                            .computeIfAbsent(resourceType.id, key -> new HashSet<>())
                            .add(item.getMaxDurability());
                }
            }
        }

        for (Map.Entry<String, Set<Double>> entry : familyDurabilities.entrySet()) {
            if (entry.getValue().size() > 1) {
                LOGGER.atInfo().log(
                        "Alteration input family %s mixes base MaxDurability values %s. "
                                + "Wear carries as a fraction when altering across different bases.",
                        entry.getKey(),
                        entry.getValue()
                );
            }
        }
    }
}
