package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.protocol.MovementSettings;

import javax.annotation.Nonnull;

/**
 * The jump height stat's change to a player's movement settings: the force of a jump from the
 * ground and of a jump out of water, scaled together.
 */
public final class JumpForceState extends ScaledMovementState {

    /**
     * The force multiplier that gives a jump the requested height multiplier.
     *
     * <p>A jump's force sets the speed it leaves the ground with, and the height it reaches grows
     * with the square of that speed. Scaling the force by the stat directly made a +20% piece
     * jump 42% higher and a -20% set jump 35% lower. The square root makes the stat's number the
     * height a player actually gets.</p>
     *
     * @param heightMultiplier the jump height stat's multiplier, never negative
     * @return the multiplier to apply to the jump forces
     */
    public static float forceForHeight(float heightMultiplier) {
        return (float) Math.sqrt(Math.max(0.0F, heightMultiplier));
    }

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
