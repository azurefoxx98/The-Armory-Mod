package me.ladypaladra.thearmorymod.tailoring.assets;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetCodecMapCodec;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public final class ArmorVariantConfig
        implements JsonAssetWithMap<String, IndexedLookupTableAssetMap<String, ArmorVariantConfig>> {

    @Nonnull
    public static final AssetCodecMapCodec<String, ArmorVariantConfig> CODEC =
            new AssetCodecMapCodec<>(
                    Codec.STRING,
                    (config, key) -> config.id = key == null ? "" : key,
                    config -> config.id,
                    (config, data) -> config.data = data,
                    config -> config.data
            );

    static {
        CODEC.register("Default", ArmorVariantConfig.class, ArmorVariantConfigCodec.CODEC);
    }

    public String id = "";
    public AssetExtraInfo.Data data;
    public boolean unknown = false;
    public String[] itemIds = new String[0];

    public ArmorVariantConfig() {
    }

    @Nonnull
    public String getId() {
        return id == null ? "" : id;
    }

    public boolean isUnknown() {
        return unknown;
    }

    @Nonnull
    public String[] getItemIds() {
        return itemIds == null ? new String[0] : itemIds;
    }

    @Nonnull
    public Item[] resolveItems() {
        if (itemIds == null || itemIds.length == 0) {
            return new Item[0];
        }

        List<Item> resolved = new ArrayList<>(itemIds.length);

        for (String itemId : itemIds) {
            if (itemId == null || itemId.isBlank()) {
                continue;
            }

            Item item = Item.getAssetMap().getAsset(itemId.trim());
            if (item != null && item != Item.UNKNOWN) {
                resolved.add(item);
            }
        }

        return resolved.toArray(new Item[0]);
    }
}