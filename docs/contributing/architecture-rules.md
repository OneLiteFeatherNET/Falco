# Architecture rules

What `falco-archunit` enforces, why each rule exists, and — as important — what it cannot see. The
module holds 39 [ArchUnit](https://www.archunit.org/) rules over the compiled classes of every other
module. It has no `main` sources and is never published; how it fits into the build is in [Build
Setup](build-setup.md), what `check` runs per module in [Testing and Javadoc](testing-and-javadoc.md).

The design document that derives the rules, with the evidence behind each one, lives in the
repository at
[`docs/superpowers/specs/2026-08-01-archunit-architecture-rules-design.md`](https://github.com/OneLiteFeatherNET/Falco/blob/main/docs/superpowers/specs/2026-08-01-archunit-architecture-rules-design.md).

## Why the module exists

Falco claims three properties, and until this module nothing in the build checked any of them.

**Three separately pullable artefacts.** [Add Falco to your build](../how-to/add-falco-to-your-build.md) promises "take one without the
other if that is all you need". No module declares a `project` dependency on another, so the promise
is kept purely by omission — one line in a build file plus one import turns three artefacts into
one, and neither the compiler nor a test says a word.

**A compute core that knows no server.** `RegionFile` is, by its own Javadoc, "a pure byte container.
It neither knows the NBT structure of a chunk nor the Minestom chunk model"; `BlockLightSource`
"separates the algorithm from the registries of a running server". Minestom is `compileOnly`
everywhere (see [Dependency Management](dependency-management.md)), so a wrong import compiles straight
through. This separation is also what makes the published numbers possible at all: Minestom's
`AnvilLoader` cannot be touched in a bare JMH fork because its static fields read the registry, while
Falco's `RegionFile` reads none — see [Why a second Anvil loader](../explanation/why-a-second-anvil-loader.md).

**An experimental but honest API.** The build has no japicmp, no revapi and no Error Prone. Whatever
reaches `repo.onelitefeather.dev` cannot be taken back binary-compatibly, and the only carrier of the
`@ApiStatus.Experimental` promise is the annotation itself.

## Why a module of its own

Two reasons, and the second is the stronger one.

Cross-module rules cannot be stated inside a module that only sees itself: "`falco-anvil` does not
know `falco-light`" has no home in either of them.

More importantly, `falco-archunit` pulls the other modules as **test dependencies**, so it sees only
their `main` classes — which puts it exactly where a consumer of the jar stands. That position is
what lets `publicSignaturesStayReachable` find a class of mistake **no test in the modules
themselves can**: a `public` method returning the package-private `SectorAllocator` compiles, passes
every test in `falco-anvil` because those tests share the package, and is unusable for a consumer,
who cannot name the type.

The same construction puts test sources out of reach by design. `falco-light` declares
`testImplementation(project(":falco-anvil"))` — a cross-module dependency the isolation rules would
otherwise flag, and one that is entirely legitimate, because the light engine is verified against
chunks the loader reads. Rules over test sources would need a second module or a second import path;
that is a decision deliberately not taken rather than an oversight.

## The rules

48 `@ArchTest` fields in seven classes, under
`falco-archunit/src/test/java/net/onelitefeather/falco/architecture/`.

| Class | Rules | What it pins |
| --- | ---: | --- |
| `ModuleBoundaryTest` | 8 | `falco-anvil`, `falco-light` and `falco-instance` do not know each other; they use only the declared third-party libraries; no published module touches the Minestom implementation it replaces; the measuring path of the demo knows only the interface both sides share; SLF4J appears in no public signature |
| `PublicApiTest` | 6 | `@ApiStatus.Experimental` on every public type; `@NotNullByDefault` on the package; no unreachable types in public signatures; visible fields are immutable constants; public classes are final unless forced open; utility classes hide their constructor |
| `ForeignCouplingTest` | 10 | The light core and the byte layer know no Minestom, no NBT and no file; the registry is queried only in the named adapters; Falco does not implement Minestom's `Light`; only `ChunkLightService` writes into it |
| `ErrorHandlingTest` | 11 | Own exceptions are public, unchecked and carry a cause; checked faults stay inside the hierarchy and are translated at exactly one point; no generic throwables, and the format classes throw no bare `IOException`; one `ExceptionManager` boundary per module; no JVM shutdown in a library; only the demo owns the console; loggers are `private static final` and SLF4J only |
| `ConcurrencyTest` | 8 | No mutable static field; virtual threads only, and bounded by a semaphore; no `ThreadLocal`; no publicly reachable monitor; shared state is safely published |
| `ForeignWritePathTest` | 4 | The container's write path stays private and synchronised and has exactly four entry points; a view hands its writes to the container; Falco overrides no block write |
| `MigrationBoundaryTest` | 1 | `falco-migration` knows no Minestom — a world has to be convertible before anything boots |

**`falco-migration` sits outside `ModuleBoundaryTest`'s `PUBLISHED` set on purpose.** That set asserts
that the modules in it do not know each other, and `falco-migration` *does* know `falco-anvil`: it
reads its exception types and implements its `ChunkMigrator`, which is what lets a loader migrate the
chunks it reads (see [How world migration works](../explanation/how-world-migration-works.md)). It therefore has the separate class above.
The consequence is worth stating plainly: **the other rules of `ModuleBoundaryTest` do not reach it
either** — that is a gap rather than a decision, and it has not been closed.

Each rule carries its reason in its own Javadoc, with the file and line the reason rests on.

```
./gradlew :falco-archunit:test    # the rules alone
./gradlew check                   # everything, the rules included
```

`archRule.failOnEmptyShould=true` is pinned in `archunit.properties`, so a rule whose selection runs
empty fails rather than passing silently. That matters more than it sounds: several rules select a
handful of classes, and a refactoring that empties the selection would otherwise turn the rule green
by making it vacuous.

## What the rules cannot see

A rule that appears to cover more than it does is worse than no rule, so each one names its own limit
in its Javadoc. The load-bearing ones:

- **`synchronized` blocks.** ArchUnit models bytecode members and accesses, not `monitorenter` /
  `monitorexit`. `noPublicMonitor` catches the `ACC_SYNCHRONIZED` flag on a method and is
  structurally blind to `synchronized (this)` inside one. `BiomePaletteResolver` contains exactly
  such a block; it is harmless there — a double-checked initialisation behind a volatile field — but
  the rule would never have found it.
- **The seqlock protocol in `RegionFile`.** Whether the order read-version → read-data →
  read-version-again is respected is the actual correctness condition of the parallel read path (see
  [How the concurrency design works](../explanation/how-the-concurrency-design-works.md)). Accesses are visible to ArchUnit, their ordering
  is not.
- **"No CPU-bound work happens while a lock is held."** The claim the three-stage loader rests on.
  Two rules secure it *indirectly*, by keeping NBT and the file system out of the layers that must
  not have them, but `lock()` and `unlock()` are ordinary method calls and the region between them is
  not a concept of the model.
- **Raw types only.** Generic arguments are invisible, so an SLF4J `Logger` inside a `List<Logger>`
  passes the rule that keeps SLF4J out of the public API.
- **Anything that exists only in Javadoc.** "every caller of this method is covered by a test", "the
  array is not copied", and the truth — as opposed to the presence — of a `@Nullable`.

Two properties are semantic and belong to tests rather than to any static tool:
`supportsParallelLoading() == true` implying `loadChunk` is thread-safe, and the invariants a subtype
of `FalcoInstance` or `FalcoChunk` has to keep.

## One tolerated violation, named rather than exempted

`RegionFile.retryWhileDenied` sleeps up to 100 times for 1 ms each, on a Windows quirk when replacing
an external chunk file. `noBlockingWait` exempts **that method by name** rather than the class or the
module, so every further blocking wait still trips the rule. The 100 ms worst case is stated in the
Javadoc of `FalcoAnvilLoader.saveChunk`, because that method runs on the caller's thread.

## What the rules found when they were introduced

Written sharp and without exemptions, three were red on the first run. All three were answered by
changing the code, not the rule:

| Rule | What it found |
| --- | --- |
| `publicApiIsMarkedExperimental` | `RegionFile.RawChunk`, the one public type of thirty-one without the marker, reachable through `readRaw` |
| `noPublicMonitor` | `FalcoAnvilLoader.close()` was `public synchronized` and therefore locked on an object the caller holds — a caller writing `synchronized (loader)` blocked the shutdown |
| `sharedStateIsSafelyPublished` | `FalcoInstance.chunkSupplier` and `chunkLoader` were written by public setters and read on the load path with no barrier, so a reader could observe a half-constructed `ChunkLoader` |

The third had been found independently while modernising for Java 25, from the other direction — by
reading the code rather than by the shape of the field. Two routes, one defect.

A fourth rule was red for a reason the design had missed, and there the **rule** was wrong rather
than the code. It covered `net.minestom.server.registry` as a whole, but that package holds the
dynamic registry, which needs a booted server, next to `RegistryData`, the static tables
`block.registry()` hands out. `MinestomBlockLightSource` only ever reaches the latter — which the
neighbouring rule grants it by name. Narrowed to the dynamic registry, both rules say what they meant.
