package net.minestom.server.instance;

import net.minestom.server.entity.Entity;
import net.onelitefeather.falco.benchmark.support.MinestomChunks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link ChunkViewerCacheLeakTest} class establishes that constructing a chunk for an
 * {@code InstanceContainer} leaves an entry in the viewer cache of its entity tracker that nothing
 * ever removes, and that the same construction for a {@code FalcoInstance} does not.
 *
 * <h2>Why this test exists</h2>
 * <p>
 * It was written to explain a benchmark result rather than to look for a defect.
 * {@code ChunkComparisonBenchmark} reported {@code FalcoChunk#copy} as twenty to forty times faster
 * than {@code DynamicChunk#copy} while every other measured operation of the two agreed to the
 * decimal, which cannot be true of the code: the two implementations differ only in that the Falco
 * one carries over its tickable bookkeeping as well, so it does strictly more work — at the time a
 * whole {@code Int2ObjectOpenHashMap} copy, today the single {@code int} that replaced it, which
 * makes the argument weaker in magnitude and no different in direction. A difference of that
 * size with no cause in the code means the two arms are not measuring the same thing, and the
 * benchmark had to be disqualified until the cause was named. This is the cause.
 * </p>
 *
 * <h2>The mechanism</h2>
 * <p>
 * Every chunk asks the entity tracker of its instance for a {@code Viewable} while it is being
 * constructed, and hands it the shared instances of that instance to key the lookup by:
 * </p>
 * <pre>{@code
 * // Chunk
 * final List<SharedInstance> shared = instance instanceof InstanceContainer instanceContainer ?
 *         instanceContainer.getSharedInstances() : List.of();
 * this.viewable = instance.getEntityTracker().viewable(shared, chunkX, chunkZ);
 *
 * // EntityTrackerImpl
 * return entry.viewers.computeIfAbsent(new ChunkViewKey(sharedInstances, chunkX, chunkZ), ChunkView::new);
 *
 * // EntityTrackerImpl.ChunkViewKey — the list is compared by identity, not by value
 * return sharedInstances == instances && chunkX == x && chunkZ == z;
 * }</pre>
 * <p>
 * {@code InstanceContainer#getSharedInstances} returns {@code Collections.unmodifiableList(...)},
 * which is a fresh wrapper on every call. A key built from it is therefore never {@code equals} to a
 * key already in the map, so {@code computeIfAbsent} inserts every single time and the map grows
 * without bound. An instance that is not an {@code InstanceContainer} is handed {@code List.of()}
 * instead, and because that is one shared immutable singleton the identity comparison succeeds and
 * the entry is reused.
 * </p>
 * <p>
 * The growth is not the whole cost. {@code ChunkViewKey} is a record that overrides {@code equals}
 * and does not override {@code hashCode}, so the generated value based {@code hashCode} is still in
 * force. Two empty lists hash alike, which means every leaked key of a given chunk position hashes
 * into the same bin while none of them compares equal to another. The map therefore does not merely
 * grow, it grows into one bin, and each insertion has to walk what is already there.
 * </p>
 *
 * <h2>What this does and does not say</h2>
 * <p>
 * It says that the leak exists, that it is linear in the amount of chunk constructions, and that
 * {@code FalcoInstance} does not trigger it. It does not say that Falco solved it: Falco escapes it
 * for the single reason that it is not an {@code InstanceContainer}, and its own unload path does
 * not clear the entry either, so a Falco world still keeps one entry per chunk position for the life
 * of the process. That is a far smaller quantity than one per construction, and it is not nothing.
 * </p>
 *
 * <h2>Running it</h2>
 * <pre>{@code
 * ./gradlew :falco-benchmarks:test --tests "*ChunkViewerCacheLeakTest*" -i
 * }</pre>
 *
 * @author TheMeinerLP
 * @version 1.0.1
 * @since 0.4.0
 */
@DisplayName("The viewer cache an instance keeps for its chunks")
class ChunkViewerCacheLeakTest {

    /**
     * The chunk position every constructed chunk is placed at.
     * <p>
     * One position for all of them on purpose. A leak that is keyed by position would be
     * indistinguishable from correct behaviour if every chunk sat somewhere else, because one entry
     * per position is exactly what the cache is for. Holding the position still makes every entry
     * after the first one a leaked entry by definition.
     * </p>
     */
    private static final int POSITION = 0;

    /**
     * The amounts of chunk constructions the growth is sampled at.
     * <p>
     * Three points rather than one, because the claim is that the growth is linear rather than that
     * it is nonzero. A single sample cannot tell an unbounded leak from a bounded overhead.
     * </p>
     */
    private static final int[] SAMPLES = {16, 160, 1600};

    /**
     * Reads how many entries the viewer cache of an instance holds.
     *
     * @param instance the instance to read from
     * @return the amount of cached views
     */
    private static int viewerCacheSize(Instance instance) {
        final EntityTrackerImpl tracker = (EntityTrackerImpl) instance.getEntityTracker();
        final EntityTrackerImpl.TargetEntry<Entity> entry =
                tracker.targetEntries[EntityTracker.Target.PLAYERS.ordinal()];

        return entry.viewers.size();
    }

    /**
     * Constructs the given amount of chunks at the same position and reports the growth of the cache.
     *
     * @param instance the instance to construct the chunks for
     * @param amount   the amount of chunks to construct
     * @return the amount of entries the construction added to the cache
     */
    private static int growthOver(Instance instance, int amount) {
        final int before = viewerCacheSize(instance);

        for (int index = 0; index < amount; index++) {
            final Chunk chunk = MinestomChunks.newChunk(instance, POSITION, POSITION);

            // Without this the JIT is free to drop a construction whose result is never read, and
            // the test would report the absence of a leak it simply never triggered.
            assertTrue(chunk.getChunkX() == POSITION, "the constructed chunk is at the sampled position");
        }
        return viewerCacheSize(instance) - before;
    }

    /**
     * The tests that show the container leaking.
     */
    @Nested
    @DisplayName("for an InstanceContainer")
    class ForAContainer {

        /**
         * Establishes that the cache grows by one entry per construction, without bound.
         */
        @Test
        @DisplayName("grows by one entry per constructed chunk and never shrinks")
        void testTheCacheGrowsWithEveryConstruction() {
            final InstanceContainer container = MinestomChunks.newContainer();

            try {
                final StringBuilder report = new StringBuilder("viewer cache of an InstanceContainer\n");

                for (int sample : SAMPLES) {
                    final int growth = growthOver(container, sample);

                    report.append(String.format("  %6d constructions -> %6d new entries%n", sample, growth));
                    assertEquals(sample, growth, "every construction leaks exactly one entry, so the growth "
                            + "equals the amount of constructions");
                }
                System.out.println(report);
            } finally {
                MinestomChunks.release(container);
            }
        }
    }

    /**
     * The tests that show a Falco instance escaping the leak.
     */
    @Nested
    @DisplayName("for a FalcoInstance")
    class ForAFalcoInstance {

        /**
         * Establishes that repeated construction at one position adds at most the one entry the
         * cache exists for.
         */
        @Test
        @DisplayName("holds one entry per position no matter how often a chunk is constructed")
        void testTheCacheStaysBounded() {
            final Instance falco = MinestomChunks.newFalcoInstance();

            try {
                final StringBuilder report = new StringBuilder("viewer cache of a FalcoInstance\n");
                int total = 0;

                for (int sample : SAMPLES) {
                    final int growth = growthOver(falco, sample);

                    total += growth;
                    report.append(String.format("  %6d constructions -> %6d new entries%n", sample, growth));
                }
                System.out.println(report);
                assertTrue(total <= 1, "one position can need at most one cached view, but the cache grew by "
                        + total + " entries");
            } finally {
                MinestomChunks.release(falco);
            }
        }
    }
}
