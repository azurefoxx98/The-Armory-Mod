package me.ladypaladra.thearmorymod.alteration.page;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemArmor;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.alteration.AlterationConfig;
import me.ladypaladra.thearmorymod.alteration.AlterationRecipeIndex;
import me.ladypaladra.thearmorymod.alteration.AlterationTableBlock;
import me.ladypaladra.thearmorymod.alteration.AlterationTransaction;
import me.ladypaladra.thearmorymod.telemetry.ArmoryTelemetry;
import me.ladypaladra.thearmorymod.ui.ItemSlots;
import me.ladypaladra.thearmorymod.ui.ItemText;
import me.ladypaladra.thearmorymod.ui.LastPicked;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server driven page for the Alteration Table. Right clicking F on a table opens this
 * in place of the stock bench window. It reads left to right as a sentence: the gear
 * you own, the altar where the change happens, and the variants the selected piece can
 * become. Picking gear and picking a variant only select, the ALTER button is the one
 * committing action, and the result slot previews the exact item and durability the
 * player is about to receive. Every alteration runs through
 * {@link AlterationTransaction} so durability and metadata carry onto the new variant.
 * The page never takes custody of any item, every slot is a display painted from the
 * server and every action mutates the real inventory directly, so there is nothing to
 * hand back when the page closes.
 */
public class AlterationPage extends InteractiveCustomUIPage<AlterationPage.PageEvent> {

    // Client documents streamed from the mod's Common tree, referenced the same way the
    // builtin pages reference theirs. If the live client cannot resolve these by path,
    // flip INLINE_MARKUP so the identical documents are sent inline instead. Both paths
    // share one markup source, the classpath resource below.
    private static final boolean INLINE_MARKUP = false;
    // Streamed mod documents register in the client's UI document registry under the
    // same UI/Custom relative short name the builtin pages use, measured across two
    // live sessions: the full "UI/Custom/..." form was never found, while the short
    // form resolved and parsed once the file was in the client's asset cache. The
    // cache itself lags one join behind any change to the ui files, so after editing
    // them always reconnect twice before judging a failure.
    private static final String PAGE_DOCUMENT = "Pages/TheArmory/AlterationPage.ui";
    private static final String PAGE_RESOURCE = "/Common/UI/Custom/Pages/TheArmory/AlterationPage.ui";
    private static final String BENCH_KEY = "alteration";

    // The gear list is one ItemGrid and has no entry document. The variant list still
    // uses a button cell, and that cell needs a real document rather than inline markup.
    //
    // Do not inline this cell. Inline markup cannot resolve a relative path, and doing so
    // crashed the client in game on 2026-08-10, dropping a tester on the first list click.
    // The failed version passed the cell to appendInline with its original
    // '$C = "../../Common.ui"' header. Those "../" segments are relative to the document
    // that declares them. An inline string is not a document, so nothing resolved and the
    // client refused the append. The crash waited for a click because paintVariants
    // returns early until something is selected. That let the page open cleanly and then
    // die as soon as it was used.
    //
    // Neither markup gate could catch this because uicheck.py and propcheck.py read .ui
    // files, while this markup was a Java string. Keep cell markup in documents where
    // both gates can see it.
    private static final String VARIANT_ENTRY_DOCUMENT = "Pages/TheArmory/AlterationVariantEntry.ui";

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String LANG_PREFIX = "server.customUI.alterationPage.";
    private static final Pattern SLOT_INDEX_FIELD =
            Pattern.compile("\"SlotIndex\"\\s*:\\s*\"?(-?\\d+)\"?");

    // Inventory sections walked for alterable gear. Order sets the list order on screen,
    // hotbar and storage first, then worn armor and the side pouches.
    private static final int[] SCANNED_SECTIONS = {
            InventoryComponent.HOTBAR_SECTION_ID,
            InventoryComponent.STORAGE_SECTION_ID,
            InventoryComponent.ARMOR_SECTION_ID,
            InventoryComponent.BACKPACK_SECTION_ID,
            InventoryComponent.UTILITY_SECTION_ID
    };

    // Event keys. None are prefixed with @, so the client sends the literal values we
    // set here straight back rather than resolving them as widget selectors.
    private static final String TYPE_SELECT_INPUT = "SelectInput";
    private static final String TYPE_SELECT_VARIANT = "SelectVariant";
    private static final String TYPE_ALTER = "Alter";
    private static final String TYPE_FILTER = "Filter";
    private static final String TYPE_SEARCH = "Search";
    private static final String TYPE_CLOSE = "Close";
    private static final String TYPE_DEPOSIT_KIT = "DepositKit";
    private static final String TYPE_WITHDRAW_KIT = "WithdrawKit";

    /**
     * The category chips above the gear grid. The engine's armor slots are Head, Chest,
     * Hands and Legs, there is no Feet slot in this build, so the chip row follows the
     * engine rather than inventing a category nothing can match.
     */
    private enum GearFilter {
        All("ChipAll"),
        Head("ChipHead"),
        Chest("ChipChest"),
        Hands("ChipHands"),
        Legs("ChipLegs"),
        Weapon("ChipWeapon");

        // Widget id stem, the button is <stem>Button and the active underline is <stem>Mark.
        private final String widget;

