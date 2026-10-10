# Keep chunk light up to date automatically

Have chunks relight themselves when blocks change, instead of noticing the change and calling the
engine yourself. You end with an instance whose chunks are lit by a scheduler, and with a block change
that shows up lit on the next tick, without a call of your own. This is the same entry point Minestom
offers with `setChunkSupplier(LightingChunk::new)`.

**Before you start:** `falco-light` on the classpath, and an `Instance` whose chunk supplier you can
set.

## Set the scheduler's chunk supplier

1. Every call in [How-to Compute light for a loaded world](compute-light-for-a-loaded-world.md) is one you make yourself: you have to
   notice that a chunk changed and decide when to recompute it. `FalcoLightingChunk` removes that step,
   and it is the same entry point Minestom sets with `setChunkSupplier(LightingChunk::new)`. Create a
   scheduler and set its supplier on your `Instance` (`// your Instance`):

   ```java
   import net.onelitefeather.falco.light.ChunkLightScheduler;
   import net.onelitefeather.falco.light.ChunkLightService;

   ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService());
   instance.setChunkSupplier(scheduler.supplier()); // your Instance
   ```

2. That is the whole setup. Keep one scheduler per instance: a second instance handed to the same one
   is refused with an `IllegalStateException`. A server with many worlds needs one scheduler per world.
   The five types the engine is made of, and what each one is responsible for, are listed in
   [Reference API surface and stability](../reference/api-surface-and-stability.md#light-engine-types).

## Choose the executor in a test

1. Inject a direct executor when a test needs a deterministic result. The task then runs on the calling
   thread, so a tick is finished when `onTick` returns:

   ```java
   // Deterministic: the task runs on the calling thread, so a tick is finished when onTick returns.
   ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService(), Runnable::run, 16);
   ```

The default gives every area its own virtual thread and bounds them with a semaphore at the processor
count, the same shape `FalcoAnvilLoader` uses for its saves. The bound sits inside the task rather
than around the submission on purpose: acquiring it before starting the thread would block whichever
chunk happened to trigger the pass, and that chunk is being ticked by the server.

A direct executor turns the whole cycle synchronous, which is what makes the tests of this path
deterministic rather than timing-dependent — place a block, call `tick`, assert the light. A server
that already has a pool can hand that one over instead.

## Replay a single block change by hand

1. Use the incremental state directly only if you drive the engine yourself. `ChunkLightState` is what the
   scheduler runs on, and it keeps the light of a chunk between changes:

   ```java
   import java.util.List;
   import net.onelitefeather.falco.light.ChunkLightState;
   import net.onelitefeather.falco.light.LightNibbles;

   ChunkLightState state = ChunkLightState.blockLight(opacityTables); // your List<SectionOpacity>

   // after a block changed at that position
   state.update(updatedOpacityTables, x, y, z); // your List<SectionOpacity> after the change
   List<LightNibbles> light = state.toSections();
   ```

   The scheduler reports the changed position; `ChunkLightState#update` replays it, and a removed light
   source is retracted before the light is spread again. How this works, and why the update is identical to a
   full recalculation, is explained in [Explanation How the light engine works](../explanation/how-the-light-engine-works.md#incremental-updates-and-what-they-keep).

## Tune the scheduler

The builder carries the settings that matter under load. Every one has a default; the values below
are the ones you would most often set:

```java
ChunkLightScheduler scheduler = ChunkLightScheduler.builder(lighting)
        .executor(ChunkLightScheduler.defaultExecutor())
        .maxAreaSize(4)               // chunks per side of one lighting area
        .maxCachedChunks(256)         // opacity tables kept between passes
        .skyLight(ChunkLightScheduler.SkyLight.FROM_DIMENSION)
        .onFailure(throwable -> log.error("lighting failed", throwable))
        .build();
```

`maxAreaSize` decides where one lighting area ends: a larger value means fewer seams between areas, and
a smaller one means less work per pass. `SkyLight.FROM_DIMENSION` computes sky light only when the
dimension has skylight. `onFailure` receives what a failed pass threw, so a lighting failure is logged
instead of disappearing.

## Tell the scheduler about changes Falco did not make

When something outside Falco changes the world, say so, and the chunk is relit on the next tick:

```java
scheduler.markChanged(instance, 0, 0);              // this chunk needs relighting
scheduler.markChanged(instance, 0, 0, 8, 40, 8);    // this position did
scheduler.markDirty(instance, 0, 0);                // relight without an incremental path
```

## Use it on an InstanceContainer

On a `FalcoInstance` the supplier is all you need. On an `InstanceContainer`, build the chunks yourself,
hang a `ChunkLightListener` on each one, and tick the scheduler from a repeating task:

```java
container.setChunkSupplier((instance, x, z) -> {
    FalcoChunk chunk = new FalcoChunk(instance, x, z);
    chunk.addLifecycleListener(new ChunkLightListener(scheduler));
    return chunk;
});
MinecraftServer.getSchedulerManager().buildTask(() -> scheduler.onTick(container, System.currentTimeMillis()))
        .repeat(TaskSchedule.tick(1))
        .schedule();
```

Use `FalcoInstance` unless you need the container's own write path; see
[Use FalcoInstance instead of InstanceContainer](use-falcoinstance-instead-of-instancecontainer.md).

## Check it worked

Place a block, let one tick pass, and the surrounding light is correct without any call of your own.
With a direct executor (`Runnable::run`) the pass is finished when `onTick` returns, which is what
makes a test of this path deterministic rather than timing-dependent.

## If it does not work

An `IllegalStateException` when handing a second instance to the same scheduler is by design: the
dirty set is keyed by chunk coordinates alone. A server with many worlds needs one scheduler per
world.

See also: [How-to Compute light for a loaded world](compute-light-for-a-loaded-world.md) for the manual calls ·
[Explanation How the light engine works](../explanation/how-the-light-engine-works.md) · [Explanation Scope and non-goals](../explanation/scope-and-non-goals.md) for the cap on
kept light and the seam an area split leaves
