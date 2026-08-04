# `falco-migration`, plan 1: the engine

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A library that lifts one Anvil chunk compound from Minecraft 1.13 to the version the server
writes — blocks, biomes and block entities — with no Minestom and no running server.

**Architecture:** An ordered chain of steps, each declaring the version interval it applies to. A
chunk runs the steps whose interval its source version intersects. Block translation is a strategy
behind a versioned rule set keyed on the whole state. Directory discovery is separate from the chain
and belongs to the batch runner, which plan 2 builds — but the resolution itself lives here, because
it is pure logic and testable without files.

**Tech Stack:** Java 25, Gradle, Adventure NBT (`net.kyori.adventure.nbt`), JUnit 5. No Minestom
anywhere in this module's main sources.

**Specs:** `docs/superpowers/specs/2026-08-04-falco-migration-design.md` and the measurement it rests
on, `docs/superpowers/specs/2026-08-04-blockstate-property-research.md`.

**Plan 2 (not this document)** builds the two front ends: the batch runner with its CLI, and the
`ChunkMigrator` implementation that plugs into `falco-anvil`'s service point. Neither is needed to
test anything here.

## Global Constraints

- New module `falco-migration`. It may depend on `falco-anvil` and on nothing else in this repository.
- **No Minestom in main sources.** Not `compileOnly`, not anywhere. The engine is pure NBT, and an ArchUnit rule enforces it.
- **The floor is DataVersion 1519 (1.13).** Below it the engine declines rather than guesses.
- Every public type and member carries `@ApiStatus.Experimental`, Javadoc with `@param`/`@return`/`@throws`, and `@since 2.1.0`.
- Javadoc runs under `-Werror`.
- Test method names read as sentences. Tests are package-private, plain JUnit assertions.
- Conventional Commits, lower case, scope `(migration)`.
- **No timing figure may be produced or quoted anywhere in this work.** Check `uptime` before test runs and record it.
- Counts come from the JUnit XML, never the console summary.

## The facts this plan encodes, and where they came from

Twenty-two facts, measured in `2026-08-04-blockstate-property-research.md`. Every rule the plan writes
carries its source in a comment — DataConverter's fix version, or the wiki page, or the computed
diff. **No rule is written from memory.**

| # | Case | States | Kind |
| --- | --- | ---: | --- |
| 1 | `grass` → `short_grass` (1.20.3) | 1 | name |
| 2 | `grass_path` → `dirt_path` (1.17) | 1 | name |
| 3 | `sign` → `oak_sign` (V1802) | 32 | name |
| 4 | `wall_sign` → `oak_wall_sign` (V1802) | 8 | name |
| 5 | `stone_slab` → `smooth_stone_slab` (V1802) | 6 | name, **1.13→1.14 only** |
| 6 | `cobblestone_wall` / `mossy_cobblestone_wall`: `north/south/east/west` `false,true` → `none,low,tall` (V2503) | 128 | value |
| 7 | `cauldron[level]` → `cauldron` or `water_cauldron[level]` (V2679) | 4 | **name decided by a property** |
| 8 | `redstone_wire` direction values (V2531) | 144 | **whole-state, cross-property** |

Zero property renames. 258 states with a missing property are **not** in this plan — Minestom fills
them from the target default, verified in all 30 cases, and Task 7 pins that with a test rather than
implementing it.

## File Structure

| File | Responsibility |
| --- | --- |
| `falco-migration/build.gradle.kts` | Module, depends on `falco-anvil` and adventure-nbt |
| `…/migration/MigrationContext.java` | Source version, target version, a counter sink |
| `…/migration/MigrationStep.java` | One step: does it apply, and what it does |
| `…/migration/ChunkMigration.java` | The chain; the public entry point |
| `…/migration/BlockStateRule.java` | One versioned rule over a whole state |
| `…/migration/BlockStateRules.java` | The 22 facts, each with its source |
| `…/migration/BlockState.java` | Name plus properties, immutable |
| `…/migration/WorldLayout.java` | Source directory discovery and target mapping |
| `…/migration/steps/*.java` | One class per chain step |
| `falco-archunit/…/MigrationBoundaryTest.java` | The module sees no Minestom |

