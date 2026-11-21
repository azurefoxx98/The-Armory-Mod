package me.ladypaladra.thearmorymod.armorstand;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Marks the mannequin NPC that an armour stand puts on display.
 *
 * <p>A figure is scenery. Nothing done to it should ever produce an item, because the armour it
 * appears to wear is a copy and the real stack stays in the block's container the whole time.
 * This marker is what {@link MannequinShieldSystem} looks for when it cancels damage.</p>
 *
 * <p>It does not close the duplication reported on 24 August, and describing it as the fix for
 * that report is wrong. The report says the figure was killed with magic, and that path writes
 * the health stat directly rather than raising a damage event. A damage filter can only cancel
 * damage that exists, so it never sees the kill at all. Keeping the marker is still correct,
 * because a prop that can be hurt is wrong on its own terms, but shielding a figure from direct
 * stat changes is a different problem and it is not solved here.</p>
 *
 * <p>This is not a source of truth for anything. The figure is excluded from the world save and
 * is rebuilt from the block's container, so this component never survives a restart. It carries
 * a codec only because registering a component requires one.</p>
 */
public final class MannequinShield implements Component<EntityStore> {

    private static ComponentType<EntityStore, MannequinShield> COMPONENT_TYPE;

    public static final BuilderCodec<MannequinShield> CODEC =
        BuilderCodec.builder(MannequinShield.class, MannequinShield::new)
            .append(new KeyedCodec<>("V", Codec.INTEGER),
                (c, v) -> c.version = v == null ? 1 : v,
                c -> c.version).add()
            .build();

    private int version = 1;

    public MannequinShield() {
    }

    public static void setComponentType(@Nonnull ComponentType<EntityStore, MannequinShield> type) {
        COMPONENT_TYPE = type;
    }

    public static ComponentType<EntityStore, MannequinShield> getComponentType() {
        return COMPONENT_TYPE;
    }

    @Override
    public Component<EntityStore> clone() {
        return new MannequinShield();
    }
}
