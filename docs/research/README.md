# Research notes

Findings from the multi-agent investigations run while building the experimental Anvil chunk loader
and the instance. They are kept because each one answers a question that cost real effort to answer
and that will be asked again: *can we replace this part of Minestom, and is it worth it?*

Every claim in these documents was verified against the sources of Minestom `2026.06.20-26.1.2` or
by compiling and running probe code against that jar. Where a number is a measurement, the document
says so and names the probe. Where the agents disagreed, both positions are recorded rather than
silently resolved.

| Document | Question | Verdict |
| --- | --- | --- |
| [exception-hierarchy.md](exception-hierarchy.md) | A dedicated checked/unchecked exception hierarchy for the Anvil package | Feasible, design ready, one open decision |
| [instance-container.md](instance-container.md) | A multithreaded, "1:1 compatible" `InstanceContainer` replacement | **Partial** — compiles and runs, but 1:1 compatibility is not reachable and the performance premise is wrong |
| [light-engine.md](light-engine.md) | A faster, lower-memory light engine | **Feasible and measurably faster**, but worth nothing for pre-lit worlds |
| [shared-instances-and-batches.md](shared-instances-and-batches.md) | Shared instances and batch integration for `FalcoInstance` | **Split** — shared instances are walled off but the capability is cheaply rebuildable; the batch gap writes a field nothing can read |

## The recurring lesson

Three of these investigations started from "replace component X of Minestom". The answers differed
sharply, and the difference was never obvious in advance:

- **Palette** (investigated earlier, no document): impossible. `sealed interface Palette permits
  PaletteImpl` is a hard compiler error, and `Section` is a record holding that exact type.
- **`InstanceContainer`**: possible but pointless in the intended form — the parallelism the request
  targeted lives somewhere else entirely.
- **`Light`**: possible, and a prototype was 3.1×–5.8× faster with bit-identical output — but the
  code path does not execute at all for the workload Falco actually has.
- **`SharedInstance` and the batches**: the shared-instance wall is real, yet the *capability* behind
  it is twenty lines of public API — while the batch gap everyone worried about writes a field that
  no code reachable from a foreign instance ever reads.

The pattern: *sealed-ness decides whether it is possible, and the profile decides whether it is
worth it.* Both have to be checked before designing anything, and neither can be guessed. The fourth
investigation adds a third question to ask first: *who reads the thing we are missing?* Twice now the
answer was "nobody".