        GearFilter(@Nonnull String widget) {
            this.widget = widget;
        }
    }

    /**
     * How a guidance line reads. The four colors are already the page's vocabulary, the
     * two loud ones are lifted off the shipped barter card so a success and a refusal
     * here look like a success and a refusal anywhere else in the game.
     * <p>
     * Written as six digit hex, which is the form the engine's own color grammar accepts
     * for #RRGGBB and the form the rest of this page's markup already uses. The one
     * engine page that colors a Message this way, PortalDeviceActivePage, passes the
     * eight digit #RRGGBBAA form instead, so if these ever render wrong in game the first
     * thing to try is appending ff.
     */
    private enum StatusTone {
        // The idle next step, the same muted blue the strip's own label declares.
        Guide("#96a9be"),
        // Notable but neutral, for example the gauge topping itself up.
        Info("#E8A93B"),
        // The alteration went through.
        Good("#3d913f"),
        // A refusal, and the only tone that ever needs to stop the player.
        Bad("#cc4444");

        private final String color;

        StatusTone(@Nonnull String color) {
            this.color = color;
        }
    }

    @Nonnull
    private final Ref<ChunkStore> blockEntity;

    // The selected input, recorded as the exact coordinate the stack was read from so the
    // craft can target that same instance. Null section means nothing is selected.
    private int selectedSection = Integer.MIN_VALUE;
    private short selectedSlot = -1;
    @Nullable
    private String selectedItemId;
    @Nullable
    private String selectedFamily;

    // The variant the player has armed. Selecting a variant no longer crafts, it only
    // fills the result preview, and ALTER is what spends the charge.
    @Nullable
    private String selectedVariantId;

    // Which category chip is active. Only narrows the gear grid, never the variants.
    @Nonnull
    private GearFilter filter = GearFilter.All;
    @Nonnull
    private String searchQuery = "";

    /** Grid-order inventory coordinates rebuilt in lockstep with the displayed slots. */
    @Nonnull
    private final List<GearRow> gearRows = new ArrayList<>();

    /** Index carried by the most recent raw grid event, or -1 when absent. */
    private int clickedSlotIndex = -1;

    private record GearRow(int section, short slot, @Nonnull String itemId) {
    }

