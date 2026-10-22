package net.onelitefeather.falco.light;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.palette.Palette;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/**
 * The {@link ChunkLightService} class calculates the block light of a chunk and hands the result to
 * the sections of that chunk.
 * <p>
 * The service is the connection between the engine and a running server. It reads the block states
 * of every section, runs the propagation and writes the result back through
 * {@link net.minestom.server.instance.light.Light#set(byte[])}. That method belongs to the stable
 * part of the light interface, which is why the service uses it instead of implementing the
 * interface itself: the calculation methods of that interface are marked internal and their
 * signatures may change between server versions.
 * </p>
 * <p>
 * Because the result is handed over through the regular interface, the service works with any chunk
 * of any loader, including chunks produced by the Anvil loader of Falco or the one of the server.
 * </p>
 * <p>
 * Writing the light through {@code set} also clears the update flag of the section, so the server
 * does not recompute what was just calculated.
 * </p>
 * <p>
 * A single instance may be used by as many threads as one likes. Beyond the source it was built
 * with, which only answers questions about a block and never changes, the service holds only idle
 * propagators, and a call takes one for itself alone, so two calls cannot reach each other. That matters more
 * here than elsewhere: a server lights the chunks around its players in parallel and keeps one
 * service for the whole instance, and because {@code set} clears the update flag, a result which two
 * threads had corrupted would never be recomputed. The world would simply carry wrong light.
 * </p>
 * <p>
 * <b>What that sentence covers, and what it does not.</b> It is about this object: two calls share
 * no state <em>inside</em> the service. They can still share state outside it, namely the chunks
 * they read, and {@link #calculateWithNeighbours(Instance, int, int)} reads nine of them. Two
 * threads lighting <em>overlapping</em> neighbourhoods therefore have one reading a chunk the other
 * is writing. Nothing is corrupted — every write holds the write lock of its chunk — but a result
 * can be committed from a read that was already stale by the time it was written, which a player
 * sees as a seam rather than as an error, and which {@code set} then makes permanent.
 * </p>
 * <p>
 * Keeping neighbourhoods disjoint is therefore the caller's job, and there is a ready answer for
 * anyone who does not want it: {@link ChunkLightScheduler} never submits overlapping work. A chunk
 * being computed stays marked but is not submitted again, and a chunk whose mark count moved while
 * its area ran has its result discarded rather than written. Calling this method directly is the
 * path that carries the obligation.
 * </p>
 * <p>
 * The working state of a call is the propagator, which keeps buffers for a whole column. A call
 * takes one from a pool, and returns it when it is done, so no two calls ever hold the same one at
 * once. The pool keeps the parallelism this service exists to allow — a thread which finds it empty
 * builds a new propagator rather than waiting — and spares a server the buffers of one propagator
 * per lit chunk, which used to be the largest allocation of every call.
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
public final class ChunkLightService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkLightService.class);

    /**
     * The amount of chunks the exchanged area reaches beyond the chunk in the middle.
     * A level of fifteen cannot survive more than one chunk of travel, so a further ring could not
     * receive anything the middle chunk sends out.
     */
    private static final int NEIGHBOURHOOD_RADIUS = 1;

    /**
     * The edge length of the exchanged area in chunks.
     */
    private static final int NEIGHBOURHOOD_SIZE = NEIGHBOURHOOD_RADIUS * 2 + 1;

    /**
     * The amount of exchange rounds after which the exchange gives up.
     * A level drops by one per chunk border at the very least, so fifteen rounds are enough for
     * every reachable level and the cap only protects against a case which should not exist.
     */
    private static final int MAX_EXCHANGE_ROUNDS = 16;

    private final BlockLightSource source;

    /**
     * The propagators no call is currently using.
     * <p>
     * A propagator holds buffers for a whole column, several hundred kilobytes for a tall world. A
     * ThreadLocal would not keep them, because a scheduler runs every area on a virtual thread of its
     * own, so they are kept here instead. The queue never grows beyond the amount of calls that ran
     * at the same time, which is what a server already bounds by the processor count.
     * </p>
     */
    private final Queue<ChunkLightPropagator> propagators = new ConcurrentLinkedQueue<>();

    /**
     * Creates a service which reads the block properties from the registry of the server.
     */
    public ChunkLightService() {
        this(new MinestomBlockLightSource());
    }

    /**
     * Creates a service which reads the block properties from the given source.
     *
     * @param source the source which describes the light properties of a block
     */
    public ChunkLightService(BlockLightSource source) {
        this.source = source;
    }

    /**
     * Calculates the block light of the given chunk and stores it in its sections.
     * <p>
     * The block states are read under the read lock of the chunk, the propagation runs without any
     * lock, and only the transfer of the result is guarded again. The expensive part therefore
     * never blocks another user of the chunk.
     * </p>
     *
     * @param chunk the chunk to light
     */
    public void calculate(Chunk chunk) {
        List<SectionOpacity> opacity = opacityOf(chunk);
        applyLight(chunk, withPropagator(propagator -> propagator.propagate(opacity)), false);
    }

    /**
     * Runs the given work with a propagator nobody else is using during the call.
     * <p>
     * The propagator is taken from the pool, or built if the pool is empty, and returned afterwards
     * even if the work fails. The work must not keep it beyond the call, since the next caller
     * overwrites its buffers.
     * </p>
     *
     * @param work the work to run
     * @param <T>  the type of the result
     * @return what the work returned
     */
    <T> T withPropagator(Function<ChunkLightPropagator, T> work) {
        @Nullable ChunkLightPropagator pooled = this.propagators.poll();
        ChunkLightPropagator propagator = pooled != null ? pooled : new ChunkLightPropagator();

        try {
            return work.apply(propagator);
        } finally {
            this.propagators.offer(propagator);
        }
    }

    /**
     * Builds the opacity table of every section of the given chunk.
     * <p>
     * This is one half of the seam a scheduler builds on. Reading the block states of a chunk and
     * turning them into opacity tables is the expensive part of the whole operation, and anything
     * that lights more than one chunk at a time has to do it once per chunk rather than once per
     * calculation. Exposing the step keeps that code in one place instead of copying it into every
     * caller that needs a {@link ChunkLightState} of its own.
     * </p>
     *
     * @param chunk the chunk to read
     * @return the opacity table of every section
     */
    public List<SectionOpacity> opacityOf(Chunk chunk) {
        List<int[]> states = readStates(chunk);
        List<SectionOpacity> opacity = new ArrayList<>(states.size());

        for (int[] section : states) {
            opacity.add(SectionOpacity.of(section, this.source));
        }
        return opacity;
    }

    /**
     * Writes the calculated light into the sections of the given chunk.
     * <p>
     * This is the other half of the seam a scheduler builds on. A caller which computed the light
     * of several chunks together hands every result over through this method, so the write path is
     * the same one the service itself uses and a change to it cannot fall out of step.
     * </p>
     * <p>
     * The write goes through {@link net.minestom.server.instance.light.Light#set(byte[])}, which
     * also clears the update flag of the section. The server therefore does not recompute what was
     * written here — a wrong result is never corrected on its own, which is why every caller of
     * this method is covered by a test.
     * </p>
     *
     * @param chunk the chunk which receives the light
     * @param light the calculated light of every section
     * @param sky   whether the sky light is written instead of the block light
     */
    public static void applyLight(Chunk chunk, List<LightNibbles> light, boolean sky) {
        chunk.lockWriteLock();
        try {
            List<Section> sections = chunk.getSections();

            for (int index = 0; index < sections.size() && index < light.size(); index++) {
                byte[] array = light.get(index).toDenseArray();
                Section section = sections.get(index);

                if (sky) {
                    section.skyLight().set(array);
                    continue;
                }
                section.blockLight().set(array);
            }
        } finally {
            chunk.unlockWriteLock();
        }
    }

    /**
     * Calculates the sky light of the given chunk and stores it in its sections.
     *
     * @param chunk the chunk to light
     */
    public void calculateSky(Chunk chunk) {
        List<SectionOpacity> opacity = opacityOf(chunk);
        List<LightNibbles> light = withPropagator(propagator -> propagator.propagateSky(opacity));
        applyLight(chunk, light, true);
    }

    /**
     * Calculates the light of the given chunk from the chunks around it and writes that one chunk.
     * <p>
     * A chunk which is lit on its own ends its light at the border, which shows up as a straight
     * dark line every sixteen blocks. Handing the border to the direct neighbours once is not
     * enough either, because light which enters a neighbour has to leave it again on another side
     * to reach the chunk behind it, which is what a source in a corner does.
     * </p>
     * <p>
     * The exchange therefore repeats over the whole area until no chunk of it raises a level any
     * more. Every injection only ever raises levels, so the repetition walks towards a fixed point
     * and reaches the same result regardless of the order the borders are handed over in. A cap on
     * the amount of rounds keeps a case which should not exist from looping forever; hitting it is
     * reported instead of silently accepted.
     * </p>
     * <p>
     * <b>The eight chunks around the middle are read but never written.</b> They only exchanged
     * light inside the 3×3, so whatever they legitimately receive from further out is missing from
     * their result and writing it back would replace their correct light with a darker one. The
     * middle chunk does not have that problem, and provably so: a source outside the 3×3 is at
     * least seventeen blocks away from it and no path can be shorter than the direct distance, so
     * not even a level of fifteen survives the trip. Writing only the middle chunk is therefore
     * both cheaper and correct, and it is the same rule {@link ChunkLightArea} follows.
     * </p>
     * <p>
     * What this method still has over an area of one chunk is its reach: the 3×3 includes the four
     * diagonal chunks, and a source in one of them arrives in the middle chunk through the chunk
     * between them. An area reads only the chunks which share a border with it, so it does not see
     * that source. Where several connected chunks are lit at once, {@link ChunkLightArea} is the
     * cheaper path, because it reads every chunk once instead of once per neighbourhood.
     * </p>
     * <p>
     * Only chunks which the instance already holds take part. A neighbour which is not loaded is
     * skipped rather than loaded, because lighting a chunk must not pull a world into memory.
     * </p>
     * <p>
     * <b>Two threads must not run this for overlapping neighbourhoods.</b> The nine chunks are read
     * one after another, so a neighbour read here may be a chunk another call is writing, and the
     * result is then committed from bytes that were already stale — visible as a seam, never as an
     * error, and permanent because writing light clears the update flag of the section. The service
     * itself is thread-safe; the chunks are what is shared. {@link ChunkLightScheduler} keeps the
     * neighbourhoods disjoint for anyone who would rather not.
     * </p>
     *
     * @param instance the instance which holds the chunk and its neighbours
     * @param chunkX   the chunk x coordinate
     * @param chunkZ   the chunk z coordinate
     */
    public void calculateWithNeighbours(Instance instance, int chunkX, int chunkZ) {
        if (instance.getChunk(chunkX, chunkZ) == null) {
            return;
        }

        @Nullable NeighbourhoodEntry[] neighbourhood = readNeighbourhood(instance, chunkX, chunkZ);
        exchangeUntilSettled(neighbourhood, chunkX, chunkZ);

        @Nullable NeighbourhoodEntry middle = neighbourhood[slot(0, 0)];

        if (middle == null) {
            return;
        }
        applyLight(middle.chunk(), middle.state().toSections(), false);
    }

    /**
     * Reads every already loaded chunk of the exchanged area and lights it on its own.
     * <p>
     * The opacity table of a chunk is built here and nowhere else, because resolving the block
     * states of a chunk is the expensive part of the whole operation and the exchange visits the
     * same chunk many times.
     * </p>
     *
     * @param instance the instance which holds the chunks
     * @param chunkX   the chunk x coordinate of the middle of the area
     * @param chunkZ   the chunk z coordinate of the middle of the area
     * @return one entry per position of the area, empty where no chunk is loaded
     */
    private @Nullable NeighbourhoodEntry[] readNeighbourhood(Instance instance, int chunkX, int chunkZ) {
        @Nullable NeighbourhoodEntry[] neighbourhood = new NeighbourhoodEntry[NEIGHBOURHOOD_SIZE * NEIGHBOURHOOD_SIZE];

        for (int offsetZ = -NEIGHBOURHOOD_RADIUS; offsetZ <= NEIGHBOURHOOD_RADIUS; offsetZ++) {
            for (int offsetX = -NEIGHBOURHOOD_RADIUS; offsetX <= NEIGHBOURHOOD_RADIUS; offsetX++) {
                Chunk chunk = instance.getChunk(chunkX + offsetX, chunkZ + offsetZ);

                if (chunk == null) {
                    continue;
                }

                List<SectionOpacity> opacity = opacityOf(chunk);
                neighbourhood[slot(offsetX, offsetZ)] =
                        new NeighbourhoodEntry(chunk, opacity, withPropagator(propagator -> ChunkLightState.blockLight(opacity, propagator)));
            }
        }
        return neighbourhood;
    }

    /**
     * Repeats the border exchange over the given area until nothing changes any more.
     *
     * @param neighbourhood the chunks of the exchanged area
     * @param chunkX        the chunk x coordinate of the middle of the area
     * @param chunkZ        the chunk z coordinate of the middle of the area
     */
    private static void exchangeUntilSettled(@Nullable NeighbourhoodEntry[] neighbourhood, int chunkX, int chunkZ) {
        // The loaded chunks are numbered in slot order, which walks the area in a fixed order so
        // that two runs over the same chunks do the same work in the same sequence.
        int[] indices = new int[neighbourhood.length];
        List<ChunkLightState> states = new ArrayList<>(neighbourhood.length);
        List<List<SectionOpacity>> opacity = new ArrayList<>(neighbourhood.length);

        for (int slot = 0; slot < neighbourhood.length; slot++) {
            @Nullable NeighbourhoodEntry entry = neighbourhood[slot];
            indices[slot] = entry == null ? -1 : states.size();

            if (entry != null) {
                states.add(entry.state());
                opacity.add(entry.opacity());
            }
        }

        int[] neighbours = new int[states.size() * BorderExchange.FACES.length];

        for (int offsetZ = -NEIGHBOURHOOD_RADIUS; offsetZ <= NEIGHBOURHOOD_RADIUS; offsetZ++) {
            for (int offsetX = -NEIGHBOURHOOD_RADIUS; offsetX <= NEIGHBOURHOOD_RADIUS; offsetX++) {
                int chunk = indices[slot(offsetX, offsetZ)];

                if (chunk < 0) {
                    continue;
                }

                for (int face = 0; face < BorderExchange.FACES.length; face++) {
                    int neighbourX = offsetX + BorderExchange.FACES[face].offsetX();
                    int neighbourZ = offsetZ + BorderExchange.FACES[face].offsetZ();
                    neighbours[chunk * BorderExchange.FACES.length + face] =
                            isOutside(neighbourX, neighbourZ) ? -1 : indices[slot(neighbourX, neighbourZ)];
                }
            }
        }

        if (!new BorderExchange(states, opacity, neighbours).settle(MAX_EXCHANGE_ROUNDS)) {
            LOGGER.warn(
                    "The light of a chunk and its neighbours did not settle after {} exchange rounds chunk=[{},{}]",
                    MAX_EXCHANGE_ROUNDS, chunkX, chunkZ
            );
        }
    }

    /**
     * Calculates the position of a chunk inside the exchanged area.
     *
     * @param offsetX the chunk x offset from the middle of the area
     * @param offsetZ the chunk z offset from the middle of the area
     * @return the position of the chunk inside the area
     */
    @Contract(pure = true)
    private static int slot(int offsetX, int offsetZ) {
        return (offsetZ + NEIGHBOURHOOD_RADIUS) * NEIGHBOURHOOD_SIZE + (offsetX + NEIGHBOURHOOD_RADIUS);
    }

    /**
     * Checks whether the given offset lies outside of the exchanged area.
     *
     * @param offsetX the chunk x offset from the middle of the area
     * @param offsetZ the chunk z offset from the middle of the area
     * @return true if the offset is outside of the area, otherwise false
     */
    @Contract(pure = true)
    private static boolean isOutside(int offsetX, int offsetZ) {
        return Math.abs(offsetX) > NEIGHBOURHOOD_RADIUS || Math.abs(offsetZ) > NEIGHBOURHOOD_RADIUS;
    }

    /**
     * The {@link NeighbourhoodEntry} record holds everything the exchange needs about one chunk of
     * the area, so neither its block states nor its opacity tables are read a second time.
     *
     * @param chunk   the chunk the entry belongs to
     * @param opacity the light properties of every section of the chunk
     * @param state   the light of the chunk as it is exchanged
     */
    private record NeighbourhoodEntry(
            Chunk chunk,
            List<SectionOpacity> opacity,
            ChunkLightState state
    ) {
    }

    /**
     * Returns the block light level which is stored for the given position.
     *
     * @param chunk the chunk which holds the position
     * @param x     the x coordinate inside the chunk
     * @param y     the y coordinate of the block
     * @param z     the z coordinate inside the chunk
     * @return the stored light level of the position
     */
    @Contract(pure = true)
    public int blockLightAt(Chunk chunk, int x, int y, int z) {
        chunk.lockReadLock();
        try {
            return chunk.getSectionAt(y).blockLight().getLevel(x & 15, y & 15, z & 15);
        } finally {
            chunk.unlockReadLock();
        }
    }

    /**
     * Reads the block state of every block of every section of the chunk.
     *
     * @param chunk the chunk to read
     * @return the state ids of every section, ordered from the lowest section upwards
     */
    private static List<int[]> readStates(Chunk chunk) {
        chunk.lockReadLock();
        try {
            List<Section> sections = chunk.getSections();
            List<int[]> states = new ArrayList<>(sections.size());

            for (Section section : sections) {
                int[] blocks = new int[LightNibbles.BLOCK_COUNT];
                Palette palette = section.blockPalette();
                palette.getAll((x, y, z, value) -> blocks[(y << 8) | (z << 4) | x] = value);
                states.add(blocks);
            }
            return states;
        } finally {
            chunk.unlockReadLock();
        }
    }
}