---

### Task 1: The module, and the rule that keeps it honest

**Files:**
- Create: `falco-migration/build.gradle.kts`, `falco-migration/src/main/java/net/onelitefeather/falco/migration/package-info.java`
- Modify: `settings.gradle.kts` — `include("falco-migration")` after `falco-archunit`
- Create: `falco-archunit/src/test/java/net/onelitefeather/falco/architecture/MigrationBoundaryTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: the module and the package `net.onelitefeather.falco.migration`.

- [ ] **Step 1: Write the failing rule**

The rule comes before the code it guards, because it is the one thing that cannot be retrofitted once
an import slips in. In a new `MigrationBoundaryTest`:

```java
class MigrationBoundaryTest {

    private static final String MIGRATION = "net.onelitefeather.falco.migration..";

    @ArchTest
    static final ArchRule migrationKnowsNoMinestom = noClasses()
            .that().resideInAPackage(MIGRATION)
            .should().dependOnClassesThat().resideInAnyPackage("net.minestom..")
            .because("the engine converts stored NBT and must run without a server, which is what "
                   + "lets a world be converted before anything boots");
}
```

Copy the `@AnalyzeClasses` annotation and its import scope from the existing `ModuleBoundaryTest` in
the same package — read it first, do not guess the scope.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :falco-archunit:test --tests "*MigrationBoundaryTest*"`
Expected: failure — the package does not exist, so ArchUnit finds no classes. If ArchUnit instead
passes vacuously on an empty package, say so in the report: a rule that passes because it sees
nothing is worth nothing, and the next step is what gives it something to see.

- [ ] **Step 3: Create the module**

`settings.gradle.kts` gains `include("falco-migration")`.

`falco-migration/build.gradle.kts`, modelled on `falco-anvil/build.gradle.kts` — read that file
first, it is 17 lines:

```kotlin
description = "Converts stored Anvil chunk data from Minecraft 1.13 upwards"

dependencies {
    implementation(platform(libs.mycelium.bom))
    implementation(libs.slf4j.api)
    implementation(project(":falco-anvil"))

    compileOnly(libs.adventure.nbt)
    compileOnly(libs.annotations)

    testImplementation(libs.adventure.nbt)
    testImplementation(libs.annotations)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
```

**No `libs.minestom` line, in any configuration.** That is the point of the module.

`package-info.java` states what the package is: an engine over stored NBT that knows no server, with
the floor at DataVersion 1519 and the reason for it.

- [ ] **Step 4: Give the rule something to see, and watch it pass**

Add the smallest real type, so the rule analyses a non-empty package:

```java
@ApiStatus.Experimental
public record BlockState(String name, @Unmodifiable Map<String, String> properties) {

    public BlockState {
        properties = Map.copyOf(properties);
    }

    @Contract(pure = true)
    public static BlockState of(String name) {
        return new BlockState(name, Map.of());
    }
}
```

Run: `./gradlew :falco-archunit:test --tests "*MigrationBoundaryTest*" :falco-migration:build`
Expected: PASS.

- [ ] **Step 5: Gegenprobe**

