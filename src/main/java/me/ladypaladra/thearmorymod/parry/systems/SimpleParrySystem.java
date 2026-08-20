package me.ladypaladra.thearmorymod.parry.systems;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.damage.DamageDataComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import me.ladypaladra.thearmorymod.parry.ParrySettings;
import me.ladypaladra.thearmorymod.parry.components.ParryComponent;
import me.ladypaladra.thearmorymod.parry.util.EntityUtil;
import me.ladypaladra.thearmorymod.parry.util.ParryUtil;
import me.ladypaladra.thearmorymod.parry.util.StunUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static me.ladypaladra.thearmorymod.parry.systems.BlockTrackingSystem.hasParryTag;

public class SimpleParrySystem extends DamageEventSystem {

    private static final String PARRY_SUCCESS_SOUND_ID = "TA_Parry_Success";

    private final ComponentType<EntityStore, ParryComponent> parryComponentType;

    public SimpleParrySystem(ComponentType<EntityStore, ParryComponent> parryComponentType) {
        this.parryComponentType = parryComponentType;
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(int index,
                       @Nonnull ArchetypeChunk<EntityStore> chunk,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer,
                       @Nonnull Damage damage) {
        if (damage.isCancelled()) return;

        Ref<EntityStore> defenderRef = chunk.getReferenceTo(index);
        Damage.Source source = damage.getSource();
        if (!(source instanceof Damage.EntitySource entitySource)) return;

        Ref<EntityStore> attackerRef = entitySource.getRef();
        if (!attackerRef.isValid()) return;

        ParryComponent parryComponent = chunk.getComponent(index, parryComponentType);
        if (parryComponent == null) return;

        DamageDataComponent defenderDamageData = chunk.getComponent(index, DamageDataComponent.getComponentType());
        boolean blockingNow = defenderDamageData != null && defenderDamageData.getCurrentWielding() != null;

        ItemStack heldItem = EntityUtil.getItemInHand(chunk, index);
        boolean allowedWeapon = hasParryTag(heldItem);

        if (!allowedWeapon) return;

        if (ParrySettings.REQUIRE_BLOCKING && !blockingNow) return;

        TimeResource timeResource = store.getResource(TimeResource.getResourceType());
        long nowMs = timeResource.getNow().toEpochMilli();
        boolean parryingNow = parryComponent.isParrying(nowMs);
        if (!parryingNow) return;

        TransformComponent defenderTransform = chunk.getComponent(index, TransformComponent.getComponentType());
        if (defenderTransform == null) return;

        ParryUtil.cancelParryDamage(damage);
        parryComponent.setLastSuccessfulParryTimeMs(nowMs);
        parryComponent.clearParryWindow();
        parryComponent.setWasBlocking(blockingNow);

        int parrySoundIndex = SoundEvent.getAssetMap().getIndex(PARRY_SUCCESS_SOUND_ID);
        if (parrySoundIndex >= 0) {
            SoundUtil.playSoundEvent2d(defenderRef, parrySoundIndex, SoundCategory.SFX, store);
        }

        StunUtil.applyStun(attackerRef, store, commandBuffer, ParrySettings.STUN_DURATION_SECONDS);
    }
}
