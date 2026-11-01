package me.ladypaladra.thearmorymod.powers;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.modules.projectile.component.ImpactModifiers;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the root interactions a player's worn armour names when that player lands from a fall.
 *
 * <p>The game has no interaction type for landing, so an armour piece cannot say "do this when I
 * land" on its own. This system is that missing moment and nothing more: the effect itself is an
 * ordinary root interaction, built from vanilla interactions, so it can be made and changed in the
 * asset editor. Which pieces carry it and when it fires are decided by {@link LandingTrigger}.</p>
 *
 * <p>Player movement is decided by the client, so the server learns of a landing only from the
 * movement states queued in {@link PlayerInput}. This reads that queue the way the engine's own
 * fall damage does, and must run before it, because the fall damage system clears the fall
 * distance once it has used it. Reading the queue never changes it.</p>
 *
 * <p>Each chain starts the way the engine starts a stat's own effects: as an
 * {@link InteractionType#EntityStatEffect} chain, queued on the player's interaction manager. That
 * type is blocked by nothing and has no default cooldown, so a landing never cancels an attack in
 * progress. Its recharge is the effect's own setting, see {@link LandingTrigger}. The effect on the wearer is synced to
 * the client, which draws it. The hit on each creature runs on the server alone, with the creature
 * as its target, because the engine's own selector would wait for the client to report its hits.
 * Damage dealt that way names the wearer as its source, so the world's PvP setting and every other
 * damage rule applies to it.</p>
 *
 * <p>A landing fires once. A second landing message without the player having left the ground
 * between them, which happens while a teleport is waiting for the client, is ignored.</p>
 */
public final class LandingTriggerSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final Set<UUID> airborne = ConcurrentHashMap.newKeySet();

    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    private final Map<String, Long> lastFiredMs = new ConcurrentHashMap<>();

    private final Query<EntityStore> query = Archetype.of(
            PlayerRef.getComponentType(),
            Player.getComponentType(),
            PlayerInput.getComponentType(),
            InventoryComponent.Armor.getComponentType(),
            InteractionModule.get().getInteractionManagerComponent()
    );

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.BEFORE, DamageSystems.FallDamagePlayers.class),
            new SystemDependency<>(Order.BEFORE, PlayerSystems.ProcessPlayerInput.class)
    );

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<EntityStore> chunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        PlayerInput input = chunk.getComponent(index, PlayerInput.getComponentType());
        PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
        if (input == null || playerRef == null) {
            return;
        }

        List<PlayerInput.InputUpdate> queue = input.getMovementUpdateQueue();
        if (queue.isEmpty()) {
            return;
        }

        UUID uuid = playerRef.getUuid();
        MovementStates landing = null;
        for (int i = 0; i < queue.size(); i++) {
            if (!(queue.get(i) instanceof PlayerInput.SetMovementStates entry) || entry.movementStates() == null) {
                continue;
            }
            if (!entry.movementStates().onGround) {
                airborne.add(uuid);
            } else if (airborne.remove(uuid) && landing == null) {
                landing = entry.movementStates();
            }
        }

        if (landing == null || landing.inFluid || chunk.getArchetype().contains(DeathComponent.getComponentType())) {
            return;
        }

        List<String[]> worn = WornTags.wornValues(chunk.getComponent(index, InventoryComponent.Armor.getComponentType()), WornTags.ON_LANDING);
        if (worn.isEmpty()) {
            return;
        }

        Player player = chunk.getComponent(index, Player.getComponentType());
        InteractionManager manager = chunk.getComponent(index, InteractionModule.get().getInteractionManagerComponent());
        if (player == null || manager == null) {
            return;
        }

        double fallDistance = player.getCurrentFallDistance();
        Map<String, Integer> counts = LandingTrigger.countPieces(worn);
        Ref<EntityStore> ref = chunk.getReferenceTo(index);

        for (Map.Entry<String, LandingTrigger.Settings> effect : LandingTrigger.firing(counts, this::settings, fallDistance).entrySet()) {
            RootInteraction root = RootInteraction.getAssetMap().getAsset(effect.getKey());
            if (root == null) {
                continue;
            }

            LandingTrigger.Settings settings = effect.getValue();
            String rechargeKey = uuid + ":" + effect.getKey();
            long now = System.currentTimeMillis();
            if (!settings.recharged(lastFiredMs.get(rechargeKey), now)) {
                continue;
            }
            lastFiredMs.put(rechargeKey, now);

            ImpactModifiers fall = new ImpactModifiers(Map.of(LandingTrigger.FALL_ATTRIBUTE, settings.scaleFor(fallDistance)), null);

            // What the wearer and those nearby see and hear. Synced to the client, which draws it.
            start(manager, ref, root, fall, null, true, commandBuffer);

            RootInteraction hit = hitRoot(effect.getKey(), settings);
            if (hit == null) {
                continue;
            }
            for (Ref<EntityStore> target : targets(ref, settings.radius(), commandBuffer)) {
                // Damage needs nothing from the client, so these run on the server alone.
                start(manager, ref, hit, fall, target, false, commandBuffer);
            }
        }
    }

    /**
     * Starts a chain the way the engine starts a stat's own effects. The ability attributes ride on
     * the context, where forked chains copy them and a chain outside the ability slots is never
     * snapshotted over by the engine.
     */
    private static void start(
            @Nonnull InteractionManager manager,
            @Nonnull Ref<EntityStore> wearer,
            @Nonnull RootInteraction root,
            @Nonnull ImpactModifiers fall,
            @Nullable Ref<EntityStore> target,
            boolean client,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        InteractionContext context = InteractionContext.forInteraction(manager, wearer, InteractionType.EntityStatEffect, commandBuffer);
        context.getMetaStore().putMetaObject(ImpactModifiers.CAST_SNAPSHOT, fall);
        if (target != null) {
            context.getMetaStore().putMetaObject(Interaction.TARGET_ENTITY, target);
        }
        InteractionChain chain = manager.initChain(InteractionType.EntityStatEffect, context, root, client);
        manager.queueExecuteChain(chain);
    }

    /**
     * Every living creature and player within the radius of the wearer's feet, the wearer left out,
     * found the way the engine's own selectors search: the player and entity spatial indexes.
     */
    @Nonnull
    private static List<Ref<EntityStore>> targets(
            @Nonnull Ref<EntityStore> wearer,
            double radius,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        TransformComponent transform = commandBuffer.getComponent(wearer, TransformComponent.getComponentType());
        if (transform == null) {
            return List.of();
        }

        List<Ref<EntityStore>> found = SpatialResource.getThreadLocalReferenceList();
        commandBuffer.getResource(EntityModule.get().getPlayerSpatialResourceType())
                .getSpatialStructure().collect(transform.getPosition(), radius, found);
        commandBuffer.getResource(EntityModule.get().getEntitySpatialResourceType())
                .getSpatialStructure().collect(transform.getPosition(), radius, found);

        // The list is the engine's shared scratch list, so the result is copied out of it.
        List<Ref<EntityStore>> targets = new ArrayList<>();
        for (Ref<EntityStore> ref : found) {
            if (ref == null || !ref.isValid() || ref.equals(wearer) || targets.contains(ref)) {
                continue;
            }
            if (commandBuffer.getComponent(ref, EntityStatMap.getComponentType()) == null
                    || commandBuffer.getArchetype(ref).contains(DeathComponent.getComponentType())) {
                continue;
            }
            targets.add(ref);
        }
        return targets;
    }

    @Nullable
    private RootInteraction hitRoot(@Nonnull String rootId, @Nonnull LandingTrigger.Settings settings) {
        if (settings.hit() == null) {
            return null;
        }
        RootInteraction hit = RootInteraction.getAssetMap().getAsset(settings.hit());
        if (hit == null && reported.add(rootId + ":" + settings.hit())) {
            LOGGER.atWarning().log("Landing interaction %s names %s %s, which does not exist. It hits nothing.", rootId, LandingTrigger.HIT_TAG, settings.hit());
        }
        return hit;
    }

    @Nullable
    private LandingTrigger.Settings settings(@Nonnull String rootId) {
        RootInteraction root = RootInteraction.getAssetMap().getAsset(rootId);
        if (root == null) {
            if (reported.add(rootId)) {
                LOGGER.atWarning().log("An armour piece names landing interaction %s, which does not exist. Nothing runs when its wearer lands.", rootId);
            }
            return null;
        }

        Map<String, String[]> tags = root.getData() != null ? root.getData().getRawTags() : null;
        return LandingTrigger.Settings.read(tags, problem -> {
            if (reported.add(rootId + ":" + problem)) {
                LOGGER.atWarning().log("Landing interaction %s: %s. Its default is used instead.", rootId, problem);
            }
        });
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        // Queuing a chain on the interaction manager is not thread safe, so this runs on the world thread.
        return false;
    }
}
