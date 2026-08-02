package net.onelitefeather.falco.light;

import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The {@link ChunkLightPropagator} class spreads light through every section of a chunk at once.
 * <p>
 * A propagation which stops at a section border produces a visible seam every sixteen blocks,
 * because a light source near the border lights its own section and nothing beyond it. This class
 * therefore treats the sections of a chunk as one column and lets the search cross their borders.
 * </p>
 * <p>
 * The search is the same breadth-first pass {@link LightPropagator} performs, extended by the
 * vertical axis. A position is visited again whenever a brighter source raises its level, so the
 * queue can hold more entries than the column has positions and grows when it runs full.
 * </p>
 * <p>
 * An instance keeps its buffers for the largest column it has seen and reuses them, so repeated
 * runs allocate nothing beyond their result. It is reusable but confined to a single thread.
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
public final class ChunkLightPropagator {

    private static final BlockFace[] FACES = BlockFace.values();
    private static final int MASK = LightNibbles.DIMENSION - 1;

    /**
     * The opposite of every face, in the order of {@link #FACES}.
     * <p>
     * {@code BlockFace#opposite()} is a switch over the enum, and the inner loop calls it once per
     * face per queued position — six times for every block the light reaches. Resolving it once at
     * class load turns that into an array read.
     * </p>
     */
    private static final BlockFace[] OPPOSITES = opposites();

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

    /**
     * The index into {@link #FACES} of the opposite of every face.
     * <p>
     * A queued position remembers the face that points back at whoever queued it, and a face is
     * cheaper to carry as its index than as a reference. This is that index.
     * </p>
     */
    private static final int[] OPPOSITE_INDEX = oppositeIndexes();

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
     * The amount of bits a queued position occupies, leaving the ones above it for the face.
     * <p>
     * A position index is {@code (y << 8) | (z << 4) | x} over a column of at most a few hundred
     * blocks, so twenty-four bits carry a column of 65 536 sections — four orders of magnitude past
     * anything a dimension declares.
     * </p>
     */
    private static final int POSITION_BITS = 24;

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
     * The bit of the opposite of every face, ready to be tested against a flat occlusion byte.
     */
    private static final int[] OPPOSITE_BIT = oppositeBits();

    /**
     * Resolves the occlusion bit of the opposite of every face once.
     *
     * @return the bit of the opposite of every face, indexed like {@link #FACES}
     */
    private static int[] oppositeBits() {
        int[] bits = new int[FACES.length];

        for (int index = 0; index < FACES.length; index++) {
            bits[index] = 1 << OPPOSITE_INDEX[index];
        }
        return bits;
    }

    /**
     * The occlusion bit of the face light enters a block through when it falls straight down.
     */
    private static final int TOP_BIT = 1 << BlockFace.TOP.ordinal();

    private byte[] levels;
    private byte[] occlusion;
    private int[] queue;

    /**
     * Creates a new propagator without any buffer. The buffers are sized on the first run.
     */
    public ChunkLightPropagator() {
        this.levels = new byte[0];
        this.occlusion = new byte[0];
        this.queue = new int[0];
    }

    /**
     * Calculates the light of every section of a chunk.
     * The sections are expected in the order they are stacked, starting with the lowest one.
     *
     * @param sections the light properties of every section of the chunk
     * @return the calculated light of every section, in the order the sections were given
     * @throws IllegalArgumentException if the chunk holds no section
     */
    public List<LightNibbles> propagate(List<SectionOpacity> sections) {
        int height = prepare(sections);
        return search(sections, height, seed(sections, height));
    }

    /**
     * Calculates the sky light of every section of a chunk.
     * <p>
     * Sky light enters from above and falls straight down without losing a level, which is what
     * makes a cave dark while an open field is fully lit at every height. Only once something stops
     * the fall does the light spread like any other light, losing one level per block.
     * </p>
     *
     * @param sections the light properties of every section of the chunk
     * @return the calculated sky light of every section, in the order the sections were given
     * @throws IllegalArgumentException if the chunk holds no section
     */
    public List<LightNibbles> propagateSky(List<SectionOpacity> sections) {
        int height = prepare(sections);
        return search(sections, height, seedSky(sections, height));
    }

    /**
     * Verifies the given chunk and clears the buffers for a new run.
     *
     * @param sections the light properties of every section of the chunk
     * @return the amount of blocks the column spans vertically
     * @throws IllegalArgumentException if the chunk holds no section
     */
    private int prepare(List<SectionOpacity> sections) {
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("A chunk has to hold at least one section");
        }

        int height = sections.size() * LightNibbles.DIMENSION;
        int blockCount = height * LightNibbles.DIMENSION * LightNibbles.DIMENSION;
        ensureCapacity(blockCount);
        Arrays.fill(this.levels, 0, blockCount, (byte) 0);

