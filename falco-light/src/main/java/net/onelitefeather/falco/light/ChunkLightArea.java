package net.onelitefeather.falco.light;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The {@link ChunkLightArea} class computes the light of one connected group of chunks in a single
 * pass and writes the result back to that group alone.
 * <p>
 * <b>The ring is read but never written.</b> An area whose edge chunks knew nothing about their
 * neighbours would end at a straight dark line every sixteen blocks, so every chunk which shares a
 * border with the area is read as well and takes part in the exchange. Writing those chunks back is
 * the tempting next step and it is wrong: a ring chunk only saw the light of the area, never the
 * light of whatever lies on its own far side, so its result is darker than its current one. That is
 * exactly the defect {@link ChunkLightService#calculateWithNeighbours(Instance, int, int)} still
 * has, where all nine chunks of the neighbourhood are written and the eight outer ones are darkened
 * in the process. Here it is designed out: the ring is scratch data.
 * </p>
 * <p>
 * Computing a group together rather than one chunk after the other is the entire reason this type
 * exists. Reading the block states of a chunk and turning them into opacity tables is the expensive
 * part of lighting, and a per-chunk neighbourhood reads every chunk up to nine times. An area reads
 * each of its chunks once and each of its ring chunks once, no matter how many chunks the area
 * holds.
 * </p>
 * <p>
 * A computation keeps no state beyond the call, so one instance may serve as many threads as one
 * likes — the same property {@link ChunkLightService} has and for the same reason. Two threads
 * computing <em>overlapping</em> areas is a different matter and is not made safe here; the
 * scheduler is what keeps that from happening.
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
public final class ChunkLightArea {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkLightArea.class);

    private static final BlockFace[] HORIZONTAL_FACES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};

    /**
     * The amount of exchange rounds after which the exchange gives up.
     * A level drops by at least one per chunk border, so sixteen rounds carry every level which can
     * exist and the cap only protects against a case which should not occur.
     */
    private static final int MAX_EXCHANGE_ROUNDS = 16;

    /**
     * A caller which wants every chunk of the area written back.
     */
    private static final Predicate<ChunkArea> ALWAYS = area -> true;

    private final ChunkLightService service;

    /**
     * Creates an area computation which reads and writes through the given service.
     *
     * @param service the service which builds the opacity tables and writes the light back
     */
    public ChunkLightArea(ChunkLightService service) {
        this.service = service;
    }

    /**
     * Computes the light of the given area and writes it into the chunks of that area.
     *
     * @param instance the instance which holds the chunks
     * @param area     the chunks which are computed together and written back
     * @param sky      whether the sky light is computed instead of the block light
     * @return the chunks whose light was written, in the order they were given
     */
    public List<ChunkArea> compute(Instance instance, List<ChunkArea> area, boolean sky) {
        return compute(instance, area, sky, ALWAYS);
    }

    /**
     * Computes the light of the given area and writes back the chunks the caller still wants.
     * <p>
     * The predicate is asked once per chunk, directly before that chunk is written, and it is the
     * hook the scheduler uses to drop a result which has gone stale. A chunk which changed while
     * the area was being computed carries a result built from block states that no longer exist,
     * and writing it would clear the update flag of its sections on the basis of that stale read.
     * Such a chunk is therefore discarded whole. The other chunks of the same area are untouched by
     * this and are written normally, because a change in one chunk cannot invalidate more than the
     * ring around it and the next pass covers that.
     * </p>
     *
     * @param instance the instance which holds the chunks
     * @param area     the chunks which are computed together
     * @param sky      whether the sky light is computed instead of the block light
     * @param wanted   answers for each chunk whether its result may still be written
     * @return the chunks whose light was written, in the order they were given
     */
    public List<ChunkArea> compute(Instance instance, List<ChunkArea> area, boolean sky, Predicate<ChunkArea> wanted) {
        Set<ChunkArea> inside = new LinkedHashSet<>(area);
        Map<ChunkArea, Entry> entries = read(instance, inside, sky);

        if (entries.isEmpty()) {
            return List.of();
        }
        exchangeUntilSettled(entries);
        return write(entries, inside, wanted, sky);
    }

    /**
     * Reads every loaded chunk of the area and of the ring around it, and lights each on its own.
     * <p>
     * A coordinate the instance holds no chunk for is skipped rather than loaded. Lighting must not
     * pull a world into memory, and a chunk nobody has asked for has no viewer whose light could be
     * wrong.
     * </p>
     *
     * @param instance the instance which holds the chunks
     * @param inside   the chunks of the area
     * @param sky      whether the sky light is computed instead of the block light
     * @return one entry per loaded chunk of the area and of its ring
     */
    private Map<ChunkArea, Entry> read(Instance instance, Set<ChunkArea> inside, boolean sky) {
        Set<ChunkArea> wanted = new LinkedHashSet<>(inside);

        for (ChunkArea position : inside) {
            for (BlockFace face : HORIZONTAL_FACES) {
                wanted.add(position.neighbour(face));
            }
        }

        Map<ChunkArea, Entry> entries = new HashMap<>(wanted.size());

        for (ChunkArea position : wanted) {
            @Nullable Chunk chunk = instance.getChunk(position.x(), position.z());

            if (chunk == null) {
                continue;
            }

            List<SectionOpacity> opacity = this.service.opacityOf(chunk);
            ChunkLightState state = sky ? ChunkLightState.skyLight(opacity) : ChunkLightState.blockLight(opacity);

            entries.put(position, new Entry(chunk, opacity, state));
        }
        return entries;
    }

    /**
     * Repeats the border exchange over the whole area until nothing changes any more.
     * <p>
     * Every injection only ever raises a level, so the repetition walks towards a fixed point and
     * arrives at the same result no matter which order the borders are handed over in. The cap on
     * the rounds keeps a case which should not exist from looping forever, and hitting it is
     * reported rather than silently accepted.
     * </p>
     *
     * @param entries the chunks of the area and of its ring
     */
    private static void exchangeUntilSettled(Map<ChunkArea, Entry> entries) {
        for (int round = 0; round < MAX_EXCHANGE_ROUNDS; round++) {
            if (!exchange(entries)) {
                return;
            }
        }
        LOGGER.warn("The light of an area of {} chunks did not settle after {} exchange rounds",
                entries.size(), MAX_EXCHANGE_ROUNDS);
    }

    /**
     * Hands the border of every loaded face neighbour to every chunk of the area once.
     * <p>
     * The ring takes part in this in both directions. Light which leaves the area, travels through
     * a ring chunk and comes back is the only path when the direct way inside the area is walled
     * off, and a ring state is scratch data which nothing reads afterwards, so raising it costs
     * nothing.
     * </p>
     *
     * @param entries the chunks of the area and of its ring
     * @return true if at least one chunk raised a level, otherwise false
     */
    private static boolean exchange(Map<ChunkArea, Entry> entries) {
        boolean changed = false;

        for (Map.Entry<ChunkArea, Entry> current : entries.entrySet()) {
            Entry entry = current.getValue();

            for (BlockFace face : HORIZONTAL_FACES) {
                @Nullable Entry neighbour = entries.get(current.getKey().neighbour(face));

                if (neighbour == null) {
                    continue;
                }
                changed |= entry.state().injectBorder(
                        entry.opacity(), face, neighbour.state().border(face.opposite())
                );
            }
        }
        return changed;
    }

    /**
     * Writes the settled light into the chunks of the area, leaving the ring untouched.
     *
     * @param entries the chunks of the area and of its ring
     * @param inside  the chunks of the area
     * @param wanted  answers for each chunk whether its result may still be written
     * @param sky     whether the sky light is written instead of the block light
     * @return the chunks whose light was written
     */
    private static List<ChunkArea> write(
            Map<ChunkArea, Entry> entries,
            Set<ChunkArea> inside,
            Predicate<ChunkArea> wanted,
            boolean sky
    ) {
        List<ChunkArea> written = new ArrayList<>(inside.size());

        for (ChunkArea position : inside) {
            @Nullable Entry entry = entries.get(position);

            if (entry == null || !entry.chunk().isLoaded() || !wanted.test(position)) {
                continue;
            }
            ChunkLightService.applyLight(entry.chunk(), entry.state().toSections(), sky);
            written.add(position);
        }
        return written;
    }

    /**
     * The {@link Entry} record holds everything the exchange needs about one chunk, so neither its
     * block states nor its opacity tables are read a second time.
     *
     * @param chunk   the chunk the entry belongs to
     * @param opacity the light properties of every section of the chunk
     * @param state   the light of the chunk as it is exchanged
     */
    private record Entry(Chunk chunk, List<SectionOpacity> opacity, ChunkLightState state) {
    }
}
