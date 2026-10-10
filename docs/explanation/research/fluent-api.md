# Research: a fluent construction API for the three modules

**Question.** Should `falco-anvil`, `falco-light` and `falco-instance` be given a fluent construction
surface — builders, terminal methods, lifecycle and observer slots — and if so, in what shape? This
page is the investigation that preceded that decision, and it is kept as what it was rather than
rewritten into documentation of the result.

**Read it as a record, not as a reference.** Sections 1 to 7 describe the state before any of this
was built, and they still say "would" and "should" throughout. What was built from them is
[#16](https://github.com/OneLiteFeatherNET/Falco/pull/16), and it departs from the page in three
places — question 13, the mutability of the anvil builder, and step 6 of the sequence — each marked
in a quote box where it happens. Where the page and the code disagree, the code wins. For what can
actually be called, see [Add Falco to your build](../../how-to/add-falco-to-your-build.md),
[How the Anvil loader is built](../how-the-anvil-loader-is-built.md) and [How the light engine works](../how-the-light-engine-works.md).

**Revision 2.** The page argues throughout against a revision 1 that is not published here, and marks
every place where revision 1 was wrong. All path:line references are relative to the repository root
and were checked against `origin/main` at commit `26ac23e`. Lines move; when one does not match,
the surrounding identifier is the reliable part of the reference.

> ### Evidence status
>
> **Verified against the code.** The analysis of the current state (section 1), the observation table
> (section 5.1), every field, line and constructor reference for the modules `falco-anvil`,
> `falco-light`, `falco-instance` and `falco-demo`, the call counts (`FalcoAnvilLoader` 14,
> `ChunkLightScheduler` 27, `FalcoInstance` 7 constructor calls) and the build sites. In the code
> examples of sections 3 and 4 every **existing** method that is called was looked up in the field
> inventory or in the Minestom sources; newly proposed elements are marked as such at every point.
>
> **Corrected relative to revision 1.** `toBuilder()`/`copy()` (not implementable),
> `install(Instance)` together with `FalcoLight` (calls a method that does not exist and solves only
> half of the reported defect), `kinds(LightKind...)` (no default, permits an unusable
> configuration), variant (C) in section 4.2 (`createLightData()` has the wrong signature),
> `FalcoWorld` (contradicted itself on the question of saving), `FalcoChunkSupplier`, `falco-api` as
> a fourth module (not justifiable), the precedence claim in section 1.2(d) and the justification for
> the `eventNode()` null check (non sequitur, see section 5.2).
>
> **Left open, not claimed.** Whether JSpecify is managed in `mycelium-bom` (section 6, question 4).
> Whether an IDE reads `@Contract` from a jar without `org.jetbrains.annotations` being on the
> consumer's class path (section 6, question 4). Whether `AnvilDiagnostics` without `final` becomes
> measurably more expensive on the hot path (section 6, question 11).
>
> **Minestom line basis, to be read with care.** Through `mycelium-bom 1.7.2`
> (`settings.gradle.kts:32`, `:47`) the project actually resolves **`2026.06.20-26.1.2`** —
> re-checked through `:falco-instance:dependencies --configuration compileClasspath` and the BOM POM.
> Some of the Minestom line references in this document, however, come from the later
> `2026.07.22-26.2`; the line numbers mix both states (`Block#stateId()` appears once as `:247`, once
> as `:256`). **All types and method signatures named exist identically in both versions** — only the
> line numbers were not consistently taken against the resolved state. One value was
> version-dependent and has been corrected: `MinecraftConstants.DATA_VERSION` in the resolved
> `26.1.2` is **4790** (`MinecraftConstants.java:15`), not 4903. Anyone who needs a Minestom line
> reference as evidence should check it against `26.1.2`.
>
> ### The repository has moved on since this page was written
>
> Every reference here is against `origin/main` at `26ac23e`. Four commits landed after it, and two
> of them touch what this page argues from:
>
> - **`e45dc1e` — `fix(instance): publish the chunk supplier and loader safely` (#11).** The
>   non-volatile `chunkSupplier`/`chunkLoader` fields are now `volatile`. Section 1.2(c) marks the
>   consequences inline; section 6 question 3 is settled. The same commit replaced
>   `FalcoAnvilLoader.close()`'s `public synchronized` with a private `ReentrantLock` and added the
>   missing `@ApiStatus.Experimental` to `RegionFile.RawChunk` — both are premises of section 3.1
>   that should be re-read before acting on it.
> - **`cb30cf0` — `feat(archunit): enforce the architecture rules the compiler cannot see` (#12).**
>   A seventh module now enforces 39 rules, and `PublicApiTest` binds every proposal on this page:
>   `@ApiStatus.Experimental` on **every** public type, `@NotNullByDefault` on the package, no
>   unreachable types in public signatures, and public classes `final` unless forced open; see
>   [Architecture Rules](../../contributing/architecture-rules.md). Sections 1 to 6 were written before the module existed
>   and none of them mentions it. **Section 7 closes that gap** and checks every sketch against the
>   rules as they stand at `4c86c96`: one sketch fails, two are confirmed, the rest cost annotations.
>
> - **`1f72962` — `fix(light): forget chunks that left the instance` (#14).** Written from this page.
>   It closes the `dirty` defect of section 3.2.1 and makes `defaultExecutor()` public, which is
>   measure 1 of section 2.9.3. Both sections carry the correction inline. Of the plan in 3.2.1, the
>   proposed `ChunkLightArea.remove(ChunkArea)` proved unnecessary.
>
> Line numbers in `FalcoInstance.java` and `ChunkLightScheduler.java` have shifted. What has **not**
> changed: the blocker in section 1.2(f), re-verified against `1f72962`.

## Contents

- [1. The current situation](#1-the-current-situation)
- [2. Design principles](#2-design-principles)
- [3. API sketch per module](#3-api-sketch-per-module)
- [4. The connecting facade](#4-the-connecting-facade)
- [5. Observer/hooks](#5-observerhooks)
- [6. Open decisions](#6-open-decisions)
- [7. The sketches against `falco-archunit`](#7-the-sketches-against-falco-archunit)
- [8. Sources](#8-sources)

---

## 1. The current situation

### 1.1 What a user has to write today

The official quick start (`README.md:81-118`) is the densest description of the current state:

```java
InstanceContainer instance = MinecraftServer.getInstanceManager()
        .createInstanceContainer(DimensionType.OVERWORLD);

FalcoAnvilLoader loader = new FalcoAnvilLoader(Path.of("worlds", "lobby"), DimensionType.OVERWORLD.key());
instance.setChunkLoader(loader);
instance.enableAutoChunkLoad(true);

ChunkLightService lighting = new ChunkLightService();
MinecraftServer.getGlobalEventHandler().addListener(InstanceChunkLoadEvent.class, event ->
        lighting.calculateWithNeighbours(event.getInstance(), event.getChunkX(), event.getChunkZ()));

MinecraftServer.getSchedulerManager().buildShutdownTask(() -> {
    instance.saveChunksToStorage().join();
    try {
        loader.close();
    } catch (IOException exception) {
        throw new UncheckedIOException(exception);
    }
});
```

Seven mutually independent statements whose order is nowhere enforced, plus a shutdown task whose
omission is only noticed at the next server start, as a corrupted region file. The demo does the
same, only spread out: the loader is created in
`falco-demo/src/main/java/net/onelitefeather/falco/demo/DemoServer.java:148`, the shutdown task in
`:153`, the Instance in `:155-156`, the ChunkSupplier in `:157` — and the comment `:150-152`
explains why `try-with-resources` does not work here. That is not negligence but the correct answer
to an API that knows no object which owns the loader.

### 1.2 Where it hurts concretely

**(a) Configuration exists in the code, but not in the API.** `FalcoAnvilLoader` has exactly two
constructors (`falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java:126`
and `:144`); the only adjustable value is `openRegionLimit`. Five further adjusting screws are set
hard in the constructor and are unreachable from outside:

```java
this.compressionLevel = ChunkCompression.DEFAULT_LEVEL;                          // :151
this.diagnostics = new AnvilDiagnostics();                                       // :155
this.blockResolver = new BlockPaletteResolver(this.diagnostics);                 // :156
this.biomeResolver = new BiomePaletteResolver(this.diagnostics);                 // :157
this.saveLimit = new Semaphore(Math.max(Runtime.getRuntime().availableProcessors(), 2)); // :160
this.dataVersion = MinecraftServer.DATA_VERSION;                                 // :161
```

Particularly bitter is `PaletteEntryResolver`
(`falco-anvil/src/main/java/net/onelitefeather/falco/anvil/PaletteEntryResolver.java:27`): a
published, documented, `@ApiStatus.Experimental`-marked interface type that `SectionCodec` accepts
on all four methods — but which no consumer can get into the loader. Likewise
`ChunkCompression.compress(byte[], int)` (`ChunkCompression.java:163`), whose javadoc `:72-76` says
explicitly that a caller who writes a world once and reads it often should pass a higher level —
and it is exactly that caller who cannot.

**(b) Telescoping constructors with interchangeable parameters.** `ChunkLightScheduler` has three
constructors, declared in non-monotonic order:

```java
public ChunkLightScheduler(ChunkLightService service)                                                  // :121
public ChunkLightScheduler(ChunkLightService service, Executor executor, int maxAreaSize, int maxCachedChunks) // :135
public ChunkLightScheduler(ChunkLightService service, Executor executor, int maxAreaSize)              // :152
```

`maxAreaSize` and `maxCachedChunks` are both `int` and stand next to each other; swapped, it compiles
and runs with a silent misconfiguration. Anyone who wants to change only the cache is forced to
supply an executor — and, when this was written, could not name the default one, because
`defaultExecutor()` was `private static`. The tests show the consequence: they repeat `16`, that is
`DEFAULT_MAX_AREA_SIZE`, merely to reach the executor parameter. **That half is fixed:** `1f72962`
made the method public, so the default can be named. The swappable `int` pair remains.

**(c) Scattered setters instead of one configuration phase.** `FalcoInstance` has three constructors
(`falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java:225`, `:237`,
`:255`) plus four setters (`:803` `setChunkSupplier`, `:831` `setChunkLoader`, `:856` `setGenerator`,
`:1059` `enableAutoChunkLoad`), and the registration happens outside the class. Two details make this
dangerous rather than merely unlovely: `chunkSupplier` (`:211`) and `chunkLoader` (`:213`) are
**not** volatile, yet they are read in `retrieveChunk` and passed on to a virtual thread
(`:564-571`) — `setChunkLoader` while loads are in flight is a data race without happens-before. And
`setChunkLoader` does not call `loadInstance` again (documented at `:824-827`), so there are two
semantically different ways to set the same value.

> **Fixed after this page was written.** The data race is gone. `e45dc1e` (*fix(instance): publish the
> chunk supplier and loader safely*, #11) made both fields `volatile` — they are now at `:222` and
> `:233`, with the reasoning in the javadoc at `:217-221`. Wherever this page argues from the
> non-volatile fields — here, in P1, in section 2.9.4 and in *What the builder cannot fix*
> (section 3.3) — that particular argument no longer applies, and section 6 question 3 is settled.
> The second half of the point stands: `setChunkLoader` still does not call `loadInstance`, and the
> registration still happens outside the class.

**(d) The precedence of the world source is invisible.** Loader → generator → empty exists only in
the javadoc (`FalcoInstance.java:90-96`). In the code there is a `@Nullable` constructor parameter
(`:237`) and a `@Nullable` setter (`:856`), which can be set independently of each other.

> **A correction to revision 1.** It said that for anyone who sets both, "the generator silently
> never runs". Against the code that is wrong: `completeLoad` calls `createChunk` exactly when the
> loader has returned `null` (`:593-597`), and `createChunk` applies the generator (`:667-669`). A
> loader **with** a generator for unknown chunks is a working, sensible combination; the test cited
> (`FalcoInstanceGeneratorTest.java:106-119`) pins only the reverse case — the generator does not run
> when the loader has delivered the chunk. The real deficiency is narrower: the precedence is a
> javadoc promise, not a visible form. The consequence for section 3.3 is considerable, see decision
> 1 there.

**(e) Observation only through a type test and pull.** The only way to the Anvil counters runs
through an `instanceof` cast, and in the demo even through two, because a decorator sits in between:

```java
// falco-demo/src/main/java/net/onelitefeather/falco/demo/LoaderDiagnosis.java:56-63
if (loader instanceof TimingChunkLoader timing) {
    return of(timing.delegate());
}
if (!(loader instanceof FalcoAnvilLoader falco)) {
    return null;
}
AnvilDiagnostics diagnostics = falco.diagnostics();
```

`AnvilDiagnostics` is a `final class` (`AnvilDiagnostics.java:48`), hence not replaceable by a metric
sink of one's own, and is created in the loader constructor itself — three dimensions produce three
separate counter readings that nobody can bring together. On the light side the picture is the same
with a single counter: `ChunkLightScheduler.fullPropagations()` (`:324`).

**(f) The hard blocker.** `FalcoInstance` accepts `FalcoChunk` exclusively
(`requireFalcoChunk`, `FalcoInstance.java:690-695`), `FalcoLightingChunk extends DynamicChunk`
(`falco-light/src/main/java/net/onelitefeather/falco/light/FalcoLightingChunk.java:80`). A
`FalcoInstance` with `scheduler.supplier()` throws on the first chunk loaded. The demo sails around
this in 13 lines of prose and does without `FalcoInstance`
(`falco-demo/src/main/java/net/onelitefeather/falco/demo/ServerStack.java:28-40`, note text in
`:186-190`) — both stacks therefore run on `InstanceContainer` (`DemoServer.java:155`). That is the
single most important observation in this document: a facade that is supposed to build the "whole
Falco stack" cannot today offer exactly the combination that would be its selling point.

> **Cleared in [#16](https://github.com/OneLiteFeatherNET/Falco/pull/16).** `setChunkLifecycle` hands
> the two `protected` hooks to whoever owns the chunk type, so `FalcoInstance` manages any
> `DynamicChunk`, and `FalcoLightingChunk` gained the `markLoaded`/`markUnloaded` pair that
> `FalcoChunk` already had. Neither module learns about the other — `falco-instance` sees a
> `Consumer<Chunk>`, `falco-light` its own class, and the cast is written by the caller — which is
> what keeps the three isolation rules of `falco-archunit` green. Three tests in `falco-demo` run the
> full stack; removing the `setChunkLifecycle` call from the first brings back exactly the exception
> described above. The demo itself still runs both stacks on `InstanceContainer`, now for a different
> reason (see the correction to step 6 in section 6).

**(g) No teardown handle.** `FalcoAnvilLoader` is `AutoCloseable` (`:618`), `ChunkLightScheduler` has
neither `close()` nor a default executor that can be shut down (`:168-179`, virtual threads without a
handle), `FalcoInstance.unregister(InstanceManager)` (`:296`) does not touch the loader. The user
holds three lifecycles together by hand.

---

## 2. Design principles

### P1 — Construction phase before publication, never fluent setters on the runtime object

**Rationale.** Fowler separates the fluent interface (a readability pattern for whole expressions)
from the builder (a construction pattern against telescoping constructors, Effective Java Item 2) and
warns that fluent setters on the domain model violate command-query separation. OkHttp and
`java.net.http.HttpClient` draw the consequence: builder and product are separate types, the product
is immutable, and variants arise through `newBuilder()` or `copy()`. Testcontainers shows the
counter-check: `withEnv(...)` after `start()` is a silent no-op, because `start()` simply returns
when `containerId != null` — nothing throws, nothing warns.

**Consequence for Falco.** The twelve fields of `FalcoAnvilLoader` (`:105-116`) are final; a builder
is a pure addition there, with no rework of the internals. For `ChunkLightScheduler` that holds only
in part — revision 1 claimed too much here: final and configuration are exclusively `area` (`:107`),
`executor` (`:108`) and `maxAreaSize` (`:109`); `:111-114` are runtime state (`dirty`, `inFlight`,
`lastTick`, `bound`), and `maxCachedChunks` is not a field of the scheduler at all but is passed
through to `new ChunkLightArea(service, maxCachedChunks)` (`:139` → `ChunkLightArea.java:166`). A
scheduler builder is therefore an addition, a scheduler `toBuilder()` would not be — the same
reconstruction question on which `toBuilder()` fails in section 3.1. `FalcoInstance` is the third
case: its four setters can be called at any time and write to non-volatile fields (`:211`, `:213`).
The fluent API offers exclusively a construction phase for all three modules.
`instance.chunkLoader(x).generator(y)` as a fluent style on the running object is not built — for
`FalcoInstance` that would aggravate the existing visibility problem, and for `FalcoAnvilLoader` it
is simply impossible.

### P2 — Required parameters in the factory or the terminal method, not in `build()` validation

**Rationale.** The classic builder loses the compile-time guarantee of the constructor; Netty repairs
that with `validate()` and `IllegalStateException("group not set")`, and `HttpRequest.Builder` does
the same for the missing URI — although `HttpRequest.newBuilder(URI)` exists and would make the error
impossible. The cheaper route without step interfaces is to make the required piece a parameter of
the factory or terminal method: `Thread.ofVirtual().name("x-", 0).start(task)` cannot forget the
`Runnable`, and `Timer.builder("name").tags(...).register(registry)` cannot forget the registry.

**Consequence for Falco.** Every module has exactly one or two genuine required values, and they are
stable: `worldRoot` + `dimension` (anvil), `ChunkLightService` or rather its `BlockLightSource`
(light), `RegistryKey<DimensionType>` (instance). These sit in the `builder(...)` entry point or in
the terminal method — never as an optional setter with a later check. A staged builder with one
interface per stage (jOOQ) is **not** introduced: with two required values the gain is zero, the
interface explosion is real, and jOOQ has to warn its users explicitly against referencing the step
types as a variable type.

### P3 — Optional values are additive, named and checked early

**Rationale.** Late validation in `build()` loses the connection to the offending call. AutoValue
solves that with the `autoBuild()` sandwich: values that can be checked context-free in the setter,
only cross-property invariants in `build()`, and there with a named rule rather than a bare exception
type. Caffeine goes further and forbids even setting a value twice, along with semantic
incompatibilities (`requireState(this.weigher == null, "maximum size cannot be combined with
weigher")`). OpenTelemetry makes the cardinality visible through naming: `add*` accumulates, `set*`
replaces.

**Consequence for Falco.** `openRegionLimit` is checked in the constructor today
(`FalcoAnvilLoader.java:145-147`) and an existing test pins exactly that limit — the check therefore
belongs in the builder method, not in `build()`, so that the moment of the exception is only moved
earlier and never later. `maxAreaSize < 1` (`ChunkLightScheduler.java:136-138`) and
`maxCachedChunks < 0` (`ChunkLightArea.java:166-168`) likewise. The sharpest case is
`compressionLevel`: today it is checked **nowhere** during construction, only in
`ChunkCompression.compress` (`:163-167`) — and there on a path that `saveChunk` swallows with
`catch (IOException | RuntimeException)` (`FalcoAnvilLoader.java:369-376`). A slot that opens this
value **must** check in the setter, otherwise it trades an unreachable adjustment knob for silently
unsaved chunks. Naming convention: on its own types Falco already uses getters without a `get`
prefix (`diagnostics()`, `regionDirectory()`, `supplier()`, `fullPropagations()`); the builder
setters are correspondingly prefix-free (`openRegionLimit(int)`, not `setOpenRegionLimit(int)`), and
listener slots are named `on*`.

### P4 — Immutability: the result is immutable, the builder is not

**Rationale.** The pragmatic compromise of the libraries examined is uniform throughout: mutable
builder + explicit `copy()`/`toBuilder()` + immutable result. Copy-on-write builders avoid the
aliasing trap structurally, but cost one object per call. The JDK writes the thread-safety down
explicitly ("not synchronized and should not be called from multiple threads without external
synchronization") — on `HttpRequest.Builder`, `copy()` exists for exactly the reason that
`var a = base.timeout(5); var b = base.timeout(10);` would otherwise mutate the same object. JEP 468
(`old with { x = 0; }`) has stood at Candidate since 2024 and did **not** ship in JDK 25; withers
have to be written by hand.

**Consequence for Falco.** The builders are mutable, not thread-safe (to be written that way in the
javadoc — in a chunk/light context that is part of the contract, not fine print) and deliver
immutable products. **No builder gets a `copy()` or a `toBuilder()`.** Revision 1 had earmarked this for
the anvil builder as the showcase; it is not implementable against the actual set of fields and would
on top of that promote a data-loss path to the normal route (section 3.1). The aliasing trap is
instead removed at the root: the anvil builder carries its required values in the terminal method,
can be called more than once, and delivers an independent loader per `build()` — a reusable blueprint
instead of a copyable product.

> **Correction to revision 1.** It stated there that value types in `record` style would not be
> introduced, because Falco has the pattern only in the unpublished demo and two construction styles
> side by side could not be justified. That is factually wrong: `record` is already published API in
> all three modules — `PaletteData.java:39`, `RegionFile.RawChunk` (`RegionFile.java:682`),
> `ChunkArea.java:49`, plus four internal ones (`FalcoAnvilLoader.java:229`, `:1037`,
> `BiomePaletteResolver.java:106`, `ChunkLightService.java:370`). The style argument carries against
> the record just as little as it does against the builder. The decision against the options record
> as the sole contribution is therefore re-argued in section 2.9, from the actual set of fields.

### P5 — Nullability: no `@Nullable` returns in the chain, `@Contract` on every fluent slot

**Rationale.** A `null` in the middle of a chain turns it into an NPE trap with no pointer to the
link. For the return value itself the assignment is not trivial: with a mutable builder, discarding
`this` is harmless (`@CanIgnoreReturnValue`); with an immutable wither it is always a bug
(`@CheckReturnValue`). With the JetBrains annotations, `@Contract("_ -> this")` and
`@Contract(value = "_ -> new", pure = true)` do the same job on the IDE side.

**Consequence for Falco.** Every production package already carries `@NotNullByDefault`
(`falco-anvil/src/main/java/net/onelitefeather/falco/anvil/package-info.java:24`, likewise light,
instance, demo), and `@Contract` is established in the repository (`FalcoInstance.java:462`,
`ServerStack.java:89`). All fluent methods get `@Contract("_ -> this")`. Copy methods are dropped
without replacement (P4), and so is their annotation. Optional values are modelled as
`@Nullable` **parameters** wherever `null` already has a meaning today (`Generator`,
`ChunkLoader`) — the split into `fromGenerator`/`fromLoader`/`empty` proposed in revision 1 is
withdrawn, because it would have forbidden a combination that works (section 3.3, decision 1).
**Open point:** `org.jetbrains.annotations` is `compileOnly` everywhere
(`falco-instance/build.gradle.kts:8`), so the annotations are not available to the consumer as
resolvable types when compiling against the published jars. For an API whose value lies to a large
extent in IDE guidance, that is a genuine decision — see section 6, question 4.
JSpecify (`@NullMarked`, the consensus standard since 1.0/2024, Spring 7 has converged on it) would
be the alternative, but would be a new dependency in the repository; the dossier contains no
statement on whether JSpecify is already managed in `mycelium-bom`.

### P6 — Binary compatibility: return types are contracts, and are so from the start

**Rationale.** According to Eclipse's "Evolving Java-based APIs", changing a method return type
breaks binary compatibility **always**, even when narrowing to a subtype — the descriptor is part of
the signature in the bytecode. In a fluent API the return type is part of every single method. A
later rework `Builder foo()` → `NextStage foo()`, or pulling in a generic base class, is a major
break. Second: adding an abstract method to a builder **interface** is binary compatible only if
clients do not implement it — the JDK solves that through `default`
(`HttpRequest.Builder.HEAD()` was added later, in Java 18, as a `default`).

**Consequence for Falco.** There is no japicmp/revapi in the build (verified by a search across all
`.kts`/`.yml`/`.json`), and everything is `@ApiStatus.Experimental` — the freedom is there. Even so:
(a) The builders are **concrete final classes**, not interfaces, because a class can be extended with
methods additively whereas an interface cannot without `default`. (b) No CRTP
(`Builder<B extends Builder<B>>`) without a need for inheritance — Netty, Testcontainers and AssertJ
pay the price (unreadable declarations, an unchecked cast in `self()`, `GenericContainer<?>` at every
field declaration), and Falco does not have the benefit, because `FalcoAnvilLoader`,
`ChunkLightService` and `ChunkLightScheduler` are all `final`. (c) New optional properties are added
additively as new methods, never as a signature change. (d) The new fluent surface continues to carry
`@ApiStatus.Experimental`; it must not sell itself as stable while the rest is not
(`package-info.java:19`). Point (c) is at the same time the strongest argument against the options
record as the sole contribution (section 2.9.2): adding a record component changes the descriptor of
the canonical constructor and breaks every call site binarily.

### P7 — Shrink the contract surface rather than grow it

**Rationale.** A builder with 30 methods of equal rank produces autocomplete noise and reveals no
required ordering; per Fowler, the javadoc of a single fluent method is structurally of little use,
because it only explains itself in context.

**The principle, stated more precisely.** Revision 1 phrased it as "the fluent API must not grow the set
of public types" and violated that about tenfold in its own prescriptive part. The defensible wording
is: **no new published top-level types.** A nested `Builder` counts against the javadoc and
compatibility budget, not against the namespace surface of the module package — it cannot be named
without the type it builds. This refinement is invoked in section 3 and section 4.1 and is evidenced
there in each case by a type count. For comparison, the current state
(top-level, `public`, excluding `package-info`): falco-anvil 13, falco-light 14, falco-instance 3.

**Consequence for Falco.** The fluent API is the opportunity to mark the surface that is public by
accident today. Implementation detail in fact, but fully public in `falco-light`:
`ChunkLightState` (`:40`, with `copy/get/update/border/injectBorder/toSections`), `SectionOpacity`
(`:32`), `LightPropagator` (`:29` — no longer called by **any** production class of the module, only
by tests and benchmarks, although `package-info.java:7` describes it as part of the architecture),
`ChunkLightArea` (`:97`) and the sentinel constant `ChunkLightArea.CLEAN = Long.MIN_VALUE` (`:117`).
These types get `@ApiStatus.Internal`.

> **Correction to revision 1.** `LightNibbles` (`:31`) does **not** belong on this list. The type
> appears in the signature of the public static method `ChunkLightService.applyLight(Chunk,
> List<LightNibbles>, boolean)` (`ChunkLightService.java:157`), and `ChunkLightService` is the type
> declared as the module's entry point (`package-info.java:3-9`). Marking a parameter type of a
> public method as internal does not shrink the contract surface; it makes a false statement about
> it. Either `applyLight` itself becomes internal — in which case it has to be clarified who needs it
> outside the module — or `LightNibbles` stays public. The remaining five entries are not affected by
> this objection.

### P8 — Build on Minestom's EventNode, do not build beside it

**Rationale.** Falco uses the EventNode system in **zero** places across its three published modules;
a search over `falco-instance`, `falco-light`, `falco-anvil` finds only `EventDispatcher.call(...)`,
that is, the sending of standard events, but not a single `EventNode`, `addListener` or
`eventNode()` access. Instead, `falco-light` contains a complete home-grown one.

**Consequence for Falco — weakened relative to revision 1.** The principle remains correct as a
prohibition: Falco does not invent a second registration route alongside Minestom's. It does
**not**, however, carry as a construction argument for a terminal method that registers event nodes.
Section 3.2.1 shows that the defect the unload listener was meant to cure is only half reachable
through `InstanceChunkUnloadEvent` — the majority of the ghost entries come from coordinates that
never had a chunk object. The fix needs zero events and zero new public API. What remains of P8:
wherever Falco wants to listen at instance scope in future, it listens under `instance.eventNode()`
and not globally, and it registers during setup, never in the hot path (section 5.2). Exactly two
things stay home-grown for structural reasons, because Minestom has no counterpart: the `setBlock`
override (position-precise; `InstanceBlockUpdateEvent` is fired only from
`InstanceContainer#setBlock` or `FalcoInstance#writeBlock`, never from `Chunk#setBlock`, and
batches, generators and loaders write past it) and `LightUpdateAware` (chunk-scoped; `Chunk` does not
implement `EventHandler`, `EventFilter` has no `CHUNK` constant — only `ALL`, `ENTITY`, `PLAYER`,
`ITEM`, `INSTANCE`, `INVENTORY`, `BLOCK` exist).

### 2.9 Rejected alternatives

P1–P8 presuppose the builder as the construction form and then justify only its detailed shape. That
is a gap: no cheaper form was ever put up against the builder. This section makes that up, tests four
alternatives against the same field inventory from which section 1 raised its finding, and ends with
the decision that section 3 builds on.

#### 2.9.1 The yardstick

**Published top-level types today.** falco-anvil 13 (`FalcoAnvilLoader`, `RegionFile`,
`SectionCodec`, `PaletteData`, `PaletteEntryResolver`, `BlockPaletteResolver`, `BiomePaletteResolver`,
`AnvilDiagnostics`, `AnvilChunkException`, `ChunkCompression`, `BitPacker`, `NbtReads`,
`RegionConstants`; plus exactly one published nested type, `RegionFile.RawChunk`,
`RegionFile.java:682`) — falco-light 14 — falco-instance 3 (`FalcoInstance`, `FalcoChunk`,
`FalcoInstanceException`).

**Test surface.** The figure "more than 30 test call sites" from revision 1 is wrong. Counted:

| Constructor | Call sites | Distribution |
|---|---|---|
| `new FalcoAnvilLoader(...)` | **14** | 13 unqualified plus one fully qualified one in `ChunkLightServiceIntegrationTest.java:155`; of those 7 in `falco-anvil/src/test`, 2 in `falco-benchmarks`, 3 in `falco-demo/src/test`, 1 in production code (`LoaderKind.java:112`) |
| `new ChunkLightScheduler(...)` | **27** | 25 in 4 test files of falco-light, 1 in the javadoc (`ChunkLightScheduler.java:187`), 1 in `ServerStack.java:167` |
| `new FalcoInstance(...)` | **7** | all in `falco-instance/src/test`, 5 files |

What decides this is not the sum but the concentration: falco-anvil encapsulates its construction in
three `loader()` helpers (`FalcoAnvilLoaderIntegrationTest.java:52`, `…LifecycleTest.java:60`,
`…ConcurrencyTest.java:74`), which 79 lines in `falco-anvil/src/test` refer to — a change to the
constructor set costs three lines there. falco-light has no such helper: there, 25 literal
`new ChunkLightScheduler(...)` stand side by side.

**Javadoc.** `build.gradle.kts:35-37` sets `addBooleanOption("Werror", true)`; the standard doclet has
`-Xdoclint:all` by default, missing `@param`/`@return` are build errors. `falco-demo` additionally
attaches `javadoc` to `check` (`falco-demo/build.gradle.kts:22-24`). Every new public method and every
record component costs a full block.

**Java version.** Toolchain 25 (`build.gradle.kts:20-24`), `options.release.set(25)` (`:30-33`).

#### 2.9.2 Alternative A — an options record per module

**What Java 25 can do here.** Records: yes, and already as published API of this repository (see the
correction to P4). Compact canonical constructor with validation: yes. Derived instances through
`with` expressions (JEP 468): **no** — Candidate since 2024, not in JDK 25. `withX` methods have to be
written by hand, one per component.

What is implementable against the actual field inventory:

```java
public record AnvilOptions(int openRegionLimit, int compressionLevel, int saveParallelism, int dataVersion) {

    public static final AnvilOptions DEFAULTS = new AnvilOptions(
            FalcoAnvilLoader.DEFAULT_OPEN_REGION_LIMIT,                  // FalcoAnvilLoader.java:103
            ChunkCompression.DEFAULT_LEVEL,                              // ChunkCompression.java:78
            Math.max(Runtime.getRuntime().availableProcessors(), 2),     // as in FalcoAnvilLoader.java:160
            MinecraftServer.DATA_VERSION);                               // as in FalcoAnvilLoader.java:161

    public AnvilOptions {
        if (openRegionLimit <= 0) {
            throw new IllegalArgumentException("The amount of open region files must be positive but was " + openRegionLimit);
        }
        if (compressionLevel < ChunkCompression.FASTEST_LEVEL || compressionLevel > ChunkCompression.SMALLEST_LEVEL) {
            throw new IllegalArgumentException("The compression level must be within ["
                    + ChunkCompression.FASTEST_LEVEL + ", " + ChunkCompression.SMALLEST_LEVEL + "] but was " + compressionLevel);
        }
    }
}
```

Every constant called here is verified: `DEFAULT_OPEN_REGION_LIMIT` (`FalcoAnvilLoader.java:103`),
`FASTEST_LEVEL` (`ChunkCompression.java:61`), `DEFAULT_LEVEL` (`:78`), `SMALLEST_LEVEL` (`:83`). The
validation is verbatim that of today's constructor (`FalcoAnvilLoader.java:145-147`) plus the one from
`ChunkCompression.compress` (`:164-167`), which today only strikes when saving.

Two findings speak for this form. First, `MinecraftServer.DATA_VERSION` is a *compile-time constant*:
`MinecraftConstants` declares `int DATA_VERSION = 4790` in an interface, and javac inlines that per
JLS 13.1. A `DEFAULTS` field therefore triggers no class initialisation of `MinecraftServer` — the
worry from revision 1 ("`build()` must not touch `MinecraftServer.DATA_VERSION`") is moot. Second, the
`toBuilder()` problem **structurally does not exist** here: `worldRoot` and `Key` never enter the
options object, they stay constructor parameters.

**The finding against it is hard.** The three object-valued knobs from section 1.2(a) — `diagnostics`
(`:155`), `blockResolver` (`:156`), `biomeResolver` (`:157`) — **cannot** go into a `DEFAULTS` field.
`AnvilDiagnostics` is a `final class` with `LongAdder` counters (`AnvilDiagnostics.java:48`,
constructor `:77`); a statically shared `DEFAULTS` would put every loader of a JVM on the same counter
state — exactly the mixing that section 1.2(e) describes as a defect, only the other way round. Taking
them into the record forces `@Nullable` components meaning "null = build a fresh one per loader", that
is, the nullable-with-semantics that P3 argues against.
**The options record covers the four `int` values cleanly and the three object values not at all** —
and `PaletteEntryResolver` is the most prominent unreachable value in the whole document.

**Binary compatibility.** Adding a component changes the descriptor of the canonical constructor
(`(IIII)V` → `(IIIII)V`) and breaks every call site binarily, `DEFAULTS` included. Adding a builder
method is purely additive (P6). As long as everything carries `@ApiStatus.Experimental`
(`package-info.java:19`, `README.md:24-25`) and no japicmp is in the build, the point is partly
covered.

**Javadoc.** 4 components + 4 `withX` blocks + 1 type block = 9 units against 4 builder methods
+ `build()` + type block = 6. The difference is real but small — javadoc is **not** the distinguishing
feature.

#### 2.9.3 Alternative B — named static factories, without any builder

The house style already knows this form, in the same package: `RegionFile.open(Path)` (`RegionFile.java:148`),
`ChunkCompression.fromId(int)` (`ChunkCompression.java:106`), in the demo `LoaderKind.parse(String)`
(`:80`), `LoaderKind.create(...)` (`:110-117`), `ServerStack.parse(String)` (`:141`),
`ServerStack.chunkSupplier()` (`:165-170`). It is the only form with **zero** new published types.

**Where it carries.** `FalcoAnvilLoader.forWorld(Path, Key)` is a pure renaming of the
2-parameter constructor (`:126`) and makes the trap from `README.md:125-133` visible in the name:
world root, not region directory.

**Where it does not carry.** `FalcoAnvilLoader.forRegionDirectory(Path)` is not implementable without
exactly the rebuild that section 3.1 rejects: the constructor resolves `(worldRoot, dimension)`
immediately through `resolveRegionDirectory` (`:148`, `:203-211`) and keeps only `dimension.asString()`
from the `Key` (`:154`). A factory that takes the directory directly needs a second private constructor
and a decision about what `legacyLayout()` (`:601`) then returns — the same fourth combination that
section 3.1 discards. And structurally the form fails in falco-light: the damage from section 1.2(b)
is two *independently* optional values, and named factories express those only through n² names.

**But two individual measures from this alternative are valuable independently of the rest:**

1. **`defaultExecutor()` becomes `public`.** ✅ **Done in `1f72962` (#14).** It was `private static`
   and creates a fresh virtual-thread executor bounded by a semaphore on every call — so it could be
   published without danger, and its javadoc now states that two schedulers built from two calls do
   not share a bound. A caller that only wants to change the cache can *name* the default instead of
   rebuilding it:

   ```java
   new ChunkLightScheduler(service, ChunkLightScheduler.defaultExecutor(), ChunkLightScheduler.DEFAULT_MAX_AREA_SIZE, 512);
   ```

   One method, one javadoc block, zero new types — and exactly the reason why the tests repeat `16`
   today disappears (section 1.2(b)). That is cheaper than any builder and does not rule one out.
2. **A two-component record `AreaLimits(int maxAreaSize, int maxCachedChunks)`** with the two
   existing checks (`ChunkLightScheduler.java:136-138`, `ChunkLightArea.java:166-168`) and a
   `DEFAULTS` constant made from `DEFAULT_MAX_AREA_SIZE` (`:90`) and `DEFAULT_MAX_CACHED_CHUNKS`
   (`ChunkLightArea.java:127`) removes the swappability at the call site. It costs one published
   top-level type and covers only two of the six slots from section 3.2.3.

#### 2.9.4 Alternative C — a configuration object as the parameter of *one* constructor

This is the contribution question for A, and its promise — *one* constructor — cannot be redeemed
against the field inventory. The 2-parameter constructor `FalcoAnvilLoader(Path, Key)` (`:126`) stands
in the published quick start (`README.md:92`), in `LoaderKind.java:112` and at 14 call sites; removing
it breaks the documented entry page and every test. "One constructor" means in practice:
**a third constructor next to the two existing ones.** The telescoping set grows, it does not shrink.

The gain is real nonetheless: the copying question disappears, there is no aliasing case (P4), no
`@CheckReturnValue`-versus-`@CanIgnoreReturnValue` dilemma (P5) and no question about the thread
safety of the builder. P4 and P5 are not satisfied for this form, they become moot. The loss is the
terminal method: `build(Path, Key)` with required values (section 3.1) and `register(...)`/
`registerAndShutdownWith(...)` (section 3.3) cannot be hung off a constructor parameter — and in
section 3.3 it is precisely the terminal method that carries the lifecycle guarantee which closes the
data-loss case.

#### 2.9.5 Alternative D — change nothing at all

Zero new types, zero javadoc, zero migration — the zero point against which everything else has to
justify itself.

**Where it fails hard.** Setters would not be an ergonomics question here but a degradation of
correctness. All twelve fields of `FalcoAnvilLoader` are final (`:105-116`), and the configuration
values are not read on the thread that set them: `compressionLevel` at `:363`, `blockResolver` at
`:1000`/`:1252`, `biomeResolver` at `:1001`/`:1253`, `dataVersion` at `:1182`, `openRegionLimit` at
`:844`. The load path runs on a virtual thread according to `FalcoInstance.java:564-566`, the save
path behind `this.saveLimit.acquire()` (`:407`). Making these fields non-final produces exactly the
data race that section 1.2(c) reports as a defect — there grown historically, here deliberately
introduced anew.

**Where it is surprisingly good: falco-instance.** The three constructors `:225`, `:237`, `:255` are
**not** a telescope in the sense of section 1.2(b): their parameters are `UUID`,
`RegistryKey<DimensionType>`, `@Nullable ChunkLoader`, `Registries`, `Key` — different types
throughout, swapping them is a compiler error. The module's actual damage is the non-volatile fields
(`:211`, `:213`) and the asymmetry between constructor (`:260` calls `loadInstance`) and setter
(`:831` does not call it, documented at `:824-827`). Both survive every construction form unchanged.

**Where it definitely is not enough:** section 1.2(a). `PaletteEntryResolver` remains a published,
documented interface type (`PaletteEntryResolver.java:26-27`) that `SectionCodec` accepts in four
places and that no consumer can get into the loader. No abstention from change fixes that.

#### 2.9.6 Comparison

The builder column shows the state **after** this revision (section 3), not that of revision 1.

| Criterion | Builder (section 3) | A options record | B named factories | C options as ctor param | D change nothing |
|---|---|---|---|---|---|
| New published top-level types | **0** (3 nested builders) | +1 per module | 0 | +1 per module | 0 |
| P7 in its sharpened form | satisfied | violated | satisfied | violated | satisfied |
| Binary compat. on addition | additive | **break** (canonical ctor) | additive | break, inherited from A | additive |
| Solves section 1.2(a) unreachable object values | **yes** | no | no | no | no |
| Solves section 1.2(b) swappable `int` | yes | yes | only with `AreaLimits` | yes | no |
| Carries a terminal method (lifecycle, section 3.3) | **yes** | no | no | no | no |
| Testability | unit test, no `Env` needed | unit test | unit test | unit test | unchanged |
| Javadoc units (anvil, 4 values) | ~6 | ~9 | ~2 | ~9 | 0 |
| Migration of existing calls | 0 (ctors remain) | 0 | 0 | 0 | 0 |
| Degrades concurrency | no | no | no | no | **yes**, if setters |

The "testability" row has turned around compared to revision 1: there the terminal method was to be
`install(Instance)`, and that is testable only with a real `Instance` whose `eventNode()` is not null —
so only in a test with an `Env` parameter and a server process (`MicrotusExtension.java:32` in cyano
0.7.2 calls `MinecraftServer.updateProcess()`; today 7 of 19 test files in falco-light declare an
`Env`). On top of that, `EnvImpl` fails every test whose exception reaches the handler
(`EnvImpl.java:24`, `:34-36`) — a test for the *error path* of a light pass would first have to divert
the handler. With the terminal method `build()` (section 3.2) this item disappears entirely.

#### 2.9.7 Decision

**The builder gets built, in all three modules — not because it is the prettiest form, but because in
each module it is the only one that touches that module's named illness.** The modules have different
illnesses, and the deciding reason is a different one per module:

- **falco-anvil: unreachable object values.** Only the builder solves section 1.2(a). A, B, C and D
  cannot supply `AnvilDiagnostics` and the two `PaletteEntryResolver` without either putting every
  loader of a JVM on shared counters or introducing `@Nullable` components with special semantics.
  That is not an ergonomics question but the module's main finding.
- **falco-light: independently optional values.** After the revision there are six slots (section
  3.2.3), not two; named factories would need n² names for that. The two individual measures from
  2.9.3 are adopted **in addition**: `defaultExecutor()` becomes public (immediately, independently of
  the builder), `AreaLimits` is not built, because the builder covers the same two values without a
  new top-level type.
- **falco-instance: lifecycle, not construction.** Here the objection from alternative D is accepted
  explicitly: the three constructors are not a telescope, and a builder that merely wraps the setters
  repairs nothing. The builder is built anyway — but its value lies exclusively in the **terminal
  method** and in the three slots that do not exist at all today (chunk lifecycle, ownership of the
  loader, save decision on shutdown, section 3.3). The order follows from that: `volatile` first as a
  standalone fix, builder afterwards (section 4.3).

What remains from the alternatives and is **not** built: no options record in a published module (P6
plus the object-value finding), no second factory family next to `RegionFile.open` (section 3.1,
fourth commitment), no `copy()`/`toBuilder()` (P4).

---

## 3. API sketch per module

### 3.1 falco-anvil

**New types.** Exactly one: `FalcoAnvilLoader.Builder`, nested and final. No new top-level type, no
new functional interface, no new constant. The two existing constructors (`:126`, `:144`) stay
unchanged; they have 14 call sites in the Java code plus the quick start (`README.md:92`), one of
them in production code
(`falco-demo/src/main/java/net/onelitefeather/falco/demo/LoaderKind.java:112`) and four for the
3-parameter form, all in tests (`FalcoAnvilLoaderConcurrencyTest.java:176`, `:224`,
`FalcoAnvilLoaderIntegrationTest.java:417`, `:432`).

The builder exists for a single purpose: to make reachable the values the constructor hardcodes
today (`:151`, `:155`, `:156`, `:157`, `:160`, `:161`) and that section 1.2(a) describes as "present
in the code, absent from the API".

#### Entry point and terminal method

```text
FalcoAnvilLoader.builder()                       -> Builder          (static, no parameters)
FalcoAnvilLoader.Builder.build(Path, Key)        -> FalcoAnvilLoader (terminal method)
```

The two required values sit in the **terminal method**, not in the entry point. P2 allows either;
the terminal method is strictly stronger here, and for a concrete reason rather than out of symmetry:
the same builder builds several loaders one after another. That is exactly the use case for which
revision 1 had provided a `copy()`.

`build()` can be called any number of times and returns an independent loader every time. Everything
that has to exist per loader comes into being in `build()`: `regions`, `trackedChunks` (`:158-159`)
and `saveLimit` (`:160`).

> **Corrected during implementation ([#16](https://github.com/OneLiteFeatherNET/Falco/pull/16)).**
> This section called for a *mutable* builder here, with reuse across dimensions as the argument. The
> argument does not hold — an immutable builder is reused identically, because `build(Path, Key)`
> takes the values that differ per loader anyway — and the cost of following it would have been three
> builders with two semantics: `anvilBuilder.compressionLevel(9);` would take effect while
> `lightBuilder.maxAreaSize(4);` silently would not. The same line meaning two different things is a
> trap, and the variant that does nothing is the one nobody notices. All three builders are immutable.

#### Slot table

| Slot | Kind | Default | Where the value comes from today |
|---|---|---|---|
| `build(Path worldRoot, …)` | **required** | — | Constructor parameter `:126`/`:144`; resolved to `regionDirectory` immediately (`:148`, `resolveRegionDirectory:203-211`), not stored afterwards |
| `build(…, Key dimension)` | **required** | — | Constructor parameter; becomes `regionDirectory` (`:152`), `legacyLayout` (`:153`) and `dimensionLabel = dimension.asString()` (`:154`) |
| `openRegionLimit(int)` | optional | `DEFAULT_OPEN_REGION_LIMIT` = 64 (`:103`) | 3-parameter constructor `:144`, check `:145-147`, field `:150`, used in `evictRegions:844` |
| `compressionLevel(int)` | optional | `ChunkCompression.DEFAULT_LEVEL` = 2 (`ChunkCompression.java:78`) | hardcoded at `:151`, used in `saveChunk:363` |
| `diagnostics(AnvilDiagnostics)` | optional | **new instance per `build()`** | hardcoded at `:155`; read by `:257`, `:276`, `:292`, `:316`, `:337`, `:364`, `:370`, `:514`, `:662-679`, `:1287` |
| `blockResolver(PaletteEntryResolver)` | optional | `new BlockPaletteResolver(<effective diagnostics>)` | hardcoded at `:156`; used in `decodeSections:1000` and `encodeSection:1252` |
| `biomeResolver(PaletteEntryResolver)` | optional | `new BiomePaletteResolver(<effective diagnostics>)` | hardcoded at `:157`; used in `decodeSections:1001` and `encodeSection:1253` |
| `saveParallelism(int)` | optional | `Math.max(availableProcessors(), 2)` | hardcoded at `:160`; used in `saveChunks:407`/`:413` |
| `dataVersion(int)` | optional | `MinecraftServer.DATA_VERSION` | hardcoded at `:161`; used in `snapshot:1182` |
| `exceptionHandler(Consumer<Throwable>)` | optional | `MinecraftServer.getExceptionManager()::handleException`, **evaluated per error** | `failedLoad:342`, `saveChunk:375`, `awaitAll:1289` |

Three of the rows each carry a commitment that cannot be read off the table.

**`diagnostics` and the two resolvers belong together.** The constructor creates the diagnostics
first and passes them into both resolvers (`:155-157`). `build()` has to reproduce that: determine
the effective diagnostics first, then create the default resolvers from them — after which the order
of the setter calls does not matter. Anyone who sets a resolver of their own can tear that coupling
apart, and `build()` **cannot** check it: `PaletteEntryResolver` has exactly two methods,
`toId(String, CompoundBinaryTag)` and `toEntry(int)` (`PaletteEntryResolver.java:42`, `:50`), and
neither of them exposes diagnostics. The consequence is silent: `logSummary` reads
`unknownBlockCount()`/`unknownBiomeCount()` from the loader's own instance (`:670`, `:677`), a
resolver with foreign diagnostics counts past it, and the closing line reports zero unknown blocks.
That belongs in the javadoc of `blockResolver`/`biomeResolver` and is demonstrated in the example
below. A slot of the form `blockResolver(Function<AnvilDiagnostics, PaletteEntryResolver>)` would
rule the trap out structurally, but it makes the common case (`ignored -> myResolver`) unreadable —
deliberately not chosen.

**The default for `diagnostics` is a *new* instance per `build()`, not one per builder.** Otherwise
two loaders from the same builder would silently share their counters *and* their throttle states
(`missingRegionFileReported` and friends, `AnvilDiagnostics.java:63-65`), and one world would stop
getting a warning because another had already used it up. Whoever wants to share says so:
`diagnostics(shared)`. `AnvilDiagnostics` is suited to that — every field is a `ConcurrentHashMap`,
an `AtomicBoolean` or a `LongAdder` (`:90-101`).

**`saveParallelism` applies per loader.** `saveLimit` is one semaphore per loader (`:160`); three
loaders from one builder with `saveParallelism(4)` give twelve concurrent save operations, not four.
That, too, is a javadoc sentence.

#### Four commitments of the sketch

1. **`compressionLevel(int)` checks in the setter, not in `build()`.** `ChunkCompression.compress`
   throws `IllegalArgumentException` outside `[FASTEST_LEVEL, SMALLEST_LEVEL]`
   (`ChunkCompression.java:163-167`), and `saveChunk` catches `IOException | RuntimeException` and
   swallows it with a log line (`FalcoAnvilLoader.java:369-376`). A wrong level would therefore not
   show up during the construction phase, but would leave every single chunk silently unsaved. The
   bound is already pinned (`ChunkCompressionTest.java:113-114`); the setter adopts it word for word.
   The same holds for `openRegionLimit(int)` with the check from `:145-147`, and analogously for
   `saveParallelism(int)` with `>= 1`.
2. **`dataVersion(int)` is the only slot whose default is bound to Falco's compile time.**
   `MinecraftServer.DATA_VERSION` is inherited from `sealed interface MinecraftConstants`, where it
   is `int DATA_VERSION = 4790` — a *constant variable* that javac inlines into `FalcoAnvilLoader`.
   At runtime there is no access to `MinecraftServer`, and `build()` therefore needs no precaution
   (contrary to what revision 1 claimed). The point is the flip side: the value comes from the Minestom
   Falco was compiled against, not from the one the user runs against. `dataVersion(int)` is the only
   way to write a world for a divergent Minestom, and the only reason the slot exists.
3. **`exceptionHandler` moves only the sink, not the control flow.** `loadChunk` still throws
   `AnvilChunkException` afterwards (`:318-320`, `failedLoad:336-344`), and `saveChunk` still
   swallows (`:369-376`). The asymmetry is deliberate and justified in the class javadoc (`:54-58`):
   a chunk reported as absent is regenerated and overwrites the real data. The slot nevertheless has
   a hard reason beyond the metric: `MinecraftServer.getExceptionManager()` is
   `serverProcess.exception()` on a `volatile` field without a null check, one that only
   `MinecraftServer.init()` sets. A loader used by a tool without a server process dies in the error
   path on an NPE that hides the actual cause. The default is therefore evaluated **per error**
   rather than captured in `build()` — for the same reason that `BiomePaletteResolver` resolves its
   registry only on first use (`BiomePaletteResolver.java:59-65`, `:80-94`).
4. **`RegionFile` gets no parallel fluent facade.** `RegionFile.open(Path)` (`:148`) stays a static
   factory. Two construction styles in the same package confuse more than they help.

#### What the sketch deliberately does *not* offer

**`dimension(Key)` as a slot — and with it `copy()`/`toBuilder()`.** This is the substantive
correction to revision 1. Three independent reasons, each sufficient on its own:

- *P2.* `dimension` is a required value. An optional setter with a re-check in `build()` is exactly
  the construction P2 is written against.
- *The set of fields.* The loader stores neither `worldRoot` nor the `Key`; the fields are
  `openRegionLimit, compressionLevel, regionDirectory, legacyLayout, dimensionLabel, diagnostics,
  blockResolver, biomeResolver, regions, trackedChunks, saveLimit, dataVersion` (`:105-116`).
  It would be reconstructible — `legacyLayout` (`:108`) distinguishes the two layouts, and
  `Key.key(dimensionLabel)` would give the key back — but it would be reconstruction code with tests
  of its own, for a purpose the next point invalidates.
- *The data-loss path.* `resolveRegionDirectory` depends on I/O: if
  `<world>/dimensions/<ns>/<value>/region` does not exist while `<world>/region` does, the resolution
  falls back to the legacy directory (`:207-210`). On a world in the old layout, overworld **and**
  nether therefore land on the same directory and write over each other. This trap exists today
  already, through `new FalcoAnvilLoader(root, THE_NETHER)`, but it is a rare user error. A
  `copy().dimension(...)` whose entire reason for existing is the dimension switch turns it into the
  documented normal path.

**The real use case behind it stays served.** It is not "clone a loader" but "several dimensions with
the same configuration and the same counters". That is exactly what the reusable builder with
required values in the terminal method achieves — without a new field, without reconstruction,
without `copy()`. And because every `build()` call visibly redoes the resolution, the caller can
check it; `regionDirectory()` (`:591`) and `legacyLayout()` (`:601`) are public for precisely that
purpose according to their own javadoc (`:582-586`).

**`regionDirectory(Path)` as an override of the resolution.** Creates a fourth combination (explicit
path × `legacyLayout` flag) that nobody needs today. See section 6, question 5.

**`compression(ChunkCompression)`.** The scheme is fixed to `ZLIB` in two places (`:363` and `:755`,
where it is written into the region header). `GZIP` is permitted by the format, but nobody writes it;
`NONE` would bloat worlds. No slot.

**`onChunkLoaded/Saved/Skipped/Error(...)`.** Dropped relative to revision 1.
`onChunkSkipped((chunkX, chunkZ, reason) -> …)` requires a functional interface
`(int, int, String) -> void` that does not exist in `java.util.function` — so at least one new
published type, against P7 and uncounted in revision 1. All four events run through `AnvilDiagnostics`
anyway (`countChunkLoaded:211`, `countChunkSaved:218`, `countError:225`,
`reportMissingRegionFile:125`, `reportMissingChunkEntry:141`, `reportPartialChunk:160`), and
`diagnostics(AnvilDiagnostics)` makes that sink injectable. What is missing is a single word:
`AnvilDiagnostics` is `final` (`AnvilDiagnostics.java:48`) and therefore not replaceable by a metrics
sink — the observation from section 1.2(e). Whether that `final` falls is a decision about the
inheritability of a class on a hot path and belongs in section 6, question 11.

#### Shifting the side effects

The constructor as it stands does I/O and logs: `resolveRegionDirectory` calls `Files.isDirectory`
twice (`:207`), the INFO line (`:167-174`) calls `Files.isDirectory` again (`:171`) and
`describeRegionFileCount` (`:183`, which opens `Files.list` at `:184`). All of that moves entirely
into `build()`. For tests that rely on this line or on an early-resolved directory the point in time
changes — but not the order relative to the first `loadChunk`. With repeated `build()` the line
appears once per created loader, which is right: it names `regionDirectory`, `legacyLayout` and
`dimensionLabel`, and those differ per loader.

#### Example

A server that holds the overworld and the nether of the same world. Shared counters across both
dimensions, a block mapping of its own for a retired mod, high compression because this world is
rarely written and often read, and errors land in a list of their own instead of in the
`ExceptionManager`.

```java
Path worldRoot = Path.of("worlds", "lobby");
AnvilDiagnostics shared = new AnvilDiagnostics();
List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

// The delegate gets the same diagnostics as the loader. Otherwise it counts past the
// closing line, which reads only the loader's own instance.
PaletteEntryResolver blocks = new PaletteEntryResolver() {

    private final PaletteEntryResolver vanilla = new BlockPaletteResolver(shared);

    @Override
    public int toId(String name, CompoundBinaryTag properties) {
        if ("examplemod:reinforced_stone".equals(name)) {
            return Block.DEEPSLATE.stateId();
        }
        return this.vanilla.toId(name, properties);
    }

    @Override
    public CompoundBinaryTag toEntry(int id) {
        return this.vanilla.toEntry(id);
    }
};

FalcoAnvilLoader.Builder template = FalcoAnvilLoader.builder()
        .diagnostics(shared)
        .blockResolver(blocks)
        .biomeResolver(new BiomePaletteResolver(shared))
        .compressionLevel(ChunkCompression.SMALLEST_LEVEL)
        .saveParallelism(4)
        .openRegionLimit(32)
        .exceptionHandler(failures::add);

FalcoAnvilLoader overworld = template.build(worldRoot, DimensionType.OVERWORLD.key());
FalcoAnvilLoader nether = template.build(worldRoot, DimensionType.THE_NETHER.key());

// The resolution falls back to <world>/region as soon as the dimension directory is missing.
// Two dimensions of the same world must therefore never land on the same directory —
// that is what the two reading getters are for.
if (overworld.regionDirectory().equals(nether.regionDirectory())) {
    throw new IllegalStateException(
            "Both dimensions resolved to " + overworld.regionDirectory()
                    + "; the world is still in the layout without dimension directories");
}

overworldInstance.setChunkLoader(overworld);
netherInstance.setChunkLoader(nether);
// Both loaders are AutoCloseable (:618) and live as long as their instance. Who closes
// them, and when relative to saving, is decided by section 3.3.
```

Every existing method called here is evidenced: `AnvilDiagnostics()` (`AnvilDiagnostics.java:77`),
`BlockPaletteResolver(AnvilDiagnostics)` (`BlockPaletteResolver.java:49`),
`BiomePaletteResolver(AnvilDiagnostics)` (`BiomePaletteResolver.java:55`),
`PaletteEntryResolver.toId/toEntry` (`PaletteEntryResolver.java:42`, `:50`),
`ChunkCompression.SMALLEST_LEVEL` (`ChunkCompression.java:83`), `regionDirectory()`
(`FalcoAnvilLoader.java:591`). From Minestom: `Block.DEEPSLATE` (`Blocks.java:2312`) and
`Block.stateId()` (`Block.java:256`); `DimensionType.OVERWORLD` and `DimensionType.THE_NETHER` are
`RegistryKey<DimensionType>` (`DimensionTypes.java:12`, `:18`), and `key()` comes from `Keyed`, which
`RegistryKey` extends (`RegistryKey.java:15`) — the same combination the quick start already uses
(`README.md:92`).

### 3.2 falco-light

#### 3.2.1 First, because this is not a matter of style: `dirty` is never fully drained

> **Fixed in `1f72962` (#14).** `compute` now drops the entries of its area whose chunk the instance
> no longer holds, conditional on the recorded revision. Three tests pin it: a marked coordinate that
> never had a chunk, a chunk unloaded between the mark and the pass, and a still-loaded chunk as the
> guard. The analysis below stands as the description of what was wrong; two details of the **plan**
> did not survive contact with the code:
>
> - The proposed package-private `ChunkLightArea.remove(ChunkArea)` was **not** needed.
>   `computeIncrementally` already drops the kept light when it finds no chunk
>   (`ChunkLightArea.java:378-386`), so a second removal would only have been a second place to keep
>   in sync. The fix touches one file, not two.
> - The estimate of "5 lines inside the existing `try`" held; the table below overstates the work by
>   one row.
>
> The same pull request made `defaultExecutor()` public, so section 2.9.3 measure 1 is done as well.

This is a defect, not a design argument, and it is larger than revision 1 described it.

**Mechanism.** `markChanged(Instance,int,int)` and `markChanged(Instance,int,int,int,int,int)` always
mark the 3×3 around the changed chunk through `markNeighbourhood` (`ChunkLightScheduler.java:306-312`)
— **without checking whether the instance holds a chunk there at all**. An entry is removed from
`dirty` (`:111`) in exactly one place: `:423`, and only for positions taken from `written`. `written`
comes from `ChunkLightArea.write` (`:522-553`) and contains a position only if `entries` has an entry
for it; `read` skips every position for which `instance.getChunk(...)` returns null
(`ChunkLightArea.java:378-386`). A coordinate without a loaded chunk can therefore never end up in
`written` and stays in `dirty` **permanently**.

**The path to it is the ordinary one.** `FalcoLightingChunk.onLoad()` calls
`markChanged(this.instance, this.chunkX, this.chunkZ)` (`FalcoLightingChunk.java:147`). Every chunk
that is loaded thereby immediately puts up to eight neighbour coordinates into `dirty`. For a
preloaded lobby of 4×4 chunks those are the 20 positions of the surrounding ring — permanently,
without a single chunk ever having been unloaded.

**The cost is not only memory.** `onTick` (`:361-365`) takes **every** position out of
`dirty.keySet()` on every tick, `ChunkArea.group` forms areas from them, and `submit` hands them to
the executor. For a ghost area the chunk itself is cheap (skipped), but `read` additionally collects
the ring (`ChunkLightArea.java:363-369`) — and that is where the loaded chunks are. For each of them
`this.service.opacityOf(chunk)` runs unconditionally (`:388`), that is a full `readStates` across all
sections (`ChunkLightService.java:402-418`, one `int[4096]` per section) plus `SectionOpacity.of` —
exactly the step that `ChunkLightService.java:118-124` itself calls "the expensive part of the whole
operation". After that `exchangeUntilSettled` runs over those entries, a second time when sky light
is active (`ChunkLightScheduler.java:418-420`). This repeats **every tick, permanently, in a world in
which nothing changes**. Only when a ghost area has no loaded ring neighbour at all does the
`entries.isEmpty()` short circuit take effect (`ChunkLightArea.java:330-332`).

The unloaded chunk from revision 1 is the same case, only rarer: `InstanceContainer.unloadChunk` calls
`chunk.unload()` (`InstanceContainer.java:282`), after which `instance.getChunk(...)` returns null,
and a still-open `dirty` mark is never cleared again.

**On the two other structures that revision 1 and the critique counted along with it:**

- `ChunkLightArea.kept` (`:146`, `:171-177`) is an LRU `LinkedHashMap` with `removeEldestEntry`
  against `maxCachedChunks` — so it is **capped**, not a leak. `ChunkLightArea.forget(position)`
  (`:214-220`) only clears the content (`Kept.drop()`, `:645-649`) and leaves the map entry standing;
  it keeps occupying a cache slot. Beyond that, `read` already removes the entry today as soon as a
  position without a chunk occurs in a pass (`:380-385`). That heals itself — but only as long as the
  position is still in `dirty`, which after the fix is no longer the case. That is why the fix needs
  a real removal, not `forget`.
- `inFlight` (`:112`) **does have a removal path**: `release(group)` (`:462-466`) runs in the
  `finally` of `compute` (`:428-430`) and in the `catch` of `submit` (`:391`), so in every case. The
  critique's point does not hold here. The only gap is an `Executor` that accepts a task and silently
  discards it without throwing — then the marks are left behind and the affected chunks never get
  light again. That is a different risk and belongs in the javadoc of the `executor` slot.

**The fix, sized.**

| Place | Change | Size |
| --- | --- | --- |
| `ChunkLightArea` | new **package-private** method `void remove(ChunkArea position)` with the body `this.kept.remove(position);` — the same statement already stands at `:383` | 1 statement + javadoc |
| `ChunkLightScheduler.compute` | after the `written` loop (`:422-425`) a second loop over `group`: where `instance.getChunk(position.x(), position.z())` is null, `this.dirty.remove(position, recorded.get(position))` and `this.area.remove(position)` | 5 lines inside the existing `try` |
| `ChunkLightSchedulerTest` | two tests: a marked coordinate without a chunk disappears after one pass; a marked chunk that is unloaded before the pass disappears as well. Both need only `Env#createEmptyInstance` and the `DIRECT` executor, which are already there (`:40`, `:46`, `:68`) | 2 tests |
| **Public API** | **none** | 0 |

Three properties make this safe, and all three are evidenced: the conditional form
`remove(key, value)` (the same as `:423`) leaves a mark standing that arrived during the pass; a
coordinate that is loaded again reports back by itself through `FalcoLightingChunk.onLoad()`
(`:145-148`); and `inFlight` stays untouched, because removing the in-flight mark would let the next
tick claim the same position a second time — and two threads over overlapping areas are explicitly
not guarded against (`ChunkLightArea.java:83-86`).

**Optional, not necessary:** a public `ChunkLightScheduler.forget(int chunkX, int chunkZ)` that
clears `dirty` and `area.remove` immediately instead of waiting for the next pass. It has exactly two
justifications — determinism in tests and a consumer that removes chunks past
`InstanceContainer.unloadChunk` — and for the reason above it likewise does not clear `inFlight`.
Whoever takes it hangs it on an `unload()` override in `FalcoLightingChunk`; `Chunk#unload()` is
`protected` (`Chunk.java:308`), so it is reachable through the same inheritance as the already
overridden `onLoad()` (`Chunk.java:273`, `FalcoLightingChunk.java:144-148`). That is the reason the
event node from revision 1 is not needed: the chunk learns of its own unloading without any `EventNode`,
without `MinecraftServer.process()` and for every kind of `Instance`.

What the route through `InstanceChunkUnloadEvent` **cannot** do, by contrast: it fires only for
chunks that were loaded. The majority of the ghost entries come from coordinates that never had a
chunk object. So the proposal from revision 1 was not merely not compilable — it would not have solved
the problem even if `scheduler.forget(int,int)` had existed.

#### 3.2.2 New types

Three, all nested, none of them a new top-level name:

1. `ChunkLightScheduler.Builder` — final, nested.
2. `ChunkLightScheduler.SkyLight` — enum with three constants (section 3.2.4).
3. `MinestomBlockLightSource.Overrides` — final, nested (section 3.2.5).

`ChunkLightService` keeps both of its constructors (`:89`, `:98`) and gets no builder — it has
exactly one field (`:84`). `ChunkLightArea` gets no facade: its `Predicate<ChunkArea> wanted` /
`ToLongFunction<ChunkArea> revision` pairs (`:276`, `:301`) are two mutually exclusive parameters of
a private `run(...)` (`:320-335`), that is, a state machine as a nullable pair; that belongs behind
`@ApiStatus.Internal`, not in a builder. `ChunkArea` is already a published record
(`ChunkArea.java:49`) and is reused in the callback signatures instead of inventing a new type.

Dropped relative to revision 1: `LightKind` and `FalcoLight` (together with `install(Instance)`, see
section 3.2.3). In addition, independent of the builder and taken over from section 2.9.3:
`defaultExecutor()` (`:168`) becomes `public` — one method, one javadoc block, zero new types.

#### 3.2.3 Slot table

The entry point is `ChunkLightScheduler.builder(ChunkLightService service)`; the service is the only
required parameter and therefore stands in the factory, not in a setter (P2). The terminal method is
`build()`.

| Slot | Signature | Default when not set | Reachable today via | Why a slot |
| --- | --- | --- | --- | --- |
| Executor | `executor(Executor)` | `defaultExecutor()` (`ChunkLightScheduler.java:168-179`) | 3- and 4-parameter constructor (`:152`, `:135`) | Only settable together with `maxAreaSize`; two `int` next to each other in the 4-parameter constructor are section 1.2(b) |
| Area size | `maxAreaSize(int)` | `DEFAULT_MAX_AREA_SIZE` = 16 (`:90`) | as above | ditto; today throws `IllegalArgumentException` for `< 1` (`:136-138`), and that stays |
| Kept chunks | `maxCachedChunks(int)` | `ChunkLightArea.DEFAULT_MAX_CACHED_CHUNKS` = 128 (`ChunkLightArea.java:127`) | **only** through the 4-parameter constructor (`:135`), so only together with executor and area size | The value from section 1.2(a): not reachable without the other two. It does not live in the scheduler but is passed through to `new ChunkLightArea(service, maxCachedChunks)` (`:139`) |
| Sky light | `skyLight(SkyLight)` | `SkyLight.FROM_DIMENSION` | not at all — today hard-wired to `instance.getCachedDimensionType().hasSkylight()` (`:418`) | section 3.2.4 |
| Failure sink | `onFailure(Consumer<Throwable>)` | `MinecraftServer.getExceptionManager()::handleException`, exactly today's `report` (`:473-475`) | not at all | The only point at which the scheduler hangs statically off the running server; called from `submit:392` and `compute:427` |
| Completion observer | `onAreaCompleted(BiConsumer<List<ChunkArea>, List<ChunkArea>>)` — group, then written chunks | no-op | not at all; the only things observable today are `fullPropagations()` (`:324`) and `isDirty(x,z)` (`:502`) | Needs no new type, because `ChunkArea` is published. **Without a duration parameter**: whoever supplies the `Executor` wraps the `Runnable` themselves and measures the same time; a third parameter would cost a new functional interface |

Explicitly **not** a slot:

- `install(Instance)` / `setChunkSupplier`. The line `instance.setChunkSupplier(scheduler.supplier())`
  (`Instance.java:371`, `ChunkLightScheduler.java:197`) stays with the caller. It is one line, it is
  in the javadoc of `supplier()` (`:186-189`), and a terminal method that swallows it buys nothing in
  exchange for the whole setup path needing a real `Instance` with a server process from then on
  (section 2.9.6). The defect that `install` was also meant to heal is fixed in section 3.2.1 without
  any API change.
- The propagation limits stay `private static final`: `NEIGHBOURHOOD_RADIUS = 1`
  (`ChunkLightService.java:70`), `MAX_EXCHANGE_ROUNDS = 16` (`:82`), `AFFECTED_RADIUS = 1`
  (`ChunkLightScheduler.java:105`). They are grounded in physics — level 15 does not survive a second
  chunk — and do not belong in the API.
- `MAX_REPLAYED_CHANGES = 64` (`ChunkLightArea.java:138`) is, by its own comment, world-dependent and
  therefore the only legitimate candidate for a further slot. It sits in `ChunkLightArea`, not in the
  scheduler, and would need the same pass-through path as `maxCachedChunks`. Deferred, section 6,
  question 12.

#### 3.2.4 `kinds` is dropped, `skyLight` gets a defined three-state

`kinds(LightKind...)` is beyond saving for three reasons, and all three are in the code:

1. **`kinds(SKY)` on its own would be a buildable but unusable configuration.** Only the block-light
   pass yields `written` (`ChunkLightScheduler.java:416`); the return value of the sky pass is
   discarded (`:419`), and entries disappear from `dirty` only via `written` (`:422-425`). A
   scheduler without a block-light pass never clears its `dirty` set and recomputes every marked
   chunk on every tick. A configuration shape that makes this state representable is built wrong.
2. **An enum set suggests a symmetry that does not exist.** The two passes are not of equal rank: one
   governs the lifecycle of the `dirty` set, the other does not.
3. **There was no default.** `{BLOCK}` would have silently darkened every overworld; "derived from
   the dimension" is not an element of an enum set.

Replacement: a single slot with three named states. A `boolean` cannot express this — it lacks
exactly the value "as before".

| Constant | Behaviour in `compute` | Use case |
| --- | --- | --- |
| `FROM_DIMENSION` (default) | still asks `instance.getCachedDimensionType().hasSkylight()`, unchanged **per pass** | every configuration in use today; the switch is behaviour-neutral |
| `DISABLED` | skips the sky pass even when the dimension carries sky light | the real case: a lobby under a closed roof on an overworld dimension. Saves the second `computeIncrementally` run entirely |
| `ENABLED` | forces the sky pass even when the dimension carries none | the mirror case; a custom dimension with `hasSkylight() == false` that is nonetheless rendered open (`DimensionType.java:79`, `:184`) |

Decisive against the critique's objection: what is chosen at `build()` is only the **question**, not
the answer. `FROM_DIMENSION` still reads `getCachedDimensionType()` (`Instance.java:467`) from the
instance on every pass — nothing is frozen that is decided per pass today.

Two consequences that belong in the javadoc, because they would otherwise surprise:

- The **block-light pass stays unconditional**. There is no slot to turn it off, because it carries
  the `dirty` set. Making it switchable would mean binding `written` to whichever pass is running —
  a change to the loop `:416-425`, not to its configuration (section 6, question 6).
- `DISABLED` in a dimension with sky light still collects sky positions in `Changes`
  (`ChunkLightArea.java:692-698`) that are never picked up. That is harmless and capped: at
  `MAX_REPLAYED_CHANGES = 64` the counter tips over to `skyUnknown` and stops growing (`:692-694`),
  so at most 64 `int` per chunk. Exactly this state already occurs today in every dimension without
  sky light — `DISABLED` creates no new class of residue.

#### 3.2.5 The `BlockLightSource` helper does not fit the type system this way — corrected version

The objection is justified and revision 1 does not even name it. `BlockLightSource` knows exactly two
methods, both over `int stateId`: `emission(int)` (`BlockLightSource.java:27`) and
`blocksFace(int, BlockFace)` (`:41`). A `Block` in Minestom is **one state**, not a block type:
`Block#stateId()` (`Block.java:247`) returns exactly one number, and `Block.BARRIER`
(`Blocks.java:1058`) is the default state. `withEmission(Block.BARRIER, 15)` would have left all
remaining states of the same barrier at the registry value — without consequence for a barrier,
silently wrong for every block with properties (slabs, stairs, torches on a wall).

Three corrections:

1. **Expansion instead of a single state.** The `Block`-typed form resolves through
   `Block#possibleStates()` (`Block.java:225`; `BlockImpl.java:240-242` returns all states of the
   same block id) and enters every state id into the override table. Beside it stands the exact form
   over `int stateId` for callers who mean exactly one state. That makes the difference visible in
   the name instead of hiding it.
2. **`blocksFace` belongs with it.** Otherwise the helper resolves only half of the asymmetry cited
   as its motivation: anyone who wants to make a face opaque would still have to rebuild the whole
   interface including its registry binding (`MinestomBlockLightSource.java:51`, `:64`). The face is
   named through **falco's** `BlockFace` (`BlockFace.java:22-52`: `BOTTOM, TOP, NORTH, SOUTH, WEST,
   EAST`), not through Minestom's enum of the same name — it is not without reason that
   `MinestomBlockLightSource` imports Minestom's version fully qualified (`:32-33`, `:74-76`). An
   example that mixes `Block` from Minestom and `BlockFace` from falco in the same line must name
   this trap in the javadoc.
3. **It sits in `MinestomBlockLightSource`, not in `BlockLightSource`.** A `Block`-typed factory on
   the interface would pull the registry dependency into exactly the type whose documented purpose is
   its absence (`BlockLightSource.java:7-12`, `package-info.java:12-16`: "`MinestomBlockLightSource`
   is the only type here that knows about Minestom"). So: `MinestomBlockLightSource.overriding()`
   returns a nested `Overrides` builder whose `build()` returns a `BlockLightSource` that puts a
   state-id table in front and delegates everything else to a `MinestomBlockLightSource`.

The limit that remains, and that likewise belongs in the javadoc: the interface knows only `stateId`
— no position, no chunk, no biome. Position-dependent light sources are in principle not expressible
here, and that is not an oversight but the condition under which
`SectionOpacity.of(int[], BlockLightSource)` (`ChunkLightService.java:134`) asks once per section
instead of once per block.

#### 3.2.6 The example

All existing calls have been checked against what is actually declared:
`ChunkLightService(BlockLightSource)` (`ChunkLightService.java:98`),
`ChunkLightScheduler.DEFAULT_MAX_AREA_SIZE` (`ChunkLightScheduler.java:90`),
`ChunkLightScheduler#supplier()` (`:197`), `Instance#setChunkSupplier(ChunkSupplier)`
(`Instance.java:371`), `Block.BARRIER` (`Blocks.java:1058`), `Block#possibleStates()`
(`Block.java:225`), `BlockFace.TOP` (`BlockFace.java:32`). New and proposed in this section are
exclusively: `ChunkLightScheduler.builder`, the six slots, `SkyLight` and
`MinestomBlockLightSource.overriding()`.

```java
// A lobby under a closed roof: the sky-light pass is pure compute time, the executor gets
// a name for the thread dump, one block emits differently from the registry, and the top
// face of a barrier stops light.
Executor lightPool = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("falco-light-", 0).factory());

// BlockFace here is net.onelitefeather.falco.light.BlockFace, not the Minestom enum of
// the same name. emitting(Block, ...) covers every state of the block, not just the default.
BlockLightSource sources = MinestomBlockLightSource.overriding()
        .emitting(Block.BARRIER, 15)
        .blocking(Block.BARRIER, BlockFace.TOP)
        .build();

ChunkLightScheduler scheduler = ChunkLightScheduler.builder(new ChunkLightService(sources))
        .executor(lightPool)
        .maxAreaSize(ChunkLightScheduler.DEFAULT_MAX_AREA_SIZE)
        .maxCachedChunks(512)                       // today reachable only together with the two lines above
        .skyLight(SkyLight.DISABLED)                // no sky pass, although the dimension carries sky light
        .onFailure(throwable -> LOGGER.error("light pass failed", throwable))
        .build();

// Stays with the caller: one line, and it needs no terminal method.
instance.setChunkSupplier(scheduler.supplier());
```

The caller closes the `lightPool` themselves — the builder accepts it but does not take ownership of
it. That has to be in the javadoc of the slot, because the alternative (`build()` remembering whether
it built the executor itself) is exactly the kind of hidden ownership that P3 argues against.

#### 3.2.7 Unresolved

- `markDirty` (`:225`), `markChanged(Instance,int,int)` (`:247`) and
  `markChanged(Instance,int,int,int,int,int)` (`:276`) differ markedly in semantics and cost, and
  barely in their names; according to the class documentation (`:213-218`) `markDirty` is the legacy
  one, and it is the only one of the three that does **not** call `markNeighbourhood`. That is a
  deprecation decision, not a design question (section 6, question 7).
- There is no way to request a computation and wait for its result — no `CompletableFuture`, no
  promise as to when a marked chunk is finished. A builder changes nothing about that.
- An `Executor` that accepts tasks and silently discards them freezes the chunks of the affected area
  permanently (`inFlight` is cleared only in `compute`/`submit`, `:428-430`, `:391`). The `executor`
  slot must name this; it cannot be caught.

### 3.3 falco-instance

**New types.** Exactly one: `FalcoInstance.Builder` (nested, final). No new top-level type, no handle
type, no supplier type of our own — `FalcoWorld` and `FalcoChunkSupplier` from revision 1 both fall away
(the supplier type because section 4.2 gives up the class check on `FalcoChunk`, and a supplier that
enforces `FalcoChunk` would be wrong after that). The three existing constructors (`:225`, `:237`,
`:255`) remain. Added to them are two new methods on `FalcoInstance` itself: the static factory
`builder(RegistryKey<DimensionType>)` and `shutdown(InstanceManager)`.

A preliminary note that section 2.9.5 explicitly adopts: the three constructors are **not** a
telescoping constructor in the sense of section 1.2(b) — their parameters have different types
throughout. The builder is not justified here by parameter confusion, but by the terminal method and
by three things that do not exist at all today: the chunk lifecycle (section 4.2), ownership of the
loader and the save decision at shutdown.

```java
FalcoAnvilLoader loader = new FalcoAnvilLoader(Path.of("worlds", "lobby"), DimensionType.OVERWORLD.key());

FalcoInstance instance = FalcoInstance.builder(DimensionType.OVERWORLD)
        .uuid(UUID.randomUUID())                    // optional, default randomUUID()
        .registries(MinecraftServer.process())      // optional, default MinecraftServer.process()
        .dimensionName(Key.key("lobby", "arena"))   // optional, default dimensionType.key()
        .chunkLoader(loader)                        // optional, default ChunkLoader.noop()
        .generator(null)                            // optional, runs only for chunks without a loader entry
        .chunkSupplier(FalcoChunk::new)             // optional, default FalcoChunk::new
        .autoChunkLoad(true)
        .ownsLoader(true)                           // default false
        .saveOnShutdown(true)                       // default true
        .registerAndShutdownWith(MinecraftServer.getInstanceManager(),
                                 MinecraftServer.getSchedulerManager());
```

Every existing method called here has been checked: `new FalcoAnvilLoader(Path, Key)`
(`FalcoAnvilLoader.java:126`), `FalcoChunk(Instance, int, int)` (`FalcoChunk.java:59`, matches
`ChunkSupplier#createChunk`), `MinecraftServer.getSchedulerManager()` (`MinecraftServer.java:187`).

#### The lifecycle semantics

This is the place where revision 1 contradicted itself (`section 3.3` said `close()` does not save,
`section 4.3` said it does), and the only one in the whole document where a wrong design costs data.
The ruling is:

> **`shutdown(InstanceManager)` saves, and it saves first.** The order is
> **(1)** `saveChunksToStorage().join()`, if `saveOnShutdown` is set, **(2)**
> `unregister(instanceManager)`, **(3)** a check for chunks left behind, **(4)** `close()` on the
> loader, if `ownsLoader` is set.

This order is not chosen, it is forced — at both ends:

**Saving has to come before `unregister`, otherwise it saves nothing and reports success.**
`saveChunksToStorage()` (`:768`) takes a snapshot of `this.chunks.values()` (`:770`). `unregister`
runs over `List.copyOf(this.chunks.values())` and calls `unloadChunk` (`:300`), and `unloadChunk`
removes the chunk from exactly that map (`:731`). A save after the unregister sees an empty map,
hands `saveChunks` an empty collection and comes back with a successfully completed
`CompletableFuture`. No error, no log, no file — the most expensive silent success this API could
build.

**And `unloadChunk` does not save.** `FalcoInstance.unloadChunk` passes the chunk on to
`this.chunkLoader.unloadChunk(falcoChunk)` (`:742`); `FalcoAnvilLoader.unloadChunk` (`:447-468`)
only decrements its region references and closes the file where applicable (`:465-467`). It writes
nothing. The unload path is the one that throws the data away — which is why saving has to come
strictly before it.

**Saving has to come before `close()`, otherwise it throws.** `saveChunk` and `saveChunks` both
begin with `ensureOpen()` (`FalcoAnvilLoader.java:353`, `:391`), and `ensureOpen` throws
`IllegalStateException` on a closed loader (`:537-543`).

This confirms the order in the README (`README.md:107-114`) — it is correct, and it is the reason
section 1.1 names forgetting it as the mistake that only shows up at the next server start.

**How data loss becomes impossible instead of unlikely.** Four mechanisms, each checkable on its
own:

1. **There is no longer a statement that can be forgotten.** Today there are seven independent
   statements plus a shutdown task; the shutdown task is the only one whose absence shows up days
   later. After the rework, saving is no longer a call made by the user but part of `shutdown(...)`,
   and `shutdown` is registered as a shutdown task by the terminal method
   `registerAndShutdownWith(...)` — in the same expression in which the instance first becomes
   reachable at all. The path "instance built, shutdown forgotten" no longer exists, because both
   are one statement.
2. **The default is the loss-free side.** `saveOnShutdown` is `true`. The asymmetry is unambiguous:
   the price of a superfluous save is time and a freshly written region file, the price of a missing
   save is a session's worth of work. The opposite case exists in reality and is evidenced in the
   repository — the demo closes the loader without saving (`DemoServer.java:414-420`), because its
   world is only read. For that case `saveOnShutdown(false)` is a visible, named call and not an
   omission.
3. **A failed save aborts instead of carrying on.** If `saveChunksToStorage().join()` throws,
   neither the unregister nor the closing of the loader happens. The chunks stay in memory, and a
   second call can succeed — `unregister` is explicitly callable more than once (`:274-277`), and
   `close()` on the loader is idempotent (`FalcoAnvilLoader.java:619-621`). Today the README does
   the opposite: `join()` throws inside a shutdown task, and what comes after that is decided by the
   runnable chain.
4. **The silent leak in `unregister` becomes visible.** `unregister` gives up after
   `UNREGISTER_PASSES = 4` passes and only logs a warning (`:146`, `:309-310`). A `shutdown` built on
   top of it would inherit that silent giving up — revision 1 noted this as an objection and gave no
   answer. The answer is cheap and needs no change to `unregister`: `getChunks()` is public
   (`:751-753`), so `shutdown` checks again after the unregister and reports the chunks left behind
   as an error. That makes the leak detectable by a caller for the first time instead of merely
   logged.

The body, with checked calls only:

```java
public void shutdown(InstanceManager instanceManager) throws IOException {
    if (this.saveOnShutdown) saveChunksToStorage().join();
    unregister(instanceManager);
    final int leaked = getChunks().size();

    IOException loaderFailure = null;
    if (this.ownsLoader && this.chunkLoader instanceof AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (IOException exception) {
            loaderFailure = exception;
        } catch (Exception exception) {
            loaderFailure = new IOException("the chunk loader of the instance " + getUuid()
                    + " did not close cleanly", exception);
        }
    }
    if (leaked > 0) {
        throw new FalcoInstanceException(leaked + " chunks of the instance " + getUuid()
                + " survived the unregister; see the warning above", loaderFailure);
    }
    if (loaderFailure != null) throw loaderFailure;
}
```

`ChunkLoader` is not an `AutoCloseable` (`ChunkLoader.java:16`), `FalcoAnvilLoader` implements both
side by side (`FalcoAnvilLoader.java:82`) — hence the type check instead of a cast.
`FalcoInstanceException extends RuntimeException` (`FalcoInstanceException.java:33`), the
two-argument constructor exists (`:50`).

#### The five decisions and their price

1. **`chunkLoader(...)` and `generator(...)` stay two independent slots.** The precedence loader →
   generator → empty (`:90-96`) is not an either-or relation but an order, and it is already
   implemented correctly in the code (`completeLoad:593-597`, `createChunk:667-669`). The test that
   revision 1 cites as evidence for the opposite only pins "the generator does not run when the loader
   delivered the chunk" (`FalcoInstanceGeneratorTest.java:106-119`). Three mutually exclusive
   factory methods (`fromLoader`/`fromGenerator`/`empty`) would therefore have removed a working and
   sensible combination — a loader with a generator for unknown chunks — from the API. **The price
   of this decision:** the precedence still lives only in the javadoc. The builder's gain here lies
   solely in both values standing in one place before the instance becomes reachable.
2. **`ownsLoader(boolean)`, default `false`.** A `FalcoAnvilLoader` belongs to a world and a
   dimension (`FalcoAnvilLoader.java:126`), not to an instance; it can be shared. The default is
   today's behaviour — `unregister` does not touch the loader. **Price:** whoever hands the loader
   to the builder and forgets `ownsLoader` gets exactly today's state, that is, an open handle after
   the shutdown. That is not data loss (the save happened in step 1), but it is the one place where
   a forgotten slot still costs something.
3. **`register(...)` moves the point in time of `InstanceRegisterEvent`.** Today the tests set
   `setGenerator`/`setChunkSupplier` after `registerInstance` (`FalcoInstanceTest.java:48-52`), and
   the event fires in `InstanceManager.java:167`. With the builder everything is set beforehand.
   Listeners that do not yet expect a generator at register time behave differently. **That is a
   behavioural change, not a pure addition.**
4. **Two terminal methods, because `MinecraftServer.start(...)` returns.** `start` creates a
   `TickSchedulerThread` and returns (`MinecraftServer.java:393-397`). A `try-with-resources` around
   `server.start(...)` would save the world, unregister it and close the loader while the server is
   in the middle of accepting players. Hence:
   - `register(InstanceManager)` — registers, returns the instance, hooks nothing in. The caller
     calls `shutdown(...)` itself and gets the exception.
   - `registerAndShutdownWith(InstanceManager, SchedulerManager)` — additionally `buildShutdownTask`
     (`SchedulerManager.java:36`). This is the form that the README and the demo both build by hand
     today (`README.md:107`, `DemoServer.java:153`), including the reason the demo names in the
     comment at `:150-152`: Minestom runs shutdown tasks on a signal as well.

   **Price:** a shutdown task is a `Runnable` (`SchedulerManager.java:36`) and is worked off through
   `shutdownTasks.drain(Runnable::run)` (`:32-34`) — a thrown exception would cut off the remaining
   tasks. The hooked-in path therefore logs at ERROR and does not throw, the manual one throws. The
   two terminal methods therefore differ not only in convenience but in error reporting, and that
   belongs in their javadoc.
5. **`registries(Registries)`, and `process(ServerProcess)` is not offered separately — because it
   would be the same thing.** `public interface ServerProcess extends Registries, Snapshotable`
   (`ServerProcess.java:30`); the example above hands `MinecraftServer.process()` to
   `registries(...)`. The distinction drawn in revision 1 does not exist. What remains is the honest
   limit: the registries injection is only half carried through,
   `MinecraftServer.process().dispatcher().createPartition` (`:643`), `.deletePartition` (`:733`)
   and `MinecraftServer.getBlockManager()` (`:432`, `:491`) reach out globally. That belongs in the
   javadoc of the slot, not in a second slot.

#### What the builder cannot fix

`chunkSupplier` (`:211`) and `chunkLoader` (`:213`) are not volatile, yet they are read in
`retrieveChunk` and passed on to a virtual thread (`:564-571`). A builder that merely wraps the
setters more elegantly preserves the data race; making them final breaks
`setChunkLoader`/`setChunkSupplier`. **This paragraph is superseded: `e45dc1e` made both fields
`volatile` (now `:222`, `:233`), so the race is fixed and a builder no longer inherits it.** Equally
untouched — and still true — is the asymmetry that the constructor calls
`loadInstance` (`:260`) and `setChunkLoader` does not (`:824-827`, `:831`). See section 6,
question 3 — the recommendation there (`volatile` right away as a standalone fix) is independent of
this sketch and precedes it in the sequence (section 4.3).

---

## 4. The connecting facade

### 4.1 Its own module, unpublished, or a wiki recipe?

Revision 1 claimed that a published `falco-api` of its own followed "necessarily from the module
structure". It does not follow. The only thing that necessarily follows is that a type carrying three
module types in its signature has to see those three modules — which yields a compile dependency, not
a fourth Maven artifact. The option revision 1 does not even name is the one the repository already
lives today: the README is exactly such a recipe.

**The criterion.** A published artifact is a permanent compatibility obligation and four registration
points in the build (`settings.gradle.kts:4-9`, `build.gradle.kts:62`, `:87-97`,
`falco-bom/build.gradle.kts:4-8`; if one is forgotten, the module silently goes unpublished or
unpinned). That obligation can only be justified if:

> The artifact contains behaviour that a consumer could not write in their own code with the same
> means — because it needs access to package-private parts of a module, or because it fixes a defect
> inside a module.

Convenience alone is not enough: a recipe a user copies once costs them twenty lines; an artifact
costs the maintainer every version, every javadoc block under `-Werror` (`build.gradle.kts:35-37`)
and every binary compatibility question (section 6, question 10).

**Applied.** After the blocker fix from section 4.2 (variant D) the facade consists exclusively of
public calls: `FalcoInstance.builder(...)` with its slots, `ChunkLightScheduler.supplier()`
(`ChunkLightScheduler.java:197`), `FalcoLightingChunk.markLoaded/markUnloaded` and
`new FalcoAnvilLoader(...)`. None of it is package-private, none of it fixes a defect. The
criterion is not met.

**Decision: no `falco-api`.** The facade is a wiki recipe plus a runnable version in the unpublished
`falco-demo`. Should it later turn out that the same recipe is copied word for word into three
projects, that is the moment to re-examine the criterion — not before.

The side effect is that the repository's first `api(` edge outside the BOM never comes into existence
and `falco-light` keeps working for an `InstanceContainer` user without `falco-instance` on the
class path — which `FalcoLightingChunk.java:26-34` expressly guarantees.

#### Type balance against P7

| Module | new published top-level types | new published methods |
|---|---|---|
| falco-instance | 0 (`FalcoInstance.Builder` is nested) | `builder(RegistryKey)`, `shutdown(InstanceManager)`, the builder slots |
| falco-light | 0 (`Builder`, `SkyLight`, `Overrides` are nested) | `FalcoLightingChunk.markLoaded()`, `.markUnloaded()` (section 4.2 D), `defaultExecutor()` becomes public |
| falco-anvil | 0 (`FalcoAnvilLoader.Builder` is nested) | the builder slots |
| falco-api | — (does not exist) | — |

Zero new top-level types in all three modules; P7 in its sharpened form (section 2) is met. The
chunk lifecycle from section 4.2 (D) consumes no type, because it hangs off a single builder method
as a pair of `java.util.function.Consumer<Chunk>`. Dropped relative to revision 1: `FalcoWorld`,
`FalcoStack`, `Falco`, `FalcoLitChunk`, `FalcoChunkSupplier`, `LightKind`, `FalcoLight` and the three
sub-builder types — roughly ten types that revision 1 would have introduced against its own P7.

### 4.2 The blocker that has to be solved first

**The finding.** `FalcoInstance` accepts `FalcoChunk` and nothing else (`requireFalcoChunk`,
`FalcoInstance.java:690-695`); `FalcoLightingChunk extends DynamicChunk`
(`FalcoLightingChunk.java:80`). The reason for the check can be named precisely and has nothing to do
with light: at three places (`:608`, `:614`, `:732`) `FalcoInstance` needs the hooks
`Chunk#onLoad()` (`Chunk.java:273`) and `Chunk#unload()` (`Chunk.java:308`), which are `protected`,
and `FalcoChunk` hands them out as `markLoaded()`/`markUnloaded()` (`FalcoChunk.java:87`, `:100`).
Second, `writeBlock` needs the six-parameter `setBlock`, which on `Chunk` is `protected`
(`Chunk.java:99`) and only becomes public on `DynamicChunk` (`DynamicChunk.java:76-79`) — exactly the
second justification that `FalcoChunk.java:27-30` gives for the choice of superclass.

From this follows the actual requirement: **`FalcoInstance` needs a `DynamicChunk` plus a way to
reach its two lifecycle hooks.** Nothing more.

**(A) `FalcoLightingChunk extends FalcoChunk`.** Shortest diff, works immediately. **Price:**
`falco-light` gains an `api(project(":falco-instance"))` edge — the repository's first module edge
outside the BOM (today the only one is `falco-light/build.gradle.kts:19`, `testImplementation`).
Every `falco-light` consumer working on an `InstanceContainer` drags `falco-instance` along.
The layering inverts: the module that by its own javadoc works on *every* instance
(`FalcoLightingChunk.java:31-33`) would depend on the instance module. `FalcoLightingChunk.java:26-34`
expressly argues the opposite.

**(B) A lifecycle interface in `falco-instance`.** `FalcoChunk implements FalcoChunkLifecycle`,
`FalcoLightingChunk` likewise; `requireFalcoChunk` tests for `DynamicChunk` and for the interface.
**Price:** the same module edge as (A), and it cannot be pushed down to `compileOnly` — the JVM
resolves superinterfaces when the class is loaded, and a missing `FalcoChunkLifecycle` would give a
`NoClassDefFoundError` on the first `FalcoLightingChunk`. Second price: the parameter type of
`writeBlock` (`:413`) would have to be `DynamicChunk & FalcoChunkLifecycle`, which Java does not
express outside generic bounds — so either a generic method with an unchecked cast or two separate
`instanceof` at one place.

**(B′) A fourth, tiny module for the interface alone.** Both modules depend on a leaf, neither on the
other. **Price:** a published artifact with two methods of content, four build registration points,
one BOM entry. Not justifiable under the criterion from section 4.1 as long as (D) exists.

**(C) `FalcoLitChunk` in a fourth module — dropped.** The code printed in revision 1 is not
implementable, and the price was set too low:

- `createLightData()` does not exist. The method is
  `protected LightData createLightData(boolean requiredFullChunk)` (`DynamicChunk.java:310`); the real
  code calls `createLightData(false)` (`FalcoLightingChunk.java:88`).
- The `tick` override does **not** simply fall away without replacement. `FalcoInstance` creates a
  tick partition for every published chunk (`FalcoInstance.java:643`), and `ThreadDispatcherImpl`
  ticks a partition that is itself `Tickable` along with it (`ThreadDispatcherImpl.java:140-141`) —
  `Chunk implements Tickable` (`Chunk.java:43`). Without a `tick` override or an `InstanceTickEvent`
  listener nobody calls `ChunkLightScheduler.onTick` (`:352`), and the `dirty` set is never drained.
  Whoever switches to `InstanceTickEvent` instead also has to know that the two sources deliver
  different time bases: `instance.tick(milliStart)` gets milliseconds,
  `dispatcher().updateAndAwait(nanoStart)` nanoseconds (`ServerProcessImpl.java:302-312`), and
  `onTick` deduplicates on exact equality (`ChunkLightScheduler.java:355`). Mixing both sources would
  produce two passes per tick.
- The `copy()` problem has no good solution. `FalcoLitChunk extends FalcoChunk` inherits
  `FalcoChunk.copy` (`FalcoChunk.java:120-128`) and returns a copy without a scheduler. A "proper"
  override that passes the scheduler along would however be a mistake: a copy goes by construction
  into a **different** instance (`InstanceContainer.java:610-618`), and `ChunkLightScheduler.bind`
  throws `IllegalStateException` as soon as the same scheduler sees a second instance (`:483-492`).
  The only correct copy is one without a light binding — which the inherited implementation already
  delivers. **Incidental finding that belongs in the defect log:** `FalcoLightingChunk` does not
  override `copy` at all today and inherits `DynamicChunk.copy` (`DynamicChunk.java:237-243`), so it
  returns a bare `DynamicChunk` and loses light **and** lifecycle hooks. An existing, independent
  defect (section 6, question 13).
- The duplication is more expensive than "roughly 40 lines": what would be duplicated is a documented
  ordering invariant (`FalcoLightingChunk.java:108-114`, `ChunkLightScheduler.java:260-266`) across a
  module boundary, without compile-time coupling — the same class of problem that the same document
  rejects variant (B) for.

**(D) The lifecycle becomes a parameter, not a type — recommendation.** `FalcoInstance` gives up the
class check and accepts any `DynamicChunk`; how such a chunk learns of its loading and unloading is
what the caller states at construction. For that, `falco-light` gets exactly what `FalcoChunk`
already has: two public methods that make the inherited `protected` hooks reachable.

In `falco-light`, without any new dependency and without a new type:

```java
// FalcoLightingChunk — word for word as FalcoChunk.java:87 and :100
public void markLoaded() {
    onLoad();
}

public void markUnloaded() {
    unload();
}
```

`onLoad()` is overridden in this class anyway (`FalcoLightingChunk.java:144-148`), so it still
registers the chunk and its eight neighbours with the scheduler; `unload()` is inherited from `Chunk`
(`Chunk.java:308-310`).

In `falco-instance`, `requireFalcoChunk` (`:690-695`) becomes a check for `DynamicChunk`, and the
three call sites (`:608`, `:614`, `:732`) go through the configured pair:

```java
FalcoInstance instance = FalcoInstance.builder(DimensionType.OVERWORLD)
        .chunkSupplier(lighting.supplier())
        .chunkLifecycle(chunk -> ((FalcoLightingChunk) chunk).markLoaded(),
                        chunk -> ((FalcoLightingChunk) chunk).markUnloaded())
        // ...
```

One slot with two parameters instead of two slots, so that the pair cannot be set halfway.

**The price of (D), in full:**

- The guarantee moves from the type system into a call. Whoever configures a supplier and a pair that
  does not match it gets a `ClassCastException` instead of a `FalcoInstanceException`. The moment
  stays the same — the first loaded chunk — the diagnosis is somewhat worse. Remedy: the default
  lifecycle is today's (`instanceof FalcoChunk`, otherwise `FalcoInstanceException` with the existing
  message).
- **Condition, without which `testAForeignChunkSupplierIsRejected` (`FalcoInstanceTest.java:153-163`)
  goes red.** The type check has to stay at its present **place**, not merely in its present form.
  Today `requireFalcoChunk` throws in `completeLoad` (`FalcoInstance.java:598`) **before**
  `publishChunk` (`:604`), and that is exactly why `assertNull(instance.getChunk(0, 0))` holds. If the
  check moves into the lifecycle call (`:614`), `DynamicChunk::new` passes through beforehand:
  `publishChunk` puts the chunk into `this.chunks` (`:641`) and creates a partition (`:642`) before
  anything throws. The test then fails at the `assertNull` line, not at the exception. (D) is
  therefore only implementable if the default lifecycle still runs its check before `publishChunk` and
  only the *configured* lifecycle replaces it — a condition on the implementation, not an
  impossibility, but it belongs in the ticket.
- The javadoc of `FalcoInstanceException` (`:9-11`) describes "every chunk has to be a `FalcoChunk`"
  as the cause — after (D) that is only the default any more and has to be rewritten.
- `writeBlock` (`:413`) will take `DynamicChunk` in future; the assurance "this chunk is manageable"
  is established at one place instead of being carried by the parameter type.
- `falco-light` gets two more public methods — the only enlargement of the contract surface in this
  section, and it is literally the one `falco-instance` has had since 0.1.0.

**What (D) does not cost:** no module edge, no fourth artifact, no duplication, no third chunk type,
no change to `ChunkLightScheduler`, none to `FalcoAnvilLoader`. And `FalcoInstance` incidentally
becomes usable for every `DynamicChunk` subtype a user writes themselves — today that only works
through `FalcoChunk`.

**Recommendation: (D) now.** (A) remains the option for a later major, should the module edge come
into existence for other reasons anyway; (B), (B′) and (C) are moot after (D).

### 4.3 The entry point

After sections 4.1 and 4.2 there is no `Falco.stack()` type any more. The entry point is the
`FalcoInstance.Builder`, and the "facade" is the recipe that brings the three modules together in one
expression. That is not a demotion: the original purpose — one expression, one lifecycle, no
forgotten shutdown — is met in full, only without a new artifact.

```java
public final class Bootstrap {

    public static void main(String[] args) {
        MinecraftServer server = MinecraftServer.init();

        FalcoAnvilLoader loader = new FalcoAnvilLoader(
                Path.of("worlds", "lobby"), DimensionType.OVERWORLD.key());
        ChunkLightScheduler lighting = new ChunkLightScheduler(new ChunkLightService());

        FalcoInstance instance = FalcoInstance.builder(DimensionType.OVERWORLD)
                .chunkLoader(loader)
                .chunkSupplier(lighting.supplier())
                .chunkLifecycle(chunk -> ((FalcoLightingChunk) chunk).markLoaded(),
                                chunk -> ((FalcoLightingChunk) chunk).markUnloaded())
                .autoChunkLoad(true)
                .ownsLoader(true)
                .registerAndShutdownWith(MinecraftServer.getInstanceManager(),
                                         MinecraftServer.getSchedulerManager());

        MinecraftServer.getGlobalEventHandler().addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(new Pos(0, 64, 0));
        });

        server.start("0.0.0.0", 25565);
    }
}
```

Every existing method in it has been checked: `FalcoAnvilLoader(Path, Key)` (`:126`),
`ChunkLightScheduler(ChunkLightService)` (`:121`), `ChunkLightService()` (`:89`), `supplier()` (`:197`,
returns `FalcoLightingChunk`, `:198`), `MinecraftServer.getSchedulerManager()`
(`MinecraftServer.java:187`), `MinecraftServer.start(String, int)` (`:399`). The remaining lines are
those of today's quick start (`README.md:102-105`, `:116`).

**What changes relative to the quick start.** Seven independent statements plus a hand-written
shutdown task become one expression plus the listener, which has nothing to do with Falco. The three
statements whose order costs data — save, unregister, close — are no longer visible, because they are
no longer selectable (section 3.3). And the stack the demo expressly cannot build today
(`ServerStack.java:28-40`, `:203-205`) becomes buildable.

**The sequence.** In this order, because every step can be signed off on its own:

1. ✅ **Done in `1f72962` (#14).** Two one-liners with no construction decision: `defaultExecutor()`
   becomes `public` (section 2.9.3), and the `dirty` defect from section 3.2.1 is fixed. It cost five
   lines, three tests and no API change — the third test is the guard that keeps the other two
   honest.
2. ✅ **Done in `e45dc1e` (#11)**, before this page was written: `chunkSupplier`/`chunkLoader` to
   `volatile` (section 6, question 3) — a correctness fix, independent of everything here.
3. ✅ **Implemented in [#16](https://github.com/OneLiteFeatherNET/Falco/pull/16).**
   `FalcoAnvilLoader.Builder` and `ChunkLightScheduler.Builder` (sections 3.1, 3.2) — purely additive,
   no behaviour change.
4. ✅ **Implemented in [#16](https://github.com/OneLiteFeatherNET/Falco/pull/16).** Variant (D): two
   methods in `falco-light`, the lifecycle configuration in `falco-instance` — as
   `setChunkLifecycle`, exactly as this step foresaw, still without an instance builder. **From here
   on the full stack runs**, which three tests in `falco-demo` pin down.
5. ✅ **Implemented in [#16](https://github.com/OneLiteFeatherNET/Falco/pull/16).**
   `FalcoInstance.Builder` with `shutdown(InstanceManager)` and the two terminal methods
   (section 3.3).
6. ⚠️ **Partly, and deliberately so** ([#16](https://github.com/OneLiteFeatherNET/Falco/pull/16)).
   The recipe is in `falco-demo` as `FalcoStackIntegrationTest`, the only place that may know all
   three modules. `ServerStack` did **not** get a third entry: the comparison is worth something
   only while the two servers differ in the loader and the chunk type and in nothing else, and a
   third difference would work against its purpose. What did fall away is the claim in the note
   `:203-205` that the combination is impossible — that was true when it was written and is not any
   more.

**What expressly does not exist.** No `Falco` singleton, no `FalcoStack`, no `FalcoWorld`, no
`AutoCloseable`. The last is the most important of these deletions: `try-with-resources` around a
server run is wrong, because `MinecraftServer.start(...)` returns while the server is running
(`MinecraftServer.java:393-397`) — the block would save the world and unregister it as soon as the
server is up. The demo already has this right, and it writes down the reason
(`DemoServer.java:150-152`).

**What remains open** (section 6, question 14): whether `FalcoInstance` should ever carry a
`SharedInstance`. `SharedInstance` is typed against `InstanceContainer` throughout Minestom
(`InstanceManager.java:83-87`), the README records the boundary (`README.md:22`), and the compiler
rejects it. A user who wants the Falco loader and Falco light on an `InstanceContainer` — today's
quick start and both demo stacks (`DemoServer.java:155-157`) — needs none of this beyond the module
builders from sections 3.1 and 3.2. That this route stays open unchanged is, after the deletion of
`falco-api`, no longer a concession but the default.

---

## 5. Observer/hooks

### 5.1 What should be observable

The events all already exist as call sites of their own and today end in `LOGGER` lines and
`LongAdder` increments. A push hook is a purely additive layer on top:

| Event | today's site | missing for |
|---|---|---|
| Chunk loaded | `FalcoAnvilLoader.java:316` (`countChunkLoaded`) | load-time metric |
| Chunk saved | `:364` | save throughput |
| Chunk skipped (3 reasons) | `:257`, `:276`, `:292` (the log lines for them at `:258`, `:277`, `:293`) | world diagnostics |
| Load/save error | `:337`, `:370` | a dedicated error sink |
| Region opened / evicted / closed | `:822`, `:512`, `:635` (only `LOGGER.debug`) | cache monitoring |
| Light pass finished | `ChunkLightScheduler.java:422-425` | duration, size, discarded chunks |
| Light pass failed | `:473-475` | counting, logging, rescheduling |
| Light of a chunk written | `LightUpdateAware.onLightUpdated()` (`:38`) | exists, but only on the chunk |

> **Evidence correction.** The three skip reasons are diagnostics calls at `:257`
> (`reportMissingRegionFile`), `:276` (`reportMissingChunkEntry`) and `:292` (`reportPartialChunk`);
> the corresponding log lines follow at `:258`, `:277` and `:293`. The critique's statement ("`:258`,
> `:277`, `:292`") mixes both levels; the table now names the calls and the log lines separately.

What is missing today and has neither pull nor push: the number and size of the areas per tick, the
in-flight count, cache hits and evictions, the depth of the dirty set, the section level at all
(`ChunkLightService.applyLight:157` writes it in a loop without any report).

And: **`TimingChunkLoader`**
(`falco-demo/src/main/java/net/onelitefeather/falco/demo/TimingChunkLoader.java:37`) is a hand-built
decorator around the Minestom interface, whose comment at `:14-18` states why it exists — Minestom
has no event that carries the duration. That is the most open niche. **Unlike in revision 1, it is not
closed by an `onChunkLoaded(x, z, nanos)` slot**: that would need a new functional interface
`(int, int, long)` and hence a published type (section 3.1). Access runs through
`diagnostics(AnvilDiagnostics)` — and whether that is enough depends on whether `AnvilDiagnostics`
loses its `final` (section 6, question 11). As long as it does not, the decorator remains the right
answer.

### 5.2 A custom mechanism or Minestom's EventNode?

**The dividing line stands: instance-scoped lifecycle events over EventNode, everything
finer-grained over custom slots. After the check in section 3.2.1 it carries considerably less than
it did in revision 1, though.**

**What remains of the four EventNode arguments:**

- **`InstanceChunkUnloadEvent` — dropped as a solution.** Revision 1 named it the way to close the
  scheduler's missing unload path. It reaches only chunks that were loaded; the majority of the ghost
  entries in `dirty` come from neighbour coordinates that never had a chunk object (section 3.2.1).
  The fix needs five lines in `compute` and no event. The defect itself stands as a finding and is
  larger than described — not just memory, but recurring compute time per tick.
- **`InstanceTickEvent` — an option, not a free win.** `Instance#tick(long)` fires it exactly once
  per instance tick, while `FalcoLightingChunk#tick` (`:150-154`) calls `scheduler.onTick(instance,
  time)` **per chunk** and the scheduler deduplicates via `lastTick.getAndSet(time) == time`
  (`:355`). A listener saves the CAS operation per chunk per tick and fixes the case that nothing
  happens at all when no chunk of the instance ticks. It is, however, **not a pure replacement**: the
  two sources deliver different time bases — `instance.tick(milliStart)` milliseconds,
  `dispatcher().updateAndAwait(nanoStart)` nanoseconds (`ServerProcessImpl.java:302-312`) — and
  `onTick` deduplicates by exact equality. Both sources mixed give two passes per tick.
  A switch is therefore a separate, measurable rebuild, not a side effect of a fluent API.
- **`InstanceChunkLoadEvent`.** Carries `getChunkX()`/`getChunkZ()`/`getChunk()` and is not
  `protected` — the counterpart to `FalcoLightingChunk#onLoad` (`:144-148`). For a consumer that
  wants to count along, today the only open channel.
- **`Instance#invalidateSection(int, int, int)`** fires `InstanceSectionInvalidateEvent` and is
  called nowhere by Minestom itself; a pure opt-in channel for foreign code with exactly the
  semantics of `markChanged(instance, x, z)`. It lets foreign code that writes past `setBlock` report
  in without importing Falco types. That is the only one of the four points that stands without an
  offsetting cost.

**What still argues against EventNode, and does so for exactly two cases:**

- **`setBlock` stays custom-built.** `InstanceBlockUpdateEvent` is fired only from
  `InstanceContainer#setBlock` or `FalcoInstance#writeBlock` (`:451`), never from `Chunk#setBlock`.
  Batches, generators and loaders write past it. The override is the only position-accurate channel.
- **`LightUpdateAware` stays custom-built.** `Chunk` does not implement `EventHandler`, `EventFilter`
  has no chunk constant, so there is no chunk-scoped node. And even if there were:
  `EventNodeImpl#addChild`/`addListener`/`map` all synchronize on a static
  `GLOBAL_CHILD_LOCK`, and the Minestom documentation explicitly warns against temporary nodes after
  initialisation. A callback per finished chunk computation does not belong in the event system.

**Four pitfalls that belong in the javadoc of the slots:**

1. `instance.eventNode()` is `@ApiStatus.Experimental` and **null** if `MinecraftServer.process()`
   was null at the instance's construction time: `Instance.java:178-184` sets the field only if
   `process != null`, otherwise `null` with the comment "Local nodes require a server process";
   getter `:955`. **Correction to revision 1:** there the null check was justified by Falco building
   instances "against passed-in registries, that is against non-global processes"
   (`FalcoInstance.java:255`). That is a non sequitur — the `registries` parameter plays no role for
   `eventNode`, what decides is solely the global `MinecraftServer.process()`. The check remains
   correct, its reason is a different one: a tool or a test without `MinecraftServer.init()` gets
   `null`, regardless of which registries it passed.
2. Registration belongs in the setup, never in the hot path — because of `GLOBAL_CHILD_LOCK`.
3. Listen instance-scoped rather than globally. `instance.eventNode()` is already a
   `map(this, EventFilter.INSTANCE)` node; a child below it sees only events of this instance.
   `falco-demo` listens globally today (`DemoServer.java:234-253`) — defensible for a demo, not for
   the library.
4. `onLightUpdated` runs on the area's worker thread, never under a chunk lock, and must not block
   (`LightUpdateAware.java:34-36`). The contract still lacks the failure behaviour: Caffeine logs and
   swallows listener exceptions, which is defensible for a cache but not for an observer that is
   supposed to send a packet.

### 5.3 The slot form

For the observation that does not run over EventNode, Caffeine's split by execution context applies.
Falco already has it — only unnamed, and in revision 1 it was hung on a slot that section 3.2 does not
offer (`onLightUpdated` on the builder, plus a duration parameter that would have cost a new
functional interface). Read correctly they are two **different places**, and the difference is the
statement about threading:

- **Synchronous, on the area's compute thread, affecting the result:** `LightUpdateAware.onLightUpdated()`
  (`:38`) — implemented on the chunk, not configured on the scheduler. Whoever blocks here stalls the
  light pass.
- **After the pass, on the configured executor, pure observation:**
  `onAreaCompleted(BiConsumer<List<ChunkArea>, List<ChunkArea>>)` and `onFailure(Consumer<Throwable>)`
  from section 3.2.3. No duration parameter: whoever supplies the `Executor` wraps the `Runnable`
  themselves and measures the same time more precisely.

```java
ChunkLightScheduler scheduler = ChunkLightScheduler.builder(service)
        // after the pass, on the executor: group, then the chunks actually written
        .onAreaCompleted((group, written) -> meter.counter("falco.light.written").increment(written.size()))
        .onFailure(throwable -> LOGGER.error("light pass failed", throwable))
        .build();
```

And a warning sentence that has to stand at every slot that can influence the result: `Light#set`
clears the section's update flag (`ChunkLightService.java:32-34`, `:146-151`). Light that is written
wrong is never corrected by anyone. A custom `BlockLightSource` — including one via
`MinestomBlockLightSource.overriding()` (section 3.2.5) — produces permanently wrong light if it is
wrong. A fluent API that makes such hooks more convenient has to document that at every single slot,
not once centrally.

---

## 6. Open decisions

The recommendations are sequenced against one another; the order of implementation is in section 4.3.
Questions that section 2.9 or sections 3/4 have already decided (construction form per module,
`falco-api`, `copy()`, `install(Instance)`) are not restated here.

**1. Do the existing constructors stay, or are they deprecated?**
Options: (a) both ways permanently side by side; (b) constructors `@Deprecated`, builder as the only
recommended way; (c) remove the constructors.
Affected: `FalcoAnvilLoader.java:126`/`:144` (14 call sites, one of them in production code,
`LoaderKind.java:112`, plus `README.md:92`), `ChunkLightScheduler.java:121`/`:135`/`:152` (27
call sites, one of them in production code, `ServerStack.java:167`), `FalcoInstance.java:225`/`:237`/
`:255` (7, all in tests). So far there is **not a single** `@Deprecated` in the production code of the
three modules.
**Recommendation: (a) throughout.** Revision 1 recommended (b) for the telescoping variants; that is
withdrawn. `@Deprecated` on `FalcoAnvilLoader(Path, Key, int)` would hit not a single site in
production code, the benefit would remain unevidenced, and the price would be introducing the
repository's first `@Deprecated` — plus warnings in 25 test calls of falco-light that gain nothing
from a builder. The question is asked again once the builder has been in the field for a version.

**2. When does the recipe from section 4.3 move into the wiki and `falco-demo`?**
Section 4.1 decided that there is no published fourth module. What is open is the timing: the recipe
only works after step 4 of the sequence (section 4.3), `ServerStack` gets its third entry only then,
and until that point `README.md` keeps showing today's quick start.
**Recommendation: recipe and `ServerStack` entry in the same change as variant (D)**, so that the demo
takes the path the document recommends — otherwise the documentation asserts a stack that our own code
does not build (`ServerStack.java:203-205`).

**3. Do `chunkSupplier` and `chunkLoader` in `FalcoInstance` become final?**
Today non-volatile fields (`:211`, `:213`) with public setters (`:803`, `:831`), read on virtual
threads (`:564-571`) — a data race without happens-before.
Options: (a) final, remove the setters (breaking change, fixes the race); (b) `volatile`, setters
stay (fixes visibility, not the semantics of `setChunkLoader` without `loadInstance`);
(c) status quo.
**Recommendation: (b) immediately and before the instance builder** — step 2 of the sequence. This is
a correctness problem, not an ergonomics problem, and section 2.9.5 is right: a builder that merely
wraps the setters preserves it. (a) at the next major.

**4. Raise `org.jetbrains.annotations` from `compileOnly` to `api`?**
Today `compileOnly` in all three modules (`falco-instance/build.gradle.kts:8`).
Options: (a) status quo; (b) `api(libs.annotations)` in all three modules; (c) switch to JSpecify.
The revision-1 recommendation "(c) only in the new `falco-api`" fell away with the module.
**Recommendation: (b), but only after a measurement.** Two gaps are not established and decide the
question: whether an IDE reads `@Contract` from the bytecode of a jar without the annotation being
resolvable (then (a) is free), and whether JSpecify is managed in `mycelium-bom` (then (c) becomes
assessable). Change nothing before the measurement.

**5. Offer `regionDirectory(Path)` as an explicit override?**
The constructor decides silently between dimension and legacy layout (`:203-211`) and logs that only
because otherwise the mistake cannot be found (comment `:163-166`); `README.md:125-133` devotes a
paragraph of its own to it, and section 3.1 shows that on a legacy world the resolution puts two
dimensions on the same directory.
Options: (a) do not offer it, the reading `regionDirectory()`/`legacyLayout()` stay as they are;
(b) offer `regionDirectory(Path)`; (c) a `sealed` result modelled on
`WorldSearchResult` (`falco-demo/.../WorldSearchResult.java:20`, whose javadoc `:10-13` argues for
exactly this pattern) that names directory, layout and error case.
**Recommendation: (a).** (b) creates a fourth combination (explicit path × `legacyLayout` flag). (c)
is the most elegant idea in the dossier, but `sealed` + record pattern would be a new pattern in a
published artifact. The example in section 3.1 shows that the caller can determine the collision
themselves with the two reading getters — that is enough for now.

**6. Can the block light pass be turned off?**
Section 3.2.4 makes sky light configurable via `SkyLight` and leaves the block light pass
unconditional, because only it delivers `written` (`:416`) and thereby carries the lifecycle of
`dirty` (`:422-425`).
Options: (a) leave it as it is and justify it in the javadoc; (b) bind `written` to whichever pass is
running, clear `dirty` only once all configured passes have written (behaviour change at the loop
`:416-425`, more work per tick); (c) stay with today's `boolean`.
**Recommendation: (a).** The naming gain is there immediately with `SkyLight`, the behaviour question
stays open and is not decided in passing inside an API refactoring. (c) is settled by section 3.2.4.

**7. Deprecate `markDirty`?**
`markDirty` (`:225`) is the only one of the three marking paths that does not call
`markNeighbourhood`; according to the class documentation (`:213-218`) it is the legacy holdover.
Options: (a) keep both; (b) `markDirty` `@Deprecated`; (c) rename to
`markChangedWithoutNeighbours`.
**Recommendation: (b), but only after question 1** — if the repository stays with (a) and introduces
no `@Deprecated` at all, (c) is the more consistent answer. The decision depends on question 1, not
the other way round.

**8. Who owns the executor, and what does a scheduler do whose executor discards tasks?**
`ChunkLightScheduler` has no lifecycle: no `close()`, no shutdown of the default executor
(`:168-179`, virtual threads without a handle). On top of that comes the case named in section 3.2.7:
an `Executor` that accepts a task and silently discards it leaves `inFlight` marks behind (`:391`,
`:428-430`) and freezes the area's chunks permanently.
Options: (a) the executor handed in belongs to the caller, full stop — javadoc on the slot; (b)
`ChunkLightScheduler` gets a `close()` that waits for running passes; (c) additionally a time limit
after which an in-flight mark expires.
**Recommendation: (a) with the builder, (b) as a standalone follow-up PR.** Not (c): a time limit
turns a configuration error into intermittent behaviour.

**9. Budget for the effort.**
Javadoc under `-Werror` (`build.gradle.kts:35-37`), `check dependsOn javadoc` in falco-demo
(`falco-demo/build.gradle.kts:22-24`), plus tests and migration — broken down in full in
section 2.9.1. The multiplier is not the javadoc but that every new construction form comes to stand
*next to* the old one (question 1 recommends (a)) and both ways need tests of their own. Set against
that: the four build registration points for a fourth module fall away without replacement under
section 4.1.
**That is effort, not residual risk — but it is not optional and belongs before the commitment.**

**10. Is a binary compatibility tool introduced?**
Neither japicmp nor revapi nor binary-compatibility-validator is in the build. As long as everything
is `@ApiStatus.Experimental` (`README.md:24-25`), that is covered; as soon as the fluent API is
advertised as **the** recommended entry point, it is not — and with a fluent API every return type is
a binary contract (P6). That the concern exists is shown by `AnvilDiagnostics.java:188-195`:
`reportPartialChunk()` was kept, "so callers written against the first version of this class keep
compiling" — handwork instead of a tool.
**Recommendation: introduce japicmp as soon as the recipe from section 4.3 stands in the wiki as the
recommended way** (question 2), not before.

**11. Does `final` fall on `AnvilDiagnostics`?** *(new)*
`diagnostics(AnvilDiagnostics)` (section 3.1) makes the sink injectable but not replaceable:
`AnvilDiagnostics` is `final` (`:48`), so nobody can slip in a metrics sink — exactly the
observation from section 1.2(e), and the reason why `TimingChunkLoader` (section 5.1) is still needed.
Options: (a) `final` stays, the injection is enough for shared counters; (b) `final` falls, the
`report*`/`count*` methods become overridable; (c) a narrow interface over the counting points,
`AnvilDiagnostics` implements it.
Not established: whether (b) costs measurably on the load path — the counters are `LongAdder` and
`ConcurrentHashMap` (`:90-101`), the calls sit in `loadChunk`/`saveChunk`.
**Recommendation: measure first, then (b) or (c).** Change nothing without a measurement; (a) is the
default until someone really needs the metrics sink.

**12. `MAX_REPLAYED_CHANGES` as a slot?** *(new, deferred from section 3.2.3)*
`ChunkLightArea.java:138`, world-dependent according to its own comment — the only remaining
candidate for a seventh scheduler slot. It sits in `ChunkLightArea` and would need the same
pass-through path as `maxCachedChunks` (`ChunkLightScheduler.java:139`).
**Recommendation: not with the first version.** It can be retrofitted additively (P6), and there is
no measurement showing that 64 is wrong for any real world.

**13. The `copy()` defect of `FalcoLightingChunk`.** *(new, side finding from section 4.2)*
`FalcoLightingChunk` does not override `copy` and inherits `DynamicChunk.copy`
(`DynamicChunk.java:237-243`), so it returns a bare `DynamicChunk` and loses light and lifecycle
hooks. `FalcoChunk` overrides for exactly that reason (`FalcoChunk.java:120-128`). The only correct
copy is one without a light binding — a copy goes into another instance
(`InstanceContainer.java:610-618`), and `bind` throws on the second one (`ChunkLightScheduler.java:483-492`).
All that is open is whether the type of the copy should be right.
**Recommendation: a bugfix of its own, independent of the fluent API, before variant (D).**

> **Answered, and the answer is that there is nothing to fix**
> ([#16](https://github.com/OneLiteFeatherNET/Falco/pull/16)). The premise above is wrong on the
> part that mattered: the copy does **not** lose its light. `DynamicChunk.copy` clones the sections
> and the light data goes with them, so the copy carries what it was made from as a snapshot with
> nothing updating it afterwards. Losing the *binding* is not a defect either but the only correct
> outcome — Minestom copies a chunk into another instance, a scheduler serves exactly one, and a
> bound copy would turn the first block change placed into it into an `IllegalStateException`. This
> is the opposite of `FalcoChunk`, which overrides `copy` so its instance can still unload the copy;
> the two look inconsistent and are not. A test and a paragraph in the class javadoc replace the
> planned bugfix, so the next reader does not rediscover the difference and "fix" it away.

**14. Will `FalcoInstance` ever carry a `SharedInstance`?** *(new, from section 4.3)*
`SharedInstance` is typed against `InstanceContainer` throughout Minestom
(`InstanceManager.java:83-87`), `README.md:22` records the boundary.
Options: (a) the boundary stays and is repeated in the javadoc of `FalcoInstance.builder(...)`;
(b) `FalcoInstance` gets a shared mechanism of its own (large rebuild, its own investigation).
**Recommendation: (a).** The user with the most common lobby pattern needs only the module builders
from section 3.1 and section 3.2 on an `InstanceContainer` — after the removal of `falco-api` that
path is the default, not the fallback.

---

## 7. The sketches against `falco-archunit`

The rest of this page was written before `cb30cf0` added `falco-archunit`. This section closes that
gap: every type proposed above, checked against the 39 rules as they stand at `4c86c96`. One sketch
fails, two are confirmed by rules written independently of this page, and the rest cost annotations.

The five proposed types are `FalcoAnvilLoader.Builder`, `ChunkLightScheduler.Builder`,
`ChunkLightScheduler.SkyLight`, `MinestomBlockLightSource.Overrides` and `FalcoInstance.Builder` —
all nested, all in packages that already carry a `package-info`.

### 7.1 One sketch fails: `ChunkLightScheduler.Builder`

`sharedStateIsSafelyPublished` (`ConcurrencyTest.java:135`) selects a class through `SHARED_OBJECT`
(`:78-86`), which is true of any class declaring **a field whose raw type sits in
`java.util.concurrent`**, volatile or not. The light builder needs an `Executor` field to carry its
`executor(Executor)` slot (section 3.2), and `java.util.concurrent.Executor` matches that predicate.
The condition then requires every non-static field to be `final`, `volatile`, or written only from a
constructor or an `ACC_SYNCHRONIZED` method (`:94-108`) — and a mutable builder writes its fields
from ordinary slot methods. **The rule turns red.**

This is a false positive in intent — a builder is documented as not thread-safe and is never shared —
but it is a true positive in mechanism, and the mechanism is what runs in CI. Three ways out:

> **Confirmed against the rule, not just read**
> ([#16](https://github.com/OneLiteFeatherNET/Falco/pull/16)). The prediction here was checked by
> building the light builder immutable and then changing a **single** field from `final` to mutable:
> `sharedStateIsSafelyPublished` turns red with exactly the message derived above —
> *"ChunkLightScheduler$Builder.skyLight is neither final nor volatile and is written outside the
> constructor"*. One field is enough. The recommended way out was taken, and it was extended to the
> other two builders so all three read the same way (see the correction in section 3.1).

| Way out | Verdict |
|---|---|
| Make every builder field `final`, each slot returning a new instance (or make the builder a `record`) | **Recommended.** Passes with no rule change, and P4 already asks for immutability of the result; extending it to the builder costs allocations during configuration, where nothing is hot. `record` additionally sidesteps `publicClassesAreFinal`, which exempts records |
| Declare the fields `volatile` | Passes, but states an untruth: it advertises safe publication for an object the javadoc calls not thread-safe |
| Narrow `SHARED_OBJECT` so a builder is not selected | Touches a rule that was red against a real defect (`FalcoInstance.chunkSupplier`) and caught it; weakening it for a convenience type is the wrong trade |

The other two builders are unaffected: `FalcoAnvilLoader.Builder` carries `int`,
`AnvilDiagnostics`, `PaletteEntryResolver` and `Consumer<Throwable>`, and `FalcoInstance.Builder`
carries `RegistryKey`, `ChunkLoader`, `Generator`, `ChunkSupplier`, two `Consumer`s and two
`boolean`s. `java.util.function` is not `java.util.concurrent`; neither class is selected.

### 7.2 Two rules confirm decisions this page reached independently

**The module isolation rules back variant (D).** `anvilIsStandalone`, `lightIsStandalone` and
`instanceIsStandalone` (`ModuleBoundaryTest.java:96`, `:108`, `:119`) forbid any dependency between
the three modules, and the javadoc of `isolated(...)` cites `ServerStack.java:27-40` — the very
passage arguing why `FalcoInstance` and `FalcoLightingChunk` cannot be combined "instead of marrying
the two modules". Variant (D) passes because the cast lives in *user* code: `falco-instance` sees
only `Consumer<Chunk>`, `falco-light` only its own class. Variants (A) and (B) would have needed a
type both modules name, which these rules forbid outright — a constraint section 4.2 argued to
without knowing the rules existed.

**`lightCoreKnowsNoMinestom` backs the placement of the `Overrides` helper.**
`ForeignCouplingTest.java:86` keeps Minestom out of the light core, and
`blockRegistryOnlyInAdapters` (`:191`) says `BlockLightSource` and `PaletteEntryResolver` "exist for
exactly this". Section 3.2.5 moved the helper into `MinestomBlockLightSource` for the same reason,
reached from the type system rather than from the rule.

### 7.3 What the remaining rules cost

| Rule | Consequence for the sketches |
|---|---|
| `publicApiIsMarkedExperimental` (`PublicApiTest.java:84`) | **All five types need `@ApiStatus.Experimental`.** Nested types are in scope: the rule was first red against `RegionFile.RawChunk`, a nested record (`:77-80`) |
| `publicClassesAreFinal` (`:241`) | Both builder classes and `Overrides` must be `final` — they are not interfaces, not records, extend no Minestom type. `SkyLight` is an enum and exempt. Moot for any builder made a `record` per 7.1 |
| `everyPublishedPackageDeclaresNullness` (`:128`) | Satisfied as long as nothing moves into a new subpackage. No sketch does |
| `publicSignaturesStayReachable` (`:176`) | Satisfied. Every slot carries `int`, `Path`, `Key`, `Executor`, `Consumer`/`BiConsumer`, or a published Falco type. Note the stated limitation (`:170-172`): raw types only, so a `List<Internal>` would slip through — none of the sketches has one |
| `visibleFieldsAreConstants` (`:206`) | No sketch exposes a field. Worth recording: this rule would have killed rejected alternative A independently — `public static final AnvilOptions DEFAULTS` is neither primitive nor `String` |
| `utilityClassesHideTheirConstructor` (`:294`) | Not triggered; every builder has instance methods. It would have bound the withdrawn static `Falco`/`FalcoStack` facade |
| `noPublicMonitor` (`ConcurrencyTest.java:154`) | No slot may be `synchronized`. Trivially met, and the immutable builder of 7.1 makes it structural |
| `noGenericThrowables` (`ErrorHandlingTest.java:100`) | Slot validation must not construct `RuntimeException`/`Exception`/`Throwable`/`Error` directly. `IllegalArgumentException` and `IllegalStateException` are unaffected, which is what sections 3.1 and 3.2 specify |
| `slf4jStaysOutOfThePublicApi` (`ModuleBoundaryTest.java:271`) | No slot may carry `org.slf4j.Logger`. None does; the observation slots take `Consumer`/`BiConsumer` |

### 7.4 What this does not establish

These are the rules as they stand at `4c86c96`, checked by reading them, not by compiling a builder
against them. Two of the rules carry limitations their own javadoc states — `publicSignaturesStayReachable`
compares raw types only, and `sharedStateIsSafelyPublished` sees `ACC_SYNCHRONIZED` on methods but
never a `synchronized` block. A sketch that passes here can still be wrong in a way ArchUnit cannot
see. The first builder written should be run against `:falco-archunit:test` before the second one is
started.

---

## 8. Sources

### Web research

**Patterns and fundamentals**
- https://martinfowler.com/bliki/FluentInterface.html
- https://martinfowler.com/dslCatalog/expressionBuilder.html
- https://www.baeldung.com/java-fluent-interface-vs-builder-pattern
- https://blog.jooq.org/the-java-fluent-api-designer-crash-course/
- https://www.endoflineblog.com/type-safe-builder-pattern-in-java-and-the-jilt-library
- https://glaforge.dev/posts/2024/06/17/functional-builders-in-java-with-jilt/

**JDK references**
- https://openjdk.org/jeps/468 (Derived Record Creation — Candidate, not in JDK 25)
- https://openjdk.org/projects/jdk/25/
- https://nipafx.dev/inside-java-newscast-67/ · https://inside.java/2024/04/18/newscast-67/
- https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html
- https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.Builder.html
- https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.Builder.html
- https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/classfile/package-summary.html
- https://docs.oracle.com/javase/tutorial/datetime/overview/naming.html
- https://blog.joda.org/2011/08/common-java-method-names.html
- JLS 13.1 (*constant variable* — the basis for the `MinecraftServer.DATA_VERSION` finding in
  section 2.9.2 and section 3.1)

**Compatibility and annotations**
- https://wiki.eclipse.org/Evolving_Java-based_APIs_2
- https://wiki.openjdk.org/spaces/csr/pages/32342052/Kinds+of+Compatibility
- https://jspecify.dev/docs/start-here/
- https://errorprone.info/api/latest/com/google/errorprone/annotations/CheckReturnValue.html
- https://github.com/google/error-prone/blob/master/docs/bugpattern/CheckReturnValue.md
- https://github.com/google/error-prone/blob/master/annotations/src/main/java/com/google/errorprone/annotations/CanIgnoreReturnValue.java

**Libraries taken as models**
- https://netty.io/4.1/api/io/netty/bootstrap/AbstractBootstrap.html · ServerBootstrap.java · ChannelPipeline
- https://github.com/ben-manes/caffeine/wiki/Removal · Caffeine.java · CaffeineSpec.java
- https://raw.githubusercontent.com/square/okhttp/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/OkHttpClient.kt
- https://docs.micrometer.io/micrometer/reference/observation/components.html · concepts/timers.html · concepts/meter-filters.html
- https://opentelemetry.io/docs/languages/java/sdk/ · sdk/trace/.../SpanLimits.java
- https://java.testcontainers.org/features/creating_container/ · GenericContainer.java
- https://www.javadoc.io/static/org.assertj/assertj-core/3.21.0/org/assertj/core/api/AbstractAssert.html
- https://www.jooq.org/doc/latest/manual/sql-building/dsl-api/ · reference/dont-do-this/dont-do-this-jooq-step-types/
- https://github.com/skinny85/jilt · https://github.com/Randgalt/record-builder
- https://github.com/google/auto/blob/main/value/userguide/builders-howto.md
- https://www.baeldung.com/mockito-fluent-apis · https://tuhrig.de/everytime-a-mock-returns-a-mock-a-fairy-dies/

**Minestom and platform comparison**
- https://minestom.net/docs/feature/events
- https://deepwiki.com/Minestom/Minestom · https://deepwiki.com/PaperMC/Paper · https://deepwiki.com/SpongePowered/SpongeAPI
- Minestom sources, line references mixed from `2026.07.22-26.2` and the actually resolved
  `net.minestom:minestom:2026.06.20-26.1.2` (Gradle cache; see the Evidence status box), verified:
  `MinecraftConstants.java` (`DATA_VERSION` as an interface constant), `MinecraftServer.java`
  (`:95-99` `updateProcess`, `:187` `getSchedulerManager`, `:393-399` `start`),
  `instance/ChunkLoader.java` (`:16`, no `AutoCloseable`), `utils/chunk/ChunkSupplier.java`,
  `instance/Instance.java` (`:82-85` `EventHandler<InstanceEvent>`, `:178-184` eventNode construction
  and null case, `:321` `invalidateSection`, `:371` `setChunkSupplier`, `:467`
  `getCachedDimensionType`, `:808-845` tick/`InstanceTickEvent`, `:955` `eventNode()`, `:1060-1090`
  `getBlockLight`/`getSkyLight`), `instance/InstanceContainer.java` (`:216`, `:282`, `:291`, `:338`,
  `:381`, `:610-618`), `instance/InstanceManager.java` (`:83-87` SharedInstance typing, `:167`
  `InstanceRegisterEvent`), `instance/Chunk.java` (`:43` `Tickable`, `:99`, `:273`, `:278`, `:308`),
  `instance/DynamicChunk.java` (`:76-79` `setBlock`, `:237-243` `copy`, `:307`, `:310`
  `createLightData(boolean)`), `instance/block/Block.java` (`:225` `possibleStates`, `:247`/`:256`
  `stateId`), `instance/block/BlockImpl.java` (`:240-242`), `registry/Blocks.java` (`:1058` BARRIER,
  `:2312` DEEPSLATE), `registry/DimensionTypes.java` (`:12`, `:18`), `registry/RegistryKey.java`
  (`:15`), `world/DimensionType.java` (`:79`, `:184`), `ServerProcess.java` (`:30`,
  `extends Registries, Snapshotable`), `ServerProcessImpl.java` (`:302-312` time bases),
  `thread/ThreadDispatcherImpl.java` (`:140-141`), `timer/SchedulerManager.java` (`:32-36`),
  `instance/LightingChunk.java`, `instance/batch/AbsoluteBlockBatch.java` (`:170-171`),
  `instance/light/Light.java`, `instance/Section.java`, `event/*`, `event/instance/*`
- Cyano `0.7.2`: `MicrotusExtension.java:32` (`MinecraftServer.updateProcess()`), `EnvImpl.java:24`,
  `:31`, `:34-36`

### Repo references (`origin/main` at `26ac23e`)

**falco-anvil** — `FalcoAnvilLoader.java`, `RegionFile.java`, `ChunkCompression.java`,
`PaletteEntryResolver.java`, `BlockPaletteResolver.java`, `BiomePaletteResolver.java`,
`AnvilDiagnostics.java`, `AnvilChunkException.java`, `SectionCodec.java`, `PaletteData.java`,
`RegionConstants.java`, `package-info.java`, `build.gradle.kts`; tests
`FalcoAnvilLoaderIntegrationTest.java` (`:52`, `:417`, `:432`), `FalcoAnvilLoaderLifecycleTest.java`
(`:60`), `FalcoAnvilLoaderConcurrencyTest.java` (`:74`, `:176`, `:224`), `ChunkCompressionTest.java`
(`:113-114`)

**falco-light** — `ChunkLightService.java`, `ChunkLightScheduler.java`, `FalcoLightingChunk.java`,
`ChunkLightArea.java`, `ChunkArea.java`, `ChunkLightState.java`, `BlockLightSource.java`,
`MinestomBlockLightSource.java`, `LightUpdateAware.java`, `SectionOpacity.java`, `LightNibbles.java`,
`ChunkLightPropagator.java`, `LightPropagator.java`, `BlockFace.java`, `package-info.java`,
`build.gradle.kts`; tests `ChunkLightSchedulerTest.java` (`:40`, `:46`, `:68`),
`ChunkLightServiceIntegrationTest.java` (`:155`)

**falco-instance** — `FalcoInstance.java`, `FalcoChunk.java`, `FalcoInstanceException.java`,
`package-info.java`, `build.gradle.kts`; tests `FalcoInstanceTest.java` (`:48-52`, `:152-162`),
`FalcoInstanceGeneratorTest.java` (`:106-119`), `FalcoInstanceUnloadTest.java`,
`FalcoInstanceLoadRaceTest.java`

**falco-demo** (not published, but the only complete wiring) — `DemoServer.java`
(`:148`, `:150-157`, `:234-253`, `:414-420`), `ServerStack.java` (`:28-40`, `:141`, `:165-170`,
`:203-205`), `LoaderKind.java` (`:80`, `:110-117`), `LoaderDiagnosis.java`, `TimingChunkLoader.java`,
`WorldSearchResult.java`, `DemoOptions.java`, `ServerOptions.java`, `ChunkInventory.java`

**Build and publication** — `build.gradle.kts` (`:20-24` toolchain, `:30-33` release,
`:35-37` javadoc `-Werror`, `:62` `publishedModules`, `:87-97` javadoc and sources jars and publication),
`settings.gradle.kts` (`:4-9` include, `:32`/`:47` `mycelium-bom`, `:33-50` version catalog),
`falco-bom/build.gradle.kts` (`:4-8`), `falco-demo/build.gradle.kts` (`:22-24`),
`falco-light/build.gradle.kts` (`:14`, `:19`), `falco-anvil/build.gradle.kts` (`:14`),
`falco-instance/build.gradle.kts` (`:8`, `:16`), `README.md`, `release-please-config.json`
