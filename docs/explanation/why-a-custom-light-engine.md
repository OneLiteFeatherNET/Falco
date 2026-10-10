# Why Falco has its own light engine

This document answers one question: given that Minestom already computes light, and given that
Starlight and Phosphor already exist and are widely regarded as the state of the art, why does
`falco-light` exist and why is its algorithm the shape it is? The answer is not "because ours is
faster" — that is only true under conditions this document names, and it was not the reason the
engine was written. The reason is structural, and most of the arguments that *looked* like reasons
turned out, on inspection, not to be. Those failures are recorded here at the same length as the
successes, because they are the part that would otherwise be re-litigated.

Throughout, a claim is marked as one of three things: a **measurement** from this repository, quoted
with its benchmark name and conditions; a statement established by **reading** someone else's source,
with the project, file, member and line named; or an **argument** from the properties of the
algorithm, which nobody measured and which says so. Where a number appears without one of those
three, it is a mistake. Some of the numbers below come from the original investigation rather than
from `falco-benchmarks`; those carry no `±` and no run configuration, and every one of them says so
where it appears.

Minestom is read at version `2026.06.20-26.1.2`, which is what this repository compiles against —
`settings.gradle.kts` declares `minestom` `withoutVersion()` and takes the version from
`net.onelitefeather:mycelium-bom:1.7.2`, whose `dependencyManagement` pins exactly that string.
Starlight and Phosphor are read at the branches and tags named in the Sources list, and the citations
to them are to a moving branch rather than to a pinned revision wherever the Sources list says
`fabric` branch. Every measured figure below belongs to a table in [What the benchmarks establish](what-the-benchmarks-establish.md) or
[Measured results](../reference/measured-results.md); what the `±` after a JMH mean covers is stated once, in
[What a measurement here means](what-a-measurement-here-means.md), and is not restated here.

## Contents

