package me.ladypaladra.thearmorymod.parry.util;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;

public final class EntityUtil {

    private EntityUtil() {
    }

    @Nullable
    public static ItemStack getItemInHand(ArchetypeChunk<EntityStore> chunk, int index) {
        InventoryComponent.Tool tool = chunk.getComponent(index, InventoryComponent.Tool.getComponentType());
        if (tool != null && tool.isUsingToolsItem()) {
            return tool.getActiveItem();
        }

        InventoryComponent.Hotbar hotbar = chunk.getComponent(index, InventoryComponent.Hotbar.getComponentType());
        return hotbar != null ? hotbar.getActiveItem() : null;
    }
}
