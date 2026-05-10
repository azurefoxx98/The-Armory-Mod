package me.ladypaladra.thearmorymod.tailoring.assets;

import com.hypixel.hytale.assetstore.AssetUpdateQuery;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ArmorVariantStore extends HytaleAssetStore<
        String,
        ArmorVariantConfig,
        IndexedLookupTableAssetMap<String, ArmorVariantConfig>> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String PATH = "TheArmoryMod/ArmorVariants";

    private static ArmorVariantStore instance;
    private static ArmorVariantCatalog catalog = ArmorVariantCatalog.empty();

    private final Map<String, ArmorVariantConfig> loadedConfigsById = new LinkedHashMap<>();

    private ArmorVariantStore(
            @Nonnull Builder<String, ArmorVariantConfig, IndexedLookupTableAssetMap<String, ArmorVariantConfig>> builder
    ) {
        super(builder);
    }

    @Nonnull
    public static ArmorVariantStore create() {
        var map = new IndexedLookupTableAssetMap<>(ArmorVariantConfig[]::new);
        var builder = HytaleAssetStore.builder(String.class, ArmorVariantConfig.class, map);

        builder.setPath(PATH)
                .setCodec(ArmorVariantConfig.CODEC)
                .setKeyFunction(ArmorVariantConfig::getId)
                .setIdProvider(ArmorVariantConfig.class)
                .setIsUnknown(ArmorVariantConfig::isUnknown);

        builder.setReplaceOnRemove(ArmorVariantStore::missing);

        ArmorVariantStore store = new ArmorVariantStore(builder);
        instance = store;
        return store;
    }

    @Nullable
    public static ArmorVariantStore getInstance() {
        return instance;
    }

    @Nonnull
    public static ArmorVariantCatalog getCatalog() {
        return catalog;
    }

    @Nonnull
    public static Iterable<ArmorVariantConfig> getLoadedConfigs() {
        ArmorVariantStore store = instance;
        if (store == null) {
            return Collections.emptyList();
        }

        return Collections.unmodifiableCollection(store.loadedConfigsById.values());
    }

    @Override
    protected void handleRemoveOrUpdate(
            @Nullable Set<String> removedKeys,
            @Nullable Map<String, ArmorVariantConfig> loadedOrUpdated,
            @Nonnull AssetUpdateQuery query
    ) {
        super.handleRemoveOrUpdate(removedKeys, loadedOrUpdated, query);

        if (removedKeys != null) {
            for (String removedKey : removedKeys) {
                if (removedKey != null) {
                    loadedConfigsById.remove(removedKey);
                }
            }
        }

        if (loadedOrUpdated != null) {
            for (ArmorVariantConfig config : loadedOrUpdated.values()) {
                if (config == null) {
                    continue;
                }

                loadedConfigsById.put(config.getId(), config);
                validateConfig(config);
            }
        }

        catalog = ArmorVariantCatalog.build(loadedConfigsById.values());

        LOGGER.atInfo().log(
                "Armor variants loaded=%d removed=%d total=%d catalogItems=%d",
                loadedOrUpdated == null ? 0 : loadedOrUpdated.size(),
                removedKeys == null ? 0 : removedKeys.size(),
                loadedConfigsById.size(),
                catalog.size()
        );
    }

    private static void validateConfig(@Nonnull ArmorVariantConfig config) {
        if (config.isUnknown()) {
            return;
        }

        for (String itemIdRaw : config.getItemIds()) {
            if (itemIdRaw == null || itemIdRaw.isBlank()) {
                continue;
            }

            String itemId = itemIdRaw.trim();
            Item item = Item.getAssetMap().getAsset(itemId);

            if (item == null || item == Item.UNKNOWN) {
                LOGGER.atWarning().log(
                        "Armor variant config %s references missing item %s",
                        config.getId(),
                        itemId
                );
                continue;
            }

            if (item.getArmor() == null) {
                LOGGER.atWarning().log(
                        "Armor variant config %s references non-armor item %s",
                        config.getId(),
                        itemId
                );
            }
        }
    }

    private static ArmorVariantConfig missing(String id) {
        ArmorVariantConfig config = new ArmorVariantConfig();
        config.id = id == null ? "" : id;
        config.unknown = true;
        config.itemIds = new String[0];
        return config;
    }
}