Add `compileOnly(libs.minestom)` to the module and a single field of a Minestom type to `BlockState`.
`migrationKnowsNoMinestom` must go red and name that class. Revert both; verify `git status` is clean.
**Without this the rule is unproven** — an ArchUnit rule over a package that happens to have no
forbidden import yet passes for the wrong reason.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts falco-migration/ falco-archunit/
git commit -m "feat(migration): a module that cannot see a server, and the rule that says so"
```

---

### Task 2: Where a world keeps its regions

**Files:**
- Create: `…/migration/WorldLayout.java`
- Test: `…/migration/WorldLayoutTest.java`

**Interfaces:**
- Consumes: nothing from Task 1 but the package.
- Produces: `record WorldLayout.Region(Path directory, String dimensionKey, boolean legacy)`;
  `static List<Region> WorldLayout.discover(Path worldRoot) throws IOException`;
  `static Path WorldLayout.targetDirectory(Path worldRoot, String dimensionKey)`.

- [ ] **Step 1: Write the failing tests**

The whole point is that a legacy world keeps two of its three dimensions somewhere the loader never
looks. Build the directories with `@TempDir`:

```java
@Test
void testALegacyWorldYieldsAllThreeDimensions(@TempDir Path worldRoot) throws Exception {
    Files.createDirectories(worldRoot.resolve("region"));
    Files.createDirectories(worldRoot.resolve("DIM-1/region"));
    Files.createDirectories(worldRoot.resolve("DIM1/region"));

    List<WorldLayout.Region> found = WorldLayout.discover(worldRoot);

    assertEquals(
            Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
            found.stream().map(WorldLayout.Region::dimensionKey).collect(Collectors.toSet()));
    assertTrue(found.stream().allMatch(WorldLayout.Region::legacy));
}

@Test
void testAModernWorldYieldsWhateverItContains(@TempDir Path worldRoot) throws Exception {
    Files.createDirectories(worldRoot.resolve("dimensions/minecraft/overworld/region"));
    Files.createDirectories(worldRoot.resolve("dimensions/mypack/mining/region"));

    List<WorldLayout.Region> found = WorldLayout.discover(worldRoot);

    assertEquals(
            Set.of("minecraft:overworld", "mypack:mining"),
            found.stream().map(WorldLayout.Region::dimensionKey).collect(Collectors.toSet()));
    assertFalse(found.stream().anyMatch(WorldLayout.Region::legacy));
}

@Test
void testADatapackDimensionIsNotHardCodedAway(@TempDir Path worldRoot) throws Exception {
    Files.createDirectories(worldRoot.resolve("dimensions/mypack/mining/region"));

    assertEquals(1, WorldLayout.discover(worldRoot).size());
}

@Test
void testTheNetherLandsInItsModernPlace(@TempDir Path worldRoot) {
    assertEquals(
            worldRoot.resolve("dimensions/minecraft/the_nether/region"),
            WorldLayout.targetDirectory(worldRoot, "minecraft:the_nether"));
}

@Test
void testAWorldWithNoRegionsAtAllIsEmptyRatherThanAnError(@TempDir Path worldRoot) throws Exception {
    assertTrue(WorldLayout.discover(worldRoot).isEmpty());
}
```

The third case is the one that matters beyond the vanilla three: a data pack dimension appears under
`dimensions/` in both eras and must be enumerated, not matched against a list of three.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :falco-migration:test --tests "*WorldLayoutTest*"`
Expected: compilation failure.

- [ ] **Step 3: Implement it**

`discover` looks in both shapes and returns everything it finds:

- `<world>/region` → `minecraft:overworld`, legacy
- `<world>/DIM-1/region` → `minecraft:the_nether`, legacy
- `<world>/DIM1/region` → `minecraft:the_end`, legacy
- `<world>/dimensions/<namespace>/<value>/region` → `<namespace>:<value>`, modern — **enumerated by
  walking the directory, not by checking three known names**

`targetDirectory` always returns the modern shape, because that is what the target version reads.
`DIM-1` and `DIM1` are the only two fixed points in the mapping; everything under `dimensions/`
already carries its key in its path.

Javadoc must state why this exists rather than reusing the loader's resolution: `FalcoAnvilLoader`
knows `<world>/region` and the modern shape, and falls back to the former when the latter is absent.
For a legacy world that covers the overworld and **nothing else** — a converter reusing it would see
one third of a three-dimension world and report success.

- [ ] **Step 4: Run them and watch them pass**

Run: `./gradlew :falco-migration:test --tests "*WorldLayoutTest*"`
Expected: PASS, five cases.

- [ ] **Step 5: Gegenprobe**

Replace the `dimensions/` walk with a check against the three vanilla names.
`testADatapackDimensionIsNotHardCodedAway` must go red alone. Revert.

- [ ] **Step 6: Commit**

```bash
git add falco-migration/
git commit -m "feat(migration): find every dimension a world has, not the one the loader looks at"
```

