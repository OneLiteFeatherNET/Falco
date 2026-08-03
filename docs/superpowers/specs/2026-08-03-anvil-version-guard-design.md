# A version guard for the Anvil chunk loader

Design of 2026-08-03. `FalcoAnvilLoader` reads a world it does not understand as air and reports
success. This adds the check that says so, and nothing else.

All line numbers are against `2d3955d8`, the 1.0.0 baseline.

## Why

The loader expects the chunk layout Minecraft writes since 1.18: `sections` on the root compound. A
world written by 1.17 or earlier keeps everything one level down, under `Level`. Three independent
decisions, each defensible on its own, combine into silent data loss:

1. **The status is looked for on the root.** `chunkStatus` reads `Status` and falls back to `status`
   (`FalcoAnvilLoader.java:1354-1357`), both against `data` itself. A 1.17 chunk carries
   `Level.Status`, so the answer is `null`.
2. **A chunk without a status counts as generated.** `isFullyGenerated` returns true for `null`
   (`:1371-1372`). Its Javadoc gives the reason, and the reason is good: a world written by a tool
   that stores no status would otherwise be unreadable in its entirety.
3. **A missing section list is not an error.** `decodeSections` calls
   `NbtReads.optionalList(data, SECTIONS_KEY, …)` (`:1386`), and `NbtReads.optionalList` returns
   `ListBinaryTag.empty()` when the key is absent or mistyped (`NbtReads.java:162-167`). The 1.17 key
   is `Level.Sections`, so the list is empty and nothing throws.

The chunk is then counted as loaded and consists entirely of air. Whether a later save writes that
air back over the stored region file has **not been measured** and is not claimed here; the read
side alone is the defect.

`DataVersion` would answer the question outright, and the loader writes it — `putInt("DataVersion",
this.dataVersion)` at `:1586` is the **only** occurrence of the name in the whole module. It is never
read. There is no `Reason` for an unreadable version and no counter in `AnvilDiagnostics`.

This contradicts the property the module is sold on. The README says a read failure "throws instead
of reporting the chunk as absent, so the server cannot overwrite real data with a freshly generated
chunk". For a pre-1.18 world it does the opposite, and more quietly.

## Decisions

| Question | Decision |
| --- | --- |
| What is checked | The layout first, the version second |
| Where | Between `:654` and `:655`, the one point where the full root compound exists and nothing is interpreted yet |
| How it fails | `ChunkDataException` with a new `Reason`, thrown |
| New exception type | None — the sealed hierarchy is not touched |
| Upper version bound | None |
| `isFullyGenerated(null) == true` | Unchanged |
| Save path | Unchanged |

**Why the layout is checked before the version.** A version number is a claim about the data; the
layout is the data. A world written by a third-party tool may carry no `DataVersion` at all, and a
world may carry one that does not match what it contains. The check that decides whether the loader
can proceed therefore asks what is actually in the compound. The version is read to *explain* the
failure to whoever has to fix it, not to detect it. This also means the guard does not rest on a
version table being correct.

**Why no upper bound.** The damage that is proven runs downwards: older layouts read as air. A future
format break would be a different failure and cannot be anticipated by a constant, while a hard
ceiling would turn every Minecraft release into a Falco release. The lower bound is enough.

**Why a `Reason` and not a new exception type.** `AnvilFormatException` is
`sealed … permits ChunkDataException, RegionFormatException` (`AnvilFormatException.java:38-39`).
Extending that list is a change to a published hierarchy on a 1.0.0 artefact that runs
`checkApiCompatibility`. A new enum constant is additive and carries the same information.

## The change

### 1. Read the version at the seam

```java
CompoundBinaryTag data = TAG_READER.read(…);   // :654, unchanged
// new: guard here
String status = chunkStatus(data);             // :655, unchanged
```

Everything downstream — `chunkStatus`, `decodeSections` (`:673`), `applyBlockEntities` (`:680`) — is
covered by a single call at this point, because it is the only place that holds the root compound
before anything is interpreted.

### 2. The layout check

The compound is rejected when it carries **no `sections` list on the root while holding a `Level`
compound**. That is the pre-1.18 shape, and it is the shape that produces the air chunk today. Both
halves are required: a root without `sections` and without `Level` is a genuinely empty or corrupt
chunk, which the existing paths already handle.

### 3. The version bound

`DataVersion` is read as an optional int — absent is not an error, since tools write worlds without
it. When present and below the configured minimum, the chunk is rejected with the same `Reason`.

