# Anvil version guard — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `FalcoAnvilLoader` rejects a chunk it cannot read instead of returning it as air.

**Architecture:** One guard call at the single seam in `loadChunk` where the full root compound
exists and nothing has been interpreted yet. It checks the *layout* first — a root without
`sections` but with a `Level` compound is the pre-1.18 shape — and the stored `DataVersion` second,
against a configurable floor. Both rejections throw `ChunkDataException` with one new `Reason` and
are counted per version value in `AnvilDiagnostics`.

**Tech Stack:** Java 25, Gradle, Adventure NBT (`net.kyori.adventure.nbt`), JUnit 5, Minestom
(`compileOnly`), MicrotusExtension for the environment-backed tests.

**Spec:** `docs/superpowers/specs/2026-08-03-anvil-version-guard-design.md`

## Global Constraints

- Base branch `feat/anvil-version-guard`, worktree `/mnt/projects/oss/onelitefeather/Falco-worktrees/anvil-version-guard`, off `origin/main` (`2d3955d8`, the 1.0.0 baseline).
- **The sealed hierarchy is not touched.** `AnvilFormatException permits ChunkDataException, RegionFormatException` stays exactly as it is.
- **`isFullyGenerated(null) == true` stays.** It is not the defect.
- **The save path is not touched.**
- Every new public type and member carries `@ApiStatus.Experimental`, Javadoc with `@param`/`@return`, and `@since 1.1.0`. Every modified type's `@version` is raised by one minor.
- Javadoc runs under `-Werror`; a missing tag fails the build.
- `checkApiCompatibility` runs on this module. **Every signature change must be additive** — no member is removed, renamed, or has its parameters changed. This binds the API surface, not the behaviour: Task 3 deliberately changes what the loader *does* with a pre-1.18 world, which is why its commit carries the `!` marker, and that is not a contradiction of this line. Binary compatibility and behavioural compatibility are separate promises here, and only the first one is enforced by the build.
- Builders in this project are immutable: every setter returns a **new** `Builder` with all fields passed through. Adding a field means touching the constructor, `build()`, and **every** existing setter.
- Test method names in this module read as sentences: `testLoadingAnAbsentChunkReturnsNull`.
- Commit messages are Conventional Commits, lower case, and say what changed and why.
- No timing figure may be produced or quoted anywhere in this work.

## File Structure

| File | Responsibility | Change |
| --- | --- | --- |
| `falco-anvil/src/main/java/…/ChunkDataException.java` | The fault type and its reasons | Modify: one new `Reason` constant, last in the declaration |
| `falco-anvil/src/main/java/…/AnvilDiagnostics.java` | Counters and per-value breakdowns | Modify: one constant, one field pair, `reportUnsupportedChunkVersion`, `unsupportedChunkVersions`, `chunksSkippedAsUnsupported` |
| `falco-anvil/src/main/java/…/FalcoAnvilLoader.java` | The loader, its builder, the guard | Modify: two key constants, one builder field with its setter and pass-throughs, the guard method, one call at the seam |
| `falco-anvil/src/test/java/…/AnvilDiagnosticsTest.java` | Diagnostics unit tests | Modify: three cases |
| `falco-anvil/src/test/java/…/FalcoAnvilLoaderBuilderTest.java` | Builder unit tests | Modify: two cases |
| `falco-anvil/src/test/java/…/FalcoAnvilLoaderIntegrationTest.java` | Loader against a running environment | Modify: three cases, reusing the existing `writeRawChunk` helper at `:573` |

No new file. The guard is twenty lines in the class that owns the seam; a class of its own would
split one decision across two files.

---

### Task 1: The reason and the diagnostics pair

