package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Gives players back the dice the world lost: into the inventory, or at their feet when it is full. It runs for a
 * player the moment one is owed, and at their next login if they were away.
 */
public final class DiceReturnSystem extends EntityTickingSystem<EntityStore> {

    static final String RETURNED = "server.armoryDice.returned";

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        // Nearly always nothing is owed: one flag read instead of a walk over every player.
        if (!DiceModule.ledger().anyOwed()) {
            return;
        }
        super.tick(dt, systemIndex, store);
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
        if (player == null) {
            return;
        }
        List<String> items = DiceModule.ledger().takeOwed(player.getUuid());
        if (items.isEmpty()) {
            return;
        }
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        var inventory = InventoryComponent.getCombined(commandBuffer, ref, InventoryComponent.HOTBAR_STORAGE_BACKPACK);
        for (String item : items) {
            SimpleItemContainer.addOrDropItemStack(commandBuffer, ref, inventory, new ItemStack(item, 1));
        }
        player.sendMessage(Message.translation(RETURNED).param("count", items.size()));
    }
}