        // The whole column is laid out flat once, so the search reads one array instead of walking
        // list, section and null test per face per queued position. It is paid for by one fill or
        // one copy per section, both of which the JIT turns into vector stores.
        for (int section = 0; section < sections.size(); section++) {
            sections.get(section).copyOcclusionInto(this.occlusion, section * LightNibbles.BLOCK_COUNT);
        }
        return height;
    }

    /**
     * Spreads the queued levels through the column.
     *
     * @param sections the light properties of every section of the chunk
     * @param height   the amount of blocks the column spans vertically
     * @param queued   the amount of positions which were queued as sources
     * @return the calculated light of every section
     */
    private List<LightNibbles> search(List<SectionOpacity> sections, int height, int queued) {
        int tail = queued;
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
            int y = index >> 8;
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

                if (isOutside(neighbourX, neighbourY, neighbourZ, height)) {
                    continue;
                }
                int neighbourIndex = index(neighbourX, neighbourY, neighbourZ);

                // The level is one array read, the occlusion is two and a branch, and the level
                // rejects far more often — a position is reached from up to six directions and only
                // the first of them raises it. Cheapest and most selective test first.
                if (this.levels[neighbourIndex] >= next) {
                    continue;
                }
                if ((this.occlusion[neighbourIndex] & OPPOSITE_BIT[faceIndex]) != 0) {
                    continue;
                }
                this.levels[neighbourIndex] = (byte) next;
                ensureRoom(tail);
                this.queue[tail++] = neighbourIndex | (OPPOSITE_INDEX[faceIndex] << POSITION_BITS);
            }
        }
        return collect(sections.size());
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
     * Grows the buffers if the given column needs more room than the previous one.
     *
     * @param blockCount the amount of blocks the column holds
     */
    private void ensureCapacity(int blockCount) {
        if (this.levels.length < blockCount) {
            this.levels = new byte[blockCount];
            this.occlusion = new byte[blockCount];
            this.queue = new int[blockCount];
        }
    }

    /**
     * Puts every emitting block of the column into the queue.
     *
     * @param sections the light properties of every section
     * @param height   the amount of blocks the column spans vertically
     * @return the amount of queued positions
     */
    private int seed(List<SectionOpacity> sections, int height) {
        int tail = 0;

        for (int y = 0; y < height; y++) {
            SectionOpacity section = sections.get(y >> 4);

            if (!section.hasEmission()) {
                continue;
            }
            int localY = y & MASK;

            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                    int emission = section.emission(x, localY, z);

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
     * Puts every block which sees the open sky into the queue.
     * <p>
     * Every column is walked from the top of the chunk downwards. As long as light can enter the
     * block from above it receives the full level, which is why an open column is lit to the very
     * bottom. The walk of a column ends at the first block that stops the light.
     * </p>
     *
     * @param sections the light properties of every section
     * @param height   the amount of blocks the column spans vertically
     * @return the amount of queued positions
     */
    private int seedSky(List<SectionOpacity> sections, int height) {
        int tail = 0;

        for (int z = 0; z < LightNibbles.DIMENSION; z++) {
            for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                for (int y = height - 1; y >= 0; y--) {
                    int index = index(x, y, z);

                    if ((this.occlusion[index] & TOP_BIT) != 0) {
                        break;
                    }
                    this.levels[index] = LightNibbles.MAX_LEVEL;
                    ensureRoom(tail);
                    this.queue[tail++] = index | (NO_FACE << POSITION_BITS);
                }
            }
        }
        return tail;
    }

    /**
     * Transfers the calculated levels into one light section per section of the chunk.
     *
     * @param sectionCount the amount of sections the chunk holds
     * @return the calculated light of every section
     */
    private List<LightNibbles> collect(int sectionCount) {
        List<LightNibbles> result = new ArrayList<>(sectionCount);

        for (int section = 0; section < sectionCount; section++) {
            int base = section * LightNibbles.DIMENSION;
            result.add(collectSection(base));
        }
        return result;
    }

    /**
     * Transfers the levels of a single section into a light section.
     *
     * @param baseY the lowest y coordinate of the section inside the column
     * @return the light of the section
     */
    private LightNibbles collectSection(int baseY) {
        // The levels of a section lie next to each other in the buffer of the whole column, because
        // the index of a position puts its y coordinate into the highest bits.
        return LightNibbles.ofLevels(this.levels, baseY << 8);
    }

    /**
     * Checks whether the given position lies outside of the column.
     *
     * @param x      the x coordinate to check
     * @param y      the y coordinate to check
     * @param z      the z coordinate to check
     * @param height the amount of blocks the column spans vertically
     * @return true if the position is outside of the column, otherwise false
     */
    private static boolean isOutside(int x, int y, int z, int height) {
        return (x | y | z) < 0 || x >= LightNibbles.DIMENSION || z >= LightNibbles.DIMENSION || y >= height;
    }

    /**
     * Calculates the index of a block inside the column.
     *
     * @param x the x coordinate inside the chunk
     * @param y the y coordinate inside the column
     * @param z the z coordinate inside the chunk
     * @return the index of the block
     */
    private static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }
}