**Files:**
- Modify: `falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkDataException.java:37-68`
- Modify: `falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilDiagnostics.java`
- Test: `falco-anvil/src/test/java/net/onelitefeather/falco/anvil/AnvilDiagnosticsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `ChunkDataException.Reason.UNSUPPORTED_CHUNK_VERSION`;
  `AnvilDiagnostics.UNKNOWN_DATA_VERSION` (`String`, value `"<none>"`);
  `boolean AnvilDiagnostics.reportUnsupportedChunkVersion(String version)`;
  `@Unmodifiable Map<String, Long> AnvilDiagnostics.unsupportedChunkVersions()`;
  `long AnvilDiagnostics.chunksSkippedAsUnsupported()`.

- [ ] **Step 1: Write the failing tests**

In `AnvilDiagnosticsTest.java`:

```java
@Test
void testAnUnsupportedVersionIsCountedUnderItsOwnValue() {
    AnvilDiagnostics diagnostics = new AnvilDiagnostics();

    assertTrue(diagnostics.reportUnsupportedChunkVersion("1976"));
    assertFalse(diagnostics.reportUnsupportedChunkVersion("1976"));
    assertTrue(diagnostics.reportUnsupportedChunkVersion("2724"));

    assertEquals(3, diagnostics.chunksSkippedAsUnsupported());
    assertEquals(Map.of("1976", 2L, "2724", 1L), diagnostics.unsupportedChunkVersions());
}

@Test
void testAChunkWithoutAStoredVersionIsCountedApart() {
    AnvilDiagnostics diagnostics = new AnvilDiagnostics();

    diagnostics.reportUnsupportedChunkVersion(AnvilDiagnostics.UNKNOWN_DATA_VERSION);

    assertEquals(Map.of(AnvilDiagnostics.UNKNOWN_DATA_VERSION, 1L),
            diagnostics.unsupportedChunkVersions());
}

@Test
void testTheVersionBreakdownIsSortedByValue() {
    AnvilDiagnostics diagnostics = new AnvilDiagnostics();

    diagnostics.reportUnsupportedChunkVersion("2724");
    diagnostics.reportUnsupportedChunkVersion("1976");

    assertEquals(List.of("1976", "2724"),
            List.copyOf(diagnostics.unsupportedChunkVersions().keySet()));
}
```

The third case pins the ordering promise `partialChunkStatuses()` already makes in its Javadoc: a
summary that lists values in a different order on every shutdown cannot be compared between runs.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :falco-anvil:test --tests "*AnvilDiagnosticsTest*"`
Expected: compilation failure — `reportUnsupportedChunkVersion` does not exist.

- [ ] **Step 3: Add the reason**

At the **end** of `ChunkDataException.Reason` (after `MISSING_OR_MISTYPED_KEY`, `:67`), preserving
the existing Javadoc style of the constants above it:

```java
        /**
         * The chunk comes from a Minecraft version this loader cannot read. Either it carries the
         * pre-1.18 layout, which keeps everything under {@code Level}, or its stored
         * {@code DataVersion} is below the configured floor.
         */
        UNSUPPORTED_CHUNK_VERSION
```

Add the comma after `MISSING_OR_MISTYPED_KEY`. Going last matters: a foreign exhaustive `switch` over
`Reason` keeps compiling against every constant that was already there.

- [ ] **Step 4: Add the diagnostics pair**

Follow `reportPartialChunk(String)` (`:160`) exactly — the cap, the race comment, the throttle
semantics. Next to `UNKNOWN_STATUS` (`:59`):

```java
    /**
     * The value an unsupported chunk is counted under when it stored no {@code DataVersion} at all.
     */
    public static final String UNKNOWN_DATA_VERSION = "<none>";
```

Two fields next to `partialChunkStatuses` and `partialChunks`, initialised in the constructor the
same way:

```java
    private final Map<String, LongAdder> unsupportedChunkVersions;
    private final LongAdder unsupportedChunks;
```

The reporter, mirroring `reportPartialChunk` including the cap behaviour:

```java
    /**
     * Reports a chunk which comes from a version this loader cannot read.
     * <p>
     * The throttling is per version value rather than per loader, so a world holding several
     * versions names each of them exactly once. A version beyond the cap is still counted in
     * {@link #chunksSkippedAsUnsupported()} and only loses its own entry in
     * {@link #unsupportedChunkVersions()}.
     * </p>
     *
     * @param version the stored data version, or {@link #UNKNOWN_DATA_VERSION} if none was stored
     * @return true if the caller should log the problem, otherwise false
     */
    public boolean reportUnsupportedChunkVersion(String version) {
        this.unsupportedChunks.increment();
        LongAdder counter = this.unsupportedChunkVersions.get(version);

        if (counter == null) {
            if (this.unsupportedChunkVersions.size() >= MAX_TRACKED_NAMES) {
                return false;
            }
            LongAdder created = new LongAdder();
            LongAdder previous = this.unsupportedChunkVersions.putIfAbsent(version, created);

            if (previous == null) {
                created.increment();
                return true;
            }
            previous.increment();
            return false;
        }
        counter.increment();
        return false;
    }
```

