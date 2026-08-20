package me.ladypaladra.thearmorymod.alteration;

import java.util.Collections;
import java.util.Set;

/**
 * Tunable constants for the Alteration Table. Kept in one place so the fuel
 * economy and animation can be adjusted without touching the transaction logic.
 */
public final class AlterationConfig {

    // Fuel item consumed to recharge a table. This is the mod's own Alteration Kit,
    // built on LadyPaladra's model, texture and recipe. Changing this id means also
    // renaming the gamedata asset, its recipe and its language keys.
    public static final String FUEL_ITEM_ID = "Alteration_Kit";

    // The base Alteration Table is the only table item that exists now. The colour
    // variants were removed. Matching this prefix rather than the exact id means any
    // variant added later is handled without changing this code or the feed interaction.
    public static final String TABLE_ITEM_ID_PREFIX = "Alteration_Table";

    // The Alteration Table's own crafting bench id. The engine identifies a bench by
    // the pair (Type, Id) and serves it every recipe whose BenchRequirement carries
    // that same pair, so two benches sharing an id share their whole recipe pool in
    // both directions. The tables used to declare the vanilla Builder's Workbench id,
    // which meant a vanilla workbench could alter Armory gear for free through the
    // engine crafting path, skipping the fuel charge and resetting durability, while
    // the table itself served every vanilla building recipe. This id must stay in sync
    // with BlockType.Bench.Id on the Alteration_Table asset and with the
    // BenchRequirement.Id on every alteration recipe.
    public static final String BENCH_ID = "ArmoryAlteration";

    // Each fuel item consumed grants this many alteration charges.
    public static final int CHARGES_PER_FUEL = 3;

    // Live charges from the last consumed kit. The gauge now shows only the kit that
    // is currently burning, the rest of the player's kits sit in the fuel stock beside
    // it and refill the gauge automatically when it empties. Three charges is exactly
    // one kit, so the pip row reads as "charges left on this kit".
    public static final int MAX_CHARGES = 3;

    // How many spare kits a table can bank in its fuel stock. One kit stacks to fifty,
    // so this matches a full stack of Alteration Kits held in reserve on the table.
    public static final int FUEL_STOCK_CAP = 50;

    // A table must start with zero charges. This was settled on 2026-08-09, and the value
    // must stay at zero.
    //
    // This was 3, meant as one fuel's worth of grace so that tables placed before the
    // charge component existed would not brick on upgrade. It could not do that job,
    // because the component is attached lazily by ensureAndGetComponent and a lazy attach
    // cannot tell a table placed a year ago from one placed a second ago. Both get a
    // fresh component, and a fresh component granted three free charges.
    //
    // That made the gauge an infinite fuel source: place, spend three, break, place,
    // repeat. The kit economy is the whole point of the fuel system, so a table that
    // refills itself for the price of a block break is not a rounding error, it is the
    // feature cancelling itself out.
    //
    // The grace it was protecting is worth nothing anyway: this branch has never been
    // pushed, so no table exists anywhere that predates the component. A table now starts
    // empty and the first kit fed to it is the first kit it burns.
    public static final int INITIAL_CHARGES = 0;

    // This animation plays on the player after a successful alteration. Null skips the
    // animation. Interact is the base character's generic use gesture. Swap it if
    // LadyPaladra wants a dedicated one later.
    public static final String USE_ANIMATION_ID = "Interact";

    // Metadata keys that must never survive the transfer onto the new variant.
    // Empty for now, reserved for keys we later decide are variant specific. Adding
    // "ItemDisplay" here would silently delete every name and description a player has
    // written at the Scribing Table, and nothing else would catch it.
    public static final Set<String> EXCLUDED_METADATA_KEYS = Collections.emptySet();

    private AlterationConfig() {
    }
}
