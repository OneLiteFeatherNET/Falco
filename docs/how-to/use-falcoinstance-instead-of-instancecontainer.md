# Use FalcoInstance instead of InstanceContainer

Build an instance that unloads the chunks it loaded when it is unregistered, and whose chunks
allocate their sections on demand.

**Before you start:** `falco-instance` on the classpath ([How-to Add Falco to your build](add-falco-to-your-build.md)). Keep
using `InstanceContainer` if you need `SharedInstance` views onto this world, or if you rely on any
of the four Minestom sites that branch on `instanceof InstanceContainer` — see
[Explanation Why falco-instance exists](../explanation/why-falco-instance-exists.md).

## The shortest form

```java
FalcoInstance instance = FalcoInstance.builder(DimensionType.OVERWORLD)
        .register(MinecraftServer.getInstanceManager());
```

`register` builds the instance and registers it, in that order, and returns it. The builder is
immutable — every method returns a new one — so a half-configured builder can be handed around
without anybody changing it underneath.

The constructors are public as well if a builder is not what you want:
`new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD)`.

## With a chunk loader

```java
FalcoAnvilLoader loader = new FalcoAnvilLoader(Path.of("worlds", "lobby"), DimensionType.OVERWORLD.key());

FalcoInstance instance = FalcoInstance.builder(DimensionType.OVERWORLD)
        .chunkLoader(loader)
        .autoChunkLoad(true)
        .ownsLoader(true)
        .saveOnShutdown(true)
        .registerAndShutdownWith(MinecraftServer.getInstanceManager(),
                MinecraftServer.getSchedulerManager());
```

`registerAndShutdownWith` is the form worth preferring when the instance owns its loader.
`ownsLoader(true)` means the instance closes the loader on shutdown, `saveOnShutdown(true)` means it
writes its chunks first, and the shutdown task is registered for you — the three together are the
part that is easy to get wrong by hand, because a loader closed before the save loses the save.

If you register by hand instead, `shutdown(InstanceManager)` does the same work and
`unregister(InstanceManager)` unregisters without saving.

## With the light engine

This is the combination that did not work before `1.0.0`:

```java
// The scheduler takes the light service, not the instance — it binds to an instance later,
// through the chunks its supplier produces.
ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService());

FalcoInstance instance = FalcoInstance.builder(DimensionType.OVERWORLD)
        .chunkLoader(loader)
        .chunkSupplier(scheduler.supplier())
        .autoChunkLoad(true)
        .register(MinecraftServer.getInstanceManager());
```

The supplier produces `FalcoLightingChunk`s, which extend `FalcoChunk`. Each one reports its block
changes, loads and ticks to the scheduler, and the scheduler lights the touched regions and sends
them. You write no listener.

`falco-light` declares `falco-instance` as `compileOnly`, so adding the light module does not bring
this one with it — declare both, or use the BOM. See [Add Falco to your build](add-falco-to-your-build.md).

## Watching the lifecycle

**Minestom's own events still fire, and for most application code they are the right thing.**
`FalcoInstance` dispatches `InstanceChunkLoadEvent` and `InstanceChunkUnloadEvent`, and the block
writer dispatches `PlayerBlockBreakEvent`, so a listener registered on the `GlobalEventHandler`
works here exactly as it does on an `InstanceContainer`. Take those unless you have a reason not to.

`ChunkLifecycleListener` is the other thing, and the difference is *when*, not *what*:

| | `ChunkLifecycleListener` | `InstanceChunkLoadEvent` |
| --- | --- | --- |
| when | during the transition, inside the lock | after it, once the chunk is published and the future completed |
| may call back into the instance | mostly no — stated per method | yes |
| a throw | fails the chunk load and reaches the caller waiting on it | does not |
| per block written | `onBlockChange`, no allocation | no equivalent |

Take the listener when you must act *before* anybody else sees the chunk, or when you need per-block
notification — which is what the light engine uses it for. Take the events for anything else.

Two ways into the listener, depending on how much you need.

The builder takes two consumers, which is enough for most things:

```java
FalcoInstance.builder(DimensionType.OVERWORLD)
        .chunkLifecycle(
                chunk -> log.info("loaded {} {}", chunk.getChunkX(), chunk.getChunkZ()),
                chunk -> log.info("unloaded {} {}", chunk.getChunkX(), chunk.getChunkZ()))
        .register(manager);
```

For the full set, implement `ChunkLifecycleListener` and register it on the lifecycle. Every method
has a default, so implement only what you need:

```java
instance.lifecycle().addListener(new ChunkLifecycleListener() {
    @Override
    public void onPublish(ChunkLifecycleEvent event) { }   // visible to the world, before onLoad

    @Override
    public void onLoad(ChunkLifecycleEvent event) { }

    @Override
    public void onTick(ChunkLifecycleEvent event) { }

    @Override
    public void onUnload(ChunkLifecycleEvent event) { }

    @Override
    public void onBlockChange(FalcoChunk chunk, int x, int y, int z, Block block) { }
});
```

Two things to know before you write one:

- **`onBlockChange` runs inside the chunk's write lock.** Writing another block from it re-enters that
  lock, and calling into code that takes a different lock is how a deadlock is built. Keep it to
  recording the position.
- **A listener that throws fails the load** rather than being swallowed, and the caller waiting on the
  chunk sees it. That is deliberate: a chunk whose listener failed is not a chunk anybody should be
  handed.

## Shared worlds

A `FalcoInstance` **cannot** back a shared instance, and this has not changed: `SharedInstance` takes
an `InstanceContainer` and nothing else, so the attempt does not compile. What `FalcoSharedInstance`
does is fix a container-backed view:

```java
InstanceManager manager = MinecraftServer.getInstanceManager();
InstanceContainer world = manager.createInstanceContainer();
world.setChunkSupplier(FalcoChunk::new);

// Not manager.createSharedInstance(world): that factory always builds Minestom's own type.
FalcoSharedInstance view = new FalcoSharedInstance(UUID.randomUUID(), world);
manager.registerSharedInstance(view);
```

The order matters. `createInstanceContainer` registers the container, and the view's constructor
refuses one that is not registered — `registerSharedInstance` performs no such check, and an
unregistered container is ticked by nobody.

The view keeps its own generator, chunk supplier and auto-load setting, where Minestom's writes all
three through to the container and lets one view reconfigure another. `saveInstance()` writes the
view's own tags rather than the container's.

What it does not change is who owns the blocks: `setBlock` reaches the container, which serialises
every write on its own monitor. A world that needs Falco's write path uses `FalcoInstance` and gives
up sharing.


## Read a chunk without materialising its sections

`getSections()` and `getSection(int)` materialise every section they hand back, because Minestom's
contract lets a caller write into the result. On a lazy chunk that turns a read into 24 allocations.
`Heightmap#getHeight` does the same for the sections it descends through.

Reach the storage with `chunk.storage()` and use the read-only counterparts:

| To read | Use | Not |
| --- | --- | --- |
| one section | `view(int)` | `section(int)` |
| all sections | `views()` | `sections()` |
| how many exist | `materialisedSections()` | — |
| whether one is still the shared empty | `shared(int)` | — |

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
