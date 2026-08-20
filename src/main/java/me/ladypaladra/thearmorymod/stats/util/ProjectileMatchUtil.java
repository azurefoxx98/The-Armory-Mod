package me.ladypaladra.thearmorymod.stats.util;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

public final class ProjectileMatchUtil {

    private ProjectileMatchUtil() {
    }

    public static boolean matchesProjectileAsset(Store<EntityStore> store,
                                                 CommandBuffer<EntityStore> commandBuffer,
                                                 Ref<EntityStore> projectileRef,
                                                 List<String> wildcardPatterns) {
        String assetId = getProjectileAssetId(store, commandBuffer, projectileRef);
        return matchesAnyIgnoreCase(assetId, wildcardPatterns);
    }

    public static boolean matchesAnyIgnoreCase(@Nullable String value, List<String> wildcardPatterns) {
        if (value == null || value.isBlank()) {
            return false;
        }

        for (String pattern : wildcardPatterns) {
            if (matchesWildcardIgnoreCase(value, pattern)) {
                return true;
            }
        }

        return false;
    }

    @Nullable
    private static String getProjectileAssetId(Store<EntityStore> store,
                                               CommandBuffer<EntityStore> commandBuffer,
                                               Ref<EntityStore> projectileRef) {
        ProjectileComponent projectileComponent = commandBuffer != null
                ? commandBuffer.getComponent(projectileRef, ProjectileComponent.getComponentType())
                : null;

        if (projectileComponent == null) {
            projectileComponent = store.getComponent(projectileRef, ProjectileComponent.getComponentType());
        }

        if (projectileComponent == null) {
            return null;
        }

        return projectileComponent.getProjectileAssetName();
    }

    private static boolean matchesWildcardIgnoreCase(String text, String wildcardPattern) {
        if (wildcardPattern == null || wildcardPattern.isBlank()) {
            return false;
        }

        String value = text.toLowerCase(Locale.ROOT);
        String pattern = wildcardPattern.toLowerCase(Locale.ROOT);

        if ("*".equals(pattern)) {
            return true;
        }

        String[] parts = pattern.split("\\*", -1);
        int index = 0;
        boolean anchoredStart = !pattern.startsWith("*");
        boolean anchoredEnd = !pattern.endsWith("*");

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }

            int foundAt = value.indexOf(part, index);
            if (foundAt < 0) {
                return false;
            }

            if (i == 0 && anchoredStart && foundAt != 0) {
                return false;
            }

            index = foundAt + part.length();
        }

        if (anchoredEnd) {
            String lastNonEmpty = null;
            for (int i = parts.length - 1; i >= 0; i--) {
                if (!parts[i].isEmpty()) {
                    lastNonEmpty = parts[i];
                    break;
                }
            }

            return lastNonEmpty == null || value.endsWith(lastNonEmpty);
        }

        return true;
    }
}
