package me.ladypaladra.thearmorymod.parry.components;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.parry.ParryModule;

import javax.annotation.Nonnull;

public class StunComponent implements Component<EntityStore> {

    private float timeRemaining;

    public StunComponent() {
        this(0.0F);
    }

    public StunComponent(float timeRemaining) {
        this.timeRemaining = timeRemaining;
    }

    public static ComponentType<EntityStore, StunComponent> getComponentType() {
        return ParryModule.getStunComponentType();
    }

    public float getTimeRemaining() {
        return timeRemaining;
    }

    public void setTimeRemaining(float timeRemaining) {
        this.timeRemaining = timeRemaining;
    }

    @Nonnull
    public StunComponent clone() {
        return new StunComponent(timeRemaining);
    }
}