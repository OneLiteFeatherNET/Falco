# Splitting the working record, and what a split breaks

Design of 2026-08-02. Follows
[the navigation design of 2026-08-01](2026-08-01-docs-structure-design.md), which added a sidebar, a
footer and six tables of contents but deliberately left every page whole. This one cuts the largest
page into three, adds the contributing entry point that round identified as missing, and folds the
Gradle group the sidebar was carrying open.

It also records a failure mode found the hard way, because it will recur the next time a wiki page
is split: **a link checker cannot find the references a split breaks.**

Bound by the Falco documentation standard, which since this round lives in
`Falco-docs-standard/` under version control. Section references (§0, §4, §6, §7) are to it.

## Why

The first round left `Project-Status.md` at 1 783 lines and eighteen sections, running from
*Environment* and *Conventions* through *Measured* to *Defects found and fixed* and *Open*. It gained
a table of contents, which made it navigable without making it one thing. Three readers wanted three
different pages from it:

- someone auditing a figure wanted *Measured* — 761 lines, 43 % of the page;
- someone about to contribute wanted *Environment*, *Working on this*, *Conventions* and *Releasing
  and snapshots* — about 103 lines, and the only place the Minestom version is recorded;
- someone asking what state the project is in wanted the rest.

Separately, the repository had no `CONTRIBUTING.md`. GitHub surfaces such a file in its pull request
and issue interfaces, so its absence left the contributor path — the weakest of the four traced in
the previous round — with no entry point at all.

## What was built

**Three pages instead of one.** `Measured-Results.md` holds every measured table and all eleven
provenance lines. `Contributing.md` holds the four contributor sections and is what the repository's
new `CONTRIBUTING.md` points at. `Project-Status.md` keeps facts, decisions, branch contents, defects
and open items — what its name says.

Heading levels were preserved through the move, so every subsection anchor still resolves and only
the page in front of it changes. That is what made the twelve inbound anchors mechanically
redirectable.

**`CONTRIBUTING.md` in the repository**, short by §7: prose lives in the wiki, the repository points
at it. Every command and claim in it was read from a file — the toolchain from `build.gradle.kts`,
the release mechanics from `release-please-config.json`, the review path from `CODEOWNERS` and the
two workflow files, and the 401 that stops an outside contributor building from source.

**The Gradle group folded** to `Build Setup`, which already listed all six pages behind it. The
folding rule from the previous round — a group collapses to its landing page if and only if that page
lists every subpage — turned out to be satisfied already; the seven separate lines were simply
unnecessary. Reachability was verified as a graph afterwards: fourteen pages are not in the sidebar,
every one of them is one hop from a page that is.

**Five more tables of contents**, bringing the total to eleven pages.

**The standard gained** the navigation-file exemption the previous round flagged as an unresolved
collision, and the folding rule, which had been applied without being written down anywhere.

## What a split breaks, and what finds it

The twelve references of the form `[Text](Project-Status#anchor)` were redirected mechanically. They
are greppable, and all twelve were correct on the first pass.

**Eighteen further sentences were wrong and no tool reported them.** They carry no anchor:

> the full tables are in [Project Status](Project-Status)

> [Project Status](Project-Status) — the working record: benchmark results with their conditions

These resolve perfectly. The page exists; the link is valid; the sentence is false. They sat on nine
pages, four of which were not on any change list for the round. The worst was in `_Footer.md`, which
GitHub renders under all thirty pages at once — a single wrong sentence, thirty times.

The class is specific and worth naming: **a reference whose surrounding prose asserts what the target
page contains.** Splitting a page invalidates every one of them, and only reading them in context
finds them. The repair pass had to distinguish three cases per hit — the sentence names the page as
holder of measurements (now false), of defects and decisions (still true), or as an unqualified
"working record" (needs to say which of the three pages it means).

**For the next split:** budget for reading every occurrence of the page name in context, not for
running a link checker. The checker is necessary and insufficient. Twenty-eight references were read
in the repair pass; ten needed changing.

## Verification that was actually run

Not a summary of intentions — this is what was executed and what it returned.

- **Content preservation, paragraph by paragraph.** The pre-split file was cut into 274 paragraphs
  and matched against the union of the three new ones: 257 identical, the other 17 differing only in
  a redirected link or a rewrapped line. Counting probes: `±` 94 before and 94 in the moved text,
  `×` 106 in both, eleven `<sub>` provenance lines before and eleven after, each still directly
  beneath its table (§4). No digit changed (§0).
- **Loss-bearing rows survive** (§5.6): the `1.14×` **slower** row, "Writing is a tie", the
  mixed-brightness regression, the rejected bucket queue at 5–7 % slower, the two-thread figure that
  did not reproduce, and the `1.83×` marked as having no run record.
- **Anchors, wiki-wide.** A GitHub-faithful slugifier over every page plus `README.md` and
  `CONTRIBUTING.md`: 860 link targets, zero broken. The checker was itself tested against six
  deliberately injected faults and reported all six — a checker that returns zero is worthless until
  it has been made to fail.
- **Footnote renumbering.** Splitting a `Sources` entry shifted every following footnote number on
  two pages. Verified that no reference points at an undefined source, and that the two unreferenced
  sources were already unreferenced before the change.

## Known collisions that remain

- **`####` on two pages** — thirteen in `Instance-Performance-Research`, more in
  `Research-Fluent-API`. §6 answers this with "the page needs splitting", which was out of scope in
  every round so far. Not disguised by renumbering.
- **`Measured-Results` claims to own every measured table**, which `Anvil-Chunk-Loader` refutes on its
  own page: it publishes an independent second run "only on this page", plus a two-fork control
  table. The footer was written to avoid the same overreach; the page itself still carries it.

## Deferred

- **The 112.7 µs anchor has no source table.** The value carries the claim that mixed light sources
  cost Falco about a third, and appears in no published table — either it comes from an unpublished
  run or it is a transcription error. Four `TODO(maintainer)` comments mark it. Resolving it needs a
  benchmark run, not an edit.
- **A scope error about cross-run repeats.** `Benchmarking` and `Measured-Results` agree on four and
  enumerate them; `Light-Engine` says "the two genuine cross-run repeats this project has", meaning
  the two that concern lighting. The count is not in dispute, the scope of the sentence is.
- ~~**Whether `<details>` renders inside `_Sidebar.md`.**~~ **Answered on 2026-08-02: it does.**
  Tested against the live wiki, not assumed — GitHub emits a real `<details>` element in the sidebar,
  nothing escaped. Rationale, Research and Build Setup now carry their subpages in collapsed blocks,
  so all thirty pages are one click away at fourteen rendered lines. The folding rule this document
  and the standard both describe as a constraint is therefore a choice; both have been corrected.
- **Where `Falco-docs-standard` should live.** It is now a git repository with no remote.
