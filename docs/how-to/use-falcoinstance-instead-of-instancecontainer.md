# Use FalcoInstance instead of InstanceContainer

Build an instance that unloads the chunks it loaded when it is unregistered, and whose chunks
allocate their sections on demand. You end with a `FalcoInstance` registered with the server, and
with the steps for the loader, the light engine and the listeners that you need.

**Before you start:** `falco-instance` on the classpath ([How-to Add Falco to your build](add-falco-to-your-build.md)). Keep
using `InstanceContainer` if you need `SharedInstance` views onto this world, or if you rely on any
of the four Minestom sites that branch on `instanceof InstanceContainer` — see
[Explanation Why falco-instance exists](../explanation/why-falco-instance-exists.md).

## Create the shortest form

1. Build the instance and register it in one call:

   ```java
   import net.minestom.server.MinecraftServer;
   import net.minestom.server.world.DimensionType;
   import net.onelitefeather.falco.instance.FalcoInstance;

   FalcoInstance instance = FalcoInstance.builder(DimensionType.OVERWORLD)
           .register(MinecraftServer.getInstanceManager());
   ```

   `register` builds the instance and registers it, in that order, and returns it. The constructors are
   public as well; their signatures are in
   [Reference API surface and stability](../reference/api-surface-and-stability.md#entry-points-and-signatures).

## Add a chunk loader and shut down cleanly

2. Give the instance a loader, and let it own the loader and save on shutdown:

   ```java
   import net.minestom.server.MinecraftServer;
   import net.minestom.server.world.DimensionType;
   import net.onelitefeather.falco.anvil.FalcoAnvilLoader;
   import net.onelitefeather.falco.instance.FalcoInstance;

   import java.nio.file.Path;

   FalcoAnvilLoader loader = new FalcoAnvilLoader(Path.of("worlds", "lobby"), DimensionType.OVERWORLD.key());

   FalcoInstance instance = FalcoInstance.builder(DimensionType.OVERWORLD)
           .chunkLoader(loader)
           .autoChunkLoad(true)
           .ownsLoader(true)
           .saveOnShutdown(true)
           .registerAndShutdownWith(MinecraftServer.getInstanceManager(),
                   MinecraftServer.getSchedulerManager());
   ```

   `registerAndShutdownWith` is the form to prefer when the instance owns its loader. The reasons for the
   three flags are in [Explanation Why falco-instance exists](../explanation/why-falco-instance-exists.md#owning-the-loader-and-saving-on-shutdown).

3. If you register by hand instead, `shutdown(InstanceManager)` does the same work, and
   `unregister(InstanceManager)` unregisters without saving.

## Add the light engine

4. Pass the scheduler's supplier to the builder, with the loader from step 2:

   ```java
   import net.minestom.server.MinecraftServer;
   import net.minestom.server.world.DimensionType;
   import net.onelitefeather.falco.instance.FalcoInstance;
   import net.onelitefeather.falco.light.ChunkLightScheduler;
   import net.onelitefeather.falco.light.ChunkLightService;

   // The scheduler takes the light service, not the instance — it binds to an instance later,
   // through the chunks its supplier produces.
   ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService());

   FalcoInstance instance = FalcoInstance.builder(DimensionType.OVERWORLD)
           .chunkLoader(loader)
           .chunkSupplier(scheduler.supplier())
           .autoChunkLoad(true)
           .register(MinecraftServer.getInstanceManager());
   ```

   The supplier produces `FalcoLightingChunk`s. You write no listener for the light; how the two parts
   fit together is in [Explanation Why falco-instance exists](../explanation/why-falco-instance-exists.md#the-light-engine-on-the-instance).

## Listen to chunk loads and unloads

5. Pass two consumers to the builder, which is enough for most things:

   ```java
   import net.minestom.server.MinecraftServer;
   import net.minestom.server.world.DimensionType;
   import net.onelitefeather.falco.instance.FalcoInstance;

   FalcoInstance.builder(DimensionType.OVERWORLD)
           .chunkLifecycle(
                   chunk -> logger.info("loaded {} {}", chunk.getChunkX(), chunk.getChunkZ()),    // your logger
                   chunk -> logger.info("unloaded {} {}", chunk.getChunkX(), chunk.getChunkZ()))
           .register(MinecraftServer.getInstanceManager());
   ```

   Minestom's `InstanceChunkLoadEvent` and `InstanceChunkUnloadEvent` are also dispatched, and for most
   application code they are the better choice. When to use which is explained in
   [Explanation Why falco-instance exists](../explanation/why-falco-instance-exists.md#events-and-the-chunk-lifecycle-listener).

6. For the full set of callbacks, implement `ChunkLifecycleListener` and register it on the lifecycle of
   the instance. Every method has a default, so implement only what you need. The methods are listed in
   [Reference API surface and stability](../reference/api-surface-and-stability.md#chunk-lifecycle-listener).

   ```java
   import net.minestom.server.instance.block.Block;
   import net.onelitefeather.falco.instance.ChunkLifecycleListener;
   import net.onelitefeather.falco.instance.FalcoChunk;

   instance.lifecycle().addListener(new ChunkLifecycleListener() {
       @Override
       public void onBlockChange(FalcoChunk chunk, int x, int y, int z, Block block) {
           // record the position only, nothing else; see the two rules below
       }
   });
   ```

   Two things to know before you write one:

   - **`onBlockChange` runs inside the chunk's write lock.** Writing another block from it re-enters that
     lock, and calling into code that takes a different lock is how a deadlock is built. Keep it to
     recording the position.
   - **A listener that throws fails the load** rather than being swallowed, and the caller waiting on the
     chunk sees it. That is deliberate: a chunk whose listener failed is not a chunk anybody should be
     handed.

## Share a world through a container

7. A `FalcoInstance` cannot back a shared instance. To get a shared view, create a container, give it the
   Falco chunk type, and wrap it in a `FalcoSharedInstance`:

   ```java
   import java.util.UUID;
   import net.minestom.server.MinecraftServer;
   import net.minestom.server.instance.InstanceContainer;
   import net.minestom.server.instance.InstanceManager;
   import net.onelitefeather.falco.instance.FalcoChunk;
   import net.onelitefeather.falco.instance.FalcoSharedInstance;

   InstanceManager manager = MinecraftServer.getInstanceManager();
   InstanceContainer world = manager.createInstanceContainer();
   world.setChunkSupplier(FalcoChunk::new);

   // Not manager.createSharedInstance(world): that factory always builds Minestom's own type.
   FalcoSharedInstance view = new FalcoSharedInstance(UUID.randomUUID(), world);
   manager.registerSharedInstance(view);
   ```

   The view keeps its own generator, chunk supplier and auto-load setting. The order of the calls and the
   limits of sharing are explained in [Explanation Why falco-instance exists](../explanation/why-falco-instance-exists.md#shared-instances-refused-by-the-compiler-which-is-the-good-outcome).

## Set a generator

Generation uses the usual Minestom API:

```java
instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
```

The generator is handed copies of the section palettes, and they are moved over only when it returns.
A generator that fails halfway therefore leaves the chunk as it was, instead of half built and published.

## Read a chunk without materialising its sections

8. Read through `chunk.storage()` and not through `getSections()`, `getSection(int)` or `sections()`. The
   read-only counterparts and what they replace are listed in
   [Reference API surface and stability](../reference/api-surface-and-stability.md#read-only-section-access).

## Check it worked

`InstanceManager#unregisterInstance` leaves no chunks, tick partitions or entities behind — the
behaviour `InstanceContainer` has and every other instance type does not. `materialisedSections()`
stays well below 24 on a chunk with empty sky.

## If it does not work

A `ChunkSupplier` producing anything but a `FalcoChunk` is rejected with a message naming the cause.
That is deliberate: such a chunk would be accepted everywhere except the unload path and would then
report itself as loaded forever.

See also: [Explanation Why falco-instance exists](../explanation/why-falco-instance-exists.md) for the leak this closes and the four
divergences · [Reference Measured results](../reference/measured-results.md) for the object and byte counts ·
[Explanation Scope and non-goals](../explanation/scope-and-non-goals.md)
