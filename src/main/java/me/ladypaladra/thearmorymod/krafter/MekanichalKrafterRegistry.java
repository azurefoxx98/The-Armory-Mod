package me.ladypaladra.thearmorymod.krafter;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;

public final class MekanichalKrafterRegistry {

    private static ComponentType<ChunkStore, MekanichalKrafterBlock> componentType;
    private static ComponentType<ChunkStore, MekanichalKrafterDispenser> dispenserComponentType;

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

    public static void setDispenserComponentType(
            @Nonnull ComponentType<ChunkStore, MekanichalKrafterDispenser> type
    ) {
        dispenserComponentType = type;
    }

    @Nonnull
    public static ComponentType<ChunkStore, MekanichalKrafterDispenser> getDispenserComponentType() {
        if (dispenserComponentType == null) {
            throw new IllegalStateException("MekanichalKrafterDispenser component type has not been registered yet.");
        }

        return dispenserComponentType;
    }
}