---

### Task 3: A rule keyed on the whole state, resolved by version

**Files:**
- Create: `…/migration/BlockStateRule.java`, `…/migration/BlockStateRules.java`
- Test: `…/migration/BlockStateRulesTest.java`

**Interfaces:**
- Consumes: `BlockState` from Task 1.
- Produces: `interface BlockStateRule { int since(); BlockState apply(BlockState state); boolean matches(BlockState state); }`;
  `static BlockState BlockStateRules.translate(BlockState state, int sourceVersion)`.

- [ ] **Step 1: Write the failing tests**

Four cases, and each one exists because a specific fact forces it:

```java
@Test
void testAPlainRenameIsApplied() {
    assertEquals("minecraft:short_grass",
            BlockStateRules.translate(BlockState.of("minecraft:grass"), 1519).name());
}

@Test
void testStoneSlabIsRenamedFromThirteenButNotFromSixteen() {
    assertEquals("minecraft:smooth_stone_slab",
            BlockStateRules.translate(BlockState.of("minecraft:stone_slab"), 1519).name());
    assertEquals("minecraft:stone_slab",
            BlockStateRules.translate(BlockState.of("minecraft:stone_slab"), 2566).name());
}

@Test
void testACauldronsLevelDecidesItsName() {
    BlockState empty = new BlockState("minecraft:cauldron", Map.of("level", "0"));
    BlockState filled = new BlockState("minecraft:cauldron", Map.of("level", "2"));

    assertEquals("minecraft:cauldron", BlockStateRules.translate(empty, 1519).name());
    assertEquals(Map.of(), BlockStateRules.translate(empty, 1519).properties());

    BlockState water = BlockStateRules.translate(filled, 1519);
    assertEquals("minecraft:water_cauldron", water.name());
    assertEquals("2", water.properties().get("level"));
}

@Test
void testAWallSideBecomesLowRatherThanTrue() {
    BlockState wall = new BlockState("minecraft:cobblestone_wall",
            Map.of("north", "true", "south", "false", "up", "true"));

    BlockState converted = BlockStateRules.translate(wall, 1519);

    assertEquals("low", converted.properties().get("north"));
    assertEquals("none", converted.properties().get("south"));
    assertEquals("true", converted.properties().get("up"), "up is not one of the four sides");
}
```

The second case is the whole reason rules carry a version: `stone_slab` means one block in 1.13 and
another from 1.14, so translating it out of a 1.16 world would corrupt it.

