# Why falco-instance exists

Minestom ships an `Instance` implementation that works, and this module claims no performance
advantage over it. So why does `falco-instance` exist at all, what does it deliberately refuse to
do, and what would somebody evaluating it need to know before depending on it? This document
answers those three questions from the sources, and it names the places where the honest answer is
"we do not know" or "this is worse".

The prior investigation is [`docs/research/instance-container.md`](../research/instance-container.md)
and it is binding here: everything below either follows from it or corrects it against the code as
it now stands. Minestom references are to version `2026.06.20-26.1.2`, the version this repository
compiles against [1].

## The question that was asked, and the answer that came back

The request that started this was: build an `InstanceContainer` that is *1:1 compatible* with
Minestom but faster and more maintainable. The investigation returned a partial verdict, and both
halves of it survived contact with the code:

* **It is possible to run a foreign `Instance`.** An agent wrote a complete `ForeignInstance extends
  Instance` in a foreign package, implemented all 19 abstract methods, compiled it and ran it against
  a real `MinecraftServer.init()`. It registered, loaded a chunk, answered `getBlock`, and ticked.
* **"1:1 compatible" is not reachable**, because four sites in Minestom branch on
  `instanceof InstanceContainer` and take a different path for anything else, silently.
* **The performance premise was wrong.** The chunk and entity tick parallelism does not live in the
  instance at all.

What was actually built is therefore much narrower than what was asked for, and the narrowing is the
point. The module is one class that solves one problem — a world that cleans up after itself when it
is unregistered — plus the chunk subclass that problem drags along.

Note that [`STATUS.md`](../../STATUS.md) still records this under *Investigated and deliberately not
built* ("Own `InstanceContainer`: **Not built**"). That row was written before `cee5f94` added the
module and describes the container replacement that was rejected, not the `Instance` subclass that
was built. The verdict it records — *possible but pointless as asked* — is still the reason the thing
that got built is not a container replacement.

## What the platform permits

Whether a part of Minestom can be replaced at all is decided by sealed-ness, and it has to be checked
rather than guessed. `Palette` cannot be replaced: `javap -v` on
`net.minestom.server.instance.palette.Palette` shows a `PermittedSubclasses` attribute naming
`PaletteImpl`, so a foreign implementation is a hard compiler error [1].

`Instance` and `InstanceContainer` carry no such attribute. Verified the same way against the jar of
`2026.06.20-26.1.2`:

| Type | Declaration | `PermittedSubclasses` |
| --- | --- | --- |
| `net.minestom.server.instance.palette.Palette` | `public interface` | present, permits `PaletteImpl` |
| `net.minestom.server.instance.Instance` | `public abstract class` | absent |
| `net.minestom.server.instance.InstanceContainer` | `public class` | absent |

More than that, the platform invites it. The class comment on `Instance` reads: *"WARNING: when
making your own implementation registering the instance manually is required with
`InstanceManager#registerInstance(Instance)`, and you need to be sure to signal the `ThreadDispatcher`
of every partition/element changes."* [1] That sentence is doing two jobs — it grants permission, and
it moves the partition bookkeeping onto the implementer. `FalcoInstance` does that bookkeeping in
`cacheChunk` (`dispatcher().createPartition(chunk)`) and in `unloadChunk`
(`dispatcher().deletePartition(chunk)`).

`Instance` declares 19 abstract methods and contributes the rest — world border, weather, clocks,
entity tracker, scheduler, event node, snapshots — for free from its 1 142 lines. `InstanceContainer`
adds 776 lines on top of that. Starting from `Instance` rather than from the container means writing
those 19 methods and inheriting nothing that has to be worked around.

## The four places where a foreign instance quietly behaves differently

Seven sites in Minestom name the concrete `InstanceContainer` type. Three of them are inside
`SharedInstance.areLinked` and are covered below. The other four decide behaviour, and none of them
raises anything for a foreign type — they take the other branch and continue.

| Site | What happens to a foreign instance |
| --- | --- |
| `InstanceManager.unregisterInstance` | Chunks stay loaded and their tick partitions stay alive |
| `SharedInstance` (field, constructor, `areLinked`) | Cannot be constructed at all — a compile error |
| `Chunk` constructor | Every chunk gets an empty shared-instance list |
| `ChunkBatch.updateChunk`, `AbsoluteBlockBatch` | `refreshLastBlockChangeTime()` is not called |

Silent divergence is worse than a compile error, because it surfaces as a bug much later and
somewhere else. That is the single sentence the whole module is built around.

### `unregisterInstance` leaks every chunk, and that is why this module exists

`InstanceManager.unregisterInstance(Instance)` reads, in full [1]:

