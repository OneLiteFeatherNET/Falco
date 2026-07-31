# FalcoLightingChunk Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A chunk implementation that keeps its own light up to date, so that `instance.setChunkSupplier(FalcoLightingChunk::new)` is all a consumer needs.

**Architecture:** A block change marks its chunk dirty. Once per tick a scheduler groups the dirty chunks into connected areas, each capped in size, and submits them to an injectable executor. An area reads its chunks plus one surrounding ring, exchanges borders internally until stable, and writes back only the dirty chunks. Results reach clients through Minestom's existing `invalidate()` → `UpdateLightPacket` path.

**Tech Stack:** Java 25 (no preview features), Minestom `2026.06.20-26.1.2` (`compileOnly`), JUnit 6.1.0, Cyano `0.6.2` (`MicrotusExtension`) for anything needing a server, JMH 1.37 for the acceptance measurement.

**Spec:** [`docs/superpowers/specs/2026-07-31-falco-lighting-chunk-design.md`](../specs/2026-07-31-falco-lighting-chunk-design.md)

## Global Constraints

- **Javadoc on every class and method**, with `@param` / `@return` / `@throws`. Class comments explain *why*, not *what*, and carry `@author` / `@version` / `@since`. `withJavadocJar()` is active — an incomplete comment fails the build.
- **Never write `@NotNull`.** Packages carry `@NotNullByDefault`; only `@Nullable`, `@Contract` and `@UnmodifiableView` appear explicitly.
- New public types carry `@ApiStatus.Experimental`, matching the rest of `instance.light`.
- Tests are package-private, named `test<What><Expectation>`, use plain JUnit assertions, and avoid `@Nested`. Anything needing a server uses `@ExtendWith(MicrotusExtension.class)`.
- **Test-first, strictly.** Write the failing test, confirm it fails for the right reason, then implement.
- Commits follow conventional commits, scope `(light)`, last line `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`.
- **Never run two Gradle builds in this checkout at once** — they corrupt `build/test-results` and produce failures that look like real test breakage. If it happens: `rm -rf build`.
- `./gradlew clean build` must be green before the final commit of every task.

## File Structure

| File | Responsibility |
| --- | --- |
| `instance/light/ChunkLightService.java` (modify) | Gains two public methods so an area can read opacity and write light without duplicating that code |
| `instance/light/ChunkArea.java` (create) | A chunk coordinate pair and the grouping of a dirty set into capped, connected areas. Pure arithmetic, no Minestom |
| `instance/light/ChunkLightArea.java` (create) | Computes one area: read chunks plus ring, exchange borders, write back the dirty ones |
| `instance/light/ChunkLightScheduler.java` (create) | Owns the dirty set, triggers once per tick, submits areas to the executor, handles back-pressure |
| `instance/light/FalcoLightingChunk.java` (create) | The drop-in `DynamicChunk` subclass. No computation logic |

---

### Task 1: Open the two service methods an area needs

`ChunkLightArea` must read a chunk's opacity tables and write light back. Both already exist inside `ChunkLightService` but are private (`opacityOf` at line 122, `apply` at line 139). Duplicating them would mean two places to fix a bug in.

**Files:**
- Modify: `falco-light/src/main/java/net/onelitefeather/falco/light/ChunkLightService.java`
- Test: `falco-light/src/test/java/net/onelitefeather/falco/light/ChunkLightServiceIntegrationTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `public List<SectionOpacity> opacityOf(Chunk chunk)` and `public void applyLight(Chunk chunk, List<LightNibbles> light, boolean sky)`

- [ ] **Step 1: Write the failing test**

Append to `ChunkLightServiceIntegrationTest`:

```java
@Test
void testOpacityOfExposesOneEntryPerSection(Env env) {
    Instance instance = env.createEmptyInstance();
    Chunk chunk = instance.loadChunk(0, 0).join();
    place(chunk, 8, 40, 8, Block.STONE);

    List<SectionOpacity> opacity = this.service.opacityOf(chunk);

    assertEquals(chunk.getSections().size(), opacity.size());
}

