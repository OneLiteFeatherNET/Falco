package net.onelitefeather.falco.light;

import org.jetbrains.annotations.ApiStatus;

import java.util.Arrays;

/**
 * The {@link LightPropagator} class spreads the light of every emitting block through a section.
 * <p>
 * The propagation is a breadth-first search. A position is queued again whenever a brighter source
 * raises its level, which happens when sources of different brightness reach the same area. The
 * queue therefore holds more entries than the section has positions and grows when it runs full.
 * </p>
 * <p>
 * An instance keeps its working buffers and reuses them across runs, which is what makes repeated
 * propagation allocation free apart from the result. The buffers are cleared at the start of every
 * run, so results never bleed from one run into the next. An instance is therefore reusable but
 * confined to a single thread; use one per worker.
 * </p>
 * <p>
 * This type is experimental. The light engine is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public final class LightPropagator {

    private static final BlockFace[] FACES = BlockFace.values();
    private static final int MASK = LightNibbles.DIMENSION - 1;

    private final byte[] levels;
    private int[] queue;

    /**
     * Creates a new propagator with its own working buffers.
     */
    public LightPropagator() {
        this.levels = new byte[LightNibbles.BLOCK_COUNT];
        this.queue = new int[LightNibbles.BLOCK_COUNT];
    }

    /**
     * Makes room for one more entry in the queue.
     * <p>
     * A position is queued again every time its level is raised, which happens when a brighter
     * source reaches a position a dimmer one had already lit. The amount of entries is therefore
     * not bounded by the amount of positions, and the queue has to be able to grow.
     * </p>
     *
     * @param tail the amount of entries the queue currently holds
     */
    private void ensureRoom(int tail) {
        if (tail == this.queue.length) {
            this.queue = java.util.Arrays.copyOf(this.queue, this.queue.length * 2);
        }
    }

    /**
     * Calculates the light of a section from the blocks it holds.
     *
     * @param opacity the light properties of every block of the section
     * @return the calculated light of the section
     */
    public LightNibbles propagate(SectionOpacity opacity) {
        if (!opacity.hasEmission()) {
            return LightNibbles.uniform(0);
        }

        Arrays.fill(this.levels, (byte) 0);
        int tail = seed(opacity);
        int head = 0;

        while (head < tail) {
            int index = this.queue[head++];
            int level = this.levels[index];

            if (level <= 1) {
                continue;
            }

            int x = index & MASK;
            int z = (index >> 4) & MASK;
            int y = (index >> 8) & MASK;
            int next = level - 1;

            for (BlockFace face : FACES) {
                int neighbourX = x + face.offsetX();
                int neighbourY = y + face.offsetY();
                int neighbourZ = z + face.offsetZ();

                if (isOutside(neighbourX, neighbourY, neighbourZ)) {
                    continue;
                }
                // Only the face light enters decides whether it can pass. Testing the face it
                // leaves as well would keep every emitting block that is opaque itself dark, and a
                // glowstone block is exactly that.
                if (opacity.blocksFace(neighbourX, neighbourY, neighbourZ, face.opposite())) {
                    continue;
                }

                int neighbourIndex = index(neighbourX, neighbourY, neighbourZ);

                if (this.levels[neighbourIndex] >= next) {
                    continue;
                }
                this.levels[neighbourIndex] = (byte) next;
                ensureRoom(tail);
                this.queue[tail++] = neighbourIndex;
            }
        }
        return collect();
    }

    /**
     * Puts every emitting block of the section into the queue.
     *
     * @param opacity the light properties of every block of the section
     * @return the amount of queued positions
     */
    private int seed(SectionOpacity opacity) {
        int tail = 0;

        for (int y = 0; y < LightNibbles.DIMENSION; y++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                    int emission = opacity.emission(x, y, z);

                    if (emission <= 0) {
                        continue;
                    }
                    int index = index(x, y, z);
                    this.levels[index] = (byte) emission;
                    ensureRoom(tail);
                    this.queue[tail++] = index;
                }
            }
        }
        return tail;
    }

    /**
     * Transfers the calculated levels into a light section.
     * A result in which every block carries the same level is stored without an array.
     *
     * @return the calculated light of the section
     */
    private LightNibbles collect() {
        return LightNibbles.ofLevels(this.levels, 0);
    }

    /**
     * Checks whether the given position lies outside of the section.
     *
     * @param x the x coordinate to check
     * @param y the y coordinate to check
     * @param z the z coordinate to check
     * @return true if the position is outside of the section, otherwise false
     */
    private static boolean isOutside(int x, int y, int z) {
        return (x | y | z) < 0 || x >= LightNibbles.DIMENSION || y >= LightNibbles.DIMENSION || z >= LightNibbles.DIMENSION;
    }

    /**
     * Calculates the index of a block inside the section.
     *
     * @param x the x coordinate inside the section
     * @param y the y coordinate inside the section
     * @param z the z coordinate inside the section
     * @return the index of the block
     */
    private static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }
}
