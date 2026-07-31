# Research: shared instances and batch integration for a foreign instance

**Question.** `FalcoInstance extends Instance` is said to be unable to back a `SharedInstance`, and
Minestom's block batches are said never to reach it. Both were recorded as "not achievable from the
outside". Are they, and does either one matter?

**Verdict: split.** Shared instances really are walled off by the compiler — but the *capability* is
reachable with a Falco-owned mechanism, and cheaply. Batch integration is a non-issue: the batches
already work against a foreign instance, and the one call they skip writes a field that nothing in
Minestom can ever read for such an instance. The batch gap that does exist is a different one, and it
sits on the chunk, not on the instance.

## How this was checked

Every claim below comes from the sources of Minestom `2026.06.20-26.1.2` (sources jar from the Gradle
cache, 1454 files) or from probe code compiled and run against the binary jar of the same version.
The probe lives outside the repository and is not checked in. Where something is inference rather
than an observation, it says so.

The four coupling sites named in [instance-container.md](instance-container.md) were not taken on
trust. A full sweep for `InstanceContainer` and `SharedInstance` across all 1454 sources produced the
list below. It is longer than four, and one entry is on a type nobody had looked at.

| Site | Kind | Effect on `FalcoInstance` |
| --- | --- | --- |
| `SharedInstance` field `instanceContainer` and its constructor | typed | compile error |
| `SharedInstance.getInstanceContainer()` | return type | compile error at the call site |
| `SharedInstance.areLinked` (three `instanceof` branches plus the `copy()` branch) | `instanceof` | always false |
| `InstanceManager.createSharedInstance(InstanceContainer)` | parameter | compile error |
| `InstanceManager.registerSharedInstance(SharedInstance)` | parameter | compile error |
| `InstanceManager.unregisterInstance` | `instanceof` | chunks are not unloaded — the known leak |
| `Chunk` constructor | `instanceof` | shared-viewer list is permanently `List.of()` |
| `ChunkBatch.updateChunk` | `instanceof` | `refreshLastBlockChangeTime()` skipped |
| `AbsoluteBlockBatch.apply` | `instanceof` | `refreshLastBlockChangeTime()` skipped |
| `Player.setInstance` via `SharedInstance.areLinked` | static call | the "same chunks" fast path never taken |
| `EntityTracker.viewable(List<SharedInstance>, int, int)` | parameter | only an empty list can be passed |
| `AbsoluteBlockBatch.apply`, `chunk instanceof LightingChunk` | `instanceof` | **light is not resent after a batch** |

The last row is new. It is not about the instance at all — it is about `FalcoChunk`, which extends
`DynamicChunk`, not `LightingChunk`.

There is also a hard wall that had not been recorded: **`EntityTracker` is sealed.**

```java
public sealed interface EntityTracker permits EntityTrackerImpl
```

Confirmed with `javap -v`: `PermittedSubclasses: net/minestom/server/instance/EntityTrackerImpl`.
`Instance` holds one in a `private final` field and hands it out through `getEntityTracker()`. So the
route "supply an own `EntityTracker` whose `viewable(...)` ignores the empty list" — the obvious way
to repair the `Chunk` constructor from the outside — is closed the same way `Palette` was.

## 1. Shared instances

### The assessment is correct, and it is a compile error

Not a runtime problem, not a silent divergence: the compiler refuses. Probe code with a foreign
`ProbeInstance extends Instance` produced exactly three errors and no more:

```
error: incompatible types: ProbeInstance cannot be converted to InstanceContainer
        MinecraftServer.getInstanceManager().createSharedInstance(foreign);
error: incompatible types: ProbeInstance cannot be converted to InstanceContainer
        new SharedInstance(UUID.randomUUID(), foreign);
error: incompatible types: ProbeInstance cannot be converted to InstanceContainer
            super(UUID.randomUUID(), foreign);
```

The third line matters: subclassing `SharedInstance` does not help either, because its only
constructor demands an `InstanceContainer` for the `super` call. `SharedInstance` is not final and
not sealed, and that buys nothing.

`InstanceContainer.addSharedInstance` is `protected` on top of that, so even a container that already
existed could not be told about a foreign view from another package.

### But the capability is reachable, and the pieces were verified

What `SharedInstance` actually provides is three things: the same chunk objects, a separate entity
list, and chunk viewers that include the players of the linked views. All three can be built in
`net.onelitefeather.falco.instance` without touching Minestom.

**Same chunks and separate entities — proven.** A `ProbeSharedView extends Instance` that delegates
every chunk and block method to a `ProbeInstance` registers and runs:

