package me.ladypaladra.thearmorymod.scribing;

import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These tests spell out every rule enforced by the seal.
 *
 * <p>They cover the document half of {@link ScribingSeal}. The stack half cannot be reached
 * from a unit test. The {@code new ItemStack(...)} constructor calls
 * {@code Item.getAssetStore()}, which is null outside a running server. As a result, a
 * stack-level test fails with a NullPointerException, and changing the classpath does not
 * fix it. We measured that behavior rather than assuming it. The stack half adds one
 * five-argument copy to what is tested here, and an in-game play-test covers it.</p>
 *
 * <p>One property is not covered here, so do not assume otherwise: whether
 * {@code ScribingWrite.apply} leaves unrelated metadata keys alone. Testing that property
 * requires a real ItemStack.</p>
 *
 * <p>Another property was deliberately removed instead of being left untested.
 * {@code apply} used to reject a sealed stack by throwing. The page's preview path calls
 * it too, so sealing an item threw on its own success path and the engine disconnected the
 * tester. {@code apply} is now a pure builder. These tests will not catch a future change
 * that makes it throw again, which is why the method itself states the rule. The seal is
 * enforced at the two commit sites in ScribingPage, never in the builder.</p>
 */
class ScribingSealTest {

    private static final String KEY = "TheArmoryScribeSeal";
    private static final String FOREIGN = "SomeOtherModsData";
    private static final String DISPLAY = "ItemDisplay";

    private static BsonDocument foreign() {
        return new BsonDocument().append(FOREIGN, new BsonString("keep me"));
    }

    @Test
    void nothingIsSealedByDefault() {
        assertFalse(ScribingSeal.isSealed((BsonDocument) null));
        assertFalse(ScribingSeal.isSealed(foreign()));
        assertNull(ScribingSeal.read((BsonDocument) null));
        assertNull(ScribingSeal.read(foreign()));
    }

    @Test
    void sealRoundTripsAllThreeFields() {
        UUID uuid = UUID.randomUUID();
        BsonDocument sealed = ScribingSeal.sealed(null, "Larsonix", uuid, 1_760_000_000_000L);

        assertTrue(ScribingSeal.isSealed(sealed));
        ScribingSeal.Seal seal = ScribingSeal.read(sealed);
        assertNotNull(seal);
        assertEquals("Larsonix", seal.playerName());
        assertEquals(uuid, seal.playerUuid());
        assertEquals(1_760_000_000_000L, seal.epochMillis());
    }

    @Test
    void sealingDoesNotTouchTheInputDocument() {
        // getMetadata returns the document for the item the player is still holding. A
        // writer that edits it in place therefore mutates the player's inventory. That bug
        // has already shipped once, in commit 3e8bc91.
        BsonDocument original = foreign();
        ScribingSeal.sealed(original, "Larsonix", UUID.randomUUID(), 1L);
        assertFalse(ScribingSeal.isSealed(original));
        assertEquals(1, original.size());
    }

    @Test
    void sealingPreservesUnrelatedKeys() {
        BsonDocument sealed = ScribingSeal.sealed(foreign(), "Larsonix", UUID.randomUUID(), 1L);
        assertTrue(sealed.containsKey(FOREIGN));
        assertTrue(sealed.containsKey(KEY));
    }

    @Test
    void breakingASealKeepsEverythingElse() {
        BsonDocument sealed = ScribingSeal.sealed(foreign(), "Larsonix", UUID.randomUUID(), 1L);
        BsonDocument broken = ScribingSeal.unsealed(sealed);

        assertNotNull(broken);
        assertFalse(ScribingSeal.isSealed(broken));
        assertTrue(broken.containsKey(FOREIGN));
    }

    @Test
    void breakingTheOnlyKeyYieldsNullRatherThanAnEmptyDocument() {
        // Removing the record restores the vanilla fallback. Leaving an empty record does not.
        BsonDocument sealed = ScribingSeal.sealed(null, "Larsonix", UUID.randomUUID(), 1L);
        assertNull(ScribingSeal.unsealed(sealed));
    }