The two getters, mirroring `partialChunkStatuses()` (`:316`) including the `LinkedHashMap` and the
reason for it:

```java
    /**
     * Returns how many chunks were refused because their version could not be read.
     *
     * @return the amount of refused chunks
     */
    @Contract(pure = true)
    public long chunksSkippedAsUnsupported() {
        return this.unsupportedChunks.sum();
    }

    /**
     * Returns the amount of refused chunks per stored data version, sorted by the version value.
     *
     * @return the amount of refused chunks per version
     */
    @Contract(pure = true)
    public @Unmodifiable Map<String, Long> unsupportedChunkVersions() {
        Map<String, Long> snapshot = new LinkedHashMap<>();

        this.unsupportedChunkVersions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> snapshot.put(entry.getKey(), entry.getValue().sum()));

        return Collections.unmodifiableMap(snapshot);
    }
```

Raise `@version` on both classes by one minor.

- [ ] **Step 5: Run the tests and watch them pass**

Run: `./gradlew :falco-anvil:test --tests "*AnvilDiagnosticsTest*"`
Expected: PASS, and the existing cases in that class still pass.

- [ ] **Step 6: Gegenprobe**

Drop the `size() >= MAX_TRACKED_NAMES` check. `testAnUnsupportedVersionIsCountedUnderItsOwnValue`
must stay green — it does not reach the cap — so add nothing on that basis; instead delete the
`sorted(...)` line and confirm `testTheVersionBreakdownIsSortedByValue` goes red while the other two
stay green. Revert. If it does not go red, the ordering is accidental and the test is worthless.

- [ ] **Step 7: Commit**

```bash
git add falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkDataException.java \
        falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilDiagnostics.java \
        falco-anvil/src/test/java/net/onelitefeather/falco/anvil/AnvilDiagnosticsTest.java
git commit -m "feat(anvil): count the chunks whose version the loader cannot read"
```

---

### Task 2: The builder slot

**Files:**
- Modify: `falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java` — builder field `:290-315`, every setter through `:460`, `build()`, the loader field and constructor `:119`/`:207`
- Test: `falco-anvil/src/test/java/net/onelitefeather/falco/anvil/FalcoAnvilLoaderBuilderTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `FalcoAnvilLoader.Builder minimumDataVersion(int minimumDataVersion)`;
  the loader field `private final int minimumDataVersion;`;
  the constant `DEFAULT_MINIMUM_DATA_VERSION = 2860`.

- [ ] **Step 1: Write the failing tests**

In `FalcoAnvilLoaderBuilderTest.java`, matching the style of the cases already there:

```java
@Test
void testTheMinimumDataVersionDefaultsToTheFirstRootLayout() {
    assertEquals(2860, FalcoAnvilLoader.DEFAULT_MINIMUM_DATA_VERSION);
}

