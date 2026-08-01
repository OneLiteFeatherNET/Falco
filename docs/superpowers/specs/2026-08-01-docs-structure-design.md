# Navigation for the README and the wiki

Design of 2026-08-01. The wiki has 26 pages and 12 725 lines and no persistent navigation of any
kind; the README puts 26 lines of benchmark prose in front of the dependency snippet. Neither is a
content problem, and this design changes no content: it adds the two navigation files GitHub renders
on every wiki page, reorders two entry points, and gives the five longest pages a table of contents.

Bound throughout by the Falco documentation standard, which lives outside this repository in
`Falco-docs-standard/documentation-standard.md`; section references below (§0, §4, §6, §7, §9) are
to it. Where a decision collides with that document, the collision is named.

## Why

Six parallel investigations — four on the web, two over the sources — establish four findings.

1. **`Home.md` is a single point of failure.** It is the only file that reaches all 25 other pages
   (27 link instances) and it has zero inbound links. Between the content cluster (Anvil, Light,
   Benchmarking, Project Status, Rationale, Research) and the Gradle cluster of seven pages there is
   exactly one bridge outside Home, at `Research-Fluent-API.md:8` and `:64`. A reader who arrives by
   search or deep link never reaches the other half.

2. **Long pages are dead ends.** `Anvil-Chunk-Loader.md` carries its last internal link at `:890` and
   runs to `:1137` — 247 lines with no exit, including *Error handling and world consistency* at
   `:912`, which is where `README.md:20` effectively sends the reader. The same shape appears in
   `Architecture-Rules.md` (last link `:90` of 130), `Testing-and-Javadoc.md` (`:87` of 153) and
   `Versioning-and-Releases.md` (`:85` of 118). `Research-Fluent-API.md` is the extreme case: 2 060
   lines, one inbound link, and no internal link at all after `:65`.

3. **Five pages totalling 6 717 lines carry no table of contents.** GitHub's automatic one renders
   collapsed (`aria-expanded="false"`) in the right-hand Pages box, and on a narrow viewport it sits
   behind the entire page body.

4. **The README is weighted wrongly, not written badly.** `## What "high-performance" means here`
   (`:27-52`) precedes `## Quick start` (`:54`). Across two independently gathered corpora of JVM
   libraries and Minestom-ecosystem projects, no README places performance prose before its install
   block — HikariCP, which advertises performance hardest, does not either.

What is already right and must survive: all 322 internal wiki links resolve; the retired `docs/*.md`
and `STATUS.md` paths are fully repointed, so §7's "consequences to apply while rewriting" describes
work that no longer exists; `Rationale` is a clean single-mode group; the four-step quick start is
self-supporting and is the strongest page in the documentation.

## Scope

Navigation and reordering only. No page is renamed, split or deleted. No content is shortened,
softened or dropped — §5.6 in particular: every losing row, tie and regression stays visible.

One deliberate extension beyond that line, decided by the maintainer: the four measured figures in
the README get the provenance line §7 requires and has always required. This is new text, and the
figures must be verified against `falco-benchmarks/` before it is written. No digit may change (§0).

## What gets built

### `_Sidebar.md` — new

Four groups, rendered on every wiki page. Group labels are **bold text, not headings**, which is what
keeps them clear of §6's one-`#`-per-page rule; a sidebar has no title, no scope paragraph and no
References section, so the heading rule cannot be satisfied and is instead avoided.

    Start here             Quick start (README), Installation, Anvil Chunk Loader, Light Engine
    The measured record    Benchmarking, Project Status, Rationale: Measurement
    Why it is built this way   Rationale, Research, Research: Fluent API
    Working on the build   Build Setup, Dependency Management, Versioning and Releases,
                           Publishing, Testing and Javadoc, Benchmarks and Demo,
                           Architecture Rules

**The folding rule.** A group collapses to its landing page if and only if that landing page lists
every one of its subpages. `Rationale.md` lists all five, so Rationale is one line. `Research.md`
lists five of six — `Research-Fluent-API` is absent from its table at `:39` — so that page gets its
own line beside the group. The Gradle cluster has no landing page, so its seven pages stand
individually. Result: 17 direct links, 9 more at one hop, all 26 pages, about 26 rendered lines.

