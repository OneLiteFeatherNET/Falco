package net.onelitefeather.falco.light;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * The {@link ChunkLightArea} class computes the light of one connected group of chunks in a single
 * pass and writes the result back to that group alone.
 * <p>
 * <b>The ring is read but never written.</b> An area whose edge chunks knew nothing about their
 * neighbours would end at a straight dark line every sixteen blocks, so every chunk which shares a
 * border with the area is read as well and takes part in the exchange. Writing those chunks back is
 * the tempting next step and it is wrong: a ring chunk only saw the light of the area, never the
 * light of whatever lies on its own far side, so its result is darker than its current one. Here it
 * is designed out: the ring is scratch data.
 * {@link ChunkLightService#calculateWithNeighbours(Instance, int, int)} arrives at the same rule
 * from the other side — it reads its whole 3×3 and writes only the chunk in the middle.
 * </p>
 * <p>
 * Computing a group together rather than one chunk after the other is the entire reason this type
 * exists. Reading the block states of a chunk and turning them into opacity tables is the expensive
 * part of lighting, and a per-chunk neighbourhood reads every chunk up to nine times. An area reads
 * each of its chunks once and each of its ring chunks once, no matter how many chunks the area
 * holds.
 * </p>
 *
 * <h2>Why a changed block does not cost a whole chunk</h2>
 * <p>
 * An area keeps the light of the chunks it has computed, so a later pass over the same chunks starts
 * from that light instead of searching the chunk again. What is kept is the light of a chunk
 * <em>on its own</em>, before any border exchange — the exact thing
 * {@link ChunkLightState#blockLight(List)} produces — and it is kept up to date by
 * {@link ChunkLightState#update(List, int, int, int)} for every block position the caller reports
 * through {@link #recordChange(ChunkArea, int, int, int)}.
 * </p>
 * <p>
 * <b>That the kept light is the light of a chunk alone is what makes the hard direction easy.</b>
 * Taking light back is the case an incremental engine gets wrong: when a source disappears, the
 * brightness it had spread is still stored around it, and it has to be retracted up to the point
 * where light from somewhere else legitimately takes over. A retraction which had to leave the chunk
 * to find that point would need the neighbours retracted with it, and nothing in a per-chunk state
 * could express that. Here it never has to: the kept light contains no light from any neighbour, so
 * every level in it originates inside the chunk and the retraction is complete at the chunk border
 * by construction. The light which crosses borders is not stored at all — it is derived again by the
 * border exchange on every pass, from a copy of the kept light, and an exchange only ever raises
 * levels and therefore cannot carry a stale glow forward.
 * </p>
 * <p>
 * The result is therefore the same bytes a full recalculation produces, and not approximately so:
 * the kept light equals what a fresh propagation would give, the exchange runs on a copy of it, and
 * a chunk which cannot be followed incrementally is simply propagated again. Chunks fall back to a
 * full propagation when they have no kept light yet, when their light was dropped by
 * {@link #forget(ChunkArea)} because the caller could not say what changed, when more positions
 * changed at once than are worth replaying, and whenever a ring chunk is dirty — a dirty ring chunk
 * is somebody else's area and its kept light may be a tick behind.
 * </p>
 * <p>
 * <b>The kept light is bounded and it is only a memory.</b> Roughly 100 KB per chunk and kind of
 * light are held for at most the configured amount of chunks, least recently used first; dropping an
 * entry costs a full propagation and nothing else. It relies on one property of its caller: every
 * block change has to be reported, either with its position or as unknown. A caller which changes
 * blocks without saying so gets no light pass at all today, so this adds no new way to be wrong.
 * </p>
 * <p>
 * A computation keeps no state <em>about the run</em>, so one instance may serve as many threads as
 * one likes. Two threads computing <em>overlapping</em> areas is a different matter and is not made
 * safe here; the scheduler is what keeps that from happening, and the kept light follows the same
 * rule — only the area a chunk belongs to ever writes that chunk's entry.
 * </p>
 * <p>
 * This type is experimental. The light engine is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
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
     * The revision of a chunk which is not waiting for its light.
     * <p>
     * A ring chunk is only read from the kept light when it carries this value. Anything else means
     * the chunk belongs to an area of its own, whose result is not in yet.
     * </p>
     */
    public static final long CLEAN = Long.MIN_VALUE;

    /**
     * The amount of chunks whose light is kept when no other value is given.
     * <p>
     * One chunk costs roughly 100 KB per kind of light, so this is about 25 MB of memory traded for
     * not searching a chunk again that nothing moved in. The chunks a server actually edits are a
     * far smaller set than the chunks it holds, which is why a bound this size loses very little.
     * </p>
     */
    public static final int DEFAULT_MAX_CACHED_CHUNKS = 128;

    /**
     * The amount of changed positions of one chunk which are replayed rather than recalculated.
     * <p>
     * Replaying a position is cheap but not free, and a chunk which changed in a thousand places is
     * faster to search from scratch than to walk a thousand times. Where exactly the two meet
     * depends on the world; this is well inside the range where replaying still wins and far below
     * the size of a build.
     * </p>
     */
    private static final int MAX_REPLAYED_CHANGES = 64;

    /**
     * A caller which wants every chunk of the area written back.
     */
    private static final Predicate<ChunkArea> ALWAYS = area -> true;

    private final ChunkLightService service;
    private final Map<ChunkArea, Kept> kept;
    private final AtomicLong fullPropagations = new AtomicLong();

    /**
     * Creates an area computation which reads and writes through the given service.
     *
     * @param service the service which builds the opacity tables and writes the light back
     */
    public ChunkLightArea(ChunkLightService service) {
        this(service, DEFAULT_MAX_CACHED_CHUNKS);
    }

    /**
     * Creates an area computation which keeps the light of at most the given amount of chunks.
     *
     * @param service          the service which builds the opacity tables and writes the light back
     * @param maxCachedChunks  the amount of chunks whose light is kept between two passes, zero to
     *                         keep none and recalculate every chunk of every pass
     * @throws IllegalArgumentException if the given amount is negative
     */
    public ChunkLightArea(ChunkLightService service, int maxCachedChunks) {
        if (maxCachedChunks < 0) {
            throw new IllegalArgumentException("A negative amount of kept chunks makes no sense but " + maxCachedChunks + " was given");
        }
        this.service = service;
        this.kept = Collections.synchronizedMap(new LinkedHashMap<ChunkArea, Kept>(16, 0.75f, true) {

            @Override
            protected boolean removeEldestEntry(Map.Entry<ChunkArea, Kept> eldest) {
                return size() > maxCachedChunks;
            }
        });
    }

    /**
     * Reports that one block of the given chunk changed.
     * <p>
     * The position is remembered rather than acted on. A pass which finds kept light for the chunk
     * replays it there and reaches the same result a recalculation would, and a pass which does not
     * simply ignores it.
     * </p>
     *
     * @param position the chunk the block belongs to
     * @param x        the x coordinate inside the chunk
     * @param columnY  the y coordinate inside the column, counted from the lowest section upwards
     * @param z        the z coordinate inside the chunk
     * @throws IllegalArgumentException if the position lies outside of a chunk column
     */
    public void recordChange(ChunkArea position, int x, int columnY, int z) {
        if ((x | columnY | z) < 0 || x >= LightNibbles.DIMENSION || z >= LightNibbles.DIMENSION) {
            throw new IllegalArgumentException(
                    "The position " + x + "/" + columnY + "/" + z + " is not inside a chunk column"
            );
        }
        this.kept.computeIfAbsent(position, key -> new Kept()).changes().add((columnY << 8) | (z << 4) | x);
    }

    /**
     * Drops the kept light of the given chunk, so its next pass searches it again.
     * <p>
     * This is what a caller reports when blocks changed in a way it cannot describe — a chunk that
     * was generated, loaded, or edited past {@code setBlock}. Guessing would be the only alternative
     * and a wrong guess here is permanent: light is written with the update flag of the section
     * cleared, so nothing would ever correct it.
     * </p>
     *
     * @param position the chunk whose kept light is no longer trustworthy
     */
    public void forget(ChunkArea position) {
        @Nullable Kept entry = this.kept.get(position);

        if (entry != null) {
            entry.drop();
        }
    }

    /**
     * Returns how many chunks this area has lit from scratch since it was created.
     * <p>
     * A number that keeps growing while the same chunks are being edited means the kept light is not
     * being used, which is either a bound that is too small or a caller which reports its changes as
     * unknown.
     * </p>
     *
     * @return the amount of chunks which were propagated rather than updated
     */
    @Contract(pure = true)
    public long fullPropagations() {
        return this.fullPropagations.get();
    }

    /**
     * Computes the light of the given area and writes it into the chunks of that area.
     * <p>
     * This computes every chunk from its block states and keeps nothing, which is what a caller who
     * has no revisions to offer has to do.
     * </p>
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
     * hook a caller uses to drop a result which has gone stale. A chunk which changed while the area
     * was being computed carries a result built from block states that no longer exist, and writing
     * it would clear the update flag of its sections on the basis of that stale read. Such a chunk
     * is therefore discarded whole. The other chunks of the same area are untouched by this and are
     * written normally, because a change in one chunk cannot invalidate more than the ring around it
     * and the next pass covers that.
     * </p>
     * <p>
     * This computes every chunk from its block states and keeps nothing; see
     * {@link #computeIncrementally(Instance, List, boolean, ToLongFunction)} for the path which
     * reuses what it already knows.
     * </p>
     *
     * @param instance the instance which holds the chunks
     * @param area     the chunks which are computed together
     * @param sky      whether the sky light is computed instead of the block light
     * @param wanted   answers for each chunk whether its result may still be written
     * @return the chunks whose light was written, in the order they were given
     */
    public List<ChunkArea> compute(Instance instance, List<ChunkArea> area, boolean sky, Predicate<ChunkArea> wanted) {
        return run(instance, area, sky, wanted, null);
    }

    /**
     * Computes the light of the given area, reusing the light of every chunk nothing moved in.
     * <p>
     * The revision of a chunk is any number which changes whenever the chunk is reported dirty, and
     * {@link #CLEAN} for a chunk which is not waiting for light at all. It carries both rules this
     * path needs: a chunk whose revision moved while it was being computed is discarded, exactly as
     * with the predicate above, and a <em>ring</em> chunk is only read from the kept light while it
     * is clean, because a dirty one belongs to an area whose result is not in yet.
     * </p>
     * <p>
     * The revision has to be read before the block states are, which is why it is asked for as a
     * function rather than taken as a snapshot: a change which lands between the two would otherwise
     * be invisible to the comparison at the end and would be committed as if it had never happened.
     * </p>
     *
     * @param instance the instance which holds the chunks
     * @param area     the chunks which are computed together
     * @param sky      whether the sky light is computed instead of the block light
     * @param revision answers for each chunk which revision it is currently at
     * @return the chunks whose light was written, in the order they were given
     */
    public List<ChunkArea> computeIncrementally(
            Instance instance,
            List<ChunkArea> area,
            boolean sky,
            ToLongFunction<ChunkArea> revision
    ) {
        return run(instance, area, sky, null, revision);
    }

    /**
     * Runs one computation over the given area.
     *
     * @param instance the instance which holds the chunks
     * @param area     the chunks which are computed together
     * @param sky      whether the sky light is computed instead of the block light
     * @param wanted   answers whether a chunk may still be written, or null on the incremental path
     * @param revision answers which revision a chunk is at, or null on the recalculating path
     * @return the chunks whose light was written, in the order they were given
     */
    private List<ChunkArea> run(
            Instance instance,
            List<ChunkArea> area,
            boolean sky,
            @Nullable Predicate<ChunkArea> wanted,
            @Nullable ToLongFunction<ChunkArea> revision
    ) {
        Set<ChunkArea> inside = new LinkedHashSet<>(area);
        Map<ChunkArea, Entry> entries = read(instance, inside, sky, revision);

        if (entries.isEmpty()) {
            return List.of();
        }
        exchangeUntilSettled(entries);
        return write(entries, inside, wanted, revision, sky);
    }

    /**
     * Reads every loaded chunk of the area and of the ring around it, and lights each on its own.
     * <p>
     * A coordinate the instance holds no chunk for is skipped rather than loaded. Lighting must not
     * pull a world into memory, and a chunk nobody has asked for has no viewer whose light could be
     * wrong.
     * </p>
     * <p>
     * The order inside the loop is the one correctness depends on: the revision is read first, the
     * reported positions are taken second and the block states third. Any change which slips between
     * two of these steps is then either covered by the positions or visible as a moved revision, and
     * never invisible to both.
     * </p>
     *
     * @param instance the instance which holds the chunks
     * @param inside   the chunks of the area
     * @param sky      whether the sky light is computed instead of the block light
     * @param revision answers which revision a chunk is at, or null on the recalculating path
     * @return one entry per loaded chunk of the area and of its ring
     */
    private Map<ChunkArea, Entry> read(
            Instance instance,
            Set<ChunkArea> inside,
            boolean sky,
            @Nullable ToLongFunction<ChunkArea> revision
    ) {
        Set<ChunkArea> wanted = new LinkedHashSet<>(inside);

        for (ChunkArea position : inside) {
            for (BlockFace face : HORIZONTAL_FACES) {
                wanted.add(position.neighbour(face));
            }
        }

        Map<ChunkArea, Entry> entries = new HashMap<>(wanted.size());

        for (ChunkArea position : wanted) {
            boolean member = inside.contains(position);
            long stamp = revision == null ? CLEAN : revision.applyAsLong(position);
            @Nullable Kept entry = revision == null ? null : this.kept.get(position);
            @Nullable Taken taken = entry != null && member ? entry.changes().take(sky) : null;
            @Nullable Chunk chunk = instance.getChunk(position.x(), position.z());

            if (chunk == null) {
                if (revision != null) {
                    // Nothing says the chunk comes back with the blocks it left with.
                    this.kept.remove(position);
                }
                continue;
            }

            List<SectionOpacity> opacity = this.service.opacityOf(chunk);
            ChunkLightState solo = solo(entry, opacity, sky, member, stamp, taken, revision != null);

            entries.put(position, new Entry(chunk, opacity, solo.copy(), member ? solo : null, stamp));
        }
        return entries;
    }

    /**
     * Produces the light of one chunk on its own, reusing what is kept for it where that is allowed.
     *
     * @param entry   what is kept for the chunk, or null if nothing is
     * @param opacity the light properties of every section of the chunk
     * @param sky     whether the sky light is computed instead of the block light
     * @param member  whether the chunk belongs to the area rather than to the ring around it
     * @param stamp   the revision the chunk was at before its block states were read
     * @param taken   the positions which changed since the last pass, or null if none were taken
     * @param caching whether the caller offered revisions at all
     * @return the light of the chunk without any contribution from its neighbours
     */
    private ChunkLightState solo(
            @Nullable Kept entry,
            List<SectionOpacity> opacity,
            boolean sky,
            boolean member,
            long stamp,
            @Nullable Taken taken,
            boolean caching
    ) {
        @Nullable ChunkLightState base = caching && entry != null ? entry.of(sky) : null;

        if (base == null) {
            return propagate(opacity, sky);
        }
        if (!member) {
            // A dirty ring chunk is another area's business and its kept light may be a tick behind.
            return stamp == CLEAN ? base : propagate(opacity, sky);
        }
        if (taken == null || taken.unknown()) {
            return propagate(opacity, sky);
        }
        if (taken.positions().length == 0) {
            return base;
        }

        ChunkLightState updated = base.copy();

        for (int packed : taken.positions()) {
            updated.update(opacity, packed & 15, packed >>> 8, (packed >> 4) & 15);
        }
        return updated;
    }

    /**
     * Searches the whole chunk for its light and counts the search.
     *
     * @param opacity the light properties of every section of the chunk
     * @param sky     whether the sky light is computed instead of the block light
     * @return the light of the chunk without any contribution from its neighbours
     */
    private ChunkLightState propagate(List<SectionOpacity> opacity, boolean sky) {
        this.fullPropagations.incrementAndGet();
        return sky ? ChunkLightState.skyLight(opacity) : ChunkLightState.blockLight(opacity);
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
     * <p>
     * A chunk is kept for the next pass under exactly the condition under which it is written: its
     * revision has to be the one it had before its block states were read, and the chunk has to
     * still be loaded. Anything else drops what is kept for it, because a light built on block
     * states that are already gone must never become the starting point of the pass after this one.
     * </p>
     *
     * @param entries  the chunks of the area and of its ring
     * @param inside   the chunks of the area
     * @param wanted   answers whether a chunk may still be written, or null on the incremental path
     * @param revision answers which revision a chunk is at, or null on the recalculating path
     * @param sky      whether the sky light is written instead of the block light
     * @return the chunks whose light was written
     */
    private List<ChunkArea> write(
            Map<ChunkArea, Entry> entries,
            Set<ChunkArea> inside,
            @Nullable Predicate<ChunkArea> wanted,
            @Nullable ToLongFunction<ChunkArea> revision,
            boolean sky
    ) {
        List<ChunkArea> written = new ArrayList<>(inside.size());

        for (ChunkArea position : inside) {
            @Nullable Entry entry = entries.get(position);

            if (entry == null) {
                continue;
            }

            boolean current = revision == null
                    ? wanted != null && wanted.test(position)
                    : revision.applyAsLong(position) == entry.stamp();
            boolean keep = current && entry.chunk().isLoaded();

            if (revision != null) {
                remember(position, entry.solo(), sky, keep);
            }
            if (!keep) {
                continue;
            }
            ChunkLightService.applyLight(entry.chunk(), entry.state().toSections(), sky);
            written.add(position);
        }
        return written;
    }

    /**
     * Keeps the light of one chunk for the next pass, or drops what is kept for it.
     *
     * @param position the chunk the light belongs to
     * @param solo     the light of the chunk on its own, or null if none was produced
     * @param sky      whether the sky light is kept instead of the block light
     * @param keep     whether the light may be kept at all
     */
    private void remember(ChunkArea position, @Nullable ChunkLightState solo, boolean sky, boolean keep) {
        if (!keep || solo == null) {
            forget(position);
            return;
        }
        this.kept.computeIfAbsent(position, key -> new Kept()).store(solo, sky);
    }

    /**
     * The {@link Entry} record holds everything the exchange needs about one chunk, so neither its
     * block states nor its opacity tables are read a second time.
     *
     * @param chunk   the chunk the entry belongs to
     * @param opacity the light properties of every section of the chunk
     * @param state   the light of the chunk as it is exchanged
     * @param solo    the light of the chunk alone, which is kept for the next pass, or null for a
     *                chunk of the ring
     * @param stamp   the revision the chunk was at before its block states were read
     */
    private record Entry(
            Chunk chunk,
            List<SectionOpacity> opacity,
            ChunkLightState state,
            @Nullable ChunkLightState solo,
            long stamp
    ) {
    }

    /**
     * The {@link Kept} class holds everything one chunk carries between two passes.
     * <p>
     * The light and the positions which changed since it was computed live in the same entry on
     * purpose. They are only ever right together — a light without the changes that happened to it
     * is stale, and changes without a light describe nothing — so the bound on the memory drops both
     * at once and can never leave one of them behind.
     * </p>
     */
    private static final class Kept {

        private final Changes changes = new Changes();

        private volatile @Nullable ChunkLightState block;
        private volatile @Nullable ChunkLightState sky;

        /**
         * Returns the positions which changed since the light of this chunk was computed.
         *
         * @return the collected changes of this chunk
         */
        @Contract(pure = true)
        private Changes changes() {
            return this.changes;
        }

        /**
         * Returns the kept light of the requested kind.
         *
         * @param wantsSky whether the sky light is wanted instead of the block light
         * @return the kept light, or null if none is kept for that kind
         */
        @Contract(pure = true)
        private @Nullable ChunkLightState of(boolean wantsSky) {
            return wantsSky ? this.sky : this.block;
        }

        /**
         * Keeps the given light for the next pass.
         *
         * @param state    the light of the chunk on its own
         * @param wantsSky whether the sky light is kept instead of the block light
         */
        private void store(ChunkLightState state, boolean wantsSky) {
            if (wantsSky) {
                this.sky = state;
                return;
            }
            this.block = state;
        }

        /**
         * Throws away everything this entry holds, so the next pass starts from the block states.
         */
        private void drop() {
            this.block = null;
            this.sky = null;
            this.changes.unknown();
        }
    }

    /**
     * The {@link Taken} record holds what one pass takes over from the reported changes of a chunk.
     *
     * @param positions the positions which changed, packed as the light state indexes them
     * @param unknown   true if the changes cannot be replayed and the chunk has to be searched again
     */
    private record Taken(int[] positions, boolean unknown) {
    }

    /**
     * The {@link Changes} class collects the positions a chunk reported since its last pass.
     * <p>
     * Both kinds of light are collected separately because they are computed in two passes, and a
     * list which both of them took from would leave the second one believing nothing had changed.
     * </p>
     */
    private static final class Changes {

        private int[] blockPositions = new int[8];
        private int blockCount;
        private boolean blockUnknown;

        private int[] skyPositions = new int[8];
        private int skyCount;
        private boolean skyUnknown;

        /**
         * Adds one changed position to both kinds of light.
         *
         * @param packed the position, packed as the light state indexes it
         */
        private synchronized void add(int packed) {
            if (this.blockCount == MAX_REPLAYED_CHANGES) {
                this.blockUnknown = true;
            }
            if (!this.blockUnknown) {
                this.blockPositions = grow(this.blockPositions, this.blockCount);
                this.blockPositions[this.blockCount++] = packed;
            }

            if (this.skyCount == MAX_REPLAYED_CHANGES) {
                this.skyUnknown = true;
            }
            if (!this.skyUnknown) {
                this.skyPositions = grow(this.skyPositions, this.skyCount);
                this.skyPositions[this.skyCount++] = packed;
            }
        }

        /**
         * Reports that the chunk changed in a way no list of positions can describe.
         */
        private synchronized void unknown() {
            this.blockUnknown = true;
            this.blockCount = 0;
            this.skyUnknown = true;
            this.skyCount = 0;
        }

        /**
         * Hands the collected positions of one kind of light to a pass and starts collecting anew.
         *
         * @param sky whether the positions of the sky light are taken
         * @return what the pass has to replay
         */
        private synchronized Taken take(boolean sky) {
            if (sky) {
                Taken taken = new Taken(Arrays.copyOf(this.skyPositions, this.skyCount), this.skyUnknown);
                this.skyCount = 0;
                this.skyUnknown = false;
                return taken;
            }
            Taken taken = new Taken(Arrays.copyOf(this.blockPositions, this.blockCount), this.blockUnknown);
            this.blockCount = 0;
            this.blockUnknown = false;
            return taken;
        }

        /**
         * Makes room for one more position.
         *
         * @param positions the positions collected so far
         * @param count     the amount of positions the array holds
         * @return an array with room for one more position
         */
        @Contract(pure = true)
        private static int[] grow(int[] positions, int count) {
            return count == positions.length ? Arrays.copyOf(positions, positions.length * 2) : positions;
        }
    }
}
