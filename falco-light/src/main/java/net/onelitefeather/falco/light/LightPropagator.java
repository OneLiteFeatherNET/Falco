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

    /**
     * The opposite of every face, in the order of {@link #FACES}.
     * <p>
     * {@code BlockFace#opposite()} is a switch over the enum, and the inner loop of a propagation
     * calls it once per face per queued position — six times for every block the light reaches.
     * Resolving it once at class load turns that into an array read.
     * </p>
     */
    private static final BlockFace[] OPPOSITES = opposites();

    /**
     * The index into {@link #FACES} of the opposite of every face.
     * <p>
     * A queued position remembers the face that points back at whoever queued it, and a face is
     * cheaper to carry as its index than as a reference. This is that index.
     * </p>
     */
    private static final int[] OPPOSITE_INDEX = oppositeIndexes();

    /**
     * The amount of bits a queued position occupies, leaving the ones above it for the face.
     * A section holds 4096 positions, so twelve bits carry every one of them.
     */
    private static final int POSITION_BITS = 12;

    /**
     * The bits of a queue entry which carry the position.
     */
    private static final int POSITION_MASK = (1 << POSITION_BITS) - 1;

    /**
     * The face value of an entry which nobody queued, so no direction may be skipped for it.
     * Six faces occupy the indexes zero to five, which leaves this one free.
     */
    private static final int NO_FACE = 7;

    /**
     * Resolves the index of the opposite of every face once.
     *
     * @return the index of the opposite of every face, indexed like {@link #FACES}
     */
    private static int[] oppositeIndexes() {
        int[] indexes = new int[FACES.length];

        for (int index = 0; index < FACES.length; index++) {
            indexes[index] = OPPOSITES[index].ordinal();
        }
        return indexes;
    }

    /**
     * Resolves the opposite of every face once.
     *
     * @return the opposite of every face, indexed like {@link #FACES}
     */
    private static BlockFace[] opposites() {
        BlockFace[] opposites = new BlockFace[FACES.length];

        for (int index = 0; index < FACES.length; index++) {
            opposites[index] = FACES[index].opposite();
        }
        return opposites;
    }

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
            this.queue = Arrays.copyOf(this.queue, this.queue.length * 2);
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
            int entry = this.queue[head++];
            int index = entry & POSITION_MASK;
            int arrivedFrom = entry >>> POSITION_BITS;
            int level = this.levels[index];

            if (level <= 1) {
                continue;
            }

            int x = index & MASK;
            int z = (index >> 4) & MASK;
            int y = (index >> 8) & MASK;
            int next = level - 1;

            for (int faceIndex = 0; faceIndex < FACES.length; faceIndex++) {
                // Whoever queued this position sits on the far side of that face and already holds
                // a level one higher, so the test below could never pass for it. Skipping the face
                // outright is the same result for a sixth less work.
                if (faceIndex == arrivedFrom) {
                    continue;
                }
                BlockFace face = FACES[faceIndex];
                int neighbourX = x + face.offsetX();
                int neighbourY = y + face.offsetY();
                int neighbourZ = z + face.offsetZ();

                if (isOutside(neighbourX, neighbourY, neighbourZ)) {
                    continue;
                }
                int neighbourIndex = index(neighbourX, neighbourY, neighbourZ);

                // The level is one array read, the occlusion is two and a branch, and the level
                // rejects far more often — a position is reached from up to six directions and only
                // the first of them raises it. Cheapest and most selective test first.
                if (this.levels[neighbourIndex] >= next) {
                    continue;
                }
                // Only the face light enters decides whether it can pass. Testing the face it
                // leaves as well would keep every emitting block that is opaque itself dark, and a
                // glowstone block is exactly that.
                if (opacity.blocksFace(neighbourX, neighbourY, neighbourZ, OPPOSITES[faceIndex])) {
                    continue;
                }
                this.levels[neighbourIndex] = (byte) next;
                ensureRoom(tail);
                this.queue[tail++] = neighbourIndex | (OPPOSITE_INDEX[faceIndex] << POSITION_BITS);
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
                    this.queue[tail++] = index | (NO_FACE << POSITION_BITS);
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
