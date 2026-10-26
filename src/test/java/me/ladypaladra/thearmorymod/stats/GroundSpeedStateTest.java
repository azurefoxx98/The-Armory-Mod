package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.protocol.MovementSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundSpeedStateTest {

    private static final float DELTA = 0.00001F;

    @Test
    void slowingArmourSlowsEveryGaitNotOnlySprinting() {
        MovementSettings live = defaults();
        GroundSpeedState state = new GroundSpeedState();

        assertTrue(state.reconcile(live, 0.7F));

        assertGaits(live, defaults(), 0.7F);
    }

    @Test
    void engineeringRigSpeedsUpWalkingAsWellAsSprinting() {
        MovementSettings live = defaults();
        GroundSpeedState state = new GroundSpeedState();

        state.reconcile(live, 1.2F);

        assertEquals(0.3F * 1.2F, live.forwardWalkSpeedMultiplier, DELTA);
        assertEquals(1.0F * 1.2F, live.forwardRunSpeedMultiplier, DELTA);
        assertEquals(1.65F * 1.2F, live.forwardSprintSpeedMultiplier, DELTA);
    }

    @Test
    void aChangeAlreadyInPlaceIsNotSentAgain() {
        MovementSettings live = defaults();
        GroundSpeedState state = new GroundSpeedState();
        state.reconcile(live, 0.7F);

        assertFalse(state.reconcile(live, 0.7F));
        assertGaits(live, defaults(), 0.7F);
    }

    @Test
    void takingTheArmourOffRestoresTheSpeedsTheGameHad() {
        MovementSettings live = defaults();
        GroundSpeedState state = new GroundSpeedState();
        state.reconcile(live, 0.7F);

        assertTrue(state.reconcile(live, 1.0F));

        assertGaits(live, defaults(), 1.0F);
        assertFalse(state.isActive());
    }

    @Test
    void aNewMultiplierStartsFromTheOriginalSpeedsInsteadOfCompounding() {
        MovementSettings live = defaults();
        GroundSpeedState state = new GroundSpeedState();
        state.reconcile(live, 0.7F);

        state.reconcile(live, 0.5F);

        assertGaits(live, defaults(), 0.5F);
    }

    @Test
    void aFullStopIsReversible() {
        MovementSettings live = defaults();
        GroundSpeedState state = new GroundSpeedState();
        state.reconcile(live, 0.0F);
        assertGaits(live, defaults(), 0.0F);

        state.reconcile(live, 1.0F);

        assertGaits(live, defaults(), 1.0F);
    }

    @Test
    void speedsSomethingElseInstalledAreScaledInsteadOfOverwritten() {
        MovementSettings live = defaults();
        GroundSpeedState state = new GroundSpeedState();
        state.reconcile(live, 0.7F);

        MovementSettings other = otherConfig();
        copyGaits(other, live);
        assertTrue(state.reconcile(live, 0.7F));

        assertGaits(live, otherConfig(), 0.7F);
    }

    @Test
    void takingTheArmourOffLeavesSpeedsSomethingElseInstalled() {
        MovementSettings live = defaults();
        GroundSpeedState state = new GroundSpeedState();
        state.reconcile(live, 0.7F);
        copyGaits(otherConfig(), live);

        assertFalse(state.reconcile(live, 1.0F));

        assertGaits(live, otherConfig(), 1.0F);
    }

    @Test
    void noArmourChangesNothing() {
        MovementSettings live = defaults();
        GroundSpeedState state = new GroundSpeedState();

        assertFalse(state.reconcile(live, 1.0F));

        assertGaits(live, defaults(), 1.0F);
        assertFalse(state.isActive());
    }

    @Test
    void aCopyKeepsTheChangeItWasMadeFrom() {
        MovementSettings live = defaults();
        GroundSpeedState state = new GroundSpeedState();
        state.reconcile(live, 0.7F);

        GroundSpeedState copy = (GroundSpeedState) state.clone();
        assertTrue(copy.reconcile(live, 1.0F));

        assertGaits(live, defaults(), 1.0F);
        assertTrue(state.isActive());
    }

    /** The speeds the engine's own master defaults give each gait. */
    private static MovementSettings defaults() {
        MovementSettings settings = new MovementSettings();
        settings.forwardWalkSpeedMultiplier = 0.3F;
        settings.backwardWalkSpeedMultiplier = 0.3F;
        settings.strafeWalkSpeedMultiplier = 0.3F;
        settings.forwardRunSpeedMultiplier = 1.0F;
        settings.backwardRunSpeedMultiplier = 0.65F;
        settings.strafeRunSpeedMultiplier = 0.8F;
        settings.forwardCrouchSpeedMultiplier = 0.55F;
        settings.backwardCrouchSpeedMultiplier = 0.4F;
        settings.strafeCrouchSpeedMultiplier = 0.45F;
        settings.forwardSprintSpeedMultiplier = 1.65F;
        return settings;
    }

    /** Different speeds for every gait, standing in for a movement config another system applies. */
    private static MovementSettings otherConfig() {
        MovementSettings settings = new MovementSettings();
        settings.forwardWalkSpeedMultiplier = 0.2F;
        settings.backwardWalkSpeedMultiplier = 0.25F;
        settings.strafeWalkSpeedMultiplier = 0.35F;
        settings.forwardRunSpeedMultiplier = 1.5F;
        settings.backwardRunSpeedMultiplier = 0.9F;
        settings.strafeRunSpeedMultiplier = 1.1F;
        settings.forwardCrouchSpeedMultiplier = 0.6F;
        settings.backwardCrouchSpeedMultiplier = 0.5F;
        settings.strafeCrouchSpeedMultiplier = 0.55F;
        settings.forwardSprintSpeedMultiplier = 2.0F;
        return settings;
    }

    private static void copyGaits(MovementSettings from, MovementSettings to) {
        to.forwardWalkSpeedMultiplier = from.forwardWalkSpeedMultiplier;
        to.backwardWalkSpeedMultiplier = from.backwardWalkSpeedMultiplier;
        to.strafeWalkSpeedMultiplier = from.strafeWalkSpeedMultiplier;
        to.forwardRunSpeedMultiplier = from.forwardRunSpeedMultiplier;
        to.backwardRunSpeedMultiplier = from.backwardRunSpeedMultiplier;
        to.strafeRunSpeedMultiplier = from.strafeRunSpeedMultiplier;
        to.forwardCrouchSpeedMultiplier = from.forwardCrouchSpeedMultiplier;
        to.backwardCrouchSpeedMultiplier = from.backwardCrouchSpeedMultiplier;
        to.strafeCrouchSpeedMultiplier = from.strafeCrouchSpeedMultiplier;
        to.forwardSprintSpeedMultiplier = from.forwardSprintSpeedMultiplier;
    }

    private static void assertGaits(MovementSettings live, MovementSettings base, float multiplier) {
        assertEquals(base.forwardWalkSpeedMultiplier * multiplier, live.forwardWalkSpeedMultiplier, DELTA);
        assertEquals(base.backwardWalkSpeedMultiplier * multiplier, live.backwardWalkSpeedMultiplier, DELTA);
        assertEquals(base.strafeWalkSpeedMultiplier * multiplier, live.strafeWalkSpeedMultiplier, DELTA);
        assertEquals(base.forwardRunSpeedMultiplier * multiplier, live.forwardRunSpeedMultiplier, DELTA);
        assertEquals(base.backwardRunSpeedMultiplier * multiplier, live.backwardRunSpeedMultiplier, DELTA);
        assertEquals(base.strafeRunSpeedMultiplier * multiplier, live.strafeRunSpeedMultiplier, DELTA);
        assertEquals(base.forwardCrouchSpeedMultiplier * multiplier, live.forwardCrouchSpeedMultiplier, DELTA);
        assertEquals(base.backwardCrouchSpeedMultiplier * multiplier, live.backwardCrouchSpeedMultiplier, DELTA);
        assertEquals(base.strafeCrouchSpeedMultiplier * multiplier, live.strafeCrouchSpeedMultiplier, DELTA);
        assertEquals(base.forwardSprintSpeedMultiplier * multiplier, live.forwardSprintSpeedMultiplier, DELTA);
    }
}