- [The question that has to come first: does the code path run at
  all?](#the-question-that-has-to-come-first-does-the-code-path-run-at-all)
- [The reason that does not depend on a measurement](#the-reason-that-does-not-depend-on-a-measurement)
- [The propagation model: pushing, not pulling](#the-propagation-model-pushing-not-pulling)
- [Where the time actually went, and why that surprised
  us](#where-the-time-actually-went-and-why-that-surprised-us)
- [The measured comparison, including where the lead
  shrinks](#the-measured-comparison-including-where-the-lead-shrinks)
- [Byte-identical output, and the test that pins it](#byte-identical-output-and-the-test-that-pins-it)
- [Claims that did not survive checking](#claims-that-did-not-survive-checking) — five subsections,
  on Starlight, Phosphor and the literature search
- [Things we decided not to build, and why](#things-we-decided-not-to-build-and-why)
- [What this engine costs, stated plainly](#what-this-engine-costs-stated-plainly)
- [Sources](#sources)

## The question that has to come first: does the code path run at all?

The honest starting point is that for the workload Falco was originally built for, a light engine is
worth nothing.

When a chunk is loaded from an Anvil region file that already carries `SkyLight` and `BlockLight`
tags, those bytes are handed to the section through `Light#set`, and `set` clears the section's
update flag. The mechanism is two fields: `BlockLight.set` assigns `isValidBorders = true`
(`instance/light/BlockLight.java:78-83`) and `requiresUpdate()` returns `!isValidBorders` (`:72-74`),
with `SkyLight` identical at `:64-69` and `:58-60`. `LightingChunk` recomputes a section only when
that flag is true — `if (section.blockLight().requiresUpdate())` at `instance/LightingChunk.java:293`
and the sky equivalent at `:300`, with the same guard again at `:527-528` — so a pre-lit chunk is
never relit. This was read in Minestom `2026.06.20-26.1.2` and separately confirmed with probe code
that observed `requiresUpdate` flip from `true` to `false` across a `set(2048)` call [14]. The
structural half of that is checkable in a minute; the
consequence is that against a breakdown of the cost of loading one chunk — NBT parse 62 %, inflate
28 %, palette 4.5 % of the whole load, the remainder unattributed — the light engine is 0 % of
loading a pre-lit world. That breakdown is from the original investigation [14] and from no benchmark
in `falco-benchmarks`: it carries no `±`, and its base is one chunk load on whatever machine that
investigation used. The 0 % does not rest on it. It follows from the flag.

That leaves two workloads where light is actually computed. Both were measured in the original
investigation and **not** by any benchmark in `falco-benchmarks`, so they carry no `±` and no run
configuration and should be read as magnitudes: relighting a generated chunk that carries no stored
light, at 2.2 ms per chunk or about 92 µs per section, and a runtime block placement, at 0.586 ms per
placed torch [14]. If neither of those is in your server's profile, the correct decision is to not use
this package, and [How the light engine works](how-the-light-engine-works.md) says so in its first table.

Stating this first is deliberate. Every performance figure below is real, and none of it matters if
the code never executes.

## The reason that does not depend on a measurement

The strongest argument for this engine is not a benchmark. It is that Minestom computes light in
exactly one place, and that place is not the default.

Read in Minestom `2026.06.20-26.1.2`: `LightingChunk extends DynamicChunk`, and every entry point
into the built-in lighting is guarded by an `instanceof` test against that exact class. Ten lines of
`instance/LightingChunk.java` carry one; the two that decide whether anything happens at all are
`if (!(chunk instanceof LightingChunk lighting)) return null;` at `:371` and `:383`, and
`if (!(c instanceof LightingChunk)) return Set.of();` at `:569`. A chunk that is not a
`LightingChunk` receives no light at all; it does not receive worse light, it receives none.

The part that makes this more than a footnote is what `InstanceContainer` does by default. Its
constructor calls `setChunkSupplier(DynamicChunk::new)` (`instance/InstanceContainer.java:120`). A
plain `InstanceContainer`, unmodified, therefore produces chunks that Minestom's light engine will
not touch. Using the built-in engine is an opt-in that a server has to know about.

Falco takes the opposite position on where light lives. The propagation runs outside the chunk,
references no Minestom type at all, and the finished 2048-byte array is handed in through
`Light#set(byte[])`, which every `Light` implementation accepts. That has three consequences worth
naming:

- It works with any chunk from any loader, including `DynamicChunk`, including a custom one.
- It does not depend on `Light#calculateInternal` / `calculateExternal`, which carry
  `@ApiStatus.Internal` on the interface (`instance/light/Light.java:37`, `:43`) and may change
  signature between Minestom versions. `Light#set` at `:35` carries no such annotation. One honest
  qualification, because a reader will find it: both implementations do annotate their *override* —
  `BlockLight.set` at `:77` and `SkyLight.set` at `:63`. The interface declaration is what a caller
  binds against, and that is the one without the marker, but the signal is not unambiguous and this
  argument should not be leaned on harder than that.
- Because the algorithm knows nothing about Minestom, it is tested against a handful of fake blocks
  with no server running. Only `MinestomBlockLightSource`, the adapter, needs one.

This is the argument that survives a machine being fast or slow, and it is the one to weigh first.

## The propagation model: pushing, not pulling

The search in `LightPropagator#propagate` is a breadth-first flood fill over a section's 4096
positions. It dequeues a position, reads that position's level from a `byte[4096]`, and for each of
the six faces computes the neighbour index, tests whether the neighbour's *entered* face is occluded,
and writes `level - 1` into the neighbour if that raises it. The neighbour is then queued. Nothing in
that loop ever reads the six neighbours of the *dequeued* cell in order to decide what the dequeued
cell should be.

That direction — push a level onto the neighbours, rather than recompute a cell by pulling from all
six of its own neighbours — is precisely the property Spottedleaf names as the reason Starlight beats
vanilla Minecraft. Starlight's `TECHNICAL_DETAILS.md` puts it as vanilla "checking ALL 6 of its
neighbours" against Starlight, "for Starlight it only checks JUST ONE (the block it propagated
from)" [1]. Reading `StarLightEngine.performLightIncrease` confirms the implementation matches the
document: it iterates a direction set, computes the neighbour position as `offX = posX +
propagate.x` with its `y` and `z` siblings, and writes the target level there through
`currentNibble.set(localIndex, targetLevel)` before queuing it [4]. **Established by reading, not by
measuring.**

So the headline structural advantage of Starlight was never available to be imported: it was already
the design. This is the single most useful thing in this section, and it is the reason
`falco-light` did not become a Starlight port.

### The nuance that the earlier notes got slightly wrong

There is a correction to make here, and it cuts against us rather than for us.
[Project Status](../project-record/project-status.md) records
the push-versus-pull property under *Claims about other light engines* as the reason there is nothing
left to take from Starlight, which is correct. It is easy to read that entry as also implying an
advantage over Minestom. It does not, and reading Minestom's source settles it.

`net.minestom.server.instance.light.LightCompute.compute` (`instance/light/LightCompute.java:115`)
is likewise a push-based BFS over a `ShortArrayFIFOQueue` of packed shorts in the layout
`[4bit level][4bit y][4bit z][4bit x]` — the layout is stated in that method's own javadoc at `:112`.
It dequeues an entry, unpacks x/y/z and level (`:137-142`), iterates `Direction.values()` (`:144`,
over the `DIRECTIONS` array declared at `:17`), and writes `lightLevel - 1` into each in-bounds
neighbour whose current level is lower, enqueueing it (`:168-169`). Minestom is not the vanilla
algorithm; it does not pull, and it does not re-derive a cell from six reads. **Established by
reading Minestom `2026.06.20-26.1.2`, not by measuring.**

The difference between the two engines is therefore not the shape of the search. It is what a single
step of the search costs. Inside that `for (Direction direction : DIRECTIONS)` loop,
`LightCompute.compute` resolves `getBlock(blockPalette, x, y, z)` — the block it is propagating
*from* — and `getBlock(blockPalette, xO, yO, zO)` (`:158-159`), asks each of the two for its
`occlusionShape()` through its registry entry (`:161-162`), and calls `isOccluded` on the pair
(`:165`). `getBlock` is `Block.fromStateId(palette.get(x, y, z))` (`:192-194`). Both lookups sit
inside the loop rather than above it, so the *source* block is resolved again for each direction in
which the neighbour would actually be raised — the pair sits behind
`if (getLight(lightArray, newIndex) < newLightLevel)` at `:157`, so it is up to six resolutions of
the same block per dequeued cell rather than exactly six. Falco resolves nothing here:
`SectionOpacity#blocksFace` is three statements, one array index and one bit test against a six-bit
mask (`SectionOpacity.OCCLUSION_BITS = 6`).

That is the whole mechanism, and it is why the margin between the two engines moves with how often
the question "does light pass through here?" is asked, rather than with how many positions are
visited.

Two riders on it, because it is the argument the rest of this page leans on. It counts resolutions
and not time: nothing established here says what one `Block.fromStateId` plus one `occlusionShape()`
costs relative to one array index, and no benchmark in this repository isolates that pair. And it is
an explanation offered for the measured result below rather than something the harness separates out
— what the tables support is that the gap widens with occlusion; that this paragraph is why is
argued from the code.

### Why occlusion is stored per face and not per block

A design detail that is not a preference. Of 1168 block types surveyed in the original
investigation, 364 are uniformly opaque, 644 uniformly transparent and **160 are directional** —
slabs, stairs, snow, farmland, dirt paths, lecterns, daylight detectors, stonecutters — which is
13.70 % [14]. Those four counts are a survey from that investigation, taken against the registry of
whatever Minestom version it ran on, which it does not record; they are not re-derived here and the
share should be read as "one block type in seven" rather than to four figures. A first draft using a
single per-block flag produced 472 wrong cells out of 4096 [14].
`SectionOpacity` therefore stores a six-bit mask per block, and only the face light *enters* is
tested. Testing the face it leaves as well would keep every emitting block that is opaque in its own
right dark, and glowstone is exactly that; the comment on that branch in `LightPropagator#propagate`
says so, and `MinestomBlockLightSourceTest` pins the behaviour on real bottom and top slabs.

## Where the time actually went, and why that surprised us

For a long stretch the engine lost to Minestom on sparse sections, and the explanation given in the
documentation was that the search's shape favoured dense sections. That explanation was wrong, and
the benchmark that showed it wrong is the most useful thing built on the light side.

`LightEngineStageBenchmark` splits both engines into stages and measures them inside the project, in
`net.minestom.server.instance.light` because the built-in methods are package-private. For one light
source in an open section, in microseconds [11][12]:

| Stage | Before `69381af` | After | |
| --- | ---: | ---: | --- |
| `opacity` — build the table | 31.33 | **8.07** | changed by the commit |
| `readStates` | 7.70 | 7.23 | untouched — internal control |
| `propagate` — the search itself | 33.85 | 31.41 | untouched by the commit |
| `collect` | 0.24 | 0.23 | untouched — internal control |
| `falcoFull` (measured, not the column sum) | 77.1 | 46.3 | |

<sub>`LightEngineStageBenchmark.falcoOpacity` / `.falcoReadStates` / `.falcoPropagate` /
`.falcoCollect` / `.falcoFull` — the rows are labelled by stage above and the method names carry the
`falco` prefix in the source — `lightSources = 1`, `occlusionPercent = 0`, one thread, one fork with
`-Xms512m -Xmx512m`, 3 warmup and 5 measurement iterations of 1 s per the class annotation, JMH
1.37, one 16-core machine recorded as not idle, commits `69381af` (after) against its parent
(before), no `results.json` committed. **No `±` was recorded for any cell.** One fork: any `±` would
have covered variance inside one JVM only — see [What a measurement here means](what-a-measurement-here-means.md).</sub>

The table is a genuine before/after across one commit, and it carries its own noise estimate, which
is the reason it is worth more than its missing error bars suggest. Two stages were not touched by
`69381af` at all: `readStates` moves by 6 % and `collect` by 4 % between the two runs. Those two are
an **internal control** — they put the run-to-run floor of this particular pair of runs at roughly
5 %. Against that floor, `opacity` going 31.33 → 8.07, a factor of 3.9, is far outside anything the
noise could produce. This is an argument from the table's own structure, not a significance test.

The last row is `falcoFull`, measured separately rather than summed, which is why it does not equal
the column above it: the stages sum to 73.12 before and 46.94 after, leaving a residual of +3.98 and
−0.64. Exposing that residual is what `falcoFull` is for.

Two things fall out. First, **the search was never the problem.** At 33.85 µs the Falco propagation
was already faster than the built-in search, which the same benchmark puts at 53.9 µs — a **derived**
figure, `minestomFull` minus `minestomQueue`, because Minestom's search consumes the queue it is
handed and cannot be measured on a prepared one. Neither operand carries a `±`, so the difference
carries none either and 53.9 must not be turned into a ratio. The entire deficit sat in the
preparation. Second, the preparation was a single allocation: the lambda handed to `computeIfAbsent`
captured the `BlockLightSource`, so a fresh instance was created per block, and escape analysis did
not remove it because `computeIfAbsent` is too large to inline — roughly 4096 objects per section.
Replacing the map with a linear-probing table over the raw state id, packing occlusion and emission
together into a short, took the allocation of one call from 74 040 to 8 664 bytes [11]. **That
allocation pair is the most robust number on this page**: `-prof gc` allocation counts are
essentially deterministic, so unlike every timing here they are immune to the fork count, to the JIT
and to what else the machine was doing.

The lesson recorded here is not "we made the table faster". It is that an algorithmic explanation was
offered and believed for a measurement that had an allocation as its cause, and that only splitting
the path into stages distinguished the two. Nothing about the search was changed in `69381af`, and
the search times before and after — 33.85 and 31.41 — say so.

**The search was not the problem, which is not the same as the search being tight.** #28 changed it
afterwards, and the change is as small as they come: `BlockFace#opposite()` is a switch over the
enum, and the inner loop called it once per face per queued position — six times for every block the
light reaches. Resolving all six once at class load turns that into an array read, and took the same
stage under the same parameters from 31.10 / 31.57 µs to 27.25 / 27.39 µs across two independent
three-fork runs per side, **−12.8 %**. This does not revise anything above: the stage that carried
the deficit was `opacity`, and it still is. It qualifies the reading. A stage can be the wrong place
to look for a *bottleneck* and still be the right place to spend twenty lines, and the pair of
numbers that settled that question was only available because the path had already been split into
stages for a different reason.

The same change on `ChunkLightPropagator`, which walks a whole chunk rather than one section, is
proposed in #32. Its measurement is the more instructive of the two: the saving is a function of
light-source density rather than chunk size, and at eight sources per section it is not measurable
at all.

## The measured comparison, including where the lead shrinks

`LightEngineComparisonBenchmark` runs both engines over the same section, from a block palette to a
finished 2048-byte array, with `MinecraftServer.init()` up so that both sides pay the real registry.
Neither side skips its preparation: the built-in path builds its seed queue through
`BlockLight.buildInternalQueue`, and the Falco path builds its opacity table through the real block
registry rather than a stand-in [13].

µs/op, lower is better [11][12]. The last column gives the conservative bounds on the ratio,
`(b − eb) / (a + ea)` to `(b + eb) / (a − ea)`, because a ratio can be no more precise than the two
intervals it is built from:

| Sources | Solid | Falco | Minestom | Falco is | Ratio bounds |
| ---: | ---: | ---: | ---: | --- | --- |
| 1 | 0 % | 44.5 ± 0.6 | 49.4 ± 1.3 | 1.11× faster | 1.07×–1.16× |
| 8 | 0 % | 98.3 ± 2.4 | 121.1 ± 5.5 | 1.23× faster | 1.15×–1.32× |
| 64 | 0 % | 109.2 ± 1.6 | 126.5 ± 5.6 | 1.16× faster | 1.09×–1.23× |
| 1 | 30 % | 39.3 ± 0.8 | 62.0 ± 2.0 | 1.58× faster | 1.50×–1.66× |
| 8 | 30 % | 119.3 ± 3.5 | 204.2 ± 3.7 | **1.71× faster** | 1.63×–1.80× |
| 64 | 30 % | 122.6 ± 1.3 | 206.6 ± 4.2 | 1.68× faster | 1.63×–1.74× |

<sub>`LightEngineComparisonBenchmark.falco` / `.minestom`, `lightSources` and `occlusionPercent` per
row, `emissionMix = UNIFORM` so every source is glowstone at level 15, one section per operation, one
thread, run as `-f 1 -wi 5 -i 10` — an override of the class annotation's 3 warmup and 5 measurement
iterations, and a better configuration than it, since ten iterations narrow the t-multiplier — 1 s
iterations, `-Xms512m -Xmx512m`, JMH 1.37, one 16-core machine recorded as not idle, no
`results.json` committed. One fork: the `±` covers variance between iterations of one JVM, not
between JVM launches — see [What a measurement here means](what-a-measurement-here-means.md).</sub>

**The intervals are disjoint at all six points**, so the direction and the magnitude are supported
for this run and are stated without hedging. The shape of the result follows directly from the
mechanism above: with 30 % solid blocks the search runs into an occlusion question constantly and
Falco answers each one from an array, while with 0 % solid it barely asks and the table is paid for
and hardly used. 1.11× at one source in an open section is the narrowest margin in the table for
exactly that reason.

Three things bound what this table licenses, and none of them touches the six rows themselves. It is
one fork on one machine, so it is a property of that run and not of the software. `occlusionPercent`
is a benchmark parameter and not a survey of real worlds — 30 % is measured because solid blocks end
a search early, so an entirely open section is the upper bound of the work rather than the typical
case. And every source in this table is at level 15, which the next section shows is the assumption
that flatters Falco most.

What the table cannot be dismissed as, however, is a faster engine computing something else. Both
engines are run over the section in `@Setup` and the trial is aborted before any measurement if the
2048 bytes differ (`verifyBothEnginesAgree`), and the measured Minestom side is the real
`BlockLight.buildInternalQueue` and `LightCompute.compute` rather than a reimplementation. Those are
facts about the harness, not results from it.

### The mixed-brightness case, where the lead shrinks to nothing much

The table above places glowstone everywhere, so every source starts at level 15. Real interiors are
not like that. `emissionMix=MIXED`, added in `0e8fbb5`, keeps the positions and the seed identical
and cycles the sources through glowstone 15, lantern 15, torch 14, redstone torch 7 and magma
block 3, so the levels are the only thing that changes. µs/op [11][12]:

| Sources | Solid | Falco | Minestom | Falco is | Ratio bounds |
| ---: | ---: | ---: | ---: | --- | --- |
| 8 | 0 % | 118.97 ± 8.89 | 126.54 ± 9.55 | no difference resolvable | — |
| 8 | 30 % | 116.50 ± 7.63 | 201.46 ± 16.83 | 1.73× faster | 1.49×–2.01× |
| 64 | 0 % | 150.42 ± 32.73 | 162.20 ± 3.11 | no difference resolvable | — |
| 64 | 30 % | 149.42 ± 12.64 | 252.26 ± 9.28 | 1.69× faster | 1.50×–1.91× |

<sub>`LightEngineComparisonBenchmark.falco` / `.minestom`, `emissionMix = MIXED`, `lightSources` and
`occlusionPercent` per row, one section per operation, one thread, run as `-f 1 -wi 3 -i 5` — note
that this is a *different* configuration from the UNIFORM table above, which used `-i 10` — 1 s
iterations, `-Xms512m -Xmx512m`, JMH 1.37, one 16-core machine recorded as not idle, no
`results.json` committed. One fork: the `±` covers variance between iterations of one JVM, not
between JVM launches — see [What a measurement here means](what-a-measurement-here-means.md).</sub>

**Mixed brightness costs Falco more than it costs Minestom, and in the open-sky rows it costs the
lead entirely.** At 8 sources and 0 % solid the intervals are `[110.1, 127.9]` against
`[117.0, 136.1]`; at 64 sources and 0 % they are `[117.7, 183.2]` against `[159.1, 165.3]`. Both
overlap, so neither row supports a factor in either direction, and the `1.06×` and `1.08×` those two
rows previously printed have been withdrawn. Note what that does *not* say: an unresolved difference
is not a demonstration that the two are equal. The right statement is that at this precision the
open-sky rows do not separate the engines. The 64-source row is the weaker of the two on Falco's own
side — ±32.73 on a mean of 150.42 is 22 % — which is itself informative: with mixed levels Falco's
own cost becomes less predictable, not just higher.

The size of the regression at 64 sources in an open section was once quoted as a rise from 112.7 to
150.4 µs, about 33 %. **The 112.7 was never traceable** — it appears in no published table, and the
`UNIFORM` 64 / 0 % row above is 109.2 ± 1.6 — so it has been dropped rather than reconciled, and no
figure has been substituted for it. Substituting 109.2 would have invented a measurement.

The claim it was carrying does hold, and now rests on a run that can be looked up. A two-fork re-run
of both cells on 2026-08-02 puts Falco at 109.88 ± 1.47 under `UNIFORM` against 155.73 ± 8.44 under
`MIXED` — **a rise of 41.7 %** on disjoint intervals, with conservative bounds of 32.3 % to 51.4 %.
Mixed levels therefore cost Falco something between a third and half again, and the old "about a
third" sat at the bottom of that range rather than at its centre. The run and its conditions are
under [With sources of mixed brightness](../reference/measured-results.md#with-sources-of-mixed-brightness).

The cause is a property of the search that the uniform benchmark could not see. A plain FIFO queue
delivers positions in level order only while every source starts at the same level. With mixed
levels, a position lit by a dim source can be reached later by a brighter wave, its level raised, and
the position queued a second time. `LightPropagator` documents this at its `ensureRoom` method and
grows the queue for it — and one of the defects fixed in this branch was precisely a queue sized on
the false assumption that a position is queued at most once [10]. The 30 % rows stay disjoint and
keep their lead (1.73× and 1.69× against the uniform table's 1.71× and 1.68×) because there the cheap
opacity answers still dominate what the search spends. That the regression lands only on the rows
where the search dominates, and not on the rows where the opacity table does, is what makes the
mechanism above more than a plausible story — but it is still a mechanism argued from the code, not
one the harness isolates.

This is what the `emissionMix` parameter exists for, and it is why it is a parameter rather than a
constant. It is also the honest bound on the headline numbers: quote the uniform table without the
mixed one and you have overstated the engine on the workload most servers actually have.

## Byte-identical output, and the test that pins it

Correctness here is not "better", it is "equal". A faster engine that lights a section even slightly
differently is not a faster engine; it is a second lighting of the same world, and the difference
surfaces as a patch of wrong brightness that no player can explain and no log mentions.

`LightEngineEquivalenceTest` compares the two engines byte for byte across 54 scenarios — nine source
counts `{0, 1, 2, 4, 8, 16, 64, 128, 512}` against six shares of solid blocks
`{0, 10, 30, 50, 70, 90}` — with every section built from the fixed seed `20260731L` so a failure
reproduces. It reaches `BlockLight.buildInternalQueue` and `LightCompute.compute` through reflection
rather than by placing the test inside a Minestom package, so no package of the server is split
across two artifacts. It runs with `./gradlew test` like any other test. The test also asserts that
every scenario holding at least one source produced light, because two dark sections would agree no
matter what either engine did. `LightEngineComparisonBenchmark` repeats the same 2048-byte comparison
in its `@Setup` and aborts the trial on a disagreement, so a faster number cannot come from computing
something else [13].

**The part worth remembering is not that the identity holds.** It is that until `69381af` it was
asserted in three documents and verified by nothing. It rested on a comparison run once by hand;
there was no test, and the benchmark did not check it either although the documentation said it did.
Two agents found this independently at their own ends of the code [10]. A number cited throughout the
project was hanging on nothing, and it happened to be true. That is a statement about our process,
not about the engine, and it is the reason this document insists on labelling every claim by how it
was established.

## Claims that did not survive checking

Each of the following was taken for a promising lead first, and stopped being one only after it was
checked. **All of them were established by reading source or published documents, not by measuring.**
They are the most valuable material here, because each one is a piece of work that does not have to
be done again.

### Starlight has no extended nibble arrays with a border

The idea under investigation was that Starlight stores light in arrays larger than a section, with a
one-block border, so that a propagation never has to test whether it has left the section. It does
not. `ca.spottedleaf.starlight.common.light.SWMRNibbleArray` declares
`public static final int ARRAY_SIZE = 16 * 16 * 16 / (8/4);` — 2048 bytes, 4096 nibbles, identical to
vanilla's `DataLayer`. The class allocates only arrays of exactly that length for `storageUpdating`
and `storageVisible`, and its `WORKING_BYTES_POOL` is a thread-local recycler for the same fixed
size [3].

What does exist is a flat cache in `StarLightEngine`, indexed `x + (z * 5) + (y * 25)` over a 5×5
region of chunks plus two spare sections vertically [4]. [Project Status](../project-record/project-status.md) describes
these as "a flat `byte[]` for the column"; that is imprecise, and the correction belongs here. They are declared
`protected final LevelChunkSection[] sectionCache;` and
`protected final SWMRNibbleArray[] nibbleCache;` — arrays of section and nibble-array *references*,
a lookup structure that avoids repeated map access, not a bordered light buffer. Either way, it is
not a border, and there is nothing here to copy.

### Starlight's data-holding gain does not transfer

A large share of what Starlight wins comes from what it replaces rather than from how it propagates.
`TECHNICAL_DETAILS.md` describes vanilla keeping light in a hashtable from section to nibble array
and updating that map copy-on-write, so that as the number of generated chunks grows, so does the
data in the hashtable and so does the time [1]. It also names per-update hashtable lookups against a
`Long2ByteMap` that Starlight avoids.

Falco never had that structure. `LightNibbles` holds a section's levels directly, without an array at
all when the section is uniform, and there is no map from section coordinate to light array anywhere
in the propagation path. A gain measured against a structure we do not have is not available to us.

### The 12× / 28× / 37× figures do not apply

These are the numbers most often quoted at us. Their provenance, read from the document that
publishes them [1]:

- They are measured against **Minecraft 1.16.5**. The document states its own scope explicitly:
  "This document is then only valid for versions from 1.16 to 1.19."
- The individual figures are per-operation microbenchmarks — block place at y=254 about 37× faster,
  block remove at y=254 about 13×, glowstone place about 12×, glowstone remove about 28× — not a
  whole-server or whole-chunk result.
- The **chunk-generation comparison was invalidated by its own author**, not by us. The document
  attributes the gap largely to vanilla's copy-on-write hashtable growing with the number of chunks
  generated, and states that this "basically invalidates the gen test for 1.20+" [1].
- In the gist announcing that he was ceasing to publish Starlight versions, Spottedleaf names two of
  Starlight's core ideas as having been taken up by the 1.20 vanilla engine — "The light engine
  should propagate levels, rather than updates" and "The sky light engine should have dedicated logic
  to light chunks" — writes that comparisons between the two on 1.20 "aren't clear on which
  implementation is currently faster", and says of what remains that "Vanilla is still 2x slower, but
  it is fast enough" [2]. He also states Starlight is "not neccessary to use on 1.20.x anyways". The
  gist does not say vanilla took everything, and it is quoted here only for the two ideas it names.

None of this makes Starlight a bad piece of work; it is an excellent one, and the push-based
propagation it popularised is the design Falco uses. It does mean that a 37× figure carried forward
to a 26.x-era server, or to a comparison with Minestom, is being quoted a decade out of context.

Two smaller corrections in the same family, from the original investigation [14]: **Starlight does
not use a bucket queue.** Its own document states the queue is a plain FIFO, and adds that it is
*vanilla* that has ordered queues by light level, backed by a `LongLinkedOpenHashSet` [1]. The
assumption that a priority queue was the trick to import was simply false. And Minestom's own
`compute` is a plain FIFO over `ShortArrayFIFOQueue` with no boxing, which the investigation
described as "already clean" — there is no obvious inefficiency there to exploit.

### Phosphor's optimisations are vanilla-specific, with one exception

Phosphor is a Fabric mod; its repository was archived on 12 May 2025 and it is read-only [5]. Its
README describes the goal but does not enumerate the optimisations, so the enumeration below comes
from the source tree.

Every lighting change Phosphor makes is a mixin onto a vanilla class: `MixinLightStorage`,
`MixinSkyLightStorage`, `MixinChunkToNibbleArrayMap`, `MixinLevelPropagator`,
`MixinChunkLightProvider`, `MixinServerLightingProvider`, plus support types such as
`LightProviderUpdateTracker`, `DoubleBufferedLong2ObjectHashMap` and `ReadonlyChunkNibbleArray` [5].
Those are optimisations of vanilla's storage and scheduling. `MixinChunkLightProvider`, for instance,
caches the two most recently accessed chunk sections in `private final ChunkSection[][]
cachedChunkSections = new ChunkSection[2][]` and tracks pending updates as a
`Long2ObjectOpenHashMap<BitSet>` keyed by section position, to avoid iterating all positions of a
sub-chunk. None of that has a counterpart in Falco, which holds no chunk-to-nibble map and does no
deferred update scheduling of its own.

The one transferable piece is `net.caffeinemc.phosphor.common.block.BlockStateLightInfo`, attached to
block states through `MixinAbstractBlockState`. It is two methods:

```java
public interface BlockStateLightInfo {
    VoxelShape[] getExtrudedFaces();
    int getLightSubtracted();
}
```

That is a per-block-state cache of occlusion geometry and light attenuation, resolved once and read
many times — which is exactly what `SectionOpacity` is, at section scope rather than global scope
and packed into a six-bit face mask plus an emission rather than into `VoxelShape[]`. The
optimisation was already present; only the packaging differs.

### There is no scientific literature on this problem

Searched for and not found: peer-reviewed work on discrete, integer-level, Minecraft-style flood-fill
light propagation. The nearest thing to a canonical reference is a blog post series, Ben Arnold's
"Fast Flood Fill Lighting in a Blocky Voxel Game" for Seed of Andromeda [9]; the original site did
not resolve when checked for this document (TLS failure), and an archived rendering exists [9a].
Voxel cone tracing and VXGI address continuous radiance transport and do not transfer to a
sixteen-level integer flood fill. This is a negative result, so treat it as "we looked and found
nothing", not as proof that nothing exists.

## Things we decided not to build, and why

| Subject | Basis | Verdict |
| --- | --- | --- |
| A custom `net.minestom.server.instance.light.Light` implementation | source | **Not built.** It compiles and works — a prototype carried a marker byte `0xCD` all the way into the `LightData` record sent to the client [14] — but `Section.clone()` at `instance/Section.java:26-33` calls `Light.sky()` / `Light.block()` outright, so a foreign implementation is silently replaced on copy, and the calculation methods it would have to implement carry `@ApiStatus.Internal` (`instance/light/Light.java:37`, `:43`) while `set` at `:35` does not — with the qualification recorded further up, that both implementations annotate their own override of `set`. Handing results in through `Light#set` avoids the `Section.clone()` problem outright and leaves only the weaker of the two annotation signals to rely on. |
| A bucket queue (Dial) | measured, but not by this harness | **Not built, and no longer undecided for lack of evidence.** The prior investigation put it at 5–7 % *slower* at equal source brightness and 32–36 % faster at mixed brightness [10]; those figures come from that investigation and not from any benchmark in `falco-benchmarks`, so they carry no conditions and no uncertainty here. Since `0e8fbb5` the harness does produce the mixed case, and it shows the regression the investigation predicted. The prerequisite for deciding is met; the change is still not made, and making it would need its own measurement. |
| Parallelising the BFS within one chunk | judgement, from measured magnitudes | **Not built.** The work is 50–150 µs, which is the range the tables above sit in. Handing that to another thread is argued to cost more than it saves; nobody built the threaded version to check, so the cost of handing it over is asserted and not measured. Minestom parallelises across chunks in `LightingChunk`'s static `Executors.newWorkStealingPool()` (`instance/LightingChunk.java:38`), which is the right granularity, and we do not compete with it. |
| Vector API (JEP 508) | scope | **Ruled out by packaging, not by performance — no vectorised version was written or measured.** Tenth incubator round: `javac` refuses without `--add-modules jdk.incubator.vector`, the runtime then prints an unsuppressable warning, and the JAR specification has no attribute to carry the flag, so every consumer of the library would have to set a JVM flag [10]. |
| Bit-slicing light levels across voxels | scope | **Not built.** No precedent in any engine examined here. It would be an original design with an unproven benefit, which is not what this module is for. |
| Beating Minestom on concurrency | source | **Not attempted, because there is nothing there.** It would be convenient to repeat the loader's argument about a lock held across expensive work, and it is not true of the light path. `LightCompute` is purely static and allocates its result buffer per call (`instance/light/LightCompute.java:118`), `BlockLight` keeps its buffers in instance fields per section (`instance/light/BlockLight.java:20-22`), and `LightingChunk` already dispatches through a work-stealing pool (`instance/LightingChunk.java:38`). Read, not measured: no lock in that path holds work that could run in parallel. |

## What this engine costs, stated plainly

Four limits, so that the trade is visible:

- **In an open section with mixed source brightness, no difference between the two engines is
  resolvable.** Both `MIXED` / 0 % rows overlap, at 8 sources and at 64, and the 64-source row carries
  a half-width of ±32.73 on a mean of 150.42 on Falco's own side. The uniform open-sky rows are the
  narrowest margins in the whole comparison (1.11× to 1.23×) even where they do separate.
- **An area does not read the diagonal chunks of its ring.** `ChunkLightArea` builds its ring from
  the face neighbours of every chunk it holds, so a light source in a chunk that touches the area only
  at a corner is missing from the result. It reaches the area through the chunk between the two, and
  that chunk is in the ring — but a ring chunk's state is computed from its own block states alone, so
  it does not carry what its diagonal neighbour sent it. This is the residue of a larger defect that
  is now fixed: `calculateWithNeighbours` used to write back all nine chunks of its 3×3, although only
  the middle one had seen everything that reaches it, so the eight it borrowed had their correct light
  replaced with a darker one. It now writes only the middle chunk. The argument that the middle chunk
  is safe is a derivation from the per-block decay rather than a measurement — a source outside the
  3×3 is at least 17 blocks away and level 15 cannot survive the trip — and the byte-identity tests
  cover a single section, not a ring [10]. The same derivation is what says the diagonals matter for
  an area: they are one chunk away, not two.
- **The 3×3 neighbourhood therefore survives the area.** `calculateWithNeighbours` was not deprecated
  in favour of `ChunkLightArea`, and the reason is exactly the point above: for a *single* chunk the
  neighbourhood is the more accurate of the two, because it reads the four diagonals an area of one
  chunk never sees. For several connected chunks the area is the cheaper one, because it reads each
  chunk once instead of once per neighbourhood — and this one is measured rather than argued.
  `AreaVsPerChunkBenchmark` compares one area pass over *n* chunks against *n* `calculateWithNeighbours`
  calls: at one chunk the two are level, which is the *predicted* control result and the reason the
  control is in the benchmark at all, and from four chunks on the intervals are disjoint at
  3.81×–4.06× (4 chunks), 4.75×–6.08× (9) and 5.52×–6.76× (16) in the area's favour. A second,
  independent run at nine chunks gave 4.17×–7.17×, which overlaps the first — one of only four
  repeated-run checks this project has, and one of the two of them that reproduce a ratio rather than
  an asymmetry — and it passes. The full table is in
  [Measured results](../reference/measured-results.md) under *Lighting an area against one neighbourhood per
  chunk*, and all five repeats are counted on that page under
  [Against the engine Minestom ships with](../reference/measured-results.md#against-the-engine-minestom-ships-with).
  Two calls with different trade-offs, not an old one and its replacement.
- **Sky light does not scale linearly.** Cost per section rises from 84 µs to 104 µs past roughly 64
  sections in `ScalingBenchmark`, and a least-squares fit over the vanilla range understates the cost
  at 256 sections by 19.5 %, where the same method lands within 1.8 % for block light [11]. Read the
  direction, not the digits: `ScalingBenchmark` measures **3** iterations rather than the 5 of every
  other class, no table of its fifteen `sectionCount` points is published, no residuals of the fit
  are, and the fit is extrapolated ten times beyond its support — so "about 20 %" is as much as the
  evidence carries. The direction is independently supported by a mechanism that needs no benchmark:
  the sky pass seeds a queue at every open cell rather than reading a heightmap.

If you already run `LightingChunk` and your sections are sparse, there is no obligation to change
anything. What argues for this engine is the case the built-in one does not cover at all — any chunk
that is not a `LightingChunk`, which by default is every chunk of an `InstanceContainer` — followed by
sections carrying a real share of solid blocks, where the margin is 1.58× to 1.73×, and the control
over *when* light is computed that follows from computing it outside the chunk.

## Sources

1. Spottedleaf, *Starlight — TECHNICAL_DETAILS.md*, PaperMC/Starlight, `fabric` branch.
   <https://github.com/PaperMC/Starlight/blob/fabric/TECHNICAL_DETAILS.md>
2. Spottedleaf, *Starlight 1.20* (gist, "The future of the Starlight mod").
   <https://gist.github.com/Spottedleaf/6cc1acdd03a9b7ac34699bf5e8f1b85c>
3. `ca.spottedleaf.starlight.common.light.SWMRNibbleArray`, PaperMC/Starlight, `fabric` branch.
   <https://github.com/PaperMC/Starlight/blob/fabric/src/main/java/ca/spottedleaf/starlight/common/light/SWMRNibbleArray.java>
4. `ca.spottedleaf.starlight.common.light.StarLightEngine`, PaperMC/Starlight, `fabric` branch.
   <https://github.com/PaperMC/Starlight/blob/fabric/src/main/java/ca/spottedleaf/starlight/common/light/StarLightEngine.java>
5. *Phosphor* (phosphor-fabric), CaffeineMC; repository archived 12 May 2025. Source tree of the
   `mc1.18.x-0.8.1` tag and the `1.19.x/dev` branch.
   <https://github.com/CaffeineMC/phosphor-fabric>
6. `net.caffeinemc.phosphor.common.block.BlockStateLightInfo`, phosphor-fabric, tag `mc1.18.x-0.8.1`.
   <https://github.com/CaffeineMC/phosphor-fabric/blob/mc1.18.x-0.8.1/src/main/java/net/caffeinemc/phosphor/common/block/BlockStateLightInfo.java>
7. Minestom `2026.06.20-26.1.2`, `net.minestom.server.instance.light.LightCompute` and
   `net.minestom.server.instance.light.BlockLight`. Read from the published sources jar; the types
   are package-private and have no stable per-version web permalink.
   <https://github.com/Minestom/Minestom>
8. Minestom `2026.06.20-26.1.2`, `net.minestom.server.instance.LightingChunk` and
   `net.minestom.server.instance.InstanceContainer` (the `setChunkSupplier(DynamicChunk::new)` call in
   the constructor). Read from the same sources jar.
   <https://github.com/Minestom/Minestom>
9. Ben Arnold, *Fast Flood Fill Lighting in a Blocky Voxel Game*, Seed of Andromeda.
   <https://www.seedofandromeda.com/blogs/29-fast-flood-fill-lighting-in-a-blocky-voxel-game-pt-1> —
   **the original site does not serve a verifiable certificate**; a request to it fails during the
   TLS handshake rather than returning any status, re-checked when this page was last revised. The
   content behind it was therefore not read for this document.
   9a. Archived and adapted rendering of the same series, which does resolve:
   <https://notverymoe.github.io/md-gamedev-gems/voxel/lighting/soa/index.html>
10. *Status — Anvil chunk loader and light engine*, this repository, [Project Status](../project-record/project-status.md).
11. *Measured results*, this repository, [Measured results](../reference/measured-results.md).
12. *Light engine*, this repository, [How the light engine works](how-the-light-engine-works.md).
13. *Benchmarks*, this repository, [What the benchmarks establish](what-the-benchmarks-establish.md).
14. *Research: a faster, lower-memory light engine*, this repository,
    [Research: Light Engine](../project-record/research/light-engine.md).
15. Falco sources read directly for this document:
    `falco-light/src/main/java/net/onelitefeather/falco/light/LightPropagator.java`,
    `SectionOpacity.java`, `ChunkLightService.java`, and
    `falco-light/src/test/java/net/onelitefeather/falco/light/LightEngineEquivalenceTest.java`.
