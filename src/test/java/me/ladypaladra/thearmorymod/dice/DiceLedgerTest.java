package me.ladypaladra.thearmorymod.dice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiceLedgerTest {

    private static final String D6 = "Armory_Dice_D6";
    private static final String D100 = "Armory_Dice_D100";

    @TempDir
    Path folder;

    @Test
    void aThrownDieCountsUntilItLeavesTheGround() {
        DiceLedger ledger = open();
        UUID owner = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ledger.thrown(first, owner, D6, 1);
        ledger.thrown(second, owner, D6, 1);
        assertEquals(2, ledger.out(owner));

        ledger.lying(first);
        assertEquals(2, ledger.out(owner));
        ledger.gone(first);
        assertEquals(1, ledger.out(owner));

        // Lost in the air: owed back, and no longer out in the world.
        ledger.lost(second);
        assertEquals(0, ledger.out(owner));
        assertEquals(List.of(D6), ledger.takeOwed(owner));
    }

    @Test
    void aDieStillInTheAirIsNotStruckOffAsGone() {
        DiceLedger ledger = open();
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        ledger.thrown(id, owner, D100, 2);
        // One die of a D100 leaving the ground while the other still flies must not drop the item from the count.
        ledger.gone(id);
        assertEquals(1, ledger.out(owner));
    }

    @Test
    void aDieOnItsWayIntoAHandDoesNotCount() {
        DiceLedger ledger = open();
        UUID taker = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();
        ledger.flying(delivery, taker, D6);
        assertEquals(0, ledger.out(taker));
        ledger.landed(delivery);
        assertTrue(ledger.takeOwed(taker).isEmpty());
    }

    @Test
    void theOldestLyingDieIsCalledBackAndOwedOnce() {
        DiceLedger ledger = open();
        UUID owner = UUID.randomUUID();
        UUID flying = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        ledger.thrown(flying, owner, D6, 1);
        ledger.thrown(older, owner, D6, 1);
        ledger.thrown(newer, owner, D6, 1);
        ledger.lying(newer);
        ledger.lying(older);

        // Oldest by throw, and never the one still in the air.
        assertEquals(older, ledger.recallOldest(owner));
        assertTrue(ledger.isRecalled(older));
        assertEquals(2, ledger.out(owner));
        assertEquals(List.of(D6), ledger.takeOwed(owner));

        // Its die is cleared from the ground, by anyone: nothing more is owed for it.
        assertTrue(ledger.clearRecalled(older));
        assertFalse(ledger.isRecalled(older));
        assertFalse(ledger.clearRecalled(older));
        ledger.gone(older);
        assertTrue(ledger.takeOwed(owner).isEmpty());
    }

    @Test
    void nothingLyingMeansNothingToCallBack() {
        DiceLedger ledger = open();
        UUID owner = UUID.randomUUID();
        ledger.thrown(UUID.randomUUID(), owner, D6, 1);
        assertNull(ledger.recallOldest(owner));
        assertNull(ledger.recallOldest(UUID.randomUUID()));
    }

    @Test
    void aCalledBackPairWaitsForBothItsDice() {
        DiceLedger ledger = open();
        UUID owner = UUID.randomUUID();
        UUID pair = UUID.randomUUID();
        ledger.thrown(pair, owner, D100, 2);
        ledger.lying(pair);
        assertEquals(pair, ledger.recallOldest(owner));
        assertTrue(ledger.clearRecalled(pair));
        assertTrue(ledger.isRecalled(pair));
        assertTrue(ledger.clearRecalled(pair));
        assertFalse(ledger.isRecalled(pair));
        assertEquals(List.of(D100), ledger.takeOwed(owner));
    }

    @Test
    void aRestartKeepsLyingDiceCountedAndOwesTheFlyingOnes() {
        UUID owner = UUID.randomUUID();
        UUID lying = UUID.randomUUID();
        UUID recalled = UUID.randomUUID();
        DiceLedger before = open();
        before.thrown(lying, owner, D6, 1);
        before.lying(lying);
        before.thrown(recalled, owner, D100, 2);
        before.lying(recalled);
        before.thrown(UUID.randomUUID(), owner, D6, 1);
        before.gone(UUID.randomUUID());
        before.close();

        // Reopened as it was written: one lying, one in the air.
        DiceLedger after = open();
        assertTrue(after.anyOwed());
        assertEquals(List.of(D6), after.takeOwed(owner));
        assertEquals(2, after.out(owner));
        assertEquals(lying, after.recallOldest(owner));
        assertEquals(recalled, after.recallOldest(owner));
        after.close();

        // And a die called back before a restart is still cleared after it.
        DiceLedger last = open();
        assertTrue(last.isRecalled(lying));
        assertTrue(last.isRecalled(recalled));
        assertEquals(0, last.out(owner));
        assertEquals(2, last.takeOwed(owner).size());
        last.close();
    }

    @Test
    void aLedgerWrittenBeforeTheLimitStillOwesItsFlyingDice() {
        UUID owner = UUID.randomUUID();
        DiceLedger before = open();
        before.flying(UUID.randomUUID(), owner, D6);
        before.close();

        DiceLedger after = open();
        assertEquals(0, after.out(owner));
        assertEquals(List.of(D6), after.takeOwed(owner));
        after.close();
    }

    private final List<DiceLedger> opened = new ArrayList<>();

    // The ledger writes its file on a thread of its own: each is closed, which waits for that, before the folder goes.
    @AfterEach
    void closeEveryLedger() {
        opened.forEach(DiceLedger::close);
    }

    private DiceLedger open() {
        DiceLedger ledger = DiceLedger.open(folder.resolve("dice-ledger.json"));
        opened.add(ledger);
        return ledger;
    }
}