    public AlterationPage(@Nonnull PlayerRef playerRef, @Nonnull Ref<ChunkStore> blockEntity) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageEvent.CODEC);
        this.blockEntity = blockEntity;

        // Migrate a table saved under the old nine charge gauge the moment it is opened,
        // and persist the migration so it only happens once.
        AlterationTableBlock bench = resolveBench();
        if (bench != null) {
            bench.normalize();
            markBenchSaving();
        }

        // A table left empty with kits still stocked shows a full gauge the moment it is
        // opened, rather than a zero the player has to trigger a craft to clear.
        topUpFromStock();
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder,
            @Nonnull Store<EntityStore> store
    ) {
        ArmoryTelemetry.breadcrumb("ui", "alteration page opened");
        appendPage(commandBuilder);

        LastPicked.Pick pick = LastPicked.recall(playerRef.getUuid(), BENCH_KEY);
        if (pick != null) {
            this.selectedSection = pick.section();
            this.selectedSlot = pick.slot();
            this.selectedItemId = pick.itemId();
            ItemStack stack = resolveSelectedStack(store, ref);
            String family = stack != null
                    ? AlterationRecipeIndex.findAlterableFamily(stack.getItem())
                    : null;
            if (family == null) {
                clearSelection();
                LastPicked.forget(playerRef.getUuid(), BENCH_KEY);
            } else {
                this.selectedFamily = family;
                this.selectedVariantId = null;
            }
        }

        // Static bindings on every fixed button. The engine's pages only bind Activating
        // on button widgets, the client rejects click bindings on grids and slots,
        // measured live. These persist across every later partial update, so they are
        // only ever bound here.
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#StockButton",
                new EventData().append(PageEvent.KEY_TYPE, TYPE_DEPOSIT_KIT),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#WithdrawButton",
                new EventData().append(PageEvent.KEY_TYPE, TYPE_WITHDRAW_KIT),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#AlterButton",
                new EventData().append(PageEvent.KEY_TYPE, TYPE_ALTER),
                false
        );
        // The container's close button ships with the decorated frame but carries no
        // behaviour of its own, so the page closes itself when it is pressed.
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#CloseButton",
                new EventData().append(PageEvent.KEY_TYPE, TYPE_CLOSE),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#SearchInput",
                new EventData()
                        .append(PageEvent.KEY_TYPE, TYPE_SEARCH)
                        .append(PageEvent.KEY_QUERY, "#SearchInput.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.SlotClicking,
                "#OwnedGrid",
                new EventData().append(PageEvent.KEY_TYPE, TYPE_SELECT_INPUT),
                false
        );

        for (GearFilter value : GearFilter.values()) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#" + value.widget + "Button",
                    new EventData()
                            .append(PageEvent.KEY_TYPE, TYPE_FILTER)
                            .append(PageEvent.KEY_FILTER, value.name()),
                    false
            );
        }

        paintChips(commandBuilder);
        paintInput(commandBuilder, store, ref);
        paintResult(commandBuilder, store, ref);
        paintFuel(commandBuilder);
        paintOwnedList(commandBuilder, store, ref);
        paintVariants(commandBuilder, eventBuilder);
        paintAlterState(commandBuilder, store, ref);
        // The strip is never blank, so the very first paint already carries the opening
        // instruction rather than an empty box.
        paintGuidance(commandBuilder, store, ref);
    }

    @Override
    public void handleDataEvent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            String rawData
    ) {
        clickedSlotIndex = parseSlotIndex(rawData);
        super.handleDataEvent(ref, store, rawData);
    }

    @Override
    public void handleDataEvent(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PageEvent data
    ) {
        if (data.type == null) {
            return;
        }

        // An exception escaping this method removes the player's entity and disconnects
        // them. This was measured on the Scribing page, not guessed from this page. In the
        // server log from 2026-08-11 11:49:05, a throw added inside ScribingWrite.apply
        // reached its handler and the engine logged
        // "TickInteractionManagerSystem: Exception while ticking entity interactions!
        // Removing!" before dropping the tester mid play-test.
        //
        // This page has the identical exposure and went without the guard until 2026-08-18.
        // It is the same base class, the same tick, and the same consequence, and it runs a
        // transaction that moves real items rather than only painting text, so the cost of
        // an unpredicted bug here is higher rather than lower.
        //
        // Do not narrow this to a specific exception type. Its value is covering the bug
        // nobody predicted, which is the only kind that gets this far.
        // Throwable rather than Exception, because a stack overflow from a deep message tree
        // arrives as an Error and costs the player their session if it escapes this method.
        // It does also swallow a genuinely fatal condition such as running out of memory, and
        // that is a deliberate trade: the severe log below is what keeps such a case visible.
        try {
            switch (data.type) {
                case TYPE_SELECT_INPUT -> handleSelectInput(ref, store);
                case TYPE_SELECT_VARIANT -> handleSelectVariant(ref, store, data);
                case TYPE_ALTER -> handleAlter(ref, store);
                case TYPE_FILTER -> handleFilter(ref, store, data);
                case TYPE_SEARCH -> handleSearch(ref, store, data);
                case TYPE_CLOSE -> close();
                case TYPE_DEPOSIT_KIT -> handleDepositKit(ref, store);
                case TYPE_WITHDRAW_KIT -> handleWithdrawKit(ref, store);
                default -> {
                }
            }
        } catch (Throwable failure) {
            LOGGER.atSevere().withCause(failure).log(
                    "Alteration page failed handling event type=%s for %s. The player was kept "
                            + "in the world and told it failed.",
                    data.type, playerRef.getUuid()
            );
            ArmoryTelemetry.pageFailure("alteration", String.valueOf(data.type), failure);
            // Best effort, and deliberately the smallest possible write. Whatever just went
            // wrong may have left the page half painted, so this does not try to repaint it.
            //
            // The key is actionFailed rather than craftFailed on purpose. craftFailed is
            // already live for a real alteration that could not complete, and this catch also
            // covers a filter click or a kit withdrawal, so reusing it would tell the player
            // an alteration failed when they never asked for one.
            try {
                UICommandBuilder cb = new UICommandBuilder();
                setStatus(cb, LANG_PREFIX + "actionFailed", StatusTone.Bad);
                sendUpdate(cb, false);
            } catch (Throwable ignored) {
                // The connection is already in trouble. Nothing useful is left to do.
            }
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        // Nothing to return. The page never holds an item server side, every slot is a
        // display and every action already committed against the real inventory.
    }

    private void handleSelectInput(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        if (clickedSlotIndex < 0 || clickedSlotIndex >= gearRows.size()) {
            return;
        }

        UICommandBuilder cb = new UICommandBuilder();
        UIEventBuilder eb = new UIEventBuilder();

        GearRow row = gearRows.get(clickedSlotIndex);
        ItemStack stack = resolveRowStack(store, ref, row);
        if (stack == null) {
            clearSelection();
            LastPicked.forget(playerRef.getUuid(), BENCH_KEY);
            repaintAll(cb, eb, store, ref);
            setStatus(cb, LANG_PREFIX + "staleInput", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        String family = AlterationRecipeIndex.findAlterableFamily(stack.getItem());
        if (family == null) {
            clearSelection();
            LastPicked.forget(playerRef.getUuid(), BENCH_KEY);
            repaintAll(cb, eb, store, ref);
            setStatus(cb, LANG_PREFIX + "notAlterable", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        this.selectedSection = row.section();
        this.selectedSlot = row.slot();
        this.selectedItemId = stack.getItemId();
        this.selectedFamily = family;
        LastPicked.remember(
                playerRef.getUuid(), BENCH_KEY,
                new LastPicked.Pick(selectedSection, selectedSlot, selectedItemId)
        );
        // A new piece of gear invalidates whatever variant was armed for the old one.
        this.selectedVariantId = null;

        // repaintAll already leaves the strip on the next step, which for a piece of gear
        // with no variant armed is exactly the prompt this branch wants.
        repaintAll(cb, eb, store, ref);
        sendUpdate(cb, eb, false);
    }

    private void handleSearch(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PageEvent data
    ) {
        searchQuery = data.query != null ? data.query : "";
        UICommandBuilder cb = new UICommandBuilder();
        paintOwnedList(cb, store, ref);
        sendUpdate(cb, false);
    }

    private void handleSelectVariant(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PageEvent data
    ) {
        UICommandBuilder cb = new UICommandBuilder();
        UIEventBuilder eb = new UIEventBuilder();

        if (selectedItemId == null || selectedFamily == null || data.item == null) {
            setStatus(cb, LANG_PREFIX + "selectPrompt", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        // Selecting only arms the preview. The charge is spent by ALTER and nowhere else,
        // so a misclick on a variant can never cost the player anything.
        this.selectedVariantId = data.item;

        paintVariants(cb, eb);
        paintResult(cb, store, ref);
        paintAlterState(cb, store, ref);
        // Arming a variant is a success, and the result preview lighting up already says
        // so. The strip moves on to the next step, which is pressing Alter.
        paintGuidance(cb, store, ref);
        sendUpdate(cb, eb, false);
    }

    private void handleFilter(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PageEvent data
    ) {
        UICommandBuilder cb = new UICommandBuilder();
        UIEventBuilder eb = new UIEventBuilder();

        GearFilter chosen = parseFilter(data.filter);
        if (chosen == null) {
            return;
        }

        this.filter = chosen;

        // The filter only hides cells, it never drops a selection. A player can narrow
        // the grid to Legs with a helmet still armed and the altar keeps its preview.
        paintChips(cb);
        paintOwnedList(cb, store, ref);
        sendUpdate(cb, eb, false);
    }

    private void handleAlter(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        ArmoryTelemetry.breadcrumb("ui", "alteration committed");
        UICommandBuilder cb = new UICommandBuilder();
        UIEventBuilder eb = new UIEventBuilder();

        if (selectedItemId == null || selectedFamily == null) {
            setStatus(cb, LANG_PREFIX + "selectPrompt", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        if (selectedVariantId == null || selectedVariantId.equals(selectedItemId)) {
            setStatus(cb, LANG_PREFIX + "pickVariant", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        ItemContainer container = resolveContainer(store, ref, selectedSection);
        ItemStack stack = container != null ? container.getItemStack(selectedSlot) : null;

        if (stack == null || stack.isEmpty() || !selectedItemId.equals(stack.getItemId())) {
            clearSelection();
            repaintAll(cb, eb, store, ref);
            setStatus(cb, LANG_PREFIX + "staleInput", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        CraftingRecipe recipe = findRecipe(selectedFamily, selectedVariantId);
        if (recipe == null) {
            setStatus(cb, LANG_PREFIX + "unavailable", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        boolean creative = player != null && player.getGameMode() == GameMode.Creative;

        AlterationTableBlock bench = resolveBench();

        // Charge rule. Creative skips fuel entirely. Otherwise, if the gauge is empty we
        // burn one stocked kit to refill it before crafting, Lady's instant refill. With
        // no charge and no stock left there is nothing to spend, so we refuse.
        if (!creative) {
            if (bench == null) {
                setStatus(cb, LANG_PREFIX + "benchMissing", StatusTone.Bad);
                sendUpdate(cb, eb, false);
                return;
            }

            // Same refill topUpFromStock does, so it goes through the helper rather than
            // repeating the three lines. Two copies of the spend rule would drift apart
            // the first time one of them is edited.
            if (bench.getCharges() <= 0 && !topUpFromStock()) {
                setStatus(cb, LANG_PREFIX + "outOfCharges", StatusTone.Bad);
                sendUpdate(cb, eb, false);
                return;
            }
        }

        boolean altered = AlterationTransaction.processFromInventory(
                ref,
                store,
                container,
                selectedSlot,
                selectedItemId,
                recipe,
                selectedVariantId,
                stack.getQuantity()
        );

        if (!altered) {
            setStatus(cb, LANG_PREFIX + "craftFailed", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        if (!creative && bench != null) {
            bench.consumeCharges(1);
            markBenchSaving();
            // Burn the next kit right away so the gauge reads full again instead of
            // sitting at zero with kits in the drawer. One charge can never cost two
            // kits: the refill above only runs when the gauge is already empty, and after
            // it runs the gauge is at MAX_CHARGES so this call finds a charge left and
            // does nothing.
            topUpFromStock();
        }

        // The input is gone and the variant landed somewhere in the bag. Clear the
        // selection and repaint the gear list so the new piece shows up ready to alter
        // again, then refresh the fuel gauge and confirm.
        clearSelection();
        repaintAll(cb, eb, store, ref);
        paintFuel(cb);
        setStatus(cb, LANG_PREFIX + "altered", StatusTone.Good);
        sendUpdate(cb, eb, false);
    }

    private void handleDepositKit(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        UICommandBuilder cb = new UICommandBuilder();

        AlterationTableBlock bench = resolveBench();
        if (bench == null) {
            setStatus(cb, LANG_PREFIX + "benchMissing", StatusTone.Bad);
            sendUpdate(cb, false);
            return;
        }

        if (bench.getFuelStock() >= AlterationConfig.FUEL_STOCK_CAP) {
            setStatus(cb, LANG_PREFIX + "stockFull", StatusTone.Bad);
            sendUpdate(cb, false);
            return;
        }

        CombinedItemContainer inventory = InventoryComponent.getCombined(
                store, ref, InventoryComponent.HOTBAR_FIRST
        );

        if (!inventory.removeItemStack(new ItemStack(AlterationConfig.FUEL_ITEM_ID, 1)).succeeded()) {
            setStatus(cb, LANG_PREFIX + "noKitsToStock", StatusTone.Bad);
            sendUpdate(cb, false);
            return;
        }

        bench.addFuelStock(1);
        markBenchSaving();

        // Feeding a table that sits at zero fills the gauge on the spot, which is what
        // the player expects from the button they just pressed.
        topUpFromStock();

        paintFuel(cb);
        paintAlterState(cb, store, ref);
        paintGuidance(cb, store, ref);
        sendUpdate(cb, false);
    }

    private void handleWithdrawKit(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        UICommandBuilder cb = new UICommandBuilder();

        AlterationTableBlock bench = resolveBench();
        if (bench == null || bench.getFuelStock() <= 0) {
            setStatus(cb, LANG_PREFIX + "noKitToWithdraw", StatusTone.Bad);
            sendUpdate(cb, false);
            return;
        }

        bench.setFuelStock(bench.getFuelStock() - 1);
        markBenchSaving();

        // No top up here on purpose. Withdrawing is the player deliberately taking kits
        // out, so spending one of them back into the gauge would fight the intent.

        // Hand one kit back, dropping it at the player if the bag is full.
        CombinedItemContainer inventory = InventoryComponent.getCombined(
                store, ref, InventoryComponent.HOTBAR_FIRST
        );
        SimpleItemContainer.addOrDropItemStack(
                store, ref, inventory, new ItemStack(AlterationConfig.FUEL_ITEM_ID, 1)
        );

        paintFuel(cb);
        paintAlterState(cb, store, ref);
        paintGuidance(cb, store, ref);
        sendUpdate(cb, false);
    }

    // ----- painting -----

    /**
     * Everything a change of selection touches. Grouped because a gear click moves the
     * whole screen at once, and the three call sites that clear a selection all have to
     * repaint the same set or the altar keeps showing an item that is gone.
     */
    private void repaintAll(
            @Nonnull UICommandBuilder cb,
            @Nonnull UIEventBuilder eb,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        paintInput(cb, store, ref);
        paintResult(cb, store, ref);
        paintOwnedList(cb, store, ref);
        paintVariants(cb, eb);
        paintAlterState(cb, store, ref);
        // Last, so the strip reflects the state the repaint just produced. A caller with
        // something more specific to say writes over it on the same command builder.
        paintGuidance(cb, store, ref);
    }

    private void paintChips(@Nonnull UICommandBuilder cb) {
        for (GearFilter value : GearFilter.values()) {
            cb.set("#" + value.widget + "Mark.Visible", value == filter);
        }
    }

    private void paintInput(
            @Nonnull UICommandBuilder cb,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        ItemStack stack = resolveSelectedStack(store, ref);

        // Message values only bind to TextSpans, plain strings to Text. Mixing the two
        // on one label across updates is untested, so this label always uses TextSpans.
        if (stack != null) {
            cb.set("#SourceSlot.Slots", new ItemGridSlot[] { ItemSlots.display(
                    stack, false, false,
                    ItemSlots.resolveDescriptionKey(stack, this.playerRef)
            ) });
            cb.set("#SourceSlotFill.Visible", true);
            cb.set("#SourceName.TextSpans", stack.getDisplayName());
            cb.set("#SourceDurability.Text", ItemText.formatDurability(stack));
        } else {
            cb.set("#SourceSlotFill.Visible", false);
            cb.set("#SourceSlot.Slots", new ItemGridSlot[0]);
            cb.set("#SourceName.TextSpans", Message.translation(LANG_PREFIX + "noSource"));
            cb.set("#SourceDurability.Text", "");
        }
    }

    /**
     * The result preview, the highest value element on the screen. An alteration costs a
     * charge and rewrites the item, so the player sees the exact piece and the exact
     * durability before committing. The projection is not recomputed here, it is read off
     * the stack {@link AlterationTransaction#buildOutput} would actually produce, so the
     * preview and the transaction can never drift apart.
     */
    private void paintResult(
            @Nonnull UICommandBuilder cb,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        ItemStack source = resolveSelectedStack(store, ref);

        if (source == null || selectedVariantId == null || selectedVariantId.equals(selectedItemId)) {
            cb.set("#ResultSlotFill.Visible", false);
            cb.set("#ResultSlot.Slots", new ItemGridSlot[0]);
            cb.set("#ResultArmedFrame.Visible", false);
            cb.set("#ResultName.TextSpans", Message.translation(LANG_PREFIX + "noResult"));
            cb.set("#ResultDurability.Text", "");
            return;
        }

        ItemStack projected = AlterationTransaction.buildOutput(source, selectedVariantId, source.getQuantity());
        String transition = formatDurabilityTransition(source, projected);

        cb.set("#ResultSlot.Slots", new ItemGridSlot[] { ItemSlots.display(
                projected, false, false,
                ItemSlots.resolveDescriptionKey(projected, this.playerRef)
        ) });
        cb.set("#ResultSlotFill.Visible", true);
        cb.set("#ResultArmedFrame.Visible", true);
        cb.set("#ResultName.TextSpans", projected.getDisplayName());
        cb.set("#ResultDurability.Text", transition);
    }

    private void paintFuel(@Nonnull UICommandBuilder cb) {
        AlterationTableBlock bench = resolveBench();
        int stock = bench != null ? bench.getFuelStock() : 0;
        int charges = bench != null ? bench.getCharges() : 0;

        // A plain string binds to Text directly, wrapping it in a Message here crashed
        // the client's Set command, measured live.
        cb.set("#ChargeCount.Text", charges + " / " + AlterationConfig.MAX_CHARGES);
        cb.set("#StockCount.Text", Integer.toString(stock));

        cb.set("#Pip1.Visible", charges >= 1);
        cb.set("#Pip2.Visible", charges >= 2);
        cb.set("#Pip3.Visible", charges >= 3);
    }

    private void paintOwnedList(
            @Nonnull UICommandBuilder cb,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        gearRows.clear();
        List<ItemGridSlot> slots = new ArrayList<>();
        boolean hasSelection = resolveSelectedStack(store, ref) != null;
        int alterable = 0;

        for (int section : SCANNED_SECTIONS) {
            ItemContainer container = resolveContainer(store, ref, section);
            if (container == null) {
                continue;
            }

            short capacity = container.getCapacity();
            for (short slot = 0; slot < capacity; slot++) {
                ItemStack stack = container.getItemStack(slot);
                if (stack == null || stack.isEmpty() || !AlterationRecipeIndex.isAlterable(stack.getItem())) {
                    continue;
                }

                // Counted BEFORE the chip and the search narrow it, so the empty state can
                // tell "you own nothing this bench accepts" apart from "you own plenty and
                // this chip matches none of it".
                alterable++;

                if (!matchesFilter(stack.getItem())
                        || !ItemText.matchesSearch(stack, searchQuery, playerRef)) {
                    continue;
                }

                boolean chosen = section == selectedSection
                        && slot == selectedSlot
                        && stack.getItemId().equals(selectedItemId);
                boolean dimmed = hasSelection && !chosen;
                // Worn pieces deliberately carry no marker. During the 2026-08-10
                // play-test, the decision was that no marker is better than modifying the
                // tooltip. The per-cell gold bar disappeared with the single-grid rebuild.
                // We built the obvious replacement, a "Worn" line prepended to the slot
                // description, and then removed it. A tooltip that says something the
                // item's own tooltip never would makes a mod feel foreign. That is also why
                // the invented rarity accent bar was deleted from the Scribing preview
                // card.
                //
                // Nothing behavioural is lost. Altering a worn piece still returns it to
                // the slot it was worn in, because the row remembers its section.
                slots.add(ItemSlots.display(
                        stack,
                        true,
                        dimmed,
                        ItemSlots.resolveDescriptionKey(stack, playerRef)
                ));
                gearRows.add(new GearRow(section, slot, stack.getItemId()));
            }
        }

        cb.set("#OwnedGrid.Slots", slots.toArray(new ItemGridSlot[0]));
        int shown = slots.size();
        cb.set("#GearCount.Text", Integer.toString(shown));
        cb.set("#OwnedGrid.Visible", shown > 0);
        cb.set("#GearEmpty.Visible", shown == 0);

        // Same three causes the Scribing picker distinguishes, in the same order and for
        // the same reason. This label used to be one fixed sentence declared in the
        // markup, so a chip that matched nothing claimed the bench could alter nothing the
        // player owned, which is false the moment they press All again. Painted from here
        // now, and the markup no longer declares a translation of its own: mixing a markup
        // translation with a runtime TextSpans set on one label is untested in this engine.
        String cause;
        if (alterable == 0) {
            cause = "gearEmpty";
        } else if (!searchQuery.isBlank()) {
            cause = "gearNoMatches";
        } else {
            cause = "gearNoCategory";
        }
        cb.set("#GearEmpty.TextSpans", Message.translation(LANG_PREFIX + cause));
    }

    private void paintVariants(@Nonnull UICommandBuilder cb, @Nonnull UIEventBuilder eb) {
        cb.clear("#VariantGrid");

        if (selectedFamily == null || selectedItemId == null) {
            cb.set("#VariantCount.Text", "0");
            cb.set("#VariantEmpty.Visible", true);
            return;
        }

        int index = 0;
        for (CraftingRecipe recipe : AlterationRecipeIndex.forFamily(selectedFamily)) {
            MaterialQuantity primaryOutput = recipe.getPrimaryOutput();
            if (primaryOutput == null || primaryOutput.getItemId() == null) {
                continue;
            }

            String outputId = primaryOutput.getItemId();
            boolean isCurrent = outputId.equals(selectedItemId);

            // The piece the player already holds stays in the grid, marked, so the family
            // reads as a complete set and they can see where they are standing in it.
            // ALTER refuses it, so keeping it visible costs nothing.
            String selector = "#VariantGrid[" + index + "]";
            boolean isChosen = outputId.equals(selectedVariantId);
            cb.append("#VariantGrid", VARIANT_ENTRY_DOCUMENT);
            cb.set(selector + " #Icon.ItemId", outputId);
            cb.set(selector + " #CurrentMarker.Visible", isCurrent);
            cb.set(selector + " #SelectedFrame.Visible", isChosen);
            // Same rule the gear list runs on one column over: once something is chosen,
            // everything else steps back. Nothing dims until there is a choice to contrast
            // against, so an untouched family still reads as a full set of live options.
            cb.set(selector + " #DimVeil.Visible", selectedVariantId != null && !isChosen);

            eb.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    selector + " #Entry",
                    new EventData()
                            .append(PageEvent.KEY_TYPE, TYPE_SELECT_VARIANT)
                            .append(PageEvent.KEY_ITEM, outputId),
                    false
            );
            index++;
        }

        cb.set("#VariantCount.Text", Integer.toString(index));
        cb.set("#VariantEmpty.Visible", index == 0);
    }

    /**
     * Drives the ALTER button's disabled state. The rule is the same one
     * {@link #handleAlter} enforces, including the instant refill from stocked kits, so
     * the button is never enabled on an action that would then be refused.
     */
    private void paintAlterState(
            @Nonnull UICommandBuilder cb,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        boolean hasSelection = selectedItemId != null && selectedFamily != null;
        boolean hasVariant = selectedVariantId != null && !selectedVariantId.equals(selectedItemId);

        cb.set("#AlterButton.Disabled", !(hasSelection && hasVariant && hasPower(store, ref)));
    }

    /**
     * Whether the table can pay for one alteration right now, either from a charge on the
     * gauge or from a kit it can burn on the spot. Creative never pays. Both the ALTER
     * button and the guidance strip read this one method, so they can never disagree about
     * whether the player is being stopped by fuel.
     */
    private boolean hasPower(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null && player.getGameMode() == GameMode.Creative) {
            return true;
        }

        AlterationTableBlock bench = resolveBench();
        return bench != null && (bench.getCharges() > 0 || bench.getFuelStock() > 0);
    }

    /**
     * The idle line, the one the strip falls back to whenever nothing more specific has
     * just happened. It always names the next thing the player should do, so the strip is
     * never blank and never stale. Callers with a refusal or a confirmation to show write
     * over it on the same command builder, and the client applies the last set.
     */
    private void paintGuidance(
            @Nonnull UICommandBuilder cb,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        if (selectedItemId == null || selectedFamily == null) {
            setStatus(cb, LANG_PREFIX + "guideSelectGear", StatusTone.Guide);
            return;
        }

        if (selectedVariantId == null || selectedVariantId.equals(selectedItemId)) {
            setStatus(cb, LANG_PREFIX + "guidePickVariant", StatusTone.Guide);
            return;
        }

        if (hasPower(store, ref)) {
            setStatus(cb, LANG_PREFIX + "guideReady", StatusTone.Info);
        } else {
            setStatus(cb, LANG_PREFIX + "guideNoCharges", StatusTone.Bad);
        }
    }

    /**
     * Writes one line into the guidance strip. The tone is applied on the Message rather
     * than on the label, because a server Set can carry a colored span but cannot rewrite
     * a LabelStyle, and Message values only bind to TextSpans.
     */
    private void setStatus(
            @Nonnull UICommandBuilder cb,
            @Nonnull String langKey,
            @Nonnull StatusTone tone
    ) {
        cb.set("#Status.TextSpans", Message.translation(langKey).color(tone.color));
    }

    // ----- formatting -----

    /**
     * The projected line, for example "341/400 -> 366/430". Wear carries as a fraction
     * across differing base durabilities, so a variant genuinely can change both numbers
     * and the player is entitled to see it before spending a charge.
     */
    @Nonnull
    private static String formatDurabilityTransition(@Nonnull ItemStack source, @Nonnull ItemStack projected) {
        String from = ItemText.formatDurability(source);
        String to = ItemText.formatDurability(projected);

        if (from.isEmpty() || to.isEmpty()) {
            return "";
        }
        if (from.equals(to)) {
            return from;
        }
        return from + " -> " + to;
    }

    // ----- resolution helpers -----

    private void appendPage(@Nonnull UICommandBuilder cb) {
        if (INLINE_MARKUP) {
            cb.appendInline(null, readResource(PAGE_RESOURCE));
        } else {
            cb.append(PAGE_DOCUMENT);
        }
    }

    /**
     * Whether an owned piece survives the active category chip. Weapons are anything the
     * item declares a weapon config for, armor is matched on the engine's own slot enum.
     */
    private boolean matchesFilter(@Nullable Item item) {
        if (item == null) {
            return false;
        }

        return switch (filter) {
            case All -> true;
            case Head -> hasArmorSlot(item, ItemArmorSlot.Head);
            case Chest -> hasArmorSlot(item, ItemArmorSlot.Chest);
            case Hands -> hasArmorSlot(item, ItemArmorSlot.Hands);
            case Legs -> hasArmorSlot(item, ItemArmorSlot.Legs);
            case Weapon -> item.getWeapon() != null;
        };
    }

    private static boolean hasArmorSlot(@Nonnull Item item, @Nonnull ItemArmorSlot slot) {
        ItemArmor armor = item.getArmor();
        return armor != null && armor.getArmorSlot() == slot;
    }

    /**
     * The live stack behind the current selection, or null when the bag moved under us.
     */
    @Nullable
    private ItemStack resolveSelectedStack(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        if (selectedItemId == null) {
            return null;
        }

        ItemContainer container = resolveContainer(store, ref, selectedSection);
        ItemStack current = container != null ? container.getItemStack(selectedSlot) : null;
        if (current == null || current.isEmpty() || !selectedItemId.equals(current.getItemId())) {
            return null;
        }

        return current;
    }

    @Nullable
    private ItemStack resolveRowStack(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull GearRow row
    ) {
        ItemContainer container = resolveContainer(store, ref, row.section());
        if (container == null) {
            return null;
        }

        if (row.slot() < 0 || row.slot() >= container.getCapacity()) {
            return null;
        }

        ItemStack stack = container.getItemStack(row.slot());
        if (stack == null || stack.isEmpty() || !row.itemId().equals(stack.getItemId())) {
            return null;
        }

        return stack;
    }

    @Nullable
    private ItemContainer resolveContainer(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            int sectionId
    ) {
        ComponentType<EntityStore, ? extends InventoryComponent> type =
                InventoryComponent.getComponentTypeById(sectionId);
        if (type == null) {
            return null;
        }

        InventoryComponent component = store.getComponent(ref, type);
        return component != null ? component.getInventory() : null;
    }

    @Nullable
    private CraftingRecipe findRecipe(@Nonnull String family, @Nonnull String outputId) {
        for (CraftingRecipe recipe : AlterationRecipeIndex.forFamily(family)) {
            MaterialQuantity primaryOutput = recipe.getPrimaryOutput();
            if (primaryOutput != null && outputId.equals(primaryOutput.getItemId())) {
                return recipe;
            }
        }
        return null;
    }

    @Nullable
    private AlterationTableBlock resolveBench() {
        // The page holds the exact block entity, so the charge component comes straight
        // off it. ensureAndGetComponent attaches a fresh one for a table placed before
        // this feature that already had a block entity from the old bench.
        return blockEntity.getStore().ensureAndGetComponent(
                blockEntity, AlterationTableBlock.getComponentType()
        );
    }

    /**
     * Burns one stocked kit the moment the gauge runs dry, so the table shows a full
     * gauge and one fewer kit rather than an empty gauge with the kit unspent. The craft
     * time refill in {@link #handleAlter} stays as the fallback for a table that reaches
     * zero some other way, and the two cannot both spend a kit for the same charge
     * because each one only fires when the gauge is already at zero and each one leaves
     * it at MAX_CHARGES.
     *
     * @return true when a kit was spent, so the caller can repaint
     */
    private boolean topUpFromStock() {
        AlterationTableBlock bench = resolveBench();
        if (bench == null || bench.getCharges() > 0 || bench.getFuelStock() <= 0) {
            return false;
        }

        bench.setFuelStock(bench.getFuelStock() - 1);
        bench.setCharges(AlterationConfig.MAX_CHARGES);
        markBenchSaving();
        return true;
    }

    private void markBenchSaving() {
        BlockModule.BlockStateInfo info = blockEntity.getStore().getComponent(
                blockEntity, BlockModule.BlockStateInfo.getComponentType()
        );
        if (info != null) {
            info.markNeedsSaving();
        }
    }

    private void clearSelection() {
        this.selectedSection = Integer.MIN_VALUE;
        this.selectedSlot = -1;
        this.selectedItemId = null;
        this.selectedFamily = null;
        this.selectedVariantId = null;
    }

    @Nullable
    private static GearFilter parseFilter(@Nullable String value) {
        if (value == null) {
            return null;
        }
        for (GearFilter candidate : GearFilter.values()) {
            if (candidate.name().equals(value)) {
                return candidate;
            }
        }
        return null;
    }

    private static int parseSlotIndex(@Nullable String rawData) {
        if (rawData == null) {
            return -1;
        }
        Matcher matcher = SLOT_INDEX_FIELD.matcher(rawData);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    @Nonnull
    private static String readResource(@Nonnull String path) {
        try (InputStream in = AlterationPage.class.getResourceAsStream(path)) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "";
        }
    }

    /**
     * Decoded event payload. SlotIndex is intentionally absent because the typed codec
     * drops that client-injected field. The raw event overload reads it instead.
     */
    public static final class PageEvent {
        static final String KEY_TYPE = "Type";
        static final String KEY_ITEM = "Item";
        static final String KEY_FILTER = "Filter";
        static final String KEY_QUERY = "@Query";

        public static final BuilderCodec<PageEvent> CODEC = BuilderCodec.builder(PageEvent.class, PageEvent::new)
                .append(
                        new KeyedCodec<>(KEY_TYPE, Codec.STRING),
                        (event, value) -> event.type = value,
                        event -> event.type
                )
                .add()
                .append(
                        new KeyedCodec<>(KEY_ITEM, Codec.STRING),
                        (event, value) -> event.item = value,
                        event -> event.item
                )
                .add()
                .append(
                        new KeyedCodec<>(KEY_FILTER, Codec.STRING),
                        (event, value) -> event.filter = value,
                        event -> event.filter
                )
                .add()
                .append(
                        new KeyedCodec<>(KEY_QUERY, Codec.STRING),
                        (event, value) -> event.query = value,
                        event -> event.query
                )
                .add()
                .build();

        private String type;
        private String item;
        private String filter;
        private String query;
    }
}