```
[1] registerInstance(foreign) ok, isRegistered=true
[2] registerInstance(sharedView) ok, isRegistered=true
[3] same chunk object through both instances = true (ProbeChunk)
[4] separate entity trackers = true
```

`InstanceManager.registerInstance` guards with `Check.stateCondition(instance instanceof
SharedInstance, ...)`, and a Falco view is not a `SharedInstance`, so the ordinary registration path
is the correct one and is not blocked. The separate entity list is free: `Instance` gives every
subclass its own `EntityTracker`.

**Viewers — the interesting part.** The `Chunk` constructor computes its viewer set once, in a
`private final Viewable viewable` field, and a foreign instance always lands in the `List.of()`
branch. That field cannot be replaced. What *can* be replaced is the three methods that read it:
`Chunk.addViewer`, `Chunk.removeViewer` and `Chunk.getViewers` are plain public methods, verified
non-final with `javap`. And every path that notifies a chunk's viewers goes through `getViewers()` —
`Chunk.sendChunk()` calls it directly, and `Viewable.sendPacketToViewers` (which
`InstanceContainer.UNSAFE_setBlock`, `LightingChunk` and the unload packet all use) calls it too.
The batch probe confirms the override is on that path in practice:

```
[9] chunk.getViewers() queried by the batch = true
```

So `FalcoChunk` can compute the union itself. Minestom's own `EntityTrackerImpl.ChunkView.references()`
shows the recipe: collect players from the owning tracker, then from each linked view's tracker, both
via `nearbyEntitiesByChunkRange(chunkPosition, ServerFlag.CHUNK_VIEW_DISTANCE, Target.PLAYERS, ...)`.
Every method in that recipe is public API. A probe registered an entity in the shared view and asked
both trackers from the chunk of the owning instance:

```
[A] entity in the shared view, seen by the owning instance tracker  = 0
[B] entity in the shared view, seen by the shared view tracker      = 1
[C] the chunk can therefore build a union of both = 1
```

The probe used `Target.ENTITIES` because creating a real `Player` needs a network connection; with
`Target.PLAYERS` the same call is what Minestom itself runs. That last step is reasoned from the
source, not measured.

### What such a view would still not do

- **`SharedInstance.areLinked` stays false.** Measured: `areLinked(container, stockShared) = true`,
  `areLinked(foreign, falcoSharedView) = false`. Its only caller is `Player.setInstance`, where it
  skips re-sending chunks when a player moves between two instances that already share them. Losing
  it costs one redundant chunk resend per transfer. It is a slowdown, not a defect. Reaching it would
  need a custom `Player` subclass overriding `setInstance` — possible, disproportionate.
- **Third-party code that tests `instanceof SharedInstance` will not recognise it.** Nothing in
  Minestom does outside `InstanceManager` and `areLinked`, but plugins might.
- **It is not `SharedInstance`.** Any API that demands that type stays closed. This is a Falco
  feature with the same benefit, not a compatible implementation.

### Cost and recommendation

One new class (a delegating `Instance`, roughly the shape of Minestom's `SharedInstance` — about 20
one-line overrides), a `CopyOnWriteArrayList` of views on `FalcoInstance`, and a `getViewers()`
override of about fifteen lines in `FalcoChunk`. No new dependency, no new hook.

**Recommended if shared worlds are actually wanted, otherwise not.** The cost is small and the
mechanism is honest, but nothing in Falco needs it today, and every line of it is a line that a
Minestom upgrade can break. Build it when a caller asks for it, and when it is built, cover the
viewer union with a test, because that is the part that silently degrades to "the other players see
nothing" if a future `Chunk` stops routing through `getViewers()`.

## 2. Batch integration

### The assessment is literally true and practically irrelevant

`ChunkBatch.updateChunk` and `AbsoluteBlockBatch.apply` do guard the call, and Minestom flags it
itself at both sites:

```java
if (instance instanceof InstanceContainer) {
    // FIXME: put method in Instance instead
    ((InstanceContainer) instance).refreshLastBlockChangeTime();
}
```

That is the whole of the coupling. Everything else a batch does against an instance uses only
`Instance` and `Chunk` API: `getChunk`, `lockWriteLock`, `Chunk.setBlock`, `chunk.sendChunk()`,
`scheduleNextTick`. Measured against the probe instance:

```
[7]  ChunkBatch on foreign instance -> block = minecraft:diamond_block
[8]  lastBlockChangeTime refreshed by the batch = false
[9]  chunk.getViewers() queried by the batch = true
[10] control: container lastBlockChangeTime refreshed = true
[11] AbsoluteBlockBatch on foreign instance -> minecraft:emerald_block / minecraft:emerald_block
```

