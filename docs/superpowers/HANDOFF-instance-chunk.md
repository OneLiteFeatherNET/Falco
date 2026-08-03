# Handoff: Falco's own chunk, instance and shared instance

Written 2026-08-02 while stage 2 was still finishing, updated 2026-08-03 after all four stages
landed and both pull requests went green. Read this first if you are picking the work up in a new
session; it says where things are, what is proven, and which mistakes this project has already paid
for twice.

## Where the work lives

| Path | Branch | Contents |
|---|---|---|
| `Falco-worktrees/block-storage` | `feat/block-storage` | **the implementation.** Stages 1 to 3. PR #39. |
| `Falco-worktrees/shared-instance` | `feat/shared-instance` | stage 4, stacked on the branch above. PR #40. |
| `Falco-worktrees/ci-dispatch` | `ci/build-pr-dispatch` | PR #42, a manual trigger for the build. Unrelated to the storage work. |
| `Falco-worktrees/falco-bom` | `feat/falco-bom` | spec, stage 1 plan, the benchmark suite. Also carries ~38 uncommitted files of a *foreign* docs migration — not this work's, do not commit them. |
| `/mnt/projects/oss/onelitefeather/Falco` | varies | the main tree. **Another session works here.** Never write to it. |

**#40 targets `feat/block-storage`, not `main`.** They merge in that order, and a change that has to
reach both goes into `block-storage` first and is then merged forward.

Everything below refers to the `block-storage` worktree unless stated otherwise.

## The documents, in reading order

1. `docs/superpowers/research/2026-08-01-instance-chunk-research.md` — the research, 531 lines.
   Chapter 9 lists the 19 of 44 claims that an adversarial pass killed. Read it before trusting any
   assertion about Minestom that is not in a test. **It exists only in the `falco-bom` worktree**,
   not in this one and not on any branch that has an open pull request, so nothing currently on its
   way to `main` carries it. Everything below points back at it; if that branch is dropped, the
   reasoning behind every design decision here goes with it.
2. `docs/superpowers/specs/2026-08-01-falco-instance-chunk-design.md` — the spec. Four stages,
   22 user stories in EARS syntax, 9 non-functional requirements. Chapter 2 is the measurement table
   every architectural choice points back at.
3. `docs/superpowers/plans/2026-08-01-falco-block-storage.md` — stage 1, with `## Stage 1 result`.
4. `docs/superpowers/plans/2026-08-02-falco-lazy-sections.md` — stage 2, ten tasks.
5. `docs/superpowers/plans/2026-08-02-falco-instance-facade.md` — stage 3, twelve tasks.
6. `docs/superpowers/plans/2026-08-02-falco-shared-instance.md` — stage 4, eight tasks (in the
   `shared-instance` worktree).
7. `.superpowers/sdd/*/progress.md` — the ledgers, one directory per stage. One line per commit,
   written by the implementers themselves. **These are the recovery map**; trust them and `git log`
   over any recollection.

## State

**Stage 1 — done, reviewed, merged into the branch.** `FalcoChunk` moved from `extends DynamicChunk`
to `extends Chunk` holding a `BlockStorage`. The seam costs one object, 24 bytes per chunk. A final
whole-branch review found five Important defects, all fixed — two were real regressions no test saw
(a dropped `requireNonNullElse(…, Block.AIR)` and both biome registry guards).

**Stage 2 — done.** All ten tasks, acceptance recorded in `## Stage 2 result` at the end of the stage 2
plan. Empty sections share one flyweight, the generator stages through `view(int)` and packs on commit
behind a guard, heightmaps are built on demand, and the two block maps became one plus a counter.
A fresh chunk fell from 192 objects / 6 848 B to **25 / 840**, which is −87.7 %; a filled chunk saves
104 B and nothing more, because the flyweight pays for sections that hold nothing.

**Stage 2 — reviewed.** The final whole-branch review found four defects, all fixed in one wave
(`.superpowers/sdd/2026-08-02-falco-lazy-sections/final-review-fix-report.md`). Two were real and
neither had a test: materialising a section was an unsynchronised read-modify-write reachable from
three lock-free Minestom call sites and could lose a block silently, and the generator wrote its
special blocks inside the commit loop, which latched both heightmaps over a half-committed chunk for
the life of the chunk. The other two were figures that had gone stale, one of them in the table below.

**Stage 3 — done and reviewed.** The facade split of `FalcoInstance`, which had grown to 1 119 lines
doing registry, loading, block writing, generation and persistence, into four parts it delegates to,
plus lifecycle listeners and the viewer-cache cleanup. `InstanceFacadeTest` pins that it declares
exactly four instance fields, so a fifth kills the test. The stage also moved `FalcoLightingChunk`
from `DynamicChunk` onto `FalcoChunk` (US-3.06) — the point the whole rewrite was aiming at, and the
change that broke binary compatibility, see below. Acceptance in `## Stage 3 result`, ledger at
`.superpowers/sdd/2026-08-02-falco-instance-facade/progress.md`.

