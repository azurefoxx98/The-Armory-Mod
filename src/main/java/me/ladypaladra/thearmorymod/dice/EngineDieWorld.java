package me.ladypaladra.thearmorymod.dice;

import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.server.core.asset.type.blockhitbox.BlockBoundingBoxes;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.modules.collision.BlockDataProvider;
import com.hypixel.hytale.server.core.modules.collision.CollisionMaterial;
import com.hypixel.hytale.server.core.universe.world.World;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import javax.annotation.Nonnull;

/**
 * The game's blocks as a die sees them: the same collision boxes, turned the same way, that the engine's own projectiles
 * collide with.
 *
 * <p>It reads blocks through the engine's block reader, so a block in an unloaded chunk counts as a solid cube and a
 * die never falls into the unknown. One reader serves one world thread at a time.</p>
 */
final class EngineDieWorld extends BlockDataProvider implements DieWorld {

    private static final ThreadLocal<EngineDieWorld> READERS = ThreadLocal.withInitial(EngineDieWorld::new);

    // A block taller than its cell (a fence, a wall) reaches into the cells above and beside it.
    private static final int REACH = 1;

    private final LongOpenHashSet origins = new LongOpenHashSet();

    private EngineDieWorld() {
    }

    /**
     * This thread's reader, pointed at {@code world}.
     */
    @Nonnull
    static EngineDieWorld on(@Nonnull World world) {
        EngineDieWorld reader = READERS.get();
        reader.initialize(world);
        return reader;
    }

    @Override
    public void solids(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                       @Nonnull Boxes out) {
        origins.clear();
        int x0 = (int) Math.floor(minX) - REACH;
        int y0 = (int) Math.floor(minY) - REACH;
        int z0 = (int) Math.floor(minZ) - REACH;
        int x1 = (int) Math.floor(maxX) + REACH;
        int y1 = (int) Math.floor(maxY);
        int z1 = (int) Math.floor(maxZ) + REACH;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    // The reader keeps its last block's turn when the next block has the same id, so two stairs side
                    // by side facing different ways would read alike. Clearing it first makes every read whole.
                    clear();
                    read(x, y, z);
                    if ((getCollisionMaterials() & CollisionMaterial.MATERIAL_SOLID) == 0) {
                        continue;
                    }
                    BlockBoundingBoxes boxes = getBlockBoundingBoxes();
                    if (boxes == null) {
                        continue;
                    }
                    int ox = originX(x);
                    int oy = originY(y);
                    int oz = originZ(z);
                    // A large block fills several cells, each pointing back to one origin: its boxes count once.
                    if (!origins.add(((long) ox & 0x1FFFFFL) << 42 | ((long) oy & 0x1FFFFFL) << 21 | ((long) oz & 0x1FFFFFL))) {
                        continue;
                    }
                    for (Box box : boxes.get(rotation).getDetailBoxes()) {
                        double bx0 = box.getMin().x + ox;
                        double by0 = box.getMin().y + oy;
                        double bz0 = box.getMin().z + oz;
                        double bx1 = box.getMax().x + ox;
                        double by1 = box.getMax().y + oy;
                        double bz1 = box.getMax().z + oz;
                        if (bx1 > minX && by1 > minY && bz1 > minZ && bx0 < maxX && by0 < maxY && bz0 < maxZ) {
                            out.add(bx0, by0, bz0, bx1, by1, bz1);
                        }
                    }
                }
            }
        }
    }

    @Override
    public double fluidTop(int x, int y, int z) {
        clear();
        read(x, y, z);
        if (getFluidId() == Fluid.EMPTY_ID || getFluidId() == Fluid.UNKNOWN_ID || getFluid() == null) {
            return Double.NaN;
        }
        return y + getFillHeight();
    }
}
