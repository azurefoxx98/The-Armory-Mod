package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.BlockMaterial;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionChainData;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.event.InteractionChainStartEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import me.ladypaladra.thearmorymod.blocks.BlockAccess;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Lets F take a die lying in grass, flowers or any other block a player can walk through. The window aims F at the
 * first thing its crosshair meets, and in tall grass that is a plant, in the die's cell or one in front of it, so the
 * press lands on the plant and the die's own pickup never runs. On such a block the game's F ends in a harvest step
 * that fires no event, so this listens for the press itself as it starts. A press that met no creature and stopped on
 * a block that is not solid takes the die it was aimed at: the nearest die lying close to the line from the eye through
 * the point the crosshair hit, a little past that point, with nothing solid between that point and the die. The press
 * is then cancelled, so the plant is not also harvested.
 *
 * <p>A die behind a wall is never taken: the wall is solid and lies between.</p>
 */
public final class PickUpThroughPlants extends EntityEventSystem<EntityStore, InteractionChainStartEvent> {

    /**
     * How far past the point the crosshair hit a die may lie: a few plants deep, as far as a player sees into grass.
     */
    static final double BEYOND = 2.5;

    /**
     * How near the eye a die may lie along the line: not in the player's own feet.
     */
    static final double NEAREST = 0.5;

    /**
     * How far from the line of sight a die's middle may lie and still be the one aimed at: about a die's width, so a
     * crosshair on the die's edge takes it.
     */
    static final double REACH_ASIDE = 0.45;

    /**
     * How far outside the struck plant's cell a die's middle may lie and still count as in it: a die resting across a
     * cell edge, half in the plant's cell, whatever the line.
     */
    static final double MARGIN = 0.3;

    /**
     * Height of a resting die's middle above its position, which is the ground under it.
     */
    static final double MIDDLE = 0.2;

    /**
     * The step along the line between the struck point and a die at which cells are read for a solid one.
     */
    static final double STEP = 0.1;

    private final Query<EntityStore> query = Archetype.of(PlayerRef.getComponentType());

    public PickUpThroughPlants() {
        super(InteractionChainStartEvent.class);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull InteractionChainStartEvent event) {
        if (event.isCancelled() || event.getType() != InteractionType.Use) {
            return;
        }
        InteractionChainData data = event.getChain().getChainData();
        if (data == null || data.entityId >= 0 || data.blockPosition == null) {
            return;
        }
        World world = commandBuffer.getExternalData().getWorld();
        BlockPosition cell = data.blockPosition;
        if (solid(world, cell.x, cell.y, cell.z)) {
            return;
        }
        Ref<EntityStore> player = chunk.getReferenceTo(index);
        Transform look = TargetUtil.getLook(player, commandBuffer);
        Vector3d eye = new Vector3d(look.getPosition());
        var struck = data.hitLocation;
        Vector3d hit = struck != null ? new Vector3d(struck.x(), struck.y(), struck.z())
                : new Vector3d(cell.x + 0.5, cell.y + 0.5, cell.z + 0.5);

        List<Ref<EntityStore>> refs = new ArrayList<>();
        List<Vector3d> middles = new ArrayList<>();
        for (Ref<EntityStore> ref : LandedDieSystems.loaded()) {
            if (!ref.isValid() || ref.getStore() != store) {
                continue;
            }
            TransformComponent at = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
            if (at != null) {
                refs.add(ref);
                middles.add(new Vector3d(at.getPosition()).add(0.0, MIDDLE, 0.0));
            }
        }
        int aimed = aimedAt(eye, hit, cell, middles, (x, y, z) -> solid(world, x, y, z));
        if (aimed >= 0 && PickUpDieInteraction.take(player, refs.get(aimed), commandBuffer)) {
            event.setCancelled(true);
        }
    }

    /**
     * Which of the dice (their middles) a press from {@code eye} that struck {@code hit}, on the non-solid block
     * {@code cell}, was aimed at: one in the struck cell, or the nearest past the struck point along the line of sight,
     * close to it, with no solid cell between. Minus one for none.
     */
    static int aimedAt(@Nonnull Vector3d eye, @Nonnull Vector3d hit, @Nonnull BlockPosition cell,
                       @Nonnull List<Vector3d> middles, @Nonnull Cells solid) {
        Vector3d dir = new Vector3d(hit).sub(eye);
        double toHit = dir.length();
        if (toHit < 1.0E-6) {
            return -1;
        }
        dir.div(toHit);
        int best = -1;
        double bestAlong = Double.MAX_VALUE;
        for (int i = 0; i < middles.size(); i++) {
            Vector3d m = middles.get(i);
            double along;
            if (inCell(m, cell)) {
                along = toHit;
            } else {
                along = new Vector3d(m).sub(eye).dot(dir);
                // A die just under the line before the struck plant counts too: aimed a little high, the crosshair
                // passes over a die low in the grass and meets a plant behind it.
                if (along < NEAREST || along > toHit + BEYOND) {
                    continue;
                }
                Vector3d closest = new Vector3d(dir).mul(along).add(eye);
                if (closest.distance(m) > REACH_ASIDE || !clear(hit, m, solid)) {
                    continue;
                }
            }
            if (along < bestAlong) {
                bestAlong = along;
                best = i;
            }
        }
        return best;
    }

    static boolean inCell(@Nonnull Vector3d m, @Nonnull BlockPosition cell) {
        return within(m.x, cell.x) && within(m.y, cell.y) && within(m.z, cell.z);
    }

    private static boolean within(double value, int cell) {
        return value >= cell - MARGIN && value <= cell + 1 + MARGIN;
    }

    /**
     * Whether the straight line from {@code from} to {@code to} crosses no solid cell.
     */
    static boolean clear(@Nonnull Vector3d from, @Nonnull Vector3d to, @Nonnull Cells solid) {
        double length = from.distance(to);
        int steps = Math.max(1, (int) Math.ceil(length / STEP));
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            double x = from.x + (to.x - from.x) * t;
            double y = from.y + (to.y - from.y) * t;
            double z = from.z + (to.z - from.z) * t;
            if (solid.at((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A block that stops a line: solid, or in a chunk that is not loaded.
     */
    private static boolean solid(@Nonnull World world, int x, int y, int z) {
        BlockType block = BlockAccess.getType(world, x, y, z);
        return block == null || block.getMaterial() == BlockMaterial.Solid;
    }

    /**
     * Which cells are solid.
     */
    @FunctionalInterface
    interface Cells {
        boolean at(int x, int y, int z);
    }
}
