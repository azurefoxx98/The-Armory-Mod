package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Remembers the swim boost currently written into a player's live movement settings.
 *
 * <p>Other code can replace the live settings, a mount applying its own movement config for
 * example. Keeping the speed the boost started from, and the value it wrote, lets the boost
 * undo exactly its own change and leave anyone else's change alone.</p>
 */
public final class SwimBoostState implements Component<EntityStore> {

    private boolean active;
    private float baseSpeed;
    private float boostedSpeed;

    public boolean isActive() {
        return active;
    }

    public float getBaseSpeed() {
        return baseSpeed;
    }

    public float getBoostedSpeed() {
        return boostedSpeed;
    }

    public void start(float baseSpeed, float boostedSpeed) {
        this.active = true;
        this.baseSpeed = baseSpeed;
        this.boostedSpeed = boostedSpeed;
    }

    public void stop() {
        this.active = false;
    }

    @Nonnull
    @Override
    public Component<EntityStore> clone() {
        SwimBoostState copy = new SwimBoostState();
        copy.active = active;
        copy.baseSpeed = baseSpeed;
        copy.boostedSpeed = boostedSpeed;
        return copy;
    }
}