The fourth case pins that `up` is **not** among the four rewritten sides. `up` exists unchanged in
both versions and is carried through; the 20w06a render change concerns it, not the four directions.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :falco-migration:test --tests "*BlockStateRulesTest*"`
Expected: compilation failure.

- [ ] **Step 3: Implement the rule type and the resolution**

`translate` applies every rule whose `since()` is **greater than the source version** — a rule dated
V1802 applies to a 1.13 world (1519 < 1802) and not to a 1.16 one (2566 > 1802). Rules apply in
ascending `since()` order, so a state can pass through several.

Get that comparison right and write the reasoning into the Javadoc: the version on a rule is the
version *in which the change happened*, so it applies exactly to sources older than it.

- [ ] **Step 4: Write the 22 facts, each with its source**

`BlockStateRules` holds them. **Every entry carries a comment naming where it came from** — the
DataConverter fix version, or the computed diff. The five name rules, the shared wall table for both
wall blocks, the cauldron rule, and `redstone_wire`.

For `redstone_wire`, read the rule from
`docs/superpowers/specs/2026-08-04-blockstate-property-research.md` and implement it as a
**whole-state** function: a direction's new value depends on the other three directions of the same
state. Implemented per property it is guaranteed wrong. If the research document does not carry
enough detail to implement it exactly, **stop and report that** rather than inventing the rule —
a wrong redstone rule is silent corruption, and the case is worth its own round.

- [ ] **Step 5: Run them and watch them pass**

Run: `./gradlew :falco-migration:test`
Expected: PASS.

- [ ] **Step 6: Gegenprobe**

Two defects, one at a time, each reverted:

1. Change the version comparison from `since() > sourceVersion` to `since() >= sourceVersion`.
   `testStoneSlabIsRenamedFromThirteenButNotFromSixteen` must go red.
2. Make the wall rule rewrite `up` along with the four sides. The fourth case must go red on its
   third assertion.

- [ ] **Step 7: Commit**

```bash
git add falco-migration/
git commit -m "feat(migration): versioned rules over whole block states, with their sources"
```

---

### Task 4: The chain, and the structural steps

**Files:**
- Create: `…/migration/MigrationContext.java`, `…/migration/MigrationStep.java`, `…/migration/ChunkMigration.java`
- Create: `…/migration/steps/UnfoldLevel.java`, `…/migration/steps/NamespaceStatus.java`, `…/migration/steps/DiscardHeightmapsAndLight.java`
- Test: `…/migration/ChunkMigrationTest.java`

**Interfaces:**
- Consumes: nothing from Task 3 yet — the chain is independent of the rules until Task 5.
- Produces: `record MigrationContext(int sourceVersion, int targetVersion)`;
  `interface MigrationStep { boolean appliesTo(int sourceVersion); CompoundBinaryTag apply(CompoundBinaryTag chunk, MigrationContext context); }`;
  `static CompoundBinaryTag ChunkMigration.migrate(CompoundBinaryTag chunk, int targetVersion)` — unchecked, see Step 3.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void testAPreEighteenChunkGetsItsFieldsOnTheRoot() throws Exception {
    CompoundBinaryTag legacy = CompoundBinaryTag.builder()
            .putInt("DataVersion", 2566)
            .put("Level", CompoundBinaryTag.builder()
                    .putInt("xPos", 3)
                    .putInt("zPos", 4)
                    .putString("Status", "full")
                    .put("Sections", ListBinaryTag.empty())
                    .build())
            .build();

    CompoundBinaryTag migrated = ChunkMigration.migrate(legacy, 4790);

    assertNull(migrated.get("Level"));
    assertEquals(3, migrated.getInt("xPos"));
    assertEquals("minecraft:full", migrated.getString("Status"));
    assertNotNull(migrated.get("sections"));
}

@Test
void testAModernChunkIsLeftAloneExceptForItsVersion() throws Exception {
    CompoundBinaryTag modern = CompoundBinaryTag.builder()
            .putInt("DataVersion", 3700)
            .putString("Status", "minecraft:full")
            .put("sections", ListBinaryTag.empty())
            .build();

    CompoundBinaryTag migrated = ChunkMigration.migrate(modern, 4790);

    assertEquals(4790, migrated.getInt("DataVersion"));
    assertEquals("minecraft:full", migrated.getString("Status"));
}

@Test
void testHeightmapsAndLightAreDroppedRatherThanConverted() throws Exception {
    CompoundBinaryTag chunk = CompoundBinaryTag.builder()
            .putInt("DataVersion", 2566)
            .put("Level", CompoundBinaryTag.builder()
                    .put("Heightmaps", CompoundBinaryTag.builder().putLongArray("WORLD_SURFACE", new long[]{1L}).build())
                    .put("Sections", ListBinaryTag.empty())
                    .build())
            .build();

    CompoundBinaryTag migrated = ChunkMigration.migrate(chunk, 4790);

    assertNull(migrated.get("Heightmaps"), "a wrongly ported heightmap never announces itself");
}

@Test
void testAChunkBelowTheFloorIsDeclinedRatherThanGuessedAt() {
    CompoundBinaryTag ancient = CompoundBinaryTag.builder().putInt("DataVersion", 1000).build();

    assertThrows(MigrationException.class, () -> ChunkMigration.migrate(ancient, 4790));
}
```

Check the exact Adventure NBT accessor names (`getInt`, `getString`, `get`) against the version this
project uses before writing these — `falco-anvil`'s `NbtReads` shows the idiom in use.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :falco-migration:test --tests "*ChunkMigrationTest*"`
Expected: compilation failure.

