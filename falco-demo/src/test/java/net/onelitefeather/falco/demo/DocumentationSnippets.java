package net.onelitefeather.falco.demo;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.block.Block;
import net.minestom.server.world.DimensionType;
import net.onelitefeather.falco.anvil.FalcoAnvilLoader;
import net.onelitefeather.falco.instance.ChunkLifecycleEvent;
import net.onelitefeather.falco.instance.ChunkLifecycleListener;
import net.onelitefeather.falco.instance.FalcoChunk;
import net.onelitefeather.falco.instance.FalcoInstance;
import net.onelitefeather.falco.instance.FalcoSharedInstance;
import net.onelitefeather.falco.light.ChunkLightScheduler;
import net.onelitefeather.falco.light.ChunkLightService;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Every code block the documentation shows, compiled: the quick start of the readme and the wiki
 * page <i>Instances and chunks</i>.
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

    private DocumentationSnippets() {
    }

    /**
     * Readme, quick start, step 5 — all three modules together.
     *
     * @return the instance the snippet builds
     */
    static FalcoInstance readmeAllThreeModules() {
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
     * Readme, quick start, step 4 — the two operations without a listener around them.
     *
     * @param instance the instance to read from
     * @param lighting the light service
     * @return the block light level the snippet prints
     */
    static int readmeCheckWithoutClient(FalcoInstance instance, ChunkLightService lighting) {
        Chunk chunk = instance.loadChunk(0, 0).join();
        lighting.calculate(chunk);

        return lighting.blockLightAt(chunk, 8, 40, 8);
    }

    static FalcoInstance shortestForm() {
        return FalcoInstance.builder(DimensionType.OVERWORLD)
                .register(MinecraftServer.getInstanceManager());
    }

    static FalcoInstance withChunkLoader() {
        FalcoAnvilLoader loader = new FalcoAnvilLoader(Path.of("worlds", "lobby"), DimensionType.OVERWORLD.key());

        return FalcoInstance.builder(DimensionType.OVERWORLD)
                .chunkLoader(loader)
                .autoChunkLoad(true)
                .ownsLoader(true)
                .saveOnShutdown(true)
                .registerAndShutdownWith(MinecraftServer.getInstanceManager(),
                        MinecraftServer.getSchedulerManager());
    }

    static FalcoInstance withLightEngine() {
        FalcoAnvilLoader loader = new FalcoAnvilLoader(Path.of("worlds", "lobby"), DimensionType.OVERWORLD.key());
        ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService());

        return FalcoInstance.builder(DimensionType.OVERWORLD)
                .chunkLoader(loader)
                .chunkSupplier(scheduler.supplier())
                .autoChunkLoad(true)
                .register(MinecraftServer.getInstanceManager());
    }

    static FalcoInstance withLifecycleConsumers(InstanceManager manager) {
        return FalcoInstance.builder(DimensionType.OVERWORLD)
                .chunkLifecycle(
                        chunk -> report("loaded", chunk),
                        chunk -> report("unloaded", chunk))
                .register(manager);
    }

    static void withLifecycleListener(FalcoInstance instance) {
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

    static FalcoSharedInstance sharedWorld() {
        InstanceManager manager = MinecraftServer.getInstanceManager();
        InstanceContainer world = manager.createInstanceContainer();
        world.setChunkSupplier(FalcoChunk::new);

        FalcoSharedInstance view = new FalcoSharedInstance(UUID.randomUUID(), world);
        manager.registerSharedInstance(view);
        return view;
    }

    static int readWithoutMaterialising(FalcoChunk chunk) {
        chunk.storage().view(0);
        chunk.storage().views();
        chunk.storage().shared(0);
        return chunk.storage().materialisedSections();
    }

    private static void report(String what, Chunk chunk) {
        System.out.println(what + " " + chunk.getChunkX() + " " + chunk.getChunkZ());
    }
}
