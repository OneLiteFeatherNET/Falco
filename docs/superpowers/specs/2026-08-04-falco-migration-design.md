# `falco-migration`: raising a stored world to the version the server speaks

Design of 2026-08-04. A module that converts Anvil chunk data from Minecraft 1.13 upwards to the
version the running server writes. [#45](https://github.com/OneLiteFeatherNET/Falco/pull/45) taught
the loader to *recognise* a world it cannot read; this is where the converting starts.

**The goal is the whole world. This slice — the light edition — is blocks, biomes and block
entities, across the whole directory structure of a world.** Entities, everything outside `region/`
and anything below 1.13 come later and are recorded at the end of this document so the shape of the
whole is visible from here. Nothing in this spec should be read as a promise that a world converted
with it is complete; "The entity debt" and "What this does not do" say precisely what it is missing.

**Depends on #45.** Without a source version read from the chunk, no mapping can be selected. This
spec assumes `DataVersion` is read at the loader's seam and that
`ChunkDataException.Reason.UNSUPPORTED_CHUNK_VERSION` exists.

**Depends on the extension points**, specified in `2026-08-04-anvil-extension-points-design.md`,
which turns the guard and the unknown-entry fallback into services alongside the `ChunkMigrator`
described below. The converter needs the second of those: it installs a policy that **throws** on an
unmappable block, where the loader's default substitutes air. Without that seam, a conversion would
quietly write air into a world it was asked to preserve.

## Why

Minestom speaks exactly one Minecraft version. A world stored by an older one is not merely
inconvenient — since #45 it is refused outright, which is honest but not useful. Mojang's own
converter is not available to us:

`com.mojang:datafixerupper` 6.0.6 contains 468 class files, of which **one** is a `Schema` — the base
class — and **none** is a `*Fix`. Even `NbtOps` is absent; only `JsonOps` ships. The 254 fixes and
115 schemas live in `net.minecraft.util.datafix.*` inside the proprietary Minecraft jar and are not
redistributable. Pulling DFU in as a dependency buys the machinery and no Minecraft knowledge at all.

The only complete free rebuild is PaperMC/DataConverter: GPL-3.0, 351 files, ~2 MB of source, 265
version classes from `V99` to `V4661`, maintained for years by a specialist. That is the size of the
thing DFU's approach implies.

**So this module does not rebuild DFU.** There is no schema registry and no type-rewrite machinery.
There is an ordered chain of functions `CompoundBinaryTag → CompoundBinaryTag`, each attached to a
`DataVersion` threshold, and a body of renaming data that is read rather than compiled in.

## Decisions

| Question | Decision |
| --- | --- |
| Shape | Data-driven step chain, not a DFU-style schema chain |
| Lower bound | **1.13** (`DataVersion` 1519), after the flattening |
| Direction | Upgrade only; the seams are designed so a reverse step can be added, but none is built |
| Mapping data | A **strategy**, not a fixed source. Two are built: the module's own table, derived from vendored registry lists, and one carrying ported Chunker knowledge |
| Applications | One engine, two front ends: a loader hook and a batch runner (API with a thin CLI over it) |
| Runtime dependency | None on Minestom. The engine is pure NBT |
| Heightmaps and light | Discarded, not converted |
| **Converted in this slice** | **Blocks, biomes and block entities** |
| Directory layouts | Both, in both directions — including `DIM-1` and `DIM1` |
| Everything else in the chunk | Passed through untouched, **counted, and reported** |
| Entities in the chunk | Not moved yet — and the world is told it is incomplete because of it |

**The goal is a whole world; this slice is the light edition.** That is a deliberate first cut, not
the end state. What it changes about the design is only where the line sits — the engine, the step
chain and both front ends are built as if the rest were coming, because it is.

**Why block entities are in and entities are out**, when both are "not blocks": a block entity is
part of the chunk in every version in range and stays there, so translating it is a rename applied to
a tag that is already in the right place. An entity has to *move to another file* from 1.17 onwards,
which is a different piece of work — it touches the directory layout, not just the chunk. Cutting
between them is cutting along the seam that exists rather than through the middle of one.

**Why 1.13 and not lower.** Below the flattening, blocks are numeric ids with four bits of metadata,
which is a second block model rather than another rename. That belongs to sub-project 3.

**Why registry lists and not the rename diffs.** An earlier draft of this document said ViaVersion
supplies rename diffs in both directions and that the module would read them. **That was wrong, and
it was wrong because the claim was carried over from research instead of read out of the files.**
What the directory actually holds:

- `diff/mapping-<A>to<B>.json` **upwards** (e.g. `1.20.5to1.21`) carries `sounds` and `tags`. No
  blocks, no block entities.
- The same file **downwards** (e.g. `1.13to1.12`) carries `blockstates` and `items`, but as
  *substitutions* for things the older version lacks — `acacia_button` → `oak_button` — not as
  renames.
- `mapping-<version>.json` is a set of **registry lists** whose index is the numeric protocol id:
  `blocks`, `blockstates`, `items`, `blockentities`, `entities`.

The reason is structural: ViaVersion translates a *protocol*, where things are numbers, so an old
client can talk to a new server. World data stores *names*. Most of that directory is the wrong tool
for this job.

**What the registry lists do settle, and it is the more useful half.** Comparing the `blocks` list of
1.13 against 26.1 — 593 names against 1168 — leaves exactly **two** names that disappear:

| Gone after 1.13 | Became | In |
| --- | --- | --- |
| `grass` | `short_grass` | 1.20.3 |
| `grass_path` | `dirt_path` | 1.17 |

Everything else in that span is an addition, and an addition needs no migration. **The rename problem
is therefore nearly absent**, and the weight of this module sits entirely in the structural steps 1
to 6, for which no data source exists in the first place — only code.

So the module vendors the registry list of each version a step hangs on, not the whole directory, and
derives its rename table from the difference. The table itself is hand-written and carries a source
per entry, because a difference says *that* a name vanished, never *what it became*. A test
recomputes the difference from the vendored lists and fails when a version drops a name the table
does not know — which is what turns a hand-written table into a maintained one.

The licence permits the vendoring explicitly: *"The files under `mappings/` are free to copy, use, and
expand upon in whatever way you like."* The commit hash and the licence text are archived beside the
data.

**Block entities cannot be checked this way.** The 1.13 mapping file carries no `blockentities` list
at all (26.1 has 49). The difference that is conclusive for blocks cannot be computed for them, and
their renames have to come from elsewhere. Naming the gap is all this spec does about it; closing it
is the plan's first job.

**Why upgrade only.** DFU cannot go backwards, structurally: `getRule()` returns `nop()` when
`version >= dataVersion` and `update()` passes the input through unchanged — silently, without an
error. That is not the reason this module skips downgrade; the reason is that a downgrade is a
projection, not a rewrite, and needs a substitution policy that is a product decision rather than a
technical one. The step interfaces take a direction so that the decision stays open.

## How the loader lets migration in

`falco-anvil` declares the contract and discovers implementations through the standard service
loader. It never depends on `falco-migration`, and **migration is off unless a caller turns it on**.

### The contract, in `falco-anvil`

```java
public interface ChunkMigrator {

    boolean supports(int dataVersion);

    CompoundBinaryTag migrate(CompoundBinaryTag data, int dataVersion) throws ChunkDataException;
}
```

`supports` is asked before `migrate`, so a migrator that only covers 1.13 upwards can decline a 1.8
world and let the guard refuse it with the message it already has. `migrate` returns the chunk in the
loader's own version — everything downstream of the seam is unchanged and does not know a migration
happened.

The project has no `module-info.java` and has never used `ServiceLoader`; this is the first. So it is
a plain classpath service: `META-INF/services/net.onelitefeather.falco.anvil.ChunkMigrator` in the
`falco-migration` jar, and no JPMS `provides` clause anywhere.

### The fluent API, opt-in in both forms

Two new builder slots, alongside the immutable pass-through every other setter already does:

```java
FalcoAnvilLoader.builder()
    .migrator(myMigrator)      // explicit: this instance, no lookup
    .build(worldRoot, OVERWORLD);

FalcoAnvilLoader.builder()
    .discoverMigrator()        // service loader: whatever the classpath provides
    .build(worldRoot, OVERWORLD);
```

**Neither is the default, and that is the point.** Without one of these calls the loader behaves
exactly as it does after #45: a world it cannot read is refused. Migration on load changes what a
server does with stored data and what a load costs, and a dependency appearing on the classpath is
not a decision — calling a builder method is.

Three rules make the opt-in honest rather than convenient:

- **`discoverMigrator()` with no provider on the classpath throws** at build time. The caller asked
  for migration and would otherwise get silence — the same failure this whole effort exists to end.
- **More than one provider throws**, naming them. Picking one silently is how a world gets converted
  by a migrator nobody chose. `migrator(...)` is the explicit way out.
- **`migrator(...)` and `discoverMigrator()` are exclusive.** Calling both is a configuration error,
  not a precedence puzzle.

A migrated chunk is counted in `AnvilDiagnostics`, apart from the refused ones, so a run says how
many chunks it converted and from which versions.

## Module boundary

`falco-archunit` states `anvilIsStandalone = isolated(ANVIL, LIGHT, INSTANCE, DEMO, BENCH)`
(`ModuleBoundaryTest.java:98`): `falco-anvil` may not import any sibling module. The `ChunkMigrator`
contract above satisfies that by construction, the way `PaletteEntryResolver` already does in that
module — an interface **in `falco-anvil`**, implemented **in `falco-migration`**, which depends on
`falco-anvil` and never the other way. The service loader changes nothing about it: a provider is
found at runtime by name, which is not a compile-time edge and cannot become one.

`falco-migration` joins the rule matrix as a module that may see `falco-anvil` and nothing else.
The rule gains a companion: **nothing in `falco-anvil` may name a class from `falco-migration`**,
which is what a service contract is for and what a careless import would quietly undo.

## The core

Input: the root compound of one chunk plus its source `DataVersion`. Output: the same chunk in the
target version. No block registry, no running server — possible because `falco-anvil` carries
Minestom as `compileOnly` and `RegionFile` is a byte container by construction (`open`, `readRaw`,
`writeRaw`, `RawChunk.decompress` are all public).

**Reused:** `RegionFile`, `ChunkCompression`, `RegionConstants`, `SectorAllocator`, `NbtReads`,
`PaletteData`.

**Deliberately not reused:** `BlockPaletteResolver` and `BiomePaletteResolver`. They resolve against
the registries of a running server and substitute air or plains for anything unknown. In a loader
that is a reasonable last resort; in a converter it is the wrong reaction, because an unmappable
block must become visible, not invisible.

## The directory structure

A world is not one directory of region files, and the layout changed along with the chunk format.
The converter has to resolve the source layout and write the target one, or it converts the overworld
of a three-dimension world and reports success.

`FalcoAnvilLoader.resolveRegionDirectory` (`:572-579`) knows two shapes today:

| Layout | Path |
| --- | --- |
| modern | `<world>/dimensions/<namespace>/<value>/region` |
| legacy | `<world>/region` |

It picks legacy when the modern directory is absent and `<world>/region` exists. **That covers the
overworld and nothing else.** In the legacy layout the other two dimensions live in
`<world>/DIM-1/region` (the nether) and `<world>/DIM1/region` (the end) — names that do not appear in
that method at all. A converter that reuses the loader's resolution therefore sees one third of an
old world.

So the converter carries its own resolution, and it is a first-class part of this slice:

- **Source discovery** enumerates what a world root actually contains: `<world>/region`,
  `<world>/DIM-1/region`, `<world>/DIM1/region`, and any `<world>/dimensions/<namespace>/<value>/region`.
  Custom dimensions from data packs appear under `dimensions/` in both eras and are enumerated, not
  hard-coded to the three vanilla ones.
- **Target layout** is the modern one, because that is what the target version reads: `DIM-1` becomes
  `dimensions/minecraft/the_nether/region`, `DIM1` becomes `dimensions/minecraft/the_end/region`, and
  `<world>/region` becomes `dimensions/minecraft/overworld/region`.
- **The mapping from old directory to dimension key is data, not a guess.** `DIM-1` → `minecraft:the_nether`
  and `DIM1` → `minecraft:the_end` are the two fixed points; anything else found under `dimensions/`
  already carries its key in its path.
- **Nothing is deleted.** The converter writes the new layout and leaves the old directories in place.
  Removing them is the operator's decision, taken after they have looked at the result.

**One thing to check before implementing, not to assume:** whether the loader's fallback to
`<world>/region` for a non-overworld dimension is a defect in its own right. Reading a nether with a
legacy world root would hand back overworld regions. It may equally be that the caller is expected to
pass `<world>/DIM-1` as the root. The plan establishes which, and if it is a defect it belongs in its
own change and not in this module.

## The step chain

Each step declares the version interval it applies to. A chunk runs the steps whose interval its
source version intersects, in order.

| # | Step | Applies below | Notes |
| --- | --- | --- | --- |
| 1 | Normalise the bit packing | 2529 (20w17a, pre-1.16) | Pre-1.16 entries span long boundaries. `BitPacker` cannot read that: `pack` is documented "without letting an entry span two longs" and `unpack` computes `longIndex = index / entriesPerLong`. A separate legacy unpack is required. This row previously named 2566, 1.16's *release* DataVersion; the change actually landed in the 20w17a snapshot, DataVersion 2529 — the implementation (`NormaliseBitPacking.APPLIES_BELOW`) already carried the corrected, sourced number, and this row is now brought in line with it |
| 2 | **Count** entities still in the chunk | 2681 (20w45a, pre-1.17) | Counted and reported, **not moved**. See "The entity debt". This row previously named 2724, 1.17's *release* DataVersion; the change actually landed in the 20w45a snapshot, DataVersion 2681 — the implementation (`CountEntities.APPLIES_BELOW`) already carried the corrected, sourced number, and this row is now brought in line with it |
| 3 | Unfold `Level` | 2844 | Fields onto the root, `Sections`→`sections`, `yPos` added |
| 4 | Rebuild biomes | 2844 | Array (256 bytes pre-1.15, 1024 ints from 1.15) → palettised container per section |
| 5 | Widen the Y range | 2844 | Existing sections keep their `Y`. **Empty sections are not invented** |
| 6 | Namespace the status | see note | Namespaces a bare status, and also renames 1.13's own terminal values (`fullchunk`, `postprocessed` — *not* `full`, which did not exist as a name until 1.14) to `full` before namespacing; see `NamespaceStatus`'s own javadoc for the sourced rename chain |
| 7 | Apply renames | throughout | Blocks **and block entities**, from the module's own table. Two block entries are known for the whole 1.13–26.1 span; the block entity entries have no comparable source |
| 8 | **Discard** heightmaps and light | always | See below |

**Step 8 is a deletion on purpose.** A wrongly ported heightmap never announces itself; a missing one
is rebuilt. `falco-light` already computes light, and the server recomputes heightmaps.

**Step 6 has no verified threshold.** The exact version that namespaced the chunk status could not be
established — the wiki's own history carries the notice that it is missing a significant number of
changes. The step therefore does not test a version at all: it rewrites a status that carries no
namespace, whatever the source version, and leaves a namespaced one alone. That is correct for every
version in range and does not depend on a number nobody has read.

**An unmappable block fails its chunk.** The engine does not substitute air, and it does not silently
keep the unknown name. It throws, naming the block and the chunk, and the batch runner records it and
continues with the next chunk so that one bad block does not abort a world. A configurable
substitution policy is what a *downgrade* needs and is out of scope here — on the upgrade path an
unmappable block means the mapping data is incomplete, which is a defect to see rather than to paper
over.

### The entity debt

In a 1.13 world the entities live inside the chunk; from 1.17 the server reads them only from
separate `entities/` regions. The light edition **does not move them**.
The consequence has to be stated plainly, because it is the same failure mode #45 exists to end:

**A world converted by this module keeps its entity data in the chunk, where the target version will
never look for it. Every mob, item frame, armour stand, painting and dropped item is effectively
gone.** The bytes are still there — nothing is deleted — but nothing reads them either.

That is acceptable as a first slice only because the module refuses to let it happen quietly:

- Step 2 counts the entities it finds per chunk and totals them for the run.
- The batch runner ends with that total in its report, as a **warning**, not a statistic: "this world
  carried N entities in its chunks; they were not moved and the target version will not read them."
- The loader hook logs the same thing once per world, throttled like every other diagnostic here.
- The README and the wiki say it where a reader meets the tool, not in a footnote.

Moving them is the first item of the next slice. The move itself is lossless; the work is that
individual entity fields were renamed between versions, which is step 7's data applied to a
different tag.

**Block entities are converted, not carried.** Their `id` goes through the same renames as a block,
and the tag stays where it already is. Two things about them are explicitly *not* in this slice, and
both are counted rather than fixed: the **items inside** a block entity — a chest's contents carry
item ids that were renamed too — and per-block-entity field changes that are not a rename of the
`id`, of which the sign text rework in 1.20 is the largest. A converted chest is in the right place
with the right id; what is inside it has not been looked at.

**Everything else in the chunk is passed through untouched** — `block_ticks`, `fluid_ticks`,
`structures` and any tag this list does not name. Untouched means the tag survives the round trip
byte for byte, not that it is correct afterwards.

## The two applications

**The loader hook.** `falco-anvil` asks its `ChunkMigrator` — set through `migrator(...)` or found
through `discoverMigrator()`, never by default — when it meets a chunk below its floor, and loads the
converted result instead of refusing it. The world on disk stays old; the cost is paid on every load.
This is the path that makes an old world playable without a separate step, and the whole of what
`falco-migration` contributes to it is one implementation of that interface.

**The batch runner.** An API that walks the region files of a world root and rewrites them, with a
thin CLI over it — a `main` method that parses arguments and calls the API, carrying no logic of its
own. The API is the contract; the CLI is a convenience.

Both front ends drive the same engine. Neither may contain a conversion rule.

## What this does not do

- **No downgrade.** The interfaces admit one later; nothing implements it.
- **Nothing below 1.13.** The flattening is a later slice.
- **No entities**, neither moved nor renamed, with the consequence spelled out under "The entity
  debt".
- **No items**, anywhere — not in block entity inventories, not in `playerdata/`.
- **No per-block-entity field changes**, only the `id` rename. The 1.20 sign rework is the notable
  one.
- **Ticks and structures** are carried byte for byte and not translated.
- **Nothing outside `region/`.** `entities/`, `playerdata/`, `poi/`, `data/` and `level.dat` are
  untouched. A world converted by this module is therefore **not** fully consistent.
- **No claim that the result is lossless**, and none that a converted world will load. Neither can be
  promised for a converter of this kind, and this slice cannot even promise the world is complete.

## The later slices, recorded here so the whole is visible

Each becomes its own spec when it is reached. Nothing below is designed yet; this is the running
order and the reason for it.

1. **Blocks, biomes, block entities — the light edition.** This document.
2. **Entities.** The move from the chunk into `entities/` plus their renames, which is what makes a
   converted world actually playable rather than merely loadable. It is first of the remaining ones
   because it is the largest silent loss the light edition leaves behind.
3. **Items.** Block entity inventories and, with slice 4, player inventories — the same rename data
   applied to a third kind of tag, plus the 1.20.5 component break.
4. **The world outside `region/`.** `playerdata/`, `poi/`, `data/`, `level.dat`. POI is derivable
   from block data and may be discarded rather than converted; that is a decision for that spec.
5. **Below 1.13.** The flattening: numeric ids with four bits of metadata into block states. A second
   block model rather than another rename, and the reason 1.13 is this module's floor.

## Evidence

A converter claims the world is still the same world afterwards, which is a stronger claim than #45's
and cannot rest on fixtures alone — my own picture of the old format was wrong twice during #45, and
a fixture built from a wrong picture agrees with the code that shares it.

Three levels, in increasing strength:

1. **Per-step fixtures with a Gegenprobe.** Hand-built NBT, one case per step, each proved to go red
   when the step is removed. Catches logic errors in a step.
2. **Property tests across the whole chain.** Block count per type is preserved, no block becomes air,
   every block entity keeps its coordinate, every entity survives the move. These catch losses without
   requiring me to predict the target format correctly.
3. **Real old worlds.** These do not exist in this repository and are the evidence that would actually
   settle the question. They can be produced by running official server jars headless to generate a
   few chunks per version. That pulls foreign binaries into the test path and is therefore its own
   decision, taken when the tests are written rather than here.

**Level 3 is unresolved and is recorded as such.** Until it exists, no claim about real worlds may be
made in the README or the wiki — only about the cases levels 1 and 2 cover.

## The translation is a strategy

Step 7 does not own a table. It calls a strategy.

The interface takes a block state whole and returns one, because a rename and a property change are
the same operation seen from different distances:

- in: identifier plus its properties, the source version, the target version
- out: identifier plus its properties — or a refusal, which fails the chunk per the rule above

Neither the step chain nor either front end knows which implementation it has, so an operator's own
overrides for a modded world cost nothing to add. That is why the interface is public API rather than
an internal detail.

### What the measurement changed about this section

An earlier draft said the strategy existed to defer a choice between a cheap rename table and a
ported Chunker table, because nobody knew how large the property problem was.
`2026-08-04-blockstate-property-research.md` measured it, and three of the assumptions here were
wrong.

**There are zero property renames.** Not few — zero, for every block that exists in 1.13. All four
renames in the whole chain belong to blocks introduced later (`jigsaw`, `creaking_heart`,
`test_block`). What costs something is 39 of 593 block names and 582 of 8582 states, just under 7 %.

**So the ported Chunker table is not built, now or later.** Its value is Java↔Bedrock translation;
Falco does Java→Java and would have to invert one mapping group and compose it with another —
effort without return. The 22 facts themselves are under 100 lines and can be written down directly,
with attribution where they came from Chunker or DataConverter.

**But three properties of the mechanism are load-bearing from the first line, each forced by a
concrete case.** Retrofitting any of them means rebuilding:

1. **Rules are versioned**, resolved as "the greatest rule version ≤ the source version". Forced by
   `stone_slab`: the name means one block in 1.13 and another from 1.14, so an unversioned rule would
   corrupt a 1.16 world. This is the case that proves rules cannot be a flat table.
2. **The key is the whole state, never a single property.** Forced by `redstone_wire`, where a
   direction's new value depends on the *other three directions of the same state* — implemented per
   property it is guaranteed wrong — and by `cauldron`.
3. **A rule may change the block name.** Forced by `cauldron`, where `level=0` becomes `cauldron` and
   `level=1..3` becomes `water_cauldron[level=n]`. Name and property level are coupled, which is
   exactly why that case is invisible to a name diff.

**Two cases no registry comparison can find**, and only DataConverter had them: `stone_slab` keeps
its name while meaning a different block, and `redstone_wire`'s state list is byte-identical across
the whole span while its meaning changed underneath. A migration built on registry diffs alone would
have shipped both as silent corruption.

### Filling in missing properties is not implemented

Minestom's loader already does it. `withProperties` starts from the default state, so a 1.13 state
missing a property the target version expects gets that version's default — verified correct in all
30 cases. That removes 258 of the 582 states from this module's work entirely.

**One test nails it down anyway**, because the intuitive rule is wrong: corals and `conduit` default
to `waterlogged=true`, not false, and that is what 1.13 data means — a dry living coral died. Writing
`false` there would dry out every reef. The rule is **"the target version's default"**, never
"false".

## Order of work: by damage, not by count

Minestom is not Vanilla here, and it changes which cases are urgent.
`BlockImpl.withProperties` throws on an unknown property key **or** an unknown value, and an unknown
block name is a `NullPointerException` — `AnvilLoader.loadBlockPalette` wraps none of it. Where
Vanilla silently tolerates a stale state, Falco's loader refuses the whole chunk.

| Case | Vanilla | Falco on Minestom | States |
| --- | --- | --- | ---: |
| property missing | default | default — identical, free | 258 |
| property surplus (`cauldron[level]`) | ignored | **chunk fails** | 4 |
| value unknown (walls `north=true`) | — | **chunk fails** | 128 |
| name unknown | — | **NPE** | 42 |
| meaning changed, signature identical (`redstone_wire`) | — | loads, looks wrong | 144 |

**178 states are load-blocking**: a 1.13 world with a single cobblestone wall aborts the chunk, not
just the block. `redstone_wire` loads cleanly and renders wrong — that was true when this section was
written and is why the priority order below put it last. It is no longer true of this module's
output: `redstone_wire` now has a rule too (`BlockStateRules`, DataVersion 2532, snapshot 20w18a), so
the "loads, looks wrong" outcome in the table is what happens without that rule, not what Falco does
today. The table and the priority order are kept as the historical record that justified doing walls
and cauldron first; they are not a statement of current coverage. So the order is **names → walls →
cauldron → stone_slab → redstone_wire**, which is damage order and not state-count order.

## Where the work actually is

The measurement settled something the earlier drafts guessed at. Of DataConverter's 204 relevant fix
classes above the 1.13 floor, **six** touch block states at all; 186 do not touch block content of
any kind. Its V2832 alone — the 1.18 height extension, palettes, bit storage, heightmaps — is 917
lines, larger than the entire block-state problem.

**Block states are a small part of this module.** The weight sits in the container format, which is
steps 1 to 6 of the chain above, and in block entities, whose NBT structure no registry diff can
describe. The plan budgets accordingly, and the ratio 6/204 is counted rather than estimated. (The
share of overall effort is not: any percentage figure here would be a guess.)

## Two known limits of the mapping data

**Block-state properties are not covered by the vendored data — and that turned out to cost almost
nothing.** The registry lists carry block *names*, so no comparison over them can derive a property
change. This was written as the plan's first and largest unknown; it has since been measured, and the
answer is in `2026-08-04-blockstate-property-research.md`: zero renames, 22 facts, under 100 lines
written down by hand. What the measurement did *not* dissolve are the two cases no diff can see —
`stone_slab` and `redstone_wire` — and those are the reason the rules are versioned and keyed on the
whole state. The gap is closed; the mechanism it forced remains.

**Block entity renames have no source at all.** As noted above, the 1.13 file carries no
`blockentities` list, so the difference that settles the block question cannot be computed for them.
Since block entities are inside this slice, this is not a limit to note and move past — it is work
the plan has to find another route to. Reading the target version's list of 49 and checking each
against what a 1.13 world can contain is one such route; it is small enough to be done exhaustively.

## Open for the plan

One question genuinely remains, because it is about the build rather than the design: where the
vendored mappings live and whether the engine reads them from the classpath or from a path the caller
supplies. Both work; the choice affects packaging and the batch runner's startup, and belongs with
the plan's file structure.

**Deliberately settled here, so the plan does not reopen them:** the loader hook converts on every
load and does **not** write the result back — that would turn a read path into a write path, and #45
was written precisely because a read path that writes is how real data gets lost. Persisting a
conversion is the batch runner's job, which is why there is one.