Blocks land, viewers are refreshed, the callback fires. Only the timestamp is skipped. The control
line shows the same batch against a real `InstanceContainer` does refresh it, so the difference is
the `instanceof` and nothing else.

### The timestamp has exactly one reader, and it is out of reach anyway

A sweep for `lastBlockChangeTime` over all 1454 sources finds it written in the `InstanceContainer`
constructor, in `UNSAFE_setBlock`, at the end of `generateChunk`, in `copy()` and in
`refreshLastBlockChangeTime()` — and read in exactly one place: `SharedInstance.areLinked`, inside
the branch

```java
if (instance1 instanceof InstanceContainer container1 && instance2 instanceof InstanceContainer container2) {
    if (container1.getSrcInstance() != null) { ... container1.getLastBlockChangeTime() == container2.getLastBlockChangeTime(); }
```

That branch requires both arguments to be `InstanceContainer` and one of them to have come from
`InstanceContainer.copy()`. A `FalcoInstance` fails the first condition. So even if the batches did
call `refreshLastBlockChangeTime()` on it, no code in Minestom would ever read the result.

**Conclusion: this is not a missing capability, it is a field with no consumer.** `FalcoInstance`
maintains `lastBlockChangeTime` and exposes `getLastBlockChangeTime()` / `refreshLastBlockChangeTime()`;
that is dead weight unless Falco grows its own reader. Building a Falco-owned batch type to close it
would be work spent producing a number nobody looks at.

Recommendation: either drop the timestamp from the public surface of `FalcoInstance`, or keep it and
document that it is instance-local and deliberately not fed by Minestom's batches. Do not build a
batch type for it.

### The batch gap that is real

`AbsoluteBlockBatch.apply` ends with

```java
for (Chunk chunk : expanded) {
    if (chunk instanceof LightingChunk dc) {
        dc.sendLighting();
    }
}
```

`FalcoChunk extends DynamicChunk`, so after an `AbsoluteBlockBatch` the surrounding chunks never
resend their light. This is a chunk-type coupling and has nothing to do with the instance. Two ways
out, both inside Falco: make `FalcoChunk` extend `LightingChunk`, or have a Falco batch type resend
light itself. This is an assessment from reading the source — it was not measured, because the branch
only runs after a tick and the probe does not tick the instance.

#### How it was actually closed: neither of the two ways above

Both options this section named assume the batch has to be the one that resends. It does not, and the
gap was closed from the chunk instead, by `FalcoLightingChunk` and `LightUpdateAware` in
`falco-light`.

A batch writes its blocks through `Chunk#setBlock`. A `FalcoLightingChunk` marks itself and its eight
neighbours on every such write, so the light pass of the following tick covers the whole touched
region and delivers it through `onLightUpdated()`, which sends an `UpdateLightPacket` to the viewers
of the chunk. The `instanceof LightingChunk` branch at the end of `apply` is simply never needed:
what it would have triggered happens one tick later on a path that does not ask what type the chunk
is.

Two differences from what Minestom's branch would have done, one in each direction:

- **The light arrives one tick later.** `sendLighting()` inside `apply` is immediate; a scheduled
  pass is not. For a batch that is what a batch is for, this is the correct trade — the alternative
  is computing light on the thread that applied the blocks.
- **It arrives for the ring around the batch as well.** Minestom's loop walks the chunks the batch
  touched and no others, so a lamp placed at the edge of the outermost touched chunk does not reach
  the chunk beyond it. Marking the 3×3 per changed chunk covers that.

This does not change the analysis above. `AbsoluteBlockBatch.apply` still tests for `LightingChunk`
and still skips every other type, so the row in the table stays true and a `FalcoChunk` — which is a
plain `DynamicChunk` and knows nothing about light — is still not resent by a batch. What changed is
that Falco no longer needs that branch to work. Point 7 of *What Minestom would have to change* asked
for exactly this hook upstream, `protected void onLightingChanged()` on `Chunk`; `LightUpdateAware` is
the same idea built where it could be built, outside the server.

### An incidental finding, worth keeping

While the batch probe was failing, the cause turned out to be `LightingChunk`, not the batch:

```java
protected void onLoad() { doneInit = true; }
public boolean isLoaded() { return super.isLoaded() && doneInit; }
```