> ```java
> synchronized (instance) {
>     InstanceUnregisterEvent event = new InstanceUnregisterEvent(instance);
>     EventDispatcher.call(event);
>
>     // Unload all chunks
>     if (instance instanceof InstanceContainer) {
>         instance.getChunks().forEach(instance::unloadChunk);
>         var dispatcher = MinecraftServer.process().dispatcher();
>         instance.getChunks().forEach(dispatcher::deletePartition);
>     }
>     // Unregister
>     instance.setRegistered(false);
>     this.instances.remove(instance);
> }
> ```

The method's own Javadoc says so plainly — *"If `instance` is an `InstanceContainer` all chunks are
unloaded"* — so this is documented behaviour rather than an oversight. It is nonetheless a leak for
anyone who takes the `Instance` Javadoc up on its invitation. After the call, the instance is gone
from the manager and nothing will ever reach it again, while every chunk it ever loaded is still
alive, still holding its sections and its entities, and still registered as a tick partition with the
global dispatcher.

`FalcoInstance#unregister(InstanceManager)` is the answer, and it is nine lines:

```java
public void unregister(InstanceManager instanceManager) {
    if (isRegistered()) instanceManager.unregisterInstance(this);
    for (Chunk chunk : List.copyOf(this.chunks.values())) unloadChunk(chunk);
}
```

It delegates *first* rather than doing its own cleanup first, so listeners of
`InstanceUnregisterEvent` still see a populated instance, in the same order they would for a
container. Then it performs the unload the manager skipped. `FalcoInstance#unloadChunk` does the
whole job: it removes the chunk from the map, sends `UnloadChunkPacket` to its viewers, fires
`InstanceChunkUnloadEvent`, removes the entities the tracker reports for that chunk, clears the
chunk's loaded flag and deletes the tick partition.

Two honest caveats about this design.

**It is opt-in, and the leak is one call away.** A caller who writes
`instanceManager.unregisterInstance(falcoInstance)` — which compiles, and which is what every piece of
Minestom documentation shows — gets the leak back in full. There is no way to prevent that from a
foreign package: `unregisterInstance` is not a method that can be overridden by an instance, and
Minestom offers no hook that runs on unregistration of a non-container. `unregister` is a convention
this module documents and tests, not a guarantee it can enforce.

**The behaviour it compensates for is pinned by a test, not assumed.**
`FalcoInstanceUnloadTest#testTheInstanceManagerAloneStillLeaksTheChunks` calls
`manager.unregisterInstance(instance)` directly and asserts the *Minestom* outcome — instance
unregistered, chunk still `isLoaded()`, `getChunks().size() == 1`. The test comment says why: *"If
this ever starts to fail, Minestom has learned to clean up foreign instances and the own path can
shrink."* This is the compatibility-test discipline the research document asked for, applied to the
one site that matters. Five further cases in the same class pin the Falco side: the chunk is removed
and its flag cleared, the unload event fires exactly once even when `unloadChunk` is called twice,
`unregister` unloads every chunk, the unregister event still fires, and calling `unregister` twice is
harmless so it is safe as a shutdown step that may run more than once.

### Shared instances: refused by the compiler, which is the good outcome

`SharedInstance` holds `private final InstanceContainer instanceContainer` and its constructor is
`SharedInstance(UUID, InstanceContainer)`. `InstanceManager.createSharedInstance(InstanceContainer)`
has the same parameter type [1]. A `FalcoInstance` therefore cannot back a shared instance, and the
attempt does not compile.

That is the one branch of the four that behaves well. A compile error at the call site is exactly
what should happen when a feature is unavailable, and no work was done to route around it. The module
documents the limitation on `FalcoInstance` and in its `package-info.java` and leaves it there.
`SharedInstance.areLinked(Instance, Instance)` will return `false` for any pair involving a
`FalcoInstance`, which follows from the same typing and is the correct answer, since no
`FalcoInstance` ever shares chunks with anything.

### The chunk constructor and the empty viewer list

`Chunk`'s constructor computes the shared instances whose players must also see the chunk [1]:

```java
final List<SharedInstance> shared = instance instanceof InstanceContainer instanceContainer ?
        instanceContainer.getSharedInstances() : List.of();
this.viewable = instance.getEntityTracker().viewable(shared, chunkX, chunkZ);
```

For a foreign instance this is `List.of()` forever. In the general case that is a real defect — a
foreign instance that *did* support shared instances would find their players never receiving chunk
updates. Here it is not, and for a specific reason rather than by luck: because the previous point
makes shared instances impossible, the empty list is the correct value. The two limitations cancel.
It is worth writing down that they cancel, because a future version that found a way to support
shared instances would re-open this one.

