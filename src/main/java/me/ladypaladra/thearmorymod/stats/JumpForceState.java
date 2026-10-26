package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.protocol.MovementSettings;

import javax.annotation.Nonnull;

/**
 * The jump height stat's change to a player's movement settings: the force of a jump from the
 * ground and of a jump out of water, scaled together.
 */
public final class JumpForceState extends ScaledMovementState {

    @Nonnull
    @Override
    protected float[] read(@Nonnull MovementSettings settings) {
        return new float[]{settings.jumpForce, settings.swimJumpForce};
    }

    @Override
    protected void write(@Nonnull MovementSettings settings, @Nonnull float[] values) {
        settings.jumpForce = values[0];
        settings.swimJumpForce = values[1];
    }

    @Nonnull
    @Override
    protected ScaledMovementState newEmpty() {
        return new JumpForceState();
    }
}
