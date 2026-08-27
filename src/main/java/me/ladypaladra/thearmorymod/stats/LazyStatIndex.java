package me.ladypaladra.thearmorymod.stats;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;

public final class LazyStatIndex {

    private final String statId;
    private volatile int resolvedIndex = AssetMapWithIndexes.NOT_FOUND;

    public LazyStatIndex(String statId) {
        this.statId = statId;
    }

    public int get() {
        int index = resolvedIndex;
        if (index != AssetMapWithIndexes.NOT_FOUND) {
            return index;
        }

        int found = EntityStatType.getAssetMap().getIndex(statId);
        if (found != AssetMapWithIndexes.NOT_FOUND) {
            resolvedIndex = found;
            return found;
        }

        // The named engine sentinel keeps a future value change visible to the compiler and reader.
        return AssetMapWithIndexes.NOT_FOUND;
    }
}