- [ ] **Step 3: Implement the chain**

`ChunkMigration.migrate` reads `DataVersion`, declines below 1519 with `MigrationException`, runs
every step whose `appliesTo` is true in declared order, and stamps the target version at the end.

**`MigrationException extends RuntimeException`, unchecked — corrected after Task 1's review.** This
plan first called for a checked type. That is impossible here, and reading the rules rather than
hitting them gives two reasons:

- `ErrorHandlingTest.checkedFaultsStayInsideTheHierarchy` runs over the whole
  `net.onelitefeather.falco..` tree, not only the published modules, and requires every **checked**
  throwable in it to be assignable to `AnvilFormatException`.
- That hierarchy is `sealed … permits ChunkDataException, RegionFormatException` and lives in another
  package, so it cannot be extended from here at all.

Unchecked is the house style anyway, and it comes with a requirement:
`ErrorHandlingTest.ownExceptionsAreUncheckedAndCarryACause` demands every `RuntimeException` in the
tree be public **and** carry a public `(String, Throwable)` constructor. Give `MigrationException`
exactly that, or that rule fails instead.

Drop `throws MigrationException` from the signature in the Interfaces block above accordingly.

- [ ] **Step 4: Implement the three steps**

- `UnfoldLevel` (below 2844): moves every child of `Level` onto the root, renames `Sections` to
  `sections`, and adds `yPos`. Read the field list from the spec's step table; do not invent names.
- `NamespaceStatus` (any version): rewrites a status without a namespace, leaves a namespaced one
  alone. **It tests no version at all** — the exact version that namespaced the status could not be
  established, and this formulation is correct for every version in range without depending on a
  number nobody has read.
- `DiscardHeightmapsAndLight` (any version): removes `Heightmaps`, `isLightOn`, and the per-section
  `BlockLight`/`SkyLight`. Deliberate deletion: a wrongly ported heightmap never announces itself,
  a missing one is rebuilt.

- [ ] **Step 5: Run them and watch them pass**

Run: `./gradlew :falco-migration:test`
Expected: PASS.

- [ ] **Step 6: Gegenprobe**

Make `NamespaceStatus` rewrite unconditionally, so `minecraft:full` becomes
`minecraft:minecraft:full`. `testAModernChunkIsLeftAloneExceptForItsVersion` must go red. Revert.

- [ ] **Step 7: Commit**

```bash
git add falco-migration/
git commit -m "feat(migration): the step chain, and the three that only move things"
```

---

### Task 5: Sections — bit packing and biomes

**Files:**
- Create: `…/migration/steps/NormaliseBitPacking.java`, `…/migration/steps/RebuildBiomes.java`, `…/migration/steps/TranslateBlockStates.java`
- Create: `…/migration/LegacyBitReader.java`
- Test: `…/migration/LegacyBitReaderTest.java`, `…/migration/steps/SectionStepsTest.java`

**Interfaces:**
- Consumes: `BlockStateRules.translate` from Task 3, the chain from Task 4.
- Produces: `static int[] LegacyBitReader.unpack(long[] packed, int bitsPerEntry, int entryCount)`.

- [ ] **Step 1: Write the failing test for the packing first**

This is the one piece of real bit work in the plan, and it is where a silent defect would hide.
Pre-1.16 entries **span long boundaries**; `falco-anvil`'s `BitPacker` cannot read that — its `pack`
Javadoc says "without letting an entry span two longs" and `unpack` computes
`longIndex = index / entriesPerLong`. So this module needs its own reader.

```java
@Test
void testAnEntryThatSpansTwoLongsIsReadWhole() {
    // 5 bits per entry: entry 12 starts at bit 60 and runs into the next long.
    long[] packed = { 0xF000_0000_0000_0000L, 0x0000_0000_0000_0001L };

    int[] values = LegacyBitReader.unpack(packed, 5, 13);

    assertEquals(0b11111, values[12], "the entry crosses the long boundary and must be read whole");
}

@Test
void testTheModernReaderWouldGetThatWrong() {
    long[] packed = { 0xF000_0000_0000_0000L, 0x0000_0000_0000_0001L };

    assertNotEquals(
            BitPacker.unpack(packed, 5, 13)[12],
            LegacyBitReader.unpack(packed, 5, 13)[12],
            "if these agree, the legacy reader is not doing anything and this module does not need it");
}
```

