# Rationale

Why Falco is built the way it is, and what the reasoning rests on. The rest of the documentation
describes what the code does; these five documents record why it was done at all, which alternatives
were rejected and for what reason, and where the argument is weaker than the headline figures
suggest. They are written for someone deciding whether to trust this code — and for whoever here
proposes to re-open one of these questions in a year. Every claim in them is labelled by how it was
established: a **measurement** naming its benchmark and conditions, a statement read from someone
else's **source** naming the file and version, or an **argument** from the properties of the design.
Where a limit, a defect or a case against the design exists, it is written down at the same length
as the successes.

- [`chunk-loading.md`](chunk-loading.md) — Minestom already ships a working Anvil loader, so why is
  there a second one? Where the built-in parallelism is nominal rather than real, why the answer is
  a three-stage pipeline rather than a finer lock, why a failed read throws instead of reporting the
  chunk as absent, and the cases in which the built-in loader remains the better tool.
- [`lighting.md`](lighting.md) — Given that Minestom computes light, and that Starlight and Phosphor
  exist, why does `falco-light` exist and why is its algorithm shaped as it is? The reason turns out
  to be structural rather than a benchmark, and most of the arguments that looked like reasons —
  including the widely quoted Starlight factors — did not survive being checked.
- [`instances-and-chunks.md`](instances-and-chunks.md) — Why `falco-instance` exists although it
  claims no speed advantage over `InstanceContainer`. The four places where Minestom silently takes
  a different path for a foreign `Instance`, the chunk leak that is the whole argument for the
  module, and the things it deliberately refuses to do.
- [`concurrency.md`](concurrency.md) — Why the region files are guarded by a per-entry seqlock, a
  usage count and a hard refusal after `close()`, rather than by the locks a reviewer would reach
  for first. Includes the five races that would all have failed silently, a sixth that only Windows
  could show, and why the stress tests run on platform threads.
- [`measurement.md`](measurement.md) — Falco claims factors; why should someone who did not run the
  benchmarks believe them? How the harness is built, which parameter values were chosen and why,
  and — at greater length than the flattering parts — which numbers are thin, which must never be
  quoted as a factor, and what it takes to re-run any of it yourself.
