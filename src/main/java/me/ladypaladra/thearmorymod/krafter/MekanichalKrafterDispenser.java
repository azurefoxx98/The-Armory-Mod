package me.ladypaladra.thearmorymod.krafter;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;

/**
 * A krafter that builds a block on top of itself instead of handing an item to a player.
 * Every interval it checks the cell above its top, plays its Dispense state when that cell is free,
 * and places the block once the clip has played.
 */
public class MekanichalKrafterDispenser implements Component<ChunkStore> {

    @Nonnull
    public static final BuilderCodec<MekanichalKrafterDispenser> CODEC;

    public static final int INTERVAL_MILLIS = 60_000;

    // Dispense.blockyanim is 145 frames at 60 per second. The block appears where the animated barrel comes to rest.
    public static final int DISPENSE_MILLIS = 2_417;

    public static final String DISPENSED_BLOCK_ID = "Throwable_Explosive_Barrel";

    public static final String STATE_DISPENSE = "Dispense";

    public enum Step {
        NONE,
        DUE,
        FINISHED
    }

    private int remainingMillis = INTERVAL_MILLIS;
    private int dispenseMillis = 0;

    private transient int saveAccumulatorMillis = 0;

    public MekanichalKrafterDispenser() {
    }

    public MekanichalKrafterDispenser(int remainingMillis, int dispenseMillis) {
        this.remainingMillis = remainingMillis;
        this.dispenseMillis = dispenseMillis;
    }

    public static ComponentType<ChunkStore, MekanichalKrafterDispenser> getComponentType() {
        return MekanichalKrafterRegistry.getDispenserComponentType();
    }

    /**
     * Moves the clock on. DUE means the interval ran out and a new one has started, so the caller decides
     * whether to dispense. FINISHED means a running dispense has played to its end.
     */
    @Nonnull
    public Step advance(int deltaMillis) {
        saveAccumulatorMillis += deltaMillis;

        if (dispenseMillis > 0) {
            dispenseMillis = Math.max(0, dispenseMillis - deltaMillis);
            return dispenseMillis == 0 ? Step.FINISHED : Step.NONE;
        }

        remainingMillis -= deltaMillis;
        if (remainingMillis > 0) {
            return Step.NONE;
        }

        remainingMillis = INTERVAL_MILLIS;
        return Step.DUE;
    }

    public void beginDispense() {
        dispenseMillis = DISPENSE_MILLIS;
    }

    public boolean isDispensing() {
        return dispenseMillis > 0;
    }

    /**
     * True once a second of ticking has passed since the last save, so the clock survives a restart
     * without marking the block dirty every tick.
     */
    public boolean takeSaveDue() {
        if (saveAccumulatorMillis < 1000) {
            return false;
        }

        saveAccumulatorMillis = 0;
        return true;
    }

    public int getRemainingMillis() {
        return remainingMillis;
    }

    public int getDispenseMillis() {
        return dispenseMillis;
    }

    @Nonnull
    @Override
    public Component<ChunkStore> clone() {
        return new MekanichalKrafterDispenser(this.remainingMillis, this.dispenseMillis);
    }

    static {
        CODEC = BuilderCodec.builder(MekanichalKrafterDispenser.class, MekanichalKrafterDispenser::new)
                .appendInherited(
                        new KeyedCodec<>("RemainingMillis", Codec.INTEGER),
                        (component, value) -> component.remainingMillis = value,
                        component -> component.remainingMillis,
                        (component, parent) -> component.remainingMillis = parent.remainingMillis
                )
                .add()
                .appendInherited(
                        new KeyedCodec<>("DispenseMillis", Codec.INTEGER),
                        (component, value) -> component.dispenseMillis = value,
                        component -> component.dispenseMillis,
                        (component, parent) -> component.dispenseMillis = parent.dispenseMillis
                )
                .add()
                .build();
    }
}
