package net.onelitefeather.falco.light;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The {@link ChunkLightState} class holds the calculated light of a chunk and updates it when a
 * single block changed, without recalculating the whole chunk.
 * <p>
 * Adding brightness is easy: the new light spreads and never has to take anything back. Removing it
 * is the hard case and the reason this class exists. When a light source disappears, the brightness
 * it had spread is still stored in every block around it, and simply spreading again would keep
 * that glow forever. The update therefore runs in two passes: the first retracts every level which
 * originated from the changed position, collecting the still valid levels it meets at the edge, and
 * the second spreads those back in.
 * </p>
 * <p>
 * Sky light needs one more piece of state for the same reason. Its origin is not a block but the
 * open sky above a column, so an update cannot tell from the levels alone which positions lost
 * their origin and which gained one. A state which holds sky light therefore keeps the height at
 * which every column stops the sky and compares it against the height the column has after the
 * change. Only that one column can move, which is what keeps an update small instead of re-seeding
 * all two hundred and fifty six columns of the chunk.
 * </p>
 * <p>
 * Instances are not thread safe. Keep one per chunk and use it from one thread at a time.
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
public final class ChunkLightState {

    private static final BlockFace[] FACES = BlockFace.values();
    private static final int MASK = LightNibbles.DIMENSION - 1;

    /**
     * The heightmap of a state which holds block light. Block light never reads it.
     */
    private static final int[] NO_HEIGHTMAP = new int[0];

    /**
     * The height of a column which nothing stops the sky in.
     */
    private static final int OPEN_COLUMN = -1;

    /**
     * The amount of entries the working queues start with.
     * <p>
     * A queue sized for one entry per position would be correct and would cost about 860 KB per
     * state, which is nine tenths of everything a state occupies — for buffers that an update of a
     * single block fills a few dozen entries of. They therefore start small and grow, which is what
     * makes a state cheap enough to be kept between two passes rather than thrown away after each.
     * </p>
     */
    private static final int INITIAL_QUEUE_SIZE = 1024;

    private final byte[] levels;
    private final int sectionCount;
    private final int height;
    private final boolean sky;
    private final int[] skyTop;

    private int[] removalQueue;
    private byte[] removalLevels;
    private int[] additionQueue;

    /**
     * Creates a new state from the given levels.
     *
     * @param levels       the level of every block of the column
     * @param sectionCount the amount of sections the chunk holds
     * @param sky          whether the state holds sky light
     * @param skyTop       the highest position which stops the sky per column
     */
    private ChunkLightState(byte[] levels, int sectionCount, boolean sky, int[] skyTop) {
        this.levels = levels;
        this.sectionCount = sectionCount;
        this.height = sectionCount * LightNibbles.DIMENSION;
        this.sky = sky;
        this.skyTop = skyTop;
        this.removalQueue = new int[INITIAL_QUEUE_SIZE];
        this.removalLevels = new byte[INITIAL_QUEUE_SIZE];
        this.additionQueue = new int[INITIAL_QUEUE_SIZE];
    }

    /**
     * Returns an independent state which holds the same light as this one.
     * <p>
     * This is what lets a computed light be kept and updated instead of being calculated again. A
     * border exchange raises the levels of everything it touches, so the state it runs on is no
     * longer the light of that chunk alone and cannot serve as the starting point of the next
     * update. The kept state stays untouched and the exchange runs on a copy of it.
     * </p>
     * <p>
     * The working queues are not copied. They hold nothing between two calls, and a copy which
     * carried them would defeat the point of keeping the state small.
     * </p>
     *
     * @return a copy which shares nothing with this state
     */
    @Contract(pure = true)
    public ChunkLightState copy() {
        return new ChunkLightState(
                this.levels.clone(),
                this.sectionCount,
                this.sky,
                this.skyTop.length == 0 ? NO_HEIGHTMAP : this.skyTop.clone()
        );
    }

    /**
     * Makes room for one more entry in the addition queue.
     * <p>
     * A position enters the queue again whenever a brighter source raises it, and the retraction can
     * reach the same position from several sides. The amount of entries is therefore not bounded by
     * the amount of positions, so the queue has to be able to grow rather than rely on that
     * assumption.
     * </p>
     *
     * @param tail the amount of entries the queue currently holds
     */
    private void ensureRoom(int tail) {
        if (tail == this.additionQueue.length) {
            this.additionQueue = Arrays.copyOf(this.additionQueue, this.additionQueue.length * 2);
        }
    }