    @Test
    void breakingASealDoesNotTouchTheInputDocument() {
        BsonDocument sealed = ScribingSeal.sealed(foreign(), "Larsonix", UUID.randomUUID(), 1L);
        ScribingSeal.unsealed(sealed);
        assertTrue(ScribingSeal.isSealed(sealed));
    }

    @Test
    void aDamagedPayloadStillCountsAsSealed() {
        // read returns null, so the screen falls back to unattributed wording. The damaged
        // item must still count as sealed or it becomes an accidental way around the seal.
        BsonDocument damaged = new BsonDocument().append(KEY, new BsonString("not a document"));
        assertTrue(ScribingSeal.isSealed(damaged));
        assertNull(ScribingSeal.read(damaged));
    }

    @Test
    void readRejectsAPayloadWithTheWrongFieldTypes() {
        BsonDocument wrong = new BsonDocument().append(KEY, new BsonDocument()
                .append("name", new BsonString("Larsonix"))
                .append("uuid", new BsonString(UUID.randomUUID().toString()))
                .append("epochMillis", new BsonString("not a number")));
        assertTrue(ScribingSeal.isSealed(wrong));
        assertNull(ScribingSeal.read(wrong));
    }

    @Test
    void readRejectsAMalformedUuidWithoutThrowing() {
        BsonDocument bad = new BsonDocument().append(KEY, new BsonDocument()
                .append("name", new BsonString("Larsonix"))
                .append("uuid", new BsonString("obviously-not-a-uuid"))
                .append("epochMillis", new BsonInt64(1L)));
        assertTrue(ScribingSeal.isSealed(bad));
        assertNull(ScribingSeal.read(bad));
    }

    // The tooltip marker tests are all here to protect the player's text.
    //
    // The marker writes a line to the item's ItemDisplay description. This is also where
    // the mod stores the player's own text. A bad restore does more than draw the wrong
    // pixel. It silently destroys writing that a player paid a Scribe Lock to keep. These
    // tests cover the three restore cases and the two ways a damaged payload can reach them.

    private static BsonDocument display(String description) {
        return new BsonDocument().append("Description", new BsonString(description));
    }

    private static BsonDocument withDisplay(String description) {
        return new BsonDocument()
                .append(FOREIGN, new BsonString("keep me"))
                .append(DISPLAY, display(description));
    }

    @Test
    void breakingAMarkedSealRestoresThePriorDisplayVerbatim() {
        BsonDocument prior = display("Forged in the first light of dawn.");
        BsonDocument sealed = ScribingSeal.sealed(
                withDisplay("Forged in the first light of dawn.\n\nSealed by Larsonix"),
                "Larsonix", UUID.randomUUID(), 1L, prior, true);

        BsonDocument broken = ScribingSeal.unsealed(sealed);
        assertNotNull(broken);
        // The restore must be byte for byte. A markup round trip could not preserve another
        // mod's translation key or return an off-palette colour unchanged.
        assertEquals(prior, broken.getDocument(DISPLAY));
        assertTrue(broken.containsKey(FOREIGN));
        assertFalse(ScribingSeal.isSealed(broken));
    }

    @Test
    void breakingAMarkedSealWithNoPriorDisplayRemovesTheEntry() {
        // The player sealed an item without writing anything, so the marker became the
        // entire description. Breaking the seal must remove the entry instead of leaving an
        // empty record, because only absence restores the vanilla name and description.
        BsonDocument sealed = ScribingSeal.sealed(
                withDisplay("Sealed by Larsonix"), "Larsonix", UUID.randomUUID(), 1L, null, true);

        BsonDocument broken = ScribingSeal.unsealed(sealed);
        assertNotNull(broken);
        assertFalse(broken.containsKey(DISPLAY));
        assertTrue(broken.containsKey(FOREIGN));
    }

