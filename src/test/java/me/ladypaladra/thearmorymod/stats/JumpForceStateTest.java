package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.protocol.MovementSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JumpForceStateTest {

    private static final float DELTA = 0.00001F;

    /** The engine's master defaults. */
    private static final float JUMP = 11.8F;
    private static final float SWIM_JUMP = 10.0F;

    @Test
    void bothJumpsChangeTogether() {
        MovementSettings live = settings(JUMP, SWIM_JUMP);
        JumpForceState state = new JumpForceState();

        assertTrue(state.reconcile(live, 0.8F));

        assertEquals(JUMP * 0.8F, live.jumpForce, DELTA);
        assertEquals(SWIM_JUMP * 0.8F, live.swimJumpForce, DELTA);
    }

    @Test
    void takingTheArmourOffRestoresTheJumpTheGameHad() {
        MovementSettings live = settings(JUMP, SWIM_JUMP);
        JumpForceState state = new JumpForceState();
        state.reconcile(live, 1.2F);

        assertTrue(state.reconcile(live, 1.0F));

        assertEquals(JUMP, live.jumpForce, DELTA);
        assertEquals(SWIM_JUMP, live.swimJumpForce, DELTA);
    }

    @Test
    void aJumpSomethingElseInstalledIsScaledInsteadOfOverwritten() {
        MovementSettings live = settings(JUMP, SWIM_JUMP);
        JumpForceState state = new JumpForceState();
        state.reconcile(live, 1.2F);

        live.jumpForce = 20.0F;
        live.swimJumpForce = 4.0F;
        assertTrue(state.reconcile(live, 1.2F));

        assertEquals(20.0F * 1.2F, live.jumpForce, DELTA);
        assertEquals(4.0F * 1.2F, live.swimJumpForce, DELTA);
    }

    @Test
    void takingTheArmourOffLeavesAJumpSomethingElseInstalled() {
        MovementSettings live = settings(JUMP, SWIM_JUMP);
        JumpForceState state = new JumpForceState();
        state.reconcile(live, 1.2F);
        live.jumpForce = 20.0F;
        live.swimJumpForce = 4.0F;

        assertFalse(state.reconcile(live, 1.0F));

        assertEquals(20.0F, live.jumpForce, DELTA);
        assertEquals(4.0F, live.swimJumpForce, DELTA);
    }

    @Test
    void aJumpChangeLeavesTheGroundSpeedsAlone() {
        MovementSettings live = settings(JUMP, SWIM_JUMP);
        live.forwardRunSpeedMultiplier = 1.0F;
        live.forwardSprintSpeedMultiplier = 1.65F;

        new JumpForceState().reconcile(live, 0.8F);

        assertEquals(1.0F, live.forwardRunSpeedMultiplier, DELTA);
        assertEquals(1.65F, live.forwardSprintSpeedMultiplier, DELTA);
    }

    @Test
    void aCopyKeepsTheChangeItWasMadeFrom() {
        MovementSettings live = settings(JUMP, SWIM_JUMP);
        JumpForceState state = new JumpForceState();
        state.reconcile(live, 0.8F);

        JumpForceState copy = (JumpForceState) state.clone();
        assertTrue(copy.reconcile(live, 1.0F));

        assertEquals(JUMP, live.jumpForce, DELTA);
        assertTrue(state.isActive());
    }

    private static MovementSettings settings(float jumpForce, float swimJumpForce) {
        MovementSettings settings = new MovementSettings();
        settings.jumpForce = jumpForce;
        settings.swimJumpForce = swimJumpForce;
        return settings;
    }
}