@Test
void testApplyLightWritesIntoTheSections(Env env) {
    Instance instance = env.createEmptyInstance();
    Chunk chunk = instance.loadChunk(0, 0).join();
    int sectionCount = chunk.getSections().size();
    List<LightNibbles> light = new ArrayList<>(sectionCount);

    for (int index = 0; index < sectionCount; index++) {
        light.add(LightNibbles.uniform(7));
    }

    this.service.applyLight(chunk, light, false);

    assertEquals(7, this.service.blockLightAt(chunk, 1, chunk.getMinSection() * 16 + 1, 1));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*ChunkLightServiceIntegrationTest*"`
Expected: FAIL — compile error, `opacityOf(Chunk)` and `applyLight(...)` have private/no access.

- [ ] **Step 3: Make both methods public**

In `ChunkLightService`, change `private List<SectionOpacity> opacityOf(Chunk chunk)` to public and rename `private static void apply(...)` to `public static void applyLight(...)`, updating its three call sites inside the class (`calculate`, `calculateSky`, `calculateWithNeighbours`). Extend both Javadoc blocks to state that they are the seam a scheduler builds on, and that `applyLight` clears the section's update flag so the server will not recompute the result.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "*Light*"`
Expected: PASS, no other light test affected.

- [ ] **Step 5: Commit**

```bash
git add falco-light/src/main/java/net/onelitefeather/falco/light/ChunkLightService.java \
        falco-light/src/test/java/net/onelitefeather/falco/light/ChunkLightServiceIntegrationTest.java
git commit -m "refactor(light): expose the opacity and apply steps of the service"
```

---

### Task 2: Group dirty chunks into capped, connected areas

Pure coordinate arithmetic — no Minestom types, no server, no threads. This is where the size cap from the spec lives.

**Files:**
- Create: `falco-light/src/main/java/net/onelitefeather/falco/light/ChunkArea.java`
- Test: `falco-light/src/test/java/net/onelitefeather/falco/light/ChunkAreaTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `record ChunkArea(int x, int z)` and `static List<List<ChunkArea>> group(Collection<ChunkArea> dirty, int maxSize)`

- [ ] **Step 1: Write the failing test**

```java
class ChunkAreaTest {

    @Test
    void testTouchingChunksFormOneArea() {
        List<List<ChunkArea>> areas = ChunkArea.group(
                List.of(new ChunkArea(0, 0), new ChunkArea(1, 0), new ChunkArea(1, 1)), 16);

        assertEquals(1, areas.size());
        assertEquals(3, areas.getFirst().size());
    }

    @Test
    void testSeparateChunksFormSeparateAreas() {
        List<List<ChunkArea>> areas = ChunkArea.group(
                List.of(new ChunkArea(0, 0), new ChunkArea(10, 10)), 16);

        assertEquals(2, areas.size());
    }

    @Test
    void testDiagonalNeighboursDoNotJoin() {
        // Light crosses a face, not a corner. Two chunks meeting only at a corner do not
        // exchange a border and therefore do not have to be computed together.
        List<List<ChunkArea>> areas = ChunkArea.group(
                List.of(new ChunkArea(0, 0), new ChunkArea(1, 1)), 16);

        assertEquals(2, areas.size());
    }

    @Test
    void testAnAreaIsSplitAtTheCap() {
        List<ChunkArea> row = new ArrayList<>();

        for (int x = 0; x < 10; x++) {
            row.add(new ChunkArea(x, 0));
        }
        List<List<ChunkArea>> areas = ChunkArea.group(row, 4);

        assertEquals(3, areas.size());
        assertEquals(10, areas.stream().mapToInt(List::size).sum());
    }

    @Test
    void testNoChunkIsLostOrDuplicated() {
        List<ChunkArea> dirty = new ArrayList<>();

        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                dirty.add(new ChunkArea(x, z));
            }
        }
        List<ChunkArea> flattened = ChunkArea.group(dirty, 5).stream().flatMap(List::stream).toList();

        assertEquals(dirty.size(), flattened.size());
        assertEquals(Set.copyOf(dirty), Set.copyOf(flattened));
    }

    @Test
    void testAnEmptyInputProducesNoAreas() {
        assertTrue(ChunkArea.group(List.of(), 16).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*ChunkAreaTest*"`
Expected: FAIL — `ChunkArea` does not exist.

- [ ] **Step 3: Write the implementation**

Create `ChunkArea` as a record with `x` and `z`. Implement `group` as a breadth-first flood fill over the dirty set: take any unvisited chunk, walk its four face neighbours that are also dirty and unvisited, and stop adding to the current area once it holds `maxSize` chunks — the remaining connected chunks then start the next area. Return one list per area.

The class comment must explain *why* diagonals do not join (light crosses faces, not corners) and *why* the cap exists (a `ChunkLightState` is roughly 980 KB, so an unbounded area over a large build would allocate hundreds of megabytes).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*ChunkAreaTest*"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add falco-light/src/main/java/net/onelitefeather/falco/light/ChunkArea.java \
        falco-light/src/test/java/net/onelitefeather/falco/light/ChunkAreaTest.java
git commit -m "feat(light): group dirty chunks into capped connected areas"
```

---

### Task 3: Compute one area

**Files:**
- Create: `falco-light/src/main/java/net/onelitefeather/falco/light/ChunkLightArea.java`
- Test: `falco-light/src/test/java/net/onelitefeather/falco/light/ChunkLightAreaTest.java`

**Interfaces:**
- Consumes: `ChunkArea` (Task 2), `ChunkLightService#opacityOf`, `ChunkLightService#applyLight` (Task 1)
- Produces: `ChunkLightArea(ChunkLightService service)` and `void compute(Instance instance, List<ChunkArea> area, boolean sky)`

- [ ] **Step 1: Write the failing test**

```java
@ExtendWith(MicrotusExtension.class)
class ChunkLightAreaTest {

    @Test
    void testLightCrossesTheBorderBetweenTwoChunksOfOneArea(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk left = instance.loadChunk(0, 0).join();
        instance.loadChunk(1, 0).join();
        place(left, 15, 40, 8, Block.GLOWSTONE);

        ChunkLightService service = new ChunkLightService();
        new ChunkLightArea(service).compute(instance, List.of(new ChunkArea(0, 0), new ChunkArea(1, 0)), false);

        Chunk right = instance.getChunk(1, 0);
        assertEquals(15, service.blockLightAt(left, 15, 40, 8));
        assertEquals(14, service.blockLightAt(right, 0, 40, 8), "light has to reach across the border");
    }

    @Test
    void testChunksOutsideTheAreaKeepTheirLight(Env env) {
        // The ring is read so the area's edge is correct, but it must never be written —
        // a ring chunk is missing the light from beyond it and would end up too dark.
        Instance instance = env.createEmptyInstance();
        Chunk inside = instance.loadChunk(0, 0).join();
        Chunk ring = instance.loadChunk(1, 0).join();
        place(ring, 8, 40, 8, Block.GLOWSTONE);

        ChunkLightService service = new ChunkLightService();
        service.calculate(ring);
        int before = service.blockLightAt(ring, 8, 40, 8);

        new ChunkLightArea(service).compute(instance, List.of(new ChunkArea(0, 0)), false);

        assertEquals(before, service.blockLightAt(ring, 8, 40, 8), "a ring chunk must not be rewritten");
        assertEquals(13, service.blockLightAt(inside, 15, 40, 8), "the area saw the ring's light");
    }

    @Test
    void testAnUnloadedChunkInTheAreaIsSkipped(Env env) {
        Instance instance = env.createEmptyInstance();
        instance.loadChunk(0, 0).join();

        ChunkLightService service = new ChunkLightService();
        new ChunkLightArea(service).compute(instance, List.of(new ChunkArea(0, 0), new ChunkArea(50, 50)), false);
        // no exception, the loaded chunk was still computed
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*ChunkLightAreaTest*"`
Expected: FAIL — `ChunkLightArea` does not exist.

- [ ] **Step 3: Write the implementation**

`compute` does four things in order:

1. Collect the area's chunks plus every face neighbour of them that is loaded and not itself in the area — that is the ring. Skip coordinates `instance.getChunk` returns `null` for.
2. Build `opacityOf` once per collected chunk, and a `ChunkLightState` per chunk (`ChunkLightState.blockLight` or `skyLight` depending on `sky`).
3. Repeat over all collected chunks: for each pair of face-adjacent chunks, `injectBorder` the neighbour's `border` of the opposite face. Stop when a full pass changes nothing, or after 16 rounds — a level drops by at least one per chunk border, so 16 rounds cover every reachable level; log a warning if the cap is hit, mirroring `ChunkLightService#exchangeUntilSettled`.
4. Write back with `applyLight` **only** for chunks in `area`, never for ring chunks.

The class comment must state why the ring is read but not written.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*ChunkLightAreaTest*"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add falco-light/src/main/java/net/onelitefeather/falco/light/ChunkLightArea.java \
        falco-light/src/test/java/net/onelitefeather/falco/light/ChunkLightAreaTest.java
git commit -m "feat(light): compute one connected area with a read-only ring"
```

---

### Task 4: The scheduler

**Files:**
- Create: `falco-light/src/main/java/net/onelitefeather/falco/light/ChunkLightScheduler.java`
- Test: `falco-light/src/test/java/net/onelitefeather/falco/light/ChunkLightSchedulerTest.java`

**Interfaces:**
- Consumes: `ChunkArea#group` (Task 2), `ChunkLightArea#compute` (Task 3)
- Produces: `ChunkLightScheduler(ChunkLightService service, Executor executor, int maxAreaSize)`, `ChunkLightScheduler(ChunkLightService service)`, `void markDirty(Instance instance, int chunkX, int chunkZ, long revision)`, `void onTick(Instance instance, long time)`

- [ ] **Step 1: Write the failing test**

```java
@ExtendWith(MicrotusExtension.class)
class ChunkLightSchedulerTest {

    /** Runs every task on the calling thread, so a tick is finished when onTick returns. */
    private static final Executor DIRECT = Runnable::run;

    @Test
    void testATickComputesEveryDirtyChunkOnce(Env env) {
        Instance instance = env.createEmptyInstance();
        Chunk chunk = instance.loadChunk(0, 0).join();
        place(chunk, 8, 40, 8, Block.GLOWSTONE);

        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, DIRECT, 16);
        scheduler.markDirty(instance, 0, 0, 1L);
        scheduler.onTick(instance, 1L);

        assertEquals(15, service.blockLightAt(chunk, 8, 40, 8));
    }

    @Test
    void testASecondTickWithoutChangesComputesNothing(Env env) {
        Instance instance = env.createEmptyInstance();
        instance.loadChunk(0, 0).join();

        ChunkLightService service = new ChunkLightService();
        AtomicInteger runs = new AtomicInteger();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, task -> {
            runs.incrementAndGet();
            task.run();
        }, 16);

        scheduler.markDirty(instance, 0, 0, 1L);
        scheduler.onTick(instance, 1L);
        scheduler.onTick(instance, 2L);

        assertEquals(1, runs.get(), "a clean tick must not submit work");
    }

    @Test
    void testTheSameTimestampTriggersOnlyOnePass(Env env) {
        Instance instance = env.createEmptyInstance();
        instance.loadChunk(0, 0).join();

        ChunkLightService service = new ChunkLightService();
        AtomicInteger runs = new AtomicInteger();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, task -> {
            runs.incrementAndGet();
            task.run();
        }, 16);

        scheduler.markDirty(instance, 0, 0, 1L);
        // every chunk of the instance reports the same tick timestamp
        scheduler.onTick(instance, 5L);
        scheduler.onTick(instance, 5L);
        scheduler.onTick(instance, 5L);

        assertEquals(1, runs.get());
    }

    @Test
    void testAChunkChangedDuringComputationStaysDirty(Env env) {
        Instance instance = env.createEmptyInstance();
        instance.loadChunk(0, 0).join();

        ChunkLightService service = new ChunkLightService();
        AtomicInteger runs = new AtomicInteger();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, task -> {
            runs.incrementAndGet();
            task.run();
        }, 16);

        scheduler.markDirty(instance, 0, 0, 1L);
        scheduler.onTick(instance, 1L);
        // a newer revision than the one the pass recorded
        scheduler.markDirty(instance, 0, 0, 2L);
        scheduler.onTick(instance, 2L);

        assertEquals(2, runs.get(), "the changed chunk has to be computed again");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*ChunkLightSchedulerTest*"`
Expected: FAIL — `ChunkLightScheduler` does not exist.

- [ ] **Step 3: Write the implementation**

State: a `ConcurrentHashMap<ChunkArea, Long>` mapping dirty chunks to the revision they were last marked at, a `Set<ChunkArea>` of chunks currently in flight, and a `volatile long lastTick`.

- `markDirty` records the revision (keeping the highest).
- `onTick` returns immediately if `time == lastTick`; otherwise it sets `lastTick`, takes a snapshot of the dirty entries that are **not** in flight, groups them with `ChunkArea.group(…, maxAreaSize)`, marks them in flight, and submits one task per area.
- Each task computes block light, then sky light if `instance.getCachedDimensionType()` reports skylight, then removes the chunk from the dirty map **only if** its recorded revision is unchanged. Finally it clears the in-flight marks and calls `chunk.invalidate()` for every written chunk so Minestom sends an `UpdateLightPacket`.
- The whole task body is wrapped in `try/catch (Throwable)`: report to `MinecraftServer.getExceptionManager()`, leave the chunks dirty, and always clear the in-flight marks in a `finally` block — otherwise one failure would freeze those chunks forever.

The single-argument constructor builds the default executor: virtual threads bounded by `Runtime.getRuntime().availableProcessors()`, matching how `FalcoAnvilLoader` bounds `saveChunks`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*ChunkLightSchedulerTest*"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add falco-light/src/main/java/net/onelitefeather/falco/light/ChunkLightScheduler.java \
        falco-light/src/test/java/net/onelitefeather/falco/light/ChunkLightSchedulerTest.java
git commit -m "feat(light): schedule area computations once per tick"
```

---

### Task 5: The drop-in chunk

**Files:**
- Create: `falco-light/src/main/java/net/onelitefeather/falco/light/FalcoLightingChunk.java`
- Modify: `falco-light/src/main/java/net/onelitefeather/falco/light/ChunkLightScheduler.java` — add `supplier()`, which could not exist in Task 4 because the chunk type it returns did not exist yet
- Test: `falco-light/src/test/java/net/onelitefeather/falco/light/FalcoLightingChunkTest.java`

**Interfaces:**
- Consumes: `ChunkLightScheduler` (Task 4)
- Produces: `FalcoLightingChunk(ChunkLightScheduler scheduler, Instance instance, int chunkX, int chunkZ)` and `ChunkSupplier ChunkLightScheduler#supplier()`. The scheduler comes first because `ChunkSupplier` fixes the trailing three parameters.

- [ ] **Step 1: Write the failing test**

```java
@ExtendWith(MicrotusExtension.class)
class FalcoLightingChunkTest {

    @Test
    void testPlacingALightSourceLightsTheChunkAfterATick(Env env) {
        Instance instance = env.createEmptyInstance();
        ChunkLightService service = new ChunkLightService();
        ChunkLightScheduler scheduler = new ChunkLightScheduler(service, Runnable::run, 16);
        instance.setChunkSupplier(scheduler.supplier());

        Chunk chunk = instance.loadChunk(0, 0).join();
        chunk.lockWriteLock();
        try {
            chunk.setBlock(8, 40, 8, Block.GLOWSTONE);
        } finally {
            chunk.unlockWriteLock();
        }
        chunk.tick(1L);

        assertEquals(15, service.blockLightAt(chunk, 8, 40, 8));
    }

    @Test
    void testTheSupplierProducesFalcoLightingChunks(Env env) {
        Instance instance = env.createEmptyInstance();
        instance.setChunkSupplier(new ChunkLightScheduler(new ChunkLightService(), Runnable::run, 16).supplier());

        assertInstanceOf(FalcoLightingChunk.class, instance.loadChunk(0, 0).join());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*FalcoLightingChunkTest*"`
Expected: FAIL — `FalcoLightingChunk` does not exist.

- [ ] **Step 3: Write the implementation**

`FalcoLightingChunk extends DynamicChunk`, holding a reference to its scheduler and an `AtomicLong revision`.

- `setBlock(int, int, int, Block, BlockHandler, ...)` — call `super`, increment `revision`, then `scheduler.markDirty(instance, chunkX, chunkZ, revision.get())`.
- `onLoad()` — call `super`, then mark dirty, so a freshly loaded chunk gets light.
- `tick(long time)` — call `super`, then `scheduler.onTick(instance, time)`.

`ChunkLightScheduler#supplier()` returns `(instance, x, z) -> new FalcoLightingChunk(this, instance, x, z)`.

Do **not** override `createLightData`. `DynamicChunk` already reads the sections we write through `applyLight`, so the default implementation returns the current state and never blocks — which is exactly the behaviour the spec asks for. The class comment must record this, so nobody adds an override later thinking it is missing.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*FalcoLightingChunk*"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Run the whole build and commit**

```bash
./gradlew clean build
git add falco-light/src/main/java/net/onelitefeather/falco/light/FalcoLightingChunk.java \
        falco-light/src/main/java/net/onelitefeather/falco/light/ChunkLightScheduler.java \
        falco-light/src/test/java/net/onelitefeather/falco/light/FalcoLightingChunkTest.java
git commit -m "feat(light): add a chunk that keeps its own light up to date"
```

---

### Task 6: The acceptance measurement

The spec makes area forming conditional on one number. This task produces it.

**Files:**
- Create: `falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/light/AreaVsPerChunkBenchmark.java`
- Modify: `docs/light-engine.md`, `docs/benchmarks.md`

**Interfaces:**
- Consumes: `ChunkLightArea#compute` (Task 3)
- Produces: nothing further

- [ ] **Step 1: Write the benchmark**

Two methods over `@Param({"1", "4", "9", "16"}) int chunkCount`, on the same generated chunks:
`area` calls `ChunkLightArea#compute` once for the whole square; `perChunk` calls `ChunkLightService#calculateWithNeighbours` once per chunk. `@Warmup(iterations = 3, time = 1)`, `@Measurement(iterations = 5, time = 1)`, `@Fork(1)`.

- [ ] **Step 2: Build and run it**

```bash
./gradlew jmhJar
rm -f /tmp/jmh.lock
java -jar build/libs/falco-*-jmh.jar AreaVsPerChunkBenchmark -f 1 -wi 3 -i 5
```

- [ ] **Step 3: Decide against the criterion**

The spec says: an area of *n* chunks must be measurably cheaper than *n* separate `calculateWithNeighbours` calls.

- **Holds:** record the numbers in `docs/light-engine.md` with their spread, and note the benchmark in `docs/benchmarks.md`.
- **Does not hold:** stop. Do not tune the benchmark until it agrees. Report the numbers and propose replacing area forming with the simpler per-chunk path — the spec says so explicitly, and a design that loses its justification should lose its complexity.

- [ ] **Step 4: Commit**

```bash
./gradlew clean build
git add falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/light/AreaVsPerChunkBenchmark.java docs/
git commit -m "test(jmh): measure area computation against per-chunk neighbour calls"
```

---

## Notes for the implementer

- `applyLight` clears the section's update flag, so the server will not recompute what was written. A wrong result is therefore never corrected on its own — this is why every task above is test-first.
- `ChunkLightService` holds no state between calls and may be shared by any number of threads. **Do not add a field to it**, and do not give the scheduler a cached propagator: a shared scratch buffer produced wrong light in about 99 % of concurrent calls before it was removed.
- `calculateWithNeighbours` currently writes all nine chunks and darkens the eight ring chunks. Task 3 deliberately does not repeat that. Once this plan is done, that method could be reduced to writing only its centre chunk — out of scope here, tracked in `STATUS.md`.