Work out the expected value by hand before writing the assertion, and put the derivation in the test
comment. **If the two readers agree, the second test fails and that is the correct outcome** — it
would mean the legacy format is not what this plan assumes, which is a finding to report, not a test
to adjust.

`BitPacker` is in `falco-anvil` and package-private-adjacent — check its visibility from this module
before relying on it in a test; if it is not reachable, assert against a hand-computed value instead
and say so in the report.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :falco-migration:test --tests "*LegacyBitReaderTest*"`
Expected: compilation failure.

- [ ] **Step 3: Implement the reader**

A bit offset that walks continuously across the array, rather than restarting per long.

- [ ] **Step 4: The three section steps**

- `NormaliseBitPacking` (below 2566): re-packs every section's block state data from the spanning
  layout into the long-aligned one.
- `RebuildBiomes` (below 2844): the biome array — 256 bytes before 1.15, 1024 ints from 1.15 — into a
  palettised container per section. Read the exact shapes from the spec's step table.
- `TranslateBlockStates` (any version): walks each section's palette and puts every entry through
  `BlockStateRules.translate`, carrying the source version.

Each step's test builds a section by hand and asserts on the result. At least one test must carry a
`cobblestone_wall` with `north=true` through the **whole chain** and assert it comes out as
`north=low` — that is the case that would otherwise abort the chunk on load, and it is the proof that
the rules and the chain are actually wired together rather than merely both present.

- [ ] **Step 5: Run them and watch them pass**

Run: `./gradlew :falco-migration:test`
Expected: PASS.

- [ ] **Step 6: Gegenprobe**

Make `TranslateBlockStates` pass the target version to `translate` instead of the source version.
The wall case must go red — with the target version, no rule applies and `north=true` survives.
Revert.

- [ ] **Step 7: Commit**

```bash
git add falco-migration/
git commit -m "feat(migration): read the packing 1.13 wrote, and translate what it held"
```

---

### Task 6: Block entities, and counting what is left behind

**Files:**
- Create: `…/migration/steps/TranslateBlockEntities.java`, `…/migration/steps/CountEntities.java`
- Modify: `…/migration/MigrationContext.java` — the counter sink
- Test: `…/migration/steps/BlockEntityStepTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void testABlockEntityIdIsRenamedLikeItsBlock() throws Exception {
    // a 1.13 sign block entity keeps its coordinates and gains the renamed id
    …
}

@Test
void testTheEntitiesLeftInTheChunkAreCountedRatherThanMoved() throws Exception {
    CompoundBinaryTag chunk = /* a 1.13 chunk with two entities in Level.Entities */;

    MigrationContext context = new MigrationContext(1519, 4790);
    ChunkMigration.migrate(chunk, context);

    assertEquals(2, context.entitiesLeftBehind());
}
```

Fill in the first fixture from the block entity list the plan's Task 6 research establishes — see the
next step. Do not invent a rename.

- [ ] **Step 2: Establish the block entity renames exhaustively**

The spec records this as work with no data source: the 1.13 mapping file carries no `blockentities`
list, so no diff settles it. The route it names is small enough to finish: read the target version's
list of 49 and check each against what a 1.13 world can contain.

Do that, write the result into the report with a source per entry, and **encode only what you can
source**. Where a rename is uncertain, leave it out and list it in the report as unresolved rather
than guessing — an invented block entity rename silently rewrites a chest.

- [ ] **Step 3: Implement both steps**

`TranslateBlockEntities` renames the `id` and nothing else. Explicitly out of scope, and named in the
Javadoc: the **items inside** a block entity, and per-block-entity field changes such as the 1.20
sign rework.

`CountEntities` (below 2724) counts what it finds and moves nothing. Its Javadoc must state the
consequence plainly, in the spec's own words: the data stays in the chunk where the target version
will never look for it, so every mob, item frame and painting is effectively gone — counted, so it
cannot happen quietly.

- [ ] **Step 4: Run, Gegenprobe, commit**

Run `./gradlew :falco-migration:test`. Then remove the counter increment from `CountEntities` and
confirm the entity case goes red alone; revert. Commit as
`feat(migration): translate block entities, and count the entities this slice leaves behind`.

---

### Task 7: Acceptance

- [ ] **Step 1: Check the load, then run every module**

`uptime` before and after, both into the report.

```bash
./gradlew :falco-anvil:test :falco-light:test :falco-instance:test :falco-demo:test \
          :falco-benchmarks:test :falco-archunit:test :falco-migration:test --rerun-tasks
