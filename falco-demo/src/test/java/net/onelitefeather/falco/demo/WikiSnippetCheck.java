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
 * Every code block of the wiki page <i>Instances and chunks</i>, compiled.
 *
 * <p>This class is never run. It exists so that a snippet in the wiki cannot quietly stop compiling
 * against the code it documents.</p>
 */
final class WikiSnippetCheck {

    private WikiSnippetCheck() {
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