**Stage 4 — done and reviewed.** `FalcoSharedInstance`, with the constructor guard and the save path
that reports through the returned future alone. Acceptance in `## Stage 4 result`, ledger at
`.superpowers/sdd/2026-08-02-falco-shared-instance/progress.md`.

**Both pull requests are green and out of draft** as of 2026-08-03 14:00, on all three runners.
Neither has been reviewed by a human yet.

## What is measured, and what is not

**Citable** — JOL and counting tests are deterministic and were taken on a loaded machine without harm:

| | |
|---|---|
| fresh chunk, Minestom | 192 objects, 6 848 B |
| fresh chunk, Falco after stage 2 | **25 objects, 840 B** |
| materialisation: fresh chunk / pure read | 0 / 0 sections |
| one `setBlock` at y=64 | 10 sections — the heightmap descent, not the write |
| write order y=200 then y=−64 / reverse | 18 / 3 — a factor of six |
| `getSections()` | 24 — it is a write in disguise |
| generation of y=−64..0 | 4 of 24 |
| empty section share, real generated overworld | 62.24 % (441 finished chunks around one spawn) |
| palette break-even, indirect against direct | between 192 and 224 entries |
| Minestom's viewer cache leak | 1 entry per chunk construction, 257 B, never removed |

If you find **32 objects / 2 088 B** for the fresh Falco chunk in an older task report, it is the same
measurement taken with tasks 2 and 3 in place and tasks 7 and 8 not yet written. The difference is the
four objects of the two heightmaps and the three of the second block map. `ChunkFootprintTest` says
which is current; it is the only thing that does.

**Not citable** — every timing figure taken during this work. The machine ran at load 4.4 to 7.0
throughout (a Minecraft client, an IDE and several agent sessions). They establish direction, never
magnitude. Among them: the 6.2×/7.3× on `setBlock` contention, the 765 ms / 86.5 MB chunk resend,
the 24× cost of `optimize()`.

**The full JMH run has never happened.** `docs/benchmarks/full-run.sh` (in the `falco-bom` worktree)
takes 2 h 35 min at three forks and refuses to start above load 1.5. It needs an idle machine. Until
it has run, no timing figure from this work belongs in the README or the wiki.

## The mistake this project keeps making

Six times in one session, a check did something other than what it claimed. Every time the result
looked plausible first:

1. The census counted a void hub world and reported it as an overworld — 99.6 % against the real 62.2 %.
2. A copy benchmark measured Minestom's viewer cache leak instead of a copy, and reported Falco as
   forty times faster than code that does strictly more work.
3. `BlockStorageTest` stayed green with the `- minSection` term deleted from every method, because
   all five cases used y = 0..3 where the term contributes nothing.
4. The equivalence check materialised the chunk whose footprint was about to be measured, so every
   number after it measured the check. This one hid the entire stage 2 saving.
5. Stage 1 dropped two Minestom guards and no test noticed, because no test ever touched a biome.
6. The stage 2 task briefs carried materialisation counts of 1/1/17 that measurement corrected to
   0/1/10.

**Therefore, in every agent brief:** *Would your test still be green without the implementation?*
and *Does your measurement measure itself?* Both questions have earned their place. Implementers now
prove their tests bite by mutation, and several have caught their own briefs being wrong.

## Traps that cost time

- **The Minestom clone at `/mnt/projects/oss/minestom/Minestom` is ten months stale.** It caused
  eleven false findings in the research. The canonical reference is the unpacked sources jar of the
  pinned version, at
  `/tmp/claude-1000/-mnt-projects-oss-onelitefeather-Falco/34edb948-9dfe-4540-9666-9e29f0d44d7b/scratchpad/minestom-src`
  (re-unpack from `~/.gradle/caches/…/minestom-2026.06.20-26.1.2-sources.jar` if the scratchpad is gone).
- **`getSections()`, `getSection(int)` and `Heightmap#getHeight` materialise.** For reading only,
  `BlockStorage` has `view(int)`, `views()` and `materialisedSections()`.
- **`Palette#compare` cannot compare content across a mode change** — it compares the `count` field,
  which carries the value itself in single-value mode.
- **`optimize()` is not free and not always useful.** It gives up above `maxBitsPerEntry = 8`, so on
  a wide palette it charges full price for nothing. `PaletteCompaction` asks first.
- **Long background runs do not survive a session change.** Three died mid-work here, each with
  finished but uncommitted changes. Recovery was possible only through `git status` and the reports.
  Keep runs to two tasks, and make implementers write the ledger after every commit.
- **Region file size says nothing about terrain density.** Anvil pads every chunk to whole 4096-byte
  sectors; a 4.3 MB file held 601 KB of payload.

