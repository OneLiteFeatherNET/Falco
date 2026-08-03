package net.onelitefeather.falco.demo;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.block.Block;
import net.minestom.server.timer.TaskSchedule;
import net.minestom.server.world.DimensionType;
import net.onelitefeather.falco.anvil.AnvilDiagnostics;
import net.onelitefeather.falco.anvil.FalcoAnvilLoader;
import net.onelitefeather.falco.instance.ChunkLifecycleEvent;
import net.onelitefeather.falco.instance.ChunkLifecycleListener;
import net.onelitefeather.falco.instance.FalcoChunk;
import net.onelitefeather.falco.instance.FalcoInstance;
import net.onelitefeather.falco.instance.FalcoSharedInstance;
import net.onelitefeather.falco.light.ChunkLightListener;
import net.onelitefeather.falco.light.ChunkLightScheduler;
import net.onelitefeather.falco.light.ChunkLightService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Every code block the documentation shows, compiled: the quick start and the feature overview of
 * the readme, and the wiki page <i>Instances and chunks</i>.
 *
 * <p>This class is never run and asserts nothing. Its whole value is failing to compile — a snippet
 * somebody copies is the one piece of documentation that can be wrong in a way no reader forgives,
 * and a page cannot be kept honest by rereading it. Writing the first version already caught one:
 * the light engine block said {@code new ChunkLightScheduler(instance)}, and that constructor takes
 * a {@code ChunkLightService}.</p>
 *
 * <p>When a snippet in either document changes, change it here too. When this stops compiling, the
 * document is wrong rather than this class.</p>
 */
final class DocumentationSnippets {

    private static final Logger log = LoggerFactory.getLogger(DocumentationSnippets.class);

    private DocumentationSnippets() {
    }

    // ---- readme, quick start ------------------------------------------------------------------

    /**
     * Step 4 — the two operations without a listener around them.
     *
     * @param instance the instance to read from
     * @param lighting the light service
     * @return the block light level the snippet prints
     */
    static int quickStartCheckWithoutClient(FalcoInstance instance, ChunkLightService lighting) {
        Chunk chunk = instance.loadChunk(0, 0).join();
        lighting.calculate(chunk);

        return lighting.blockLightAt(chunk, 8, 40, 8);
    }

    /**
     * Step 5 — all three modules together.
     *
     * @return the instance the snippet builds
     */
    static FalcoInstance quickStartAllThreeModules() {
        FalcoAnvilLoader loader = new FalcoAnvilLoader(Path.of("worlds", "lobby"), DimensionType.OVERWORLD.key());

        ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService());

