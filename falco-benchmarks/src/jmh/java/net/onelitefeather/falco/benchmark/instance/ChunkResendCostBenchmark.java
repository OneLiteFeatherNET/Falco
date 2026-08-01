package net.onelitefeather.falco.benchmark.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.ServerFlag;
import net.minestom.server.ServerProcess;
import net.minestom.server.coordinate.ChunkRange;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.ChunkDataPacket;
import net.onelitefeather.falco.benchmark.support.BenchmarkConstants;
import net.onelitefeather.falco.benchmark.support.MinestomChunks;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The {@link ChunkResendCostBenchmark} class measures what a full chunk resend at view distance
 * {@code 10} actually costs, in time, in bytes on the wire and in allocated heap, so that the
 * SharedInstance question of Falco is decided on a number rather than on an intuition.
 *
 * <h2>The decision this benchmark serves</h2>
 * <p>
 * When a player changes instance, {@code Player#setInstance} asks
 * {@code SharedInstance.areLinked(currentInstance, instance)} first (Player.java:618 of the pinned
 * build). If the two instances share their block storage and the player stays in the same chunk, the
 * player is respawned into the new instance without a single chunk packet: the client already holds
 * the correct version of every chunk around it. Everything else falls through to the slow path,
 * which queues every chunk in range and sends {@code chunk.getFullDataPacket()} for each of them
 * (Player.java:794).
 * </p>
 * <p>
 * That fast path is reachable only through Minestom's own types. {@code areLinked} decides by
 * {@code instanceof SharedInstance} and {@code instanceof InstanceContainer}
 * (SharedInstance.java:141-153), and {@code SharedInstance} stores its owner in a field typed to the
 * concrete {@code InstanceContainer} (SharedInstance.java:22). An architecture in which Falco keeps
 * its own block store, held by two instances side by side instead of one instance pointing at
 * another, is the cleaner shape and dissolves the aliasing defects of {@code SharedInstance} — but
 * it is not an {@code InstanceContainer}, so {@code areLinked} returns {@code false} and the fast
 * path is gone. The clean design therefore has an exact price: one full resend per instance change.
 * This class puts a number on it.
 * </p>
 *
 * <h2>Why the result is an absolute price and not a ratio</h2>
 * <p>
 * There is no second arm to compare against. The fast path does not send a cheaper packet, it sends
 * no packet at all, so its cost is zero by construction and any ratio against it would be infinite.
 * Every number this class produces is therefore the whole bill of giving the fast path up, and the
 * decision rule is an absolute threshold rather than a factor: a resend that costs a fraction of a
 * tick and a few hundred kilobytes is a price worth paying for a sound architecture, and one that
 * costs several ticks and tens of megabytes per instance change is not.
 * </p>
 *
 * <h2>The unit is a column of 441 chunks, not one chunk</h2>
 * <p>
 * {@code ChunkRange.chunksCount(10)} is {@code 441}, and that is the number of packets the slow path
 * builds for a single player. {@link #resendViewDistance10()} therefore walks {@code 441} distinct
 * chunks per operation rather than one chunk {@code 441} times. The distinction is not pedantry: a
 * single chunk stays in cache across iterations and would report the cost of a resend that never
 * leaves L2, while a real column of terrain is tens of megabytes of palette storage that the
 * serializer has to stream through memory once per resend.
 * </p>
 * <p>
 * The {@code 441} chunks are built with {@link Chunk#copy(net.minestom.server.instance.Instance, int, int)}
 * from one filled source chunk, over the same spiral of coordinates
 * {@code ChunkRange.chunksInRange} hands to the player, and they are deliberately identical in
 * content. Copying clones every {@code Section}, so each chunk owns its palettes, its light arrays
 * and its packet cache, which is what the memory traffic of the measurement depends on. Identical
 * content is what keeps the content axis an axis: two runs at the same parameter must differ in
 * nothing but the code under measurement, and terrain that varied per chunk would turn the byte
 * volume into a property of the generator instead of a property of the parameter.
 * </p>
 * <p>
 * The single chunk methods next to it exist so that the column figure can be checked against its own
 * parts. {@link #buildAndSerializeOneChunkPacket()} times {@code 441} times over should come close to
 * {@link #resendViewDistance10()}; the gap between the two is the memory locality penalty of walking
 * a whole column, and it is the one part of the answer that a per chunk measurement structurally
 * cannot show.
 * </p>
 *
 * <h2>What one packet actually holds</h2>
 * <p>
 * {@code DynamicChunk#createChunkPacket} (DynamicChunk.java:256) builds a {@code ChunkDataPacket}
 * out of four pieces: both heightmaps, which the first call computes and every later call reads back
 * from memoised state; the light data of all {@code 24} sections; the block palettes of all
 * {@code 24} sections, serialised into one {@code byte[]} through {@code NetworkBuffer.makeArray};
 * and the block entity map. Three of the four allocation posts the research report names sit in that
 * path — {@code data.clone()} in the {@code ChunkData} constructor (ChunkData.java:26), the boxing
 * of every block entity key through {@code Map.Entry::getKey} into an unmodifiable map
 * (ChunkData.java:27-30), and the doubling of the {@code NetworkBuffer} that starts at {@code 256}
 * bytes (NetworkBuffer.java:328-332).
 * </p>
 *
 * <h2>Why the packet cache is switched off</h2>
 * <p>
 * {@code DynamicChunk#getFullDataPacket} does not return a packet, it returns the {@code CachedPacket}
 * that wraps {@code createChunkPacket} (DynamicChunk.java:58 and :230). That cache keeps its result
 * in a {@code SoftReference} and, when {@code ServerFlag.CACHED_PACKET} is on, does the framing and
 * the compression inside the same call. Two things follow that a measurement cannot live with: a
 * soft reference may be cleared by the garbage collector in the middle of an iteration, which turns
 * a cache hit into a full rebuild at a moment nobody controls, and the build cannot be told apart
 * from the framing because both happen behind one method.
 * </p>
 * <p>
 * The fork therefore runs with {@code -Dminestom.cached-packet=false}, and {@link #setUp()} refuses
 * to measure if that flag did not arrive. With the cache off,
 * {@code SendablePacket.extractServerPacket} resolves straight to {@code createChunkPacket}, so
 * {@link #buildOneChunkPacket()} times the build alone, {@link #serializeOneChunkPacket()} times the
 * framing alone on a packet built once in the setup, and
 * {@link #buildAndSerializeOneChunkPacket()} times the sum — which is exactly what the enabled cache
 * would have done in one step.
 * </p>
 * <p>
 * What that flag costs the benchmark is stated rather than hidden: on a live server with the cache
 * enabled, the second and every further player to receive the same chunk pays neither the build nor
 * the framing, only the write of an already framed buffer into its own connection. This class cannot
 * time that write, because it needs a real {@code PlayerConnection}, and it does not pretend to. It
 * bounds it instead: that write moves exactly the amount of bytes this class reports, and nothing
 * more. The build and framing numbers are the price of the first resend of a chunk and of every
 * resend whose cache entry was invalidated by a block change or dropped by the collector.
 * </p>
 *
 * <h2>The bytes are the compressed bytes</h2>
 * <p>
 * {@code PacketWriting.allocateTrimmedPacket} is the method the connection layer itself uses, and
 * with a compression threshold above zero — Minestom defaults to {@code 256} — it deflates every
 * chunk packet before the length prefixes are written. The reported wire size is therefore the size
 * after compression, which is the number that actually travels, and it is far below the serialised
 * size for exactly the contents where it matters most: a fully lit air chunk ships {@code 24} arrays
 * of {@code 2048} identical bytes and deflates to almost nothing. Both figures are reported side by
 * side in the setup line, because the uncompressed one is what the serializer had to produce and the
 * compressed one is what the socket had to carry.
 * </p>
 *
 * <h2>The content axis, and the light that comes with it</h2>
 * <p>
 * A chunk packet is not one thing, and a single number for "a chunk" would be a number for whichever
 * content the author happened to build. The four values bracket the range rather than sample it, and
 * they are chosen so that no two of them are extreme in the same quantity: the content with the
 * largest serialised payload is not the content with the largest wire size, because compression
 * reorders the ranking. A reader who takes only one row out of this table has to take the row that
 * matches the world the decision is about.
 * </p>
 * <p>
 * Sky light is part of the content rather than a second axis, and it is seeded to what the content
 * physically implies: an air chunk is lit through, a chunk of solid stone to the build limit is dark
 * everywhere, and a chunk with a surface at {@code y=64} is lit above it and dark below. Seeding
 * happens through {@code Section#setSkyLight}, so no light engine runs during the measurement — the
 * model is a server whose chunks are loaded and already lit, which is the state a player finds when
 * it changes instance. A dark section contributes no bytes at all, because
 * {@code DynamicChunk#createLightData} only sends arrays whose length is not zero
 * (DynamicChunk.java:303-315), which is why the block light of every content here is empty: the
 * fixture places no light emitting blocks.
 * </p>
 * <p>
 * The consequence is that {@link ChunkContent#EMPTY} is not the free case it looks like. An air
 * chunk carries no palette data worth mentioning and up to {@code 24 x 2048} bytes of sky light,
 * and that asymmetry is a result rather than a nuisance: a lobby of empty instances pays for its
 * resends too.
 * </p>
 *
 * <h2>What the allocation profiler sees and what it misses</h2>
 * <p>
 * {@code -prof gc} reports {@code gc.alloc.rate.norm}, which counts heap allocation per operation.
 * It covers the {@code data.clone()} of {@code ChunkData}, the maps and lists the block entity
 * filter and the light data build, and the {@code byte[]} that {@code makeArray} finally reads out.
 * It does <em>not</em> cover the growth of the {@code NetworkBuffer} itself: every doubling step is
 * an {@code Arena.ofAuto().allocate} (NetworkBufferImpl.java:203 and :243), which is native memory
 * released by the collector rather than heap, and a native segment does not appear in an allocation
 * rate. The third of the three posts named in the report is therefore visible in this benchmark only
 * through the time it takes, not through the bytes it moves, and a reader who subtracts the reported
 * allocation from the packet size will find a gap that is exactly that.
 * </p>
 *
 * <h2>The equivalence stage</h2>
 * <p>
 * The premise of the whole question is that a resend delivers data the client already has. If a
 * rebuilt packet were not byte for byte the packet that was sent before, the resend would not be
 * redundant work but necessary work, and measuring "the price of avoidable traffic" would be
 * measuring the wrong thing entirely. {@link #setUp()} therefore rebuilds and reserialises the same
 * chunk twice and compares the two byte sequences before a single measurement is taken, in the shape
 * {@code LightEngineComparisonBenchmark#verifyBothEnginesAgree} established for this module: it
 * throws, and the trial stops instead of publishing a number.
 * </p>
 * <p>
 * Three further checks guard the fixture itself. Every chunk of the column has to serialise to the
 * same uncompressed length, which is what makes the column homogeneous enough for a per chunk
 * average to mean anything; the compressed lengths are only summed and their spread reported, since
 * the chunk coordinates differ and deflate is free to answer a different length for a different
 * input. A sample of the copies is compared against the source through
 * {@code MinestomChunks#assertSameBlocks}, block by block and heightmap by heightmap, because a copy
 * that silently lost its content would make every packet of the column smaller than the parameter
 * claims. And the count of lit sections is verified against what the content promises, because sky
 * light is the largest single contributor to the byte volume and a fixture that lost it would report
 * a resend that costs a fraction of the real one.
 * </p>
 *
 * <h2>What this benchmark does not answer</h2>
 * <p>
 * It measures the server side of one resend and nothing else. The client side cost of ingesting
 * {@code 441} chunks, the bandwidth of the link, the entity and viewer bookkeeping that
 * {@code Player#spawnPlayer} performs alongside the chunks, and the chunk rate limiter that spreads
 * the batch over several ticks
 * ({@code ServerFlag.MAX_CHUNKS_PER_TICK}, Player.java:780-781) are all outside it. The rate limiter
 * in particular means that the wall clock latency a player perceives is governed by the tick budget
 * rather than by the numbers here; what the numbers here decide is how much of the tick budget the
 * resend consumes, which is the part Falco controls.
 * </p>
 *
 * <h2>Running it</h2>
 * <p>
 * The full cross product is four scenarios per method. The resend method moves the whole column per
 * operation and is the slow one; the three single chunk methods are cheap and can be run on their
 * own while iterating on the packet path.
 * </p>
 * <pre>{@code
 * ./gradlew :falco-benchmarks:jmh -Pjmh.include=ChunkResendCostBenchmark
 *
 * java -jar build/libs/falco-*-jmh.jar "ChunkResendCostBenchmark" -prof gc -f 1 -wi 5 -i 5
 * java -jar build/libs/falco-*-jmh.jar "ChunkResendCostBenchmark.resendViewDistance10" \
 *     -prof gc -f 1 -wi 5 -i 5 -p content=EMPTY,TERRAIN
 * }</pre>
 * <p>
 * The Gradle task already sets the {@code gc} profiler for every benchmark of this module, and the
 * {@code -Dminestom.cached-packet=false} the class depends on travels with the {@link Fork}
 * annotation in both cases. Every trial prints one line naming the byte volume of its column before
 * it starts, because that volume is a constant of the fixture rather than a measurement and JMH has
 * no channel for a constant.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g", "-Dminestom.cached-packet=false"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class ChunkResendCostBenchmark {

    /**
     * The view distance the resend is measured at, in chunks.
     * <p>
     * Ten is the value the question was asked at and the value most servers run, above Minestom's own
     * default of {@code 8} ({@code ServerFlag.CHUNK_VIEW_DISTANCE}) and below the client maximum. The
     * cost scales with the square of it, so a server at {@code 8} pays roughly {@code 289 / 441} of
     * what this class reports and one at {@code 16} roughly {@code 1089 / 441}.
     * </p>
     */
    private static final int VIEW_DISTANCE = 10;

    /**
     * The amount of chunk packets one resend produces, {@code 441} at {@link #VIEW_DISTANCE}.
     * The value comes from {@code ChunkRange#chunksCount} rather than from a literal so that it stays
     * the number the player path itself uses.
     */
    private static final int RESEND_CHUNKS = ChunkRange.chunksCount(VIEW_DISTANCE);

    /**
     * The block Y above which {@link ChunkContent#TERRAIN} is air.
     * <p>
     * Sea level of the overworld, which puts the surface on a section boundary and therefore lets the
     * sections above it be uniformly air and uniformly lit rather than half of each. A surface in the
     * middle of a section would be more realistic in shape and would make the lit section count
     * ambiguous, which is a bad trade for a fixture that has to verify that count.
     * </p>
     */
    private static final int SURFACE_Y = 64;

    /**
     * The length of the light array of one section, {@code 2048} bytes for {@code 4096} blocks at
     * four bits each.
     */
    private static final int LIGHT_ARRAY_LENGTH = BenchmarkConstants.BLOCK_ENTRIES / 2;

    /**
     * The amount of distinct block states {@link ChunkContent#TERRAIN} draws from.
     * <p>
     * Sixty-four states is what a generated overworld chunk holds in the same order of magnitude —
     * stone, deepslate, dirt, gravel, the ores and their variants — and it puts every section palette
     * into the indirect mode at six bits per entry, which is where a real chunk sits. Below that the
     * palette collapses into a single value and stops being representative, above it the storage
     * approaches the direct mode that {@link ChunkContent#DENSE} already covers.
     * </p>
     */
    private static final int TERRAIN_STATES = 64;

    /**
     * The amount of distinct block states {@link ChunkContent#DENSE} draws from.
     * <p>
     * Two hundred and fifty-six states at one state per block is the ceiling of the axis: it fills
     * eight bits per entry in every section, so the block data of the packet reaches {@code 4096}
     * bytes per section, and the per block cycling leaves no run for the compressor to exploit.
     * </p>
     */
    private static final int DENSE_STATES = 256;

    /**
     * The amount of copies compared against the source chunk block by block.
     * <p>
     * Three rather than all {@code 441}: the comparison walks {@code 98304} positions per pair, and
     * the failure it guards against — a copy that lost its content — is a property of
     * {@code Chunk#copy} rather than of an individual coordinate, so it shows on the first sample as
     * readily as on the last. The samples are the first, the middle and the last of the column so
     * that a failure of the loop that builds it is caught wherever it starts.
     * </p>
     */
    private static final int COMPARED_COPIES = 3;

    /**
     * The content the chunks of the measured column hold.
     * <p>
     * Every value fixes the block states and the sky light together, because a chunk's light follows
     * from its shape and a fixture that combined them freely would build worlds that cannot exist.
     * </p>
     */
    public enum ChunkContent {

        /**
         * Air everywhere, sky light in every section.
         * <p>
         * The floor of the block data and, deliberately, not the floor of the packet: all
         * {@code 24} sections are lit through, so the packet carries {@code 24 x 2048} bytes of sky
         * light and nothing else worth naming. This is the shape of a void or lobby instance, and it
         * is the case that decides whether a resend is cheap in the world where instance changes are
         * most frequent.
         * </p>
         */
        EMPTY,

        /**
         * One block state in every position of the chunk, no light anywhere.
         * <p>
         * The content that looks free and, by the hypothesis this value exists to test, is not. A
         * chunk of solid stone to the build limit lets no sky light through, so it contributes no
         * light bytes at all, and a palette that held a single value would need no backing array and
         * would serialise to that one value. But the palette does not hold a single value by the time
         * it is serialised: the very first write of a differing state grows it out of the
         * single value form into the indirect form with a backing array
         * ({@code PaletteImpl#initIndirect}), and nothing ever shrinks it back, because
         * {@code PaletteImpl#optimize} has no call site anywhere in the Minestom main source tree. If
         * that is right, every section of a uniform chunk still ships its full backing array, and
         * this content is the cheapest way to see the wasted bytes without terrain on top of them.
         * </p>
         * <p>
         * That makes it the control of the axis in both directions. Against {@link #EMPTY} it isolates
         * what block data costs when there is only one block; against {@link #TERRAIN} it isolates
         * what the variety of the terrain adds over a chunk that is merely full.
         * </p>
         */
        UNIFORM,

        /**
         * Runs of {@code 64} states below {@code y=64}, air and sky light above.
         * <p>
         * The realistic case, and the one the decision should be read off. The ground is filled with
         * {@code MinestomChunks.FillShape#RANDOM_RUNS}, whose autocorrelated runs are the property
         * real terrain has and per block randomness destroys, and the sky above it is carved back to
         * air and lit. That gives both halves of a real column: eight sections of terrain and sixteen
         * sections of lit sky.
         * </p>
         * <p>
         * The sixteen air sections are not free either, and for the same reason {@link #UNIFORM}
         * exists. They were filled before they were carved, so their palettes grew a backing array
         * and, with no call site for {@code PaletteImpl#optimize}, keep it after every entry in them
         * has gone back to air. A chunk that a generator produced with air above the surface from the
         * start would be cheaper than this one, which makes this content an upper bound on real
         * terrain rather than a mean — and the size of that gap is itself a finding, since it is the
         * price Minestom pays for never shrinking a palette.
         * </p>
         */
        TERRAIN,

        /**
         * {@code 256} states cycling per block through the whole chunk, no light anywhere.
         * <p>
         * The ceiling of the serialised payload and of the work the serializer has to do. Two hundred
         * and fifty-six distinct states force eight bits per entry in every section, which is the
         * largest block data a chunk can carry short of the direct mode, and no world generator emits
         * anything close to it. If the build and the serialisation are affordable here, they are
         * affordable everywhere.
         * </p>
         * <p>
         * It is deliberately <em>not</em> the ceiling on the wire, and the distinction is the reason
         * the two byte figures are reported separately. A strict per block cycle is the most periodic
         * input deflate can be handed, so the content that produces the largest buffer produces one of
         * the smallest compressed frames. The realistic ceiling for the wire is {@link #TERRAIN},
         * whose runs are long enough to be cheap to store and irregular enough to resist compression.
         * </p>
         */
        DENSE
    }

    /**
     * The content the chunks of this trial hold.
     */
    @Param({"EMPTY", "UNIFORM", "TERRAIN", "DENSE"})
    public ChunkContent content;

    private ServerProcess process;
    private InstanceContainer container;
    private Chunk[] column;
    private ChunkDataPacket prebuilt;
    private int compressionThreshold;

    /**
     * Builds the column of {@code 441} chunks the resend walks and proves that measuring
     * it answers the question it was written for.
     * <p>
     * The order matters. The content is written first, the sky light second, the copies third, and
     * only then is one packet built and thrown away for every chunk of the column. That last pass is
     * not a warm-up in the JMH sense: it pays the one-time costs a fresh chunk carries — the full
     * heightmap refresh that {@code DynamicChunk#getHeightmaps} performs on its first call and never
     * again — so that the measured operations see the state a loaded, lit and already served chunk is
     * in. Without it the first operation of the first iteration would carry {@code 441} heightmap
     * refreshes and the measurement would describe chunk generation instead of a resend.
     * </p>
     *
     * @throws IllegalStateException if the packet cache was not disabled, if a rebuilt packet differs
     *                               from the packet built before it, if the chunks of the column
     *                               disagree on their serialised length, if a copy lost the content
     *                               of its source or if the sky light does not match the content
     */
    @Setup(Level.Trial)
    public void setUp() {
        this.process = MinestomChunks.ensureServer();
        this.compressionThreshold = MinecraftServer.getCompressionThreshold();
        requireDisabledPacketCache();

        this.container = MinestomChunks.newContainer();

        final Chunk source = MinestomChunks.newChunk(this.container, 0, 0);
        applyContent(source);
        seedSkyLight(source);

        this.column = buildColumn(source);
        for (Chunk chunk : this.column) {
            serialize(buildPacket(chunk));
        }
        this.prebuilt = asChunkDataPacket(buildPacket(this.column[0]));

        verifyRebuildIsIdentical();
        verifyColumnIsHomogeneous();
        verifyCopiesMatchSource(source);
        verifySkyLightMatchesContent();

        reportByteVolume();
    }

    /**
     * Releases the instance and the column so that the next trial of the same fork starts on an empty
     * heap.
     * <p>
     * A column of {@code 441} chunks is tens of megabytes of palette storage, and four
     * parameter values run in one fork. Leaving one trial's column reachable while the next one
     * builds its own would put the measurement into a heap that is permanently near its limit and
     * turn the answer into a statement about the garbage collector.
     * </p>
     */
    @TearDown(Level.Trial)
    public void tearDown() {
        this.prebuilt = null;
        this.column = null;
        MinestomChunks.release(this.container);
        this.container = null;
    }

    /**
     * Measures the build of one chunk packet, without serialising it.
     * <p>
     * This is {@code DynamicChunk#createChunkPacket} and nothing else: the heightmaps read back from
     * their memoised state, the light data assembled from the section arrays, the block palettes
     * written into one growing {@code NetworkBuffer} and copied out of it, and the
     * {@code ChunkData} constructor with its {@code data.clone()} and its block entity filter.
     * </p>
     *
     * @return the built packet
     */
    @Benchmark
    public ServerPacket buildOneChunkPacket() {
        return buildPacket(this.column[0]);
    }

    /**
     * Measures the serialisation, framing and compression of one already built chunk packet.
     * <p>
     * The packet is built once in the setup and reused, so this method holds still everything the
     * build does and isolates what the connection layer adds on top: the walk over the packet through
     * {@code ChunkDataPacket.SERIALIZER}, the deflate pass that
     * {@code PacketWriting#writeCompressedFormat} performs above the compression threshold, and the
     * final trimmed copy of the framed buffer.
     * </p>
     *
     * @return the framed buffer, whose readable bytes are what the socket would carry
     */
    @Benchmark
    public NetworkBuffer serializeOneChunkPacket() {
        return serialize(this.prebuilt);
    }

    /**
     * Measures the build and the serialisation of one chunk packet together.
     * <p>
     * The sum of the two methods above and the per chunk unit of the resend. With the packet cache
     * enabled this is exactly the work {@code CachedPacket#updatedCache} performs behind one call to
     * {@code getFullDataPacket} when its soft reference is empty.
     * </p>
     *
     * @return the framed buffer of the chunk
     */
    @Benchmark
    public NetworkBuffer buildAndSerializeOneChunkPacket() {
        return serialize(buildPacket(this.column[0]));
    }

    /**
     * Measures a full resend: building and serialising a packet for every one of the
     * {@code 441} chunks a player at view distance {@code 10} holds.
     * <p>
     * This is the number the SharedInstance decision is made on. It is one operation, not
     * {@code 441}, so the reported score is the price of a single instance change and
     * needs no multiplication — and it is measured over distinct chunks, so it includes the memory
     * traffic of walking a whole column rather than the cache resident cost of one chunk repeated.
     * </p>
     * <p>
     * The returned sum is the byte volume of the resend. It exists to keep the framed buffers alive
     * until the end of the loop, so that no part of the serialisation is eliminated as dead, and it
     * is a second, independent statement of the volume the setup already reported.
     * </p>
     *
     * @return the total amount of bytes the resend would put on the wire
     */
    @Benchmark
    public long resendViewDistance10() {
        long bytes = 0;

        for (Chunk chunk : this.column) {
            bytes += serialize(buildPacket(chunk)).readableBytes();
        }
        return bytes;
    }

    /**
     * Builds the full chunk packet of a chunk.
     * <p>
     * The route is {@code Chunk#getFullDataPacket} followed by
     * {@code SendablePacket#extractServerPacket}, because {@code createChunkPacket} is private and
     * the {@code CachedPacket} it hangs in is the only handle Minestom offers. With the packet cache
     * disabled that pair resolves to a plain call of the supplier, which is the private method
     * itself.
     * </p>
     *
     * @param chunk the chunk to build the packet of
     * @return the built packet
     * @throws IllegalStateException if the chunk answers with a packet that cannot be extracted
     */
    private static ServerPacket buildPacket(Chunk chunk) {
        final SendablePacket sendable = chunk.getFullDataPacket();
        final ServerPacket packet = SendablePacket.extractServerPacket(ConnectionState.PLAY, sendable);

        if (packet == null) {
            throw new IllegalStateException("The chunk " + chunk.getChunkX() + ":" + chunk.getChunkZ()
                    + " answered with " + sendable.getClass().getName()
                    + ", which holds no extractable packet, so the build cannot be measured");
        }
        return packet;
    }

    /**
     * Serialises, frames and compresses a packet the way the connection layer does.
     *
     * @param packet the packet to put on the wire
     * @return the framed buffer
     */
    private NetworkBuffer serialize(ServerPacket packet) {
        return PacketWriting.allocateTrimmedPacket(ConnectionState.PLAY, packet, this.compressionThreshold);
    }

    /**
     * Writes the blocks of the parameter into the source chunk.
     *
     * @param chunk the chunk to fill
     */
    private void applyContent(Chunk chunk) {
        switch (this.content) {
            case EMPTY -> {
                // Nothing is written: a fresh chunk is already air in every position, and every
                // MinestomChunks fill refuses to leave a chunk that way on purpose.
            }
            case UNIFORM -> MinestomChunks.fill(chunk, 1, MinestomChunks.FillShape.UNIFORM);
            case TERRAIN -> {
                MinestomChunks.fill(chunk, TERRAIN_STATES, MinestomChunks.FillShape.RANDOM_RUNS);
                carveSkyAbove(chunk);
            }
            case DENSE -> MinestomChunks.fill(chunk, DENSE_STATES, MinestomChunks.FillShape.UNIFORM);
        }
    }

    /**
     * Replaces everything above {@code y=64} with air, turning a fully filled chunk into a
     * chunk with a surface.
     * <p>
     * This walk is here rather than in {@code MinestomChunks} because the fixture has no Y bounded
     * shape: all three of its fills cover the whole column from the bottom of the world to the build
     * limit, which is the right choice for a palette or footprint measurement and the wrong one for a
     * packet, where the ratio of solid sections to air sections decides both the block data and the
     * light. The gap is worth naming rather than working around silently.
     * </p>
     * <p>
     * The order is Y descending, and that is what keeps the walk affordable. Removing the block that
     * currently defines the height of a column sends {@code Heightmap#refresh} back down that column
     * until it finds the next matching block ({@code Heightmap.java:40-46}); going downwards means the
     * next block is always the one immediately below, so every one of the {@code 65536} writes costs a
     * short scan instead of a full column scan. Going upwards would make the same carve quadratic.
     * </p>
     *
     * @param chunk the chunk to carve
     */
    private static void carveSkyAbove(Chunk chunk) {
        final int maxY = chunk.getMaxSection() * Chunk.CHUNK_SECTION_SIZE;

        chunk.lockWriteLock();
        try {
            for (int y = maxY - 1; y >= SURFACE_Y; y--) {
                for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                    for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
                        chunk.setBlock(x, y, z, Block.AIR);
                    }
                }
            }
        } finally {
            chunk.unlockWriteLock();
        }
    }

    /**
     * Fills the sky light of every section the content leaves open to the sky.
     * <p>
     * The array is written through {@code Section#setSkyLight}, which is the same entry point
     * {@code Section#clone} and the Anvil loader use, so no light engine has to run and the copies of
     * this chunk inherit the light along with the palettes. Every byte is set to {@code -1}, which is
     * two nibbles of level {@code 15}; {@code LightCompute#lazyArray} recognises that pattern and
     * folds the array into its shared fully lit constant, so seeding {@code 24} sections costs one
     * array rather than {@code 24}.
     * </p>
     * <p>
     * Block light stays untouched and therefore empty. The fixture places no light emitting block, so
     * a block light array would be all zeroes, and an all zero array is precisely what
     * {@code createLightData} drops from the packet.
     * </p>
     *
     * @param chunk the chunk to light
     */
    private void seedSkyLight(Chunk chunk) {
        final byte[] fullyLit = new byte[LIGHT_ARRAY_LENGTH];
        Arrays.fill(fullyLit, (byte) -1);

        final List<Section> sections = chunk.getSections();
        final int firstLit = firstLitSection(chunk);

        chunk.lockWriteLock();
        try {
            for (int index = firstLit; index < sections.size(); index++) {
                sections.get(index).setSkyLight(fullyLit);
            }
        } finally {
            chunk.unlockWriteLock();
        }
    }

    /**
     * Returns the index of the lowest section the content leaves open to the sky.
     *
     * @param chunk the chunk the index refers to
     * @return the first lit section, or the section count if the content is dark throughout
     */
    private int firstLitSection(Chunk chunk) {
        return switch (this.content) {
            case EMPTY -> 0;
            case TERRAIN -> Math.floorDiv(SURFACE_Y, Chunk.CHUNK_SECTION_SIZE) - chunk.getMinSection();
            case UNIFORM, DENSE -> chunk.getSections().size();
        };
    }

    /**
     * Builds the column of chunks a player at view distance {@code 10} holds.
     * <p>
     * The coordinates come from {@code ChunkRange#chunksInRange}, the very method
     * {@code Player#setInstance} uses to decide which chunks to load, so the column is the set the
     * slow path would really send rather than a square somebody wrote out by hand. The source chunk
     * takes the first coordinate, which the spiral emits as the centre, and every further coordinate
     * gets a copy.
     * </p>
     *
     * @param source the filled and lit chunk every entry is copied from
     * @return the column, with the source as its first entry
     * @throws IllegalStateException if the range does not produce {@code 441} coordinates
     */
    private Chunk[] buildColumn(Chunk source) {
        final List<long[]> coordinates = new ArrayList<>(RESEND_CHUNKS);
        ChunkRange.chunksInRange(0, 0, VIEW_DISTANCE, (chunkX, chunkZ) ->
                coordinates.add(new long[]{chunkX, chunkZ}));

        if (coordinates.size() != RESEND_CHUNKS) {
            throw new IllegalStateException("The chunk range of view distance " + VIEW_DISTANCE
                    + " produced " + coordinates.size() + " coordinates instead of " + RESEND_CHUNKS
                    + ", so the column would not be the set a player receives");
        }
        final Chunk[] chunks = new Chunk[RESEND_CHUNKS];
        chunks[0] = source;

        for (int index = 1; index < RESEND_CHUNKS; index++) {
            final long[] coordinate = coordinates.get(index);

            source.lockReadLock();
            try {
                chunks[index] = source.copy(this.container, (int) coordinate[0], (int) coordinate[1]);
            } finally {
                source.unlockReadLock();
            }
        }
        return chunks;
    }

    /**
     * Verifies that the packet cache is disabled in this fork.
     * <p>
     * Without the flag {@code CachedPacket#packet} would answer from a soft reference and the build
     * methods would measure a field read, while the first call after a collection would measure a
     * full build. A benchmark that silently degrades into that has no defined meaning, so the trial
     * refuses to start.
     * </p>
     *
     * @throws IllegalStateException if {@code ServerFlag.CACHED_PACKET} is on
     */
    private static void requireDisabledPacketCache() {
        if (!ServerFlag.CACHED_PACKET) {
            return;
        }
        throw new IllegalStateException("The packet cache is enabled, so a build cannot be told apart"
                + " from a cache hit. Run this benchmark with -Dminestom.cached-packet=false, which"
                + " the @Fork annotation of the class normally supplies");
    }

    /**
     * Verifies that rebuilding and reserialising the same chunk yields the same bytes.
     * <p>
     * The premise of the measurement: a resend carries data the client already holds. If a rebuild
     * produced different bytes, the resend would be necessary rather than redundant and the whole
     * question would be malformed.
     * </p>
     *
     * @throws IllegalStateException if the two serialisations differ
     */
    private void verifyRebuildIsIdentical() {
        final byte[] first = bytesOf(this.column[0]);
        final byte[] second = bytesOf(this.column[0]);

        if (Arrays.equals(first, second)) {
            return;
        }
        throw new IllegalStateException("Rebuilding the packet of chunk 0:0 produced " + second.length
                + " bytes against " + first.length + " bytes before, or the same length with different"
                + " content, so a resend would not be redundant work and this benchmark would be"
                + " measuring the wrong question (content=" + this.content + ")");
    }

    /**
     * Verifies that every chunk of the column serialises to the same uncompressed length.
     * <p>
     * The compressed lengths are deliberately not required to match. The chunk coordinates are part of
     * the packet and differ per chunk, and deflate is free to answer a different length for a
     * different input, so equality there would be a check on the compressor rather than on the
     * fixture. The uncompressed length has no such freedom: it is a pure function of the palettes, the
     * heightmaps and the light, all of which the copies share.
     * </p>
     *
     * @throws IllegalStateException if two chunks of the column serialise to different lengths
     */
    private void verifyColumnIsHomogeneous() {
        final long expected = payloadBytesOf(this.column[0]);

        for (int index = 1; index < this.column.length; index++) {
            final long actual = payloadBytesOf(this.column[index]);

            if (actual == expected) {
                continue;
            }
            final Chunk chunk = this.column[index];
            throw new IllegalStateException("The chunk " + chunk.getChunkX() + ":" + chunk.getChunkZ()
                    + " serialises to " + actual + " bytes while the source serialises to " + expected
                    + ", so the column is not homogeneous and a per chunk average over it would"
                    + " describe no chunk in particular (content=" + this.content + ")");
        }
    }

    /**
     * Verifies that a sample of the copies still holds the content of the source.
     *
     * @param source the chunk every entry of the column was copied from
     * @throws IllegalStateException if a sampled copy differs from the source in a block or a height
     */
    private void verifyCopiesMatchSource(Chunk source) {
        final int last = this.column.length - 1;
        final int[] samples = {1, last / 2, last};

        for (int index = 0; index < COMPARED_COPIES; index++) {
            MinestomChunks.assertSameBlocks(source, this.column[samples[index]]);
        }
    }

    /**
     * Verifies that every chunk of the column carries the sky light its content implies.
     * <p>
     * The light is the largest single contributor to the byte volume of the contents that have any,
     * and it is inherited through {@code Section#clone} rather than written per chunk, so a fixture
     * that lost it would look correct in every block and still report a resend at a fraction of its
     * real size. The read goes through {@code Light#array}, which is the same method
     * {@code createLightData} uses to decide whether a section contributes bytes at all.
     * </p>
     *
     * @throws IllegalStateException if a chunk holds a different amount of lit sections than expected
     */
    private void verifySkyLightMatchesContent() {
        final int expected = this.column[0].getSections().size() - firstLitSection(this.column[0]);

        for (Chunk chunk : this.column) {
            final int actual = litSections(chunk);

            if (actual == expected) {
                continue;
            }
            throw new IllegalStateException("The chunk " + chunk.getChunkX() + ":" + chunk.getChunkZ()
                    + " carries sky light in " + actual + " sections instead of " + expected
                    + ", so its packet would not hold the light the content implies (content="
                    + this.content + ")");
        }
    }

    /**
     * Counts the sections of a chunk whose sky light would be written into a chunk packet.
     *
     * @param chunk the chunk to count in
     * @return the amount of sections with a non empty sky light array
     */
    private static int litSections(Chunk chunk) {
        int lit = 0;

        chunk.lockReadLock();
        try {
            for (Section section : chunk.getSections()) {
                if (section.skyLight().array().length != 0) {
                    lit++;
                }
            }
        } finally {
            chunk.unlockReadLock();
        }
        return lit;
    }

    /**
     * Prints the byte volume of the column once per trial.
     * <p>
     * The volume is a constant of the fixture rather than a measurement, and JMH reports measurements
     * only. It is also the half of the answer that does not depend on the machine the benchmark runs
     * on: the microseconds differ per host, the kilobytes do not. Leaving it out of the output would
     * mean deriving it from a score, and deriving the primary result of a benchmark is how it gets
     * misread.
     * </p>
     */
    private void reportByteVolume() {
        final long payloadPerChunk = payloadBytesOf(this.column[0]);
        long wireTotal = 0;
        long wireMin = Long.MAX_VALUE;
        long wireMax = 0;

        for (Chunk chunk : this.column) {
            final long wire = serialize(buildPacket(chunk)).readableBytes();
            wireTotal += wire;
            wireMin = Math.min(wireMin, wire);
            wireMax = Math.max(wireMax, wire);
        }
        System.out.println("# ChunkResendCostBenchmark"
                + " content=" + this.content
                + " viewDistance=" + VIEW_DISTANCE
                + " chunks=" + RESEND_CHUNKS
                + " litSkySections=" + litSections(this.column[0])
                + " compressionThreshold=" + this.compressionThreshold
                + " payloadBytesPerChunk=" + payloadPerChunk
                + " wireBytesPerChunk=[" + wireMin + ", " + wireMax + "]"
                + " payloadBytesPerResend=" + payloadPerChunk * RESEND_CHUNKS
                + " wireBytesPerResend=" + wireTotal);
    }

    /**
     * Returns the framed and compressed bytes of the packet of a chunk.
     *
     * @param chunk the chunk to serialise
     * @return the bytes the socket would carry
     */
    private byte[] bytesOf(Chunk chunk) {
        final NetworkBuffer buffer = serialize(buildPacket(chunk));
        final byte[] bytes = new byte[(int) buffer.readableBytes()];

        buffer.copyTo(0, bytes, 0, bytes.length);
        return bytes;
    }

    /**
     * Returns the uncompressed serialised size of the packet of a chunk, without the frame.
     * <p>
     * {@code NetworkBuffer.Type#sizeOf} walks the packet the same way a write does but counts instead
     * of storing, so this is the amount of bytes the serializer produced before deflate and before the
     * two length prefixes of the compressed frame.
     * </p>
     *
     * @param chunk the chunk to measure
     * @return the serialised size of its packet in bytes
     */
    private long payloadBytesOf(Chunk chunk) {
        return ChunkDataPacket.SERIALIZER.sizeOf(asChunkDataPacket(buildPacket(chunk)), this.process);
    }

    /**
     * Narrows a built packet to the type the chunk path is supposed to produce.
     *
     * @param packet the packet to narrow
     * @return the packet as a {@code ChunkDataPacket}
     * @throws IllegalStateException if the chunk produced a packet of another type
     */
    private static ChunkDataPacket asChunkDataPacket(ServerPacket packet) {
        if (packet instanceof ChunkDataPacket chunkData) {
            return chunkData;
        }
        throw new IllegalStateException("The chunk produced a " + packet.getClass().getName()
                + " instead of a ChunkDataPacket, so its size cannot be attributed to a chunk resend");
    }
}