```

Counts from the JUnit XML. No count may fall against the baseline in
`docs/superpowers/plans/2026-08-04-anvil-extension-points.md`'s `## Result`.

- [ ] **Step 2: Build with javadoc and the API check**

```bash
./gradlew build -x test --rerun-tasks
```

`falco-migration` is new, so `checkApiCompatibility` has no baseline for it — confirm that is handled
rather than silently skipped, and record what the build actually does about it.

- [ ] **Step 3: Pin the free half, do not implement it**

One test that a 1.13 coral **without** `waterlogged` ends up `waterlogged=true` once loaded, and a
1.13 block whose new property defaults to false ends up false. This is the test the spec asks for
because the intuitive rule is wrong — the rule is "the target version's default", never "false", and
a coral written as `false` dries out every reef.

This test needs Minestom to resolve a default, so it lives in **`falco-anvil`'s or the demo's** test
sources, never in `falco-migration` — the boundary rule from Task 1 forbids it here, and that rule is
worth more than the convenience.

- [ ] **Step 4: Attack the gate**

Re-inject Task 5's Gegenprobe (target version instead of source version) and confirm the **full**
suite catches it, not one class. Revert; `git status --short` empty.

- [ ] **Step 5: Write the result into the plan and commit**

Append `## Result`: cases added per task, which injected defect each caught, module counts from the
XML, both load figures, the block entity renames actually established with their sources and the ones
left unresolved, and explicitly what this plan does **not** deliver: no batch runner, no CLI, no
loader hook, no entity move, nothing outside `region/`, nothing below 1.13.

---

## Self-Review

**Spec coverage.** Directory structure → Task 2. Step chain steps 1 and 4 → Task 5; steps 2 and 6 →
Task 6 and Task 4; steps 3 and 5 → Task 4; step 7 → Tasks 3 and 5; step 8 → Task 4. The strategy and
its three load-bearing properties → Task 3. The floor → Task 4. The free 258 states → Task 7 Step 3,
pinned rather than implemented. The two front ends are explicitly plan 2.

**Placeholders.** Two steps carry a deliberate "establish this, then encode it" instruction rather
than fixed content: the block entity renames in Task 6 Step 2, which the spec itself records as
having no data source, and the `redstone_wire` rule in Task 3 Step 4, which must be read from the
research document rather than from memory. Both say what to do when the answer cannot be sourced —
report it, do not invent it. Task 6 Step 1's first fixture is intentionally left to be filled from
Step 2's result, because writing a fixture before establishing the fact would be inventing one.

**Type consistency.** `BlockState(String name, Map<String,String> properties)` in Tasks 1, 3 and 5.
`MigrationContext(int sourceVersion, int targetVersion)` in Tasks 4 and 6, gaining the counter in 6.
`BlockStateRules.translate(BlockState, int sourceVersion)` — always the **source** version, which is
what Task 5's Gegenprobe attacks. `MigrationException` is this module's own **unchecked** type, never
`falco-anvil`'s sealed hierarchy.

**One risk this plan cannot remove.** The `redstone_wire` rule is 144 states of cross-property logic
transcribed from a research document rather than derived. If it is wrong it is silent — the chunk
loads and the wiring looks subtly different. Task 3 says to stop and report rather than invent, and
the acceptance does not claim the rule is verified against a real world, because nothing here can.