### The batches and `refreshLastBlockChangeTime`

`ChunkBatch.updateChunk` and `AbsoluteBlockBatch` both contain the same shape, with a `FIXME` from
Minestom's own authors sitting on it [1]:

```java
if (instance instanceof InstanceContainer) {
    // FIXME: put method in Instance instead
    ((InstanceContainer) instance).refreshLastBlockChangeTime();
}
```

`FalcoInstance` keeps a `lastBlockChangeTime` of its own and updates it on every block write, and
exposes `refreshLastBlockChangeTime()` so a caller that writes through a `Chunk` directly can keep it
truthful. What it cannot do is make the batches call it. The consequence is stated rather than
worked around: a batch applied to a `FalcoInstance` does not move the timestamp, so the timestamp
must not be used to detect batch-applied changes. The Javadoc on `getLastBlockChangeTime()` says so
at the accessor, which is where somebody would otherwise get it wrong.

## Why a `FalcoChunk` had to come along

The research document flagged a second obstacle next to the four `instanceof` sites: *"the chunk
lifecycle hooks are `protected` and unreachable from a foreign package."* This is the finding that
decided the shape of the module.

`net.minestom.server.instance.Chunk` declares [1]:

```java
protected volatile boolean loaded = true;

protected void onLoad() {}

protected void unload() {
    this.loaded = false;
}
```

Three properties of those nine lines matter together. The hooks are `protected` with no public
equivalent, so nothing outside `net.minestom.server.instance` can call them. `loaded` starts at
`true`. And `Chunk#isLoaded()` — which is what every `ChunkUtils.isLoaded` overload in Minestom reads,
and through them a large amount of entity, viewer and block code — returns that field verbatim. An
instance in a foreign package therefore cannot ever mark a chunk as unloaded, and a chunk it drops
keeps telling the rest of the server it is loaded for as long as anything holds a reference to it.

A subclass in this module re-exposes the two hooks as `markLoaded()` and `markUnloaded()`. That is the
whole of `FalcoChunk` apart from one override. Reflection with `setAccessible` was rejected: it would
break on the next JDK that closes the door, it needs an open module or a JVM flag from every
consumer, and it would hide the coupling instead of naming it. `FalcoChunkTest` checks the barrier
directly — `testMarkLoadedIsCallableFromOutsideTheMinestomPackage` and
`testMarkUnloadedReachesTheProtectedHook` exist to fail if the access ever changes.

The price is that the module drags a chunk implementation along, and the price is paid by the caller
too. `FalcoInstance#requireFalcoChunk` refuses any chunk that is not a `FalcoChunk`, so a foreign
`ChunkSupplier` throws `FalcoInstanceException` at the point where it was installed rather than
producing a chunk that can never be unloaded. `FalcoChunk#copy` is overridden for the same reason:
`DynamicChunk#copy` returns a plain `DynamicChunk`, and such a copy handed to a `FalcoInstance` would
be rejected — or, worse, accepted by an earlier version and then be unloadable.

### The third barrier, and why the superclass is `DynamicChunk`

There is a member with exactly the same problem that is easy to miss, and it decided which class
`FalcoChunk` extends. `Chunk` declares the block setter that carries a placement and a destruction as
`protected abstract` [1]:

```java
protected abstract void setBlock(int x, int y, int z, Block block,
                                 @Nullable BlockHandler.Placement placement,
                                 @Nullable BlockHandler.Destroy destroy);
```

`DynamicChunk` widens exactly that method to `public` [1]. Since an instance implementation *must*
call it — it is the only way to hand a placement or a destruction down to the block handler, and
`placeBlock` and `breakBlock` are meaningless without it — extending `DynamicChunk` rather than
`Chunk` settles this barrier at the same time as the other two. That is why `FalcoInstance#writeBlock`
takes a `FalcoChunk` and not a `Chunk` in its signature: the type is what makes the call legal.

Beyond re-exposing those members, `FalcoChunk` adds nothing — no storage, no light handling, no
packet handling. Minestom's block storage was never the part that needed replacing.

## No speed gain is claimed

This is the part most likely to be misread, so it is stated flatly: **`falco-instance` is not faster
than `InstanceContainer`, and no measurement in this repository says it is.** `falco-benchmarks`
contains benchmarks for the Anvil loader and the light engine only; there is no instance benchmark,
and therefore no figure to quote. Every performance statement below is read from source and labelled
as such.

The reason the module makes no throughput claim is structural. Chunk and entity ticking is not done
by the instance. `ServerProcessImpl.serverTick` does this [1]:

