package net.onelitefeather.falco.light;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The {@link ChunkArea} record names one chunk by its coordinates and cuts a set of such chunks
 * into the groups which are lit together.
 * <p>
 * Lighting one chunk on its own leaves a seam at every border, and lighting each chunk with its own
 * eight neighbours reads the same block states nine times over. The middle ground is to take the
 * chunks which actually changed, find the connected groups among them, and light each group as a
 * unit. This type is that middle step, and it is deliberately nothing else: no chunk, no instance,
 * no light. It is coordinate arithmetic, so the two rules below can be verified without a server.
 * </p>
 * <p>
 * <b>A corner is not a border.</b> Light leaves a chunk through one of its four faces; two chunks
 * which meet only at a corner never hand each other a border and therefore have no reason to be
 * computed together. Joining them would enlarge areas for nothing.
 * </p>
 * <p>
 * <b>An area has an upper bound, and the bound is not optional.</b> Lighting an area keeps one
 * {@link ChunkLightState} per chunk, and such a state is roughly 980 KB of buffers. A build spread
 * across a hundred connected chunks would form a single area of about a hundred megabytes plus the
 * ring around it, which is a memory profile no server can absorb inside a tick. The flood fill
 * therefore stops adding to an area once it holds the maximum, and the chunks which are left over
 * start the next one. The seam between the two parts settles on the following tick, because each
 * part reads the other as part of its ring.
 * </p>
 * <p>
 * This type is experimental. The light engine is new and its API may still change.
 * </p>
 *
 * @param x the chunk x coordinate
 * @param z the chunk z coordinate
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public record ChunkArea(int x, int z) {

    /**
     * The four directions a chunk shares a border with, as offsets on the x and the z axis.
     */
    private static final int[][] FACE_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Groups the given chunks into connected areas which do not exceed the given size.
     * <p>
     * The walk is a breadth first flood fill: it takes any chunk which has not been visited yet,
     * follows the face neighbours which are dirty as well, and closes the area once it holds
     * {@code maxSize} chunks. Whatever the area could not take is picked up by the next round, so
     * every given chunk ends up in exactly one area and none is visited twice.
     * </p>
     *
     * @param dirty   the chunks which have to be lit
     * @param maxSize the largest amount of chunks a single area may hold
     * @return one list per area, each holding the chunks of that area
     * @throws IllegalArgumentException if the given maximum size is smaller than one
     */
    @Contract(pure = true)
    public static List<List<ChunkArea>> group(Collection<ChunkArea> dirty, int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("An area has to be able to hold at least one chunk but the cap was " + maxSize);
        }

        Set<ChunkArea> remaining = new HashSet<>(dirty);
        List<List<ChunkArea>> areas = new ArrayList<>();

        for (ChunkArea start : dirty) {
            if (!remaining.remove(start)) {
                continue;
            }
            areas.add(fill(start, remaining, maxSize));
        }
        return areas;
    }

    /**
     * Collects one area, starting at the given chunk.
     * <p>
     * Every chunk which enters the area is removed from the remaining set at once rather than when
     * it is taken off the queue. Without that, a chunk reachable from two sides would be queued
     * twice and would appear twice in the result.
     * </p>
     *
     * @param start     the chunk the area starts at, already removed from the remaining set
     * @param remaining the chunks which have not been assigned to an area yet
     * @param maxSize   the largest amount of chunks the area may hold
     * @return the chunks of the area
     */
    private static List<ChunkArea> fill(ChunkArea start, Set<ChunkArea> remaining, int maxSize) {
        List<ChunkArea> area = new ArrayList<>();
        Deque<ChunkArea> queue = new ArrayDeque<>();

        queue.add(start);
        area.add(start);

        while (!queue.isEmpty() && area.size() < maxSize) {
            ChunkArea current = queue.removeFirst();

            for (int[] offset : FACE_OFFSETS) {
                if (area.size() == maxSize) {
                    return area;
                }

                ChunkArea neighbour = new ChunkArea(current.x + offset[0], current.z + offset[1]);

                if (!remaining.remove(neighbour)) {
                    continue;
                }
                queue.add(neighbour);
                area.add(neighbour);
            }
        }
        return area;
    }

    /**
     * Returns the chunk which lies on the other side of the given face.
     *
     * @param face the horizontal face to cross
     * @return the chunk behind that face
     */
    @Contract(pure = true)
    public ChunkArea neighbour(BlockFace face) {
        return new ChunkArea(this.x + face.offsetX(), this.z + face.offsetZ());
    }
}
