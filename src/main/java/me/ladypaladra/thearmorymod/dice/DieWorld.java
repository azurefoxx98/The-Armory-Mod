package me.ladypaladra.thearmorymod.dice;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * What a flying die can touch: the solid boxes of the blocks around it, and the water it may sink in.
 *
 * <p>The game's blocks are read through it in play, and a test builds its own floors, steps and walls, so the same
 * physics runs in both.</p>
 */
public interface DieWorld {

    /**
     * Adds every solid box that reaches into the region, in world blocks.
     */
    void solids(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, @Nonnull Boxes out);

    /**
     * The height of the fluid's surface in the block at {@code x, y, z}, or NaN when that block holds none.
     */
    double fluidTop(int x, int y, int z);

    /**
     * A list of boxes, each as its low corner then its high corner, kept in one array so a tick allocates nothing.
     */
    final class Boxes {
        double[] v = new double[6 * 16];
        int count;

        void clear() {
            count = 0;
        }

        void add(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            if (6 * (count + 1) > v.length) {
                v = Arrays.copyOf(v, v.length * 2);
            }
            int i = 6 * count++;
            v[i] = minX;
            v[i + 1] = minY;
            v[i + 2] = minZ;
            v[i + 3] = maxX;
            v[i + 4] = maxY;
            v[i + 5] = maxZ;
        }

        /**
         * Whether any box holds the point, more than {@code margin} inside it.
         */
        boolean holds(double x, double y, double z, double margin) {
            for (int b = 0; b < count; b++) {
                int i = 6 * b;
                if (x > v[i] + margin && y > v[i + 1] + margin && z > v[i + 2] + margin
                        && x < v[i + 3] - margin && y < v[i + 4] - margin && z < v[i + 5] - margin) {
                    return true;
                }
            }
            return false;
        }
    }
}
