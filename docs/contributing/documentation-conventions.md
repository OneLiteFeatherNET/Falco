# Documentation conventions

How this documentation is organised, what an explanation page may claim, and which page owns which fact.

The documentation follows [Diátaxis](https://diataxis.fr): a page is a **tutorial** (learning by doing), a
**how-to** (a task you already know you want), a **reference** (a value you look up), or an
**explanation** (why it is built this way). No page serves two of those at once — where prose would
have to switch mode, it links instead. `Project Status` and the `Research` pages sit outside the
four on purpose: they are the operational record of the project, not documentation of the software.

This page establishes nothing itself: it carries no measured number, cites no source, and every fact
it refers to is owned by one of the pages below. If a statement here and a statement there disagree,
the other page is the one that is right.

## What an explanation page may claim, and what it may not

Rationale is the easiest kind of documentation to fake, because a reason can be made to sound
reasonable after the fact whatever it is actually resting on. The explanation pages therefore label
every assertion as one of four kinds, and no sentence mixes two:

| Kind | What it must carry | How a reader checks it |
| --- | --- | --- |
| **Measured** | the benchmark class and method, the parameter values, the run configuration and the uncertainty — or a link to the table that carries all four | re-run the benchmark |
| **Structural** | a named file, type, method, field or constant of Falco's own source | open the file |
| **Third party** | the exact Minestom, Starlight or Phosphor type and member, with a file and line, at a named version, branch or tag | open that project's source at that revision |
| **Judgement** | an explicit marker that it is one — "was rejected because", "the design assumes", "not measured here", "argued and not measured" | argue with it |

Each page opens by naming the kinds it uses, and the module pages name three rather than four: they
treat reading Falco's source and reading somebody else's as one activity, because the check a reader
performs is the same one. The distinction that matters is preserved anyway, because a foreign source
is never cited without its project and the revision it was read at. What no page may do is state a
judgement in the grammar of a result, or a third-party claim without the citation that makes it
checkable, in the same sentence that draws a conclusion from it.

Three rules follow, and they are what a hostile reader should hold these pages to.

**A rejected alternative must say on what basis it was rejected** — measured, reasoned from the
source, or out of scope. A rejection with no stated basis is a rationalisation with a table around
it, and every such row on the pages below names its basis. Where the basis is "reasoned", the row
also says that the alternative was never built, so nobody here can say what it would have cost: a
rejection argued from a mechanism is a good reason and a weak measurement, and it is written as the
first rather than dressed as the second.

**No rationale page is where a number is first established.** Every measured figure quoted here
belongs to a table in [What the benchmarks establish](../explanation/what-the-benchmarks-establish.md) or [Measured results](../reference/measured-results.md) and is
quoted with its conditions or with a link to them. The single authoritative statement of what the `±`
after a JMH mean means lives in [What a measurement here means](../explanation/what-a-measurement-here-means.md) and is linked from
wherever it is needed rather than restated. Every table drawn from a one-fork benchmark carries that
limit in its own provenance line rather than in a page footer, because a disclaimer is worth
something only where the number is.

**Claims about somebody else's code carry a revision.** Minestom is read from the sources jar of
`net.minestom:minestom:2026.09.12-26.2`, which is the version this repository compiles against —
`settings.gradle.kts` declares `minestom` `withoutVersion()` and takes the version from
`net.onelitefeather:mycelium-bom:1.8.7`, whose `dependencyManagement` pins exactly
`2026.09.12-26.2`. That is a resolved version and not a pinned one: republishing the BOM changes
the Minestom side of every comparison without any commit here. Each such claim names the file, the
line and the member, so it can be checked in a minute and so it reads as analysis of a specific
revision rather than as a verdict on another project. The same rule governs Starlight and Phosphor
on [Lighting](../explanation/why-a-custom-light-engine.md), with the qualification that those are cited by branch or tag
rather than by a published artifact version, and a branch moves.

Four things a rationale page may therefore not do, stated as the prohibitions they are. It may not
introduce a factor that no table on another page carries, because a ratio quoted without its
operands' intervals cannot be checked. It may not present an intention as an outcome — "the pipeline
is for moving that work out of the lock" is admissible, "the pipeline removes the contention" is not,
unless a measured claim carrying its conditions follows immediately. It may not describe another
project's behaviour with a verdict word unless the citation travels in the same sentence. And it may
not omit the case where Falco loses: the losing rows, the ties and the regressions are on these
pages on purpose, and a page that has one and does not show it is wrong, not concise.

## Who owns which fact

Read [Measurement](../explanation/what-a-measurement-here-means.md) first if you intend to quote a number from any of the
others; read the module page first if you are deciding whether to depend on a module. Each page owns
a distinct set of claims and the others link to it rather than restating them.

- [Chunk Loading](../explanation/why-a-second-anvil-loader.md) — Minestom already ships a working Anvil loader, so why is
  there a second one? Where the built-in parallelism is nominal rather than real, why the answer is
  a three-stage pipeline rather than a finer lock, why a failed read throws instead of reporting the
  chunk as absent, and the cases in which the built-in loader remains the better tool. **Owns** the
  loader comparison, the save-stage decomposition, and everything the Anvil format itself forces.
- [Lighting](../explanation/why-a-custom-light-engine.md) — Given that Minestom computes light, and that Starlight and Phosphor
  exist, why does `falco-light` exist and why is its algorithm shaped as it is? The reason turns out
  to be structural rather than a benchmark, and most of the arguments that looked like reasons —
  including the widely quoted Starlight factors — did not survive being checked. **Owns** the light
  engine comparison and every claim about Starlight and Phosphor.
- [Why falco-instance exists](../explanation/why-falco-instance-exists.md) — Why `falco-instance` exists although it
  claims no speed advantage over `InstanceContainer`. The four places where Minestom silently takes
  a different path for a foreign `Instance`, the chunk leak that is the whole argument for the
  module, and the things it deliberately refuses to do. **Owns** nothing measured: the module has no
  benchmark and the page says so rather than borrowing one.
- [Concurrency](../explanation/how-the-concurrency-design-works.md) — Why the region files are guarded by a per-entry seqlock, a
  usage count and a hard refusal after `close()`, rather than by the locks a reviewer would reach
  for first. Includes the five races that would all have failed silently, a sixth that only Windows
  could show, and why the stress tests run on platform threads. **Owns** the concurrency design and
  the defect record; it quotes the loader tables from Chunk Loading and does not own them.
- [Measurement](../explanation/what-a-measurement-here-means.md) — Falco claims factors; why should someone who did not run the
  benchmarks believe them? How the harness is built, which parameter values were chosen and why,
  and — at greater length than the flattering parts — which numbers are thin, which must never be
  quoted as a factor, and what it takes to re-run any of it yourself. **Owns** the meaning of `±`,
  the per-class run configurations, and the list of numbers that may not be quoted as ratios.

Two of the five own no measurement. [Why falco-instance exists](../explanation/why-falco-instance-exists.md) has no
benchmark to own and quotes no measured table; [Concurrency](../explanation/how-the-concurrency-design-works.md) reproduces the
loader read table and points at [Chunk Loading](../explanation/why-a-second-anvil-loader.md) as its owner. The other
three follow one rule about the tables they do own: a table of measured values carries its own
provenance line directly beneath it — class and methods, parameter values, thread count, forks,
warmup and measurement iterations, JMH version, machine, commit or the absence of a run record, and
the one-fork clause — because that is the only place a provenance line does any work.

The measured tables themselves live in [Measured results](../reference/measured-results.md), which owns every one of
them, and in [What the benchmarks establish](../explanation/what-the-benchmarks-establish.md), which also says how to re-run them; the working record of
what was measured, what was investigated and refuted, and what is still open is
[Project Status](../project-record/project-status.md). Each of the five pages lists its own sources at the bottom; this
page has none of its own because it makes no claim of its own.
