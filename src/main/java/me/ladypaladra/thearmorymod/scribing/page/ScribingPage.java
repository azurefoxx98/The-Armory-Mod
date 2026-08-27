package me.ladypaladra.thearmorymod.scribing.page;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.scribing.ScribingConfig;
import me.ladypaladra.thearmorymod.scribing.ScribingMarkup;
import me.ladypaladra.thearmorymod.scribing.ScribingSeal;
import me.ladypaladra.thearmorymod.scribing.ScribingText;
import me.ladypaladra.thearmorymod.scribing.ScribingWrite;
import me.ladypaladra.thearmorymod.telemetry.ArmoryTelemetry;
import me.ladypaladra.thearmorymod.ui.ItemSlots;
import me.ladypaladra.thearmorymod.ui.ItemText;
import me.ladypaladra.thearmorymod.ui.LastPicked;
import me.ladypaladra.thearmorymod.ui.MessageTrees;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-driven screen for selecting an inventory stack, previewing tooltip text and
 * committing an inscription directly to that same stack.
 */
public class ScribingPage extends InteractiveCustomUIPage<ScribingPage.PageEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String PAGE_DOCUMENT = "Pages/TheArmory/ScribingPage.ui";
    private static final String LANG_PREFIX = "server.customUI.scribingPage.";
    private static final String BENCH_KEY = "scribing";

    // What divides the parts of a field counter. The counters used to run their values
    // together on a double space and nothing else, so "1 line  0  no limit" read as three
    // unrelated fragments rather than one reading. During the 2026-08-09 play-test, a
    // tester described it as "detached, weird spacing, no cap letter, hardly
    // understandable".
    //
    // The U+2022 glyph is a compatibility choice, not a matter of taste. A middle dot
    // U+00B7 is the usual typographic choice, but it appears zero times across the 24
    // shipped en-US language files, 10972 lines, so nothing proves the game's font carries
    // it and an absent glyph
    // draws as a blank box. The bullet appears 213 times in that same corpus, in item
    // descriptions the client renders in the tooltip, so it is attested in the font we are
    // drawing with. Written as an escape rather than as a literal so the string cannot be
    // damaged by whatever encoding a future build assumes for this source file. build.gradle
    // sets no options.encoding, so javac takes the platform default and only happens to be
    // right on this JDK. The escape does not care.
    private static final String SEPARATOR = " \u2022 ";

    // Do not call this "no limit". That made the counter lie to every admin. During the
    // 2026-08-10 play-test, a tester reported that "it always say no limit ... while
    // there is limits indeed". Holding the bypass permission lifts the server's rendered
    // caps and nothing else. The raw cap is `MaxLength` on the two text widgets, it is
    // declared in ScribingPage.ui, it is enforced by the client before a keystroke ever
    // reaches us, and it is not one of the properties a server command can rewrite at
    // runtime, so no permission can ever lift it. A bypass holder therefore still stops
    // dead at 200 typed characters on the name, which is exactly the limit the tester hit.
    //
    // The counter now names the bound that actually binds instead of promising there is
    // none. NAME_MAX_RAW_CHARS and DESC_MAX_RAW_CHARS must keep matching the MaxLength
    // values in the markup: two homes for one number, unavoidable because markup cannot
    // read config, and the markup copy is the one that decides.


    /** The client's own field on a slot event. See {@link #parseSlotIndex}. */
    private static final Pattern SLOT_INDEX_FIELD =
            Pattern.compile("\"SlotIndex\"\\s*:\\s*\"?(-?\\d+)\"?");

    // Inventory sections walked for stacks a player can inscribe. Order sets the list order
    // on screen, hand and bag first, then worn armor and the side pouches.
    //
    // The tools section is deliberately not here, and leaving it out is the whole fix for a
    // bug that reached players in 1.21.0. That section is not player storage. The engine's
    // builder tools system hands every player the six EditorTool items when the entity is
    // added, matching on the Player and Tool components with no game mode condition, and it
    // clears and refills the container each time. So a survival player who has never opened
    // a creative menu is carrying them, no ordinary inventory screen draws that section, and
    // this picker was listing them as stacks to pick and write on. Players reported seeing
    // creative tools they could not get out, and they could not because there is no way to
    // get them out.
    //
    // Inscribing one was worse than showing it. The write would land in a container the
    // server rewrites on the next join, so the ink was spent and the text was gone.
    //
    // The engine draws this same line itself. InventoryComponent.EVERYTHING is armor,
    // hotbar, utility, storage and backpack, exactly these five, and the method that builds
    // those groups takes the tool component type under the parameter name
    // ignoredToolInventoryComponentType and puts it in no group at all. A section added here
    // in future has to be one the player can actually reach and empty.
    private static final int[] SCANNED_SECTIONS = {
            InventoryComponent.HOTBAR_SECTION_ID,
            InventoryComponent.STORAGE_SECTION_ID,
            InventoryComponent.ARMOR_SECTION_ID,
            InventoryComponent.BACKPACK_SECTION_ID,
            InventoryComponent.UTILITY_SECTION_ID
    };

    private static final String TYPE_DRAFT = "Draft";
    private static final String TYPE_FILTER = "Filter";
    private static final String TYPE_SEARCH = "Search";
    private static final String TYPE_APPLY = "Apply";
    private static final String TYPE_CLEAR = "Clear";
    private static final String TYPE_SELECT = "Select";
    private static final String TYPE_CLOSE = "Close";
    private static final String TYPE_SEAL = "Seal";
    private static final String TYPE_SEAL_CONFIRM = "SealConfirm";
    private static final String TYPE_SEAL_CANCEL = "SealCancel";
    private static final String TYPE_BREAK_SEAL = "BreakSeal";

    private enum ItemFilter {
        All("ChipAll"),
        Armor("ChipArmor"),
        Weapon("ChipWeapon"),
        Tool("ChipTool"),
        Blocks("ChipBlocks"),
        Inscribed("ChipInscribed");

        // Widget id stem, the button is <stem>Button and the active underline is <stem>Mark.
        private final String widget;

        ItemFilter(@Nonnull String widget) {
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

    private int selectedSection = Integer.MIN_VALUE;
    private short selectedSlot = -1;
    @Nullable
    private String selectedItemId;
    @Nonnull
    private String searchQuery = "";
    @Nonnull
    private ItemFilter filter = ItemFilter.All;
    @Nonnull
    private String draftName = "";
    @Nonnull
    private String draftDescription = "";
    private boolean pickerHasMatches;

    /**
     * Whether SEAL has been pressed and the confirm row is showing.
     * <p>
     * This is the only guard on an irreversible act, so what clears it matters as much
     * as what sets it. A selection change clears it, because the warning on screen names
     * the item the player was looking at when they armed it. CANCEL clears it. Typing does
     * not, and that is deliberate rather than an omission: {@link #paintDraft} owns exactly
     * seven selectors on the typing hot path and widening it is how this page gets
     * fragile, and it is not needed, because #ConfirmSealButton re-reads both field values
     * at the moment it is pressed. What gets sealed is therefore always what the preview
     * column is showing, so there is no gap between what they see and what they get.
     */
    private boolean pendingSeal;

    /**
     * Where each grid slot came from, in grid order, as of the last paint.
     * <p>
     * The client identifies a click by position and knows nothing about inventory
     * sections, so this is the whole translation layer. It is rebuilt inside
     * {@link #paintPicker} in the same loop that builds the slots, because an index that
     * means one thing to the client and another to the server is a way to inscribe the
     * wrong item.
     */
    @Nonnull
    private final List<PickerRow> pickerRows = new ArrayList<>();

    /** Index carried by the most recent raw event, -1 when the payload had none. */
    private int clickedSlotIndex = -1;

    /** One painted grid slot, and where its stack lives. */
    private record PickerRow(int section, short slot, @Nonnull String itemId) {
    }

    public ScribingPage(@Nonnull PlayerRef playerRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageEvent.CODEC);
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder,
            @Nonnull Store<EntityStore> store
    ) {
        ArmoryTelemetry.breadcrumb("ui", "scribing page opened");
        appendPage(commandBuilder);

        LastPicked.Pick pick = LastPicked.recall(playerRef.getUuid(), BENCH_KEY);
        if (pick != null) {
            selectedSection = pick.section();
            selectedSlot = pick.slot();
            selectedItemId = pick.itemId();
            ItemStack stack = resolveSelectedStack(store, ref);
            if (stack == null) {
                clearSelection();
                LastPicked.forget(playerRef.getUuid(), BENCH_KEY);
            } else {
                ScribingWrite.Existing existing = ScribingWrite.readExisting(stack);
                draftName = existing.name() != null ? existing.name() : "";
                draftDescription = existing.description() != null ? existing.description() : "";

                // Seed the draft and both boxes together, or restoring the selection destroys
                // text. Seeding only the server side fields leaves the widgets
                // empty over an item that has an inscription, and the preview would show
                // text the player cannot see in either box. The first keystroke then sends
                // the box contents back as the whole draft, so an existing name collapses
                // to that one character and Inscribe writes the truncation. handleSelect
                // has always pushed both values for exactly this reason, and a restore is
                // a selection by another route, so it owes the same two writes.
                commandBuilder.set("#NameInput.Value", draftName);
                commandBuilder.set("#DescriptionInput.Value", draftDescription);
            }
        }

        // Both text bindings carry both selectors deliberately. No shipped
        // ValueChanged binding targets a multiline field, so description events are not
        // proven. If they do not fire, touching the name still refreshes the full
        // preview, and Apply reads both fields again so the committed text stays exact.
        EventData draftData = new EventData()
                .append(PageEvent.KEY_TYPE, TYPE_DRAFT)
                .append(PageEvent.KEY_NAME, "#NameInput.Value")
                .append(PageEvent.KEY_DESC, "#DescriptionInput.Value");
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.ValueChanged, "#NameInput", draftData, false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#DescriptionInput",
                new EventData()
                        .append(PageEvent.KEY_TYPE, TYPE_DRAFT)
                        .append(PageEvent.KEY_NAME, "#NameInput.Value")
                        .append(PageEvent.KEY_DESC, "#DescriptionInput.Value"),
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
                CustomUIEventBindingType.Activating,
                "#InscribeButton",
                new EventData()
                        .append(PageEvent.KEY_TYPE, TYPE_APPLY)
                        .append(PageEvent.KEY_NAME, "#NameInput.Value")
                        .append(PageEvent.KEY_DESC, "#DescriptionInput.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#ClearButton",
                new EventData().append(PageEvent.KEY_TYPE, TYPE_CLEAR),
                false
        );

        // Both seal presses carry the two field selectors for the same reason Inscribe
        // does: the draft the server holds is only as fresh as the last ValueChanged that
        // arrived, and no shipped binding targets a multiline field so description events
        // are not proven. Reading both values at the press is what makes the committed
        // text exact, and it is doubly load-bearing here because the confirm press is the
        // one that writes something that can never be edited again.
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#SealButton",
                new EventData()
                        .append(PageEvent.KEY_TYPE, TYPE_SEAL)
                        .append(PageEvent.KEY_NAME, "#NameInput.Value")
                        .append(PageEvent.KEY_DESC, "#DescriptionInput.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#ConfirmSealButton",
                new EventData()
                        .append(PageEvent.KEY_TYPE, TYPE_SEAL_CONFIRM)
                        .append(PageEvent.KEY_NAME, "#NameInput.Value")
                        .append(PageEvent.KEY_DESC, "#DescriptionInput.Value"),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#CancelSealButton",
                new EventData().append(PageEvent.KEY_TYPE, TYPE_SEAL_CANCEL),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#BreakSealButton",
                new EventData().append(PageEvent.KEY_TYPE, TYPE_BREAK_SEAL),
                false
        );
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#CloseButton",
                new EventData().append(PageEvent.KEY_TYPE, TYPE_CLOSE),
                false
        );

        for (ItemFilter value : ItemFilter.values()) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#" + value.widget + "Button",
                    new EventData()
                            .append(PageEvent.KEY_TYPE, TYPE_FILTER)
                            .append(PageEvent.KEY_FILTER, value.name()),
                    false
            );
        }

        // The picker's click is bound once and never again. The column is a single grid, so
        // unlike the per-cell version this selector never changes and a repaint has no
        // bindings to reissue. That is not only tidier, it removes the question of whether
        // re-adding a binding to the same selector stacks up a second handler client side.
        //
        // The payload carries only the type. Which slot was hit arrives in the client's
        // own SlotIndex field, read in the raw handleDataEvent below, because the typed
        // codec drops it. Nothing here is an "@" key, so nothing here can ask the client
        // for a property that does not exist, which is the form that disconnects people.
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.SlotClicking,
                "#PickerGrid",
                new EventData().append(PageEvent.KEY_TYPE, TYPE_SELECT),
                false
        );

        paintChips(commandBuilder);
        paintPicker(commandBuilder, store, ref);
        paintSelection(commandBuilder, store, ref);
        Validation validation = validate(draftName, draftDescription);
        paintDraft(commandBuilder, store, ref, validation);
        paintPreviewChrome(commandBuilder, store, ref, validation);
    }

    /**
     * The raw payload, read for one field the typed layer cannot see.
     * <p>
     * A slot click carries the clicked index as a client-injected {@code SlotIndex}
     * sibling of our own keys, and the engine's typed codec drops it. There is no second
     * route to it: this overload is the only place it exists. Both mods that drive an
     * {@code ItemGrid} from a server page do exactly this, Live Painter's
     * {@code ItemCustomizerPage} and {@code CrucibleUIProbe}, and the probe's 457 logged
     * payloads are where the field's name and shape come from.
     * <p>
     * Only the read is guarded. A failure to decode the event proper still surfaces the
     * way it does everywhere else on this page rather than being swallowed here.
     */
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

        // An exception out of this method removes the player's entity and disconnects
        // them. This is measured behavior, not a guess. The server log captured it at
        // 2026-08-11 11:49:05. A throw
        // added inside ScribingWrite.apply reached here through paintDraft and the engine
        // logged "TickInteractionManagerSystem: Exception while ticking entity
        // interactions! Removing!" and dropped a tester mid play-test.
        //
        // So every handler runs inside this catch. The point is not to hide bugs, it is
        // that the blast radius of one is a sentence in the status strip rather than the
        // tester losing their session. The full stack is logged at SEVERE, which is how the
        // one above was diagnosed, and the player is told plainly that it failed.
        //
        // Do not narrow this to a specific exception type. The whole value is that it
        // covers the bug nobody predicted, which is the only kind that gets this far.
        // Throwable rather than Exception, because a stack overflow from a deep message tree
        // arrives as an Error and costs the player their session if it escapes this method.
        // It does also swallow a genuinely fatal condition such as running out of memory, and
        // that is a deliberate trade: the severe log below is what keeps such a case visible.
        try {
            switch (data.type) {
                case TYPE_DRAFT -> handleDraft(ref, store, data);
                case TYPE_FILTER -> handleFilter(ref, store, data);
                case TYPE_SEARCH -> handleSearch(ref, store, data);
                case TYPE_APPLY -> handleApply(ref, store, data);
                case TYPE_CLEAR -> handleClear(ref, store);
                case TYPE_SELECT -> handleSelect(ref, store);
                case TYPE_SEAL -> handleSeal(ref, store, data);
                case TYPE_SEAL_CONFIRM -> handleSealConfirm(ref, store, data);
                case TYPE_SEAL_CANCEL -> handleSealCancel(ref, store);
                case TYPE_BREAK_SEAL -> handleBreakSeal(ref, store);
                case TYPE_CLOSE -> close();
                default -> {
                }
            }
        } catch (Throwable failure) {
            LOGGER.atSevere().withCause(failure).log(
                    "Scribing page failed handling event type=%s for %s. The player was kept in the "
                            + "world and told it failed.",
                    data.type, playerRef.getUuid()
            );
            ArmoryTelemetry.pageFailure("scribing", String.valueOf(data.type), failure);
            // Best effort, and deliberately the smallest possible write. Whatever just went
            // wrong may have left the page half painted, so this does not try to repaint it.
            try {
                UICommandBuilder cb = new UICommandBuilder();
                setStatus(cb, LANG_PREFIX + "writeFailed", StatusTone.Bad);
                sendUpdate(cb, false);
            } catch (Throwable ignored) {
                // The connection is already in trouble. Nothing useful is left to do.
            }
        }
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        // The page never takes custody of an item, so closing has nothing to return.
    }

    /**
     * Turns a clicked grid index back into an inventory position, then selects it.
     * <p>
     * The index is only meaningful against the row list from the paint the player was
     * actually looking at, so the stale-target guard is not optional bookkeeping: if the
     * inventory moved under them, or the search refiltered between the press and its
     * arrival, the row's recorded item id no longer matches the slot and the selection is
     * refused rather than silently landing on a neighbour. That refusal is the same one
     * the old per-cell version gave, reached the same way, just keyed on an index instead
     * of on three strings the client had to carry back.
     */
    private void handleSelect(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        UICommandBuilder cb = new UICommandBuilder();
        UIEventBuilder eb = new UIEventBuilder();

        // Changing item disarms a pending seal. The warning on screen names the item they
        // were looking at when they pressed SEAL, so carrying that arm onto a different
        // item is exactly the mis-seal the confirm step exists to prevent.
        pendingSeal = false;

        PickerRow row = clickedSlotIndex >= 0 && clickedSlotIndex < pickerRows.size()
                ? pickerRows.get(clickedSlotIndex)
                : null;
        ItemStack stack = row != null ? resolveRowStack(store, ref, row) : null;
        if (stack == null) {
            clearSelection();
            LastPicked.forget(playerRef.getUuid(), BENCH_KEY);
            paintPicker(cb, store, ref);
            paintSelection(cb, store, ref);
            paintDraft(cb, store, ref, validate("", ""));
            paintPreviewChrome(cb, store, ref, validate("", ""));
            setStatus(cb, LANG_PREFIX + "staleTarget", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        selectedSection = row.section();
        selectedSlot = row.slot();
        selectedItemId = stack.getItemId();
        LastPicked.remember(
                playerRef.getUuid(), BENCH_KEY,
                new LastPicked.Pick(selectedSection, selectedSlot, selectedItemId)
        );
        ScribingWrite.Existing existing = ScribingWrite.readExisting(stack);
        draftName = existing.name() != null ? existing.name() : "";
        draftDescription = existing.description() != null ? existing.description() : "";

        cb.set("#NameInput.Value", draftName);
        cb.set("#DescriptionInput.Value", draftDescription);
        paintPicker(cb, store, ref);
        paintSelection(cb, store, ref);
        Validation validation = validate(draftName, draftDescription);
        paintDraft(cb, store, ref, validation);
        paintPreviewChrome(cb, store, ref, validation);
        sendUpdate(cb, eb, false);
    }

    private void handleDraft(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PageEvent data
    ) {
        draftName = value(data.name);
        draftDescription = value(data.description);
        UICommandBuilder cb = new UICommandBuilder();

        // This hot path updates only the two preview spans, two counters, two errors and
        // the status. In particular it never writes a Value back to a focused field,
        // because doing so resets the caret, and it never rebuilds the picker.
        paintDraft(cb, store, ref, validate(draftName, draftDescription));
        sendUpdate(cb, false);
    }

    private void handleSearch(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PageEvent data
    ) {
        searchQuery = value(data.query);
        UICommandBuilder cb = new UICommandBuilder();
        UIEventBuilder eb = new UIEventBuilder();
        paintPicker(cb, store, ref);
        sendUpdate(cb, eb, false);
    }

    private void handleFilter(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PageEvent data
    ) {
        UICommandBuilder cb = new UICommandBuilder();
        UIEventBuilder eb = new UIEventBuilder();

        ItemFilter chosen = parseFilter(data.filter);
        if (chosen == null) {
            return;
        }

        this.filter = chosen;

        // The filter only hides cells, it never drops the selection or the player's draft.
        paintChips(cb);
        paintPicker(cb, store, ref);
        sendUpdate(cb, eb, false);
    }

    private void handleApply(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PageEvent data
    ) {
        ArmoryTelemetry.breadcrumb("ui", "scribing inscription applied");
        UICommandBuilder cb = new UICommandBuilder();
        UIEventBuilder eb = new UIEventBuilder();

        if (selectedItemId == null) {
            setStatus(cb, LANG_PREFIX + "noTarget", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        ItemContainer container = resolveContainer(store, ref, selectedSection);
        ItemStack stack = resolveSelectedStack(store, ref);
        if (container == null || stack == null) {
            clearSelection();
            paintPicker(cb, store, ref);
            paintSelection(cb, store, ref);
            paintDraft(cb, store, ref, validate("", ""));
            paintPreviewChrome(cb, store, ref, validate("", ""));
            setStatus(cb, LANG_PREFIX + "staleTarget", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        // This guard is the only enforcement, not a backstop. ScribingWrite.apply is a
        // pure builder that deliberately does not refuse a sealed
        // stack: it once did, and because the preview path also calls it, sealing an item
        // threw on its own success path and disconnected the tester. The long note on
        // apply carries the incident. So the rule that a sealed inscription cannot be
        // overwritten lives here and in handleSealConfirm, at the two places that actually
        // commit, where a refusal can be shown in the status strip instead of thrown.
        if (ScribingSeal.isSealed(stack)) {
            repaintAll(cb, store, ref, validate(draftName, draftDescription));
            setStatus(cb, LANG_PREFIX + "alreadySealed", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        draftName = value(data.name);
        draftDescription = value(data.description);
        Validation validation = validate(draftName, draftDescription);
        if (!validation.ok()) {
            paintDraft(cb, store, ref, validation);
            sendUpdate(cb, eb, false);
            return;
        }

        boolean carriesDisplay = hasItemDisplay(stack);
        if (validation.nameOk().empty() && validation.descriptionOk().empty() && !carriesDisplay) {
            paintDraft(cb, store, ref, validation);
            setStatus(cb, LANG_PREFIX + "nothingToChange", StatusTone.Info);
            sendUpdate(cb, eb, false);
            return;
        }

        // Cost is checked here and only spent after the write succeeds. Spending first
        // looks natural and is wrong: the write can still fail on a stale slot, and the
        // player would have paid an inkwell for nothing with no way to notice. Ordering it
        // this way means the only remaining race is the player emptying their bag between
        // the check and the charge, which costs the server one free inscription and costs
        // the player nothing, and it is logged.
        boolean mustPay = charges(store, ref);
        CombinedItemContainer inventory = mustPay
                ? paymentInventory(store, ref)
                : null;
        ItemStack cost = mustPay
                ? new ItemStack(ScribingConfig.COST_ITEM_ID, ScribingConfig.COST_QUANTITY)
                : null;

        if (inventory != null && !inventory.canRemoveItemStack(cost)) {
            // Repaint the count in the refusal colour rather than lighting a separate
            // error widget. The status strip names the item, never the configuration
            // that produced the rule.
            cb.set("#CostAmount.TextSpans",
                    costSpan(countCarried(store, ref, ScribingConfig.COST_ITEM_ID)));
            setStatus(cb, LANG_PREFIX + "cannotAfford", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        Message name = ScribingWrite.toMessage(validation.nameOk().runs());
        Message description = ScribingWrite.toMessage(validation.descriptionOk().runs());
        ItemStack replacement = ScribingWrite.apply(stack, name, description);
        if (!ScribingWrite.commit(container, selectedSlot, stack, replacement)) {
            LOGGER.atWarning().log(
                    "Could not inscribe item %s in inventory section %d, slot %d.",
                    selectedItemId,
                    selectedSection,
                    selectedSlot
            );
            setStatus(cb, LANG_PREFIX + "writeFailed", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        if (inventory != null && !inventory.removeItemStack(cost).succeeded()) {
            // The item is already inscribed and taking it back would be worse than letting
            // this one through, so the write stands and the miss is recorded instead.
            LOGGER.atWarning().log(
                    "Inscribed %s without charging %s, the cost was affordable at the check and gone by the charge.",
                    selectedItemId,
                    ScribingConfig.COST_ITEM_ID
            );
        }

        paintPicker(cb, store, ref);
        paintSelection(cb, store, ref);
        paintDraft(cb, store, ref, validation);
        paintPreviewChrome(cb, store, ref, validation);
        setStatus(cb, LANG_PREFIX + "inscribed", StatusTone.Good);
        sendUpdate(cb, eb, false);
    }

    /**
     * SEAL, which arms and writes nothing.
     * <p>
     * The arming step is the point. Sealing cannot be undone by the player who did it,
     * so it takes two deliberate presses on two differently labelled buttons, and the
     * markup places the confirm where the cursor is not after this press. Every refusal
     * that would stop the commit is checked here too, so the player is never walked into a
     * confirmation for something that was going to fail anyway.
     */
    private void handleSeal(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PageEvent data
    ) {
        UICommandBuilder cb = new UICommandBuilder();
        UIEventBuilder eb = new UIEventBuilder();

        draftName = value(data.name);
        draftDescription = value(data.description);

        // Refused before the player is walked into a confirmation. A seal that cannot later
        // be undone correctly is worse than no seal, and inscribing is unaffected because it
        // never touches the raw display key.
        if (!ScribingSeal.isDisplayKeyTrusted()) {
            pendingSeal = false;
            setStatus(cb, LANG_PREFIX + "sealUnavailable", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        ItemStack stack = resolveSelectedStack(store, ref);
        if (stack == null) {
            clearSelection();
            pendingSeal = false;
            repaintAll(cb, store, ref, validate("", ""));
            setStatus(cb, LANG_PREFIX + "staleTarget", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        // Unreachable while the editor is the only place SEAL is drawn, and checked anyway
        // for the same reason handleApply checks it. Arming on an already sealed item would
        // walk the player into a confirmation that handleSealConfirm is going to refuse.
        if (ScribingSeal.isSealed(stack)) {
            repaintAll(cb, store, ref, validate(draftName, draftDescription));
            setStatus(cb, LANG_PREFIX + "alreadySealed", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        Validation validation = validate(draftName, draftDescription);
        if (!validation.ok()) {
            paintDraft(cb, store, ref, validation);
            sendUpdate(cb, eb, false);
            return;
        }

        // Sealing an item that carries no inscription would lock in nothing at all, so it
        // is refused rather than silently spending a lock on an empty promise.
        if (validation.nameOk().empty()
                && validation.descriptionOk().empty()
                && !hasItemDisplay(stack)) {
            paintDraft(cb, store, ref, validation);
            setStatus(cb, LANG_PREFIX + "nothingToSeal", StatusTone.Info);
            sendUpdate(cb, eb, false);
            return;
        }

        pendingSeal = true;
        paintState(cb, store, ref);
        setStatus(cb, LANG_PREFIX + "sealArmed", StatusTone.Bad);
        sendUpdate(cb, eb, false);
    }

    private void handleSealCancel(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        pendingSeal = false;
        UICommandBuilder cb = new UICommandBuilder();
        paintState(cb, store, ref);
        paintDraft(cb, store, ref, validate(draftName, draftDescription));
        sendUpdate(cb, false);
    }

    /**
     * The irreversible one. Ordering matches {@link #handleApply} exactly: both costs are
     * checked here, the item is written, and only then are they spent. Spending first
     * reads naturally and is wrong, because the write can still fail on a stale slot and
     * the player would have paid a lock for nothing with no way to notice.
     */
    private void handleSealConfirm(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull PageEvent data
    ) {
        ArmoryTelemetry.breadcrumb("ui", "scribing seal committed");
        UICommandBuilder cb = new UICommandBuilder();
        UIEventBuilder eb = new UIEventBuilder();

        // Checked again rather than assumed from the arm step. This is the press that spends
        // the lock and rewrites the item, so it verifies for itself.
        if (!ScribingSeal.isDisplayKeyTrusted()) {
            pendingSeal = false;
            paintState(cb, store, ref);
            setStatus(cb, LANG_PREFIX + "sealUnavailable", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        // A confirm that arrives without an arm is not trusted. The client cannot normally
        // produce one, and an irreversible act is the wrong place to assume that holds.
        if (!pendingSeal) {
            paintState(cb, store, ref);
            sendUpdate(cb, eb, false);
            return;
        }
        pendingSeal = false;

        ItemContainer container = resolveContainer(store, ref, selectedSection);
        ItemStack stack = resolveSelectedStack(store, ref);
        if (container == null || stack == null) {
            clearSelection();
            repaintAll(cb, store, ref, validate("", ""));
            setStatus(cb, LANG_PREFIX + "staleTarget", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }
        if (ScribingSeal.isSealed(stack)) {
            repaintAll(cb, store, ref, validate(draftName, draftDescription));
            setStatus(cb, LANG_PREFIX + "alreadySealed", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        draftName = value(data.name);
        draftDescription = value(data.description);
        Validation validation = validate(draftName, draftDescription);
        if (!validation.ok()) {
            paintState(cb, store, ref);
            paintDraft(cb, store, ref, validation);
            sendUpdate(cb, eb, false);
            return;
        }
        if (validation.nameOk().empty()
                && validation.descriptionOk().empty()
                && !hasItemDisplay(stack)) {
            paintState(cb, store, ref);
            paintDraft(cb, store, ref, validation);
            setStatus(cb, LANG_PREFIX + "nothingToSeal", StatusTone.Info);
            sendUpdate(cb, eb, false);
            return;
        }

        // Ink pays for a CHANGE of text, so sealing an inscription the player is not
        // editing costs the lock alone. Charging for a write that writes the same words
        // back would be a fee for nothing.
        ScribingWrite.Existing existing = ScribingWrite.readExisting(stack);
        boolean textChanges = !draftName.equals(existing.name() == null ? "" : existing.name())
                || !draftDescription.equals(existing.description() == null ? "" : existing.description());

        CombinedItemContainer inventory = paymentInventory(store, ref);
        ItemStack inkCost = textChanges && charges(store, ref)
                ? new ItemStack(ScribingConfig.COST_ITEM_ID, ScribingConfig.COST_QUANTITY)
                : null;
        boolean needLock = sealRequired(store, ref);
        ItemStack lockCost = needLock && ScribingConfig.SEAL_CONSUMES
                ? new ItemStack(ScribingConfig.SEAL_ITEM_ID, ScribingConfig.SEAL_QUANTITY)
                : null;

        if (inventory != null && inkCost != null && !inventory.canRemoveItemStack(inkCost)) {
            cb.set("#CostAmount.TextSpans",
                    costSpan(countCarried(store, ref, ScribingConfig.COST_ITEM_ID)));
            paintState(cb, store, ref);
            setStatus(cb, LANG_PREFIX + "cannotAfford", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }
        if (needLock
                && countCarried(store, ref, ScribingConfig.SEAL_ITEM_ID) < ScribingConfig.SEAL_QUANTITY) {
            cb.set("#SealAmount.TextSpans",
                    sealSpan(countCarried(store, ref, ScribingConfig.SEAL_ITEM_ID)));
            paintState(cb, store, ref);
            setStatus(cb, LANG_PREFIX + "cannotAffordSeal", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        // One stack carrying both the inscription and the seal, committed in one slot
        // replacement, so an item can never end up inscribed but unsealed after the lock
        // has been taken.
        Message name = ScribingWrite.toMessage(validation.nameOk().runs());
        Message description = ScribingWrite.toMessage(validation.descriptionOk().runs());
        ItemStack replacement = ScribingWrite.applyAndSeal(
                stack,
                name,
                description,
                tooltipBase(stack, name, description),
                playerRef.getUsername(),
                playerRef.getUuid(),
                System.currentTimeMillis()
        );
        if (!ScribingWrite.commit(container, selectedSlot, stack, replacement)) {
            LOGGER.atWarning().log(
                    "Could not seal item %s in inventory section %d, slot %d.",
                    selectedItemId, selectedSection, selectedSlot
            );
            paintState(cb, store, ref);
            setStatus(cb, LANG_PREFIX + "writeFailed", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        spend(inventory, inkCost, "inscribe");
        spend(inventory, lockCost, "seal");

        repaintAll(cb, store, ref, validation);
        setStatus(cb, LANG_PREFIX + "sealed", StatusTone.Good);
        sendUpdate(cb, eb, false);
    }

    /**
     * Staff removing a seal. Permission is re-checked here and not merely at paint time,
     * because a hidden widget is a UI convenience and never an authorisation.
     */
    private void handleBreakSeal(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store
    ) {
        UICommandBuilder cb = new UICommandBuilder();
        UIEventBuilder eb = new UIEventBuilder();

        // Breaking is the operation that restores the player's own text from the stored key.
        // With the key untrusted it would restore nothing and discard what they wrote, so it
        // is refused rather than attempted.
        if (!ScribingSeal.isDisplayKeyTrusted()) {
            setStatus(cb, LANG_PREFIX + "sealUnavailable", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        if (!playerRef.hasPermission(ScribingConfig.BREAK_SEAL_PERMISSION)) {
            setStatus(cb, LANG_PREFIX + "alreadySealed", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        ItemContainer container = resolveContainer(store, ref, selectedSection);
        ItemStack stack = resolveSelectedStack(store, ref);
        if (container == null || stack == null) {
            clearSelection();
            repaintAll(cb, store, ref, validate("", ""));
            setStatus(cb, LANG_PREFIX + "staleTarget", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        ItemStack replacement = ScribingSeal.breakSeal(stack);
        if (!ScribingWrite.commit(container, selectedSlot, stack, replacement)) {
            paintState(cb, store, ref);
            setStatus(cb, LANG_PREFIX + "writeFailed", StatusTone.Bad);
            sendUpdate(cb, eb, false);
            return;
        }

        // The item becomes editable again, so its stored text is read back into the boxes
        // exactly as a fresh selection would. Seeding the fields and the draft together is
        // not optional: doing one without the other is what once collapsed a name to a
        // single character on the next keystroke.
        ScribingWrite.Existing existing = ScribingWrite.readExisting(replacement);
        draftName = existing.name() != null ? existing.name() : "";
        draftDescription = existing.description() != null ? existing.description() : "";
        cb.set("#NameInput.Value", draftName);
        cb.set("#DescriptionInput.Value", draftDescription);

        repaintAll(cb, store, ref, validate(draftName, draftDescription));
        setStatus(cb, LANG_PREFIX + "sealBroken", StatusTone.Info);
        sendUpdate(cb, eb, false);
    }

    /**
     * Takes one cost after a successful write, or records the miss.
     * <p>
     * The item is already written and taking it back would be worse than letting this one
     * through, so a failed deduction stands and is logged. The only race it leaves is the
     * player emptying their bag between the check and the charge, which costs the server
     * one free write and costs the player nothing.
     */
    private void spend(
            @Nullable CombinedItemContainer inventory,
            @Nullable ItemStack cost,
            @Nonnull String what
    ) {
        if (inventory == null || cost == null) {
            return;
        }
        if (!inventory.removeItemStack(cost).succeeded()) {
            LOGGER.atWarning().log(
                    "Completed %s on %s without charging %s, it was affordable at the check and gone by the charge.",
                    what, selectedItemId, cost.getItemId()
            );
        }
    }

    /** The four painters every commit path ends with, in the order they must run. */
    private void repaintAll(
            @Nonnull UICommandBuilder cb,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Validation validation
    ) {
        paintPicker(cb, store, ref);
        paintSelection(cb, store, ref);
        paintDraft(cb, store, ref, validation);
        paintPreviewChrome(cb, store, ref, validation);
    }

    private void handleClear(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        draftName = "";
        draftDescription = "";
        UICommandBuilder cb = new UICommandBuilder();
        cb.set("#NameInput.Value", "");
        cb.set("#DescriptionInput.Value", "");
        Validation validation = validate("", "");
        paintDraft(cb, store, ref, validation);
        paintPreviewChrome(cb, store, ref, validation);
        setStatus(cb, LANG_PREFIX + "clearedDraft", StatusTone.Info);
        sendUpdate(cb, false);
    }

    // ----- painting -----

    private void paintChips(@Nonnull UICommandBuilder cb) {
        for (ItemFilter value : ItemFilter.values()) {
            cb.set("#" + value.widget + "Mark.Visible", value == filter);
        }
    }

    /**
     * Repaints the whole picker as one grid of slots.
     * <p>
     * The column is a single {@code ItemGrid} and the click binding on it is static, so
     * this method sets exactly one property and touches no event builder. That is the
     * shape the game uses for its own item library, and it is what makes a repaint cheap
     * enough to run on every keystroke of the search box.
     * <p>
     * {@link #pickerRows} is rebuilt in lockstep with the slot array and is the only
     * thing that turns a clicked index back into an inventory position. Anything that
     * changes the order or the filtering here changes the meaning of every index the
     * client is holding, which is why the two lists are built in the same loop and never
     * separately.
     */
    private void paintPicker(
            @Nonnull UICommandBuilder cb,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        pickerRows.clear();
        List<ItemGridSlot> slots = new ArrayList<>();
        int carried = 0;
        boolean hasSelection = resolveSelectedStack(store, ref) != null;

        for (int section : SCANNED_SECTIONS) {
            ItemContainer container = resolveContainer(store, ref, section);
            if (container == null) {
                continue;
            }
            for (short slot = 0; slot < container.getCapacity(); slot++) {
                ItemStack stack = container.getItemStack(slot);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                carried++;
                if (!matchesFilter(stack)
                        || !ItemText.matchesSearch(stack, searchQuery, playerRef)) {
                    continue;
                }
                boolean chosen = section == selectedSection
                        && slot == selectedSlot
                        && stack.getItemId().equals(selectedItemId);
                pickerRows.add(new PickerRow(section, slot, stack.getItemId()));
                // Nothing is dimmed until there is a selection to contrast against, so an
                // untouched picker reads as a plain inventory rather than as a wall of
                // refusals.
                slots.add(wireSlot(stack, true, hasSelection && !chosen));
            }
        }

        cb.set("#PickerGrid.Slots", slots.toArray(new ItemGridSlot[0]));

        int shown = slots.size();
        cb.set("#PickerCount.Text", Integer.toString(shown));
        // The GRID is hidden, not the row that holds it, which is what the per-cell
        // version did too. Both the grid's row and the empty panel carry FlexWeight, so
        // hiding the row instead would hand the empty state the whole column and move a
        // message the player has already seen in one place. Empty-state layout is not what
        // this change is about.
        cb.set("#PickerGrid.Visible", shown > 0);
        cb.set("#PickerEmpty.Visible", shown == 0);
        // Three causes, three messages. The chips added a second way for the grid to come
        // back empty, and telling a player their SEARCH found nothing when they typed no
        // search and only pressed a category is the kind of small lie that makes a screen
        // feel careless. Search wins the wording when both are narrowing, because it is
        // the one the player typed and so the one they will think to clear first.
        String cause;
        if (carried == 0) {
            cause = "nothing";
        } else if (!searchQuery.isBlank()) {
            cause = "noMatches";
        } else {
            cause = "noCategory";
        }
        cb.set("#PickerEmptyTitle.TextSpans", Message.translation(LANG_PREFIX + cause + "Title"));
        cb.set("#PickerEmptyBody.TextSpans", Message.translation(LANG_PREFIX + cause + "Body"));
        pickerHasMatches = shown > 0;
        paintState(cb, store, ref);
    }

    /**
     * Every selector that answers "which desk state is on screen, and what may be pressed".
     * <p>
     * Keep these selectors in one place for correctness, not tidiness. {@link #paintPicker}
     * and {@link #paintSelection}
     * used to write five of these each, in two places that had to agree by hand. The seal
     * added five more, and ten booleans with two homes is exactly how a confirm row
     * survives the selection change that was supposed to clear it. Anything that changes
     * which panel is visible or which button is live belongs here and nowhere else.
     */
    private void paintState(
            @Nonnull UICommandBuilder cb,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        ItemStack stack = resolveSelectedStack(store, ref);
        boolean selected = stack != null;
        boolean sealed = selected && ScribingSeal.isSealed(stack);
        // The editor is for an item that can still be written on. A sealed one gets its own
        // panel instead, so no disabled field is ever drawn looking like a field.
        boolean editing = pickerHasMatches && selected && !sealed;

        cb.set("#DeskEmpty.Visible", pickerHasMatches && !selected);
        cb.set("#DeskEditor.Visible", editing);
        cb.set("#DeskSealed.Visible", pickerHasMatches && sealed);
        // The legend teaches the markup, so it belongs to the editing state only. It is a
        // sibling of the three panels now rather than a child of the editor.
        cb.set("#LegendPanel.Visible", editing);
        cb.set("#PreviewCard.Visible", pickerHasMatches && selected);

        boolean ink = charges(store, ref);
        boolean lock = sealRequired(store, ref);
        cb.set("#RequirementsRow.Visible", editing && (ink || lock));
        cb.set("#CostCell.Visible", ink);
        cb.set("#SealCell.Visible", lock);

        cb.set("#SealButton.Visible", ScribingConfig.SEAL_ENABLED);
        cb.set("#ActionNormal.Visible", !pendingSeal);
        cb.set("#ActionConfirm.Visible", pendingSeal);
        cb.set("#InscribeButton.Disabled", selected && !canAfford(store, ref));
        cb.set("#SealButton.Disabled", selected && !canAffordSeal(store, ref));
    }

    private void paintSelection(
            @Nonnull UICommandBuilder cb,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        ItemStack stack = resolveSelectedStack(store, ref);
        boolean selected = stack != null;
        paintState(cb, store, ref);
        // Have over need, the way the game shows recipe materials, not a sentence with a
        // number in it. The count carries the refusal in its own colour because a server
        // command cannot rewrite a LabelStyle, which is also why the row needs no
        // separate error widget beside it.
        cb.set("#CostSlot.Slots", new ItemGridSlot[] { wireSlot(
                new ItemStack(ScribingConfig.COST_ITEM_ID, ScribingConfig.COST_QUANTITY),
                false,
                false
        ) });
        cb.set("#CostName.TextSpans", Message.translation("server.items." + ScribingConfig.COST_ITEM_ID + ".name"));
        int have = countCarried(store, ref, ScribingConfig.COST_ITEM_ID);
        cb.set("#CostAmount.TextSpans", costSpan(have));

        // The lock cell reads exactly like the ink cell beside it, because they are the
        // same kind of statement and the player should not have to learn two.
        cb.set("#SealSlot.Slots", new ItemGridSlot[] { wireSlot(
                new ItemStack(ScribingConfig.SEAL_ITEM_ID, ScribingConfig.SEAL_QUANTITY),
                false,
                false
        ) });
        cb.set("#SealName.TextSpans",
                Message.translation("server.items." + ScribingConfig.SEAL_ITEM_ID + ".name"));
        cb.set("#SealAmount.TextSpans",
                sealSpan(countCarried(store, ref, ScribingConfig.SEAL_ITEM_ID)));

        if (!selected) {
            return;
        }

        // A sealed item never reaches the editor, so its panel is painted instead and the
        // target row below is skipped rather than written into a hidden parent.
        if (ScribingSeal.isSealed(stack)) {
            paintSealed(cb, stack);
            return;
        }

        // The real stack, metadata stripped for the wire, so hovering the desk shows this
        // item's own tooltip as the "before" against the preview column's "after".
        cb.set("#TargetSlot.Slots", new ItemGridSlot[] { wireSlot(stack, false, false) });
        cb.set("#TargetName.TextSpans", stack.getDisplayName());
        // Deliberately not a language key, unlike every other string this page paints.
        // The card replicates what the client already draws, and the shipped tooltip draws
        // this line as "ID: <item-id>". Making our copy translatable would let the preview
        // drift from the tooltip it exists to predict, which is the one way this card must
        // never be wrong.
        cb.set("#PvId.Text", "ID: " + stack.getItemId());

        // Translated rather than composed in Java. Hardcoded English in a player-facing
        // string is already recorded as a defect against AlterationTransaction in this
        // mod, so it does not get reintroduced here. The count rides as an ICU parameter,
        // which is the engine's own mechanism, used on 876 lines of its language files.
        // The design gives this caption two tones. The stack note is ordinary
        // information and stays muted, while anything warning the player that pressing
        // the button will overwrite or replace existing text goes gold, so the one that
        // matters is not the same weight as the one that does not.
        ScribingWrite.Existing existing = ScribingWrite.readExisting(stack);
        Message caption;
        if (stack.getQuantity() > 1) {
            // Metadata is per stack, so this is a fact the player is entitled to before
            // they press the button rather than after.
            caption = Message.translation(LANG_PREFIX + "captionStack")
                    .param("count", stack.getQuantity())
                    .color("#878e9c");
        } else if (existing.name() != null || existing.description() != null) {
            caption = Message.translation(LANG_PREFIX + "captionInscribed")
                    .color(StatusTone.Info.color);
        } else if (existing.foreign()) {
            caption = Message.translation(LANG_PREFIX + "captionForeign")
                    .color(StatusTone.Info.color);
        } else {
            caption = Message.empty();
        }
        cb.set("#TargetCaption.TextSpans", caption);
    }

    /**
     * The draft acknowledgement deliberately owns exactly seven selectors. Do not add
     * field Values, picker commands or event bindings here. Rapid ValueChanged events
     * are acknowledgement gated, so each event receives one compact update.
     */
    private void paintDraft(
            @Nonnull UICommandBuilder cb,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Validation validation
    ) {
        ItemStack stack = resolveSelectedStack(store, ref);
        ItemStack preview = previewStack(validation, stack);
        Message namePreview = preview != null ? preview.getDisplayName() : Message.empty();
        Message descriptionPreview = preview != null
                ? renderableDescription(preview.getDisplayDescription())
                : Message.empty();
        cb.set("#PvName.TextSpans", namePreview);
        cb.set("#PvDescription.TextSpans", descriptionPreview);

        // The rule hides with the description, and it has to do so on the typing path
        // rather than only on select and apply. The shipped tooltip hides its whole
        // description group, separator included, so a rule left standing over an emptied
        // description draws a line the real tooltip never draws, and this card's one job
        // is being wrong in no way the player can see. It costs one more set on an update
        // already being sent, and the hot-path rule that actually matters, one sendUpdate
        // with no grid work, still holds.
        //
        // The test is the RENDERED message, never whether the box is empty. Clearing the
        // box restores the item's own description through the engine's null fallback, and
        // that description is still drawn, so keying off the draft would hide a rule the
        // tooltip does show. paintPreviewChrome computes it the same way on purpose, so
        // whichever of the two runs last writes the same answer.
        cb.set("#PvSep1.Visible", !messageIsEmpty(descriptionPreview));

        // The counters carry their own state in colour, which the design shows three ways:
        // muted while there is room, red once the text is over the cap, gold when the
        // player holds the bypass and there is no cap to be over. It rides on the Message
        // because a server command cannot rewrite a LabelStyle, and it is the difference
        // between a number and a number that tells you something.
        cb.set("#NameCount.TextSpans",
                countSpan(formatNameCount(validation), validation.name(), validation.bypass()));
        cb.set("#DescriptionCount.TextSpans",
                countSpan(formatDescriptionCount(validation),
                        validation.description(), validation.bypass()));

        Message status;
        StatusTone tone;
        if (!validation.ok()) {
            status = Message.raw(firstProblem(validation));
            tone = StatusTone.Bad;
        } else if (!draftName.isEmpty() && validation.nameOk().empty()
                || !draftDescription.isEmpty() && validation.descriptionOk().empty()) {
            status = Message.translation(LANG_PREFIX + "tagsOnly");
            tone = StatusTone.Info;
        } else if (validation.nameOk().empty() && validation.descriptionOk().empty()) {
            if (stack != null && hasItemDisplay(stack)) {
                status = Message.translation(LANG_PREFIX + "willRestore");
            } else {
                status = Message.translation(LANG_PREFIX + "typePrompt");
            }
            tone = StatusTone.Info;
        } else {
            status = Message.translation(LANG_PREFIX + "ready");
            tone = StatusTone.Guide;
        }
        cb.set("#Status.TextSpans", status.color(tone.color));

    }

    private void paintPreviewChrome(
            @Nonnull UICommandBuilder cb,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull Validation validation
    ) {
        ItemStack stack = resolveSelectedStack(store, ref);
        ItemStack preview = previewStack(validation, stack);
        Message descriptionPreview = preview != null
                ? renderableDescription(preview.getDisplayDescription())
                : Message.empty();
        boolean descriptionVisible = !messageIsEmpty(descriptionPreview);
        cb.set("#PvSep1.Visible", descriptionVisible);
        String durability = stack != null ? ItemText.formatDurability(stack) : "";
        cb.set("#PvDurability.Text", durability);
        cb.set("#PvDurability.Visible", !durability.isEmpty());
        cb.set("#PvSep2.Visible", !durability.isEmpty());
        paintPreviewQuality(cb, stack);
    }


    @Nonnull
    private static String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return Long.toString(Math.round(value));
        }
        String formatted = String.format(Locale.ROOT, "%.2f", value);
        return formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /**
     * Names the target item's quality beside the preview's name, in that quality's own
     * colour, which is how the shipped tooltip signals rarity (ItemTooltip.ui:41-45).
     * <p>
     * It does not swap the card texture, and that is a measured limit rather than a
     * shortcut. A relative texture path in a custom UI document is rooted at
     * {@code Common/UI/Custom/} and refuses to climb above it, verified against all 135
     * shipped documents and confirmed by the client rejecting the attempt outright. The
     * per-quality cards live in {@code Common/UI/ItemQualities/Tooltips/}, a sibling of
     * that root, so no custom page can wear one. Tinting the reachable tooltip texture by
     * rarity is out too: its outer ring is fully transparent, so a colour behind it draws
     * a rectangle, not a border.
     * <p>
     * The label is read off the live asset rather than a table transcribed into Java, so a
     * quality another mod registers still names and colours itself correctly. Qualities
     * that ask not to be labelled are respected, which is why ordinary Default gear shows
     * nothing here and only the notable items announce themselves.
     * <p>
     * It also does not draw a coloured accent bar. One used to sit at the top of the card
     * and it was an invention of mine that appears in no version of {@code ItemTooltip.ui}.
     * On a Common item it rendered as a white line across a card whose entire purpose is to
     * be indistinguishable from the real tooltip. The label is the whole rarity signal.
     */
    private static void paintPreviewQuality(
            @Nonnull UICommandBuilder cb,
            @Nullable ItemStack stack
    ) {
        ItemQuality quality = resolveQuality(stack);
        Message label = Message.empty();
        Color textColor = quality != null ? quality.getTextColor() : null;
        if (quality != null && quality.isVisibleQualityLabel()) {
            String key = quality.getLocalizationKey();
            if (key != null && textColor != null) {
                label = Message.translation(key).color(hex(textColor));
            }
        }
        cb.set("#PvQuality.TextSpans", label);
    }

    /**
     * The hover card for one picker cell: what this STACK is, not what the item is.
     * <p>
     * Built as spans rather than a string so an inscribed name arrives with the colours
     * the player gave it, and so the description keeps whatever styling was written into
     * its metadata. A newline renders as a real line break here, measured in game.
     */
    @Nonnull
    private ItemGridSlot wireSlot(
            @Nonnull ItemStack stack,
            boolean clickable,
            boolean dimmed
    ) {
        return ItemSlots.display(stack, clickable, dimmed,
                ItemSlots.resolveDescriptionKey(stack, playerRef));
    }

    @Nullable
    private static ItemQuality resolveQuality(@Nullable ItemStack stack) {
        if (stack == null) {
            return null;
        }
        Item item = stack.getItem();
        if (item == null) {
            return null;
        }
        return ItemQuality.getAssetMap().getAsset(item.getQualityIndex());
    }

    @Nonnull
    private static String hex(@Nonnull Color color) {
        return String.format(
                Locale.ROOT, "#%02x%02x%02x",
                color.red & 0xff, color.green & 0xff, color.blue & 0xff
        );
    }


    @Nullable
    private static ItemStack previewStack(
            @Nonnull Validation validation,
            @Nullable ItemStack stack
    ) {
        if (stack == null || !validation.ok()) {
            return stack;
        }
        // A sealed item is shown as it is and never rebuilt from the draft. This is a
        // correctness rule, not a shortcut. Its text can no longer change, so there
        // is no "after" to predict, and rebuilding would compose a display from the draft
        // alone and therefore drop the seal line the item's real tooltip carries. This card
        // exists to be identical to that tooltip, so a card quietly missing a line the game
        // draws is the one way it must never be wrong. Caught reviewing the seal marker, on
        // the same method whose other caller once disconnected a tester.
        if (ScribingSeal.isSealed(stack)) {
            return stack;
        }
        Message name = ScribingWrite.toMessage(validation.nameOk().runs());
        Message description = ScribingWrite.toMessage(validation.descriptionOk().runs());
        return ScribingWrite.apply(stack, name, description);
    }

    private static boolean messageIsEmpty(@Nonnull Message message) {
        // The console renderer inserts escape codes that are not whitespace, so it cannot answer this.
        return MessageTrees.plainText(message).isBlank();
    }

    /**
     * What the sealed tooltip's "Sealed" line should sit under when the player wrote no
     * description of their own, or null when there is nothing worth keeping.
     *
     * <p>Always ask the post-write stack, never the one the player selected. That distinction
     * is the whole care in this method. Reading {@code stack.getDisplayDescription()} directly
     * would return the description the item has right now, which on a re-inscription is the old
     * custom text the player has just deleted from the box. Sealing would then resurrect the
     * words they removed and make them permanent. Building the plain write first and asking
     * that gives the description the item will actually fall back to, which is the vanilla
     * one. {@code apply} is a pure builder, so this costs an object and no side effect.</p>
     *
     * <p>The renderability check is not optional either. In-game measurements on 2026-08-09
     * showed that an item whose description key has no language entry draws the raw key as
     * literal text, so
     * without it sealing an Adamantite Sword would stamp
     * {@code server.items.Sword_Adamantite_Blue.description} onto it permanently.</p>
     */
    @Nullable
    private Message tooltipBase(
            @Nonnull ItemStack stack,
            @Nullable Message name,
            @Nullable Message description
    ) {
        if (description != null) {
            return null;
        }
        Message fallback = renderableDescription(ScribingWrite.apply(stack, name, null)
                .getDisplayDescription());
        return messageIsEmpty(fallback) ? null : fallback;
    }

    /**
     * The description as it will actually render, or empty when it would render as a
     * translation key.
     * <p>
     * In-game measurements on 2026-08-09 showed that selecting an Adamantite Sword printed
     * {@code server.items.Sword_Adamantite_Blue.description} into the preview card as
     * literal text. The item has no description: its display description is a
     * translation Message, the key has no entry in any language file, and the client draws
     * the key when it cannot resolve one.
     * <p>
     * The real tooltip does not do this, it hides its whole description group for an item
     * with none, so a card claiming to show what the player will see must do the same. The
     * check runs server side because only the server can ask whether a key resolves, and it
     * uses the same {@link I18nModule} lookup the search filter already relies on.
     * <p>
     * Player-authored text is unaffected: an inscribed description is raw, carries no
     * message id, and falls straight through.
     */
    @Nonnull
    private Message renderableDescription(@Nonnull Message message) {
        String messageId = message.getMessageId();
        if (messageId == null) {
            return message;
        }
        String translated = I18nModule.get().getMessage(playerRef.getLanguage(), messageId);
        return translated == null || translated.isBlank() ? Message.empty() : message;
    }

    // ----- validation and formatting -----

    @Nonnull
    private Validation validate(@Nonnull String name, @Nonnull String description) {
        boolean bypass = playerRef.hasPermission(ScribingConfig.BYPASS_PERMISSION);
        return new Validation(
                ScribingText.validateName(name, ScribingText.Limits.forName(bypass)),
                ScribingText.validateDescription(description, ScribingText.Limits.forDescription(bypass)),
                bypass
        );
    }

    private record Validation(
            @Nonnull ScribingText.Result name,
            @Nonnull ScribingText.Result description,
            boolean bypass
    ) {
        private boolean ok() {
            return name instanceof ScribingText.Result.Ok
                    && description instanceof ScribingText.Result.Ok;
        }

        @Nonnull
        private ScribingText.Result.Ok nameOk() {
            return (ScribingText.Result.Ok) name;
        }

        @Nonnull
        private ScribingText.Result.Ok descriptionOk() {
            return (ScribingText.Result.Ok) description;
        }
    }

    @Nonnull
    private static String firstProblem(@Nonnull Validation validation) {
        if (validation.name() instanceof ScribingText.Result.Fail fail) {
            return fail.problems().getFirst().message();
        }
        return ((ScribingText.Result.Fail) validation.description()).problems().getFirst().message();
    }

    /**
     * Whether an inscription can actually be paid for right now.
     * <p>
     * This keeps a button that cannot succeed from sounding as though it did. In-game
     * testing on 2026-08-10 confirmed that a button's activate sound is played by the client
     * on press, before the server has any say, so the refusal path can never play
     * a different sound: by the time {@code handleApply} finds the player cannot pay, the
     * confirmation sting is already audible. Disabling the button is the only point the
     * client will take the answer, and it stops the press happening at all rather than
     * dressing up a refusal. The Alteration Table already gates ALTER exactly this way,
     * so this is the two benches agreeing rather than a new mechanism.
     * <p>
     * Deliberately narrow. Only affordability disables the button, because it is the one
     * refusal the player cannot see coming and cannot fix from this screen. Every other
     * refusal, an empty draft or a stale target, keeps the button live so the status
     * strip can say what happened when they press it.
     */
    private boolean canAfford(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        if (!charges(store, ref)) {
            return true;
        }
        return countCarried(store, ref, ScribingConfig.COST_ITEM_ID) >= ScribingConfig.COST_QUANTITY;
    }

    /**
     * Whether this player pays for this inscription. The one home for the rule.
     * <p>
     * Creative is the only thing that exempts anyone. Holding the bench's bypass
     * permission does not, and neither does being an operator: the permission lifts the
     * tooltip-size caps and touches nothing about the ink. This was confirmed on 2026-08-08.
     * <p>
     * This used to be computed inside {@code handleApply} while the cost row was shown on
     * {@code COST_ENABLED} alone, so on a server with the cost turned on a creative player
     * was shown a requirement row for ink they would never be charged, and it reddened
     * when they had none. The screen said pay, the transaction said free, and the screen
     * was the one the player believed. One predicate, read by both.
     */
    private boolean charges(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        return ScribingConfig.COST_ENABLED && !creative(store, ref);
    }

    /**
     * Whether the player must be holding a lock to seal right now.
     * <p>
     * Creative exempts this exactly as it exempts the ink, and both read that answer from
     * {@link #creative} rather than each testing the game mode themselves, because two
     * copies of one exemption is how the two costs would eventually disagree.
     */
    private boolean sealRequired(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        return ScribingConfig.SEAL_ENABLED && !creative(store, ref);
    }

    /**
     * Creative is the only thing that exempts anyone, and that is already recorded on
     * {@link #canAfford}. Holding the bench's bypass permission does not, and neither does
     * being an operator.
     */
    private boolean creative(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        Player player = store.getComponent(ref, Player.getComponentType());
        return player != null && player.getGameMode() == GameMode.Creative;
    }

    /**
     * Sealing is gated on affordability for the same reason inscribing is: the client
     * plays a button's activate sound on press, before the server can refuse.
     */
    private boolean canAffordSeal(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        if (!sealRequired(store, ref)) {
            return true;
        }
        return countCarried(store, ref, ScribingConfig.SEAL_ITEM_ID) >= ScribingConfig.SEAL_QUANTITY;
    }

    /**
     * How many of one item the player can actually pay with right now.
     * <p>
     * This walks the payment containers, not the picker's sections, and the difference is
     * the whole reason the method reads this way. A count taken from somewhere wider than
     * the charge is a number the player cannot act on: it reads as enough, enables the
     * button, and then the charge refuses. The combined container presents its children as
     * one flat run of slots, so walking it here is walking exactly what removeItemStack
     * will walk in a moment.
     */
    private int countCarried(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull String itemId
    ) {
        ItemContainer inventory = paymentInventory(store, ref);
        int total = 0;
        for (short slot = 0; slot < inventory.getCapacity(); slot++) {
            ItemStack stack = inventory.getItemStack(slot);
            if (stack != null && !stack.isEmpty() && itemId.equals(stack.getItemId())) {
                total += stack.getQuantity();
            }
        }
        return total;
    }

    /**
     * Have over need, reddened when short. A requirement row, not a sentence.
     */
    @Nonnull
    private static Message costSpan(int have) {
        Message span = Message.raw(have + " / " + ScribingConfig.COST_QUANTITY);
        return have < ScribingConfig.COST_QUANTITY ? span.color(StatusTone.Bad.color) : span;
    }

    /** The same reading as {@link #costSpan}, for the lock. */
    @Nonnull
    private static Message sealSpan(int have) {
        Message span = Message.raw(have + " / " + ScribingConfig.SEAL_QUANTITY);
        return have < ScribingConfig.SEAL_QUANTITY ? span.color(StatusTone.Bad.color) : span;
    }

    /**
     * The sealed panel: the item, who sealed it, and the way out if the player has one.
     */
    private void paintSealed(@Nonnull UICommandBuilder cb, @Nonnull ItemStack stack) {
        cb.set("#SealedSlot.Slots", new ItemGridSlot[] { wireSlot(stack, false, false) });
        cb.set("#SealedName.TextSpans", stack.getDisplayName());
        cb.set("#SealedCaption.TextSpans", stack.getQuantity() > 1
                ? Message.translation(LANG_PREFIX + "captionStack")
                        .param("count", stack.getQuantity())
                        .color("#878e9c")
                : Message.empty());

        // A damaged payload still counts as sealed, so this falls back to the unattributed
        // wording rather than leaving the panel silent about why nothing can be edited.
        // That fallback carries no date on purpose: epochMillis is one of the three fields
        // ScribingSeal.read validates, so a null seal is exactly the case where there is no
        // trustworthy timestamp to print.
        //
        // The date goes in as an ISO-8601 string, not as the epoch long it is stored as, and
        // the conversion is the whole point rather than a detail.
        //
        // An ICU {sealedAt, date} pattern needs a value it can read as a date. There is a
        // param overload taking a long, so passing the epoch compiles and looks right, and it
        // silently renders the sentence with nothing where the date belongs. That shipped and
        // was caught in game rather than by any check here.
        //
        // The engine's own precedent is what settles the form. MemoriesPage reads
        // NPCMemory.getCapturedTimestamp, a long, and does NOT hand it over as one: it calls
        // Instant.ofEpochMilli, atZone with UTC, then toString, and passes the result to the
        // String overload, for server.memories.general.foundIn = "Found in {location},
        // {dateValue, date}". Read from the shipped jar's bytecode, because reading only the
        // getter type and the pattern is exactly how this was got wrong the first time.
        //
        // UTC matches what the engine does. The client formats the instant in the viewer's own
        // locale, so no English date is hardcoded here, which is a defect already recorded
        // against AlterationTransaction in this mod.
        //
        // The date is assembled from day, month name and year rather than handed to a date
        // placeholder, and the reason is measured rather than stylistic.
        //
        // A {value, date} placeholder renders one fixed numeric format and ignores the style
        // argument. Printing the same instant through long, full, short and medium returned
        // the identical numeric date in every case, while the same value with no placeholder
        // formatting returned the full ISO string. So the client is not running a complete
        // ICU implementation here, it applies one format and drops what it was asked for. That
        // also explains why not one of the 20 date and time placeholders in the 121 shipped
        // language files bothers to pass a style: it would do nothing.
        //
        // A numeric date is genuinely ambiguous between countries, since the twentieth of August
        // reads as 20/08 to half the world and 08/20 to the other half, and this is the one line
        // on the panel carrying a fact somebody may need to rely on.
        //
        // The month therefore arrives as its own translated message rather than as text built
        // here. Formatting the whole date in Java would be simpler and would also hardcode
        // English month names into a screen this mod exists to let people write on, which is a
        // defect already recorded against AlterationTransaction. Passing a Message as a
        // parameter is the engine's own mechanism, and MemoriesPage does exactly this for the
        // location in its own sentence. The day and the year go as strings so that no number
        // formatter can put a thousands separator inside a year.
        //
        // Word order lives in the language file, so a translator can move the parts.
        ScribingSeal.Seal seal = ScribingSeal.read(stack);
        Message sealedLine;
        if (seal == null) {
            sealedLine = Message.translation(LANG_PREFIX + "sealedUnknown");
        } else {
            ZonedDateTime sealedAt = Instant.ofEpochMilli(seal.epochMillis()).atZone(ZoneOffset.UTC);
            sealedLine = Message.translation(LANG_PREFIX + "sealedBy")
                    .param("player", seal.playerName())
                    .param("day", String.valueOf(sealedAt.getDayOfMonth()))
                    .param("month", Message.translation(
                            LANG_PREFIX + "month" + sealedAt.getMonthValue()))
                    .param("year", String.valueOf(sealedAt.getYear()));
        }
        cb.set("#SealedBody.TextSpans", sealedLine);

        // Name the button instead of merely offering it. The one person who play-tests this
        // bench is the server owner and is therefore OP, so they hold this permission silently
        // at all times. A bare Break seal button would look to them as if the seal were not
        // working. The note beside it says why they can see it, which is the same trap the
        // length bypass already sprang on this project once.
        cb.set("#SealedAdminRow.Visible",
                playerRef.hasPermission(ScribingConfig.BREAK_SEAL_PERMISSION));
    }

    /**
     * A counter with its state in its colour. Muted while there is room, the refusal red
     * once the field is over its cap, and the accent gold when the player holds the
     * bypass, because then nothing is wrong and there is no fraction to be over.
     */
    @Nonnull
    private static Message countSpan(
            @Nonnull String text,
            @Nonnull ScribingText.Result result,
            boolean bypass
    ) {
        String color = "#5a6a7a";
        if (bypass) {
            color = StatusTone.Info.color;
        } else if (result instanceof ScribingText.Result.Fail) {
            color = StatusTone.Bad.color;
        }
        return Message.raw(text).color(color);
    }

    @Nonnull
    private String formatNameCount(@Nonnull Validation validation) {
        int used = stats(validation.name(), draftName).renderedChars();
        return validation.bypass()
                ? used + plural(used, " character", " characters")
                        + SEPARATOR + draftName.length() + " / " + ScribingConfig.NAME_MAX_RAW_CHARS + " typed"
                : used + " / " + ScribingConfig.NAME_MAX_CHARS;
    }

    /**
     * Takes the bypass off the Validation rather than asking the permission system again.
     * The counter and the check that refuses the write have to agree, and re-reading the
     * permission on the typing path was a second source for one fact, on the one path
     * that runs on every keystroke.
     *
     * <p>The line cap is shown now, and it was not before. This read "2 lines  41 / 300",
     * which reports the line count without ever saying six is the maximum, so the only way
     * a player learned the line cap existed was by tripping it. The character budget was
     * spelled out and the line budget was not, on the one field where lines are what
     * actually bound the tooltip.</p>
     */
    @Nonnull
    private String formatDescriptionCount(@Nonnull Validation validation) {
        ScribingMarkup.Stats stats = stats(validation.description(), draftDescription);
        int lines = stats.lineCount();
        int used = stats.renderedChars();
        if (validation.bypass()) {
            return lines + plural(lines, " line", " lines")
                    + SEPARATOR + used + plural(used, " character", " characters")
                    + SEPARATOR + draftDescription.length() + " / " + ScribingConfig.DESC_MAX_RAW_CHARS + " typed";
        }
        // "2 / 6 lines" then "41 / 300". The word rides on the first pair only: the second
        // repeats the same shape immediately after it, so naming the unit twice reads as
        // clutter rather than as clarity.
        return lines + " / " + ScribingConfig.DESC_MAX_LINES + " lines"
                + SEPARATOR + used + " / " + ScribingConfig.DESC_MAX_TOTAL_CHARS;
    }

    @Nonnull
    private static String plural(int count, @Nonnull String one, @Nonnull String many) {
        return count == 1 ? one : many;
    }

    @Nonnull
    private static ScribingMarkup.Stats stats(
            @Nonnull ScribingText.Result result,
            @Nonnull String raw
    ) {
        if (result instanceof ScribingText.Result.Ok ok) {
            return ok.stats();
        }
        return ScribingMarkup.parse(raw.replace("\r\n", "\n").replace('\r', '\n')).stats();
    }

    // ----- inventory and document helpers -----

    private boolean matchesFilter(@Nonnull ItemStack stack) {
        Item item = stack.getItem();
        if (item == null) {
            return false;
        }

        return switch (filter) {
            case All -> true;
            case Armor -> item.getArmor() != null;
            case Weapon -> item.getWeapon() != null;
            case Tool -> item.getTool() != null;
            case Blocks -> item.getBlockId() != null;
            case Inscribed -> {
                ScribingWrite.Existing existing = ScribingWrite.readExisting(stack);
                yield existing.name() != null || existing.description() != null;
            }
        };
    }

    private static boolean hasItemDisplay(@Nonnull ItemStack stack) {
        return stack.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC) != null;
    }

    @Nullable
    private ItemStack resolveSelectedStack(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        if (selectedItemId == null) {
            return null;
        }
        ItemContainer container = resolveContainer(store, ref, selectedSection);
        ItemStack stack = container != null ? container.getItemStack(selectedSlot) : null;
        if (stack == null || stack.isEmpty() || !selectedItemId.equals(stack.getItemId())) {
            return null;
        }
        return stack;
    }

    /**
     * The stack a painted row still points at, or null if it has moved or changed.
     */
    @Nullable
    private ItemStack resolveRowStack(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PickerRow row
    ) {
        ItemContainer container = resolveContainer(store, ref, row.section());
        if (container == null || row.slot() < 0 || row.slot() >= container.getCapacity()) {
            return null;
        }
        ItemStack stack = container.getItemStack(row.slot());
        if (stack == null || stack.isEmpty() || !row.itemId().equals(stack.getItemId())) {
            return null;
        }
        return stack;
    }

    /**
     * Pulls the client's slot index out of a raw event payload.
     * <p>
     * The field sits alongside our own keys as {@code "SlotIndex":4}, which is the shape
     * CrucibleUIProbe logged 457 times on this exact server version. Quotes are tolerated
     * because nothing guarantees the client will always send it unquoted, and anything
     * unparseable returns -1, which the caller treats as no selection rather than as slot
     * zero. Guessing zero here would inscribe whichever item happened to be first.
     */
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

    /**
     * The container behind a section id, or null when this bench has no business reading it.
     * <p>
     * Every path that reaches a stack comes through here: the picker, the carried count, the
     * restored selection, the inscribe and both seal paths. That is why the membership test
     * sits on this method rather than at the six call sites. The engine will happily resolve
     * a section this bench does not scan, the tools section included, so one call site that
     * forgot to check would put the whole feature back where it was. A section id can arrive
     * from a remembered pick made before this list changed, or from a later edit that adds
     * an id somewhere other than the array above, and neither of those should be able to
     * open a container the player cannot see.
     */
    @Nullable
    private ItemContainer resolveContainer(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            int section
    ) {
        if (!isScanned(section)) {
            return null;
        }
        ComponentType<EntityStore, ? extends InventoryComponent> type =
                InventoryComponent.getComponentTypeById(section);
        if (type == null) {
            return null;
        }
        InventoryComponent component = store.getComponent(ref, type);
        return component != null ? component.getInventory() : null;
    }

    private static boolean isScanned(int section) {
        for (int scanned : SCANNED_SECTIONS) {
            if (scanned == section) {
                return true;
            }
        }
        return false;
    }

    /**
     * The containers a cost is paid from. Every reader of "can the player afford this" goes
     * through here, and that is the point of the method.
     * <p>
     * The count on screen and the charge used to read different containers. The count walked
     * every section the picker walks while the charge ran against HOTBAR_FIRST, which is
     * hand and bag only, so an inkwell in the backpack was counted as one the player had. It
     * lit the Inscribe button and then failed at the charge, refusing over an item the
     * player could see themselves carrying. An inkwell is a placeable decoration as well as
     * an ingredient, so keeping a stack out of the way is ordinary play.
     * <p>
     * Hand and bag is the reach the Alteration Table already uses for its kits, so this
     * keeps the two benches saying the same thing about where materials come from. Widening
     * it is a change to the economy rather than a fix, and it is not made here.
     * <p>
     * Naming it once is what makes the two answers agree. They cannot drift while both come
     * from this one call, which is not true of two lists that merely happen to match today.
     */
    @Nonnull
    private CombinedItemContainer paymentInventory(
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref
    ) {
        return InventoryComponent.getCombined(store, ref, InventoryComponent.HOTBAR_FIRST);
    }

    private void appendPage(@Nonnull UICommandBuilder cb) {
        cb.append(PAGE_DOCUMENT);
    }

    private void clearSelection() {
        selectedSection = Integer.MIN_VALUE;
        selectedSlot = -1;
        selectedItemId = null;
        draftName = "";
        draftDescription = "";
    }

    private void setStatus(
            @Nonnull UICommandBuilder cb,
            @Nonnull String key,
            @Nonnull StatusTone tone
    ) {
        cb.set("#Status.TextSpans", Message.translation(key).color(tone.color));
    }

    @Nonnull
    private static String value(@Nullable String value) {
        return value != null ? value : "";
    }

    @Nullable
    private static ItemFilter parseFilter(@Nullable String value) {
        if (value == null) {
            return null;
        }
        for (ItemFilter candidate : ItemFilter.values()) {
            if (candidate.name().equals(value)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Everything the client sends back that we asked for.
     * <p>
     * Section, slot and item id used to live here, sent as three strings per cell by the
     * per-cell picker. They are gone with it: a slot click identifies itself by index and
     * the index is read from the raw payload, so there is nothing left for the client to
     * carry on our behalf. Every key that remains is either a literal of ours or an "@"
     * selector for a text field's value, both of which are proven forms.
     */
    public static final class PageEvent {
        static final String KEY_TYPE = "Type";
        static final String KEY_NAME = "@Name";
        static final String KEY_DESC = "@Desc";
        static final String KEY_FILTER = "Filter";
        static final String KEY_QUERY = "@Query";

        public static final BuilderCodec<PageEvent> CODEC = BuilderCodec.builder(PageEvent.class, PageEvent::new)
                .append(new KeyedCodec<>(KEY_TYPE, Codec.STRING),
                        (event, value) -> event.type = value, event -> event.type).add()
                .append(new KeyedCodec<>(KEY_NAME, Codec.STRING),
                        (event, value) -> event.name = value, event -> event.name).add()
                .append(new KeyedCodec<>(KEY_DESC, Codec.STRING),
                        (event, value) -> event.description = value, event -> event.description).add()
                .append(new KeyedCodec<>(KEY_FILTER, Codec.STRING),
                        (event, value) -> event.filter = value, event -> event.filter).add()
                .append(new KeyedCodec<>(KEY_QUERY, Codec.STRING),
                        (event, value) -> event.query = value, event -> event.query).add()
                .build();

        private String type;
        private String name;
        private String description;
        private String filter;
        private String query;
    }
}
