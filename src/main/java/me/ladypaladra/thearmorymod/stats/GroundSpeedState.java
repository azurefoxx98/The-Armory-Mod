package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.protocol.MovementSettings;

import javax.annotation.Nonnull;

/**
 * The move speed stat's change to a player's movement settings.
 *
 * <p>The stat scales every gait on the ground: walking, running, crouching and sprinting, in
 * every direction. Each gait has its own speed multiplier in the settings, and sprinting does
 * not stack on top of running, so scaling all of them by the same amount changes every gait by
 * exactly that amount.</p>
 */
public final class GroundSpeedState extends ScaledMovementState {

    @Nonnull
    @Override
    protected float[] read(@Nonnull MovementSettings settings) {
        return new float[]{
                settings.forwardWalkSpeedMultiplier,
                settings.backwardWalkSpeedMultiplier,
                settings.strafeWalkSpeedMultiplier,
                settings.forwardRunSpeedMultiplier,
                settings.backwardRunSpeedMultiplier,
                settings.strafeRunSpeedMultiplier,
                settings.forwardCrouchSpeedMultiplier,
                settings.backwardCrouchSpeedMultiplier,
                settings.strafeCrouchSpeedMultiplier,
                settings.forwardSprintSpeedMultiplier
        };
    }

    @Override
    protected void write(@Nonnull MovementSettings settings, @Nonnull float[] values) {
        settings.forwardWalkSpeedMultiplier = values[0];
        settings.backwardWalkSpeedMultiplier = values[1];
        settings.strafeWalkSpeedMultiplier = values[2];
        settings.forwardRunSpeedMultiplier = values[3];
        settings.backwardRunSpeedMultiplier = values[4];
        settings.strafeRunSpeedMultiplier = values[5];
        settings.forwardCrouchSpeedMultiplier = values[6];
        settings.backwardCrouchSpeedMultiplier = values[7];
        settings.strafeCrouchSpeedMultiplier = values[8];
        settings.forwardSprintSpeedMultiplier = values[9];
    }

    @Nonnull
    @Override
    protected ScaledMovementState newEmpty() {
        return new GroundSpeedState();
    }
}