```java
// Tick all instances
for (Instance instance : instance().getInstances()) {
    try { instance.tick(milliStart); } catch (Exception e) { exception().handleException(e); }
}
// Tick all chunks (and entities inside)
dispatcher().updateAndAwait(nanoStart);
```

`Instance#tick` handles the scheduler, world age, clocks, weather, the tick event and the world
border, and no chunks. Chunks and entities are ticked by
`ThreadDispatcher<Chunk, Entity> dispatcher`, a single field on `ServerProcessImpl` shared by every
instance on the server, constructed as
`ThreadDispatcher.dispatcher(ThreadProvider.counter(), ServerFlag.DISPATCHER_THREADS)` — and
`ServerFlag.DISPATCHER_THREADS` is `intProperty("minestom.dispatcher-threads", 1)`, so it defaults to
one thread [1]. Replacing the instance changes none of that. A server that wants parallel ticking
raises the dispatcher thread count; that works identically with `InstanceContainer` and with
`FalcoInstance`, and neither of them can influence it.

This is the same lesson the research document drew, and it generalises: *sealed-ness decides whether
a replacement is possible, and the profile decides whether it is worth it.* Here it was possible and
not worth it for the stated reason, so the reason to build it had to be a different one.

### What does differ, stated as source reading rather than measurement

Three differences follow from comparing the two implementations line by line. None of them has been
measured, and none of them should be quoted as a factor.

**The block write path takes a narrower lock.** `InstanceContainer.UNSAFE_setBlock` is declared
`private synchronized void`, so it holds the monitor of the whole instance for its entire body —
which includes the chunk write lock, the placement-rule evaluation and the recursive neighbour
updates [1]. Every block write anywhere in the world therefore queues behind every other one.
`FalcoInstance#writeBlock` takes only `chunk.lockWriteLock()`, and takes it around the placement rule
and the `chunk.setBlock` call alone; the neighbour updates happen after the lock is released,
explicitly so that acquiring a second chunk's lock while holding the first cannot deadlock. Two
writes to two different chunks do not wait for each other. Whether that is worth anything on a real
workload is unknown — there is no benchmark, and it is entirely possible that a server whose ticking
is single-threaded by default never contends for that monitor at all.

**The `currentlyChangingBlocks` map of the container is guarded inconsistently.** It is a plain
`java.util.HashMap`. It is read (`isAlreadyChanged`) and written inside `UNSAFE_setBlock`, under the
instance monitor. It is cleared in `tick(long)` under a *different* lock, the `changingBlockLock`
`ReentrantLock`, which nothing else takes [1]. The two accesses are therefore not mutually exclusive:
a tick clearing the map can run concurrently with a block write mutating it, on a non-thread-safe
map. `FalcoInstance` uses a `ConcurrentHashMap` and no second lock, which removes the case rather
than narrowing it. This is a defect in Minestom read from the source; it has not been reproduced with
a probe here, and no claim is made about how often it would bite.

**The chunk map is a different structure, with no number attached.** `InstanceContainer` holds its
chunks in `Long2ObjectSyncMap<Chunk>` from `space.vectrix.flare:flare-fastutil:2.0.1`, which is a
port of Go's `sync.Map` — its own Javadoc cites `https://golang.org/src/sync/map.go` [2]. It splits
into a lock-free `read` map and a `dirty` map behind an implicit lock; after a promotion has emptied
the dirty map, the next write calls `dirtyLocked()`, which allocates a fresh backing map and copies
every non-expunged entry of the read map into it [2]. That is O(number of loaded chunks) on a write,
and chunk loading and unloading are writes. `FalcoInstance` uses a plain `ConcurrentHashMap<Long,
Chunk>` instead, which boxes the key and gives that up in exchange.

The prior investigation reports the sync map as "up to 19.8× slower in the measured access pattern".
**That figure is not reproducible from this repository** — it came from a standalone probe outside
it, there is no instance benchmark here, and this document does not rely on it. What can be said from
the source alone is that the two structures make opposite trade-offs, and that nobody has measured
which one wins for chunk streaming. If that ever matters, it needs a benchmark, not an argument.

## What this module deliberately does not attempt

Each of these is a refusal rather than a gap that nobody got round to, and each throws or fails to
compile rather than degrading quietly.

**World generation.** `generator()` returns `null` always, `setGenerator(Generator)` throws
`FalcoInstanceException` for anything but `null`, and `generateChunk` always throws. Storing a
generator that nothing would ever call is the worse option: the world would come out empty with no
hint as to why. A world here comes from its `ChunkLoader` or stays empty. The research document
recommended leaving the generator path out of a first version and that recommendation was followed
literally. This is the single largest reason to keep using `InstanceContainer`.