A freshly constructed `LightingChunk` reports `isLoaded() == false` until `onLoad()` runs, and
`onLoad()` is `protected`. Measured: `fresh DynamicChunk.isLoaded = true`, `fresh
LightingChunk.isLoaded = false`. Both batches begin with `if (!chunk.isLoaded()) { LOGGER.warn(...);
return; }` — so against an instance that never calls `onLoad()`, a batch silently does nothing and
logs a warning about an "unloaded chunk". Calling the hook fixed the probe. This is precisely what
`FalcoChunk.markLoaded()` exists for, and it is a good argument for keeping it.

## Rejected approaches

- **Reflection / `setAccessible`.** Would open `SharedInstance.instanceContainer` and
  `InstanceContainer.addSharedInstance`, but the `instanceof InstanceContainer` checks in `Chunk`,
  `InstanceManager` and the batches are type tests, not access checks — reflection cannot make them
  pass. It would buy a half-working `SharedInstance` and a module-opens requirement, and it breaks
  silently on every Minestom upgrade.
- **Faking the type** (a class in `net.minestom.server.instance`, a proxy, an agent). The
  `instanceof` checks would pass, but the code behind them reads private container fields, so the
  result is either a `NullPointerException` or the container's behaviour rather than Falco's. It also
  puts Falco classes into Minestom's package, which is where the `protected` barrier stops being
  visible and the next upgrade stops being reviewable.
- **Forking Minestom.** Out of scope by definition, and it turns every upgrade into a merge.

The Falco-owned mechanism in section 1 is preferred over all of these for one reason: it is made of
public API only, so an upgrade that breaks it breaks it at compile time.

## What Minestom would have to change

Both problems have the same root: `InstanceContainer` carries two pieces of state that belong to
`Instance`. A single change fixes the shared-instance wall, the batch `FIXME` and the chunk
constructor at once.

**Proposed change — move the shared-instance registry and the block-change timestamp up to `Instance`.**

1. `Instance` gains
   - `private final List<SharedInstance> sharedInstances = new CopyOnWriteArrayList<>();`
   - `public List<SharedInstance> getSharedInstances()`, `public boolean hasSharedInstances()`,
     and a package-private/`protected` `addSharedInstance(SharedInstance)`;
   - `private volatile long lastBlockChangeTime;` with `public long getLastBlockChangeTime()` and
     `public void refreshLastBlockChangeTime()`.

   `InstanceContainer` loses the same members and inherits them. Source-compatible for callers, since
   the members keep their names and are inherited.

2. `SharedInstance`: field and constructor parameter retyped `InstanceContainer` → `Instance`. Add
   `public Instance getSourceInstance()` and deprecate `getInstanceContainer()`. Widening the
   *return* type of the existing method would break callers that assign to `InstanceContainer`, which
   is why it should be a new method rather than a retyped one.

3. `InstanceManager.createSharedInstance(InstanceContainer)` → `createSharedInstance(Instance)`.
   Source-compatible for every caller; binary-incompatible, so it needs a recompile — worth naming in
   the changelog. `registerSharedInstance` needs no signature change once
   `getSourceInstance()` returns `Instance`.

4. `Chunk` constructor: replace

   ```java
   final List<SharedInstance> shared = instance instanceof InstanceContainer instanceContainer ?
           instanceContainer.getSharedInstances() : List.of();
   ```

   with `final List<SharedInstance> shared = instance.getSharedInstances();`.

5. `ChunkBatch.updateChunk` and `AbsoluteBlockBatch.apply`: replace the guarded cast with
   `instance.refreshLastBlockChangeTime();`. This is what the `// FIXME: put method in Instance
   instead` at both sites already asks for.

6. `SharedInstance.areLinked`: branch on `SharedInstance` and on `Instance` rather than on
   `InstanceContainer`. The `copy()` branch needs a `getSrcInstance()` on `Instance` (default `null`)
   to move with the timestamp; without it, keep that branch on `InstanceContainer` and only widen the
   first three.

7. Independently: `InstanceManager.unregisterInstance` should unload chunks for every `Instance`, not
   only for an `InstanceContainer` (already argued in [instance-container.md](instance-container.md)),
   and `AbsoluteBlockBatch`'s `chunk instanceof LightingChunk` would be better as a no-op hook on
   `Chunk` — say `protected void onLightingChanged()` — so a custom chunk can react.

**Impact on existing users.** Points 1, 4, 5 and 7 are invisible. Point 3 requires a recompile.
Point 2 is the only one with a deprecation. Nobody loses behaviour: an `InstanceContainer` keeps
doing exactly what it does now, because it is an `Instance` and the state simply moved one level up.

Points 1–5 are a self-contained pull request; 6 and 7 can be separate issues.
