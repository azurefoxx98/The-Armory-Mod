package me.ladypaladra.thearmorymod.alteration;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;

public final class AlterationRegistry {

    private static ComponentType<ChunkStore, AlterationTableBlock> componentType;

    private AlterationRegistry() {
    }

    public static void setComponentType(
            @Nonnull ComponentType<ChunkStore, AlterationTableBlock> type
    ) {
        componentType = type;
    }

    @Nonnull
    public static ComponentType<ChunkStore, AlterationTableBlock> getComponentType() {
        if (componentType == null) {
            throw new IllegalStateException("AlterationTableBlock component type has not been registered yet.");
        }

        return componentType;
    }
}