The default minimum is **2860**, the first version whose chunks carry `sections` on the root
(1.18; per the Minecraft chunk-format history). It is configurable through a new builder slot, which
sits next to the existing `dataVersion(int)` at `:411` — that one is the version *written* on save,
this one is the lowest version *accepted* on load. The two must not be conflated in the Javadoc.

If 2860 is off by a release, the layout check of step 2 still rejects the world correctly. The
constant changes only what the message says.

### 4. The reason

A new constant `UNSUPPORTED_CHUNK_VERSION` at the end of `ChunkDataException.Reason`
(`ChunkDataException.java:37-68`, six constants today).

**Not `UNSUPPORTED_DATA_VERSION`.** The layout check of step 2 fires on chunks that may carry no
`DataVersion` at all, and naming the reason after a field that is absent in the case that triggers it
most often would send the reader looking for the wrong thing. One constant covers both checks,
because both say the same thing — this chunk comes from a version the loader cannot read — and both
have the same remedy. Which check fired is carried by the message, not by a second constant.

The message names the version found (or that none was stored), the minimum accepted, and which of
the two checks fired. That is the whole content of the report; without it the reader is left where
the missing status left them before.

### 5. The counter

`AnvilDiagnostics` gains `reportUnsupportedChunkVersion(String version)` returning `boolean` on first
sight of a value, plus an `@Unmodifiable Map<String, Long> unsupportedChunkVersions()` getter. This
follows `reportPartialChunk(String)` / `partialChunkStatuses()` (`:160`, `:316`) — the existing pair
that keeps a per-value breakdown rather than a bare count, and the one whose shape fits here, because
"which versions did this world contain" is the question an operator actually asks. Chunks carrying no
`DataVersion` are counted under a constant, in the way `UNKNOWN_STATUS` (`:59`) already serves that
role for the status breakdown.

## What does not change

- **`isFullyGenerated(null)` stays true.** It is not the defect. It only became one in combination
  with an unchecked layout, and step 2 removes that combination.
- **The sealed hierarchy**, as decided above.
- **The save path.** `snapshot(chunk)` (`:1559`) builds from the runtime chunk, not from foreign NBT;
  there is nothing there to migrate, only a target version to stamp. Note for later work: the save
  path swallows every `AnvilFormatException` (`:766-773`, log and report, no rethrow).

## API compatibility

The module runs `checkApiCompatibility` since 1.0.0. Three additions, all outward:

- a new enum constant — binary compatible; source-incompatible only for an exhaustive `switch` over
  `Reason` in foreign code, which is why it goes last in the declaration;
- a new builder method;
- a new diagnostics method and getter.

**One behavioural change, and it is deliberate:** a caller feeding the loader a pre-1.18 world gets an
exception where it got an air chunk before. That is the point of the change, and it belongs in the
changelog as such rather than as a fix note.

## Tests

Fixtures are built as NBT by hand — a root compound holding `Level.Sections` is a few lines and needs
neither a real old world nor Minecraft.

| # | Input | Expectation |
| --- | --- | --- |
| 1 | `Level` compound holding `Sections`, no root `sections`, no `DataVersion` | throws, `Reason.UNSUPPORTED_CHUNK_VERSION` |
| 2 | Root `sections`, `DataVersion` below the minimum | throws, same reason |
| 3 | Root `sections`, no `DataVersion` | loads normally |
| 4 | Case 1 and case 2 | `unsupportedChunkVersions()` holds one entry each — the stored version for case 2, the unknown-version constant for case 1 |

Case 1 is the one that pins the defect: today it returns a chunk and reports success.
Case 3 is the regression guard for tool-written worlds — it is what keeps the check from being too
sharp.

**Gegenprobe.** With the guard removed, cases 1 and 2 must go red and case 3 must stay green. A case
3 that also goes red means the check rejects worlds it should read, and the test caught the wrong
thing.

## Out of scope

This converts nothing. It reads a version, checks a layout, and fails honestly. Migrating a world is
the subject of `falco-migration`, specified separately: an NBT-to-NBT engine over the existing
`RegionFile` API, data-driven from the vendored ViaVersion mappings, upgrade direction first, lower
bound 1.16 initially. The flattening break and the world parts outside `region/` — `entities/`,
`poi/`, `playerdata/`, `level.dat` — are stages after that.

Reading the source version is the precondition for all of it: without it no mapping can be selected.