Flat-listing all 26 was rejected: GitHub renders the custom sidebar *below* its own alphabetical
Pages box, so a second full enumeration would be pure duplication.

Anchor deep links stay out of the sidebar. §6 wants a cross-reference phrased as a statement of what
the reader will find, which a sidebar label cannot be — so the deep links live in Home's prose
instead, where they cost no screen height and can be written as sentences.

### `_Footer.md` — new

Renders in the main column at full text width, directly after `#wiki-body`, which resolves all four
dead ends at once. Two parts: the sentence that every measured table belongs to
[Project Status](Project-Status) and that the `±` is defined once in
[Rationale: Measurement](Rationale-Measurement); then a pointer line — Home, repository, README,
Javadoc, issues, licence. No greeting, no emoji, no second person (§5.8).

### `Home.md` — reordered

Stays the canonical full index. That is not redundancy with the sidebar but a technical necessity:
the custom sidebar is not rendered on `/wiki/_pages` or `/wiki/<page>/_history`, and on narrow
viewports `Layout--flowRow-until-md` pushes it below the whole page body. **No existing link and no
existing descriptive half-line is removed.**

1. `# Falco` — new. Home is the only one of the 26 pages without an H1; §6 requires one. GitHub
   renders the file title "Home" above it, so the two strings differ and nothing reads as doubled.
2. Intro paragraph, unchanged.
3. `## Using Falco` — Installation, Anvil Chunk Loader, Light Engine, descriptions verbatim. One
   added prose sentence carrying the deep links `Anvil-Chunk-Loader#usage`,
   `Light-Engine#when-this-is-worth-using`, `Light-Engine#usage`, and `Project-Status#environment`
   for the Minestom version the README's `<version>` placeholder needs.
4. `## The measured record` — Benchmarking and Project Status first, then the existing bold paragraph
   verbatim. This repairs a real referent error: that paragraph currently sits under a list of *five*
   entries and names neither page.
5. `## Background: rationale` — unchanged.
6. `## Background: research` — unchanged. The apparent count mismatch ("five investigations" over six
   entries) is not a defect: `Research.md:39` lists exactly five documents, and Fluent API is
   explicitly a proposal rather than an investigation. No number is touched (§0).
7. `## Working on the build` — renamed from `## Gradle build`; a section heading, not a page. All
   seven entries verbatim. One added prose sentence carrying `Installation#building-from-source`,
   `Project-Status#working-on-this` and `Testing-and-Javadoc#reproducing-a-test-run`.
8. A closing forward reference, because §6 forbids ending on a list item and the page currently does.

Home does not advertise the sidebar. §6 forbids navigation instructions.

### `README.md` — reordered

1. `# Falco`, then badges reordered to **API experimental, Release, Java 25, Documentation,
   Licence**. Every public type carries `@ApiStatus.Experimental` and the README says so at `:24-25`;
   maturity belongs first. Five badges stay five badges.
2. Intro paragraphs and the module table, unchanged.
3. The experimental paragraph stays where it is, above the quick start. Guava puts its stability
   warning at the foot of the page, but only Guava's `@Beta` parts are unstable — here the whole
   library is, and the current placement is the better one.
4. `## Quick start` **moves up**, all four steps verbatim. The reader reaches the dependency snippet
   after roughly 30 lines instead of 60. One added link in the comment at `:70-72` pointing at
   `Project-Status#environment`; the version number is not copied, because §7 assigns it to Project
   Status.
5. `## What "high-performance" means here` **moves down**, word for word unchanged, and gains the
   provenance line described below plus a link to `Rationale-Measurement` — which the README
   currently references nowhere at all, though §0 requires the authoritative `±` definition to be
   linked from everywhere that uses it.
6. `## Documentation` gains exactly one entry pointing at [Build Setup](Build-Setup) for the whole
   Gradle cluster. Not seven — §7 keeps the README short and this rubric is a curated selection.
7. `## Licence`, unchanged, at the end.

