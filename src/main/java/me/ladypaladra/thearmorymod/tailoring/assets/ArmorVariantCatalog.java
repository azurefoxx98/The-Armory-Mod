package me.ladypaladra.thearmorymod.tailoring.assets;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ArmorVariantCatalog {

    private static final ArmorVariantCatalog EMPTY =
            new ArmorVariantCatalog(Collections.emptyMap());

    private final Map<String, ArmorVariantConfig> byItemId;

    private ArmorVariantCatalog(@Nonnull Map<String, ArmorVariantConfig> byItemId) {
        this.byItemId = byItemId;
    }

    @Nonnull
    public static ArmorVariantCatalog empty() {
        return EMPTY;
    }

    @Nonnull
    public static ArmorVariantCatalog build(@Nonnull Iterable<ArmorVariantConfig> configs) {
        Map<String, ArmorVariantConfig> byItemId = new HashMap<>();

        for (ArmorVariantConfig config : configs) {
            if (config == null || config.isUnknown()) {
                continue;
            }

            for (String itemId : config.getItemIds()) {
                String cleanItemId = clean(itemId);
                if (cleanItemId.isEmpty()) {
                    continue;
                }

                byItemId.put(cleanItemId, config);
            }
        }

        return new ArmorVariantCatalog(Collections.unmodifiableMap(byItemId));
    }

    @Nullable
    public ArmorVariantConfig findByItemId(@Nullable String itemId) {
        String cleanItemId = clean(itemId);
        if (cleanItemId.isEmpty()) {
            return null;
        }

        return byItemId.get(cleanItemId);
    }

    @Nonnull
    public List<String> findVariantGroup(@Nullable String currentItemId) {
        String cleanCurrentItemId = clean(currentItemId);
        if (cleanCurrentItemId.isEmpty()) {
            return Collections.emptyList();
        }

        ArmorVariantConfig config = findByItemId(cleanCurrentItemId);
        if (config == null || config.isUnknown()) {
            return Collections.emptyList();
        }

        Set<String> result = new LinkedHashSet<>();

        for (String itemIdRaw : config.getItemIds()) {
            String itemId = clean(itemIdRaw);

            if (!itemId.isEmpty()) {
                result.add(itemId);
            }
        }

        return new ArrayList<>(result);
    }

    public int size() {
        return byItemId.size();
    }

    @Nonnull
    private static String clean(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}