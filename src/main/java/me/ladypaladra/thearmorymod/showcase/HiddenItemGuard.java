package me.ladypaladra.thearmorymod.showcase;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Takes the showcase world's own blocks back out of any player's inventory.
 *
 * <p>Those blocks build the showcase and are never meant to be held. Their quality keeps them out
 * of the creative library and its search, but a creative player can still pick a block straight out
 * of the world, and the server grants any item a creative client asks for. So any stack of that
 * quality that reaches a player is removed as soon as it lands.</p>
 */
public class HiddenItemGuard extends EntityEventSystem<EntityStore, InventoryChangeEvent> {

    public static final String HIDDEN_QUALITY = "Armory_Showcase_Hidden";

    public HiddenItemGuard() {
        super(InventoryChangeEvent.class);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull InventoryChangeEvent event
    ) {
        ItemContainer container = event.getItemContainer();

        for (short slot = 0; slot < container.getCapacity(); slot++) {
            ItemStack stack = container.getItemStack(slot);

            if (!ItemStack.isEmpty(stack) && isHidden(stack.getItem())) {
                container.removeItemStackFromSlot(slot);
            }
        }
    }

    public static boolean isHidden(Item item) {
        if (item == null) {
            return false;
        }

        ItemQuality quality = ItemQuality.getAssetMap().getAsset(item.getQualityIndex());
        return quality != null && HIDDEN_QUALITY.equals(quality.getId());
    }
}
