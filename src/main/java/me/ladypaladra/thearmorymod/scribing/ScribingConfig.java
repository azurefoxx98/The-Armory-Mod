package me.ladypaladra.thearmorymod.scribing;

/**
 * Keeps the Scribing Table's tunable constants together. Server policy can then change
 * without scattering matching limits across the preview and commit paths.
 */
public final class ScribingConfig {

    // Part two uses this page id when it registers and opens the custom screen. Keeping
    // the id stable lets saved interactions and future language keys share one identity.
    public static final String PAGE_ID = "TheArmoryScribingPage";

    // This is the item id for the placed table. Part two keeps its block asset and
    // interaction registration in sync with this value, preventing the screen from
    // opening on another block.
    public static final String BLOCK_ITEM_ID = "Scribing_Table";

    // The block asset uses the mod's own Scribing Bench model, texture, and icon. Keep all
    // three paths in Scribing_Table.json together whenever the art moves.

    // This is the ink item charged when a server owner enables the cost. The shipped
    // policy is free scribing, so changing this id has no effect until COST_ENABLED is
    // enabled.
    public static final String COST_ITEM_ID = "Deco_Inkwell";

    // Inscribing costs one inkwell. A server owner can turn this off to make naming free.
    //
    // This originally shipped as false because naming is cosmetic and was meant to cost
    // nothing unless a server requested it. Two things were wrong with that choice. The
    // approved screen design includes a materials row, so the shipped default silently
    // contradicted the design and the row never appeared. A cost path that is off by
    // default also goes untested. The refund-ordering defect found in review would have
    // reached the first server owner who enabled it because we would never have exercised
    // the path ourselves.
    //
    // Keeping it on by default gives us the economy Lady described. An owner who disagrees
    // can change this one constant. The screen design and this default were confirmed on
    // 2026-08-09.
    public static final boolean COST_ENABLED = true;

    // When costs are enabled, one inkwell pays for one write. Keeping the quantity separate
    // lets a server tune the economy without changing the transaction path.
    public static final int COST_QUANTITY = 1;

    // Controls whether players can spend a Scribe Lock to make an inscription permanent.
    public static final boolean SEAL_ENABLED = true;

    // The Scribing Table offers this item when a player chooses to seal an inscription.
    public static final String SEAL_ITEM_ID = "Scribe_Lock";

    // The shipped economy requires one Scribe Lock for each seal.
    public static final int SEAL_QUANTITY = 1;

    // Controls whether sealing spends the required item. Turning this off makes the item a
    // reusable key.
    public static final boolean SEAL_CONSUMES = true;

    // This permission lifts the tooltip-size policy for administrators. Unsafe control
    // characters are still filtered because they damage output seen by other players.
    public static final String BYPASS_PERMISSION = "thearmory.scribing.bypasslimits";

    // This permission lets staff remove a permanent inscription seal.
    public static final String BREAK_SEAL_PERMISSION = "thearmory.scribing.breakseal";

    // Cap raw input before parsing to bound the parsing cost and stored text. Two hundred
    // characters is roughly four times the rendered name cap of 48 characters.
    public static final int NAME_MAX_RAW_CHARS = 200;

    // Cap raw description input before parsing to bound the parsing cost and stored text.
    // Twelve hundred characters is four times the rendered description cap.
    public static final int DESC_MAX_RAW_CHARS = 1200;

    // These rendered limits bound tooltip height rather than an engine restriction. The
    // engine hard limit is 32,768,040 bytes. At 18pt bold within about 432px of content, a
    // name fits roughly 43 characters per line, so 48 characters wrap to at most two lines.
    public static final int NAME_MAX_CHARS = 48;

    // This product-policy cap bounds the total description even though the engine accepts
    // far more. Together with the line caps, it keeps tooltip cards compact.
    public static final int DESC_MAX_TOTAL_CHARS = 300;

    // Six description lines occupy about 120px. That keeps the whole tooltip card near
    // 250px instead of letting player text cover another player's screen.
    public static final int DESC_MAX_LINES = 6;

    // In-game measurements show that a real newline renders as a real line break, so this
    // is the cap that actually bounds height. Sixty characters at 14pt occupy about 420px,
    // just inside the 432px content width. As a result, a full line rarely soft-wraps.
    public static final int DESC_MAX_LINE_CHARS = 60;

    private ScribingConfig() {
    }
}
