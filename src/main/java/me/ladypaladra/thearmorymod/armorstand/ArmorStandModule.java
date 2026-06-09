package me.ladypaladra.thearmorymod.armorstand;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import me.ladypaladra.thearmorymod.TheArmoryMod;
import me.ladypaladra.thearmorymod.armorstand.blockstate.ArmorStandComponent;
import me.ladypaladra.thearmorymod.armorstand.blockstate.ArmorStandLegacyMannequinSystem;
import me.ladypaladra.thearmorymod.armorstand.blockstate.ArmorStandOnRemoveSystem;
import me.ladypaladra.thearmorymod.armorstand.blockstate.ArmorStandTickSystem;
import me.ladypaladra.thearmorymod.armorstand.command.ArmorStandCommand;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ArmorStandModule {

    private static final Set<Ref<EntityStore>> activeMannequins = ConcurrentHashMap.newKeySet();

    private ArmorStandModule() {}

    public static void register(TheArmoryMod plugin) {
        TheArmoryMod.LOGGER.atInfo().log("Registering ArmorStandModule...");

        ComponentType<ChunkStore, ArmorStandComponent> compType =
                plugin.getChunkStoreRegistry().registerComponent(
                        ArmorStandComponent.class,
                        ArmorStandCommand.ARMOR_STAND_BLOCK_ID,
                        ArmorStandComponent.CODEC
                );

        ArmorStandComponent.setComponentType(compType);

        plugin.getEntityStoreRegistry().registerSystem(new ArmorStandLegacyMannequinSystem());

        plugin.getChunkStoreRegistry().registerSystem(new ArmorStandTickSystem(compType));
        plugin.getChunkStoreRegistry().registerSystem(new ArmorStandOnRemoveSystem(compType));

        plugin.getCommandRegistry().registerCommand(new ArmorStandCommand());

        TheArmoryMod.LOGGER.atInfo().log("ArmorStandModule registered!");
    }

    public static void shutdown(TheArmoryMod plugin) {
        Set<Ref<EntityStore>> mannequinRefs = snapshotTrackedMannequinRefs();
        int count = mannequinRefs.size();

        activeMannequins.clear();

        for (Ref<EntityStore> mannequinRef : mannequinRefs) {
            scheduleTrackedMannequinRemoval(mannequinRef);
        }

        TheArmoryMod.LOGGER.atInfo().log(
                "ArmorStandModule shutdown - cleanup scheduled for " + count + " mannequin(s)"
        );
    }

    private static Set<Ref<EntityStore>> snapshotTrackedMannequinRefs() {
        return new HashSet<>(activeMannequins);
    }

    private static void scheduleTrackedMannequinRemoval(Ref<EntityStore> mannequinRef) {
        try {
            if (mannequinRef == null || !mannequinRef.isValid()) return;

            Store<EntityStore> entityStore = mannequinRef.getStore();
            if (entityStore == null) return;

            World world = entityStore.getExternalData().getWorld();
            if (world == null) return;

            world.execute(() -> {
                try {
                    if (mannequinRef.isValid()) {
                        mannequinRef.getStore().removeEntity(mannequinRef, RemoveReason.REMOVE);
                    }
                } catch (Exception ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    public static void trackMannequin(Ref<EntityStore> mannequinRef) {
        if (mannequinRef != null) {
            activeMannequins.add(mannequinRef);
        }
    }

    public static void untrackMannequin(Ref<EntityStore> mannequinRef) {
        if (mannequinRef != null) {
            activeMannequins.remove(mannequinRef);
        }
    }

    public static void trackMannequin(NPCEntity npc) {
        if (npc == null) return;

        try {
            trackMannequin(npc.getReference());
        } catch (Exception ignored) {}
    }

    public static void untrackMannequin(NPCEntity npc) {
        if (npc == null) return;

        try {
            untrackMannequin(npc.getReference());
        } catch (Exception ignored) {}
    }
}