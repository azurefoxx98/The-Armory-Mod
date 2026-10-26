package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Remembers the move speed change currently written into a player's live movement settings.
 *
 * <p>The move speed stat scales every gait on the ground: walking, running, crouching and
 * sprinting, in every direction. Each gait has its own speed multiplier in the settings, and
 * sprinting does not stack on top of running, so scaling all of them by the same amount
 * changes every gait by exactly that amount.</p>
 *
 * <p>Other code can replace the live settings while the change is on, a game mode applying its
 * own movement config for example. Keeping the speeds the change started from, and the speeds
 * it wrote, lets it recognise its own values, scale whatever replaced them, and undo exactly
 * its own change when the stat goes back to normal. Restoring the remembered speeds instead
 * of dividing by the multiplier keeps a full stop, a multiplier of zero, reversible.</p>
 */
public final class GroundSpeedState implements Component<EntityStore> {

    private static final int GAITS = 10;

    private boolean active;
    private float[] baseSpeeds = new float[GAITS];
    private float[] writtenSpeeds = new float[GAITS];

    public boolean isActive() {
        return active;
    }

    /**
     * Brings the live settings in line with the given multiplier.
     *
     * @param live the player's live movement settings, changed in place
     * @param multiplier the move speed multiplier, 1.0 for none
     * @return true when the live settings were changed and must be sent to the client
     */
    public boolean reconcile(@Nonnull MovementSettings live, float multiplier) {
        float[] liveSpeeds = read(live);
        boolean ours = active && same(liveSpeeds, writtenSpeeds);

        if (Math.abs(multiplier - 1.0F) <= StatMultiplier.EPSILON) {
            active = false;
            if (!ours) {
                // Nothing of ours is in place, or someone else replaced it. Either way, leave it.
                return false;
            }
            write(live, baseSpeeds);
            return true;
        }

        // While our change is in place the speeds it started from are still the base. Anything
        // else in the live settings is a new base, and the change is applied on top of it.
        float[] base = ours ? baseSpeeds : liveSpeeds;
        float[] target = scaled(base, multiplier);
        if (ours && same(writtenSpeeds, target)) {
            return false;
        }

        write(live, target);
        baseSpeeds = base;
        writtenSpeeds = target;
        active = true;
        return true;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        GroundSpeedState copy = new GroundSpeedState();
        copy.active = active;
        copy.baseSpeeds = baseSpeeds.clone();
        copy.writtenSpeeds = writtenSpeeds.clone();
        return copy;
    }

    @Nonnull
    private static float[] read(@Nonnull MovementSettings settings) {
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

    private static void write(@Nonnull MovementSettings settings, @Nonnull float[] speeds) {
        settings.forwardWalkSpeedMultiplier = speeds[0];
        settings.backwardWalkSpeedMultiplier = speeds[1];
        settings.strafeWalkSpeedMultiplier = speeds[2];
        settings.forwardRunSpeedMultiplier = speeds[3];
        settings.backwardRunSpeedMultiplier = speeds[4];
        settings.strafeRunSpeedMultiplier = speeds[5];
        settings.forwardCrouchSpeedMultiplier = speeds[6];
        settings.backwardCrouchSpeedMultiplier = speeds[7];
        settings.strafeCrouchSpeedMultiplier = speeds[8];
        settings.forwardSprintSpeedMultiplier = speeds[9];
    }

    @Nonnull
    private static float[] scaled(@Nonnull float[] speeds, float multiplier) {
        float[] result = Arrays.copyOf(speeds, speeds.length);
        for (int i = 0; i < result.length; i++) {
            result[i] *= multiplier;
        }
        return result;
    }

    private static boolean same(@Nonnull float[] a, @Nonnull float[] b) {
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > StatMultiplier.EPSILON) {
                return false;
            }
        }
        return true;
    }
}
