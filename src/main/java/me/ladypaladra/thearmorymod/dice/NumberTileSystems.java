package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Set;

/**
 * The big number's tiles: how they rise, face their viewer and fade, and who may see them.
 */
public final class NumberTileSystems {

    /**
     * The tiles' size: one tile is 18 by 22 units, so at this scale a digit stands about 0.4 block tall.
     */
    static final float SCALE = 1.2f;

    /**
     * Blocks between the middles of two digits, a tile's width and a little air.
     */
    static final double SPACING = 18.0 / 64.0 * SCALE * 1.08;

    /**
     * Blocks the number rises to above where it starts, and the eye height a viewer is faced at.
     */
    static final double RISE = 0.55;
    static final double EYE = 1.6;

    private NumberTileSystems() {
    }

    /**
     * Moves each tile: it pops up over a few ticks, floats, keeps facing its viewer with its digits in reading order,
     * then shrinks away, or for the Veil sinks.
     */
    public static final class Animate extends EntityTickingSystem<EntityStore> {

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Query.and(DiceModule.tileType(), TransformComponent.getComponentType());
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                         @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            NumberTile tile = chunk.getComponent(index, DiceModule.tileType());
            TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
            if (tile == null || transform == null) {
                return;
            }
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            Ref<EntityStore> viewer = store.getExternalData().getRefFromUUID(tile.viewer);
            TransformComponent eye = viewer != null && viewer.isValid()
                    ? commandBuffer.getComponent(viewer, TransformComponent.getComponentType()) : null;
            if (tile.age >= tile.life || eye == null) {
                commandBuffer.tryRemoveEntity(ref, RemoveReason.REMOVE);
                return;
            }
            tile.age++;
            tile.show.age = Math.max(tile.show.age, tile.age);

            Vector3d toViewer = new Vector3d(eye.getPosition()).add(0.0, EYE, 0.0).sub(tile.anchor);
            // The viewer's right, seen from the viewer: the line the digits are laid along, in reading order.
            double towards = Math.atan2(toViewer.x, toViewer.z);
            Vector3d right = new Vector3d(Math.cos(towards), 0.0, -Math.sin(towards));
            double across = (tile.place - (tile.places - 1) / 2.0) * SPACING;

            transform.getPosition().set(tile.anchor).add(0.0, height(tile), 0.0).fma(across, right);
            // An entity faces the way its yaw heads, and the game heads yaw 0 to -Z, so a tile whose carved face is
            // its model's +Z turns to the viewer with the heading of the direction to the viewer, half a turn from
            // the angle above. Seen in game: with the angle above, every tile showed its back.
            transform.getRotation().set(0.0f, (float) Math.atan2(-toViewer.x, -toViewer.z), 0.0f);

            float scale = scale(tile);
            if (Math.abs(scale - tile.shownScale) > 1.0E-3) {
                Model model = tileModel(tile.modelAsset, tile.texture, scale, tile.show.light(scale / SCALE));
                if (model != null) {
                    commandBuffer.putComponent(ref, ModelComponent.getComponentType(), new ModelComponent(model));
                }
                tile.shownScale = scale;
            }
        }
    }

    /**
     * Gives a showing number to players who come within reach after it rose: every few ticks, each number looks once
     * for players near it who have no tiles of it yet. A number about to fade is not offered.
     */
    public static final class Latecomers extends EntityTickingSystem<EntityStore> {

        static final int EVERY = 10;
        static final int TOO_LATE = 15;

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return DiceModule.tileType();
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                         @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            NumberTile tile = chunk.getComponent(index, DiceModule.tileType());
            if (tile == null) {
                return;
            }
            NumberShow show = tile.show;
            if (show.age % EVERY != 0 || show.checkedAt == show.age || show.life - show.age < TOO_LATE) {
                return;
            }
            show.checkedAt = show.age;
            for (Ref<EntityStore> player : DiceShow.playersNear(show.anchor, DiceShow.NUMBER_RANGE, commandBuffer)) {
                DiceShow.numberFor(show, player, commandBuffer);
            }
        }
    }

    /**
     * Shows each player their own tiles only.
     */
    public static final class OnlyItsViewer extends EntityTickingSystem<EntityStore> {

        private final Set<Dependency<EntityStore>> dependencies =
                Collections.singleton(new SystemDependency<>(Order.AFTER, EntityTrackerSystems.CollectVisible.class));

        @Nullable
        @Override
        public SystemGroup<EntityStore> getGroup() {
            return EntityTrackerSystems.FIND_VISIBLE_ENTITIES_GROUP;
        }

        @Nonnull
        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return dependencies;
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Query.and(EntityTrackerSystems.EntityViewer.getComponentType(), PlayerRef.getComponentType());
        }

        @Override
        public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
            // Most ticks no roll is showing: skip the walk over every viewer's visible set.
            if (store.getEntityCountFor(DiceModule.tileType()) == 0) {
                return;
            }
            super.tick(dt, systemIndex, store);
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                         @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            EntityTrackerSystems.EntityViewer viewer = chunk.getComponent(index, EntityTrackerSystems.EntityViewer.getComponentType());
            PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
            if (viewer == null || player == null) {
                return;
            }
            for (var iterator = viewer.visible.iterator(); iterator.hasNext(); ) {
                Ref<EntityStore> ref = iterator.next();
                if (!ref.isValid() || !commandBuffer.getArchetype(ref).contains(DiceModule.tileType())) {
                    continue;
                }
                NumberTile tile = commandBuffer.getComponent(ref, DiceModule.tileType());
                if (tile != null && !tile.viewer.equals(player.getUuid())) {
                    viewer.hiddenCount++;
                    iterator.remove();
                }
            }
        }
    }

    /**
     * A digit tile's model in a band's texture, casting the band's light. The tiles are never saved, so the texture
     * and light chosen here are never reloaded from a model reference, which would bring back the model's own.
     */
    @Nullable
    static Model tileModel(@Nonnull String modelAsset, @Nonnull String texture, float scale, @Nonnull ColorLight light) {
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(modelAsset);
        if (asset == null) {
            return null;
        }
        return DiceShow.retextured(Model.createScaledModel(asset, scale), texture, light);
    }

    private static double height(@Nonnull NumberTile tile) {
        double t = Math.min(1.0, tile.age / 8.0);
        // Rises with a small overshoot, then floats.
        double rise = RISE * (1.0 + 1.7 * Math.pow(t - 1.0, 3) + 0.7 * Math.pow(t - 1.0, 2));
        double bob = 0.035 * Math.sin(tile.age * 0.16);
        int left = tile.life - tile.age;
        if (tile.band == RollBand.VEIL && left < 24) {
            // The Veil's number sinks into the rift as it closes.
            rise -= (24 - left) / 24.0 * (RISE + 0.6);
        }
        return rise + bob;
    }

    private static float scale(@Nonnull NumberTile tile) {
        float s = SCALE;
        if (tile.age < 5) {
            s *= 0.35f + 0.65f * (tile.age / 5.0f);
        }
        int left = tile.life - tile.age;
        if (tile.band != RollBand.VEIL && left < 6) {
            s *= 0.25f + 0.75f * (left / 6.0f);
        }
        // Rounded so the model is only rebuilt while it is actually changing size.
        return Math.round(s * 50.0f) / 50.0f;
    }
}
