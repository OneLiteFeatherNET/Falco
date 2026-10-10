# Research notes

Findings from the investigations run while building the experimental Anvil chunk loader and the
instance. They are kept because each one answers a question that cost real effort to answer and that
will be asked again: *can we replace this part of Minestom, and is it worth it?* This page says what
each document is, how it was produced, and how much weight a reader should give it. It is not itself
a source for any claim; every fact lives in the document that established it.

## How these documents were produced, and what that licenses

Each was written by agents, not by a person reading the code by hand and not by the benchmark
harness. The two methods used were: reading the Minestom sources at `2026.06.20-26.1.2` (the sources
jar from the Gradle cache, 1 454 files), and compiling and running probe code against the binary jar
of that version. The probes live outside this repository and are not checked in. Stating that plainly
matters more than it costs, because it decides exactly how far each kind of finding carries.

**The structural findings carry.** *`Light` is not sealed. `EntityTracker` is.
`InstanceManager.unregisterInstance` takes an `instanceof InstanceContainer` branch and skips the
cleanup for anything else. `Chunk#onLoad` and `Chunk#unload` are `protected`, so any subclass in any
package may call them on `this`.* Every one of those is checkable in ten minutes by anyone with the
same jar, and several were re-checked against it while this page was written. They do not depend on
who ran the investigation or on what machine.

**The numbers do not carry.** Every figure in these documents — 5.8×, 12×, 19.8×, 2.2 ms per chunk,
≈400 000× — came from a standalone probe with no recorded machine, no recorded run configuration and
no uncertainty. **None of them came from the JMH harness, and none can be re-run from this
repository.** They are kept because deleting a result is worse than labelling it, and because the
*direction* of most of them is independently supported by a structural argument in the same document.
They are not evidence of the same kind as a benchmark table, and no page in this documentation should quote
one as if it were.

That fixes the ordering. The measured record is [What the benchmarks establish](../../explanation/what-the-benchmarks-establish.md) and
[Measured results](../../reference/measured-results.md). The reasoning that rests on it is [the documentation index](../../README.md),
where every claim is labelled by how it was established. These pages sit below both. Where a research
document and a rationale page disagree, the rationale page wins: [Rationale: Instances and
Chunks](../../explanation/why-falco-instance-exists.md) discards the 19.8× figure from [Research: Instance
Container](instance-container.md) as non-reproducible, and it is right to.

## The documents

| Document | Question | Verdict |
| --- | --- | --- |
| [Exception Hierarchy](exception-hierarchy.md) | A dedicated checked/unchecked exception hierarchy for the Anvil package | Feasible, design ready, one open decision — and two of its premises about *this* repository are wrong |
| [Instance Container](instance-container.md) | A multithreaded, "1:1 compatible" `InstanceContainer` replacement | **Partial** — compiles and runs, but 1:1 compatibility is not reachable and the performance premise is wrong |
| [Light Engine](light-engine.md) | A faster, lower-memory light engine | **Feasible**, and a prototype was faster in an unrepeated probe, but worth nothing for pre-lit worlds |
| [Shared Instances and Batches](shared-instances-and-batches.md) | Shared instances and batch integration for `FalcoInstance` | **Split** — shared instances are walled off but the capability is cheaply rebuildable; the batch gap writes a field nothing can read |
| [Instance Performance](instance-performance-research.md) | Where a speed advantage for `falco-instance` could exist at all | **Hypothesis only.** Nothing in it is measured, and the module claims no speed advantage |
| [Fluent API](fluent-api.md) | A fluent construction surface for the three modules — builders, terminal methods, lifecycle and observer slots | **Built**, as [#16](https://github.com/OneLiteFeatherNET/Falco/pull/16). The page is the investigation as it stood before, kept unrewritten; the code departs from it in three places, each marked where it happens |

**Provenance.** The first three were written on 2026-07-31 and entered the repository at
[`fc0aef5`](https://github.com/OneLiteFeatherNET/Falco/commit/fc0aef5); *Shared Instances and
Batches* the same day at
[`eee1cf2`](https://github.com/OneLiteFeatherNET/Falco/commit/eee1cf2), with the section on how the
batch gap was actually closed added at
[`a83ed0b`](https://github.com/OneLiteFeatherNET/Falco/commit/a83ed0b). *Instance Performance* was
carried out on 2026-08-01 against Falco `0.3.0`. All five were reviewed against the sources at
`ca79507`; where a claim did not survive that review it is corrected and marked on the page itself,
not here.

**[Instance Performance](instance-performance-research.md) is the odd one out** and is labelled as such
on its own page. It is not a report of an investigation that concluded — it is a catalogue of places
where a speed advantage *could* exist and of what would have to be built and measured before anyone
could say. It contains no measurement, and the repository's position that `falco-instance` claims no
speed gain is unchanged by it.

## The recurring lesson

Three of these investigations started from "replace component X of Minestom". The answers differed
sharply, and the difference was never obvious in advance:

- **Palette** (investigated earlier, no document): impossible. `sealed interface Palette permits
  PaletteImpl` is a hard compiler error, and `Section` is a record holding that exact type.
- **`InstanceContainer`**: possible but pointless in the intended form — the parallelism the request
  targeted lives somewhere else entirely.
- **`Light`**: possible, and a prototype measured 3.1×–5.8× faster with bit-identical output in an
  unrepeated probe — but the code path does not execute at all for the workload Falco actually has.
- **`SharedInstance` and the batches**: the shared-instance wall is real, yet the *capability* behind
  it is twenty lines of public API — while the batch gap everyone worried about writes a field that
  no code reachable from a foreign instance ever reads.

The pattern: *sealed-ness decides whether it is possible, and the profile decides whether it is
worth it.* Both have to be checked before designing anything, and neither can be guessed. The fourth
investigation adds a third question to ask first: *who reads the thing we are missing?* Twice now the
answer was "nobody".

There is a fourth lesson, and it is about these documents rather than about Minestom. An
investigation that names its method survives being checked; one that does not is indistinguishable
from an opinion a year later. Two premises in [Exception Hierarchy](exception-hierarchy.md)
turned out to describe a different codebase entirely, and they were found only because someone went
back to the source. Anything written here should say what it read and when.
