package me.ladypaladra.thearmorymod.ui;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LastPickedTest {

    @Test
    void rememberedPickCanBeRecalled() {
        UUID player = UUID.randomUUID();
        LastPicked.Pick pick = new LastPicked.Pick(2, (short) 4, "WardenHelm");

        LastPicked.remember(player, "ArmourBench", pick);

        assertEquals(pick, LastPicked.recall(player, "ArmourBench"));
    }

    @Test
    void playerWhoNeverPickedRecallsNothing() {
        UUID player = UUID.randomUUID();

        assertNull(LastPicked.recall(player, "ArmourBench"));
    }

    @Test
    void playersDoNotShareAPickForTheSameBench() {
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        LastPicked.Pick pick = new LastPicked.Pick(1, (short) 3, "EngineeringRig");

        LastPicked.remember(firstPlayer, "ArmourBench", pick);

        assertEquals(pick, LastPicked.recall(firstPlayer, "ArmourBench"));
        assertNull(LastPicked.recall(secondPlayer, "ArmourBench"));
    }

    @Test
    void onePlayersPicksStaySeparateAcrossBenches() {
        UUID player = UUID.randomUUID();
        LastPicked.Pick armourPick = new LastPicked.Pick(1, (short) 2, "WardenHelm");
        LastPicked.Pick weaponPick = new LastPicked.Pick(3, (short) 5, "Longbow");

        LastPicked.remember(player, "ArmourBench", armourPick);
        LastPicked.remember(player, "WeaponBench", weaponPick);

        assertEquals(armourPick, LastPicked.recall(player, "ArmourBench"));
        assertEquals(weaponPick, LastPicked.recall(player, "WeaponBench"));
    }

    @Test
    void rememberingAgainOverwritesThePreviousPick() {
        UUID player = UUID.randomUUID();
        LastPicked.Pick firstPick = new LastPicked.Pick(1, (short) 2, "WardenHelm");
        LastPicked.Pick replacementPick = new LastPicked.Pick(2, (short) 6, "EngineeringRig");

        LastPicked.remember(player, "ArmourBench", firstPick);
        LastPicked.remember(player, "ArmourBench", replacementPick);

        assertEquals(replacementPick, LastPicked.recall(player, "ArmourBench"));
    }

    @Test
    void forgettingOneBenchLeavesTheOtherBenchPick() {
        UUID player = UUID.randomUUID();
        LastPicked.Pick armourPick = new LastPicked.Pick(1, (short) 2, "WardenHelm");
        LastPicked.Pick weaponPick = new LastPicked.Pick(3, (short) 5, "Longbow");

        LastPicked.remember(player, "ArmourBench", armourPick);
        LastPicked.remember(player, "WeaponBench", weaponPick);

        LastPicked.forget(player, "ArmourBench");

        assertNull(LastPicked.recall(player, "ArmourBench"));
        assertEquals(weaponPick, LastPicked.recall(player, "WeaponBench"));
    }
}