@Test
void testANegativeMinimumDataVersionIsRefused() {
    assertThrows(IllegalArgumentException.class,
            () -> FalcoAnvilLoader.builder().minimumDataVersion(-1));
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :falco-anvil:test --tests "*FalcoAnvilLoaderBuilderTest*"`
Expected: compilation failure — neither member exists.

- [ ] **Step 3: Add the constant, the field and the setter**

Next to the other constants at the top of `FalcoAnvilLoader`:

```java
    /**
     * The lowest data version the loader reads by default: 1.18, the first version whose chunks
     * carry {@code sections} on the root compound instead of under {@code Level}.
     */
    public static final int DEFAULT_MINIMUM_DATA_VERSION = 2860;
```

Add `private final int minimumDataVersion;` to **both** `FalcoAnvilLoader` (next to `dataVersion`,
`:119`) and `Builder` (`:297`), assign it in both constructors, and thread it through **every**
existing `Builder` setter and `build()`. There are eight setters; each constructs a new `Builder`
with the full field list, and every one of them needs the new argument. Missing one silently drops
the caller's value.

The setter itself, placed next to `dataVersion(int)` (`:411`):

```java
        /**
         * Sets the lowest data version the loader accepts when reading a chunk.
         * <p>
         * This is the read side and has nothing to do with {@link #dataVersion(int)}, which is the
         * version written into every saved chunk. A chunk below this floor is refused rather than
         * read, because the layout it carries would otherwise decode to air.
         * </p>
         *
         * @param minimumDataVersion the lowest data version the loader accepts
         * @return a new builder with this value
         * @throws IllegalArgumentException if the version is negative
         */
        @Contract(value = "_ -> new", pure = true)
        public Builder minimumDataVersion(int minimumDataVersion) {
            if (minimumDataVersion < 0) {
                throw new IllegalArgumentException(
                        "The minimum data version must not be negative but was " + minimumDataVersion);
            }
            return new Builder(this.openRegionLimit,
                    this.compressionLevel,
                    this.saveParallelism,
                    this.dataVersion,
                    minimumDataVersion,
                    this.diagnostics,
                    this.blockResolver,
                    this.biomeResolver,
                    this.exceptionHandler);
        }
```

Raise `@version` on `FalcoAnvilLoader`.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `./gradlew :falco-anvil:test --tests "*FalcoAnvilLoaderBuilderTest*"`
Expected: PASS, and every existing builder case still passes.

- [ ] **Step 5: Gegenprobe on the pass-through**

`Builder` exposes no readers, so the value is asserted through the built loader. Add a
**package-private** reader on `FalcoAnvilLoader`, next to the field — package-private keeps it out of
the published API and therefore out of `checkApiCompatibility`:

```java
    @Contract(pure = true)
    int minimumDataVersion() {
        return this.minimumDataVersion;
    }
```

`builder()` is at `:256` and `build(Path worldRoot, Key dimension)` at `:541`, both verified against
this baseline. Add the case:

```java
@Test
void testTheMinimumDataVersionSurvivesEveryOtherSetter(@TempDir Path worldRoot) {
    FalcoAnvilLoader loader = FalcoAnvilLoader.builder()
            .minimumDataVersion(1519)
            .openRegionLimit(4)
            .compressionLevel(3)
            .saveParallelism(2)
            .build(worldRoot, Key.key("minecraft:overworld"));

    assertEquals(1519, loader.minimumDataVersion());
}
```

Then inject the defect: in `openRegionLimit(int)` at `:337`, replace `this.minimumDataVersion` with
`DEFAULT_MINIMUM_DATA_VERSION`. The case must go **red**, because the value set first is dropped by a
setter called after it — which is exactly the mistake the immutable-builder pattern invites when a
field is added. Revert the defect; the test stays.

- [ ] **Step 6: Commit**

```bash
git add falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java \
        falco-anvil/src/test/java/net/onelitefeather/falco/anvil/FalcoAnvilLoaderBuilderTest.java
git commit -m "feat(anvil): let a caller say which data version is the floor"
```

---

### Task 3: The guard at the seam

**Files:**
- Modify: `falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java:654-655` and the constants block at `:92-98`
- Test: `falco-anvil/src/test/java/net/onelitefeather/falco/anvil/FalcoAnvilLoaderIntegrationTest.java`

**Interfaces:**
- Consumes: `Reason.UNSUPPORTED_CHUNK_VERSION` and `reportUnsupportedChunkVersion(String)` from Task 1; `minimumDataVersion` from Task 2.
- Produces: nothing further tasks build on.

- [ ] **Step 1: Write the failing tests**

In `FalcoAnvilLoaderIntegrationTest.java`, using the existing `writeRawChunk` helper at `:573`:

```java
@Test
void testAPreRootLayoutChunkIsRefusedInsteadOfReadAsAir(Env env) throws Exception {
    CompoundBinaryTag legacy = CompoundBinaryTag.builder()
            .put("Level", CompoundBinaryTag.builder()
                    .putString("Status", "minecraft:full")
                    .put("Sections", ListBinaryTag.empty())
                    .build())
            .build();
    writeRawChunk(3, 3, legacy);

    try (FalcoAnvilLoader loader = loader()) {
        Instance instance = env.createEmptyInstance(loader);

        ChunkDataException failure = assertThrows(ChunkDataException.class,
                () -> loader.loadChunk(instance, 3, 3));
        assertEquals(ChunkDataException.Reason.UNSUPPORTED_CHUNK_VERSION, failure.reason());
    }
}

@Test
void testAChunkBelowTheFloorIsRefused(Env env) throws Exception {
    CompoundBinaryTag old = CompoundBinaryTag.builder()
            .putInt("DataVersion", 2724)
            .putString("Status", "minecraft:full")
            .put("sections", ListBinaryTag.empty())
            .build();
    writeRawChunk(4, 4, old);

    AnvilDiagnostics diagnostics = new AnvilDiagnostics();
    try (FalcoAnvilLoader loader = FalcoAnvilLoader.builder()
            .diagnostics(diagnostics)
            .build(this.worldRoot, OVERWORLD)) {
        Instance instance = env.createEmptyInstance(loader);

        assertThrows(ChunkDataException.class, () -> loader.loadChunk(instance, 4, 4));
        assertEquals(Map.of("2724", 1L), diagnostics.unsupportedChunkVersions());
    }
}

@Test
void testAChunkWithoutAStoredVersionStillLoads(Env env) throws Exception {
    CompoundBinaryTag toolWritten = CompoundBinaryTag.builder()
            .putString("Status", "minecraft:full")
            .put("sections", ListBinaryTag.empty())
            .build();
    writeRawChunk(5, 5, toolWritten);

    try (FalcoAnvilLoader loader = loader()) {
        Instance instance = env.createEmptyInstance(loader);

        assertNotNull(loader.loadChunk(instance, 5, 5));
    }
}
```

The third is the regression guard. A world written by a tool that stores no `DataVersion` must keep
loading — that is what `isFullyGenerated(null) == true` exists for, and the guard must not take it
away.

Check the exact `build(...)` signature against `FalcoAnvilLoaderBuilderTest` before writing the
second case; adjust the call if it differs.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :falco-anvil:test --tests "*FalcoAnvilLoaderIntegrationTest*"`
Expected: cases 1 and 2 FAIL — case 1 because `loadChunk` returns a chunk rather than throwing,
which **is the defect this whole plan exists for**; case 2 likewise. Case 3 already passes.

- [ ] **Step 3: Add the two key constants**

Next to `SECTIONS_KEY` (`:92`):

```java
    private static final String LEGACY_LEVEL_KEY = "Level";
    private static final String DATA_VERSION_KEY = "DataVersion";
```

- [ ] **Step 4: Write the guard**

Place it next to `chunkStatus` (`:1353`):

```java
    /**
     * Refuses a chunk which comes from a version this loader cannot read.
     * <p>
     * The layout is checked before the version, because a version number is a claim about the data
     * while the layout is the data: a chunk may carry no version at all, and one that carries a
     * version may not hold what that version promises. A root compound without {@code sections} but
     * with a {@code Level} compound is the pre-1.18 shape, which would otherwise decode to an empty
     * section list and reach the caller as a chunk of air.
     * </p>
     *
     * @param data the root compound of the chunk
     * @throws ChunkDataException if the chunk cannot be read
     */
    private void requireReadableVersion(CompoundBinaryTag data) throws ChunkDataException {
        int version = NbtReads.optionalInteger(data, DATA_VERSION_KEY, -1);
        String reported = version < 0 ? AnvilDiagnostics.UNKNOWN_DATA_VERSION : Integer.toString(version);
        boolean legacyLayout = data.get(SECTIONS_KEY) == null
                && NbtReads.optionalCompound(data, LEGACY_LEVEL_KEY) != null;

        if (!legacyLayout && (version < 0 || version >= this.minimumDataVersion)) {
            return;
        }

        if (this.diagnostics.reportUnsupportedChunkVersion(reported)) {
            LOGGER.warn(
                    "Refusing a chunk from data version {} in {}: {}",
                    reported, this.regionDirectory,
                    legacyLayout
                            ? "the chunk data sits under Level, which this loader does not read"
                            : "the loader accepts " + this.minimumDataVersion + " and above"
            );
        }

        throw new ChunkDataException(
                ChunkDataException.Reason.UNSUPPORTED_CHUNK_VERSION,
                legacyLayout
                        ? "The chunk stores its data under Level, which means a version before 1.18"
                        : "The chunk stores data version " + version
                                + " but the loader accepts " + this.minimumDataVersion + " and above"
        );
    }
```

Note the two conditions are deliberately not symmetric: a missing version (`-1`) alone is **not** a
rejection, a legacy layout alone **is**.

- [ ] **Step 5: Call it at the seam**

Between `:654` and `:655`, leaving both lines untouched:

```java
            CompoundBinaryTag data = TAG_READER.read(new ByteArrayInputStream(raw.decompress()), BinaryTagIO.Compression.NONE);
            requireReadableVersion(data);
            String status = chunkStatus(data);
```

The `ChunkDataException` propagates into the existing `catch` at `:692` and through `failedLoad`,
which already calls `countError()` (`:712`). The refused chunk is therefore counted twice on purpose:
once as an error, once in the version breakdown. Raise `@version` on the class.

- [ ] **Step 6: Run the tests and watch them pass**

Run: `./gradlew :falco-anvil:test --tests "*FalcoAnvilLoaderIntegrationTest*"`
Expected: PASS, all three, and every existing case in the class unchanged.

- [ ] **Step 7: Gegenprobe**

Two defects, injected one at a time, each reverted afterwards:

1. Drop `&& NbtReads.optionalCompound(data, LEGACY_LEVEL_KEY) != null` from `legacyLayout`.
   `testAChunkWithoutAStoredVersionStillLoads` must go **red** — the guard now rejects any chunk
   without a root `sections`, including tool-written ones. This proves the second half of the
   condition earns its place.
2. Change `version >= this.minimumDataVersion` to `version >= 0`. `testAChunkBelowTheFloorIsRefused`
   must go red while the other two stay green.

Verify `git status` is clean after each revert.

- [ ] **Step 8: Commit**

```bash
git add falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java \
        falco-anvil/src/test/java/net/onelitefeather/falco/anvil/FalcoAnvilLoaderIntegrationTest.java
git commit -m "fix(anvil)!: refuse a pre-1.18 world instead of reading it as air"
```

The `!` is deliberate: a caller feeding the loader such a world now gets an exception where it got an
air chunk. That is the point of the change and belongs in the changelog as a break.

---

### Task 4: Acceptance

**Files:** none modified unless a defect is found.

- [ ] **Step 1: Run every module**

```bash
./gradlew :falco-anvil:test :falco-light:test :falco-instance:test \
          :falco-demo:test :falco-benchmarks:test :falco-archunit:test --rerun-tasks
```

Take the counts **from the JUnit XML** under each module's `build/test-results/test/`, not from the
console summary. No count may fall. The baseline at `2d3955d8` is: anvil 217, light 205, instance
186, demo 166, benchmarks 42 (one skipped — `EmptySectionCensusTest` wants an Anvil world on disk),
archunit 46. Anvil gains eight cases from tasks 1 to 3.

- [ ] **Step 2: Check the load first, and record it**

Run `uptime` before and after. Both figures go into the report. **No timing figure is produced here
and none may be quoted** — this task measures counts, not speed.

- [ ] **Step 3: Build with javadoc and the API check**

```bash
./gradlew build -x test --rerun-tasks
```

Expected: green, javadoc genuinely executed for all four published modules with zero warnings, and
`checkApiCompatibility` executed. If japicmp flags the new enum constant, do not weaken the check —
record what it says and stop; that is a decision for the project owner.

- [ ] **Step 4: Attack the gate**

Re-inject the defect from Task 3 Gegenprobe #1 and confirm it is caught by the full suite and not
only by the single test class. Revert, verify the tree is clean, confirm the suites are green again.

- [ ] **Step 5: Write the report and commit it**

Append a `## Result` section to this plan: which cases were added, which defect each one caught,
the counts per module from the XML, both load figures, and — explicitly — what this work does **not**
do: it converts nothing, it does not touch the save path, and it does not make a pre-1.18 world
usable.

```bash
git add docs/superpowers/plans/2026-08-03-anvil-version-guard.md
git commit -m "docs(plan): record what the acceptance measured, and what it did not"
```

---

### Task 5: Documentation

**Files:**
- Modify: `README.md`
- Modify: the `Anvil-Chunk-Loader` page in the wiki repository at `/mnt/projects/oss/onelitefeather/Falco.wiki`

- [ ] **Step 1: State the floor in the README**

The README table row for `falco-anvil` says a read failure throws "so the server cannot overwrite
real data with a freshly generated chunk". Add one sentence naming the floor: the loader reads worlds
from 1.18 onwards and refuses older ones instead of reading them as air. Do not add a section.

- [ ] **Step 2: Extend the existing wiki page**

The long-form documentation lives in the wiki repository, not here. Extend the existing
`Anvil-Chunk-Loader` page with the floor, the builder slot, the new reason and the diagnostics
getter. **No new page**, so the sidebar needs no entry.

- [ ] **Step 3: Commit both, separately**

The wiki is its own repository with its own history.

```bash
git add README.md
git commit -m "docs(anvil): say which worlds the loader reads"
```

---

## Self-Review

**Spec coverage.** Every section of the spec maps to a task: the seam and both checks to Task 3;
the reason to Task 1; the builder slot and the 2860 default to Task 2; the counter to Task 1; the
API-compatibility note to Task 4 Step 3; all four test cases of the spec's table to Tasks 1 and 3
(case 4 of the spec is folded into Task 3's second case, which asserts the breakdown directly);
"what does not change" to the Global Constraints. The spec's out-of-scope section needs no task.

**Placeholders.** None. Every code step carries the code. Task 2 Step 5 first read "if `Builder`
exposes no reader, do X instead" and named a method that exists nowhere; `Builder` was checked and
exposes none, so the step now states the one route and adds the package-private reader it needs.
`builder()` (`:256`) and `build(Path, Key)` (`:541`) were verified against this baseline rather than
assumed.

**Type consistency.** `reportUnsupportedChunkVersion(String)` and `unsupportedChunkVersions()` are
named identically in Task 1's Interfaces block, its code, and Task 3's test. `UNKNOWN_DATA_VERSION`
is `"<none>"` throughout and distinct from the existing `UNKNOWN_STATUS` (`"<unknown>"`).
`UNSUPPORTED_CHUNK_VERSION` — not `UNSUPPORTED_DATA_VERSION` — in all five places it appears.
`DEFAULT_MINIMUM_DATA_VERSION` is 2860 in Task 2's test, its constant, and the spec.

**One risk this plan cannot remove.** 2860 comes from the research, not from a source read
first-hand. If it is wrong, Task 2's first test locks in the wrong number — but the layout check of
Task 3 rejects pre-1.18 worlds regardless, so the guard still works and only the message misleads.
Whoever implements Task 2 should confirm 2860 against minecraft.wiki's chunk-format history and
correct the constant, its Javadoc and the test together if it differs.

---

## Result

Acceptance run against `a74cd042` (branch tip), worktree
`/mnt/projects/oss/onelitefeather/Falco-worktrees/anvil-version-guard`, base `2d3955d8`.

### Cases added

Task 2's implementer corrected `DEFAULT_MINIMUM_DATA_VERSION` to **2844**, not the plan's researched
2860 — the "one risk this plan cannot remove" above was exercised and resolved during implementation.

13 test cases were added to `falco-anvil` (not the 8 estimated in the task-4 brief; Task 3's own
review follow-up added 4 more edge-case witnesses beyond its original commit, and Task 1 added 3):

- `AnvilDiagnosticsTest`: `testAnUnsupportedVersionIsCountedUnderItsOwnValue`,
  `testAChunkWithoutAStoredVersionIsCountedApart`, `testTheVersionBreakdownIsSortedByValue`
- `FalcoAnvilLoaderBuilderTest`: `testTheMinimumDataVersionDefaultsToTheFirstRootLayout`,
  `testANegativeMinimumDataVersionIsRefused`, `testTheMinimumDataVersionSurvivesEveryOtherSetter`
- `FalcoAnvilLoaderIntegrationTest`: `testAPreRootLayoutChunkIsRefusedInsteadOfReadAsAir`,
  `testAChunkBelowTheFloorIsRefused`, `testAChunkWithoutAStoredVersionStillLoads`,
  `testAChunkWithNeitherSectionsNorLevelIsNotRefused`, `testAChunkWithBothSectionsAndLevelIsNotRefused`,
  `testAChunkWithADataVersionStoredAsTheWrongTypeIsRefused`, `testAChunkWithANegativeDataVersionIsRefused`

### Gate attack

Re-injected Task 3 Gegenprobe #1 — dropped the `Level` half of `legacyChunkLayout`:

```java
boolean legacyChunkLayout = data.get(SECTIONS_KEY) == null;
```

Ran the **full** `:falco-anvil:test` module (230 cases, not a single filtered class). Result: `230
tests completed, 2 failed` —

- `FalcoAnvilLoaderIntegrationTest > testAChunkWithNeitherSectionsNorLevelIsNotRefused`
- `FalcoAnvilLoaderIntegrationTest > testAPartiallyGeneratedChunkIsCountedUnderItsStatus`

The second is a **pre-existing** test that predates this whole plan — it never anticipated the guard,
yet the mutated condition broke it too, which is stronger evidence than the dedicated regression test
alone. The task-4-brief's predicted witness, `testAChunkWithoutAStoredVersionStillLoads`, stayed green
under this mutation, exactly as Task 3's own report already found: that fixture carries a present
(if empty) `sections` list, so the dropped condition half never gets a chance to fire for it.

Reverted the mutation; `git status --short` was empty afterward; `:falco-anvil:test --rerun-tasks` was
green again (230/230).

### Module counts (from JUnit XML under `build/test-results/test/`, counted via `<testcase>` elements
— not the console summary)

| Module | Count at `2d3955d8` (measured) | Count now | Delta |
| --- | --- | --- | --- |
| falco-anvil | 217 | 230 | +13 |
| falco-light | 223 | 223 | 0 |
| falco-instance | 259 | 259 | 0 |
| falco-demo | 167 | 167 | 0 |
| falco-benchmarks | 42 (1 skipped) | 42 (1 skipped) | 0 |
| falco-archunit | 47 | 47 | 0 |

No count fell. **Discrepancy against the task-4 brief's stated baseline**: the brief quoted light 205,
instance 186, demo 166, archunit 46. `git diff 2d3955d8..HEAD --stat` shows this branch touches only
`falco-anvil` files — so light/instance/demo/archunit at `2d3955d8` are, by construction, identical to
what this run measured (223/259/167/47). The brief's numbers predate the `feat(instance): a shared
instance that repairs what it inherits (#40)` merge that landed on `main` before `2d3955d8`, which is
almost certainly where the instance-module gap (186 → 259) comes from. This is a stale reference in
the brief, not a regression — flagged here rather than silently reconciled.

### Machine load

`uptime` before the module run (09:33:57): `load average: 0.72, 2.99, 4.34`
`uptime` after the module run (09:36:08): `load average: 4.63, 4.42, 4.73`

No timing figure was produced or is quoted anywhere in this section.

### `./gradlew build -x test --rerun-tasks`

`BUILD SUCCESSFUL`. `javadoc` genuinely executed (no `UP-TO-DATE`/`FROM-CACHE`, zero warnings in the
full output) for all four modules that carry a javadoc task: falco-anvil, falco-light, falco-instance,
falco-demo. `checkApiCompatibility` genuinely executed for the three modules configured for it —
falco-anvil, falco-light, falco-instance (`build.gradle.kts:122`, `publishedModules - falco-bom`;
falco-demo is not in `publishedModules` and carries no japicmp task; falco-bom is a platform artifact
with no jar to compare). japicmp raised no complaint about the new `UNSUPPORTED_CHUNK_VERSION` enum
constant — adding an enum constant is additive under `onlyBinaryIncompatibleModified`, so no exception
handling in `gradle/api-breaks.properties` was needed and none was added.

### What this work does not do

- It converts nothing. A pre-1.18 world is refused, not rewritten into the post-1.18 layout.
- It does not touch the save path. Only the read seam in `loadChunk` gained the guard.
- It does not make a pre-1.18 world usable. The loader now fails loudly instead of silently returning
  air; the world itself remains unreadable by this loader.

### Known, checked, still true

- `decodeSections` still reads `sections` via `NbtReads.optionalList`. A chunk stamped
  `minecraft:full`, with neither `sections` nor `Level`, passes the guard (no legacy layout — `Level`
  is absent) and reaches the caller as an air chunk. Confirmed still open and intentionally outside
  this plan's scope.
- A `DataVersion` tag present but of the wrong NBT type is read as `-1` by
  `NbtReads.optionalInteger` (falls through its `instanceof NumberBinaryTag` check), and reported as
  `"-1"` — indistinguishable in the diagnostics breakdown from a genuine `DataVersion` of `-1`. Both
  are correctly rejected by the guard; only the breakdown conflates them. Confirmed still true.
- `FalcoAnvilLoader.Builder`'s `@version` javadoc tag is still `1.0.0` (`FalcoAnvilLoader.java:300`),
  unraised despite the outer class moving to `1.2.0`. Confirmed still true.