No `## Contents` in the README: after the move it has four `##` sections, and a table of contents
would push down the very snippet the move lifts. No `## Contributing` section: there is no
`CONTRIBUTING.md` for it to point at, and a section with no target is an empty shell.

### The provenance line for the README figures

`README.md:32-46` quotes 1 181 ± 31, 2 200 ± 445, 103 437 ± 856 306 and 1.11× to 1.71×. §4 requires a
`<sub>` provenance line beneath each table of measured values with nine fields: benchmark class and
methods · parameter values · thread count · forks · warmup and measurement iterations with iteration
time · JMH version · machine class · commit or "run record not committed" · the `±` clause.

Every field is read from the benchmark sources, not generalised: §0 records that fork counts,
iteration counts and iteration times differ per class, and that any blanket statement across the
suite is false. Where a run setting cannot be recovered, the annotation value is stated and the run's
setting marked unrecorded (§9.1). Tables from a `@Fork(1)` class carry the one-fork clause at the
table (§4). No digit changes.

### Tables of contents

Five pages, using the form that already exists at `Project-Status.md:45-64`: heading `## Contents`
verbatim at `##` depth, one bullet per `##` section in document order, no `###` entries, the section
does not list itself, glosses rare and structural only. Placed after the opening scope paragraph and
after the *read this before quoting a number* blockquote, immediately before the first `##`.

`Research-Fluent-API` (2 060 lines), `Anvil-Chunk-Loader` (1 137), `Light-Engine` (967),
`Benchmarking` (925), `Rationale-Measurement` (882). The last falls just outside on length but is the
page everything links into, so it is where a reader most often lands mid-text.

Anchors are read from the rendered `/_toc` fragments, not derived from heading text. On Benchmarking,
the tables under *Headline results* sit inside `<details>` blocks and GitHub renders no anchor for a
`<summary>` line — the gloss says so rather than pretending they are reachable.

### Broken anchors repaired

`Rationale-Measurement.md:596` and `:827` both link the text *What the ± is* at the anchor
`#what-the--is`, and no such heading exists on the page. Target becomes
`#the-interval-after-a-number` (`:44`). Link text unchanged. The
irony is load-bearing: this is the one definition §0 declares authoritative.

## Known standard collisions

- **§6, cross-references as statements.** A sidebar is labels by construction. Handled by keeping
  every explanatory half-line in Home and admitting no imperative into the sidebar, but the standard
  should gain a sentence exempting `_Sidebar.md` and `_Footer.md` from the phrasing rule while
  keeping them under the link-syntax rule. Until it does, the sidebar is formally a violation.
- **§6, heading depth.** `Research-Fluent-API.md`, `Instance-Performance-Research.md` and
  `Research-Shared-Instances-And-Batches.md` use `####`, which §6 answers with "the page needs
  splitting". Splitting is out of scope. The violation stays and is not disguised by renumbering; it
  is inconsequential for the tables of contents, which list only `##`.
- **§7, retired paths.** The standard's repointing instructions are stale: the retired paths survive
  at exactly one place, `Instance-Performance-Research.md:533-534`, and there correctly, as a
  historical date. The standard lives in another repository and is not edited here.

## Deferred

- A landing page for the Gradle cluster would fold seven sidebar lines into one. A new page is out of
  scope; noted as the obvious next round.
- Whether `<details>`/`<summary>` renders inside `_Sidebar.md` is unverified — it appears in none of
  24 inspected project wikis. If it works, sidebar height stops depending on page count and the
  folding rule becomes unnecessary. Only testable against the live wiki.
- A real `CONTRIBUTING.md`. GitHub surfaces such a file in PR and issue interfaces automatically, and
  the contributor currently has no entry point from the README.

## Verification

The design is done when, on the live wiki: the sidebar renders on an article page and every one of
its links resolves; the footer renders on the four former dead-end pages; each of the five new
`## Contents` blocks has every anchor jump to its section; and the two repaired `±` anchors land on
`### The interval after a number`. In the repository: the README's dependency snippet sits above the
benchmark section, and every figure in that section carries a provenance line whose fields match the
benchmark class it names.
