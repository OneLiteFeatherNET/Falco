# Closing the open evidence gaps in the documentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to execute
> this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Every task states its own
> verification; a task is done when that verification is shown, not when the edit is made.

**Goal:** Close the 26 `TODO(maintainer)` markers in the Falco wiki, in the order that spends effort
where it buys the most evidence. Roughly a third close with a benchmark run that the CI workflow now
makes cheap; a third are arithmetic that no run will fix; the rest are decisions or are
unrecoverable and must be stated as such.

**Non-goal:** New documentation. Every task below either attaches evidence to a claim that lacks it,
corrects a claim the sources contradict, or records that neither is possible.

## Where things are

| | |
| --- | --- |
| Repository | `/mnt/projects/oss/onelitefeather/Falco`, branch `main` |
| Wiki | `/mnt/projects/oss/onelitefeather/Falco.wiki` — a **separate** git repository, branch `master` |
| Documentation standard | `/mnt/projects/oss/onelitefeather/Falco-docs-standard/documentation-standard.md` — binding, local git, no remote |
| Benchmark workflow | `.github/workflows/benchmark.yml`, `profile: custom` or `full` |
| Prior design records | `docs/superpowers/specs/2026-08-01-docs-structure-design.md`, `…/2026-08-02-docs-split-design.md` |

**On the line numbers below.** They were verified against the wiki at `662e4db` and every one of them
hit its marker. They will drift. Treat them as a hint and locate the work by
`grep -rn 'TODO(maintainer)' *.md`, which is the authority — the marker text is stable, the line is
not. Following this plan's own rule about counts bound to a commit, that is the commit.

## Global constraints

Read `documentation-standard.md` before the first edit. It is binding and its §0 opens with the three
prohibitions this plan exists to serve: **never invent, adjust, re-round or extrapolate a measured
number; never weaken a supported claim; never strengthen an unsupported one.**

Beyond that, five rules this project learned by breaking them. They are not style preferences.

- [ ] **After changing a page, grep for its name and read every hit in context.**
      `grep -rn '<Page-Name>' *.md`. Anchored links redirect mechanically; the danger is the sentence
      *without* an anchor whose surrounding prose asserts what the page contains. Eighteen such
      sentences survived one split, on nine pages, four of which were on no change list. This failed
      five times in one session and no tool reports it.
- [ ] **Never publish a measurement from a single fork.** A one-fork run once showed an ordering that
      two forks did not confirm. Two forks minimum, and say so in the provenance line.
- [ ] **Bind every count to a commit.** "seventeen at `a09c71f`", never "seventeen". The benchmark
      class count went 14 → 16 → 17 in one day and broke seven sentences across three files. A count
      in a heading is worse: prefer removing it to updating it.
- [ ] **Never compare figures measured on different machines.** A ratio is no more portable than an
      absolute number: the same light comparison measures 1.35× on a CI runner and 1.13× on the
      machine behind the published tables. CI figures go in their own table with their own provenance
      line, never merged into an existing one.
- [ ] **A checker that has never failed proves nothing.** Before trusting a link or anchor pass,
      inject known-bad targets and confirm each is reported.

## Phase 1 — Runs the CI workflow makes cheap

Five markers, one workflow each. Start here: the evidence is missing rather than wrong, and the tool
already exists. Trigger with `profile: custom`, leave `forks` **empty** so each class annotation
stands — that is what re-running a published table requires. Download the artefact, take the
generated provenance line, and check every field against the run before pasting it.

- [ ] **Save-stage table has no `±`, and `full` was never published.** `Benchmarking:324`.
      Run `ChunkSaveStageBenchmark`. Publish the rows with their intervals and add the `full` row,
      which exists precisely so the sum of the stages can be checked against the whole operation.
      *Verify:* every row carries `±`; the stage sum and `full` are both visible and the text says
      whether they agree.
- [ ] **Palette and opacity figures have no provenance.** `Benchmarking:386`.
      Run `PaletteDataBenchmark.encode` and `SectionOpacityBenchmark.of` at the parameter levels the
      text quotes. *Verify:* the published numbers are from this run, or the old ones are marked as
      superseded and kept — do not silently replace a digit.
- [ ] **A confirmation run is claimed that does not exist.** `Light-Engine:261`.
      Run `LightEngineComparisonBenchmark`. *Verify:* either the repeat is published with its
      conditions, or the sentence claiming a repeat is removed. Note this run becomes a **sixth**
      cross-run repeat — the register under
      *Against the engine Minestom ships with* in `Measured-Results` must be updated, and roughly ten
      sentences across four pages carry that count.
- [ ] **`distinctStates = 8` is declared but never published.** `Measured-Results:183`.
      Same class as its neighbours. *Verify:* the row exists or the reference to it is dropped.
- [ ] **97 % / 63 % have an unclear denominator.** `Rationale-Chunk-Loading:250`.
      Publishing `full` (first task) settles it. *Verify:* the percentages are recomputed from
      published rows and the denominator is named, or they are withdrawn. **They are wrong as they
      stand** — see Phase 2.

## Phase 2 — Arithmetic no run will fix

These are not missing evidence. They are numbers that contradict the rows printed beside them, which
is worse: a reader who checks them finds the error, and every other figure loses credibility.

