package me.ladypaladra.thearmorymod.krafter;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;

public class MekanichalKrafterBlock implements Component<ChunkStore> {

    @Nonnull
    public static final BuilderCodec<MekanichalKrafterBlock> CODEC;

    public static final int DEFAULT_COOLDOWN_MILLIS = 10_000;

    private int remainingMillis = DEFAULT_COOLDOWN_MILLIS;
    private boolean ready = false;

    private transient int saveAccumulatorMillis = 0;

    public MekanichalKrafterBlock() {
    }

    public MekanichalKrafterBlock(int remainingMillis, boolean ready) {
        this.remainingMillis = remainingMillis;
        this.ready = ready;
    }

    public static ComponentType<ChunkStore, MekanichalKrafterBlock> getComponentType() {
        return MekanichalKrafterRegistry.getComponentType();
    }

    public void restartCooldown() {
        this.remainingMillis = DEFAULT_COOLDOWN_MILLIS;
        this.ready = false;
        this.saveAccumulatorMillis = 0;
    }

    public boolean tickCooldown(float dt) {
        if (ready) {
            return false;
        }

        int deltaMillis = Math.max(1, (int) (dt * 1000.0F));

        remainingMillis = Math.max(0, remainingMillis - deltaMillis);
        saveAccumulatorMillis += deltaMillis;

        if (remainingMillis <= 0) {
            ready = true;
            remainingMillis = 0;
            saveAccumulatorMillis = 0;
            return true;
        }

        if (saveAccumulatorMillis >= 1000) {
            saveAccumulatorMillis = 0;
            return true;
        }

        return false;
    }

    public boolean isReady() {
        return ready;
    }

    public int getRemainingMillis() {
        return remainingMillis;
    }

    public void setRemainingMillis(int remainingMillis) {
        this.remainingMillis = remainingMillis;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    @Nonnull
    @Override
    public Component<ChunkStore> clone() {
        return new MekanichalKrafterBlock(this.remainingMillis, this.ready);
    }

    static {
        CODEC = BuilderCodec.builder(MekanichalKrafterBlock.class, MekanichalKrafterBlock::new)
                .appendInherited(
                        new KeyedCodec<>("RemainingMillis", Codec.INTEGER),
                        (component, value) -> component.remainingMillis = value,
                        component -> component.remainingMillis,
                        (component, parent) -> component.remainingMillis = parent.remainingMillis
                )
                .add()
                .appendInherited(
                        new KeyedCodec<>("Ready", Codec.BOOLEAN),
                        (component, value) -> component.ready = value,
                        component -> component.ready,
                        (component, parent) -> component.ready = parent.ready
                )
                .add()
                .build();
    }
}