    @Test
    void breakingASealWrittenBeforeTheMarkerExistedLeavesTheDisplayAlone() {
        // This compatibility case matters because a tester still has one of these items. A
        // seal written before 2026-08-11 has no marked flag and added no line to the tooltip.
        // Its display belongs entirely to the player, so touching it would cause the exact
        // data loss this mechanism exists to prevent.
        BsonDocument sealed = ScribingSeal.sealed(
                withDisplay("Forged in the first light of dawn."),
                "Larsonix", UUID.randomUUID(), 1L);

        BsonDocument broken = ScribingSeal.unsealed(sealed);
        assertNotNull(broken);
        assertEquals(display("Forged in the first light of dawn."), broken.getDocument(DISPLAY));
    }

    @Test
    void aMarkedSealStillReadsAsAnOrdinarySeal() {
        UUID uuid = UUID.randomUUID();
        BsonDocument sealed = ScribingSeal.sealed(
                null, "Larsonix", uuid, 1_760_000_000_000L, display("before"), true);

        ScribingSeal.Seal seal = ScribingSeal.read(sealed);
        assertNotNull(seal);
        assertEquals("Larsonix", seal.playerName());
        assertEquals(uuid, seal.playerUuid());
        assertEquals(1_760_000_000_000L, seal.epochMillis());
    }

    @Test
    void aDamagedMarkerFlagNeitherThrowsNorTouchesTheDisplay() {
        // unsealed runs on a player-reachable path inside the engine tick. An exception
        // there causes a disconnect rather than a refusal. This happened on 2026-08-11 and
        // removed a tester's entity in the middle of a play-test.
        BsonDocument sealed = withDisplay("mine").append(KEY, new BsonDocument()
                .append("name", new BsonString("Larsonix"))
                .append("uuid", new BsonString(UUID.randomUUID().toString()))
                .append("epochMillis", new BsonInt64(1L))
                .append("marked", new BsonString("not a boolean"))
                .append("priorDisplay", new BsonString("not a document")));

        BsonDocument broken = ScribingSeal.unsealed(sealed);
        assertNotNull(broken);
        assertEquals(display("mine"), broken.getDocument(DISPLAY));
    }

    @Test
    void aMarkedSealWithADamagedPriorDisplayRemovesTheEntryRatherThanThrowing() {
        BsonDocument sealed = withDisplay("mine").append(KEY, new BsonDocument()
                .append("name", new BsonString("Larsonix"))
                .append("uuid", new BsonString(UUID.randomUUID().toString()))
                .append("epochMillis", new BsonInt64(1L))
                .append("marked", BsonBoolean.TRUE)
                .append("priorDisplay", new BsonString("not a document")));

        BsonDocument broken = ScribingSeal.unsealed(sealed);
        assertNotNull(broken);
        assertFalse(broken.containsKey(DISPLAY));
    }

    @Test
    void sealingDoesNotTouchThePriorDisplayItWasHanded() {
        BsonDocument prior = display("mine");
        BsonDocument sealed = ScribingSeal.sealed(
                null, "Larsonix", UUID.randomUUID(), 1L, prior, true);

        sealed.getDocument(KEY).getDocument("priorDisplay")
                .put("Description", new BsonString("tampered"));
        assertEquals(display("mine"), prior);
    }

    @Test
    void theDuplicatedDisplayKeyIsTheOneTheRestoreUses() {
        // ScribingSeal cannot read ItemDisplayMetadata.KEY without class-loading an engine
        // type into this test. It copies the literal instead, and
        // ScribingModule.verifyDisplayKey checks that copy at boot. This test verifies that
        // the accessor and restore agree, which is the half of the pairing a unit test can reach.
        assertEquals(DISPLAY, ScribingSeal.displayKey());
    }
}