**Shared instances.** Refused by the compiler, as described above. Not worked around.

**Batch integration.** The batches will not refresh this instance's block-change timestamp, and
nothing in this module can make them. Documented at the accessor.

**Foreign chunk types.** A `ChunkSupplier` that produces anything but a `FalcoChunk` is rejected with
a message naming the cause, because such a chunk would be accepted everywhere except the unload path
and would then report itself as loaded forever.

**Throughput.** No claim, no benchmark, see above.

The `Instance` API surface that is not listed here is inherited from `Instance` unchanged — world
border, weather, clocks, entity tracker, scheduler, event node, snapshots — and behaves exactly as it
does for any other instance, because none of it is overridden.

## When to keep using `InstanceContainer`

Use Minestom's container if the world is generated rather than loaded, if it backs a `SharedInstance`,
or if block batches are part of how the world is built. Those are the three things this module cannot
do, and two of them will tell you at compile time.

Consider `FalcoInstance` if the world comes from a `ChunkLoader`, if instances are created and
destroyed at runtime — a lobby rotation, a per-match arena, anything where `unregisterInstance` is
called more than once over the life of the process — and if you are willing to call
`FalcoInstance#unregister` instead of the manager's method. The leak that fixes is the entire
argument for the module. Everything else in it is either the machinery that fix requires or a
smaller, more legible version of code that already existed.

The module is marked `@ApiStatus.Experimental` throughout and its API may still change in a minor
release. It is covered by 23 tests, all of which run against a real server process through Cyano's
`MicrotusExtension` rather than against a mock, because the property being tested is that Minestom
accepts the type as an instance — and a mock proves nothing about that.

## Sources

1. **Minestom**, version `2026.06.20-26.1.2`. https://github.com/Minestom/Minestom — read from the
   published sources jar (`net.minestom:minestom:2026.06.20-26.1.2:sources`) and the class files of
   the corresponding binary jar. Types and members cited above:
   `net/minestom/server/instance/Instance.java` (class Javadoc, 19 abstract methods, `tick(long)`),
   `net/minestom/server/instance/InstanceContainer.java` (`UNSAFE_setBlock`, `chunks`,
   `currentlyChangingBlocks`, `changingBlockLock`, `tick(long)`, `isAlreadyChanged`),
   `net/minestom/server/instance/InstanceManager.java` (`unregisterInstance`,
   `createSharedInstance`), `net/minestom/server/instance/SharedInstance.java` (field, constructor,
   `areLinked`), `net/minestom/server/instance/Chunk.java` (constructor, `loaded`, `onLoad`,
   `unload`, `isLoaded`, `protected abstract setBlock`),
   `net/minestom/server/instance/DynamicChunk.java` (public `setBlock` with placement and destroy),
   `net/minestom/server/instance/batch/ChunkBatch.java` (`updateChunk`),
   `net/minestom/server/instance/batch/AbsoluteBlockBatch.java`,
   `net/minestom/server/utils/chunk/ChunkUtils.java` (`isLoaded`),
   `net/minestom/server/ServerProcessImpl.java` (`dispatcher`, `serverTick`),
   `net/minestom/server/ServerFlag.java` (`DISPATCHER_THREADS`),
   `net/minestom/server/instance/palette/Palette.java` (`PermittedSubclasses`, via `javap -v`).
2. **flare-fastutil**, `space.vectrix.flare:flare-fastutil:2.0.1`, read from the published sources
   jar: `space/vectrix/flare/fastutil/Long2ObjectSyncMap.java` (interface Javadoc) and
   `Long2ObjectSyncMapImpl.java` (`dirtyLocked`, `missLocked`, `promoteLocked`). The implementation
   is a port of Go's `sync.Map`, which its own Javadoc cites as
   https://golang.org/src/sync/map.go.
3. **Research: a multithreaded `InstanceContainer` replacement**,
   [`docs/research/instance-container.md`](../research/instance-container.md) in this repository —
   the prior investigation, including the `ForeignInstance` probe transcript and the sync-map figure
   that is explicitly not relied on here.
4. **Status — Anvil chunk loader and light engine**, [`STATUS.md`](../../STATUS.md) in this
   repository — the working record, including the *What can and cannot be replaced* section and the
   *Investigated and deliberately not built* verdict on `InstanceContainer`.
5. **`falco-instance` sources and tests** in this repository:
   `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java`,
   `FalcoChunk.java`, `FalcoInstanceException.java`, `package-info.java`, and
   `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoInstanceUnloadTest.java`,
   `FalcoInstanceTest.java`, `FalcoChunkTest.java`.
