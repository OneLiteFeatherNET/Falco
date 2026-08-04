package net.onelitefeather.falco.anvil;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The {@link FalcoAnvilLoader} class loads and saves chunks in the Anvil format and replaces the
 * loader which Minestom ships with.
 * <p>
 * The work of a chunk is split into three stages so the expensive part never happens while a lock
 * is held. The chunk state is copied under the read lock of the chunk, the conversion between that
 * copy and the compressed bytes runs without any lock at all, and only the transfer of those bytes
 * into the region file is guarded. That is the difference which makes parallel access worthwhile,
 * because a region file which serializes decompression and parsing gains nothing from more threads.
 * </p>
 * <p>
 * A chunk which cannot be read is reported as an error instead of being reported as absent. An
 * absent chunk makes the server generate a new one which then overwrites the real data on the next
 * save, so a read failure has to stay visible.
 * </p>
 * <p>
 * A region file is never closed while a thread is reading from or writing to it. Every access
 * registers itself on the handle first, and a handle which is dropped from the cache while it is
 * still registered is closed by the last thread which leaves it. Dropping and closing are therefore
 * two separate steps, which is what allows an unload or an eviction to happen at any moment without
 * failing the work that is already running.
 * </p>
 * <p>
 * A loader which was closed refuses further work with an {@link IllegalStateException}. Silently
 * ignoring a load would report the chunk as absent and make the server overwrite it, and silently
 * ignoring a save would drop chunk data during the shutdown it belongs to.
 * </p>
 *
 * <p>
 * This type is experimental. The Anvil loader is new and its API may still change while it is
 * being validated against real worlds.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.3.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public final class FalcoAnvilLoader implements ChunkLoader, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(FalcoAnvilLoader.class);

    private static final BinaryTagIO.Reader TAG_READER = BinaryTagIO.unlimitedReader();
    private static final BinaryTagIO.Writer TAG_WRITER = BinaryTagIO.writer();

    private static final String SECTIONS_KEY = "sections";
    private static final String DATA_VERSION_KEY = "DataVersion";
    private static final String BLOCK_STATES_KEY = "block_states";
    private static final String BIOMES_KEY = "biomes";
    private static final String BLOCK_ENTITIES_KEY = "block_entities";
    private static final String STATUS_KEY = "Status";
    private static final String LEGACY_STATUS_KEY = "status";
    private static final String FULL_STATUS = "minecraft:full";

    private static final int BLOCK_ENTRIES = 16 * 16 * 16;
    private static final int BIOME_ENTRIES = 4 * 4 * 4;

    /**
     * The amount of region files a loader keeps open by default.
     */
    public static final int DEFAULT_OPEN_REGION_LIMIT = 64;

    /**
     * The lowest data version the loader reads by default: {@code 21w43a}, the first version whose
     * chunks carry {@code sections} on the root compound instead of under {@code Level}.
     *
     * @since 1.1.0
     */
    public static final int DEFAULT_MINIMUM_DATA_VERSION = 2844;

    private final int openRegionLimit;
    private final int compressionLevel;
    private final Path regionDirectory;
    private final boolean legacyLayout;
    private final String dimensionLabel;
    private final AnvilDiagnostics diagnostics;
    private final PaletteEntryResolver blockResolver;
    private final PaletteEntryResolver biomeResolver;
    private final Map<Long, RegionHandle> regions;
    private final Map<Long, Set<Long>> trackedChunks;
    private final Semaphore saveLimit;
    private final int dataVersion;
    private final int minimumDataVersion;

    /**
     * The policy consulted before a chunk is decoded, or null to skip that check entirely.
     * <p>
     * Resolved once, in the constructor, through {@link ServiceResolution#choose(Class, Object,
     * boolean, Class)}, naming {@link DefaultChunkVersionPolicy} as the shipped default — so a
     * foreign {@link ChunkVersionPolicy} registered through {@code META-INF/services} is chosen over
     * it, and only a second foreign provider is refused as ambiguous. A builder which never touches
     * {@link Builder#versionPolicy(ChunkVersionPolicy)} or {@link Builder#discoverVersionPolicy()}
     * discovers a policy through the classpath by default, which is what keeps every loader built
     * the way earlier versions built one refusing the same chunks it always refused. Calling
     * {@code versionPolicy(null)} is the only way to leave this field null, and
     * {@link #checkVersion(CompoundBinaryTag)} treats that as "check nothing" rather than
     * substituting the default itself.
     * </p>
     *
     * @since 2.1.0
     */
    private final @Nullable ChunkVersionPolicy versionPolicy;

    /**
     * The policy consulted for a palette entry the running server does not know, resolved once in
     * the constructor the same way {@link #versionPolicy} is: through {@link
     * ServiceResolution#choose(Class, Object, boolean, Class)}, naming {@link
     * DefaultUnknownEntryPolicy} as the shipped default.
     * <p>
     * Unlike {@link #versionPolicy}, this field is never null. A builder which never touches
     * {@link Builder#unknownEntryPolicy(UnknownEntryPolicy)} or
     * {@link Builder#discoverUnknownEntryPolicy()} discovers a policy from the classpath by default,
     * falling back to {@link DefaultUnknownEntryPolicy} when the classpath registers none — there is
     * no "consult nothing" state for this decision the way {@code versionPolicy(null)} lets a caller
     * skip the version check entirely, because an id is always required for the loader to keep
     * decoding.
     * </p>
     *
     * @since 2.1.0
     */
    private final UnknownEntryPolicy unknownEntryPolicy;

    /**
     * Where failures are reported, or null for the exception manager of the running server.
     * <p>
     * Null rather than a captured default on purpose. {@code MinecraftServer.getExceptionManager()}
     * reads a field which only {@code MinecraftServer.init()} sets, so resolving it while the loader
     * is built would turn a loader created before the server into one that dies in its own error
     * path — on a null pointer which hides whatever actually went wrong.
     * </p>
     */
    private final @Nullable Consumer<Throwable> exceptionHandler;

    /**
     * The lock which serialises the shutdown of the loader.
     * <p>
     * A private lock rather than the monitor of the loader itself, in the shape the region file
     * already uses. The monitor of a public object is held by whoever holds a reference to it, so a
     * caller writing {@code synchronized (loader)} could keep the shutdown from ever running.
     * </p>
     */
    private final ReentrantLock closeLock;

    private volatile boolean closed;

    /**
     * Creates a new loader for the given world directory and dimension.
     *
     * @param worldRoot the root directory of the world
     * @param dimension the key of the dimension the loader reads and writes
     * @throws IllegalStateException if the classpath registers more than one foreign
     *                                {@link ChunkVersionPolicy}
     */
    public FalcoAnvilLoader(Path worldRoot, Key dimension) {
        this(worldRoot, dimension, DEFAULT_OPEN_REGION_LIMIT);
    }

    /**
     * Creates a new loader which keeps at most the given amount of region files open.
     * <p>
     * A region file is normally closed as soon as every chunk this loader took from it has been
     * unloaded. The limit is the second line of defence for the case that unload calls never
     * arrive, for example because chunks stay loaded for the whole lifetime of the server. An
     * evicted file is reopened transparently on the next access.
     * </p>
     *
     * @param worldRoot       the root directory of the world
     * @param dimension       the key of the dimension the loader reads and writes
     * @param openRegionLimit the amount of region files the loader keeps open
     * @throws IllegalArgumentException if the limit is not positive
     * @throws IllegalStateException    if the classpath registers more than one foreign
     *                                   {@link ChunkVersionPolicy}
     */
    public FalcoAnvilLoader(Path worldRoot, Key dimension, int openRegionLimit) {
        this(worldRoot, dimension, builder().openRegionLimit(openRegionLimit));
    }

    /**
     * Creates a loader from the values collected by a builder.
     * <p>
     * Everything that has to exist once per loader is created here rather than in the builder, which
     * is what lets one builder produce several independent loaders: the region cache, the tracked
     * chunks, the save permit and, unless the caller named one, the diagnostics.
     * </p>
     *
     * @param worldRoot the root directory of the world
     * @param dimension the key of the dimension the loader reads and writes
     * @param settings  the builder which holds the configured values
     */
    private FalcoAnvilLoader(Path worldRoot, Key dimension, Builder settings) {
        ResolvedRegionDirectory resolved = resolveRegionDirectory(worldRoot, dimension);
        // The resolvers see whichever diagnostics this loader ends up with, exactly as the
        // constructor does: the counters of a resolver and those the loader reports have to be the
        // same object, or logSummary reports zero unknown blocks past a resolver that counted them.
        AnvilDiagnostics effective =
                settings.diagnostics == null ? new AnvilDiagnostics() : settings.diagnostics;

        this.openRegionLimit = settings.openRegionLimit;
        this.compressionLevel = settings.compressionLevel;
        this.regionDirectory = resolved.directory();
        this.legacyLayout = resolved.legacyLayout();
        this.dimensionLabel = dimension.asString();
        this.diagnostics = effective;
        // A caller who names both a policy and their own resolver has built a loader in which the
        // policy can never be reached: the resolver a builder is handed is used exactly as given,
        // never rebuilt around the configured policy, so unknownEntryPolicy() below would keep
        // reporting a policy that the actual decoding path never consults. Refusing the combination
        // holds this to the same standard ServiceResolution.choose already applies to explicit
        // configuration versus discovery: two conflicting explicit decisions are refused outright,
        // not silently reconciled by picking one of them. A resolver configured without touching
        // either unknownEntryPolicy slot is unaffected, because unknownEntryPolicyConfigured stays
        // false for a builder that never called unknownEntryPolicy(...) or
        // discoverUnknownEntryPolicy() itself.
        if (settings.unknownEntryPolicyConfigured && (settings.blockResolver != null || settings.biomeResolver != null)) {
            throw new IllegalStateException(
                    "An UnknownEntryPolicy was configured together with a custom "
                            + (settings.blockResolver != null ? "blockResolver" : "biomeResolver")
                            + ". A resolver supplied through the builder is used exactly as given and never "
                            + "sees the configured policy, so it would silently keep its own fallback instead. "
                            + "Pass the policy into the resolver you build instead, e.g. "
                            + "new BlockPaletteResolver(diagnostics, policy), and stop configuring it on this "
                            + "builder."
            );
        }
        UnknownEntryPolicy resolvedUnknownEntryPolicy = ServiceResolution.choose(
                UnknownEntryPolicy.class, settings.unknownEntryPolicy, settings.discoverUnknownEntryPolicy,
                DefaultUnknownEntryPolicy.class);
        this.unknownEntryPolicy = resolvedUnknownEntryPolicy == null
                ? new DefaultUnknownEntryPolicy()
                : resolvedUnknownEntryPolicy;
        this.blockResolver = settings.blockResolver == null
                ? new BlockPaletteResolver(effective, this.unknownEntryPolicy)
                : settings.blockResolver;
        this.biomeResolver = settings.biomeResolver == null
                ? new BiomePaletteResolver(effective, this.unknownEntryPolicy)
                : settings.biomeResolver;
        this.regions = new ConcurrentHashMap<>();
        this.trackedChunks = new ConcurrentHashMap<>();
        this.saveLimit = new Semaphore(settings.saveParallelism);
        this.dataVersion = settings.dataVersion;
        this.minimumDataVersion = settings.minimumDataVersion;
        this.exceptionHandler = settings.exceptionHandler;
        this.versionPolicy = ServiceResolution.choose(
                ChunkVersionPolicy.class, settings.versionPolicy, settings.discoverVersionPolicy,
                DefaultChunkVersionPolicy.class);
        this.closeLock = new ReentrantLock();

        // Which directory was chosen, and how many region files are in it, is the first thing
        // somebody needs when a loader returns no chunks. Without this line the choice between the
        // two layouts happens invisibly, and a world whose files sit in the other one looks exactly
        // like a world which is empty.
        LOGGER.info(
                "Opening the anvil loader for region={} layout={} exists={} regionFiles={} dim={} versionPolicy={}",
                this.regionDirectory,
                this.legacyLayout ? "legacy <world>/region" : "dimension <world>/dimensions/<namespace>/<value>/region",
                Files.isDirectory(this.regionDirectory),
                describeRegionFileCount(this.regionDirectory),
                this.dimensionLabel,
                this.versionPolicy == null ? "none" : this.versionPolicy.getClass().getName()
        );
    }

    /**
     * Reports a failure to the configured sink, or to the exception manager of the server.
     * <p>
     * The default is resolved here rather than when the loader is built, because
     * {@code MinecraftServer.getExceptionManager()} needs a server process which a loader may well
     * outlive on both ends — a tool that reads a world without ever starting a server has none at
     * all, and would get a null pointer instead of its actual failure.
     * </p>
     *
     * @param exception the failure to report
     */
    private void reportException(Throwable exception) {
        if (this.exceptionHandler == null) {
            MinecraftServer.getExceptionManager().handleException(exception);
            return;
        }
        this.exceptionHandler.accept(exception);
    }

    /**
     * Returns a builder for a loader whose defaults are those of the constructors.
     * <p>
     * The builder reaches the values the constructors set for themselves — the compression level,
     * the diagnostics, both palette resolvers, the save parallelism and the data version. It also
     * defaults to discovering a {@link ChunkVersionPolicy} and an {@link UnknownEntryPolicy} from
     * the classpath, which is what keeps every loader built through a constructor rather than an
     * explicit {@link Builder#versionPolicy(ChunkVersionPolicy)} or
     * {@link Builder#unknownEntryPolicy(UnknownEntryPolicy)} call behaving the way it always did.
     * The world directory and the dimension are not among them: they are required, so they sit in
     * {@link Builder#build(Path, Key)} rather than in a slot.
     * </p>
     *
     * @return a new builder with the defaults of the constructors
     */
    @Contract(value = "-> new", pure = true)
    public static Builder builder() {
        return new Builder(DEFAULT_OPEN_REGION_LIMIT, ChunkCompression.DEFAULT_LEVEL,
                Math.max(Runtime.getRuntime().availableProcessors(), 2), MinecraftServer.DATA_VERSION,
                DEFAULT_MINIMUM_DATA_VERSION, null, null, null, null, null, true, null, true, false);
    }

    /**
     * Collects the values of a loader before it is built.
     * <p>
     * <b>Immutable.</b> Every slot returns a new builder and leaves the one it was called on
     * untouched, the same shape the builders in {@code falco-light} and {@code falco-instance} have.
     * A mixture would be a trap: the same line written against two of them would mean two different
     * things, and the one that silently does nothing is the one nobody notices.
     * </p>
     * <p>
     * The builder can be reused. {@link #build(Path, Key)} may be called any number of times and
     * returns an independent loader every time, which is what a caller opening the same world for
     * several dimensions needs. Two properties follow from that and are stated on the slots
     * concerned: the diagnostics default to a <em>new</em> instance per {@code build}, and
     * {@link #saveParallelism(int)} applies per loader, so three loaders from one builder with
     * {@code saveParallelism(4)} perform twelve concurrent saves and not four.
     * </p>
     * <p>
     * Slots which take a bounded number check it immediately rather than in {@code build}. A wrong
     * compression level does not fail construction: {@code saveChunk} catches the exception and
     * swallows it with a log line, so the world would silently stop being written.
     * </p>
     * <p>
     * This type is experimental, like everything else in this package.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.3.0
     * @since 0.4.0
     */
    @ApiStatus.Experimental
    public static final class Builder {

        private final int openRegionLimit;
        private final int compressionLevel;
        private final int saveParallelism;
        private final int dataVersion;
        private final int minimumDataVersion;
        private final @Nullable AnvilDiagnostics diagnostics;
        private final @Nullable PaletteEntryResolver blockResolver;
        private final @Nullable PaletteEntryResolver biomeResolver;
        private final @Nullable Consumer<Throwable> exceptionHandler;
        private final @Nullable ChunkVersionPolicy versionPolicy;
        private final boolean discoverVersionPolicy;
        private final @Nullable UnknownEntryPolicy unknownEntryPolicy;
        private final boolean discoverUnknownEntryPolicy;
        private final boolean unknownEntryPolicyConfigured;

        private Builder(int openRegionLimit, int compressionLevel, int saveParallelism, int dataVersion,
                        int minimumDataVersion, @Nullable AnvilDiagnostics diagnostics,
                        @Nullable PaletteEntryResolver blockResolver,
                        @Nullable PaletteEntryResolver biomeResolver,
                        @Nullable Consumer<Throwable> exceptionHandler,
                        @Nullable ChunkVersionPolicy versionPolicy,
                        boolean discoverVersionPolicy,
                        @Nullable UnknownEntryPolicy unknownEntryPolicy,
                        boolean discoverUnknownEntryPolicy,
                        boolean unknownEntryPolicyConfigured) {
            this.openRegionLimit = openRegionLimit;
            this.compressionLevel = compressionLevel;
            this.saveParallelism = saveParallelism;
            this.dataVersion = dataVersion;
            this.minimumDataVersion = minimumDataVersion;
            this.diagnostics = diagnostics;
            this.blockResolver = blockResolver;
            this.biomeResolver = biomeResolver;
            this.exceptionHandler = exceptionHandler;
            this.versionPolicy = versionPolicy;
            this.discoverVersionPolicy = discoverVersionPolicy;
            this.unknownEntryPolicy = unknownEntryPolicy;
            this.discoverUnknownEntryPolicy = discoverUnknownEntryPolicy;
            this.unknownEntryPolicyConfigured = unknownEntryPolicyConfigured;
        }

        /**
         * Sets how many region files the loader keeps open.
         * <p>
         * A region file is normally closed as soon as every chunk taken from it has been unloaded.
         * The limit is the second line of defence for the case that unload calls never arrive.
         * </p>
         *
         * @param openRegionLimit the amount of region files the loader keeps open
         * @return a new builder with this value
         * @throws IllegalArgumentException if the limit is not positive
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder openRegionLimit(int openRegionLimit) {
            if (openRegionLimit <= 0) {
                throw new IllegalArgumentException("The amount of open region files must be positive but was " + openRegionLimit);
            }
            return new Builder(openRegionLimit,
                    this.compressionLevel,
                    this.saveParallelism,
                    this.dataVersion,
                    this.minimumDataVersion,
                    this.diagnostics,
                    this.blockResolver,
                    this.biomeResolver,
                    this.exceptionHandler,
                    this.versionPolicy,
                    this.discoverVersionPolicy,
                    this.unknownEntryPolicy,
                    this.discoverUnknownEntryPolicy,
                    this.unknownEntryPolicyConfigured);
        }

        /**
         * Sets the deflate level the loader writes chunks with.
         * <p>
         * The default is {@link ChunkCompression#DEFAULT_LEVEL}. A caller who writes a world once
         * and reads it often is the reason this slot exists — a higher level costs write time and
         * saves read time for the entire life of the world.
         * </p>
         *
         * @param compressionLevel the deflate level between {@link ChunkCompression#FASTEST_LEVEL}
         *                         and {@link ChunkCompression#SMALLEST_LEVEL}
         * @return a new builder with this value
         * @throws IllegalArgumentException if the level is outside the supported range
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder compressionLevel(int compressionLevel) {
            if (compressionLevel < ChunkCompression.FASTEST_LEVEL || compressionLevel > ChunkCompression.SMALLEST_LEVEL) {
                throw new IllegalArgumentException("The compression level has to be between "
                        + ChunkCompression.FASTEST_LEVEL + " and " + ChunkCompression.SMALLEST_LEVEL
                        + " but was " + compressionLevel);
            }
            return new Builder(this.openRegionLimit,
                    compressionLevel,
                    this.saveParallelism,
                    this.dataVersion,
                    this.minimumDataVersion,
                    this.diagnostics,
                    this.blockResolver,
                    this.biomeResolver,
                    this.exceptionHandler,
                    this.versionPolicy,
                    this.discoverVersionPolicy,
                    this.unknownEntryPolicy,
                    this.discoverUnknownEntryPolicy,
                    this.unknownEntryPolicyConfigured);
        }

        /**
         * Sets how many chunks the loader saves at the same time.
         * <p>
         * The bound belongs to one loader. Several loaders from one builder each get their own, so
         * the concurrent saves of a server are this number times the amount of loaders.
         * </p>
         *
         * @param saveParallelism the amount of chunks saved concurrently by one loader
         * @return a new builder with this value
         * @throws IllegalArgumentException if the amount is smaller than one
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder saveParallelism(int saveParallelism) {
            if (saveParallelism < 1) {
                throw new IllegalArgumentException("A loader has to be able to save at least one chunk at a time but the bound was " + saveParallelism);
            }
            return new Builder(this.openRegionLimit,
                    this.compressionLevel,
                    saveParallelism,
                    this.dataVersion,
                    this.minimumDataVersion,
                    this.diagnostics,
                    this.blockResolver,
                    this.biomeResolver,
                    this.exceptionHandler,
                    this.versionPolicy,
                    this.discoverVersionPolicy,
                    this.unknownEntryPolicy,
                    this.discoverUnknownEntryPolicy,
                    this.unknownEntryPolicyConfigured);
        }

        /**
         * Sets the data version the loader stamps onto the chunks it writes.
         * <p>
         * The default is the data version of the Minestom that Falco was compiled against, which is
         * inlined by the compiler and is therefore not necessarily the one the caller runs against.
         * This slot is the only way to write a world for a divergent Minestom.
         * </p>
         *
         * @param dataVersion the data version written into every saved chunk
         * @return a new builder with this value
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder dataVersion(int dataVersion) {
            return new Builder(this.openRegionLimit,
                    this.compressionLevel,
                    this.saveParallelism,
                    dataVersion,
                    this.minimumDataVersion,
                    this.diagnostics,
                    this.blockResolver,
                    this.biomeResolver,
                    this.exceptionHandler,
                    this.versionPolicy,
                    this.discoverVersionPolicy,
                    this.unknownEntryPolicy,
                    this.discoverUnknownEntryPolicy,
                    this.unknownEntryPolicyConfigured);
        }

        /**
         * Sets the lowest data version the loader accepts when reading a chunk.
         * <p>
         * This is the read side and has nothing to do with {@link #dataVersion(int)}, which is the
         * version written into every saved chunk. A chunk below this floor is refused rather than
         * read, because the layout it carries would otherwise decode to air.
         * </p>
         *
         * @param minimumDataVersion the lowest data version the loader accepts
         * @return a new builder with this value
         * @throws IllegalArgumentException if the version is negative
         * @since 1.1.0
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder minimumDataVersion(int minimumDataVersion) {
            if (minimumDataVersion < 0) {
                throw new IllegalArgumentException(
                        "The minimum data version must not be negative but was " + minimumDataVersion);
            }
            return new Builder(this.openRegionLimit,
                    this.compressionLevel,
                    this.saveParallelism,
                    this.dataVersion,
                    minimumDataVersion,
                    this.diagnostics,
                    this.blockResolver,
                    this.biomeResolver,
                    this.exceptionHandler,
                    this.versionPolicy,
                    this.discoverVersionPolicy,
                    this.unknownEntryPolicy,
                    this.discoverUnknownEntryPolicy,
                    this.unknownEntryPolicyConfigured);
        }

        /**
         * Sets the diagnostics the loader counts into.
         * <p>
         * Without this slot every {@link #build(Path, Key)} creates its own instance, which is what
         * keeps two loaders from sharing their counters <em>and</em> the throttles of their
         * warnings — one world would stop being warned about because another had used the warning
         * up. Pass one instance here to deliberately collect several loaders into one place.
         * </p>
         * <p>
         * The default resolvers are created from whichever diagnostics are effective, so the order
         * of the calls on this builder does not matter.
         * </p>
         *
         * @param diagnostics the diagnostics of the loader
         * @return a new builder with this value
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder diagnostics(AnvilDiagnostics diagnostics) {
            return new Builder(this.openRegionLimit,
                    this.compressionLevel,
                    this.saveParallelism,
                    this.dataVersion,
                    this.minimumDataVersion,
                    diagnostics,
                    this.blockResolver,
                    this.biomeResolver,
                    this.exceptionHandler,
                    this.versionPolicy,
                    this.discoverVersionPolicy,
                    this.unknownEntryPolicy,
                    this.discoverUnknownEntryPolicy,
                    this.unknownEntryPolicyConfigured);
        }

        /**
         * Sets the resolver which turns block palette entries into state ids.
         * <p>
         * <b>A resolver of your own does not count into the diagnostics of the loader.</b> The
         * default is created from the effective diagnostics; a foreign resolver counts wherever it
         * was built to count, and the closing summary of the loader then reports zero unknown
         * blocks although the resolver saw them. {@link PaletteEntryResolver} exposes no
         * diagnostics, so this cannot be checked in {@code build}.
         * </p>
         * <p>
         * <b>Do not combine this with {@link #unknownEntryPolicy(UnknownEntryPolicy)} or
         * {@link #discoverUnknownEntryPolicy()}.</b> Whatever policy those slots resolve to is never
         * handed to a resolver supplied here — {@link #build(Path, Key)} refuses the combination
         * rather than build a loader whose configured policy is silently unreachable. Pass the
         * policy into your own resolver instead, the same way the shipped resolvers accept one in
         * their constructor.
         * </p>
         *
         * @param blockResolver the resolver for block palette entries
         * @return a new builder with this value
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder blockResolver(PaletteEntryResolver blockResolver) {
            return new Builder(this.openRegionLimit,
                    this.compressionLevel,
                    this.saveParallelism,
                    this.dataVersion,
                    this.minimumDataVersion,
                    this.diagnostics,
                    blockResolver,
                    this.biomeResolver,
                    this.exceptionHandler,
                    this.versionPolicy,
                    this.discoverVersionPolicy,
                    this.unknownEntryPolicy,
                    this.discoverUnknownEntryPolicy,
                    this.unknownEntryPolicyConfigured);
        }

        /**
         * Sets the resolver which turns biome palette entries into ids.
         * <p>
         * The same caveat as {@link #blockResolver(PaletteEntryResolver)}: a resolver of your own
         * counts past the diagnostics of the loader, and the same restriction against combining it
         * with {@link #unknownEntryPolicy(UnknownEntryPolicy)} or
         * {@link #discoverUnknownEntryPolicy()} applies, for the same reason.
         * </p>
         *
         * @param biomeResolver the resolver for biome palette entries
         * @return a new builder with this value
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder biomeResolver(PaletteEntryResolver biomeResolver) {
            return new Builder(this.openRegionLimit,
                    this.compressionLevel,
                    this.saveParallelism,
                    this.dataVersion,
                    this.minimumDataVersion,
                    this.diagnostics,
                    this.blockResolver,
                    biomeResolver,
                    this.exceptionHandler,
                    this.versionPolicy,
                    this.discoverVersionPolicy,
                    this.unknownEntryPolicy,
                    this.discoverUnknownEntryPolicy,
                    this.unknownEntryPolicyConfigured);
        }

        /**
         * Sets where the loader reports the failures it survives.
         * <p>
         * This moves the sink, not the control flow: a chunk which cannot be read still throws
         * afterwards, and a chunk which cannot be saved is still swallowed with a log line. The
         * asymmetry is deliberate — a chunk reported as absent would be regenerated and would
         * overwrite the real data.
         * </p>
         * <p>
         * Beyond metrics, the slot has a hard reason. The default needs
         * {@code MinecraftServer.getExceptionManager()}, which reads a field only
         * {@code MinecraftServer.init()} sets; a loader used without a server process would die in
         * its error path on a null pointer that hides the actual cause. Naming a sink here avoids
         * that entirely.
         * </p>
         *
         * @param exceptionHandler the sink which receives every reported failure
         * @return a new builder with this value
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder exceptionHandler(Consumer<Throwable> exceptionHandler) {
            return new Builder(this.openRegionLimit,
                    this.compressionLevel,
                    this.saveParallelism,
                    this.dataVersion,
                    this.minimumDataVersion,
                    this.diagnostics,
                    this.blockResolver,
                    this.biomeResolver,
                    exceptionHandler,
                    this.versionPolicy,
                    this.discoverVersionPolicy,
                    this.unknownEntryPolicy,
                    this.discoverUnknownEntryPolicy,
                    this.unknownEntryPolicyConfigured);
        }

        /**
         * Sets the policy consulted before a chunk is decoded, or clears it so nothing is consulted.
         * <p>
         * Calling this slot always turns discovery off, whatever value is passed: an explicit
         * decision about the policy — including the explicit decision "none" — has to win over the
         * classpath without {@link ServiceResolution#choose(Class, Object, boolean, Class)} seeing
         * both set at once and refusing to guess between them. A builder that never calls this slot
         * keeps discovering the default instead, which is what {@link #builder()} starts with.
         * </p>
         * <p>
         * Passing {@code null} is not "use the default": it is "check nothing". A pre-{@code
         * 21w43a} chunk that would otherwise be refused loads as a chunk of air instead, exactly as
         * this loader read one before {@link ChunkVersionPolicy} existed. That cost is deliberate —
         * see {@code testWithoutAnyPolicyALegacyChunkIsNotChecked} in the loader's integration
         * tests for the case that documents it.
         * </p>
         * <p>
         * An explicit instance passed here is shared by every loader this builder builds afterward,
         * the same way an explicit {@link #diagnostics(AnvilDiagnostics)} instance is — and
         * {@link ChunkVersionPolicy} is called from every one of those loaders' parallel loads at
         * once, so it has to tolerate that sharing the way {@link AnvilDiagnostics} already does.
         * </p>
         *
         * @param versionPolicy the policy to consult before a chunk is decoded, or null to consult
         *                      none
         * @return a new builder with this value
         * @since 2.1.0
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder versionPolicy(@Nullable ChunkVersionPolicy versionPolicy) {
            return new Builder(this.openRegionLimit,
                    this.compressionLevel,
                    this.saveParallelism,
                    this.dataVersion,
                    this.minimumDataVersion,
                    this.diagnostics,
                    this.blockResolver,
                    this.biomeResolver,
                    this.exceptionHandler,
                    versionPolicy,
                    false,
                    this.unknownEntryPolicy,
                    this.discoverUnknownEntryPolicy,
                    this.unknownEntryPolicyConfigured);
        }

        /**
         * Asks the loader to discover its {@link ChunkVersionPolicy} from the classpath instead of
         * using an explicit instance.
         * <p>
         * This is what {@link #builder()} already defaults to, so calling it only matters after an
         * earlier {@link #versionPolicy(ChunkVersionPolicy)} call on the same chain, to undo it. The
         * shipped {@link DefaultChunkVersionPolicy} steps aside for a single registered foreign
         * provider; a classpath that registers more than one <em>foreign</em> provider makes
         * {@link Builder#build(Path, Key)} throw {@link IllegalStateException} rather than guess
         * between them.
         * </p>
         *
         * @return a new builder with this value
         * @since 2.1.0
         */
        @Contract(value = "-> new", pure = true)
        public Builder discoverVersionPolicy() {
            return new Builder(this.openRegionLimit,
                    this.compressionLevel,
                    this.saveParallelism,
                    this.dataVersion,
                    this.minimumDataVersion,
                    this.diagnostics,
                    this.blockResolver,
                    this.biomeResolver,
                    this.exceptionHandler,
                    null,
                    true,
                    this.unknownEntryPolicy,
                    this.discoverUnknownEntryPolicy,
                    this.unknownEntryPolicyConfigured);
        }

        /**
         * Sets the policy consulted for a palette entry the running server does not know, or clears
         * it so the classpath default is used instead.
         * <p>
         * Calling this slot always turns discovery off, whatever value is passed, for the same
         * reason {@link #versionPolicy(ChunkVersionPolicy)} does: an explicit decision has to win
         * over the classpath without {@link ServiceResolution#choose(Class, Object, boolean, Class)}
         * seeing both set at once and refusing to guess between them. A builder that never calls
         * this slot keeps discovering the default instead, which is what {@link #builder()} starts
         * with.
         * </p>
         * <p>
         * Unlike {@link #versionPolicy(ChunkVersionPolicy)}, passing {@code null} here is not "check
         * nothing": {@link #build(Path, Key)} always resolves a usable policy, falling back to
         * {@link DefaultUnknownEntryPolicy} when neither an explicit instance nor a foreign
         * classpath provider is found, because a resolver always needs an id for the entry it could
         * not otherwise decode.
         * </p>
         *
         * <p>
         * <b>Do not combine this with {@link #blockResolver(PaletteEntryResolver)} or
         * {@link #biomeResolver(PaletteEntryResolver)}.</b> A resolver supplied through either of
         * those slots is used exactly as given and is never rebuilt around this policy, so
         * {@link #build(Path, Key)} refuses the combination instead of building a loader whose
         * configured policy would never actually run.
         * </p>
         * <p>
         * An explicit instance passed here is shared by every loader this builder builds afterward,
         * the same way an explicit {@link #diagnostics(AnvilDiagnostics)} instance is — and
         * {@link UnknownEntryPolicy} is called from every one of those loaders' parallel loads at
         * once, so it has to tolerate that sharing the way {@link AnvilDiagnostics} already does.
         * </p>
         *
         * @param unknownEntryPolicy the policy to consult for an unknown palette entry, or null to
         *                           fall back to the classpath default
         * @return a new builder with this value
         * @since 2.1.0
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder unknownEntryPolicy(@Nullable UnknownEntryPolicy unknownEntryPolicy) {
            return new Builder(this.openRegionLimit,
                    this.compressionLevel,
                    this.saveParallelism,
                    this.dataVersion,
                    this.minimumDataVersion,
                    this.diagnostics,
                    this.blockResolver,
                    this.biomeResolver,
                    this.exceptionHandler,
                    this.versionPolicy,
                    this.discoverVersionPolicy,
                    unknownEntryPolicy,
                    false,
                    true);
        }

        /**
         * Asks the loader to discover its {@link UnknownEntryPolicy} from the classpath instead of
         * using an explicit instance.
         * <p>
         * This is what {@link #builder()} already defaults to, so calling it only matters after an
         * earlier {@link #unknownEntryPolicy(UnknownEntryPolicy)} call on the same chain, to undo it.
         * The shipped {@link DefaultUnknownEntryPolicy} steps aside for a single registered foreign
         * provider; a classpath that registers more than one <em>foreign</em> provider makes
         * {@link Builder#build(Path, Key)} throw {@link IllegalStateException} rather than guess
         * between them.
         * </p>
         * <p>
         * Calling this together with {@link #blockResolver(PaletteEntryResolver)} or
         * {@link #biomeResolver(PaletteEntryResolver)} is refused by {@link #build(Path, Key)} for
         * the same reason {@link #unknownEntryPolicy(UnknownEntryPolicy)} is: a resolver supplied
         * through either of those slots never sees whatever this discovers.
         * </p>
         *
         * @return a new builder with this value
         * @since 2.1.0
         */
        @Contract(value = "-> new", pure = true)
        public Builder discoverUnknownEntryPolicy() {
            return new Builder(this.openRegionLimit,
                    this.compressionLevel,
                    this.saveParallelism,
                    this.dataVersion,
                    this.minimumDataVersion,
                    this.diagnostics,
                    this.blockResolver,
                    this.biomeResolver,
                    this.exceptionHandler,
                    this.versionPolicy,
                    this.discoverVersionPolicy,
                    null,
                    true,
                    true);
        }

        /**
         * Builds a loader for the given world and dimension.
         * <p>
         * Both values are required, which is why they are parameters here and not slots. They are
         * resolved to a region directory immediately and are not kept, so this is also the reason
         * the builder offers no {@code copy}: a loader cannot say which world it came from.
         * </p>
         *
         * @param worldRoot the root directory of the world
         * @param dimension the key of the dimension the loader reads and writes
         * @return a new loader, independent of every other loader from this builder
         * @throws IllegalStateException if this builder was left on classpath discovery and the
         *                                classpath registers more than one foreign
         *                                {@link ChunkVersionPolicy} or more than one foreign
         *                                {@link UnknownEntryPolicy}, or if
         *                                {@link #unknownEntryPolicy(UnknownEntryPolicy)} or
         *                                {@link #discoverUnknownEntryPolicy()} was called on this
         *                                builder together with {@link #blockResolver(PaletteEntryResolver)}
         *                                or {@link #biomeResolver(PaletteEntryResolver)} — a custom
         *                                resolver never sees the configured policy, so the combination
         *                                would silently drop it instead of applying it
         */
        @Contract(value = "_, _ -> new", pure = true)
        public FalcoAnvilLoader build(Path worldRoot, Key dimension) {
            return new FalcoAnvilLoader(worldRoot, dimension, this);
        }
    }

    /**
     * Counts the region files of a directory for the opening log line.
     *
     * @param directory the directory which holds the region files
     * @return the amount of region files, or a word for a directory which cannot be listed
     */
    private static String describeRegionFileCount(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return Long.toString(entries.filter(entry -> entry.getFileName().toString().endsWith(".mca")).count());
        } catch (IOException exception) {
            // A directory which is absent is the interesting case here and is already reported by
            // the exists flag of the same line, so the failure itself needs no stack trace.
            return "unreadable";
        }
    }

    /**
     * Resolves the directory which holds the region files of a dimension.
     * A world which still uses the layout without a dimension directory keeps working because the
     * legacy directory is used when it exists and the current one does not.
     *
     * @param worldRoot the root directory of the world
     * @param dimension the key of the dimension
     * @return the directory which holds the region files and the layout it came from
     */
    @Contract(pure = true)
    private static ResolvedRegionDirectory resolveRegionDirectory(Path worldRoot, Key dimension) {
        Path current = worldRoot.resolve("dimensions").resolve(dimension.namespace()).resolve(dimension.value()).resolve("region");
        Path legacy = worldRoot.resolve("region");

        if (!Files.isDirectory(current) && Files.isDirectory(legacy)) {
            return new ResolvedRegionDirectory(legacy, true);
        }
        return new ResolvedRegionDirectory(current, false);
    }

    /**
     * The {@link ResolvedRegionDirectory} record names the directory a loader reads from together
     * with the layout which produced it.
     * <p>
     * The layout travels with the path because the two are only equivalent when the world is
     * healthy. A directory which came out of the dimension layout and holds nothing is a different
     * situation from a world which has no region files at all, and the difference is invisible in
     * the path alone.
     * </p>
     *
     * @param directory    the directory which holds the region files
     * @param legacyLayout whether the directory came from the layout without a dimension directory
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.1.0
     */
    private record ResolvedRegionDirectory(Path directory, boolean legacyLayout) {
    }

    /**
     * {@inheritDoc}
     * <p>
     * The region file is registered as in use for the duration of the read, so an unload or an
     * eviction which happens in parallel cannot close the file this call is reading from.
     * </p>
     *
     * @throws IllegalStateException if the loader was already closed or is closed while the call
     *                               is looking for the region file
     */
    @Override
    public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
        ensureOpen();
        RegionHandle handle;

        // The acquisition stays outside of the block which reports a failed chunk. It refuses its
        // work once the loader is closed, and a shutdown which arrives during a load is a lifecycle
        // event of the caller rather than a chunk which could not be read.
        try {
            handle = acquireRegion(chunkX, chunkZ, false);
        } catch (IOException | AnvilFormatException exception) {
            throw failedLoad(chunkX, chunkZ, exception);
        }

        if (handle == null) {
            if (this.diagnostics.reportMissingRegionFile()) {
                LOGGER.debug(
                        "Skipping a chunk whose region file does not exist chunk=[{},{}] region={} dim={}",
                        chunkX, chunkZ, this.regionDirectory, this.dimensionLabel
                );
            }
            return null;
        }

        try {
            RegionFile.RawChunk raw;

            try {
                raw = handle.file().readRaw(chunkX, chunkZ);
            } finally {
                releaseRegion(handle);
            }

            if (raw == null) {
                if (this.diagnostics.reportMissingChunkEntry()) {
                    LOGGER.debug(
                            "Skipping a chunk which its region file holds no entry for chunk=[{},{}] region={} dim={}",
                            chunkX, chunkZ, this.regionDirectory, this.dimensionLabel
                    );
                }
                return null;
            }

            CompoundBinaryTag data = TAG_READER.read(new ByteArrayInputStream(raw.decompress()), BinaryTagIO.Compression.NONE);
            if (this.versionPolicy != null) {
                checkVersion(data);
            }
            String status = chunkStatus(data);

            if (!isFullyGenerated(status)) {
                // The status is the whole content of this report. Without it the line says that
                // something is wrong with the world without saying what, which is what sent the
                // reader of a run that returned nothing looking through a debugger.
                if (this.diagnostics.reportPartialChunk(status == null ? AnvilDiagnostics.UNKNOWN_STATUS : status)) {
                    LOGGER.warn(
                            "Skipping a chunk which is not fully generated chunk=[{},{}] status={} region={} dim={}",
                            chunkX, chunkZ, status, this.regionDirectory, this.dimensionLabel
                    );
                }
                return null;
            }

            Chunk chunk = instance.getChunkSupplier().createChunk(instance, chunkX, chunkZ);
            // The conversion runs before the lock is taken so only the transfer into the chunk is
            // guarded. That is what keeps parallel loading worthwhile.
            List<DecodedSection> sections = decodeSections(chunk, data);

            chunk.lockWriteLock();
            try {
                for (DecodedSection section : sections) {
                    section.applyTo(chunk);
                }
                applyBlockEntities(chunk, data);
            } finally {
                chunk.unlockWriteLock();
            }
            trackChunk(chunkX, chunkZ);
            this.diagnostics.countChunkLoaded();
            return chunk;
        } catch (IOException | RuntimeException | AnvilFormatException exception) {
            throw failedLoad(chunkX, chunkZ, exception);
        }
    }

    /**
     * Reports a chunk which could not be read and builds the exception which carries that failure
     * to the caller.
     * <p>
     * Reporting the chunk as absent would make the server generate a replacement which overwrites
     * the real data on the next save, so the failure has to propagate.
     * </p>
     *
     * @param chunkX    the absolute chunk x coordinate
     * @param chunkZ    the absolute chunk z coordinate
     * @param exception the failure which stopped the load
     * @return the exception the caller has to throw
     */
    private AnvilChunkException failedLoad(int chunkX, int chunkZ, Throwable exception) {
        ChunkLocation location = locationOf(chunkX, chunkZ);
        // A format fault is told where it happened before it is reported. The classes that detect
        // one read bytes and have never been told which world those bytes belong to, so this is the
        // first point at which the context exists at all.
        Throwable located = exception instanceof AnvilFormatException fault ? fault.at(location) : exception;

        this.diagnostics.countError();
        LOGGER.error(
                "Failed to load the chunk chunk=[{},{}] region={} dim={}",
                chunkX, chunkZ, this.regionDirectory, this.dimensionLabel, located
        );
        reportException(located);
        return new AnvilChunkException("The chunk " + chunkX + "/" + chunkZ + " could not be loaded", location, located);
    }

    /**
     * Describes where a failure of the given chunk happened.
     *
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     * @return the location the loader attaches to a fault of that chunk
     */
    private ChunkLocation locationOf(int chunkX, int chunkZ) {
        return new ChunkLocation(chunkX, chunkZ, this.regionDirectory.toString(), this.dimensionLabel);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The call blocks, and on one path it blocks for a length worth knowing before it is placed in
     * a tick task. A chunk whose payload no longer fits into the region file is moved into a file
     * of its own, and a file system which denies a rename while another handle still holds the name
     * — Windows does, POSIX does not — makes that move repeat. The repetition is bounded by
     * {@code EXTERNAL_ATTEMPTS * EXTERNAL_RETRY_DELAY} in the region file, which is 100 attempts one
     * millisecond apart and therefore <strong>100 ms</strong> of waiting per rename, on top of the
     * write itself. That is longer than a tick. A caller which cannot afford it has to move this
     * call off the tick thread; {@link #saveChunks(Collection)} does not help, because it waits for
     * its tasks.
     * </p>
     *
     * @throws IllegalStateException if the loader was already closed or is closed while the call
     *                               is looking for the region file
     */
    @Override
    public void saveChunk(Chunk chunk) {
        ensureOpen();
        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();

        try {
            CompoundBinaryTag data = snapshot(chunk);
            ByteArrayOutputStream target = new ByteArrayOutputStream(64 * 1024);
            TAG_WRITER.writeNamed(Map.entry("", data), target, BinaryTagIO.Compression.NONE);

            writeToRegion(chunkX, chunkZ, ChunkCompression.ZLIB.compress(target.toByteArray(), this.compressionLevel));
            this.diagnostics.countChunkSaved();
        } catch (IllegalStateException exception) {
            // The loader was closed while this save was running. Counting that as a failed chunk
            // would hide the reason behind a data error, so the refusal reaches the caller as it is.
            throw exception;
        } catch (IOException | RuntimeException | AnvilFormatException exception) {
            this.diagnostics.countError();
            LOGGER.error(
                    "Failed to save the chunk chunk=[{},{}] region={} dim={}",
                    chunkX, chunkZ, this.regionDirectory, this.dimensionLabel, exception
            );
            reportException(exception);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * The chunks are grouped by their region file and every group is handled by a single task. The
     * default implementation starts one thread per chunk which lets thousands of them compete for
     * the same region locks while every chunk snapshot is held in memory at the same time.
     * </p>
     *
     * @throws IllegalStateException if the loader was already closed
     */
    @Override
    public void saveChunks(Collection<Chunk> chunks) {
        ensureOpen();
        Map<Long, List<Chunk>> grouped = new HashMap<>();

        for (Chunk chunk : chunks) {
            long region = CoordConversion.regionIndex(
                    RegionConstants.chunkToRegion(chunk.getChunkX()),
                    RegionConstants.chunkToRegion(chunk.getChunkZ())
            );
            grouped.computeIfAbsent(region, _ -> new ArrayList<>()).add(chunk);
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(grouped.size());

            for (List<Chunk> group : grouped.values()) {
                futures.add(executor.submit(() -> {
                    this.saveLimit.acquire();
                    try {
                        for (Chunk chunk : group) {
                            saveChunk(chunk);
                        }
                    } finally {
                        this.saveLimit.release();
                    }
                    return null;
                }));
            }
            awaitAll(futures);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportsParallelLoading() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportsParallelSaving() {
        return true;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The region file of the chunk is closed once every chunk this loader took from it has been
     * unloaded. Only chunks this loader handled itself are tracked, because a loader also receives
     * unload calls for chunks it never loaded. An unload call for such a chunk is ignored instead
     * of closing a file which is still in use.
     * </p>
     */
    @Override
    public void unloadChunk(Chunk chunk) {
        int chunkX = chunk.getChunkX();
        int chunkZ = chunk.getChunkZ();
        long index = regionIndex(chunkX, chunkZ);
        Set<Long> chunks = this.trackedChunks.get(index);

        if (chunks == null || !chunks.remove(CoordConversion.chunkIndex(chunkX, chunkZ))) {
            return;
        }

        LOGGER.trace("Unloading the chunk chunk=[{},{}] dim={}", chunkX, chunkZ, this.dimensionLabel);

        if (!chunks.isEmpty()) {
            return;
        }
        // The removal has to be conditional, another thread may have registered a chunk since the
        // emptiness check above.
        if (this.trackedChunks.remove(index, chunks)) {
            closeRegion(index);
        }
    }

    /**
     * Drops the region file with the given index from the cache and closes it.
     * <p>
     * A file which is still being read from or written to is only dropped here. The thread which
     * leaves it last performs the actual close, so an unload cannot break a load which is already
     * running.
     * </p>
     *
     * @param index the index of the region file
     */
    private void closeRegion(long index) {
        RegionHandle handle = this.regions.remove(index);

        if (handle != null) {
            retire(handle, "after its last chunk was unloaded");
        }
    }

    /**
     * Closes the given handle unless a thread is still using it.
     * The handle has to be removed from the cache before this method is called, otherwise a thread
     * could register itself on a file which is about to be closed.
     *
     * @param handle the handle which was dropped from the cache
     * @param reason the reason which is written into the log line of a successful close
     */
    private void retire(RegionHandle handle, String reason) {
        if (handle.retire()) {
            closeQuietly(handle, reason);
        }
    }

    /**
     * Closes the file of the given handle and reports a failure instead of propagating it.
     *
     * @param handle the handle whose file is closed
     * @param reason the reason which is written into the log line of a successful close
     */
    private void closeQuietly(RegionHandle handle, String reason) {
        try {
            handle.file().flush();
            handle.file().close();
            LOGGER.debug("Closed the region file region={} dim={} {}", handle.file().path(), this.dimensionLabel, reason);
        } catch (IOException exception) {
            this.diagnostics.countError();
            LOGGER.error("Failed to close the region file region={} dim={}", handle.file().path(), this.dimensionLabel, exception);
        }
    }

    /**
     * Releases a handle which was obtained by {@link #acquireRegion(int, int, boolean)}.
     * The file is closed here when this thread was the last user of a handle which had already been
     * dropped from the cache.
     *
     * @param handle the handle to release
     */
    private void releaseRegion(RegionHandle handle) {
        if (handle.release()) {
            closeQuietly(handle, "after its last user finished");
        }
    }

    /**
     * Verifies that the loader is still usable.
     *
     * @throws IllegalStateException if the loader was already closed
     */
    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException(
                    "The anvil loader for region=" + this.regionDirectory + " dim=" + this.dimensionLabel + " is closed"
            );
        }
    }

    /**
     * Records that this loader handled the given chunk so its region file can be released once
     * every chunk of that file has been unloaded again.
     *
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     */
    private void trackChunk(int chunkX, int chunkZ) {
        this.trackedChunks
                .computeIfAbsent(regionIndex(chunkX, chunkZ), _ -> ConcurrentHashMap.newKeySet())
                .add(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    /**
     * Calculates the index of the region file which holds the given chunk.
     *
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     * @return the index of the region file
     */
    @Contract(pure = true)
    private static long regionIndex(int chunkX, int chunkZ) {
        return CoordConversion.regionIndex(RegionConstants.chunkToRegion(chunkX), RegionConstants.chunkToRegion(chunkZ));
    }

    /**
     * Returns the diagnostics which collect the counters of this loader.
     *
     * @return the diagnostics of the loader
     */
    @Contract(pure = true)
    public AnvilDiagnostics diagnostics() {
        return this.diagnostics;
    }

    /**
     * Returns the directory this loader reads its region files from.
     * <p>
     * Exposed because the directory is resolved from the world root and the dimension rather than
     * given, so a caller which returned no chunks cannot otherwise tell whether the loader was
     * looking where the caller expected it to.
     * </p>
     *
     * @return the resolved region directory
     */
    @Contract(pure = true)
    public Path regionDirectory() {
        return this.regionDirectory;
    }

    /**
     * Returns whether the region directory came from the layout without a dimension directory.
     *
     * @return true if the region files sit directly under the world root, otherwise false
     */
    @Contract(pure = true)
    public boolean legacyLayout() {
        return this.legacyLayout;
    }

    /**
     * Returns the lowest data version this loader accepts when reading a chunk.
     * <p>
     * Package-private on purpose: this reader exists for the Gegenprobe of the builder slot and for
     * the guard a later change adds to the load path, not for a caller outside this package.
     * </p>
     *
     * @return the lowest data version the loader accepts
     */
    @Contract(pure = true)
    int minimumDataVersion() {
        return this.minimumDataVersion;
    }

    /**
     * Returns the policy this loader resolved, or null if it checks nothing.
     * <p>
     * Package-private for the same reason as {@link #minimumDataVersion()}: this exists for the
     * builder's own pass-through tests, not for a caller outside this package.
     * </p>
     *
     * @return the resolved policy, or null if the loader checks no chunk version at all
     * @since 2.1.0
     */
    @Contract(pure = true)
    @Nullable ChunkVersionPolicy versionPolicy() {
        return this.versionPolicy;
    }

    /**
     * Returns the policy this loader resolved for an unknown palette entry.
     * <p>
     * Package-private for the same reason as {@link #versionPolicy()}: this exists for the
     * builder's own pass-through tests, not for a caller outside this package.
     * </p>
     *
     * @return the resolved policy, never null
     * @since 2.1.0
     */
    @Contract(pure = true)
    UnknownEntryPolicy unknownEntryPolicy() {
        return this.unknownEntryPolicy;
    }

    /**
     * Closes every region file the loader opened and reports a summary of its work.
     * <p>
     * A loader is closed while the tasks of the server are still running, because the loader reports
     * parallel work as supported and therefore receives one task per chunk. A file which such a task
     * is still using is dropped from the cache here and closed by that task when it finishes, so no
     * handle survives the shutdown. Every later call is rejected, which is what stops a task from
     * opening a file that nobody would close again.
     * </p>
     * <p>
     * Two threads calling this at the same time are serialised by a lock the loader keeps to
     * itself, not by the monitor of the loader. The distinction matters because the loader is
     * handed to the server and therefore to arbitrary code: a caller holding
     * {@code synchronized (loader)} must not be able to stop a shutdown.
     * </p>
     *
     * @throws IOException if a region file cannot be closed
     */
    @Override
    public void close() throws IOException {
        this.closeLock.lock();

        try {
            if (this.closed) {
                return;
            }
            // The flag is raised before the cache is emptied. A thread which publishes a handle reads
            // the flag after publishing it, so either that thread sees the flag or the loop below sees
            // the handle, and the file is closed in both cases.
            this.closed = true;
            IOException failure = null;

            for (Long index : List.copyOf(this.regions.keySet())) {
                RegionHandle handle = this.regions.remove(index);

                if (handle == null) {
                    continue;
                }
                if (!handle.retire()) {
                    LOGGER.debug("Leaving the region file region={} dim={} to the task which is still using it",
                            handle.file().path(), this.dimensionLabel);
                    continue;
                }

                try {
                    handle.file().flush();
                    handle.file().close();
                } catch (IOException exception) {
                    failure = exception;
                    LOGGER.error("Failed to close the region file region={} dim={}", handle.file().path(), this.dimensionLabel, exception);
                }
            }
            this.regions.clear();
            this.trackedChunks.clear();
            logSummary();

            if (failure != null) {
                throw failure;
            }
        } finally {
            this.closeLock.unlock();
        }
    }

    /**
     * Writes the summary of the loader. The line reports on the error level when at least one
     * chunk failed so a shutdown which lost data does not look like a clean one.
     */
    private void logSummary() {
        long errors = this.diagnostics.errors();
        String message = "Closing the anvil loader after {} loaded, {} skipped and {} saved chunks with {} errors,"
                + " {} unknown blocks and {} unknown biomes region={} dim={}";

        if (errors > 0) {
            LOGGER.warn(
                    message, this.diagnostics.chunksLoaded(), this.diagnostics.chunksSkipped(),
                    this.diagnostics.chunksSaved(), errors,
                    this.diagnostics.unknownBlockCount(), this.diagnostics.unknownBiomeCount(),
                    this.regionDirectory, this.dimensionLabel
            );
        } else {
            LOGGER.info(
                    message, this.diagnostics.chunksLoaded(), this.diagnostics.chunksSkipped(),
                    this.diagnostics.chunksSaved(), errors,
                    this.diagnostics.unknownBlockCount(), this.diagnostics.unknownBiomeCount(),
                    this.regionDirectory, this.dimensionLabel
            );
        }
        logSkipSummary();
    }

    /**
     * Writes the breakdown of the skipped chunks, but only for a run which skipped anything.
     * <p>
     * A separate line rather than more fields on the one above, because the three reasons only
     * matter when at least one of them fired and a normal shutdown should not have to be read
     * around them. On the run this exists for they are the whole message: a loader which returned
     * nothing has to say which of the three reasons it returned nothing for.
     * </p>
     */
    private void logSkipSummary() {
        long skipped = this.diagnostics.chunksSkipped();

        if (skipped == 0) {
            return;
        }
        LOGGER.warn(
                "The anvil loader skipped {} chunks: {} had no region file, {} had no entry in their region file"
                        + " and {} are not fully generated {} region={} dim={}",
                skipped,
                this.diagnostics.chunksSkippedWithoutRegionFile(),
                this.diagnostics.chunksSkippedWithoutEntry(),
                this.diagnostics.chunksSkippedAsPartial(),
                describeStatuses(this.diagnostics.partialChunkStatuses()),
                this.regionDirectory,
                this.dimensionLabel
        );
    }

    /**
     * Renders the status values of the partially generated chunks with their counts.
     *
     * @param statuses the amount of chunks per status value
     * @return the rendered status values, or a word for a run which saw none
     */
    @Contract(pure = true)
    private static String describeStatuses(Map<String, Long> statuses) {
        if (statuses.isEmpty()) {
            return "(no status seen)";
        }
        return statuses.entrySet().stream()
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.joining(", ", "(", ")"));
    }

    /**
     * Writes the given payload into the region file of the chunk.
     * <p>
     * The handle is registered as in use for the duration of the write, so an eviction which
     * happens in parallel drops the file from the cache without closing it under this thread. The
     * write therefore needs no retry.
     * </p>
     *
     * @param chunkX  the absolute chunk x coordinate
     * @param chunkZ  the absolute chunk z coordinate
     * @param payload the compressed payload of the chunk
     * @throws IOException           if the chunk cannot be written
     * @throws RegionFormatException if the region file it belongs to is malformed
     */
    private void writeToRegion(int chunkX, int chunkZ, byte[] payload) throws IOException, RegionFormatException {
        RegionHandle handle = acquireRegion(chunkX, chunkZ, true);

        if (handle == null) {
            throw new IOException("The region file for the chunk " + chunkX + "/" + chunkZ + " could not be created");
        }

        try {
            handle.file().writeRaw(chunkX, chunkZ, ChunkCompression.ZLIB, payload);
        } finally {
            releaseRegion(handle);
        }
    }

    /**
     * Returns a registered handle for the region file which holds the given chunk.
     * <p>
     * The registration is what keeps the file open for the caller. A handle which was dropped from
     * the cache in the meantime cannot be registered any more, and the loop then opens the file
     * again instead of handing back a file which is about to be closed. Every returned handle has
     * to be released through {@link #releaseRegion(RegionHandle)}.
     * </p>
     * <p>
     * The file is opened outside of the mapping function of the map because opening it performs
     * blocking work. Doing that inside the mapping function blocks a bin of the map for the whole
     * duration and has already caused a deadlock in the loader of Minestom.
     * </p>
     *
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     * @param create whether the file should be created when it does not exist yet
     * @return the registered handle or null if the file does not exist and should not be created
     * @throws IOException           if the file cannot be opened
     * @throws IllegalStateException if the loader was closed while the file was being opened
     */
    private @Nullable RegionHandle acquireRegion(int chunkX, int chunkZ, boolean create) throws IOException, RegionFormatException {
        int regionX = RegionConstants.chunkToRegion(chunkX);
        int regionZ = RegionConstants.chunkToRegion(chunkZ);
        long index = CoordConversion.regionIndex(regionX, regionZ);
        Path path = this.regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca");

        while (true) {
            RegionHandle cached = this.regions.get(index);

            if (cached != null) {
                if (cached.acquire()) {
                    return cached;
                }
                // The handle was dropped between the lookup and the registration, so it is on its
                // way out and a new one has to be opened.
                continue;
            }

            if (!create && !Files.exists(path)) {
                return null;
            }
            ensureOpen();

            RegionHandle opened = new RegionHandle(RegionFile.open(path));
            RegionHandle previous = this.regions.putIfAbsent(index, opened);

            if (previous != null) {
                opened.file().close();
                continue;
            }
            // The loader can be closed between the check above and this publication. The flag is
            // read after publishing, so whoever of the two threads loses the race still sees the
            // work of the other one and the file cannot survive as an unclosed handle.
            if (this.closed) {
                if (this.regions.remove(index, opened)) {
                    retire(opened, "because the loader was closed while it was being opened");
                }
                ensureOpen();
            }

            LOGGER.debug("Opened the region file region={} dim={}", path, this.dimensionLabel);

            if (!opened.acquire()) {
                continue;
            }
            evictRegions(index);
            return opened;
        }
    }

    /**
     * Drops region files from the cache until the configured limit is met again.
     * <p>
     * The file which was just opened is never dropped so the caller keeps a cached handle. A file
     * which another thread is still using is only dropped here; that thread closes it when it
     * finishes, which can keep the loader above its limit for the duration of a single access.
     * </p>
     *
     * @param keep the index of the region file which must stay cached
     */
    private void evictRegions(long keep) {
        for (Map.Entry<Long, RegionHandle> entry : this.regions.entrySet()) {
            if (this.regions.size() <= this.openRegionLimit) {
                return;
            }
            if (entry.getKey() == keep || !this.regions.remove(entry.getKey(), entry.getValue())) {
                continue;
            }
            retire(entry.getValue(), "to stay below the open file limit");
        }
    }

    /**
     * The {@link RegionHandle} class ties a region file to the amount of threads which are working
     * with it, which is what allows the file to be dropped from the cache at any moment without
     * closing it under a thread that is still reading or writing.
     * <p>
     * A handle is either usable, which means it can accept further users, or retired, which means it
     * was dropped from the cache and is closed as soon as its last user leaves. A retired handle
     * never becomes usable again, so a thread which finds one has to open the file anew.
     * </p>
     *
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.1.0
     */
    private static final class RegionHandle {

        private final RegionFile file;

        private int users;
        private boolean retired;

        /**
         * Creates a handle for the given region file.
         *
         * @param file the region file the handle guards
         */
        private RegionHandle(RegionFile file) {
            this.file = file;
        }

        /**
         * Returns the region file of this handle.
         *
         * @return the guarded region file
         */
        @Contract(pure = true)
        private RegionFile file() {
            return this.file;
        }

        /**
         * Registers the calling thread as a user of the region file.
         *
         * @return true if the file may be used, false if the handle is already retired
         */
        private synchronized boolean acquire() {
            if (this.retired) {
                return false;
            }
            this.users++;
            return true;
        }

        /**
         * Removes the calling thread from the users of the region file.
         *
         * @return true if the caller has to close the file because it was the last user of a
         * retired handle, otherwise false
         */
        private synchronized boolean release() {
            this.users--;
            return this.retired && this.users == 0;
        }

        /**
         * Marks the handle as dropped from the cache so it cannot accept further users.
         *
         * @return true if the caller has to close the file because no thread is using it, false if
         * the last user closes it instead
         */
        private synchronized boolean retire() {
            this.retired = true;
            return this.users == 0;
        }
    }

    /**
     * Returns the amount of region files the loader currently keeps open.
     *
     * @return the amount of open region files
     */
    @Contract(pure = true)
    public int openRegionCount() {
        return this.regions.size();
    }

    /**
     * Consults {@link #versionPolicy} about a chunk, and reports and counts a refusal before it
     * reaches the caller.
     * <p>
     * The policy only decides and throws; it does not know about {@link AnvilDiagnostics} or the
     * logger, on purpose — see {@link ChunkVersionPolicy}. Counting and logging the refusal is
     * therefore the loader's job, done here rather than duplicated at every call site, and done
     * only when {@link #versionPolicy} is not null: the caller at {@link #loadChunk(Instance, int,
     * int)} already guards the call for that reason.
     * </p>
     *
     * @param data the root compound of the chunk
     * @throws ChunkDataException if the policy refuses the chunk
     */
    private void checkVersion(CompoundBinaryTag data) throws ChunkDataException {
        try {
            this.versionPolicy.check(data, this.minimumDataVersion);
        } catch (ChunkDataException failure) {
            String reported = reportedDataVersion(data);

            if (this.diagnostics.reportUnsupportedChunkVersion(reported)) {
                LOGGER.warn(
                        "Refusing a chunk from data version {} in {}: {}",
                        reported, this.regionDirectory, failure.getMessage()
                );
            }
            throw failure;
        }
    }

    /**
     * Renders the stored {@code DataVersion} of a chunk the way the version breakdown of
     * {@link AnvilDiagnostics} groups it: by the number it holds, or by
     * {@link AnvilDiagnostics#UNKNOWN_DATA_VERSION} for a chunk which stores none.
     * <p>
     * This mirrors only the presentation the guard used before it became a policy, not its
     * decision: a key that is present but not a number renders as {@code "-1"} here exactly as it
     * always did, because {@link NbtReads#optionalInteger(CompoundBinaryTag, String, int)} falls
     * back to that default for a mistyped value the same way it does for a missing one.
     * </p>
     *
     * @param data the root compound of the chunk
     * @return the data version to report, or {@link AnvilDiagnostics#UNKNOWN_DATA_VERSION}
     */
    @Contract(pure = true)
    private static String reportedDataVersion(CompoundBinaryTag data) {
        return data.get(DATA_VERSION_KEY) == null
                ? AnvilDiagnostics.UNKNOWN_DATA_VERSION
                : Integer.toString(NbtReads.optionalInteger(data, DATA_VERSION_KEY, -1));
    }

    /**
     * Reads the generation status of the given chunk data.
     * The key is read in both spellings because Minestom writes it in lower case while the game
     * itself writes it capitalised.
     *
     * @param data the chunk data to read
     * @return the stored status, or null if the chunk carries none
     */
    @Contract(pure = true)
    private static @Nullable String chunkStatus(CompoundBinaryTag data) {
        String status = NbtReads.optionalString(data, STATUS_KEY);

        if (status == null) {
            status = NbtReads.optionalString(data, LEGACY_STATUS_KEY);
        }
        return status;
    }

    /**
     * Checks whether the given status describes a fully generated chunk.
     * A chunk without a status counts as generated, because a world written by a tool which does
     * not store one would otherwise be unreadable in its entirety.
     *
     * @param status the stored status, or null if the chunk carries none
     * @return true if the chunk is fully generated, otherwise false
     */
    @Contract(pure = true)
    private static boolean isFullyGenerated(@Nullable String status) {
        return status == null || FULL_STATUS.equals(status);
    }

    /**
     * Converts the sections of the given chunk data without touching the chunk.
     * The result is applied to the chunk afterwards while the write lock is held, which keeps the
     * expensive conversion out of the guarded section.
     *
     * @param chunk the chunk the sections belong to
     * @param data  the chunk data to read
     * @return the converted sections
     * @throws IOException if a section is malformed
     */
    private List<DecodedSection> decodeSections(Chunk chunk, CompoundBinaryTag data) throws ChunkDataException {
        ListBinaryTag sections = NbtReads.optionalList(data, SECTIONS_KEY, BinaryTagTypes.COMPOUND);
        List<DecodedSection> decoded = new ArrayList<>(sections.size());

        for (int index = 0; index < sections.size(); index++) {
            CompoundBinaryTag sectionData = sections.getCompound(index);
            int sectionY = NbtReads.integer(sectionData, "Y");

            if (sectionY < chunk.getMinSection() || sectionY >= chunk.getMaxSection()) {
                // The game stores one section below and one above the world for lighting purposes.
                LOGGER.trace("Skipping the section {} outside of the world chunk=[{},{}]", sectionY, chunk.getChunkX(), chunk.getChunkZ());
                continue;
            }

            CompoundBinaryTag blockStates = NbtReads.optionalCompound(sectionData, BLOCK_STATES_KEY);
            CompoundBinaryTag biomes = NbtReads.optionalCompound(sectionData, BIOMES_KEY);

            decoded.add(new DecodedSection(
                    sectionY,
                    blockStates == null ? null : SectionCodec.decode(blockStates, this.blockResolver, BLOCK_ENTRIES, Palette.BLOCK_PALETTE_MIN_BITS),
                    biomes == null ? null : SectionCodec.decodeBiomes(biomes, this.biomeResolver, BIOME_ENTRIES, Palette.BIOME_PALETTE_MIN_BITS),
                    lightArray(sectionData, "SkyLight"),
                    lightArray(sectionData, "BlockLight")
            ));
        }
        return decoded;
    }

    /**
     * Reads a light array of a section.
     *
     * @param sectionData the section data to read
     * @param key         the key of the light array
     * @return the light array or null if the section carries none
     */
    @Contract(pure = true)
    private static byte @Nullable [] lightArray(CompoundBinaryTag sectionData, String key) {
        if (sectionData.get(key) instanceof ByteArrayBinaryTag light && light.size() == 2048) {
            return light.value();
        }
        return null;
    }

    /**
     * The {@link DecodedSection} record holds the converted content of a single section until it is
     * transferred into a chunk under its write lock.
     *
     * @param sectionY   the vertical index of the section
     * @param blocks     the converted block palette or null if the section carries none
     * @param biomes     the converted biome palette or null if the section carries none
     * @param skyLight   the stored sky light or null if the section carries none
     * @param blockLight the stored block light or null if the section carries none
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.1.0
     */
    private record DecodedSection(
            int sectionY,
            @Nullable PaletteData blocks,
            @Nullable PaletteData biomes,
            byte @Nullable [] skyLight,
            byte @Nullable [] blockLight
    ) {

        /**
         * Transfers the content of this section into the given chunk.
         * The caller has to hold the write lock of the chunk.
         *
         * @param chunk the chunk which receives the content
         * @throws ChunkDataException if a palette holds an index outside of its palette
         */
        private void applyTo(Chunk chunk) throws ChunkDataException {
            Section section = chunk.getSection(this.sectionY);

            if (this.skyLight != null) {
                section.skyLight().set(this.skyLight);
            }
            if (this.blockLight != null) {
                section.blockLight().set(this.blockLight);
            }
            if (this.blocks != null) {
                apply(section.blockPalette(), this.blocks);
            }
            if (this.biomes != null) {
                apply(section.biomePalette(), this.biomes);
            }
        }
    }

    /**
     * Transfers the given palette representation into a palette of Minestom.
     *
     * @param palette the palette which receives the values
     * @param data    the palette representation to transfer
     * @throws IOException if the representation holds an index outside of its palette
     */
    private static void apply(Palette palette, PaletteData data) throws ChunkDataException {
        if (data.isSingleValue()) {
            palette.fill(data.singleValue());
            return;
        }
        long[] packed = data.packed();

        if (packed != null && data.bitsPerEntry() == BitPacker.bitsPerEntry(data.palette().length, palette.bitsPerEntry())) {
            palette.load(data.palette(), packed);
            return;
        }

        int[] values = data.unpack();
        palette.setAll((x, y, z) -> values[index(x, y, z, palette.dimension())]);
    }

    /**
     * Calculates the index of a coordinate inside a palette of the given dimension.
     *
     * @param x         the x coordinate inside the palette
     * @param y         the y coordinate inside the palette
     * @param z         the z coordinate inside the palette
     * @param dimension the edge length of the palette
     * @return the index of the coordinate
     */
    @Contract(pure = true)
    private static int index(int x, int y, int z, int dimension) {
        return (y * dimension + z) * dimension + x;
    }

    /**
     * Applies the stored block entities to the chunk.
     *
     * @param chunk the chunk which receives the block entities
     * @param data  the chunk data to read
     * @throws IOException if a block entity is malformed
     */
    private void applyBlockEntities(Chunk chunk, CompoundBinaryTag data) throws ChunkDataException {
        ListBinaryTag entities = NbtReads.optionalList(data, BLOCK_ENTITIES_KEY, BinaryTagTypes.COMPOUND);

        for (int index = 0; index < entities.size(); index++) {
            CompoundBinaryTag entity = entities.getCompound(index);
            // The stored position is a world coordinate and has to be mapped back into the chunk.
            int x = NbtReads.integer(entity, "x") & (Chunk.CHUNK_SIZE_X - 1);
            int y = NbtReads.integer(entity, "y");
            int z = NbtReads.integer(entity, "z") & (Chunk.CHUNK_SIZE_Z - 1);

            Block block = chunk.getBlock(x, y, z);
            CompoundBinaryTag.Builder tags = CompoundBinaryTag.builder();

            for (Map.Entry<String, ? extends BinaryTag> entry : entity) {
                String key = entry.getKey();

                if (!"x".equals(key) && !"y".equals(key) && !"z".equals(key) && !"id".equals(key) && !"keepPacked".equals(key)) {
                    tags.put(key, entry.getValue());
                }
            }

            // The id names the block handler. Without resolving it the handler of every block
            // entity would be lost even though it is written back on the next save.
            if (entity.get("id") instanceof StringBinaryTag id) {
                block = block.withHandler(MinecraftServer.getBlockManager().getHandlerOrDummy(id.value()));
            }

            CompoundBinaryTag nbt = tags.build();
            chunk.setBlock(x, y, z, nbt.size() == 0 ? block : block.withNbt(nbt));
        }
    }

    /**
     * Builds the chunk data of the given chunk.
     * The state is copied under the read lock and everything else happens without it so the chunk
     * stays usable while its data is converted.
     *
     * @param chunk the chunk to describe
     * @return the chunk data of the chunk
     * @throws IOException if the chunk data cannot be built
     */
    private CompoundBinaryTag snapshot(Chunk chunk) throws IOException {
        List<Section> copies;
        List<CompoundBinaryTag> blockEntities = new ArrayList<>();

        chunk.lockReadLock();
        try {
            List<Section> sections = chunk.getSections();
            copies = new ArrayList<>(sections.size());

            for (Section section : sections) {
                copies.add(section.clone());
            }
            collectBlockEntities(chunk, blockEntities);
        } finally {
            chunk.unlockReadLock();
        }

        ListBinaryTag.Builder<CompoundBinaryTag> sections = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);

        for (int index = 0; index < copies.size(); index++) {
            sections.add(encodeSection(copies.get(index), chunk.getMinSection() + index));
        }

        ListBinaryTag.Builder<CompoundBinaryTag> entities = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        blockEntities.forEach(entities::add);

        return CompoundBinaryTag.builder()
                .putInt("DataVersion", this.dataVersion)
                .putInt("xPos", chunk.getChunkX())
                .putInt("zPos", chunk.getChunkZ())
                .putInt("yPos", chunk.getMinSection())
                .putString(STATUS_KEY, FULL_STATUS)
                .putLong("LastUpdate", 0L)
                .put(SECTIONS_KEY, sections.build())
                .put(BLOCK_ENTITIES_KEY, entities.build())
                .build();
    }

    /**
     * Collects the block entities of the given chunk.
     * Every block which carries data or a handler becomes a block entity, including the blocks of a
     * section which holds a single value. The loader of Minestom misses those.
     *
     * @param chunk  the chunk to read
     * @param target the list which receives the block entities
     */
    private static void collectBlockEntities(Chunk chunk, List<CompoundBinaryTag> target) {
        int minY = chunk.getMinSection() * Chunk.CHUNK_SECTION_SIZE;
        int maxY = chunk.getMaxSection() * Chunk.CHUNK_SECTION_SIZE;

        for (int y = minY; y < maxY; y++) {
            for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
                for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                    Block block = chunk.getBlock(x, y, z, Block.Getter.Condition.CACHED);

                    if (block == null) {
                        continue;
                    }

                    CompoundBinaryTag nbt = block.nbt();
                    boolean hasHandler = block.handler() != null;

                    if (nbt == null && !hasHandler) {
                        continue;
                    }

                    CompoundBinaryTag.Builder entity = CompoundBinaryTag.builder();

                    if (nbt != null) {
                        for (Map.Entry<String, ? extends BinaryTag> entry : nbt) {
                            entity.put(entry.getKey(), entry.getValue());
                        }
                    }
                    if (hasHandler) {
                        entity.putString("id", block.handler().getKey().asString());
                    }
                    // The format stores the position in world coordinates, not in chunk local ones.
                    target.add(entity
                            .putInt("x", chunk.getChunkX() * Chunk.CHUNK_SIZE_X + x)
                            .putInt("y", y)
                            .putInt("z", chunk.getChunkZ() * Chunk.CHUNK_SIZE_Z + z)
                            .build());
                }
            }
        }
    }

    /**
     * Builds the data of a single section.
     *
     * @param section  the section to describe
     * @param sectionY the vertical index of the section
     * @return the data of the section
     */
    private CompoundBinaryTag encodeSection(Section section, int sectionY) {
        return CompoundBinaryTag.builder()
                .putByte("Y", (byte) sectionY)
                .put(BLOCK_STATES_KEY, SectionCodec.encode(read(section.blockPalette(), BLOCK_ENTRIES, Palette.BLOCK_PALETTE_MIN_BITS), this.blockResolver))
                .put(BIOMES_KEY, SectionCodec.encodeBiomes(read(section.biomePalette(), BIOME_ENTRIES, Palette.BIOME_PALETTE_MIN_BITS), this.biomeResolver))
                .putByteArray("SkyLight", section.skyLight().array())
                .putByteArray("BlockLight", section.blockLight().array())
                .build();
    }

    /**
     * Reads the values of a palette of Minestom into the representation of the codec.
     *
     * @param palette         the palette to read
     * @param entryCount      the amount of entries the palette holds
     * @param minBitsPerEntry the smallest amount of bits the palette type allows
     * @return the palette representation of the palette
     */
    private static PaletteData read(Palette palette, int entryCount, int minBitsPerEntry) {
        int[] values = new int[entryCount];
        int dimension = palette.dimension();
        palette.getAll((x, y, z, value) -> values[index(x, y, z, dimension)] = value);
        return PaletteData.encode(values, minBitsPerEntry);
    }

    /**
     * Waits for every given task and reports the failures of them.
     *
     * @param futures the tasks to wait for
     */
    private void awaitAll(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException exception) {
                this.diagnostics.countError();
                LOGGER.error("Failed to save a group of chunks region={} dim={}", this.regionDirectory, this.dimensionLabel, exception.getCause());
                reportException(exception.getCause());
            }
        }
    }
}