    /**
     * Makes room for one more entry in the retraction queue.
     *
     * @param tail the amount of entries the queue currently holds
     */
    private void ensureRemovalRoom(int tail) {
        if (tail == this.removalQueue.length) {
            this.removalQueue = Arrays.copyOf(this.removalQueue, this.removalQueue.length * 2);
            this.removalLevels = Arrays.copyOf(this.removalLevels, this.removalLevels.length * 2);
        }
    }

    /**
     * Calculates the block light of a chunk and keeps it for later updates.
     *
     * @param sections the light properties of every section of the chunk
     * @return the created state
     */
    public static ChunkLightState blockLight(List<SectionOpacity> sections) {
        return of(sections, new ChunkLightPropagator().propagate(sections), false);
    }

    /**
     * Calculates the sky light of a chunk and keeps it for later updates.
     *
     * @param sections the light properties of every section of the chunk
     * @return the created state
     */
    public static ChunkLightState skyLight(List<SectionOpacity> sections) {
        return of(sections, new ChunkLightPropagator().propagateSky(sections), true);
    }

    /**
     * Builds a state from an already calculated light.
     *
     * @param sections the light properties of every section of the chunk
     * @param light    the calculated light of every section
     * @param sky      whether the light is sky light
     * @return the created state
     */
    private static ChunkLightState of(List<SectionOpacity> sections, List<LightNibbles> light, boolean sky) {
        int sectionCount = sections.size();
        byte[] levels = new byte[sectionCount * LightNibbles.BLOCK_COUNT];

        for (int section = 0; section < sectionCount; section++) {
            LightNibbles nibbles = light.get(section);
            int base = section * LightNibbles.DIMENSION;

            for (int y = 0; y < LightNibbles.DIMENSION; y++) {
                for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                    for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                        levels[index(x, base + y, z)] = (byte) nibbles.get(x, y, z);
                    }
                }
            }
        }
        return new ChunkLightState(levels, sectionCount, sky, sky ? skyTopOf(sections) : NO_HEIGHTMAP);
    }

    /**
     * Determines for every column of the chunk where the sky stops.
     *
     * @param sections the light properties of every section of the chunk
     * @return the highest position which stops the sky per column
     */
    @Contract(pure = true)
    private static int[] skyTopOf(List<SectionOpacity> sections) {
        int height = sections.size() * LightNibbles.DIMENSION;
        int[] skyTop = new int[LightNibbles.DIMENSION * LightNibbles.DIMENSION];

        for (int z = 0; z < LightNibbles.DIMENSION; z++) {
            for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                skyTop[column(x, z)] = columnTop(sections, height, x, z);
            }
        }
        return skyTop;
    }

    /**
     * Determines where the sky stops in a single column.
     * <p>
     * The walk starts at the top of the chunk and ends at the first block which light cannot enter
     * from above, exactly as the initial sky propagation walks a column. Everything above that
     * block sees the open sky, everything below it does not.
     * </p>
     *
     * @param sections the light properties of every section of the chunk
     * @param height   the amount of blocks the column spans vertically
     * @param x        the x coordinate inside the chunk
     * @param z        the z coordinate inside the chunk
     * @return the highest position which stops the sky, or a negative value for an open column
     */
    @Contract(pure = true)
    private static int columnTop(List<SectionOpacity> sections, int height, int x, int z) {
        for (int y = height - 1; y >= 0; y--) {
            if (blocksFace(sections, x, y, z, BlockFace.TOP)) {
                return y;
            }
        }
        return OPEN_COLUMN;
    }

    /**
     * Returns the level which is currently stored for the given position.
     *
     * @param x the x coordinate inside the chunk
     * @param y the y coordinate inside the column
     * @param z the z coordinate inside the chunk
     * @return the stored level of the position
     */
    @Contract(pure = true)
    public int get(int x, int y, int z) {
        return this.levels[index(x, y, z)];
    }

    /**
     * Updates the light after the block at the given position changed.
     * <p>
     * The neighbours of the changed position are handed to the second pass together with the
     * sources of the chunk, and that is not redundant. A block which stops blocking light holds none
     * of its own, so the retraction finds nothing to follow, and offering the sources again reaches
     * nothing either: their neighbours already carry exactly the level they belong at, so the search
     * stops at the first of them and never travels the six blocks to the position that opened up.
     * The position is only filled by the light standing right next to it, which is what these seeds
     * are.
     * </p>
     *
     * @param sections the light properties of every section, reflecting the change
     * @param x        the x coordinate inside the chunk
     * @param y        the y coordinate inside the column
     * @param z        the z coordinate inside the chunk
     */
    public void update(List<SectionOpacity> sections, int x, int y, int z) {
        if (this.sky) {
            updateSky(sections, x, y, z);
            return;
        }

        int additions = retract(seedRemoval(0, index(x, y, z)));
        additions = seedEmission(sections, additions);
        spread(sections, seedNeighbours(additions, x, y, z));
    }

    /**
     * Updates the sky light after the block at the given position changed.
     * <p>
     * Only the column of the changed block can stop the sky at another height than before, so only
     * that column is walked again. The difference between the old and the new height names exactly
     * the positions which changed their origin: the ones that fell out of the open sky have to give
     * their level back, the ones that fell into it receive the full level.
     * </p>
     * <p>
     * The changed position itself is retracted in either case, because it carries the light of the
     * block that is gone. Its neighbours are handed to the second pass afterwards, since a position
     * which just turned transparent holds no light of its own that a retraction could follow and
     * has to be filled from the outside instead.
     * </p>
     *
     * @param sections the light properties of every section, reflecting the change
     * @param x        the x coordinate inside the chunk
     * @param y        the y coordinate inside the column
     * @param z        the z coordinate inside the chunk
     */
    private void updateSky(List<SectionOpacity> sections, int x, int y, int z) {
        int column = column(x, z);
        int previousTop = this.skyTop[column];
        int currentTop = columnTop(sections, this.height, x, z);
        this.skyTop[column] = currentTop;

        int removals = seedRemoval(0, index(x, y, z));

        for (int lost = previousTop + 1; lost <= currentTop; lost++) {
            if (lost != y) {
                removals = seedRemoval(removals, index(x, lost, z));
            }
        }

        int additions = retract(removals);

        for (int opened = currentTop + 1; opened <= previousTop; opened++) {
            additions = seedSky(additions, index(x, opened, z));
        }

        // A position above both heights kept its open sky and only lost its level to the retraction.
        if (y > currentTop && y > previousTop) {
            additions = seedSky(additions, index(x, y, z));
        }
        spread(sections, seedNeighbours(additions, x, y, z));
    }

    /**
     * Clears the level of a position and hands it to the retraction.
     *
     * @param queued the amount of positions which are already queued for the retraction
     * @param index  the index of the position to retract
     * @return the amount of queued positions
     */
    private int seedRemoval(int queued, int index) {
        ensureRemovalRoom(queued);
        this.removalQueue[queued] = index;
        this.removalLevels[queued] = this.levels[index];
        this.levels[index] = 0;
        return queued + 1;
    }

    /**
     * Gives a position which sees the open sky its full level and hands it to the second pass.
     *
     * @param queued the amount of positions which are already queued for the second pass
     * @param index  the index of the position which sees the sky
     * @return the amount of queued positions
     */
    private int seedSky(int queued, int index) {
        this.levels[index] = LightNibbles.MAX_LEVEL;
        // Guarded like every other write into the queue since it stopped being sized for the whole
        // chunk. A column which opens up cannot follow a retraction in the same call, so no reachable
        // path arrives here with a full queue — which is a reason to keep the guard, not to drop it.
        ensureRoom(queued);
        this.additionQueue[queued] = index;
        return queued + 1;
    }

    /**
     * Hands every neighbour of the changed position which still carries light to the second pass.
     *
     * @param queued the amount of positions which are already queued for the second pass
     * @param x      the x coordinate inside the chunk
     * @param y      the y coordinate inside the column
     * @param z      the z coordinate inside the chunk
     * @return the amount of queued positions
     */
    private int seedNeighbours(int queued, int x, int y, int z) {
        int tail = queued;

        for (BlockFace face : FACES) {
            int neighbourX = x + face.offsetX();
            int neighbourY = y + face.offsetY();
            int neighbourZ = z + face.offsetZ();

            if (isOutside(neighbourX, neighbourY, neighbourZ)) {
                continue;
            }

            int neighbourIndex = index(neighbourX, neighbourY, neighbourZ);

            if (this.levels[neighbourIndex] <= 1) {
                continue;
            }
            ensureRoom(tail);
                this.additionQueue[tail++] = neighbourIndex;
        }
        return tail;
    }

    /**
     * Retracts every level which originated from the already cleared positions.
     * <p>
     * A neighbour which is darker than the level being removed can only have received its light
     * from it, so it is cleared as well. A neighbour which is as bright or brighter has another
     * origin and becomes a starting point for the second pass instead. Every position the caller
     * seeded is cleared before the walk begins, so a seed never mistakes another seed for a source
     * that is still valid.
     * </p>
     *
     * @param seeded the amount of positions the caller handed to the retraction
     * @return the amount of positions which were queued for the second pass
     */
    private int retract(int seeded) {
        int removalTail = seeded;
        int additionTail = 0;

        for (int head = 0; head < removalTail; head++) {
            int index = this.removalQueue[head];
            int removed = this.removalLevels[head];

            if (removed == 0) {
                continue;
            }

            int x = index & MASK;
            int z = (index >> 4) & MASK;
            int y = index >> 8;

            for (BlockFace face : FACES) {
                int neighbourX = x + face.offsetX();
                int neighbourY = y + face.offsetY();
                int neighbourZ = z + face.offsetZ();

                if (isOutside(neighbourX, neighbourY, neighbourZ)) {
                    continue;
                }

                int neighbourIndex = index(neighbourX, neighbourY, neighbourZ);
                int level = this.levels[neighbourIndex];

                if (level == 0) {
                    continue;
                }
                if (level < removed) {
                    ensureRemovalRoom(removalTail);
                    this.removalQueue[removalTail] = neighbourIndex;
                    this.removalLevels[removalTail++] = (byte) level;
                    this.levels[neighbourIndex] = 0;
                    continue;
                }
                ensureRoom(additionTail);
                this.additionQueue[additionTail++] = neighbourIndex;
            }
        }
        return additionTail;
    }

    /**
     * Adds every position which produces light on its own to the second pass.
     * <p>
     * A block which turns transparent holds no light a retraction could follow, so the sources of
     * the chunk are offered again and refill the position that opened up.
     * </p>
     *
     * @param sections the light properties of every section
     * @param queued   the amount of positions which are already queued
     * @return the amount of queued positions
     */
    private int seedEmission(List<SectionOpacity> sections, int queued) {
        int tail = queued;

        for (int y = 0; y < this.height; y++) {
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

                    if (this.levels[index] < emission) {
                        this.levels[index] = (byte) emission;
                    }
                    ensureRoom(tail);
                this.additionQueue[tail++] = index;
                }
            }
        }
        return tail;
    }

    /**
     * Spreads the queued levels back into the retracted area.
     *
     * @param sections the light properties of every section
     * @param queued   the amount of queued positions
     */
    private void spread(List<SectionOpacity> sections, int queued) {
        int tail = queued;

        for (int head = 0; head < tail; head++) {
            int index = this.additionQueue[head];
            int level = this.levels[index];

            if (level <= 1) {
                continue;
            }

            int x = index & MASK;
            int z = (index >> 4) & MASK;
            int y = index >> 8;
            int next = level - 1;

            for (BlockFace face : FACES) {
                int neighbourX = x + face.offsetX();
                int neighbourY = y + face.offsetY();
                int neighbourZ = z + face.offsetZ();

                if (isOutside(neighbourX, neighbourY, neighbourZ)) {
                    continue;
                }
                if (blocksFace(sections, neighbourX, neighbourY, neighbourZ, face.opposite())) {
                    continue;
                }

                int neighbourIndex = index(neighbourX, neighbourY, neighbourZ);

                if (this.levels[neighbourIndex] >= next) {
                    continue;
                }
                this.levels[neighbourIndex] = (byte) next;
                ensureRoom(tail);
                this.additionQueue[tail++] = neighbourIndex;
            }
        }
    }

    /**
     * Returns the light levels along one horizontal border of the chunk.
     * <p>
     * The result is read by the neighbouring chunk to continue the light across the border. It is
     * ordered by height first and by the remaining horizontal axis second.
     * </p>
     *
     * @param face the border to read
     * @return the level of every block along the border
     * @throws IllegalArgumentException if the given face is not horizontal
     */
    @Contract(pure = true)
    public byte[] border(BlockFace face) {
        checkHorizontal(face);
        byte[] border = new byte[this.height * LightNibbles.DIMENSION];

        for (int y = 0; y < this.height; y++) {
            for (int offset = 0; offset < LightNibbles.DIMENSION; offset++) {
                border[y * LightNibbles.DIMENSION + offset] = this.levels[borderIndex(face, y, offset)];
            }
        }
        return border;
    }

    /**
     * Feeds the light of a neighbouring chunk into this one.
     * <p>
     * Without this a light source close to the edge of a chunk lights its own chunk and stops
     * abruptly at the border, which shows up as a straight dark line every sixteen blocks. Each
     * level of the neighbour arrives one level weaker, exactly as if the two chunks had been
     * calculated together.
     * </p>
     *
     * <p>
     * The answer tells the caller whether the injection raised anything at all. An exchange over
     * several chunks repeats until every one of them reports that nothing changed, which is the
     * point at which the light of the whole area is settled.
     * </p>
     *
     * @param sections the light properties of every section of this chunk
     * @param face     the border the light enters through
     * @param border   the levels along the matching border of the neighbour
     * @return true if at least one level of this chunk was raised, otherwise false
     * @throws IllegalArgumentException if the face is not horizontal or the border has the wrong size
     */
    public boolean injectBorder(List<SectionOpacity> sections, BlockFace face, byte[] border) {
        checkHorizontal(face);

        if (border.length != this.height * LightNibbles.DIMENSION) {
            throw new IllegalArgumentException(
                    "The border of this chunk holds " + (this.height * LightNibbles.DIMENSION)
                            + " levels but the given one holds " + border.length
            );
        }

        int tail = 0;

        for (int y = 0; y < this.height; y++) {
            for (int offset = 0; offset < LightNibbles.DIMENSION; offset++) {
                int incoming = border[y * LightNibbles.DIMENSION + offset] - 1;

                if (incoming <= 0) {
                    continue;
                }

                int index = borderIndex(face, y, offset);

                if (this.levels[index] >= incoming) {
                    continue;
                }
                // The light enters through the face that lies towards the neighbour.
                if (blocksFace(sections, index & MASK, index >> 8, (index >> 4) & MASK, face)) {
                    continue;
                }
                this.levels[index] = (byte) incoming;
                ensureRoom(tail);
                this.additionQueue[tail++] = index;
            }
        }
        spread(sections, tail);
        return tail > 0;
    }

    /**
     * Calculates the index of a block which lies on the given border.
     *
     * @param face   the border the block lies on
     * @param y      the y coordinate inside the column
     * @param offset the position along the remaining horizontal axis
     * @return the index of the block
     */
    @Contract(pure = true)
    private static int borderIndex(BlockFace face, int y, int offset) {
        return switch (face) {
            case WEST -> index(0, y, offset);
            case EAST -> index(LightNibbles.DIMENSION - 1, y, offset);
            case NORTH -> index(offset, y, 0);
            case SOUTH -> index(offset, y, LightNibbles.DIMENSION - 1);
            case TOP, BOTTOM -> throw new IllegalArgumentException("The face " + face + " is not horizontal");
        };
    }

    /**
     * Verifies that the given face describes a horizontal border.
     *
     * @param face the face to check
     * @throws IllegalArgumentException if the face is not horizontal
     */
    private static void checkHorizontal(BlockFace face) {
        if (face == BlockFace.TOP || face == BlockFace.BOTTOM) {
            throw new IllegalArgumentException(
                    "Only a horizontal border is shared between two chunks but " + face + " was given"
            );
        }
    }

    /**
     * Returns the stored light as one light section per section of the chunk.
     *
     * @return the light of every section
     */
    @Contract(pure = true)
    public List<LightNibbles> toSections() {
        List<LightNibbles> result = new ArrayList<>(this.sectionCount);

        for (int section = 0; section < this.sectionCount; section++) {
            int base = section * LightNibbles.DIMENSION;
            LightNibbles nibbles = LightNibbles.uniform(0);
            boolean uniform = true;
            int first = this.levels[index(0, base, 0)];

            for (int y = 0; y < LightNibbles.DIMENSION; y++) {
                for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                    for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                        int level = this.levels[index(x, base + y, z)];

                        if (level != first) {
                            uniform = false;
                        }
                        if (level != 0) {
                            nibbles.set(x, y, z, level);
                        }
                    }
                }
            }
            result.add(uniform ? LightNibbles.uniform(first) : nibbles);
        }
        return result;
    }

    /**
     * Checks whether light cannot enter the given position through the given face.
     *
     * @param sections the light properties of every section
     * @param x        the x coordinate inside the chunk
     * @param y        the y coordinate inside the column
     * @param z        the z coordinate inside the chunk
     * @param face     the face light would enter through
     * @return true if light cannot pass the face, otherwise false
     */
    private static boolean blocksFace(List<SectionOpacity> sections, int x, int y, int z, BlockFace face) {
        return sections.get(y >> 4).blocksFace(x, y & MASK, z, face);
    }

    /**
     * Checks whether the given position lies outside of the column.
     *
     * @param x the x coordinate to check
     * @param y the y coordinate to check
     * @param z the z coordinate to check
     * @return true if the position is outside of the column, otherwise false
     */
    private boolean isOutside(int x, int y, int z) {
        return (x | y | z) < 0 || x >= LightNibbles.DIMENSION || z >= LightNibbles.DIMENSION || y >= this.height;
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

    /**
     * Calculates the index of a column of the chunk.
     *
     * @param x the x coordinate inside the chunk
     * @param z the z coordinate inside the chunk
     * @return the index of the column
     */
    @Contract(pure = true)
    private static int column(int x, int z) {
        return (z << 4) | x;
    }
}