- [ ] **97 % and 63 % do not follow from the four published rows**, which give 98.0 % and 65.3 %.
      `Measured-Results:132`, `Rationale-Measurement:762`, and the sentence in
      `Rationale-Chunk-Loading:250`. Recompute from the published rows, state the denominator, and
      correct all three together. *Verify:* the arithmetic is shown once and the three pages agree.
- [ ] **The Before ratio column does not follow from its own operands.** `Measured-Results:344`.
      The comment already carries five worked divisions. Decide: recompute the column, or withdraw it
      and say what the pair does establish. *Verify:* every printed ratio is reproducible from the
      two columns beside it.
- [ ] **Iteration time 2 s contradicts `time = 1` in the annotation.** `Benchmarking:141`,
      `Rationale-Measurement:659`. §9.1 of the standard already prescribes the answer: state the
      annotation value and mark the run's setting unrecorded. Do not guess, do not delete the number.
      *Verify:* both pages say the same thing and neither asserts a setting no record supports.

## Phase 3 — Contradictions between pages

- [ ] **"generateChunk always throws" — Rationale and README disagree, and the source decides.**
      `Research-Instance-Container:159`. Read `FalcoInstance` first; correct whichever page the code
      contradicts. *Verify:* quote the member and line that settles it.
- [ ] **A note claims 1 045 µs appears in no published table; it does.** `Rationale-Concurrency:103`.
      It is the one-thread Minestom read of the second loader run. *Verify:* the note is corrected
      and points at the table that holds the figure.

## Phase 4 — Profiler runs

Neither closes without `-prof gc`. The CI workflow accepts extra arguments, so this is a `custom` run
with `-prof gc` appended.

- [ ] **74 040 → 8 664 bytes per call has no committed profiler output.** `Light-Engine:141`.
- [ ] **"roughly 100 KB per chunk" has no source at all** — no profiler output, no heap dump, no test.
      `Light-Engine:777`. The 128-chunk cap is a constant and checkable; the per-entry size is not.
      *Verify for both:* the two profiler lines are attached with their benchmark method and commit,
      or the figure is withdrawn. A derived estimate is not a measurement.

## Phase 5 — Unrecoverable, to be stated rather than fixed

These need runs that were never recorded. **The correct outcome is an explicit statement, not a
guess.** §9 of the standard: where a setting cannot be recovered, write what the annotation says and
mark the run's setting unrecorded.

- [ ] **Machine table has TBD fields.** `Measured-Results:58`, `Benchmarking:677`. Fill only from the
      machine the published numbers came from. If that machine is unavailable, say so in the table.
- [ ] **Test counts come from an uncommitted XML that predates `ca79507`.** `Project-Status:395`,
      `Anvil-Chunk-Loader:1070`. Re-derive from the current tree, bind to a commit, and say the older
      counts are not reproducible.
- [ ] **Four figures were removed for lacking a source; the notes remain.** `Light-Engine:156`,
      `Light-Engine:626`, `Measured-Results:365`, `Measured-Results:782`. Decide per note: keep as a
      record of what was withdrawn, or remove. Keeping is defensible — it stops the figure being
      reintroduced — but say which it is.
- [ ] **`EOFException` failure mode has no commit, issue or test to cite.**
      `Testing-and-Javadoc:129`. Attach a reference or mark it as recalled rather than recorded.

## Phase 6 — Decisions, not work

Each needs a maintainer's answer. **Do not decide these in an agent.**

- [ ] **PR #9 makes Falco 1.0.0** while README and wiki say "experimental" throughout and every public
      type carries `@ApiStatus.Experimental`. Either the release is early or the documentation must be
      rewritten first. Largest consequence of anything in this plan.
- [ ] **Move `AnvilChunkException` to `…anvil.exception`?** `Research-Exception-Hierarchy:88`. It is a
      released `@ApiStatus.Experimental` type, so the move is possible but is an API change.
- [ ] **The documented Aves integration does not exist.** `Anvil-Chunk-Loader:121` describes a
      `ChunkLoaderFactory` and a four-argument `registerInstance` overload; neither is in the sources.
      Document what exists, or remove the section.
- [ ] **`AreaVsPerChunkBenchmark` at `Level.Iteration`?** Its chunks are not rebuilt between
      iterations and both methods write light into them, so every iteration after the first re-lights
      lit chunks. Changing it would answer whether that moves the number **and** leave the
      configuration the published table was measured under. It is one of the project's five cross-run
      repeats, so the answer affects a cited result.
- [ ] **Where does `Falco-docs-standard` belong?** Local git, eight commits, no remote.

## Verification for the whole plan

- [ ] Every wiki link and anchor resolves, checked with a GitHub-faithful slugifier that was first
      made to fail on injected bad targets. Runs of spaces do not collapse: a heading losing an em
      dash or a slash keeps both spaces and GitHub emits two dashes.
- [ ] `grep -rn 'TODO(maintainer)' *.md` — each remaining marker is one this plan deliberately left,
      and the plan says which.
- [ ] No count anywhere is stated without a commit.
- [ ] `git diff` over both repositories shows no sentence, figure or provenance line lost. Losing
      rows, ties and regressions are still visible (§5.6) — a page that has a losing case and does not
      show it fails review.
