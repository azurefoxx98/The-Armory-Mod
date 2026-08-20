package me.ladypaladra.thearmorymod.armorstand.blockstate;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.util.UUID;

/**
 * ArmorStandComponent persists the mannequin NPC UUID for an Armor Stand block.
 * Stored as a block component on the ChunkStore.
 */
public class ArmorStandComponent implements Component<ChunkStore> {

    private static ComponentType<ChunkStore, ArmorStandComponent> COMPONENT_TYPE;

    @SuppressWarnings("unchecked")
    public static final BuilderCodec<ArmorStandComponent> CODEC =
            ((BuilderCodec.Builder<ArmorStandComponent>)
                    BuilderCodec.builder(ArmorStandComponent.class, ArmorStandComponent::new)
                            .append(
                                    new KeyedCodec<>("MannequinUUID", Codec.UUID_STRING),
                                    (comp, uuid) -> comp.mannequinUuid = uuid,
                                    comp -> comp.mannequinUuid
                            ).add()
            ).build();

    private UUID mannequinUuid = null;

    public ArmorStandComponent() {}

    public ArmorStandComponent(ArmorStandComponent other) {
        this.mannequinUuid = other.mannequinUuid;
    }

    public UUID getMannequinUuid() {
        return mannequinUuid;
    }

    public void setMannequinUuid(UUID uuid) {
        this.mannequinUuid = uuid;
    }

    public static ComponentType<ChunkStore, ArmorStandComponent> getComponentType() {
        return COMPONENT_TYPE;
    }

    public static void setComponentType(ComponentType<ChunkStore, ArmorStandComponent> type) {
        COMPONENT_TYPE = type;
    }

    @Override
    public Component<ChunkStore> clone() {
        return new ArmorStandComponent(this);
    }
}