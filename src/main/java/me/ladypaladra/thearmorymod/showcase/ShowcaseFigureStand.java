package me.ladypaladra.thearmorymod.showcase;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A display stand in the showcase world. It names what its figure wears and holds, and the
 * figure is built from those names. They are names rather than items on purpose: the stand holds
 * nothing a player could take out, and breaking it can never drop armour.
 *
 * <p>The figure itself is never saved. It is rebuilt from the stand whenever the stand's chunk
 * loads, so only the names below are written to the world.</p>
 */
public class ShowcaseFigureStand implements Component<ChunkStore> {

    @Nonnull
    public static final BuilderCodec<ShowcaseFigureStand> CODEC;

    private static ComponentType<ChunkStore, ShowcaseFigureStand> componentType;

    private String head;
    private String chest;
    private String hands;
    private String legs;
    private String mainHand;
    private String offHand;

    // Working state for the figure on display, never saved.
    transient Ref<EntityStore> figure;
    transient boolean spawnPending;
    transient int spawnGeneration;
    transient boolean removed;
    transient float retryDelaySeconds;

    public ShowcaseFigureStand() {
    }

    public ShowcaseFigureStand(@Nonnull ShowcaseFigureStand other) {
        this.head = other.head;
        this.chest = other.chest;
        this.hands = other.hands;
        this.legs = other.legs;
        this.mainHand = other.mainHand;
        this.offHand = other.offHand;
    }

    public static void setComponentType(@Nonnull ComponentType<ChunkStore, ShowcaseFigureStand> type) {
        componentType = type;
    }

    @Nonnull
    public static ComponentType<ChunkStore, ShowcaseFigureStand> getComponentType() {
        if (componentType == null) {
            throw new IllegalStateException("ShowcaseFigureStand component type has not been registered yet.");
        }

        return componentType;
    }

    /**
     * The armour item ids in the order of the engine's armour slots: head, chest, hands, legs.
     */
    @Nonnull
    public String[] getArmor() {
        return new String[]{head, chest, hands, legs};
    }

    @Nullable
    public String getMainHand() {
        return mainHand;
    }

    @Nullable
    public String getOffHand() {
        return offHand;
    }

    public boolean isEmpty() {
        for (String id : getArmor()) {
            if (id != null && !id.isEmpty()) {
                return false;
            }
        }

        return (mainHand == null || mainHand.isEmpty()) && (offHand == null || offHand.isEmpty());
    }

    @Nonnull
    @Override
    public Component<ChunkStore> clone() {
        return new ShowcaseFigureStand(this);
    }

    private static KeyedCodec<String> key(String name) {
        return new KeyedCodec<>(name, Codec.STRING);
    }

    static {
        CODEC = BuilderCodec.builder(ShowcaseFigureStand.class, ShowcaseFigureStand::new)
                .append(key("Head"), (stand, id) -> stand.head = id, stand -> stand.head).add()
                .append(key("Chest"), (stand, id) -> stand.chest = id, stand -> stand.chest).add()
                .append(key("Hands"), (stand, id) -> stand.hands = id, stand -> stand.hands).add()
                .append(key("Legs"), (stand, id) -> stand.legs = id, stand -> stand.legs).add()
                .append(key("MainHand"), (stand, id) -> stand.mainHand = id, stand -> stand.mainHand).add()
                .append(key("OffHand"), (stand, id) -> stand.offHand = id, stand -> stand.offHand).add()
                .build();
    }
}
