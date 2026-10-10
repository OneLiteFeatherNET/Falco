# Why falco-instance exists

Minestom ships an `Instance` implementation that works, and this module claims no performance
advantage over it. So why does `falco-instance` exist at all, what does it deliberately refuse to
do, and what would somebody evaluating it need to know before depending on it? This document
answers those three questions from the sources, and it names the places where the honest answer is
"we do not know" or "this is worse".

**Looking for how to use it rather than why it exists?**
[Use FalcoInstance instead of InstanceContainer](../how-to/use-falcoinstance-instead-of-instancecontainer.md) is the usage page — builder, chunk loader, the light
engine, lifecycle listeners and shared worlds, with examples that are compiled rather than typed
into a page.

Three kinds of statement appear below and are marked wherever the difference matters. A **structural**
claim is read in the source of one implementation or the other and names the file, the member and,
for Minestom, the line — it says what the code does, and it is the only kind this page has in
quantity. A **judgement** is an argument from the properties of the design that nobody measured, and
it says so. A **measured** claim names its instrument and what it counted. There are two on this
page, both of them counts rather than timings — the retained size of a chunk and the share of
sections that hold nothing — and both are taken with jol and with a deterministic census, not with a
timer. **No timing figure appears here**, and the section that explains why is
[No speed gain is claimed](#no-speed-gain-is-claimed). One figure below looks like a measurement and
is not treated as one — the sync-map factor quoted from the prior investigation, which came from a
standalone probe outside this repository and is disowned in the sentence that carries it.

The prior investigation is [Research: Instance Container](../project-record/research/instance-container.md)
and it is binding here: everything below either follows from it or corrects it against the code as
it now stands. Minestom references are to version `2026.06.20-26.1.2`, the version this repository
compiles against [1]. That version is not pinned here directly: `settings.gradle.kts` declares
`minestom` `withoutVersion()` and takes it from `net.onelitefeather:mycelium-bom:1.7.2`, whose
`dependencyManagement` pins exactly that string.

## Contents

- [The question that was asked, and the answer that came back](#the-question-that-was-asked-and-the-answer-that-came-back)
- [What the platform permits](#what-the-platform-permits)
- [The four places where a foreign instance quietly behaves differently](#the-four-places-where-a-foreign-instance-quietly-behaves-differently)
- [Why a `FalcoChunk` had to come along](#why-a-falcochunk-had-to-come-along)
- [No speed gain is claimed](#no-speed-gain-is-claimed)
- [What this module deliberately does not attempt](#what-this-module-deliberately-does-not-attempt)
- [World generation, which the first version refused and this one does not](#world-generation-which-the-first-version-refused-and-this-one-does-not)
- [When to keep using `InstanceContainer`](#when-to-keep-using-instancecontainer)
- [Sources](#sources)

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
point. The module started as one class solving one problem — a world that cleans up after itself when
it is unregistered — plus the chunk subclass that problem drags along. It has since grown a generator
path (`e45d695`) and a fix for a load-versus-unload race, both described below; it has not grown a
performance claim, and the section that says so is the one to read before depending on it.

Note that [Project Status](../project-record/project-status.md) still records this under *Investigated and deliberately not
built* ("Own `InstanceContainer`: **Not built**"). That row was written before `cee5f94` added the
module and describes the container replacement that was rejected, not the `Instance` subclass that
was built. The verdict it records — *possible but pointless as asked* — is still the reason the thing
that got built is not a container replacement.

## What the platform permits

Whether a part of Minestom can be replaced at all is decided by sealed-ness, and it has to be checked
rather than guessed. `Palette` cannot be replaced: `instance/palette/Palette.java:29` declares
`public sealed interface Palette permits PaletteImpl`, so a foreign implementation is a hard
compiler error. The same fact is visible in the binary as a `PermittedSubclasses` attribute naming
`PaletteImpl`, which is how it was first checked, with `javap -v` [1].

`Instance` and `InstanceContainer` are not sealed. Read from the sources jar of
`2026.06.20-26.1.2`, with the class-file attribute checked against the corresponding binary jar:

| Type | Declaration as written | `PermittedSubclasses` in the class file |
| --- | --- | --- |
| `net.minestom.server.instance.palette.Palette` | `public sealed interface Palette permits PaletteImpl` (`instance/palette/Palette.java:29`) | present, permits `PaletteImpl` |
| `net.minestom.server.instance.Instance` | `public abstract class Instance implements …` (`instance/Instance.java:82`) | absent |
| `net.minestom.server.instance.InstanceContainer` | `public class InstanceContainer extends Instance` (`instance/InstanceContainer.java:61`) | absent |

More than that, the platform invites it. The class comment on `Instance` reads: *"WARNING: when
making your own implementation registering the instance manually is required with
`InstanceManager#registerInstance(Instance)`, and you need to be sure to signal the `ThreadDispatcher`
of every partition/element changes."* (`instance/Instance.java:78-80`) [1] That sentence is doing two
jobs — it grants permission, and it moves the partition bookkeeping onto the implementer.
`FalcoInstance` does that bookkeeping in `cacheChunk` (`dispatcher().createPartition(chunk)`) and in
`unloadChunk` (`dispatcher().deletePartition(chunk)`).

`Instance` declares 19 abstract methods — countable in the sources jar, from
`setBlock` at `instance/Instance.java:213` to `isInVoid` at `:435` — and contributes the rest, world
border, weather, clocks, entity tracker, scheduler, event node, snapshots, for free from its 1 142
lines. `InstanceContainer` adds 776 lines on top of that. Starting from `Instance` rather than from
the container means writing those 19 methods and inheriting nothing that has to be worked around.

## The four places where a foreign instance quietly behaves differently

Exactly seven lines in Minestom `2026.06.20-26.1.2` test `instanceof InstanceContainer`, and they can
be enumerated with one grep over the sources jar. Three of them are the branches of
`SharedInstance.areLinked` (`instance/SharedInstance.java:143`, `:145`, `:154` — the last tests both
of its operands, so a grep for occurrences rather than lines returns eight) and are covered below.
The other four decide behaviour, and none of them raises anything for a foreign type — they take the
other branch and continue.

| Site | What happens to a foreign instance |
| --- | --- |
| `InstanceManager.unregisterInstance` (`instance/InstanceManager.java:121`) | Chunks stay loaded and their tick partitions stay alive |
| `Chunk` constructor (`instance/Chunk.java:74`) | Every chunk gets an empty shared-instance list |
| `ChunkBatch.updateChunk` (`instance/batch/ChunkBatch.java:235`) | `refreshLastBlockChangeTime()` is not called |
| `AbsoluteBlockBatch` (`instance/batch/AbsoluteBlockBatch.java:144`) | the same |

A fifth restriction is not an `instanceof` test at all but a parameter type, and it behaves the
opposite way: `SharedInstance` holds an `InstanceContainer` field and takes one in its constructor, so
a `FalcoInstance` cannot back a shared instance and the attempt does not compile. It is covered below
alongside `areLinked` because the two together decide the same question.

Silent divergence is worse than a compile error, because it surfaces as a bug much later and
somewhere else. That is the single sentence the whole module is built around.

### `unregisterInstance` leaks every chunk, and that is why this module exists

The body of `InstanceManager.unregisterInstance(Instance)`, after a `Check.stateCondition` that
refuses to unregister an instance with online players, is this and nothing else
(`instance/InstanceManager.java:116-129`) [1]:

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

`FalcoInstance#unregister(InstanceManager)` is the answer:

```java
public void unregister(InstanceManager instanceManager) {
    if (isRegistered()) instanceManager.unregisterInstance(this);
    for (int pass = 0; pass < UNREGISTER_PASSES; pass++) {
        for (Long index : List.copyOf(this.loadingChunks.keySet())) discardRunningLoad(index);
        for (Chunk chunk : List.copyOf(this.chunks.values())) unloadChunk(chunk);
        if (this.loadingChunks.isEmpty() && this.chunks.isEmpty()) {
            this.generationForks.clear();
            return;
        }
    }
    // …clears the forks and logs a warning naming what was left behind
}
```

It delegates *first* rather than doing its own cleanup first, so listeners of
`InstanceUnregisterEvent` still see a populated instance, in the same order they would for a
container. Then it performs the unload the manager skipped. `FalcoInstance#unloadChunk` does the
whole job: it removes the chunk from the map, sends `UnloadChunkPacket` to its viewers, fires
`InstanceChunkUnloadEvent`, removes the entities the tracker reports for that chunk, clears the
chunk's loaded flag and deletes the tick partition.

Walking the chunk map alone is not enough, which is why the loop is there. A chunk that is still
being loaded is not in that map yet, so a load finishing afterwards would publish into an instance
nothing can reach any more. Every running load is therefore claimed first — `discardRunningLoad`
takes the slot out of `loadingChunks`, and a loading thread publishes only while its own future is
still the entry for that position, so a claimed load throws its result away instead. The sweep is
bounded by `UNREGISTER_PASSES` and reports what it left behind rather than looping until the world
stops changing, because a shutdown that never returns is worse than one that names a leak. That bound
is a **judgement**: a caller that keeps requesting chunks while unregistering is out of contract, and
the module does not try to win that race.

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

`SharedInstance` holds `private final InstanceContainer instanceContainer`
(`instance/SharedInstance.java:22`) and its constructor is `SharedInstance(UUID, InstanceContainer)`
(`:24`). `InstanceManager.createSharedInstance(InstanceContainer)` has the same parameter type
(`instance/InstanceManager.java:98`) [1]. A `FalcoInstance` therefore cannot back a shared instance,
and the attempt does not compile.

That is the one branch of the four that behaves well. A compile error at the call site is exactly
what should happen when a feature is unavailable, and no work was done to route around it. The module
documents the limitation on `FalcoInstance` and in its `package-info.java` and leaves it there.
`SharedInstance.areLinked(Instance, Instance)` will return `false` for any pair involving a
`FalcoInstance`, which follows from the same typing and is the correct answer, since no
`FalcoInstance` ever shares chunks with anything.

**None of that changed when `FalcoSharedInstance` was added on 2026-08-03**, and it is worth saying
plainly, because the name invites the opposite reading. `FalcoSharedInstance extends SharedInstance`
and its constructor takes an `InstanceContainer`, exactly as Minestom's does. It is not a way to
share a `FalcoInstance`; that remains impossible, and for the same reason as before.

What it is instead is a `SharedInstance` that stops writing its caller's settings into the container
everyone else is looking at. Minestom's `SharedInstance` delegates several setters straight through,
so configuring one view reconfigures the world for every other view of it. Five of those were found
by reading the source and are answered by overrides that keep the value on the view:

| Member | What Minestom's `SharedInstance` does | What this one does |
| --- | --- | --- |
| `setGenerator` / `generator` | writes the generator into the container | keeps it on the view, `volatile` because the configuring thread is not the reading one |
| `setChunkSupplier` / `getChunkSupplier` | writes the supplier into the container | same |
| `enableAutoChunkLoad` / `hasEnabledAutoChunkLoad` | flips the container's flag for everyone | the view decides for itself |
| `saveInstance` | saves the container's tags under the view's name | saves the tags of the view |
| the constructor | accepts any container | refuses one that is registered nowhere, because such a view can never receive a chunk |

The one direction in which the view still defers to the container is chunk loading, and that is
deliberate: the chunks *are* the container's, and a view that loaded its own would no longer be a
view. `loadOptionalChunk` is overridden only so that the per-view auto-load decision is the one that
answers.

What the view does not change is who owns the blocks: `setBlock` reaches the container, which serialises
every write on its own monitor. A world that needs Falco's write path therefore uses `FalcoInstance` and
gives up sharing. The order of construction matters as well: `InstanceManager#createInstanceContainer`
registers the container, and the view's constructor refuses a container that is not registered.
`InstanceManager#registerSharedInstance` performs no such check, and an unregistered container is ticked by
nobody.

### The chunk constructor and the empty viewer list

`Chunk`'s constructor computes the shared instances whose players must also see the chunk
(`instance/Chunk.java:74-76`) [1]:

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

`ChunkBatch.updateChunk` (`instance/batch/ChunkBatch.java:235-238`) and `AbsoluteBlockBatch`
(`instance/batch/AbsoluteBlockBatch.java:144-147`) both contain the same shape, with a `FIXME` from
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

`net.minestom.server.instance.Chunk` declares the following, at three separate places in the file
rather than as one block [1]:

```java
protected volatile boolean loaded = true;   // Chunk.java:59

protected void onLoad() {}                  // :273

protected void unload() {                   // :308
    this.loaded = false;
}
```

Three properties of those declarations matter together. The hooks are `protected` with no public
equivalent, so nothing outside `net.minestom.server.instance` can call them. `loaded` starts at
`true`. And `Chunk#isLoaded()` at `:266` — which is what every `ChunkUtils.isLoaded` overload in
Minestom reads, and through them a large amount of entity, viewer and block code — returns that field
verbatim. An
instance in a foreign package therefore cannot ever mark a chunk as unloaded, and a chunk it drops
keeps telling the rest of the server it is loaded for as long as anything holds a reference to it.

A subclass in this module re-exposes the two hooks as `markLoaded()` and `markUnloaded()`. That is the
whole of `FalcoChunk` apart from one override. Reflection with `setAccessible` was rejected **on
reasoning from the platform, not on a measurement** — no reflective variant was built or timed: it
would break on the next JDK that closes the door, it needs an open module or a JVM flag from every
consumer, and it would hide the coupling instead of naming it. `FalcoChunkTest` checks the barrier
directly — `testMarkLoadedIsCallableFromOutsideTheMinestomPackage` and
`testMarkUnloadedReachesTheProtectedHook` exist to fail if the access ever changes.

The price is that the module drags a chunk implementation along, and the price is paid by the caller
too. `FalcoInstance#requireFalcoChunk` refuses any chunk that is not a `FalcoChunk`, so a foreign
`ChunkSupplier` throws `FalcoInstanceException` at the point where it was installed rather than
producing a chunk that can never be unloaded. `FalcoChunk#copy` is overridden for the same reason:
`DynamicChunk#copy` returns a plain `DynamicChunk`, and such a copy handed to a `FalcoInstance` would
be rejected — or, worse, accepted by an earlier version and then be unloadable.

### The third barrier, and why the superclass changed

There is a member with exactly the same problem that is easy to miss, and it decided which class
`FalcoChunk` extends. `Chunk` declares the block setter that carries a placement and a destruction as
`protected abstract` (`instance/Chunk.java:99-101`) [1]:

```java
protected abstract void setBlock(int x, int y, int z, Block block,
                                 @Nullable BlockHandler.Placement placement,
                                 @Nullable BlockHandler.Destroy destroy);
```

`DynamicChunk` widens exactly that method to `public` (`instance/DynamicChunk.java:75-77`) [1]. Since
an instance implementation *must*
call it — it is the only way to hand a placement or a destruction down to the block handler, and
`placeBlock` and `breakBlock` are meaningless without it — the first version extended `DynamicChunk`
rather than `Chunk`, which settled this barrier at the same time as the other two. That is why
`FalcoInstance` takes a `FalcoChunk` and not a `Chunk` where it writes: the type is what makes the
call legal.

**Since 2026-08-03 the superclass is `Chunk`, and the widening is done here.** `FalcoChunk` declares
the setter `public` itself and holds its blocks in a `BlockStorage` of its own, so nothing about the
storage is inherited any more. The barrier is unchanged — it is answered one class lower down.

The sentence that used to stand here said that `FalcoChunk` adds nothing beyond re-exposing those
members, and that Minestom's block storage was never the part that needed replacing. Both were true
of the first version and neither is true now. What changed the answer was a measurement rather than
an opinion: a `DynamicChunk` allocates all of its sections in its constructor and builds both
heightmaps in field initialisers, so an empty chunk retains 192 objects and 6 848 bytes before
anything is written into it. On a generated overworld 62.24 % of sections hold no blocks at all
(441 finished chunks around one spawn, counted with `EmptySectionCensusTest`), and every one of them
was being paid for.

`FalcoChunk` allocates a section when something writes into it and builds a heightmap when something
asks for one. Empty sections share a single flyweight. A fresh chunk retains **25 objects and
840 bytes**, which is 87.7 % less — see
[A chunk that allocates what it uses](../reference/measured-results.md#a-chunk-that-allocates-what-it-uses) for the
full table and for what a *filled* chunk does and does not save. That last part matters more than the
headline: a chunk whose sections all hold blocks saves 104 bytes and nothing else. The flyweight pays
for sections that hold nothing, and a full chunk has none.

## No speed gain is claimed

This is the part most likely to be misread, so it is stated flatly: **`falco-instance` is not faster
than `InstanceContainer`, and no measurement in this repository says it is.** What it does use is
less memory, and that is a different claim with a different instrument behind it.

The distinction is worth keeping sharp, because the two are easy to conflate:

| Claim | Instrument | Status |
| --- | --- | --- |
| A chunk retains fewer objects and fewer bytes | jol 0.17, object graph walked and counted | **Measured**, deterministic, see [A chunk that allocates what it uses](../reference/measured-results.md#a-chunk-that-allocates-what-it-uses) |
| Most sections of a generated world are empty | a census over 441 finished chunks | **Measured**, deterministic, 62.24 % |
| Anything happens *faster* | a timer | **Not measured.** `falco-benchmarks` has a JMH suite for the Anvil loader and the light engine; the instance benchmarks it has since gained have never been run as a baseline on an idle machine, so no timing figure from them is quotable and none is quoted |

Counting objects is repeatable on a loaded machine and a timing is not, which is why one appears here
and the other does not. A smaller footprint may or may not show up as throughput — that depends on
allocation pressure and on the garbage collector, and nobody here has measured it either way.

There is also a structural reason for the throughput claim being absent, and that part has not
changed. Chunk and entity ticking is not done by the instance. `ServerProcessImpl.serverTick` does
this [1]:

```java
// Tick all instances                                    // ServerProcessImpl.java:309
for (Instance instance : instance().getInstances()) {
    try { instance.tick(milliStart); } catch (Exception e) { exception().handleException(e); }
}
// Tick all chunks (and entities inside)                 // :317
dispatcher().updateAndAwait(nanoStart);                  // :318
```

`Instance#tick` handles the scheduler, world age, clocks, weather, the tick event and the world
border, and no chunks. Chunks and entities are ticked by
`ThreadDispatcher<Chunk, Entity> dispatcher`, a single field on `ServerProcessImpl` (`:72`) shared by
every instance on the server, constructed at `:100` as
`ThreadDispatcher.dispatcher(ThreadProvider.counter(), ServerFlag.DISPATCHER_THREADS)` — and
`ServerFlag.DISPATCHER_THREADS` is `intProperty("minestom.dispatcher-threads", 1)`
(`ServerFlag.java:20`), so it defaults to one thread [1]. Replacing the instance changes none of
that. A server that wants parallel ticking
raises the dispatcher thread count; that works identically with `InstanceContainer` and with
`FalcoInstance`, and neither of them can influence it.

This is the same lesson the research document drew, and it generalises: *sealed-ness decides whether
a replacement is possible, and the profile decides whether it is worth it.* Here it was possible and
not worth it for the stated reason, so the reason to build it had to be a different one.

### What does differ, stated as source reading rather than measurement

Three differences follow from comparing the two implementations line by line. None of them has been
measured, and none of them should be quoted as a factor.

**The block write path takes a narrower lock.** `InstanceContainer.UNSAFE_setBlock` is declared
`private synchronized void` at `instance/InstanceContainer.java:149`, so it holds the monitor of the
whole instance for its entire body — which includes `chunk.lockWriteLock()` at `:159`, the
placement-rule evaluation and the recursive neighbour updates [1]. Every block write anywhere in the
world therefore queues behind every other one.
`FalcoInstance#writeBlock` takes only `chunk.lockWriteLock()`, and takes it around the placement rule
and the `chunk.setBlock` call alone; the neighbour updates happen after the lock is released,
explicitly so that acquiring a second chunk's lock while holding the first cannot deadlock. Two
writes to two different chunks do not wait for each other. Whether that is worth anything on a real
workload is unknown — there is no benchmark, and it is entirely possible that a server whose ticking
is single-threaded by default never contends for that monitor at all.

**The `currentlyChangingBlocks` map of the container is guarded inconsistently.** It is a plain
`java.util.HashMap`, declared at `instance/InstanceContainer.java:81`. It is read through
`isAlreadyChanged` (`:164`, defined at `:718`) and written (`:169`) inside `UNSAFE_setBlock`, under
the instance monitor. It is cleared in `tick(long)` at `:706` under a *different* lock, the
`changingBlockLock` `ReentrantLock` declared at `:80`, which nothing else in the class takes [1]. The
two accesses are therefore not mutually exclusive:
a tick clearing the map can run concurrently with a block write mutating it, on a non-thread-safe
map. `FalcoInstance` uses a `ConcurrentHashMap` and no second lock, which removes the case rather
than narrowing it. This is a defect in Minestom read from the source; it has not been reproduced with
a probe here, and no claim is made about how often it would bite.

**The chunk map is a different structure, with no number attached.** `InstanceContainer` holds its
chunks in `Long2ObjectSyncMap<Chunk>` (`instance/InstanceContainer.java:77`) from
`space.vectrix.flare:flare-fastutil:2.0.1`, which is a port of Go's `sync.Map` — its own Javadoc
cites `https://golang.org/src/sync/map.go` at `Long2ObjectSyncMap.java:64` [2]. It splits into a
lock-free `read` map and a `dirty` map behind an implicit lock; after a promotion has emptied the
dirty map, the next write calls `dirtyLocked()` (`Long2ObjectSyncMapImpl.java:487-495`), which
allocates a fresh backing map and copies every non-expunged entry of the read map into it [2]. That
one write is O(number of loaded chunks) — not every write, only the first after a promotion — and
chunk loading and unloading are writes. `FalcoInstance` uses a plain `ConcurrentHashMap<Long, Chunk>`
instead, which boxes the key and gives that up in exchange. How often the rebuild is actually hit
under chunk streaming is not established here; that is a claim about the access pattern, and the
access pattern was not measured.

The prior investigation reports the sync map as "up to 19.8× slower in the measured access pattern".
**That figure is not reproducible from this repository** — it came from a standalone probe outside
it, no instance timing has ever been produced here, and this document does not rely on it. What can
be said from the source alone is that the two structures make opposite trade-offs, and that nobody
has measured which one wins for chunk streaming. If that ever matters, it needs a benchmark run, not
an argument — the benchmark classes exist since 2026-08-03, the run does not.

## Owning the loader and saving on shutdown

`registerAndShutdownWith` is the form worth preferring when the instance owns its loader. `ownsLoader(true)`
means the instance closes the loader on shutdown, `saveOnShutdown(true)` means it writes its chunks first,
and the shutdown task is registered for you. The three together are the part that is easy to get wrong by
hand, because a loader closed before the save loses the save.

## The light engine on the instance

A `ChunkSupplier` produced by `ChunkLightScheduler#supplier()` makes `FalcoLightingChunk`s, which extend
`FalcoChunk`. Each one reports its block changes, loads and ticks to the scheduler, and the scheduler lights
the touched regions and sends them. The application writes no listener of its own.

`falco-light` declares `falco-instance` as `compileOnly`, so adding the light module does not bring this one
with it. An application declares both, or uses the BOM; see
[How-to Add Falco to your build](../how-to/add-falco-to-your-build.md).

## Events and the chunk lifecycle listener

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
notification — which is what the light engine uses it for. Take the events for anything else. The methods
of the listener are listed in [Reference API surface and stability](../reference/api-surface-and-stability.md#chunk-lifecycle-listener).

## What this module deliberately does not attempt

Each of these is a refusal rather than a gap that nobody got round to, and each throws or fails to
compile rather than degrading quietly.

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

## World generation, which the first version refused and this one does not

An earlier revision of this page recorded world generation under the refusals: `generator()` returned
`null`, `setGenerator` threw for anything but `null`, and `generateChunk` always threw. That was
accurate for `cee5f94` and it is no longer accurate. Commit `e45d695` implemented the path, and the
row belongs in this section rather than in the one above.

`FalcoInstance` keeps a `volatile @Nullable Generator generator` field, `setGenerator` stores it and
`generator()` returns it. `createChunk` applies it to a chunk no `ChunkLoader` knew about, and only
when `chunk.shouldGenerate()`; without a generator such a chunk stays empty, which is a world of air
rather than a failure. `generateChunk(int, int, Generator)` loads the chunk first and applies the
generator on top of what is already there, off the calling thread.

The one difference from `InstanceContainer` worth stating is in failure handling, and it is
**structural** rather than measured. `FalcoInstance#applyGenerator` clones every section's block and
biome palette into a `GeneratorImpl.GenSection[]`, hands the generator those clones, and copies them
back over the live palettes under the chunk's write lock only after `generate` has returned. A
generator that throws therefore publishes nothing: the chunk is byte-for-byte what it was and the
caller's future fails with the generator's own exception. `InstanceContainer.generateChunk(Chunk,
Generator)` at `instance/InstanceContainer.java:411` builds its `GenSection[]` from
`section.blockPalette()` and `section.biomePalette()` directly rather than from clones (`:413-417`),
so the generator writes into the live palettes, and it catches `Throwable` into the server's
exception manager (`:458-460`). A generator that fails halfway therefore leaves a chunk that is half
built, published, reported as loaded, and a caller of `loadChunk` that is told nothing. Holding the
write lock only for the commit is a consequence of Falco's staging and not the reason for it —
neither implementation holds the chunk's write lock across the generator itself; Minestom takes it
only for `applyGenerationData` (`:494`) and `applyFork` (`:478`). No measurement is claimed for
either property.

## When to keep using `InstanceContainer`

Use Minestom's container if it backs a `SharedInstance`, or if block batches are part of how the
world is built. Those are the two things this module cannot do, and one of them will tell you at
compile time.

Consider `FalcoInstance` if instances are created and destroyed at runtime — a lobby rotation, a
per-match arena, anything where `unregisterInstance` is called more than once over the life of the
process — and if you are willing to call `FalcoInstance#unregister` instead of the manager's method.
The leak that fixes is the entire argument for the module. Everything else in it is either the
machinery that fix requires or a smaller, more legible version of code that already existed.

The module is marked `@ApiStatus.Experimental` throughout and its API may still change in a minor
release. It carries 36 tests across five classes — `FalcoInstanceTest` (13), `FalcoInstanceGeneratorTest`
(10), `FalcoInstanceUnloadTest` (6), `FalcoChunkTest` (4) and `FalcoInstanceLoadRaceTest` (3) — all of
which run against a real server process through Cyano's `MicrotusExtension` rather than against a
mock, because the property being tested is that Minestom accepts the type as an instance, and a mock
proves nothing about that.

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
   `net/minestom/server/instance/generator/GeneratorImpl.java` (`GenSection`, `UnitImpl`, `chunk`),
   `net/minestom/server/instance/palette/Palette.java` (the `sealed … permits` declaration, and the
   `PermittedSubclasses` attribute via `javap -v`). How that version is resolved rather than pinned
   is stated in the opening section.
2. **flare-fastutil**, `space.vectrix.flare:flare-fastutil:2.0.1`, read from the published sources
   jar: `space/vectrix/flare/fastutil/Long2ObjectSyncMap.java` (interface Javadoc) and
   `Long2ObjectSyncMapImpl.java` (`dirtyLocked`, `missLocked`, `promoteLocked`). The implementation
   is a port of Go's `sync.Map`, which its own Javadoc cites as
   https://golang.org/src/sync/map.go.
3. **Research: a multithreaded `InstanceContainer` replacement**,
   [Research: Instance Container](../project-record/research/instance-container.md) in this repository —
   the prior investigation, including the `ForeignInstance` probe transcript and the sync-map figure
   that is explicitly not relied on here.
4. **Status — Anvil chunk loader and light engine**, [Project Status](../project-record/project-status.md) in this
   repository — the working record, including the *What can and cannot be replaced* section and the
   *Investigated and deliberately not built* verdict on `InstanceContainer`.
5. **`falco-instance` sources and tests**, under
   https://github.com/OneLiteFeatherNET/Falco/tree/main/falco-instance:
   `src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java` (`unregister`,
   `discardRunningLoad`, `retrieveChunk`, `completeLoad`, `createChunk`, `applyGenerator`,
   `writeBlock`, `requireFalcoChunk`, `UNREGISTER_PASSES`), `FalcoChunk.java` (`markLoaded`,
   `markUnloaded`, `copy`), `FalcoInstanceException.java`, `package-info.java`, and the five test
   classes under `src/test/java/net/onelitefeather/falco/instance/`: `FalcoInstanceTest`,
   `FalcoInstanceGeneratorTest`, `FalcoInstanceUnloadTest`, `FalcoChunkTest` and
   `FalcoInstanceLoadRaceTest`.
