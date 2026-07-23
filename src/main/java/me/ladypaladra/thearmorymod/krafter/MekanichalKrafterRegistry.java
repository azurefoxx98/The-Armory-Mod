package me.ladypaladra.thearmorymod.krafter;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;

public final class MekanichalKrafterRegistry {

    private static ComponentType<ChunkStore, MekanichalKrafterBlock> componentType;

    private MekanichalKrafterRegistry() {
    }

    public static void setComponentType(
            @Nonnull ComponentType<ChunkStore, MekanichalKrafterBlock> type
    ) {
        componentType = type;
    }

    @Nonnull
    public static ComponentType<ChunkStore, MekanichalKrafterBlock> getComponentType() {
        if (componentType == null) {
            throw new IllegalStateException("MekanichalKrafterBlock component type has not been registered yet.");
        }

        return componentType;
    }
}