## What the build and the CI do that will surprise you

Four things cost most of an afternoon on 2026-08-03. None is in the code this work wrote.

**A conflicted pull request produces no CI run at all.** Not a failed one, not a skipped one — none.
GitHub cannot form `refs/pull/N/merge` for a PR that conflicts with its base, and no run is created.
When `272cb0b3` landed on `main` at 21:07 UTC and put `FalcoLightingChunk.java` into conflict, both
PRs sat without CI for fifteen hours and the Actions page said nothing. The symptom looks exactly
like a disabled repository or an exhausted quota, and both were checked before the real cause was
found. **If runs stop appearing, check `gh pr view N --json mergeable` first.**

**`:falco-benchmarks:test` hangs on macOS and is skipped there.** The test JVM starts and no test
ever reports; the module writes zero-byte result files, the job goes silent for as long as you let
it, and the runner terminates orphan `java` processes at the end. It is the only module whose test
JVM runs with `allowAttachSelf`, `EnableDynamicAgentLoading`, `jol.magicFieldOffset` and a 4 GB heap
on a 7 GB runner, which is what jol needs to measure retained size. **Which of those hangs on arm64
is not diagnosed.** `-Pfalco.macOsFootprintTests` forces the task back on for whoever picks it up;
`docs/benchmarks/README.md` has the observation and what the skip costs.

**`ChunkFootprintTest` has a rare flake, and its shape is worth knowing.** One ubuntu run reported
`-2 objects of [B` where a difference of zero was expected. The value is the set of objects that
exist because the chunk exists, computed as (chunk + instance) minus (instance alone) from two
separate walks — and a set has no negative cardinality, so the two walks did not see the same
instance state. Reproduced neither locally (8 runs, 3 of them pinned to two cores) nor on the rerun
of the same job. Recorded as a comment on PR #39. The tempting wrong fix is to loosen the assertion;
the right one is to make both walks see the same state and to report a negative difference as an
invalid measurement rather than compare it.

**japicmp exceptions live in `gradle/api-breaks.properties` and expire on their own.** The file lists
each deliberately accepted break with its reason and names the baseline it was judged against; the
build fails if that drifts from `apiBaselineVersion`, so an exception cannot outlive the release
that absorbs it. Currently one entry: `FalcoLightingChunk` became `final` under US-3.06. Two of the
three findings japicmp reports for that class are wrong — `setBlock(…, Placement, Destroy)` and
`tick(long)` are still public on `FalcoChunk` and reach callers by inheritance, which japicmp cannot
see because that class is in another module and `ignoreMissingClasses` is on.

## Open defect, found during the merge and deliberately not fixed

`FalcoChunk#tick(long)` iterates `this.entries` with no lock, and `Int2ObjectOpenHashMap` is not
thread-safe. A concurrent `setBlock` that rehashes the map while the tick thread walks it can yield
garbage or spin. The tick thread never holds the chunk lock — `ThreadDispatcher` registers the chunk
as a `Tickable` and `TickThread` calls it under its own lock — and Minestom's `Chunk#tick` contract
says outright that the method "doesn't necessary have to be thread-safe".

**Upstream `DynamicChunk` has the identical race** with its `tickableMap` (`DynamicChunk.java:185-186`),
so this is inherited rather than introduced by the storage rewrite. ArchUnit cannot see it: the field
is `final`, so `sharedStateIsSafelyPublished` skips it by construction.

Fixing it is a design decision — take the read lock in `tick`, make the map concurrent, or confine
writes to the chunk's tick thread — and each option has a cost on a path that runs for every chunk
every tick. It belongs to stage 3, which owns the lifecycle, and it is recorded here so that the next
reader meets it as a known open item rather than as a surprise.

## How to continue

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
git log --oneline 6ec6973..HEAD                     # everything the four stages did
cat .superpowers/sdd/*/progress.md                  # the ledgers, in stage order
./gradlew build                                     # not :module:test — see below
```

**Run `./gradlew build`, not the individual test tasks.** A whole session ran `:module:test` only
and never saw that `:falco-light:checkApiCompatibility` had been failing since stage 3. The test
tasks are green while the build is red, and the difference is exactly the checks that guard the
published API.

`ChunkFootprintTest` was deliberately red from stage 1 until task 9 of stage 2 reset its
expectation. If it is red now, check the ledger before assuming a regression — and if it says
`-2 objects`, it is the flake described above, not a defect in the code.

All four stages are implemented. What is left is listed under the open items above: the tick race,
the macOS hang, the footprint flake, and the JMH baseline that has still never run. Should more
implementation follow, the shape that worked here was: one plan per stage under
`docs/superpowers/plans/`, tasks with full code and TDD cycles, then a workflow of at most two tasks
per run with a fresh implementer and a fresh reviewer each.
