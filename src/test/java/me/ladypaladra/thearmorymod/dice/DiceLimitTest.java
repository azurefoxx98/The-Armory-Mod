package me.ladypaladra.thearmorymod.dice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiceLimitTest {

    private static final String VIP = DiceLimit.NAMED_PREFIX + "vip";
    private static final String STAFF = DiceLimit.NAMED_PREFIX + "staff";
    private static final Map<String, Integer> NAMED = Map.of(VIP, 50, STAFF, -1);

    @TempDir
    Path folder;

    @Test
    void aPlayerWithNoPermissionGetsEveryonesLimit() {
        assertEquals(10, DiceLimit.resolve(10, NAMED, node -> false));
        assertEquals(0, DiceLimit.resolve(0, NAMED, node -> false));
    }

    @Test
    void aNamedLimitOnlyEverRaisesIt() {
        assertEquals(50, DiceLimit.resolve(10, NAMED, Set.of(VIP)::contains));
        assertEquals(80, DiceLimit.resolve(80, NAMED, Set.of(VIP)::contains));
    }

    @Test
    void noLimitWinsOverAnyNumber() {
        assertEquals(DiceLimit.NONE, DiceLimit.resolve(10, NAMED, Set.of(VIP, STAFF)::contains));
        assertEquals(DiceLimit.NONE, DiceLimit.resolve(10, NAMED, Set.of(DiceLimit.UNLIMITED_PERMISSION)::contains));
        assertEquals(DiceLimit.NONE, DiceLimit.resolve(-1, NAMED, node -> false));
        // An operator holds every permission there is.
        assertEquals(DiceLimit.NONE, DiceLimit.resolve(10, NAMED, node -> true));
    }

    @Test
    void aThrowUnderTheLimitCallsNothingBack() {
        DiceLedger ledger = ledger();
        UUID owner = UUID.randomUUID();
        lay(ledger, owner, 9);
        List<UUID> recalled = new ArrayList<>();
        assertEquals(1, DiceLimit.makeRoom(ledger, owner, 1, 10, recalled::add));
        assertTrue(recalled.isEmpty());
        assertEquals(9, ledger.out(owner));
    }

    @Test
    void aThrowPastTheLimitCallsBackTheOldestLyingDice() {
        DiceLedger ledger = ledger();
        UUID owner = UUID.randomUUID();
        List<UUID> laid = lay(ledger, owner, 10);
        List<UUID> recalled = new ArrayList<>();
        assertEquals(1, DiceLimit.makeRoom(ledger, owner, 1, 10, recalled::add));
        assertEquals(laid.subList(0, 1), recalled);

        // A handful of five with nine out: four more come back, oldest first.
        recalled.clear();
        assertEquals(5, DiceLimit.makeRoom(ledger, owner, 5, 10, recalled::add));
        assertEquals(laid.subList(1, 5), recalled);
        assertEquals(5, ledger.out(owner));
        assertEquals(5, ledger.takeOwed(owner).size());
    }

    @Test
    void diceInTheAirCannotBeCalledBackSoFewerAreThrown() {
        DiceLedger ledger = ledger();
        UUID owner = UUID.randomUUID();
        for (int i = 0; i < 8; i++) {
            ledger.thrown(UUID.randomUUID(), owner, "Armory_Dice_D6", 1);
        }
        List<UUID> recalled = new ArrayList<>();
        assertEquals(2, DiceLimit.makeRoom(ledger, owner, 5, 10, recalled::add));
        ledger.thrown(UUID.randomUUID(), owner, "Armory_Dice_D6", 1);
        ledger.thrown(UUID.randomUUID(), owner, "Armory_Dice_D6", 1);
        assertEquals(0, DiceLimit.makeRoom(ledger, owner, 1, 10, recalled::add));
        assertTrue(recalled.isEmpty());
    }

    @Test
    void theSmallestLimitsHold() {
        DiceLedger ledger = ledger();
        UUID owner = UUID.randomUUID();
        List<UUID> recalled = new ArrayList<>();
        assertEquals(0, DiceLimit.makeRoom(ledger, owner, 5, 0, recalled::add));
        assertEquals(1, DiceLimit.makeRoom(ledger, owner, 5, 1, recalled::add));
        assertEquals(2, DiceLimit.makeRoom(ledger, owner, 5, 2, recalled::add));
        assertTrue(recalled.isEmpty());

        // With a limit of one, each new die sends the last one home.
        List<UUID> laid = lay(ledger, owner, 1);
        assertEquals(1, DiceLimit.makeRoom(ledger, owner, 1, 1, recalled::add));
        assertEquals(laid, recalled);
    }

    @Test
    void noLimitThrowsEverythingAndCallsNothingBack() {
        DiceLedger ledger = ledger();
        UUID owner = UUID.randomUUID();
        lay(ledger, owner, 40);
        List<UUID> recalled = new ArrayList<>();
        assertEquals(5, DiceLimit.makeRoom(ledger, owner, 5, DiceLimit.NONE, recalled::add));
        assertTrue(recalled.isEmpty());
    }

    @Test
    void onePlayersDiceNeverCountForAnother() {
        DiceLedger ledger = ledger();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        lay(ledger, other, 10);
        List<UUID> recalled = new ArrayList<>();
        assertEquals(5, DiceLimit.makeRoom(ledger, owner, 5, 10, recalled::add));
        assertTrue(recalled.isEmpty());
        assertEquals(10, ledger.out(other));
    }

    private final List<DiceLedger> opened = new ArrayList<>();

    // The ledger writes its file on a thread of its own: each is closed, which waits for that, before the folder goes.
    @AfterEach
    void closeEveryLedger() {
        opened.forEach(DiceLedger::close);
    }

    private DiceLedger ledger() {
        DiceLedger ledger = DiceLedger.open(folder.resolve("dice-ledger.json"));
        opened.add(ledger);
        return ledger;
    }

    private static List<UUID> lay(DiceLedger ledger, UUID owner, int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            ledger.thrown(id, owner, "Armory_Dice_D6", 1);
            ledger.lying(id);
            ids.add(id);
        }
        return ids;
    }
}
