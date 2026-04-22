package me.ladypaladra.thearmorymod.parry.components;

import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.parry.ParrySettings;

import javax.annotation.Nonnull;

/**
 * Runtime state for a player's parry window.
 */
public class ParryComponent implements Component<EntityStore> {

    private boolean parrying;
    private boolean wasBlocking;
    private long blockStartTimeMs;
    private long lastBlockAttemptMs;
    private long lastSuccessfulParryTimeMs;

    public ParryComponent() {
        this.parrying = false;
        this.wasBlocking = false;
        this.blockStartTimeMs = 0L;
        this.lastBlockAttemptMs = 0L;
        this.lastSuccessfulParryTimeMs = 0L;
    }

    public boolean isParrying(long nowMs) {
        if (!parrying) {
            return false;
        }

        if (blockStartTimeMs <= 0L) {
            parrying = false;
            return false;
        }

        if (nowMs - blockStartTimeMs > ParrySettings.PARRY_WINDOW_MS) {
            parrying = false;
            return false;
        }

        return true;
    }

    public boolean isParryingRaw() {
        return parrying;
    }

    public void setParrying(boolean parrying) {
        this.parrying = parrying;
    }

    public boolean wasBlocking() {
        return wasBlocking;
    }

    public void setWasBlocking(boolean wasBlocking) {
        this.wasBlocking = wasBlocking;
    }

    public long getBlockStartTimeMs() {
        return blockStartTimeMs;
    }

    public void setBlockStartTimeMs(long blockStartTimeMs) {
        this.blockStartTimeMs = blockStartTimeMs;
    }

    public long getLastBlockAttemptMs() {
        return lastBlockAttemptMs;
    }

    public void setLastBlockAttemptMs(long lastBlockAttemptMs) {
        this.lastBlockAttemptMs = lastBlockAttemptMs;
    }

    public long getLastSuccessfulParryTimeMs() {
        return lastSuccessfulParryTimeMs;
    }

    public void setLastSuccessfulParryTimeMs(long lastSuccessfulParryTimeMs) {
        this.lastSuccessfulParryTimeMs = lastSuccessfulParryTimeMs;
    }

    public void clearParryWindow() {
        this.parrying = false;
        this.blockStartTimeMs = 0L;
    }

    @Nonnull
    @Override
    public ParryComponent clone() {
        ParryComponent copy = new ParryComponent();
        copy.parrying = this.parrying;
        copy.wasBlocking = this.wasBlocking;
        copy.blockStartTimeMs = this.blockStartTimeMs;
        copy.lastBlockAttemptMs = this.lastBlockAttemptMs;
        copy.lastSuccessfulParryTimeMs = this.lastSuccessfulParryTimeMs;
        return copy;
    }
}
