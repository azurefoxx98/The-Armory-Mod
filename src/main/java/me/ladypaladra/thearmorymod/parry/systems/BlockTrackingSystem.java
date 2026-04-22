package me.ladypaladra.thearmorymod.parry.systems;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.damage.DamageDataComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.WieldingInteraction;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.parry.ParrySettings;
import me.ladypaladra.thearmorymod.parry.components.ParryComponent;
import me.ladypaladra.thearmorymod.parry.util.EntityUtil;

import javax.annotation.Nonnull;
import java.util.Map;

public class BlockTrackingSystem extends EntityTickingSystem<EntityStore> {

    private static final String PARRY_TAG = "ArmoryParry";
    private final ComponentType<EntityStore, ParryComponent> parryComponentType;

    public BlockTrackingSystem(ComponentType<EntityStore, ParryComponent> parryComponentType) {
        this.parryComponentType = parryComponentType;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return parryComponentType;
    }

    @Override
    public void tick(float dt,
                     int index,
                     @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        DamageDataComponent damageData = chunk.getComponent(index, DamageDataComponent.getComponentType());
        ParryComponent parryComponent = chunk.getComponent(index, parryComponentType);
        if (damageData == null || parryComponent == null) return;

        TimeResource timeResource = store.getResource(TimeResource.getResourceType());
        long nowMs = timeResource.getNow().toEpochMilli();

        WieldingInteraction currentWielding = damageData.getCurrentWielding();
        boolean blockingNow = currentWielding != null;

        ItemStack heldItem = EntityUtil.getItemInHand(chunk, index);
        boolean allowedWeapon = hasParryTag(heldItem);
        boolean blockingWithParryWeapon = blockingNow && allowedWeapon;

        if (blockingWithParryWeapon != parryComponent.wasBlocking()) {
            if (blockingWithParryWeapon) {
                long lastAttempt = parryComponent.getLastBlockAttemptMs();
                long elapsedSinceLastAttempt = nowMs - lastAttempt;

                if (elapsedSinceLastAttempt >= ParrySettings.BLOCK_SPAM_COOLDOWN_MS) {
                    parryComponent.setBlockStartTimeMs(nowMs);
                    parryComponent.setParrying(true);
                } else {
                    parryComponent.clearParryWindow();
                }

                parryComponent.setLastBlockAttemptMs(nowMs);
            } else {
                parryComponent.clearParryWindow();
            }

            parryComponent.setWasBlocking(blockingWithParryWeapon);
        } else if (!blockingWithParryWeapon && parryComponent.isParryingRaw()) {
            parryComponent.clearParryWindow();
        }
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }

    public static boolean hasParryTag(ItemStack itemStack) {
        if (itemStack == null) return false;

        Item item = itemStack.getItem();

        AssetExtraInfo.Data data = item.getData();
        if (data == null) return false;

        Map<String, String[]> rawTags = data.getRawTags();
        return rawTags.containsKey(PARRY_TAG);
    }
}