        return FalcoInstance.builder(DimensionType.OVERWORLD)
                .chunkLoader(loader)
                .chunkSupplier(scheduler.supplier())
                .autoChunkLoad(true)
                .ownsLoader(true)
                .saveOnShutdown(true)
                .registerAndShutdownWith(MinecraftServer.getInstanceManager(),
                        MinecraftServer.getSchedulerManager());
    }

    /**
     * Shared worlds — a view that does not write its settings into the container.
     *
     * @return the view the snippet builds
     */
    static FalcoSharedInstance quickStartSharedWorld() {
        InstanceManager manager = MinecraftServer.getInstanceManager();
        InstanceContainer world = manager.createInstanceContainer();
        world.setChunkSupplier(FalcoChunk::new);

        FalcoSharedInstance view = new FalcoSharedInstance(UUID.randomUUID(), world);
        manager.registerSharedInstance(view);
        return view;
    }

    // ---- readme, everything the three modules offer --------------------------------------------

    /**
     * falco-anvil — the builder, for when the two-argument constructor does not fit.
     *
     * @return the loader the snippet builds
     */
    static FalcoAnvilLoader overviewAnvilBuilder() {
        return FalcoAnvilLoader.builder()
                .openRegionLimit(64)
                .compressionLevel(2)
                .saveParallelism(4)
                .dataVersion(4189)
                .diagnostics(new AnvilDiagnostics())
                .exceptionHandler(throwable -> log.warn("chunk load failed", throwable))
                .build(Path.of("worlds", "lobby"), DimensionType.OVERWORLD.key());
    }

    /**
     * falco-anvil — the counters that say what a world contained and this loader could not resolve.
     *
     * @param loader the loader to ask
     * @return what the counter answered
     */
    static boolean overviewDiagnostics(FalcoAnvilLoader loader) {
        AnvilDiagnostics diagnostics = loader.diagnostics();
        return diagnostics.reportUnknownBlock("mod:strange_block");
    }

    /**
     * falco-light — the three entry points, in order of how much they do.
     *
     * @param instance the instance the chunk belongs to
     * @param chunk    the chunk to light
     * @return the block light level the snippet reads back
     */
    static int overviewLightEntryPoints(Instance instance, Chunk chunk) {
        ChunkLightService lighting = new ChunkLightService();

        lighting.calculate(chunk);
        lighting.calculateSky(chunk);
        lighting.calculateWithNeighbours(instance, 0, 0);

        return lighting.blockLightAt(chunk, 8, 40, 8);
    }

    /**
     * falco-light — the scheduler builder and the knobs that matter under load.
     *
     * @param lighting the light service the scheduler drives
     * @return the scheduler the snippet builds
     */
    static ChunkLightScheduler overviewSchedulerBuilder(ChunkLightService lighting) {
        return ChunkLightScheduler.builder(lighting)
                .executor(ChunkLightScheduler.defaultExecutor())
                .maxAreaSize(4)
                .maxCachedChunks(256)
                .skyLight(ChunkLightScheduler.SkyLight.FROM_DIMENSION)
                .onFailure(throwable -> log.error("lighting failed", throwable))
                .build();
    }

    /**
     * falco-light — driving the scheduler from a plain container rather than a FalcoInstance.
     *
     * @param container the container to hang the listener on
     * @param scheduler the scheduler to report to
     */
    static void overviewSchedulerOnContainer(InstanceContainer container, ChunkLightScheduler scheduler) {
        container.setChunkSupplier((instance, x, z) -> {
            FalcoChunk chunk = new FalcoChunk(instance, x, z);
            chunk.addLifecycleListener(new ChunkLightListener(scheduler));
            return chunk;
        });
        MinecraftServer.getSchedulerManager()
                .buildTask(() -> scheduler.onTick(container, System.currentTimeMillis()))
                .repeat(TaskSchedule.tick(1))
                .schedule();
    }

    /**
     * falco-light — telling the scheduler what changed outside Falco.
     *
     * @param instance  the instance the chunk belongs to
     * @param scheduler the scheduler to tell
     */
    static void overviewMarkChanged(Instance instance, ChunkLightScheduler scheduler) {
        scheduler.markChanged(instance, 0, 0);
        scheduler.markChanged(instance, 0, 0, 8, 40, 8);
        scheduler.markDirty(instance, 0, 0);
    }

    /**
     * falco-instance — the four parts of an instance, and reading a chunk without materialising it.
     *
     * @param instance the instance to take apart
     * @param chunk    the chunk to read
     * @return how many sections actually exist
     */
    static int overviewInstanceParts(FalcoInstance instance, FalcoChunk chunk) {
        instance.registry();
        instance.lifecycle();
        instance.blockWriter();

        chunk.storage().views();
        chunk.storage().shared(0);
        return chunk.storage().materialisedSections();
    }

    /**
     * falco-instance — a lifecycle listener and a generator.
     *
     * @param instance the instance to configure
     */
    static void overviewListenerAndGenerator(FalcoInstance instance) {
        instance.lifecycle().addListener(new ChunkLifecycleListener() {
            @Override
            public void onLoad(ChunkLifecycleEvent event) {
                log.info("loaded {} {}", event.chunk().getChunkX(), event.chunk().getChunkZ());
            }
        });

        instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
    }

    // ---- wiki, Instances and chunks ------------------------------------------------------------

    /**
     * The shortest form the wiki page shows.
     *
     * @return the instance the snippet builds
     */
    static FalcoInstance wikiShortestForm() {
        return FalcoInstance.builder(DimensionType.OVERWORLD)
                .register(MinecraftServer.getInstanceManager());
    }

    /**
     * The chunk loader form, with the three flags that belong together.
     *
     * @return the instance the snippet builds
     */
    static FalcoInstance wikiWithChunkLoader() {
        FalcoAnvilLoader loader = new FalcoAnvilLoader(Path.of("worlds", "lobby"), DimensionType.OVERWORLD.key());

        return FalcoInstance.builder(DimensionType.OVERWORLD)
                .chunkLoader(loader)
                .autoChunkLoad(true)
                .ownsLoader(true)
                .saveOnShutdown(true)
                .registerAndShutdownWith(MinecraftServer.getInstanceManager(),
                        MinecraftServer.getSchedulerManager());
    }

    /**
     * The light engine combination.
     *
     * @return the instance the snippet builds
     */
    static FalcoInstance wikiWithLightEngine() {
        FalcoAnvilLoader loader = new FalcoAnvilLoader(Path.of("worlds", "lobby"), DimensionType.OVERWORLD.key());
        ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService());

        return FalcoInstance.builder(DimensionType.OVERWORLD)
                .chunkLoader(loader)
                .chunkSupplier(scheduler.supplier())
                .autoChunkLoad(true)
                .register(MinecraftServer.getInstanceManager());
    }

    /**
     * The two-consumer lifecycle route of the builder.
     *
     * @param manager the manager to register with
     * @return the instance the snippet builds
     */
    static FalcoInstance wikiLifecycleConsumers(InstanceManager manager) {
        return FalcoInstance.builder(DimensionType.OVERWORLD)
                .chunkLifecycle(
                        chunk -> report("loaded", chunk),
                        chunk -> report("unloaded", chunk))
                .register(manager);
    }

    /**
     * The full listener, with every method the interface offers.
     *
     * @param instance the instance to register on
     */
    static void wikiFullLifecycleListener(FalcoInstance instance) {
        instance.lifecycle().addListener(new ChunkLifecycleListener() {
            @Override
            public void onPublish(ChunkLifecycleEvent event) {
            }

            @Override
            public void onLoad(ChunkLifecycleEvent event) {
            }

            @Override
            public void onTick(ChunkLifecycleEvent event) {
            }

            @Override
            public void onUnload(ChunkLifecycleEvent event) {
            }

            @Override
            public void onBlockChange(FalcoChunk chunk, int x, int y, int z, Block block) {
            }
        });
    }

    /**
     * The read-only storage accessors of the table on that page.
     *
     * @param chunk the chunk to read
     * @return how many sections actually exist
     */
    static int wikiReadWithoutMaterialising(FalcoChunk chunk) {
        chunk.storage().view(0);
        chunk.storage().views();
        chunk.storage().shared(0);
        return chunk.storage().materialisedSections();
    }

    private static void report(String what, Chunk chunk) {
        log.info("{} {} {}", what, chunk.getChunkX(), chunk.getChunkZ());
    }
}
