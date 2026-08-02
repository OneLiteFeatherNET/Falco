# Handoff: Falco's own chunk, instance and shared instance

Written 2026-08-02, while stage 2 was still finishing. Read this first if you are picking the work
up in a new session; it says where things are, what is proven, and which mistakes this project has
already paid for twice.

## Where the work lives

| Path | Branch | Contents |
|---|---|---|
| `Falco-worktrees/block-storage` | `feat/block-storage` | **the implementation.** Stages 1 and 2. |
| `Falco-worktrees/falco-bom` | `feat/falco-bom` | spec, stage 1 plan, the benchmark suite. Also carries ~38 uncommitted files of a *foreign* docs migration — not this work's, do not commit them. |
| `/mnt/projects/oss/onelitefeather/Falco` | varies | the main tree. **Another session works here.** Never write to it. |

Everything below refers to the `block-storage` worktree unless stated otherwise.

## The documents, in reading order

1. `docs/superpowers/research/2026-08-01-instance-chunk-research.md` — the research, 531 lines.
   Chapter 9 lists the 19 of 44 claims that an adversarial pass killed. Read it before trusting any
   assertion about Minestom that is not in a test.
2. `docs/superpowers/specs/2026-08-01-falco-instance-chunk-design.md` — the spec. Four stages,
   22 user stories in EARS syntax, 9 non-functional requirements. Chapter 2 is the measurement table
   every architectural choice points back at.
3. `docs/superpowers/plans/2026-08-01-falco-block-storage.md` — stage 1, with `## Stage 1 result`.
4. `docs/superpowers/plans/2026-08-02-falco-lazy-sections.md` — stage 2, ten tasks.
5. `.superpowers/sdd/2026-08-02-falco-lazy-sections/progress.md` — the ledger. One line per commit,
   written by the implementers themselves. **This is the recovery map**; trust it and `git log` over
   any recollection.

## State

**Stage 1 — done, reviewed, merged into the branch.** `FalcoChunk` moved from `extends DynamicChunk`
to `extends Chunk` holding a `BlockStorage`. The seam costs one object, 24 bytes per chunk. A final
whole-branch review found five Important defects, all fixed — two were real regressions no test saw
(a dropped `requireNonNullElse(…, Block.AIR)` and both biome registry guards).

**Stage 2 — tasks 1 to 9 done, task 10 (acceptance) was running when this was written.** Check
`git log` and the ledger for the true state. Empty sections now share one flyweight, the generator
stages through `view(int)` and packs on commit behind a guard, heightmaps are built on demand.

**Stages 3 and 4 — specified, not planned.** Stage 3 is the facade split of `FalcoInstance`
(1119 lines doing registry, loading, block writing, generation and persistence) plus lifecycle
listeners and the viewer-cache cleanup. Stage 4 is `FalcoSharedInstance extends SharedInstance`.

## What is measured, and what is not

**Citable** — JOL and counting tests are deterministic and were taken on a loaded machine without harm:

| | |
|---|---|
| fresh chunk, Minestom | 192 objects, 6 848 B |
| fresh chunk, Falco after stage 2 | **32 objects, 2 088 B** |
| materialisation: fresh chunk / pure read | 0 / 0 sections |
| one `setBlock` at y=64 | 10 sections — the heightmap descent, not the write |
| write order y=200 then y=−64 / reverse | 18 / 3 — a factor of six |
| `getSections()` | 24 — it is a write in disguise |
| generation of y=−64..0 | 4 of 24 |
| empty section share, real generated overworld | 62.24 % (441 finished chunks around one spawn) |
| palette break-even, indirect against direct | between 192 and 224 entries |
| Minestom's viewer cache leak | 1 entry per chunk construction, 257 B, never removed |

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

## How to continue

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
git log --oneline 6ec6973..HEAD                     # what stages 1 and 2 did
cat .superpowers/sdd/2026-08-02-falco-lazy-sections/progress.md   # the ledger
./gradlew :falco-instance:test :falco-light:test :falco-anvil:test
```

`ChunkFootprintTest` was deliberately red from stage 1 until task 9 reset its expectation. If it is
still red, check the ledger for whether task 9 landed before assuming a regression.

To plan stage 3 or 4, use the same shape that worked here: one plan per stage under
`docs/superpowers/plans/`, tasks with full code and TDD cycles, then a workflow of at most two tasks
per run with a fresh implementer and a fresh reviewer each.
