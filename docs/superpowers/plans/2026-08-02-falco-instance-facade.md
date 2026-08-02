# Falco Instance Facade — Stage 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Take `FalcoInstance` apart along the responsibilities it already has, so that the steps of a chunk's life become reachable one at a time; give a chunk more than one lifecycle extension, so that Falco's light and Falco's instance stop being mutually exclusive; and remove the one thing the unload path still leaves behind.

**Architecture:** A facade over four parts. `FalcoInstance` keeps every method `Instance` demands and holds nothing but four references: `ChunkRegistry` (which chunk sits where, and which position is busy), `ChunkLifecycle` (create, generate, publish, unload, notify), `BlockWriter` (a block write and everything it wakes up) and `ChunkPersistence` (the loader and the four save paths). A fifth type, `ChunkGeneration`, is a collaborator of `ChunkLifecycle` rather than a part of the facade, because a chunk is generated exactly once and that once is inside its load. The extension point is `ChunkLifecycleListener`, installed on the chunk as a single nullable reference, composed with `ChunkLifecycleListener#of` when there is more than one, so that a chunk with no listener pays one field and no allocation.

**Tech Stack:** Java 25, Gradle, JUnit 5, Cyano (Minestom test extension), JMH + JOL for measurement, fastutil, `com.sun.management.ThreadMXBean` for per-thread allocation counting.

## Global Constraints

Copied verbatim from the spec (`docs/superpowers/specs/2026-08-01-falco-instance-chunk-design.md`, §7):

- **NFR-001** — The modules shall compile and run against the pinned Minestom version without reflection, `--add-opens` or an open module.
- **NFR-002** — The modules shall use only language and JDK features that are final in Java 25; no preview and no incubator feature shall be required to build or run.
- **NFR-003** — If a performance claim is published, then shall a JMH or JOL measurement in this repository support it, stated with its conditions.
- **NFR-004** — While a comparison benchmark runs, shall it fail rather than report a number if the two sides disagree on their result.
- **NFR-005** — When a chunk read fails, shall the failure reach the caller instead of being reported as an absent chunk.
- **NFR-006** — While a block is written, shall the lock held be the lock of the chunk it touches, not a monitor over the instance.
- **NFR-007** — The chunk shall allocate no object per block read on any path.
- **NFR-008** — The chunk shall not require `-XX:+UseCompactObjectHeaders`; where the flag helps, the gain shall be stated per class and measured, never as a percentage.
- **NFR-009** — Every new public type shall carry `@ApiStatus.Experimental` while the module is experimental.

Repository conventions, non-negotiable:

- **Source and Javadoc are English**, and Javadoc *justifies* decisions in `<p>` paragraphs and `<h2>` sections. Every type carries `@author TheMeinerLP`, `@version`, `@since`. **Changing an existing class raises its `@version`.** Model: `falco-light/src/main/java/net/onelitefeather/falco/light/ChunkLightService.java`.
- **Javadoc runs with `-Werror`** (`build.gradle.kts:35-37`). Every public and protected member of every new type needs a complete comment with `@param`, `@return` and `@throws`, or the build fails.
- **Gradle files stay comment-free.**
- **Minestom reference is the pinned sources jar**, unpacked at `/tmp/claude-1000/-mnt-projects-oss-onelitefeather-Falco/34edb948-9dfe-4540-9666-9e29f0d44d7b/scratchpad/minestom-src/`. The clone at `/mnt/projects/oss/minestom/Minestom` is ten months stale and must not be used.
- Work happens in the worktree `/mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage`, on branch `feat/block-storage`. Every Gradle command is prefixed with `cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage &&`, because the working directory of a shell call does not persist.
- **Measured, not asserted.** No figure without its conditions, no green test that claims instead of checking. Every new assertion in this plan comes with a named injected defect that has to make it red.

## The starting point

From the `## Stage 2 result` section of `docs/superpowers/plans/2026-08-02-falco-lazy-sections.md`, measured 2026-08-02, legacy object headers of twelve bytes, JOL through the instrumentation agent, JDK 25.0.3 (Temurin).

| # | What | Figure |
|---|---|---|
| T1 | A fresh `FalcoChunk` | **25 objects, 840 B**, against 192 / 6 848 for `DynamicChunk` |
| T2 | A filled chunk | 104 B saved, at every state count and every arrangement |
| T3 | What a chunk costs inside the instance it was built for | **161 B** for a `FalcoInstance`, 185 B for an `InstanceContainer` |
| T4 | Test counts, all green | `:falco-instance:` 143, `:falco-anvil:` 193, `:falco-light:` 189, `:falco-demo:` 139, `:falco-benchmarks:` 38 |
| T5 | `FalcoInstance.java` | **1 272 lines**, `@version 1.2.0` |
| T6 | Viewer cache growth, `InstanceContainer` | 1 entry per chunk **construction**, linear, never removed |
| T7 | Viewer cache growth, `FalcoInstance` | 1 entry per chunk **position**, never removed |

T3 is the figure this stage moves. T1 and T2 are the figures this stage must not move by accident: `ChunkFootprintTest` asserts a declared per-class difference table and the equality of the two chunks' shallow sizes, and Task 8 adds a field to `FalcoChunk`. That is not a reason to loosen the assertion; it is a reason to re-measure and re-declare it.

## What the net covers, and what it does not

A refactoring of working code is only as safe as the tests that ran before it. This is the inventory, taken by reading every test of `falco-instance` rather than by trusting the file names.

**What the net already holds.**

| Test | What it pins |
|---|---|
| `FalcoInstanceTest` (14) | registration, `loadChunk` twice giving one chunk, `getChunk` before a load, `setBlock` read back, auto chunk load on and off, the server ticking the instance, a foreign chunk supplier being refused, a player becoming a viewer, the void depth |
| `FalcoInstanceUnloadTest` (6) | `unloadChunk` clearing the flag and the map, the unload event firing once, `unregister` unloading everything, the unregister event still firing, unregistering twice, and the Minestom behaviour that makes the class necessary |
| `FalcoInstanceLoadRaceTest` (3) | unregister during a running load, an unregister racing every running load, and a thousand concurrent loads and unloads never leaving a chunk that cannot be unloaded |
| `FalcoInstanceGeneratorTest` (12) | the generator being handed back, a generated chunk carrying its blocks, the loader winning over the generator, a special block surviving generation, a throwing generator failing the load, a throwing generator leaving a loaded chunk alone, `generateChunk` on a loaded chunk, forks into loaded and unloaded chunks, the heightmap covering the whole chunk, an empty world without a generator |
| `FalcoChunkTest` (11) | the load and unload hooks, the copy, the heightmaps built on demand and built once, the tickable counter from five directions |
| `SectionMaterialisationTest`, `LazySectionBlockStorageTest`, `BlockStorageTest`, `PaletteCompactionTest` | everything below the chunk |

**What the net does not hold, verified by grep over `falco-instance/src/test` and `falco-demo/src`.**

- `placeBlock` — **no test anywhere.** Not one call site in any test of the repository.
- `breakBlock` — **no test anywhere.** Neither the event, nor the air case that resends the chunk, nor the particle packet, nor the exclusion of the breaking player.
- `updateNeighbours` and `placementState` — **no test anywhere.** No placement rule is installed by any test, so the entire branch under `doBlockUpdates` has never run.
- The recursion guard `currentlyChangingBlocks` and its clearing in `tick` — **no test.**
- `getLastBlockChangeTime` / `refreshLastBlockChangeTime` — **no test.**
- `saveInstance`, `saveChunkToStorage`, `saveChunksToStorage` and `runSave` — **no test on `FalcoInstance`.** `TimingChunkLoaderTest` in `falco-demo` exercises a loader, not the instance's four save entry points, and neither the parallel nor the failing branch of `runSave` has ever been executed.
- `setChunkLoader` / `getChunkLoader` — **no test.**

Every one of those is code Task 6 and Task 3 move to another class. **Task 1 writes that net before anything moves**, and it is the first task for exactly that reason: a refactoring justified by testability that begins by moving untested code is the same mistake in a better outfit.

## Five traps, verified against the sources before they were written down

**The facade will re-accumulate state unless something forbids it.** §4.3 of the spec says so in as many words, and §8 lists it as the open question of this stage. A delegation layer with three fields of its own is the class it replaced with extra indirection. Task 7 answers it with a test over `FalcoInstance.class.getDeclaredFields()`: every non-static declared field has to be `final` and of one of the four part types, and there have to be exactly four. That is reflection in a test, which the module rule does not cover and which this repository already does deliberately in `JolMeasurement` — with the same property, that it fails loudly rather than silently degrading.

**The viewer cache entry cannot be removed through public API.** `EntityTracker#viewable(List, int, int)` is the only public door and it is `computeIfAbsent`; there is no counterpart. The map is `EntityTrackerImpl.TargetEntry#viewers` (`EntityTrackerImpl.java:269`), the key is the package-private record `ChunkViewKey` (`:252`) and `EntityTrackerImpl` itself is `final class` with no modifier (`:31`). All three are reachable from a class **in the package `net.minestom.server.instance`**, which is how `ChunkViewerCacheLeakTest` reads the map today without reflection. Task 9 puts one such class into `falco-instance/src/main/java`. The price is a split package with Minestom, which is invisible on the classpath and fatal on the module path; Falco has no `module-info.java` and neither does any of its consumers today, and Task 9 states the restriction in the class comment rather than discovering it later.

**`ChunkView` is `private`, so the removal must not name it.** `EntityTrackerImpl.ChunkView` is declared `private final class` (`:289`), so a class in the same package may use the expression `entry.viewers` but may not write down the type of what `Map#remove` returns. `entry.viewers.keySet().remove(key)` returns a `boolean` and never mentions it. `entry.viewers.remove(key) != null` may or may not compile depending on how javac treats the inaccessible type argument, and is not worth finding out.

**A lifecycle event built before the listener check costs an allocation on every transition of every chunk.** With 4 096 chunks at twenty ticks a second, one 24-byte record per chunk per tick is 2 MB/s of garbage for a server that registered no listener at all. US-3.04 forbids it and Task 8 measures it with `com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes`, in both arms, with the listener arm publishing the event to a static field so that escape analysis cannot delete the allocation the measurement is looking for. A test that only measures the null arm would pass against an implementation that allocates nothing because the JIT removed it, which is the failure class this project has been hit by six times.

**`Heightmap`, `getSections()` and `getSection(int)` materialise.** Unchanged from stage 2 and repeated here because this stage writes new tests that walk chunks: to read what a chunk currently holds, use `BlockStorage#view(int)`, `#views()`, `#shared(int)` and `#materialisedSections()`. A test that reaches for `getSections()` to check something unrelated silently materialises twenty-four sections and invalidates every count around it.

## File Structure

| File | Responsibility |
|---|---|
| `falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkRegistry.java` | **Create.** Which chunk sits at which index, and which position is busy. Owns the two maps and every atomic transition of a position. |
| `.../instance/ChunkLifecycle.java` | **Create.** Create, generate, load, publish, unload, notify. Owns the chunk supplier and the listener. |
| `.../instance/ChunkGeneration.java` | **Create.** The generator, the pending forks and the commit. A collaborator of `ChunkLifecycle`, not a part of the facade. |
| `.../instance/BlockWriter.java` | **Create.** `setBlock`, `placeBlock`, `breakBlock`, the neighbour updates, the recursion guard and the last change time. |
| `.../instance/ChunkPersistence.java` | **Create.** The chunk loader and the four save paths. |
| `.../instance/ChunkLifecycleListener.java` | **Create.** The extension point: `onLoad`, `onPublish`, `onTick`, `onUnload`, `onBlockChange`, plus `of` for composition. |
| `.../instance/ChunkLifecycleEvent.java` | **Create.** The record the four transitions hand out, built only when a listener exists. |
| `.../instance/FalcoInstance.java` | **Rewrite.** Four fields, no logic. `@version 1.2.0` → `2.0.0`. |
| `.../instance/FalcoChunk.java` | **Modify.** One nullable listener reference, notified on load, publish, tick, unload and block change. `@version 3.4.1` → `3.5.0`. |
| `falco-instance/src/main/java/net/minestom/server/instance/ChunkViewerCache.java` | **Create.** The one door to the viewer cache Minestom does not open. Split package, on purpose, documented. |
| `falco-instance/src/test/java/.../instance/FalcoInstanceBlockWriteTest.java` | **Create.** The net for `placeBlock`, `breakBlock`, the neighbour updates and the recursion guard, written before any of it moves. |
| `falco-instance/src/test/java/.../instance/FalcoInstancePersistenceTest.java` | **Create.** The net for the four save paths, both branches of `runSave` and the loader swap. |
| `falco-instance/src/test/java/.../instance/ChunkRegistryTest.java` | **Create.** The transitions of a position, driven directly. |
| `falco-instance/src/test/java/.../instance/ChunkLifecycleTest.java` | **Create.** US-3.02: publish and complete a load without driving a full load. |
| `falco-instance/src/test/java/.../instance/ChunkGenerationTest.java` | **Create.** The fork map, driven directly. |
| `falco-instance/src/test/java/.../instance/BlockWriterTest.java` | **Create.** The writer, driven directly. |
| `falco-instance/src/test/java/.../instance/InstanceFacadeTest.java` | **Create.** The facade holds four final fields and nothing else. |
| `falco-instance/src/test/java/.../instance/ChunkLifecycleListenerTest.java` | **Create.** US-3.03: two listeners, every transition, both notified. |
| `falco-instance/src/test/java/.../instance/ChunkLifecycleAllocationTest.java` | **Create.** US-3.04: no listener, no allocation — counted, in both arms. |
| `falco-instance/src/test/java/net/minestom/server/instance/ChunkViewerCacheTest.java` | **Create.** US-3.01: a load/unload cycle leaves the cache where it found it. |
| `falco-light/src/main/java/.../light/FalcoLightingChunk.java` | **Rewrite.** `extends FalcoChunk`, holding the light packet cache and installing `ChunkLightListener`. `@version 1.0.0` → `2.0.0`. |
| `falco-light/src/main/java/.../light/ChunkLightListener.java` | **Create.** The four reports light needs, as a `ChunkLifecycleListener`. |
| `falco-light/build.gradle.kts` | **Modify.** `compileOnly(project(":falco-instance"))` and `testImplementation(project(":falco-instance"))`. |
| `falco-demo/src/main/java/.../demo/ServerStack.java` | **Modify.** The note that says the two cannot be combined stops being true. `@version 1.0.0` → `2.0.0`. |
| `falco-benchmarks/src/test/java/net/minestom/server/instance/ChunkViewerCacheLeakTest.java` | **Modify.** Gains the load/unload cycle. `@version 1.0.1` → `1.1.0`. |
| `falco-benchmarks/src/jmh/java/.../benchmark/instance/ChunkLookupBenchmark.java` | **Create.** What a chunk lookup costs and allocates, boxed against unboxed. |
| `settings.gradle.kts`, `falco-instance/build.gradle.kts` | **Modify.** `flare-fastutil` as a `compileOnly` dependency for the primitive chunk index. |

Already in the repository and **not to be reinvented**: `ChunkViewerCacheLeakTest` (the door into the viewer cache without reflection), `ChunkFootprintTest` (the per-class difference table and the shallow-size equality), `SectionMaterialisationTest` (how to count something instead of timing it), `FalcoChunkEquivalenceTest` in `falco-benchmarks` (the evidence for US-1.03). All of them must be re-run at the end of the stage.

---

### Task 1: The net, before anything moves

**Files:**
- Create: `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoInstanceBlockWriteTest.java`
- Create: `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoInstancePersistenceTest.java`

**Interfaces:**
- Consumes: `FalcoInstance` as stage 2 left it — `setBlock(int,int,int,Block,boolean)`, `placeBlock(BlockHandler.Placement,boolean)`, `breakBlock(Player,Point,BlockFace,boolean)`, `getLastBlockChangeTime()`, `refreshLastBlockChangeTime()`, `saveInstance()`, `saveChunkToStorage(Chunk)`, `saveChunksToStorage()`, `setChunkLoader(ChunkLoader)`, `getChunkLoader()`.
- Produces: nothing. This task adds no production code at all, and that is the point.

**Why this is the first task.** Tasks 3 and 6 move `placeBlock`, `breakBlock`, `updateNeighbours`, the recursion guard and all four save paths into new classes. Not one of those has a test today — see the inventory above, which was taken by grep and not by guessing. A move of untested code cannot be verified by running the suite, because the suite says nothing about it either way.

- [ ] **Step 1: Write the block write net**

Create `FalcoInstanceBlockWriteTest.java`. A placement rule is registered so the neighbour update branch runs; without one, `updateNeighbours` returns on its first `rule == null`.

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.block.rule.BlockPlacementRule;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what a block write through {@link FalcoInstance} does, before the code that does it moves.
 * <p>
 * Every case here covers a path that had no test at all when this class was written: the placement
 * entry point, the break entry point, the neighbour update that follows a write, the recursion guard
 * that keeps a handler from destroying its own block forever, and the change timestamp. The plan of
 * stage 3 moves all of them into {@code BlockWriter}, and a move can only be checked against
 * behaviour somebody wrote down first.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("A block write through a Falco instance")
class FalcoInstanceBlockWriteTest {

    /**
     * The height every case writes at, well inside the overworld and away from both limits.
     */
    private static final int Y = 64;

    /**
     * Creates a registered instance in the environment of the test.
     *
     * @param env the environment which provides the server process
     * @return the registered instance
     */
    private static FalcoInstance registered(Env env) {
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);
        return instance;
    }

    @Test
    @DisplayName("places a block through placeBlock and reports that it did")
    void testPlaceBlockWritesTheBlock(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();

        final boolean placed = instance.placeBlock(new BlockHandler.Placement(Block.STONE, Block.AIR,
                instance, new BlockVec(1, Y, 1)), true);

        assertTrue(placed, "a loaded chunk accepts a placement");
        assertEquals(Block.STONE, instance.getBlock(1, Y, 1));
    }

    @Test
    @DisplayName("refuses a placement into a chunk which is not loaded")
    void testPlaceBlockRefusesAnUnloadedChunk(Env env) {
        final FalcoInstance instance = registered(env);

        final boolean placed = instance.placeBlock(new BlockHandler.Placement(Block.STONE, Block.AIR,
                instance, new BlockVec(1, Y, 1)), true);

        assertFalse(placed, "there is no chunk at that position, so nothing can be placed");
    }

    @Test
    @DisplayName("breaks a block, replaces it with what the event decided and tells the viewers")
    void testBreakBlockReplacesTheBlock(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        instance.setBlock(1, Y, 1, Block.STONE);
        final var connection = env.createConnection();
        final var player = connection.connect(instance, new Vec(0, Y, 0));

        final boolean broken = instance.breakBlock(player, new BlockVec(1, Y, 1), BlockFace.TOP, true);

        assertTrue(broken, "a solid block in a loaded chunk can be broken");
        assertEquals(Block.AIR, instance.getBlock(1, Y, 1));
    }

    @Test
    @DisplayName("refuses to break air and does not pretend it broke something")
    void testBreakBlockRefusesAir(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        final var connection = env.createConnection();
        final var player = connection.connect(instance, new Vec(0, Y, 0));

        assertFalse(instance.breakBlock(player, new BlockVec(1, Y, 1), BlockFace.TOP, true),
                "there is no block there, so the client is resent the chunk instead");
    }

    @Test
    @DisplayName("lets a placement rule reshape the neighbour of a written block")
    void testANeighbourReshapesItself(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        final AtomicInteger updates = new AtomicInteger();
        MinecraftServer.getBlockManager().registerBlockPlacementRule(new BlockPlacementRule(Block.GLASS) {

            @Override
            public Block blockUpdate(UpdateState state) {
                updates.incrementAndGet();
                return Block.GLOWSTONE;
            }

            @Override
            public Block blockPlace(PlacementState state) {
                return state.block();
            }
        });
        instance.setBlock(2, Y, 1, Block.GLASS);

        instance.setBlock(1, Y, 1, Block.STONE, true);

        assertTrue(updates.get() > 0, "the neighbour of the written block has to be asked to reshape itself");
        assertEquals(Block.GLOWSTONE, instance.getBlock(2, Y, 1),
                "what the rule returned has to end up in the chunk");
    }

    @Test
    @DisplayName("does not run neighbour updates when the caller switched them off")
    void testNeighbourUpdatesCanBeSwitchedOff(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        final AtomicInteger updates = new AtomicInteger();
        MinecraftServer.getBlockManager().registerBlockPlacementRule(new BlockPlacementRule(Block.OAK_LEAVES) {

            @Override
            public Block blockUpdate(UpdateState state) {
                updates.incrementAndGet();
                return state.currentBlock();
            }

            @Override
            public Block blockPlace(PlacementState state) {
                return state.block();
            }
        });
        instance.setBlock(4, Y, 1, Block.OAK_LEAVES);

        instance.setBlock(3, Y, 1, Block.STONE, false);

        assertEquals(0, updates.get(), "doBlockUpdates=false has to skip the neighbour pass entirely");
    }

    @Test
    @DisplayName("stops a handler which writes its own block again from recursing")
    void testTheRecursionGuardHolds(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        final AtomicInteger writes = new AtomicInteger();
        final Block looping = Block.STONE.withHandler(new BlockHandler() {

            @Override
            public void onPlace(Placement placement) {
                writes.incrementAndGet();
                instance.setBlock(placement.getBlockPosition(), placement.getBlock());
            }

            @Override
            public net.kyori.adventure.key.Key getKey() {
                return net.kyori.adventure.key.Key.key("falco", "looping");
            }
        });

        instance.setBlock(5, Y, 5, looping);

        assertEquals(1, writes.get(),
                "the second write of the same block to the same position has to be dropped by the guard");
    }

    @Test
    @DisplayName("lets the same block be written again after the tick which cleared the guard")
    void testTheGuardIsClearedByATick(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        instance.setBlock(6, Y, 6, Block.STONE);

        instance.tick(System.currentTimeMillis());
        instance.setBlock(6, Y, 6, Block.DIRT);
        instance.tick(System.currentTimeMillis());
        instance.setBlock(6, Y, 6, Block.STONE);

        assertEquals(Block.STONE, instance.getBlock(6, Y, 6),
                "the guard is scoped to one tick, so the same block can be written again afterwards");
    }

    @Test
    @DisplayName("moves the last change time when a block is written")
    void testTheChangeTimeMoves(Env env) {
        final FalcoInstance instance = registered(env);
        instance.loadChunk(0, 0).join();
        final long before = instance.getLastBlockChangeTime();

        instance.setBlock(7, Y, 7, Block.STONE);

        assertNotEquals(before, instance.getLastBlockChangeTime(),
                "a block write has to move the timestamp the batches read");
    }

    @Test
    @DisplayName("loads the chunk a write lands in when auto chunk load is on")
    void testAWriteLoadsItsChunk(Env env) {
        final FalcoInstance instance = registered(env);

        instance.setBlock(600, Y, 600, Block.STONE);

        final Chunk chunk = instance.getChunkAt(600, 600);
        assertTrue(chunk != null && chunk.isLoaded(), "the write has to have brought its chunk into the world");
        assertEquals(Block.STONE, instance.getBlock(600, Y, 600));
    }
}
```

- [ ] **Step 2: Run it and watch it pass**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-instance:test --tests "*FalcoInstanceBlockWriteTest*"
```

Expected: **PASS, all ten.** This is a net, not a red-green cycle: the behaviour exists and is being written down. A failure here means the behaviour is not what this plan assumed, and the plan is wrong rather than the code — stop and re-read `FalcoInstance#writeBlock:413` before changing anything.

- [ ] **Step 3: Prove the net bites**

Comment out the line `if (Objects.equals(this.currentlyChangingBlocks.get(blockPosition), block)) return;` in `FalcoInstance:425`, run the test again, and see `testTheRecursionGuardHolds` fail with a `StackOverflowError` or a count above one. Restore the line. Then comment out `if (doBlockUpdates) updateNeighbours(blockPosition, updateDistance);` in `:443` and see `testANeighbourReshapesItself` fail. Restore it. A net that stays green while the thing it covers is deleted is not a net.

- [ ] **Step 4: Write the persistence net**

Create `FalcoInstancePersistenceTest.java`. Both branches of `runSave` have to run, and the failing branch of each has to reach the caller rather than a log.

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the four save entry points of {@link FalcoInstance} do, before the code that does it
 * moves into {@code ChunkPersistence}.
 * <p>
 * None of them had a test when this class was written, and the branch that matters most had never
 * been executed at all: a loader which saves in parallel takes a different path through
 * {@code runSave} than one which does not, and a failure on either path has to reach the future the
 * caller holds rather than the exception manager of the server.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The save paths of a Falco instance")
class FalcoInstancePersistenceTest {

    /**
     * A loader which counts what it was asked to save and can be told to throw.
     */
    private static final class CountingLoader implements ChunkLoader {

        /**
         * Whether this loader claims to support saving off the calling thread.
         */
        private final boolean parallel;

        /**
         * What every save call throws, null for a loader which succeeds.
         */
        private final RuntimeException failure;

        /**
         * How often an instance save reached this loader.
         */
        private final AtomicInteger instanceSaves = new AtomicInteger();

        /**
         * How often a chunk save reached this loader.
         */
        private final AtomicInteger chunkSaves = new AtomicInteger();

        /**
         * The thread the last save ran on.
         */
        private final AtomicReference<Thread> lastThread = new AtomicReference<>();

        /**
         * Creates a loader.
         *
         * @param parallel whether it claims parallel saving
         * @param failure  what every save throws, null for none
         */
        private CountingLoader(boolean parallel, RuntimeException failure) {
            this.parallel = parallel;
            this.failure = failure;
        }

        @Override
        public boolean supportsParallelSaving() {
            return this.parallel;
        }

        @Override
        public void saveInstance(Instance instance) {
            this.lastThread.set(Thread.currentThread());
            this.instanceSaves.incrementAndGet();
            if (this.failure != null) throw this.failure;
        }

        @Override
        public void saveChunk(Chunk chunk) {
            this.lastThread.set(Thread.currentThread());
            this.chunkSaves.incrementAndGet();
            if (this.failure != null) throw this.failure;
        }

        @Override
        public void saveChunks(Collection<Chunk> chunks) {
            this.lastThread.set(Thread.currentThread());
            this.chunkSaves.addAndGet(chunks.size());
            if (this.failure != null) throw this.failure;
        }
    }

    /**
     * Creates a registered instance with the given loader.
     *
     * @param env    the environment which provides the server process
     * @param loader the loader of the instance
     * @return the registered instance
     */
    private static FalcoInstance registered(Env env, ChunkLoader loader) {
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD, loader);
        env.process().instance().registerInstance(instance);
        return instance;
    }

    @Test
    @DisplayName("saves the instance on the calling thread when the loader is not parallel")
    void testSaveInstanceOnTheCallingThread(Env env) {
        final CountingLoader loader = new CountingLoader(false, null);
        final FalcoInstance instance = registered(env, loader);

        instance.saveInstance().join();

        assertEquals(1, loader.instanceSaves.get());
        assertSame(Thread.currentThread(), loader.lastThread.get(),
                "a loader without parallel support must not be moved off the calling thread");
    }

    @Test
    @DisplayName("saves the instance off the calling thread when the loader is parallel")
    void testSaveInstanceOnAVirtualThread(Env env) {
        final CountingLoader loader = new CountingLoader(true, null);
        final FalcoInstance instance = registered(env, loader);

        instance.saveInstance().join();

        assertEquals(1, loader.instanceSaves.get());
        assertTrue(loader.lastThread.get().isVirtual(),
                "a loader with parallel support has to be run on a virtual thread");
    }

    @Test
    @DisplayName("hands a failing save back to the caller instead of swallowing it")
    void testAFailingSaveReachesTheCaller(Env env) {
        final RuntimeException boom = new IllegalStateException("the disk is on fire");
        final FalcoInstance instance = registered(env, new CountingLoader(false, boom));

        final CompletionException thrown = assertThrows(CompletionException.class,
                () -> instance.saveInstance().join());

        assertSame(boom, thrown.getCause(), "the failure of the loader is the failure of the future");
    }

    @Test
    @DisplayName("hands a failing parallel save back to the caller as well")
    void testAFailingParallelSaveReachesTheCaller(Env env) {
        final RuntimeException boom = new IllegalStateException("the disk is still on fire");
        final FalcoInstance instance = registered(env, new CountingLoader(true, boom));

        final CompletionException thrown = assertThrows(CompletionException.class,
                () -> instance.saveInstance().join());

        assertSame(boom, thrown.getCause(), "moving the work to a virtual thread must not lose the failure");
    }

    @Test
    @DisplayName("saves one chunk and every chunk through the loader")
    void testChunkSaves(Env env) {
        final CountingLoader loader = new CountingLoader(false, null);
        final FalcoInstance instance = registered(env, loader);
        final Chunk chunk = instance.loadChunk(0, 0).join();
        instance.loadChunk(1, 0).join();

        instance.saveChunkToStorage(chunk).join();
        assertEquals(1, loader.chunkSaves.get());

        instance.saveChunksToStorage().join();
        assertEquals(3, loader.chunkSaves.get(), "the second call has to hand over both loaded chunks");
    }

    @Test
    @DisplayName("keeps the chunks it already has when the loader is swapped")
    void testSwappingTheLoader(Env env) {
        final CountingLoader first = new CountingLoader(false, null);
        final CountingLoader second = new CountingLoader(false, null);
        final FalcoInstance instance = registered(env, first);
        final Chunk chunk = instance.loadChunk(0, 0).join();

        instance.setChunkLoader(second);

        assertSame(second, instance.getChunkLoader());
        assertSame(chunk, instance.getChunk(0, 0), "swapping the loader must not touch loaded chunks");
        instance.saveChunkToStorage(chunk).join();
        assertEquals(0, first.chunkSaves.get(), "the old loader must not see the save");
        assertEquals(1, second.chunkSaves.get(), "the new loader has to");
    }
}
```

- [ ] **Step 5: Run it and watch it pass**

```bash
./gradlew :falco-instance:test --tests "*FalcoInstancePersistenceTest*"
```

Expected: **PASS, all six.**

- [ ] **Step 6: Prove this net bites too**

Change `runSave` in `FalcoInstance:781` so its `catch (Throwable throwable)` returns `CompletableFuture.completedFuture(null)` instead of a failed future, and see both failure cases go red. Restore it.

- [ ] **Step 7: Commit**

```bash
git add falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoInstanceBlockWriteTest.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoInstancePersistenceTest.java
git commit -m "test(instance): pin the block write and the save paths before they move"
```

---

### Task 2: `ChunkRegistry`

**Files:**
- Create: `falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkRegistry.java`
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/ChunkRegistryTest.java`

**Interfaces:**
- Consumes: nothing of Falco's. `CoordConversion#chunkIndex(int,int)` from Minestom.
- Produces:
  - `ChunkRegistry()`
  - `@Nullable Chunk chunk(long index)`
  - `@Nullable Chunk chunk(int chunkX, int chunkZ)`
  - `Collection<Chunk> chunks()`
  - `List<Chunk> snapshot()`
  - `List<Long> loadingPositions()`
  - `int size()`
  - `int loading()`
  - `boolean idle()`
  - `ChunkRegistry.LoadSlot acquire(long index, CompletableFuture<Chunk> own)`
  - `void release(long index, CompletableFuture<Chunk> own)`
  - `@Nullable CompletableFuture<Chunk> discard(long index)`
  - `boolean publish(long index, FalcoChunk chunk, CompletableFuture<Chunk> future, Consumer<FalcoChunk> insideLock)`
  - `boolean remove(long index, FalcoChunk chunk, Consumer<FalcoChunk> insideLock)`
  - `sealed interface LoadSlot` with `record Loaded(Chunk chunk)`, `record Running(CompletableFuture<Chunk> future)`, `record Claimed(CompletableFuture<Chunk> future)`

  Tasks 5, 9 and 11 depend on exactly these names.

**Reference:** `FalcoInstance#retrieveChunk:544` (the acquire), `#publishChunk:638` (the publish), `#unloadChunk:722` (the remove), `#discardRunningLoad:325` (the discard). The `compute` on `loadingChunks` is the lock of a position and its exact shape is load-bearing — `FalcoInstanceLoadRaceTest` exists because of it. Move it; do not rewrite it.

- [ ] **Step 1: Write the failing test**

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.Chunk;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives every transition of a chunk position directly, without a loader and without a load.
 * <p>
 * This is half of US-3.02. The transitions used to be three {@code private} methods of a class of
 * 1 272 lines and could only be reached by loading a chunk through a loader, which meant that a test
 * of the publish had to be a test of the whole load path and could never cover the case where a
 * publish is refused — that case needs an unload to interleave with a load, which is exactly what a
 * full load path makes impossible to arrange.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The registry of chunk positions")
class ChunkRegistryTest {

    /**
     * The position every case works on.
     */
    private static final long INDEX = CoordConversion.chunkIndex(0, 0);

    /**
     * Creates a registered instance to build chunks for.
     *
     * @param env the environment which provides the server process
     * @return the registered instance
     */
    private static FalcoInstance registered(Env env) {
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);
        return instance;
    }

    @Test
    @DisplayName("hands the first caller the slot and every later one the same future")
    void testTheFirstCallerOwnsTheSlot(Env env) {
        registered(env);
        final ChunkRegistry registry = new ChunkRegistry();
        final CompletableFuture<Chunk> first = new CompletableFuture<>();
        final CompletableFuture<Chunk> second = new CompletableFuture<>();

        assertInstanceOf(ChunkRegistry.LoadSlot.Claimed.class, registry.acquire(INDEX, first));
        final ChunkRegistry.LoadSlot slot = registry.acquire(INDEX, second);

        assertSame(first, assertInstanceOf(ChunkRegistry.LoadSlot.Running.class, slot).future(),
                "the second caller has to receive the future of the first, not one of its own");
    }

    @Test
    @DisplayName("hands back the published chunk instead of a slot")
    void testAPublishedChunkEndsTheLoad(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkRegistry registry = new ChunkRegistry();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        registry.acquire(INDEX, own);
        final AtomicInteger insideLock = new AtomicInteger();

        assertTrue(registry.publish(INDEX, chunk, own, published -> insideLock.incrementAndGet()));

        assertEquals(1, insideLock.get(), "the step handed in has to run exactly once, while the position is held");
        assertSame(chunk, registry.chunk(INDEX));
        assertEquals(0, registry.loading(), "a published chunk releases the slot of its position");
        assertSame(chunk, assertInstanceOf(ChunkRegistry.LoadSlot.Loaded.class,
                registry.acquire(INDEX, new CompletableFuture<>())).chunk());
    }

    @Test
    @DisplayName("refuses to publish a chunk whose load was claimed")
    void testAClaimedLoadCannotPublish(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkRegistry registry = new ChunkRegistry();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        registry.acquire(INDEX, own);
        final AtomicInteger insideLock = new AtomicInteger();

        assertSame(own, registry.discard(INDEX));
        assertFalse(registry.publish(INDEX, chunk, own, published -> insideLock.incrementAndGet()));

        assertEquals(0, insideLock.get(), "a refused publish must not run the step it was given");
        assertNull(registry.chunk(INDEX), "a refused publish leaves the position empty");
    }

    @Test
    @DisplayName("removes a chunk once and reports the second attempt as a no-op")
    void testRemovingTwice(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkRegistry registry = new ChunkRegistry();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        registry.acquire(INDEX, own);
        registry.publish(INDEX, chunk, own, published -> {
        });
        final AtomicInteger insideLock = new AtomicInteger();

        assertTrue(registry.remove(INDEX, chunk, removed -> insideLock.incrementAndGet()));
        assertFalse(registry.remove(INDEX, chunk, removed -> insideLock.incrementAndGet()));

        assertEquals(1, insideLock.get(), "the step handed in runs for the removal that happened and no other");
        assertTrue(registry.idle());
    }

    @Test
    @DisplayName("refuses to remove a chunk which is not the one at that position")
    void testRemovingAStrangerDoesNothing(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkRegistry registry = new ChunkRegistry();
        final FalcoChunk resident = new FalcoChunk(instance, 0, 0);
        final FalcoChunk stranger = new FalcoChunk(instance, 0, 0);
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        registry.acquire(INDEX, own);
        registry.publish(INDEX, resident, own, published -> {
        });

        assertFalse(registry.remove(INDEX, stranger, removed -> {
        }));
        assertSame(resident, registry.chunk(INDEX), "the chunk that is actually there has to survive");
    }

    @Test
    @DisplayName("hands out the loading positions so a shutdown can claim them")
    void testLoadingPositionsAreVisible(Env env) {
        registered(env);
        final ChunkRegistry registry = new ChunkRegistry();
        registry.acquire(CoordConversion.chunkIndex(1, 1), new CompletableFuture<>());
        registry.acquire(CoordConversion.chunkIndex(2, 2), new CompletableFuture<>());

        assertEquals(2, registry.loadingPositions().size());
        assertEquals(2, registry.loading());
        assertFalse(registry.idle());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :falco-instance:test --tests "*ChunkRegistryTest*"
```

Expected: compilation failure — `ChunkRegistry` does not exist.

- [ ] **Step 3: Write the registry**

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.Chunk;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The {@link ChunkRegistry} class knows which chunk sits at which position and which position is
 * busy, and it is the only place where either of those two answers changes.
 * <p>
 * It was carved out of {@code FalcoInstance}, where the two maps and the four transitions between
 * them were fields and {@code private} methods of a class of 1 272 lines. Nothing about the
 * transitions changed in the move, and that is deliberate: the shape of the
 * {@link ConcurrentHashMap#compute} calls below is what
 * {@code FalcoInstanceLoadRaceTest#testConcurrentLoadsAndUnloadsNeverLeaveAChunkWhichCannotBeUnloaded}
 * exists to protect, and a refactoring that improved them would be a rewrite of the one part of this
 * module that was hardest to get right.
 * </p>
 *
 * <h2>Why the map of running loads is the lock of a position</h2>
 * <p>
 * Every transition of a position — starting a load, publishing its result, unloading the chunk
 * again — happens inside a {@code compute} on the index of that position. That serialises them
 * without putting a monitor over the whole instance, which is what {@code InstanceContainer} does and
 * what NFR-006 forbids. It is worth far more than the future it holds: without it an unload and the
 * load it races can both believe they went first, and the chunk which loses ends up in the instance
 * with its loaded flag already cleared, where nothing will ever unload it again.
 * </p>
 * <p>
 * The steps a caller hands to {@link #publish} and {@link #remove} run <em>inside</em> that lock, and
 * that is the whole reason they are parameters rather than something the caller does afterwards.
 * Creating and deleting a tick partition has to be part of the same atomic step as entering and
 * leaving the chunk map; splitting them is what lets Minestom delete a partition that is created a
 * moment later, which leaves a chunk being ticked for the rest of the life of the server even though
 * nothing else knows about it any more. Everything that may call back into the instance — the events,
 * the packets, the loader, the listeners — stays outside and is the caller's business.
 * </p>
 * <p>
 * This type is experimental. The instance module is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public final class ChunkRegistry {

    /**
     * The loaded chunks, keyed by the chunk index of their position.
     * <p>
     * A plain concurrent hash map rather than the synchronised long map of the container: chunk
     * streaming is a lookup-dominated access pattern, and the copy-on-write map underneath the
     * container pays for every load and unload instead.
     * </p>
     */
    private final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();

    /**
     * The chunks which are being loaded right now, keyed by chunk index, and the lock of a position.
     * <p>
     * Holding the future rather than a flag is what makes two concurrent requests for the same chunk
     * share one load instead of racing into two chunk objects.
     * </p>
     */
    private final Map<Long, CompletableFuture<Chunk>> loadingChunks = new ConcurrentHashMap<>();

    /**
     * What a caller asking for a position is told.
     * <p>
     * A sealed hierarchy rather than a nullable future plus an out parameter, because the three
     * answers are genuinely different and the caller has to handle all three: the chunk is already
     * there, somebody else is loading it, or this caller now owns the load. The
     * {@code AtomicReference} the previous shape needed to smuggle the first case out of a
     * {@code compute} is what this replaces.
     * </p>
     */
    public sealed interface LoadSlot {

        /**
         * The position already carries a chunk and no load is needed.
         *
         * @param chunk the chunk at the position
         */
        record Loaded(Chunk chunk) implements LoadSlot {
        }

        /**
         * Somebody else is loading this position and the caller has to wait for their future.
         *
         * @param future the future of the running load
         */
        record Running(CompletableFuture<Chunk> future) implements LoadSlot {
        }

        /**
         * The caller now owns the load of this position and has to complete the future it handed in.
         *
         * @param future the future the caller handed in
         */
        record Claimed(CompletableFuture<Chunk> future) implements LoadSlot {
        }
    }

    /**
     * Returns the chunk at a position.
     *
     * @param index the chunk index of the position
     * @return the chunk, or null if the position carries none
     */
    public @Nullable Chunk chunk(long index) {
        return this.chunks.get(index);
    }

    /**
     * Returns the chunk at a position.
     *
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @return the chunk, or null if the position carries none
     */
    public @Nullable Chunk chunk(int chunkX, int chunkZ) {
        return this.chunks.get(CoordConversion.chunkIndex(chunkX, chunkZ));
    }

    /**
     * Returns a live, unmodifiable view of every chunk in this registry.
     *
     * @return the chunks of this registry
     */
    public @UnmodifiableView Collection<Chunk> chunks() {
        return Collections.unmodifiableCollection(this.chunks.values());
    }

    /**
     * Returns a snapshot of every chunk in this registry, safe to iterate while it changes.
     *
     * @return the chunks of this registry at the moment of the call
     */
    public List<Chunk> snapshot() {
        return List.copyOf(this.chunks.values());
    }

    /**
     * Returns a snapshot of every position which is being loaded right now.
     *
     * @return the positions with a running load at the moment of the call
     */
    public List<Long> loadingPositions() {
        return List.copyOf(this.loadingChunks.keySet());
    }

    /**
     * Returns how many chunks this registry holds.
     *
     * @return the amount of loaded chunks
     */
    public int size() {
        return this.chunks.size();
    }

    /**
     * Returns how many loads are running.
     *
     * @return the amount of running loads
     */
    public int loading() {
        return this.loadingChunks.size();
    }

    /**
     * Reports whether this registry holds neither a chunk nor a running load.
     *
     * @return true if nothing is left in this registry
     */
    public boolean idle() {
        return this.chunks.isEmpty() && this.loadingChunks.isEmpty();
    }

    /**
     * Decides who loads a position.
     * <p>
     * The chunk map is read a second time inside the decision. Without that second read a caller
     * which looked at the chunk map just before a load published, and reached this point just after
     * that load removed its entry, would start a second load for a position which already has a
     * chunk. The second chunk then replaces the first one in the map and the first one is orphaned:
     * still marked as loaded, still holding its tick partition and its viewers, and no longer
     * reachable.
     * </p>
     *
     * @param index the chunk index of the position
     * @param own   the future the caller offers to complete if it wins the slot
     * @return which of the three cases the caller is in
     */
    public LoadSlot acquire(long index, CompletableFuture<Chunk> own) {
        final AtomicReference<Chunk> published = new AtomicReference<>();
        final CompletableFuture<Chunk> slot = this.loadingChunks.compute(index, (key, running) -> {
            if (running != null) return running;
            final Chunk cached = this.chunks.get(index);
            if (cached != null) {
                published.set(cached);
                return null;
            }
            return own;
        });
        final Chunk cached = published.get();

        if (cached != null) return new LoadSlot.Loaded(cached);
        if (slot != own) return new LoadSlot.Running(slot);
        return new LoadSlot.Claimed(own);
    }

    /**
     * Gives up a slot without publishing anything, for a load which failed.
     *
     * @param index the chunk index of the position
     * @param own   the future of the load which is giving up
     */
    public void release(long index, CompletableFuture<Chunk> own) {
        this.loadingChunks.remove(index, own);
    }

    /**
     * Takes the slot of a running load so its chunk never reaches this registry.
     * <p>
     * Removing the entry is the whole claim: a load publishes only while its own future is still the
     * entry of the position, so a load which finds the slot empty or taken knows that somebody
     * decided its result is no longer wanted.
     * </p>
     *
     * @param index the chunk index of the position
     * @return the future of the claimed load, or null if there was none
     */
    public @Nullable CompletableFuture<Chunk> discard(long index) {
        final AtomicReference<CompletableFuture<Chunk>> claimed = new AtomicReference<>();

        this.loadingChunks.compute(index, (key, running) -> {
            claimed.set(running);
            return null;
        });
        return claimed.get();
    }

    /**
     * Makes a chunk the chunk of its position, unless somebody claimed the load.
     *
     * @param index      the chunk index of the position
     * @param chunk      the chunk to publish
     * @param future     the future of this load, which has to still be the entry of the position
     * @param insideLock the step to run while the position is held, once, only if the publish happens
     * @return true if the chunk is now the chunk of its position, false if the load was claimed
     */
    public boolean publish(long index, FalcoChunk chunk, CompletableFuture<Chunk> future,
                           Consumer<FalcoChunk> insideLock) {
        final AtomicBoolean published = new AtomicBoolean();

        this.loadingChunks.compute(index, (key, running) -> {
            if (running != future) return running;
            this.chunks.put(index, chunk);
            insideLock.accept(chunk);
            published.set(true);
            return null;
        });
        return published.get();
    }

    /**
     * Takes a chunk out of its position.
     *
     * @param index      the chunk index of the position
     * @param chunk      the chunk to remove, which has to be the one at that position
     * @param insideLock the step to run while the position is held, once, only if the removal happens
     * @return true if the chunk was removed, false if it was not the chunk of that position
     */
    public boolean remove(long index, FalcoChunk chunk, Consumer<FalcoChunk> insideLock) {
        final AtomicBoolean removed = new AtomicBoolean();

        this.loadingChunks.compute(index, (key, running) -> {
            if (this.chunks.remove(index, chunk)) {
                insideLock.accept(chunk);
                removed.set(true);
            }
            return running;
        });
        return removed.get();
    }
}
```

- [ ] **Step 4: Point `FalcoInstance` at it**

Delete the fields `chunks:156` and `loadingChunks:174` and add `private final ChunkRegistry registry = new ChunkRegistry();`. Then rewrite the five places that touched them, and nothing else:

```java
    @Override
    public @Nullable Chunk getChunk(int chunkX, int chunkZ) {
        return this.registry.chunk(chunkX, chunkZ);
    }

    @Override
    public @UnmodifiableView Collection<Chunk> getChunks() {
        return this.registry.chunks();
    }
```

`unregister:296` becomes:

```java
    public void unregister(InstanceManager instanceManager) {
        if (isRegistered()) instanceManager.unregisterInstance(this);
        for (int pass = 0; pass < UNREGISTER_PASSES; pass++) {
            for (Long index : this.registry.loadingPositions()) discardRunningLoad(index);
            for (Chunk chunk : this.registry.snapshot()) unloadChunk(chunk);
            if (this.registry.idle()) {
                this.generationForks.clear();
                return;
            }
        }
        this.generationForks.clear();
        LOGGER.warn("chunks kept arriving while the instance {} was unregistered; {} chunks and {} loads are left behind",
                getUuid(), this.registry.size(), this.registry.loading());
    }
```

`discardRunningLoad:325` becomes:

```java
    private void discardRunningLoad(long index) {
        final CompletableFuture<Chunk> running = this.registry.discard(index);

        if (running == null) return;
        running.completeExceptionally(new FalcoInstanceException("the chunk "
                + CoordConversion.chunkIndexGetX(index) + ":" + CoordConversion.chunkIndexGetZ(index)
                + " was unloaded while it was being loaded, so the load was cancelled"));
    }
```

`retrieveChunk:544` becomes:

```java
    private CompletableFuture<Chunk> retrieveChunk(int chunkX, int chunkZ) {
        final long index = CoordConversion.chunkIndex(chunkX, chunkZ);
        final Chunk loaded = this.registry.chunk(index);
        if (loaded != null) return CompletableFuture.completedFuture(loaded);

        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        final ChunkRegistry.LoadSlot slot = this.registry.acquire(index, own);
        switch (slot) {
            case ChunkRegistry.LoadSlot.Loaded(Chunk cached) -> {
                return CompletableFuture.completedFuture(cached);
            }
            case ChunkRegistry.LoadSlot.Running(CompletableFuture<Chunk> running) -> {
                return running;
            }
            case ChunkRegistry.LoadSlot.Claimed ignored -> {
                final ChunkLoader loader = this.chunkLoader;
                if (loader.supportsParallelLoading()) {
                    Thread.startVirtualThread(() -> completeLoad(index, chunkX, chunkZ, loader, own));
                } else {
                    // A loader without parallel support is read on the calling thread, which keeps a
                    // `loadChunk(…).join()` from a tick free of a thread hand-off it would only wait for.
                    completeLoad(index, chunkX, chunkZ, loader, own);
                }
                return own;
            }
        }
    }
```

`publishChunk:638` and the atomic half of `unloadChunk:722` become calls:

```java
    private boolean publishChunk(long index, FalcoChunk chunk, CompletableFuture<Chunk> future) {
        return this.registry.publish(index, chunk, future,
                published -> MinecraftServer.process().dispatcher().createPartition(published));
    }
```

```java
        final boolean removed = this.registry.remove(index, falcoChunk, unloaded -> {
            unloaded.markUnloaded();
            MinecraftServer.process().dispatcher().deletePartition(unloaded);
        });
        if (!removed) return;
```

And in `completeLoad:600`, `this.loadingChunks.remove(index, future)` becomes `this.registry.release(index, future)`.

- [ ] **Step 5: Run the registry test, then the whole module**

```bash
./gradlew :falco-instance:test --tests "*ChunkRegistryTest*"
./gradlew :falco-instance:test
```

Expected: PASS, six and then 159 (143 from stage 2, plus the sixteen of Task 1 and this one). `FalcoInstanceLoadRaceTest` is the case that matters: it is the only test that can see a mistake in the `compute` bodies, and it is why they were moved verbatim.

- [ ] **Step 6: Commit**

```bash
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkRegistry.java \
        falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/ChunkRegistryTest.java
git commit -m "refactor(instance): give the chunk positions a registry of their own"
```

---

### Task 3: `ChunkPersistence`

**Files:**
- Create: `falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkPersistence.java`
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoInstancePersistenceTest.java` (from Task 1, extended)

**Interfaces:**
- Consumes: `ChunkRegistry#snapshot()` from Task 2.
- Produces:
  - `ChunkPersistence(@Nullable ChunkLoader loader)`
  - `ChunkLoader loader()`
  - `void loader(ChunkLoader loader)`
  - `@Nullable Chunk read(Instance instance, int chunkX, int chunkZ)`
  - `void unloaded(Chunk chunk)`
  - `CompletableFuture<Void> saveInstance(Instance instance)`
  - `CompletableFuture<Void> saveChunk(Chunk chunk)`
  - `CompletableFuture<Void> saveChunks(List<Chunk> chunks)`

  Task 5 depends on `loader()`, `read` and `unloaded`.

**Reference:** `FalcoInstance#saveInstance:755`, `#saveChunkToStorage:761`, `#saveChunksToStorage:767`, `#runSave:781`, `#getChunkLoader:817`, `#setChunkLoader:831`, and the constructor line `this.chunkLoader.loadInstance(this)` at `:260`.

- [ ] **Step 1: Write the failing test**

Append to `FalcoInstancePersistenceTest.java`:

```java
    @Test
    @DisplayName("is usable on its own, without an instance driving it")
    void testThePartRunsWithoutTheFacade(Env env) {
        final CountingLoader loader = new CountingLoader(false, null);
        final FalcoInstance instance = registered(env, loader);
        final ChunkPersistence persistence = new ChunkPersistence(loader);

        persistence.saveInstance(instance).join();
        persistence.saveChunks(List.of()).join();

        assertEquals(1, loader.instanceSaves.get());
        assertSame(loader, persistence.loader());
    }

    @Test
    @DisplayName("uses a loader which saves and loads nothing when it is given none")
    void testTheDefaultLoaderIsTheNoopOne(Env env) {
        registered(env, ChunkLoader.noop());
        final ChunkPersistence persistence = new ChunkPersistence(null);

        assertNotNull(persistence.loader(), "a null loader has to become the noop loader, not stay null");
        assertNull(persistence.read(null, 0, 0), "the noop loader reads nothing");
    }
```

Add the imports `java.util.List`, `org.junit.jupiter.api.Assertions.assertNotNull` and `assertNull`.

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :falco-instance:test --tests "*FalcoInstancePersistenceTest*"
```

Expected: compilation failure — `ChunkPersistence` does not exist.

- [ ] **Step 3: Write the part**

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The {@link ChunkPersistence} class is everything a Falco instance does with a {@link ChunkLoader}.
 * <p>
 * Four save entry points, one read and one unload notification, and the decision on which thread each
 * of them runs. That decision is the only piece of judgement in this class and it belongs to the
 * loader: a loader which reports {@code supportsParallelSaving()} is moved onto a virtual thread, and
 * one which does not runs where it was called, so a {@code saveInstance().join()} from a tick is not
 * a thread hand-off the caller only waits for.
 * </p>
 * <p>
 * A failure completes the returned future exceptionally and stops there. It is deliberately not also
 * pushed into the exception manager of the server the way {@code InstanceContainer} does it, because
 * a failure that is both reported and returned gets handled twice and logged twice — which is
 * NFR-005 for the save direction.
 * </p>
 * <p>
 * This type is experimental. The instance module is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public final class ChunkPersistence {

    /**
     * The loader chunks are read from and written to, never null.
     */
    private volatile ChunkLoader chunkLoader;

    /**
     * Creates a persistence over a loader.
     *
     * @param loader the loader chunks are read from and written to, null for a loader which loads and
     *               saves nothing
     */
    public ChunkPersistence(@Nullable ChunkLoader loader) {
        this.chunkLoader = Objects.requireNonNullElseGet(loader, ChunkLoader::noop);
    }

    /**
     * Returns the loader chunks are read from and written to.
     *
     * @return the current chunk loader
     */
    public ChunkLoader loader() {
        return this.chunkLoader;
    }

    /**
     * Changes the loader chunks are read from and written to.
     * <p>
     * Chunks which are already loaded are not affected, and {@code ChunkLoader#loadInstance} is not
     * called again — it belongs to the construction of the instance, and calling it on a world which
     * already has chunks would overwrite live state with what is on disk.
     * </p>
     *
     * @param loader the new chunk loader
     */
    public void loader(ChunkLoader loader) {
        this.chunkLoader = Objects.requireNonNull(loader, "the chunk loader cannot be null");
    }

    /**
     * Reads a chunk through the current loader.
     *
     * @param instance the instance the chunk is read for
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     * @return the chunk the loader produced, or null if it knows nothing about that position
     */
    public @Nullable Chunk read(Instance instance, int chunkX, int chunkZ) {
        return this.chunkLoader.loadChunk(instance, chunkX, chunkZ);
    }

    /**
     * Tells the loader that a chunk is no longer part of its instance.
     * <p>
     * Called for a chunk which was unloaded and for a chunk whose load was discarded before it was
     * ever published: the loader created it and may hold bookkeeping for it, which its own
     * documentation allows for explicitly.
     * </p>
     *
     * @param chunk the chunk which left the instance
     */
    public void unloaded(Chunk chunk) {
        this.chunkLoader.unloadChunk(chunk);
    }

    /**
     * Reports whether the current loader can be read from off the calling thread.
     *
     * @return true if a load may be moved to a virtual thread
     */
    public boolean parallelLoading() {
        return this.chunkLoader.supportsParallelLoading();
    }

    /**
     * Saves the instance itself.
     *
     * @param instance the instance to save
     * @return a future completed once the work is done, completed exceptionally if it threw
     */
    public CompletableFuture<Void> saveInstance(Instance instance) {
        final ChunkLoader loader = this.chunkLoader;
        return run(loader.supportsParallelSaving(), () -> loader.saveInstance(instance));
    }

    /**
     * Saves one chunk.
     *
     * @param chunk the chunk to save
     * @return a future completed once the work is done, completed exceptionally if it threw
     */
    public CompletableFuture<Void> saveChunk(Chunk chunk) {
        final ChunkLoader loader = this.chunkLoader;
        return run(loader.supportsParallelSaving(), () -> loader.saveChunk(chunk));
    }

    /**
     * Saves a batch of chunks.
     *
     * @param chunks the chunks to save
     * @return a future completed once the work is done, completed exceptionally if it threw
     */
    public CompletableFuture<Void> saveChunks(List<Chunk> chunks) {
        final ChunkLoader loader = this.chunkLoader;
        return run(loader.supportsParallelSaving(), () -> loader.saveChunks(chunks));
    }

    /**
     * Runs a save either on the calling thread or on a virtual thread.
     *
     * @param parallel true to move the work off the calling thread
     * @param save     the work to perform
     * @return a future completed once the work is done, completed exceptionally if it threw
     */
    private CompletableFuture<Void> run(boolean parallel, Runnable save) {
        if (!parallel) {
            try {
                save.run();
                return CompletableFuture.completedFuture(null);
            } catch (Throwable throwable) {
                return CompletableFuture.failedFuture(throwable);
            }
        }
        final CompletableFuture<Void> future = new CompletableFuture<>();

        Thread.startVirtualThread(() -> {
            try {
                save.run();
                future.complete(null);
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }
}
```

- [ ] **Step 4: Point `FalcoInstance` at it**

Delete the field `chunkLoader:213` and the method `runSave:781`, and add `private final ChunkPersistence persistence;`. The constructor at `:255` becomes:

```java
        super(registries, uuid, dimensionType, dimensionName);
        this.registries = registries;
        this.persistence = new ChunkPersistence(loader);
        // Outside the ChunkPersistence constructor on purpose: loadInstance may call back into this
        // instance, and a callback into an object whose constructor has not finished is how a field
        // that is assigned two lines later is read as null.
        this.persistence.loader().loadInstance(this);
        this.lastBlockChangeTime = System.nanoTime();
```

The six delegating methods:

```java
    @Override
    public CompletableFuture<Void> saveInstance() {
        return this.persistence.saveInstance(this);
    }

    @Override
    public CompletableFuture<Void> saveChunkToStorage(Chunk chunk) {
        return this.persistence.saveChunk(chunk);
    }

    @Override
    public CompletableFuture<Void> saveChunksToStorage() {
        return this.persistence.saveChunks(this.registry.snapshot());
    }

    public ChunkLoader getChunkLoader() {
        return this.persistence.loader();
    }

    public void setChunkLoader(ChunkLoader chunkLoader) {
        this.persistence.loader(chunkLoader);
    }
```

In `retrieveChunk`, `final ChunkLoader loader = this.chunkLoader;` becomes `final ChunkLoader loader = this.persistence.loader();` and `loader.supportsParallelLoading()` stays as it is — the loader is captured once and handed to `completeLoad`, which is the existing behaviour and is not changed here.

**One difference is preserved on purpose.** `completeLoad:609` calls `this.chunkLoader.unloadChunk(...)` — the *current* loader — while the chunk was read through the loader captured at `:564`. The two can differ if `setChunkLoader` runs during a load. That is the behaviour as it stands; it becomes `this.persistence.unloaded(...)`, which reads the current loader in the same way. Changing it is a behaviour change and does not belong in a refactor. Write it down in the javadoc of `completeLoad` so the next reader does not have to rediscover it.

- [ ] **Step 5: Run the tests**

```bash
./gradlew :falco-instance:test --tests "*FalcoInstancePersistenceTest*"
./gradlew :falco-instance:test
```

Expected: PASS, eight and then 161.

- [ ] **Step 6: Commit**

```bash
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkPersistence.java \
        falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoInstancePersistenceTest.java
git commit -m "refactor(instance): move the loader and the four save paths behind ChunkPersistence"
```

---

### Task 4: `ChunkGeneration`

**Files:**
- Create: `falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkGeneration.java`
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/ChunkGenerationTest.java`

**Interfaces:**
- Consumes: `BlockStorage#view(int)`, `#shared(int)`, `#section(int)`, `#sectionCount()`, `PaletteCompaction#packBlocks`, `#packBiomes` — all as stage 2 left them.
- Produces:
  - `ChunkGeneration(Registries registries, Function<Point, Chunk> chunkAt)`
  - `@Nullable Generator generator()`
  - `void generator(@Nullable Generator generator)`
  - `void apply(Chunk chunk, Generator generator)`
  - `void applyPending(Chunk chunk)`
  - `int pendingForks()`
  - `void clearPending()`

  Task 5 depends on `generator()`, `apply` and `applyPending`.

**Reference:** `FalcoInstance#applyGenerator:959`, `#commitSection:1056`, `#storageOf:1086`, `#writeSpecialBlocks:1108`, `#applyForks:1131`, `#applyPendingForks:1165`, `#applyFork:1197`, and the fields `generationForks:186`, `registries:195`, `generator:200`. **Every one of these bodies moves unchanged.** They carry the two corrections of stage 2 — the two-pass commit with `invalidate()` between the passes, and the `producedNothing && shared(index)` skip — and both are pinned by `FalcoInstanceGeneratorTest#testTheHeightmapsSeeTheWholeChunkAndNotHalfOfIt` and by `SectionMaterialisationTest`. The one thing that changes is how the class reaches a chunk that is not the one it was asked about: `getChunkAt(start)` becomes the `Function<Point, Chunk>` handed in, so that this class needs no instance.

The javadoc of all seven moves with them. It is long and it is the reason the two-pass commit survives; do not shorten it.

- [ ] **Step 1: Write the failing test**

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Drives the generator side of a Falco instance without the instance.
 * <p>
 * The fork bookkeeping used to be a {@code private} map of {@code FalcoInstance} and could only be
 * observed through the world it eventually produced, which made a test of it a test of the whole load
 * path. Here the map has a size that can be read, so the case that mattered — a fork for a chunk
 * nobody ever asks for — is assertable instead of inferable.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The generator side of a Falco instance")
class ChunkGenerationTest {

    /**
     * Creates a registered instance to build chunks for.
     *
     * @param env the environment which provides the server process
     * @return the registered instance
     */
    private static FalcoInstance registered(Env env) {
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);
        return instance;
    }

    @Test
    @DisplayName("has no generator until it is given one")
    void testTheGeneratorIsHandedBack(Env env) {
        registered(env);
        final ChunkGeneration generation = new ChunkGeneration(MinecraftServer.process(), point -> null);
        final Generator generator = unit -> unit.modifier().fillHeight(0, 16, Block.STONE);

        assertNull(generation.generator());
        generation.generator(generator);
        assertSame(generator, generation.generator());
    }

    @Test
    @DisplayName("writes what the generator produced into the chunk it was asked about")
    void testAGeneratedChunkCarriesItsBlocks(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkGeneration generation = new ChunkGeneration(MinecraftServer.process(), point -> null);
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);

        generation.apply(chunk, unit -> unit.modifier().fillHeight(0, 16, Block.STONE));

        chunk.lockReadLock();
        try {
            assertEquals(Block.STONE, chunk.getBlock(0, 0, 0));
            assertEquals(Block.AIR, chunk.getBlock(0, 32, 0));
        } finally {
            chunk.unlockReadLock();
        }
    }

    @Test
    @DisplayName("keeps a fork for a chunk which does not exist and delivers it when it does")
    void testAPendingForkIsKeptAndDelivered(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkGeneration generation = new ChunkGeneration(MinecraftServer.process(), point -> null);
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);

        generation.apply(chunk, unit -> unit.fork(setter ->
                setter.setBlock(new net.minestom.server.coordinate.Vec(20, 0, 0), Block.STONE)));

        assertEquals(1, generation.pendingForks(),
                "the fork landed in the chunk at 1:0, which does not exist, so it has to be remembered");

        final FalcoChunk neighbour = new FalcoChunk(instance, 1, 0);
        generation.applyPending(neighbour);

        assertEquals(0, generation.pendingForks(), "delivering a fork has to take it off the list");
        neighbour.lockReadLock();
        try {
            assertEquals(Block.STONE, neighbour.getBlock(20, 0, 0));
        } finally {
            neighbour.unlockReadLock();
        }
    }

    @Test
    @DisplayName("drops every pending fork when it is told to")
    void testPendingForksCanBeDropped(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkGeneration generation = new ChunkGeneration(MinecraftServer.process(), point -> null);
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        generation.apply(chunk, unit -> unit.fork(setter ->
                setter.setBlock(new net.minestom.server.coordinate.Vec(20, 0, 0), Block.STONE)));

        generation.clearPending();

        assertEquals(0, generation.pendingForks(),
                "a fork whose target chunk is never requested waits forever, so a shutdown has to drop it");
    }
}
```

Note the constructor: `ChunkGeneration(Registries registries, Function<Point, Chunk> chunkAt)`, and `MinecraftServer.process()` is a `Registries`. The lambda `point -> null` is the honest stand-in for an instance that has no other chunk loaded, which is what these four cases are about.

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :falco-instance:test --tests "*ChunkGenerationTest*"
```

Expected: compilation failure — `ChunkGeneration` does not exist.

- [ ] **Step 3: Write the part**

The class holds the three moved fields and the seven moved methods:

```java
package net.onelitefeather.falco.instance;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.GenerationUnit;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.instance.generator.GeneratorImpl;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.registry.Registries;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The {@link ChunkGeneration} class runs a generator over a chunk and commits what it produced.
 * <p>
 * It is a collaborator of {@link ChunkLifecycle} rather than a part of the facade, and the reason is
 * that a chunk is generated exactly once and that once is inside its load. Splitting generation off
 * as a fifth part of the facade would give the instance a field nothing but the lifecycle ever
 * touches.
 * </p>
 * <p>
 * It reaches a chunk which is not the one it was asked about through the function it was built with
 * rather than through an instance. A fork writes into a neighbour, and a neighbour is the only thing
 * this class ever needs a world for; taking that as a parameter is what lets it be driven by a test
 * that has no instance at all.
 * </p>
 * <p>
 * This type is experimental. The instance module is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public final class ChunkGeneration {

    /**
     * The registries the biomes of a generated chunk are looked up in.
     */
    private final Registries registries;

    /**
     * How a chunk at a point is found, for the forks which land outside the generated chunk.
     */
    private final Function<Point, Chunk> chunkAt;

    /**
     * The section modifiers a generator produced for chunks which were not loaded at the time, keyed
     * by the chunk index of the chunk they belong to.
     */
    private final Map<Long, List<GeneratorImpl.SectionModifierImpl>> generationForks = new ConcurrentHashMap<>();

    /**
     * The generator which fills a chunk no loader knows about, null while the world stays empty.
     */
    private volatile @Nullable Generator generator;

    /**
     * Creates a generation side.
     *
     * @param registries the registries the biomes of a generated chunk are looked up in
     * @param chunkAt    how a chunk at a point is found, for forks which land outside
     */
    public ChunkGeneration(Registries registries, Function<Point, Chunk> chunkAt) {
        this.registries = registries;
        this.chunkAt = chunkAt;
    }

    // generator(), generator(Generator), pendingForks(), clearPending() are trivial accessors.
    // apply(Chunk, Generator)      <- FalcoInstance#applyGenerator:959, verbatim
    // commitSection(...)           <- FalcoInstance#commitSection:1056, verbatim
    // storageOf(Chunk)             <- FalcoInstance#storageOf:1086, verbatim
    // writeSpecialBlocks(...)      <- FalcoInstance#writeSpecialBlocks:1108, verbatim
    // applyForks(...)              <- FalcoInstance#applyForks:1131, with getChunkAt(start)
    //                                 replaced by this.chunkAt.apply(start)
    // applyPending(Chunk)          <- FalcoInstance#applyPendingForks:1165, verbatim
    // applyFork(...)               <- FalcoInstance#applyFork:1197, verbatim
}
```

The four accessors in full, because they are the only lines of this class that are new:

```java
    /**
     * Returns the generator which fills a chunk no loader knows about.
     *
     * @return the current generator, null if chunks without a loader stay empty
     */
    public @Nullable Generator generator() {
        return this.generator;
    }

    /**
     * Changes the generator which fills a chunk no loader knows about.
     * <p>
     * Chunks which are already loaded are not affected. A generator is asked for a chunk exactly
     * once, when that chunk is created, so changing it later changes the parts of the world which are
     * not there yet.
     * </p>
     *
     * @param generator the new generator, null to let chunks without a loader stay empty
     */
    public void generator(@Nullable Generator generator) {
        this.generator = generator;
    }

    /**
     * Returns how many chunk positions are waiting for a fork to be delivered to them.
     * <p>
     * Exposed because a map nothing can observe is a map nothing can assert, and a fork for a chunk
     * that is never requested is the one case that leaks quietly.
     * </p>
     *
     * @return the amount of positions with a pending fork
     */
    public int pendingForks() {
        return this.generationForks.size();
    }

    /**
     * Drops every fork which is still waiting for its chunk.
     * <p>
     * A fork whose target chunk was never requested waits forever, and after a shutdown there is
     * nothing left it could wait for.
     * </p>
     */
    public void clearPending() {
        this.generationForks.clear();
    }
```

- [ ] **Step 4: Point `FalcoInstance` at it**

Delete the fields `generationForks:186` and `generator:200`, keep `registries:195` for now (Task 5 moves it), and add `private final ChunkGeneration generation;`, built in the constructor as `new ChunkGeneration(registries, this::getChunkAt)`. Then:

```java
    @Override
    public @Nullable Generator generator() {
        return this.generation.generator();
    }

    @Override
    public void setGenerator(@Nullable Generator generator) {
        this.generation.generator(generator);
    }
```

`createChunk:662` calls `this.generation.apply(chunk, current)` and `this.generation.applyPending(chunk)`; `generateChunk:875` calls `this.generation.apply(chunk, generator)` and then `refreshLastBlockChangeTime()`. Note that `applyGenerator` ended with `refreshLastBlockChangeTime()` — that line does **not** move into `ChunkGeneration`, because the timestamp belongs to the block write side. Both callers of `apply` take it over, which is the same behaviour with the call site made visible. `unregister` calls `this.generation.clearPending()` where it cleared the map.

- [ ] **Step 5: Run the tests**

```bash
./gradlew :falco-instance:test --tests "*ChunkGenerationTest*"
./gradlew :falco-instance:test
```

Expected: PASS, four and then 165. `FalcoInstanceGeneratorTest` is the net here and all twelve of its cases have to stay green; `SectionMaterialisationTest` is the second net, because the `producedNothing && shared(index)` skip is what keeps a generated chunk at four materialised sections instead of twenty-four.

- [ ] **Step 6: Prove the move kept the two-pass commit**

Merge the two loops of `apply` back into one — write the special blocks in the same pass as the palettes — and watch `FalcoInstanceGeneratorTest#testTheHeightmapsSeeTheWholeChunkAndNotHalfOfIt` fail with a height of `79` instead of `127`. Restore the two passes. That defect is the reason the method has the shape it has, and a move that quietly loses it would be invisible in every other test.

- [ ] **Step 7: Commit**

```bash
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkGeneration.java \
        falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/ChunkGenerationTest.java
git commit -m "refactor(instance): move the generator and its forks into ChunkGeneration"
```

---

### Task 5: `ChunkLifecycle` — US-3.02

**Files:**
- Create: `falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkLifecycle.java`
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/ChunkLifecycleTest.java`

**Interfaces:**
- Consumes: `ChunkRegistry` (Task 2), `ChunkPersistence` (Task 3), `ChunkGeneration` (Task 4).
- Produces:
  - `ChunkLifecycle(FalcoInstance owner, ChunkRegistry registry, ChunkPersistence persistence, ChunkGeneration generation)`
  - `CompletableFuture<Chunk> retrieve(int chunkX, int chunkZ)`
  - `void completeLoad(long index, int chunkX, int chunkZ, ChunkLoader loader, CompletableFuture<Chunk> future)`
  - `boolean publish(long index, FalcoChunk chunk, CompletableFuture<Chunk> future)`
  - `FalcoChunk create(int chunkX, int chunkZ)`
  - `void unload(Chunk chunk)`
  - `void discard(long index)`
  - `ChunkSupplier supplier()` / `void supplier(ChunkSupplier supplier)`
  - `boolean autoLoad()` / `void autoLoad(boolean enable)`

  Task 8 adds `addListener` and `listener()` to this class; Task 9 changes the body of `unload`.

**Reference:** `FalcoInstance#retrieveChunk:544`, `#completeLoad:590`, `#publishChunk:638`, `#createChunk:662`, `#requireFalcoChunk:690`, `#unloadChunk:722`, `#discardRunningLoad:325`, and the fields `chunkSupplier:211` and `autoChunkLoad:215`.

**This is the task US-3.02 is about.** `publishChunk` and `completeLoad` become public methods of a class that can be built in a test with four collaborators, so both are reachable without driving a load through a loader. `requireFalcoChunk` moves to `FalcoChunk#require(Chunk)`, a public static, because both this class and `BlockWriter` need it.

- [ ] **Step 1: Write the failing test**

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.event.instance.InstanceChunkLoadEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reaches the publish and the load completion of a chunk without driving a full load, which is
 * US-3.02.
 * <p>
 * Both were {@code private} methods of {@code FalcoInstance} before this stage. The only way to run
 * either of them was to ask the instance for a chunk, which meant that the case they exist for —
 * a publish that is refused because an unload claimed the position while the loader was still
 * working — could not be arranged from a test at all: it needs the two to interleave, and a caller
 * driving the whole load path has no seam to interleave at. {@code FalcoInstanceLoadRaceTest} gets
 * close by running a thousand loads and unloads against each other and hoping the window is hit;
 * these cases hit it every time, deterministically, in a single thread.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The lifecycle of one chunk, driven step by step")
class ChunkLifecycleTest {

    /**
     * The position every case works on.
     */
    private static final long INDEX = CoordConversion.chunkIndex(0, 0);

    /**
     * Creates a registered instance in the environment of the test.
     *
     * @param env the environment which provides the server process
     * @return the registered instance
     */
    private static FalcoInstance registered(Env env) {
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);
        return instance;
    }

    @Test
    @DisplayName("publishes a chunk that was never loaded through a loader")
    void testPublishWithoutALoad(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycle lifecycle = instance.lifecycle();
        final ChunkRegistry registry = instance.registry();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        registry.acquire(INDEX, own);

        assertTrue(lifecycle.publish(INDEX, chunk, own));

        assertSame(chunk, instance.getChunk(0, 0));
        assertTrue(chunk.isLoaded() || !chunk.isLoaded(),
                "publishing does not set the loaded flag; completeLoad does, and that is the split");
    }

    @Test
    @DisplayName("refuses to publish a chunk whose position was claimed while it was being built")
    void testPublishIsRefusedAfterADiscard(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycle lifecycle = instance.lifecycle();
        final ChunkRegistry registry = instance.registry();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        registry.acquire(INDEX, own);

        lifecycle.discard(INDEX);

        assertFalse(lifecycle.publish(INDEX, chunk, own),
                "the position was claimed, so this chunk is not wanted any more");
        assertNull(instance.getChunk(0, 0));
        assertTrue(own.isCompletedExceptionally(), "the callers waiting for that load have to be told");
    }

    @Test
    @DisplayName("completes a load, marks the chunk and fires the load event exactly once")
    void testCompleteLoadDrivenDirectly(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycle lifecycle = instance.lifecycle();
        final AtomicInteger events = new AtomicInteger();
        instance.eventNode().addListener(InstanceChunkLoadEvent.class, event -> events.incrementAndGet());
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        instance.registry().acquire(INDEX, own);

        lifecycle.completeLoad(INDEX, 0, 0, ChunkLoader.noop(), own);

        final Chunk chunk = own.join();
        assertTrue(chunk.isLoaded(), "completeLoad is what marks the chunk loaded");
        assertSame(chunk, instance.getChunk(0, 0));
        assertEquals(1, events.get());
    }

    @Test
    @DisplayName("hands a discarded load its failure instead of its chunk")
    void testCompleteLoadOnAClaimedPosition(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycle lifecycle = instance.lifecycle();
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        instance.registry().acquire(INDEX, own);
        lifecycle.discard(INDEX);

        lifecycle.completeLoad(INDEX, 0, 0, ChunkLoader.noop(), own);

        final CompletionException thrown = assertThrows(CompletionException.class, own::join);
        assertSame(FalcoInstanceException.class, thrown.getCause().getClass(),
                "a chunk handed back after it was discarded looks usable and is not");
        assertNull(instance.getChunk(0, 0));
    }

    @Test
    @DisplayName("hands a failing loader back to the caller and gives up the slot")
    void testAFailingLoaderReleasesThePosition(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycle lifecycle = instance.lifecycle();
        final CompletableFuture<Chunk> own = new CompletableFuture<>();
        instance.registry().acquire(INDEX, own);

        lifecycle.completeLoad(INDEX, 0, 0, new ChunkLoader() {

            @Override
            public Chunk loadChunk(net.minestom.server.instance.Instance instance, int chunkX, int chunkZ) {
                throw new IllegalStateException("the region file is a directory");
            }
        }, own);

        assertThrows(CompletionException.class, own::join);
        assertEquals(0, instance.registry().loading(),
                "a failed load must not leave its position marked as busy forever");
    }

    @Test
    @DisplayName("creates a chunk through the supplier and refuses one which is not a Falco chunk")
    void testCreateUsesTheSupplier(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycle lifecycle = instance.lifecycle();

        assertSame(FalcoChunk.class, lifecycle.create(3, 4).getClass());

        lifecycle.supplier((owner, chunkX, chunkZ) -> new net.minestom.server.instance.DynamicChunk(owner, chunkX, chunkZ));
        assertThrows(FalcoInstanceException.class, () -> lifecycle.create(3, 4));
    }

    @Test
    @DisplayName("unloads a chunk once and does nothing the second time")
    void testUnloadIsIdempotent(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycle lifecycle = instance.lifecycle();
        final Chunk chunk = instance.loadChunk(0, 0).join();

        lifecycle.unload(chunk);
        lifecycle.unload(chunk);

        assertFalse(chunk.isLoaded());
        assertNull(instance.getChunk(0, 0));
    }
}
```

`MinecraftServer` is imported for symmetry with the other tests in this package and may be dropped if unused.

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :falco-instance:test --tests "*ChunkLifecycleTest*"
```

Expected: compilation failure — `ChunkLifecycle`, `FalcoInstance#lifecycle()` and `FalcoInstance#registry()` do not exist.

- [ ] **Step 3: Move `requireFalcoChunk` to `FalcoChunk`**

```java
    /**
     * Checks that a chunk is one the instance module can manage.
     * <p>
     * A chunk of any other type is accepted by everything except the unload path, where the
     * {@code protected} lifecycle hooks are out of reach, so it would silently keep reporting itself
     * as loaded forever. Refusing it here names the cause at the point where the wrong supplier was
     * used.
     * </p>
     * <p>
     * It lives on the chunk rather than on the instance because two parts of the instance need it —
     * {@link ChunkLifecycle} on the load and unload path, {@code BlockWriter} on every write — and a
     * check that both of them copy is a check that can drift.
     * </p>
     *
     * @param chunk the chunk to check
     * @return the same chunk, typed
     * @throws FalcoInstanceException if the chunk is not a {@link FalcoChunk}
     * @since 0.4.0
     */
    @Contract("_ -> param1")
    public static FalcoChunk require(Chunk chunk) {
        if (chunk instanceof FalcoChunk falcoChunk) return falcoChunk;
        throw new FalcoInstanceException("the instance module only manages " + FalcoChunk.class.getName()
                + ", but its chunk supplier produced a " + chunk.getClass().getName()
                + "; the lifecycle hooks of any other chunk cannot be reached from this package");
    }
```

Raise `FalcoChunk`'s `@version` to `3.5.0` for this change; Task 8 raises it again and that is fine — the number moves once per change that ships, and both ship in this stage.

- [ ] **Step 4: Write the lifecycle**

Class shape, with the moved bodies named:

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.entity.Entity;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.instance.InstanceChunkLoadEvent;
import net.minestom.server.event.instance.InstanceChunkUnloadEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.EntityTracker;
import net.minestom.server.network.packet.server.play.UnloadChunkPacket;
import net.minestom.server.utils.chunk.ChunkSupplier;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The {@link ChunkLifecycle} class is everything that happens to a chunk between not existing and
 * not existing again: it is created, filled, published, marked, ticked and taken away.
 * <p>
 * Every step is a method of its own and every one of them is reachable without the others. That is
 * the whole point of the class and it is US-3.02: {@code publishChunk} and {@code completeLoad} were
 * {@code private} methods of a class of 1 272 lines, so the only way to run them was to ask the
 * instance for a chunk. The case they exist for cannot be arranged that way — a publish is refused
 * when an unload claims the position while the loader is still working, and a caller driving the
 * whole load path has no seam to interleave at.
 * </p>
 *
 * <h2>What runs while a position is held, and what does not</h2>
 * <p>
 * Putting a chunk into the registry and giving it a tick partition are one step, taken while the
 * position is held, so an unload of the same position can only run entirely before or entirely after
 * it. Splitting them is what lets Minestom delete a partition that is created a moment later, which
 * leaves the chunk being ticked for the rest of the life of the server even though nothing else knows
 * about it any more.
 * </p>
 * <p>
 * The loaded flag of the chunk is deliberately set outside the lock, because it calls a hook a
 * subclass may override, and foreign code has no business running while a position is held. The
 * packet, the event, the entities and the loader follow outside for the same reason: all four can
 * call back into the instance, and holding a position while foreign code runs is how two chunks
 * deadlock each other.
 * </p>
 * <p>
 * This type is experimental. The instance module is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public final class ChunkLifecycle {

    /**
     * The instance whose chunks these are, needed for the events and for the chunk supplier.
     */
    private final FalcoInstance owner;

    /**
     * Where a chunk goes when it is published and where it is taken from when it is unloaded.
     */
    private final ChunkRegistry registry;

    /**
     * Where a chunk is read from and where its removal is reported to.
     */
    private final ChunkPersistence persistence;

    /**
     * What fills a chunk no loader knows about.
     */
    private final ChunkGeneration generation;

    /**
     * What produces the chunk objects of this instance.
     */
    private volatile ChunkSupplier chunkSupplier = FalcoChunk::new;

    /**
     * Whether a chunk which is asked for is loaded on demand.
     */
    private volatile boolean autoChunkLoad = true;

    // retrieve(int, int)                              <- FalcoInstance#retrieveChunk:544
    // completeLoad(long, int, int, ChunkLoader, ...)   <- FalcoInstance#completeLoad:590
    // publish(long, FalcoChunk, CompletableFuture)     <- FalcoInstance#publishChunk:638
    // create(int, int)                                 <- FalcoInstance#createChunk:662
    // unload(Chunk)                                    <- FalcoInstance#unloadChunk:722
    // discard(long)                                    <- FalcoInstance#discardRunningLoad:325
}
```

The four methods whose bodies change, in full:

```java
    /**
     * Reads a chunk through the loader, publishes it and completes the waiting future.
     * <p>
     * The chunk is produced first and published second, and the publish may be refused. Everything in
     * between the two is the window in which an unload can decide that this chunk is not wanted any
     * more; a load which is refused therefore has to undo itself rather than complain.
     * </p>
     * <p>
     * The loader which is told about the discard is the <em>current</em> one and not the one this
     * load read from. The two can differ if the loader was swapped while a load was running. That is
     * the behaviour this method had before the split and it is preserved rather than corrected,
     * because a refactoring which changes behaviour cannot be checked by the tests that passed before
     * it.
     * </p>
     *
     * @param index  the chunk index of the position, the key in the registry
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @param loader the loader the chunk is read from
     * @param future the future handed to the callers waiting for this chunk
     */
    public void completeLoad(long index, int chunkX, int chunkZ, ChunkLoader loader,
                             CompletableFuture<Chunk> future) {
        final FalcoChunk falcoChunk;
        try {
            Chunk chunk = loader.loadChunk(this.owner, chunkX, chunkZ);
            if (chunk == null) {
                chunk = create(chunkX, chunkZ);
                chunk.onGenerate();
            }
            falcoChunk = FalcoChunk.require(chunk);
        } catch (Throwable throwable) {
            this.registry.release(index, future);
            future.completeExceptionally(throwable);
            return;
        }
        if (!publish(index, falcoChunk, future)) {
            // The chunk was never part of this instance, so there is no registry entry and no
            // partition to clean up. The loader is still told, because it created the chunk and may
            // hold bookkeeping for it, which its own documentation allows for explicitly.
            falcoChunk.markUnloaded();
            this.persistence.unloaded(falcoChunk);
            future.completeExceptionally(new FalcoInstanceException("the chunk " + chunkX + ":" + chunkZ
                    + " was unloaded while it was being loaded, so the loaded chunk was discarded"));
            return;
        }
        falcoChunk.markLoaded();
        future.complete(falcoChunk);
        EventDispatcher.call(new InstanceChunkLoadEvent(this.owner, falcoChunk));
    }

    /**
     * Makes a freshly built chunk part of this instance, unless somebody claimed its position.
     *
     * @param index  the chunk index of the position
     * @param chunk  the chunk to publish
     * @param future the future of this load, which has to still be the entry of the position
     * @return true if the chunk is now part of this instance, false if the load was claimed
     */
    public boolean publish(long index, FalcoChunk chunk, CompletableFuture<Chunk> future) {
        return this.registry.publish(index, chunk, future,
                published -> MinecraftServer.process().dispatcher().createPartition(published));
    }

    /**
     * Creates a chunk through the chunk supplier of this instance and fills it.
     * <p>
     * This is the path a chunk takes which no {@link ChunkLoader} knows about. Without a generator the
     * chunk stays empty, which is a world made of air rather than a failure.
     * </p>
     *
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @return the created chunk
     * @throws FalcoInstanceException if the chunk supplier returned null or a foreign chunk type
     */
    public FalcoChunk create(int chunkX, int chunkZ) {
        final Chunk chunk = this.chunkSupplier.createChunk(this.owner, chunkX, chunkZ);
        if (chunk == null) {
            throw new FalcoInstanceException("the chunk supplier returned null for chunk " + chunkX + ":" + chunkZ);
        }
        final FalcoChunk falcoChunk = FalcoChunk.require(chunk);
        final var current = this.generation.generator();

        if (current != null && falcoChunk.shouldGenerate()) {
            this.generation.apply(falcoChunk, current);
            this.owner.refreshLastBlockChangeTime();
        } else {
            this.generation.applyPending(falcoChunk);
        }
        return falcoChunk;
    }

    /**
     * Removes a chunk from this instance.
     *
     * @param chunk the chunk to remove, has to be a {@link FalcoChunk}
     * @throws FalcoInstanceException if the chunk is not a {@link FalcoChunk}
     */
    public void unload(Chunk chunk) {
        if (!chunk.isLoaded()) return;
        final FalcoChunk falcoChunk = FalcoChunk.require(chunk);
        final int chunkX = falcoChunk.getChunkX();
        final int chunkZ = falcoChunk.getChunkZ();
        final long index = CoordConversion.chunkIndex(chunkX, chunkZ);
        final boolean removed = this.registry.remove(index, falcoChunk, unloaded -> {
            unloaded.markUnloaded();
            MinecraftServer.process().dispatcher().deletePartition(unloaded);
        });

        if (!removed) return;
        falcoChunk.sendPacketToViewers(new UnloadChunkPacket(chunkX, chunkZ));
        EventDispatcher.call(new InstanceChunkUnloadEvent(this.owner, falcoChunk));
        this.owner.getEntityTracker().chunkEntities(chunkX, chunkZ, EntityTracker.Target.ENTITIES)
                .forEach(Entity::remove);
        this.persistence.unloaded(falcoChunk);
    }
```

Note the one behaviour change in `create`: `FalcoChunk.require` now runs at creation rather than at the first use of the chunk. `FalcoInstanceTest#testAForeignChunkSupplierIsRejected` still passes — it asserts that a foreign supplier is refused, not where — and the failure now names the supplier at the moment it was used instead of one step later. The `ChunkLifecycleTest` case above pins the new position.

- [ ] **Step 5: Point `FalcoInstance` at it**

Delete `chunkSupplier:211`, `autoChunkLoad:215`, `createChunk:662`, `requireFalcoChunk:690`, `publishChunk:638`, `completeLoad:590`, `retrieveChunk:544`, `discardRunningLoad:325` and the body of `unloadChunk:722`. Add `private final ChunkLifecycle lifecycle;` and the two accessors the tests use:

```java
    /**
     * Hands out the registry of chunk positions of this instance.
     * <p>
     * Exposed because a facade whose parts cannot be reached is a facade whose parts cannot be
     * tested, which is the whole reason this class was split.
     * </p>
     *
     * @return the registry of this instance
     * @since 0.4.0
     */
    public ChunkRegistry registry() {
        return this.registry;
    }

    /**
     * Hands out the lifecycle of the chunks of this instance.
     *
     * @return the lifecycle of this instance
     * @since 0.4.0
     */
    public ChunkLifecycle lifecycle() {
        return this.lifecycle;
    }
```

and the delegations:

```java
    @Override
    public CompletableFuture<Chunk> loadChunk(int chunkX, int chunkZ) {
        return this.lifecycle.retrieve(chunkX, chunkZ);
    }

    @Override
    public CompletableFuture<@Nullable Chunk> loadOptionalChunk(int chunkX, int chunkZ) {
        final Chunk loaded = getChunk(chunkX, chunkZ);
        if (loaded != null) return CompletableFuture.completedFuture(loaded);
        if (!this.lifecycle.autoLoad()) return CompletableFuture.completedFuture(null);
        return this.lifecycle.retrieve(chunkX, chunkZ);
    }

    @Override
    public void unloadChunk(Chunk chunk) {
        this.lifecycle.unload(chunk);
    }

    @Override
    public void setChunkSupplier(ChunkSupplier chunkSupplier) {
        this.lifecycle.supplier(chunkSupplier);
    }

    @Override
    public ChunkSupplier getChunkSupplier() {
        return this.lifecycle.supplier();
    }

    @Override
    public void enableAutoChunkLoad(boolean enable) {
        this.lifecycle.autoLoad(enable);
    }

    @Override
    public boolean hasEnabledAutoChunkLoad() {
        return this.lifecycle.autoLoad();
    }
```

`unregister` calls `this.lifecycle.discard(index)` and `this.lifecycle.unload(chunk)`.

- [ ] **Step 6: Run the tests**

```bash
./gradlew :falco-instance:test --tests "*ChunkLifecycleTest*"
./gradlew :falco-instance:test
```

Expected: PASS, seven and then 172. `FalcoInstanceLoadRaceTest` and `FalcoInstanceUnloadTest` are the net.

- [ ] **Step 7: Prove the seam is real and not decorative**

Delete the line `if (running != future) return running;` from `ChunkRegistry#publish` and watch `ChunkLifecycleTest#testPublishIsRefusedAfterADiscard` fail deterministically, in one thread, in milliseconds. Then restore it and delete it again while running only `FalcoInstanceLoadRaceTest`: that suite may well stay green, because it has to hit a window by luck. **That difference is US-3.02 and it should be written into the commit message.**

- [ ] **Step 8: Commit**

```bash
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkLifecycle.java \
        falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoChunk.java \
        falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/ChunkLifecycleTest.java
git commit -m "refactor(instance): make publish and load completion reachable one at a time"
```

---

### Task 6: `BlockWriter`

**Files:**
- Create: `falco-instance/src/main/java/net/onelitefeather/falco/instance/BlockWriter.java`
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/BlockWriterTest.java`

**Interfaces:**
- Consumes: `FalcoChunk#require(Chunk)` from Task 5, `ChunkLifecycle#autoLoad()`.
- Produces:
  - `BlockWriter(FalcoInstance owner)`
  - `void setBlock(int x, int y, int z, Block block, boolean doBlockUpdates)`
  - `boolean placeBlock(BlockHandler.Placement placement, boolean doBlockUpdates)`
  - `boolean breakBlock(Player player, Point blockPosition, BlockFace blockFace, boolean doBlockUpdates)`
  - `void write(FalcoChunk chunk, int x, int y, int z, Block block, @Nullable BlockHandler.Placement placement, @Nullable BlockHandler.Destroy destroy, boolean doBlockUpdates, int updateDistance)`
  - `long lastChangeTime()`
  - `void refreshLastChangeTime()`
  - `void endTick()`

**Reference:** `FalcoInstance#setBlock:338`, `#placeBlock:352`, `#breakBlock:362`, `#writeBlock:413`, `#placementState:462`, `#updateNeighbours:480`, the fields `currentlyChangingBlocks:209` and `lastBlockChangeTime:217`, and `#tick:1267`. Every body moves unchanged; `getChunkAt`, `getCachedDimensionType`, `getBlock` and `loadChunk` are reached through the owner.

**NFR-006 is the reason to read this before moving it.** The write lock of the touched chunk is held across the write and nothing else: the neighbour pass, the packets and the event all run after it was released. That ordering is the difference to `InstanceContainer`, which holds a monitor over the whole instance across all three and across arbitrary `BlockHandler` code. A move that puts the `unlockWriteLock()` one line later has undone the stage-1 measurement without failing a single test today, which is why Task 1 wrote `testANeighbourReshapesItself` first: a neighbour in another chunk taking a second chunk lock while the first is held is a deadlock, and that case now runs.

- [ ] **Step 1: Write the failing test**

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.instance.block.Block;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the block writer of a Falco instance directly.
 * <p>
 * Two properties are asserted here that cannot be asserted through the instance: that a write into a
 * chunk which is handed in never consults the registry at all, and that the write lock of that chunk
 * is not held any more once the write returned. The second is what NFR-006 is about and it used to be
 * unobservable, because the only entry point took the lock, wrote, released it and ran three more
 * things, all inside one {@code private} method.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The block writer of a Falco instance")
class BlockWriterTest {

    /**
     * The height every case writes at.
     */
    private static final int Y = 64;

    /**
     * Creates a registered instance in the environment of the test.
     *
     * @param env the environment which provides the server process
     * @return the registered instance
     */
    private static FalcoInstance registered(Env env) {
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);
        return instance;
    }

    @Test
    @DisplayName("writes into the chunk it was handed, without asking where that chunk is")
    void testWriteIntoAChunkThatIsNotInTheRegistry(Env env) {
        final FalcoInstance instance = registered(env);
        final BlockWriter writer = instance.blockWriter();
        final FalcoChunk orphan = new FalcoChunk(instance, 9, 9);

        writer.write(orphan, 144, Y, 144, Block.STONE, null, null, false, 0);

        orphan.lockReadLock();
        try {
            assertEquals(Block.STONE, orphan.getBlock(144, Y, 144));
        } finally {
            orphan.unlockReadLock();
        }
        assertTrue(instance.getChunks().isEmpty(), "the writer must not have published anything");
    }

    @Test
    @DisplayName("holds the write lock of the chunk only while it writes")
    void testTheChunkLockIsReleased(Env env) {
        final FalcoInstance instance = registered(env);
        final BlockWriter writer = instance.blockWriter();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);

        writer.write(chunk, 0, Y, 0, Block.STONE, null, null, false, 0);

        // A write lock that was not released cannot be taken again from another thread, and a read
        // lock cannot be taken on top of a write lock held by this one.
        chunk.lockWriteLock();
        chunk.unlockWriteLock();
    }

    @Test
    @DisplayName("refuses to write outside the world and says so instead of throwing")
    void testAWriteOutsideTheWorldIsRefused(Env env) {
        final FalcoInstance instance = registered(env);
        final BlockWriter writer = instance.blockWriter();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);

        writer.write(chunk, 0, 5000, 0, Block.STONE, null, null, false, 0);

        chunk.lockReadLock();
        try {
            assertEquals(Block.AIR, chunk.getBlock(0, Y, 0), "nothing may have been written anywhere");
        } finally {
            chunk.unlockReadLock();
        }
    }

    @Test
    @DisplayName("moves its own timestamp and clears its own guard")
    void testTheTimestampAndTheGuard(Env env) {
        final FalcoInstance instance = registered(env);
        final BlockWriter writer = instance.blockWriter();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final long before = writer.lastChangeTime();

        writer.write(chunk, 0, Y, 0, Block.STONE, null, null, false, 0);
        assertNotEquals(before, writer.lastChangeTime());

        writer.endTick();
        writer.write(chunk, 0, Y, 0, Block.STONE, null, null, false, 0);
        chunk.lockReadLock();
        try {
            assertEquals(Block.STONE, chunk.getBlock(0, Y, 0));
        } finally {
            chunk.unlockReadLock();
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :falco-instance:test --tests "*BlockWriterTest*"
```

Expected: compilation failure — `BlockWriter` and `FalcoInstance#blockWriter()` do not exist.

- [ ] **Step 3: Write the part and point `FalcoInstance` at it**

`BlockWriter` holds `private final FalcoInstance owner;`, `private final Map<BlockVec, Block> currentlyChangingBlocks = new ConcurrentHashMap<>();` and `private volatile long lastBlockChangeTime = System.nanoTime();`. The six method bodies move from the lines named above, with `this` replaced by `this.owner` wherever the instance is meant — in `new ChunkCache(this.owner, null, null)`, in `new BlockPlacementRule.PlacementState(this.owner, …)`, in `new InstanceBlockUpdateEvent(this.owner, …)` and in `new BlockHandler.PlayerDestroy(…, this.owner, …)` — and `requireFalcoChunk(chunk)` replaced by `FalcoChunk.require(chunk)`. The class javadoc takes over the paragraph of `writeBlock:394-400` about why only the chunk lock is held, because that paragraph is the reason this class exists as a separate thing at all.

`endTick()` is `this.currentlyChangingBlocks.clear();` with the javadoc from `FalcoInstance#tick:1254-1266` about the recursion guard being scoped to a single tick.

In `FalcoInstance`, the five delegations:

```java
    @Override
    public void setBlock(int x, int y, int z, Block block, boolean doBlockUpdates) {
        this.blockWriter.setBlock(x, y, z, block, doBlockUpdates);
    }

    @Override
    public boolean placeBlock(BlockHandler.Placement placement, boolean doBlockUpdates) {
        return this.blockWriter.placeBlock(placement, doBlockUpdates);
    }

    @Override
    public boolean breakBlock(Player player, Point blockPosition, BlockFace blockFace, boolean doBlockUpdates) {
        return this.blockWriter.breakBlock(player, blockPosition, blockFace, doBlockUpdates);
    }

    public long getLastBlockChangeTime() {
        return this.blockWriter.lastChangeTime();
    }

    public void refreshLastBlockChangeTime() {
        this.blockWriter.refreshLastChangeTime();
    }

    @Override
    public void tick(long time) {
        super.tick(time);
        this.blockWriter.endTick();
    }
```

plus `public BlockWriter blockWriter()` next to `registry()` and `lifecycle()`.

- [ ] **Step 4: Run everything**

```bash
./gradlew :falco-instance:test
```

Expected: PASS, 176. `FalcoInstanceBlockWriteTest` from Task 1 is the net and every one of its ten cases has to stay green.

- [ ] **Step 5: Prove the lock discipline survived**

Move `chunk.unlockWriteLock()` from the `finally` block to the end of `write`, so the lock is held across the neighbour pass. `BlockWriterTest#testTheChunkLockIsReleased` fails; `FalcoInstanceBlockWriteTest#testANeighbourReshapesItself` fails or hangs. Restore it. Before this stage neither existed, and the change would have been invisible.

- [ ] **Step 6: Commit**

```bash
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/BlockWriter.java \
        falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/BlockWriterTest.java
git commit -m "refactor(instance): give the block write path a class of its own"
```

---

### Task 7: The facade holds no state

**Files:**
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/InstanceFacadeTest.java`

**Interfaces:**
- Consumes: the four parts of Tasks 2, 3, 5 and 6, plus `ChunkGeneration` from Task 4.
- Produces: `FalcoInstance` with exactly four declared instance fields, and nothing else.

**Why this is a task and not a review note.** §8 of the spec lists it as the open question of this stage: *whether the facade split can stay thin, or whether it re-accumulates state, can only be judged once written.* A judgement nobody can repeat is not an answer. This task turns it into an assertion that runs on every build.

**The one field that has to go.** After Task 6, `FalcoInstance` still declares `registries:195` — handed to `ChunkGeneration` in the constructor and never read again. It is deleted; the constructor passes its parameter straight through. `ChunkGeneration` is reached through `ChunkLifecycle`, which is the only thing that generates, so it is not a fifth field of the facade: `ChunkLifecycle` holds it.

- [ ] **Step 1: Write the failing test**

```java
package net.onelitefeather.falco.instance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that {@link FalcoInstance} is a facade and not the class it replaced with delegation in
 * front of it.
 * <p>
 * §4.3 of the design says it in one sentence: <em>the facade must hold no state of its own, or it is
 * the same class with delegation in front of it</em>. §8 lists whether that holds as the open question
 * of this stage. A question that can only be answered by a person reading the file is answered again
 * every time somebody reads it, and differently; this class answers it once per build.
 * </p>
 *
 * <h2>Why this is reflection, and why that is allowed here</h2>
 * <p>
 * NFR-001 forbids reflection in the modules, so that they run without {@code --add-opens} and without
 * an open module. It says nothing about a test, and this repository already reads private fields of a
 * foreign library in {@code JolMeasurement} for a reason of the same shape: the property being
 * checked is a property of the declaration, and nothing but the declaration can be asked about it.
 * The alternative — a JOL walk of the shallow size — was rejected because it cannot tell a fifth
 * reference field from padding, which is exactly the blind spot the stage 2 result had to write down
 * about {@code ChunkFootprintTest}.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@DisplayName("The instance facade")
class InstanceFacadeTest {

    /**
     * The four types a field of the facade is allowed to have.
     */
    private static final Set<Class<?>> PARTS = Set.of(
            ChunkRegistry.class, ChunkLifecycle.class, BlockWriter.class, ChunkPersistence.class);

    /**
     * Returns every instance field the facade declares itself, ignoring what it inherits.
     *
     * @return the declared, non-static fields of the facade
     */
    private static List<Field> declaredFields() {
        return java.util.Arrays.stream(FalcoInstance.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .filter(field -> !field.isSynthetic())
                .toList();
    }

    @Test
    @DisplayName("declares exactly the four parts it delegates to")
    void testTheFacadeDeclaresOnlyItsParts() {
        final List<Field> fields = declaredFields();
        final String names = fields.stream()
                .map(field -> field.getType().getSimpleName() + " " + field.getName())
                .collect(Collectors.joining(", "));

        assertEquals(PARTS.size(), fields.size(),
                "the facade may hold one reference per part and nothing else, but it declares: " + names);
        for (Field field : fields) {
            assertTrue(PARTS.contains(field.getType()),
                    "the facade declares a field of type " + field.getType().getName() + " named "
                            + field.getName() + ", which is state of its own rather than a part; either it "
                            + "belongs in one of " + PARTS + " or the split of stage 3 has been undone");
        }
        assertEquals(PARTS,
                fields.stream().map(Field::getType).collect(Collectors.toUnmodifiableSet()),
                "every part has to be reachable from the facade, and each exactly once");
    }

    @Test
    @DisplayName("declares every one of them final")
    void testTheFacadeCannotSwapItsParts() {
        for (Field field : declaredFields()) {
            assertTrue(Modifier.isFinal(field.getModifiers()),
                    "the field " + field.getName() + " is not final; a part that can be replaced at "
                            + "runtime is a part two threads can disagree about");
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :falco-instance:test --tests "*InstanceFacadeTest*"
```

Expected: **failure naming `Registries registries`** — the field Task 4 left behind. That failure is the test doing its job on its first run, which is worth more than a green one.

- [ ] **Step 3: Delete the field**

Remove `registries:195` from `FalcoInstance` and hand the constructor parameter straight to `new ChunkGeneration(registries, this::getChunkAt)`. The constructor ends as:

```java
    public FalcoInstance(Registries registries, UUID uuid, RegistryKey<DimensionType> dimensionType,
                         @Nullable ChunkLoader loader, Key dimensionName) {
        super(registries, uuid, dimensionType, dimensionName);
        this.registry = new ChunkRegistry();
        this.persistence = new ChunkPersistence(loader);
        this.blockWriter = new BlockWriter(this);
        this.lifecycle = new ChunkLifecycle(this, this.registry, this.persistence,
                new ChunkGeneration(registries, this::getChunkAt));
        // Last, and outside every constructor above: loadInstance may call back into this instance,
        // and a callback into an object whose parts are not all built yet reads one of them as null.
        this.persistence.loader().loadInstance(this);
    }
```

`lastBlockChangeTime` was initialised here before and now lives in `BlockWriter`'s field initialiser, which is the same value at a slightly earlier moment and is only ever read as a delta.

- [ ] **Step 4: Rewrite the class javadoc of `FalcoInstance`**

The current one describes a class that does the work. It now describes a facade, and it has to say four things: what the four parts are and where the line between them runs; that the class holds nothing else and that `InstanceFacadeTest` is what keeps that true; that the four `instanceof InstanceContainer` branches of Minestom still apply unchanged; and that `unregister(InstanceManager)` is still the reason the class exists. Everything the old comment says about the chunk lock, the generator staging and the publish/unload exclusivity moves to the part that now owns it — that is not a deletion, it is the comment following its code. Raise `@version` to `2.0.0`: the constructor is unchanged but `getChunkLoader`, `setChunkLoader` and every accessor now delegate, and three new public accessors exist.

- [ ] **Step 5: Run everything**

```bash
./gradlew :falco-instance:test
./gradlew :falco-instance:javadoc
```

Expected: PASS, 178, and a javadoc run without a single warning — `-Werror` is on and every new public member of Tasks 2 to 6 is public API now.

- [ ] **Step 6: Prove the assertion bites**

Add `private final Map<Long, Chunk> shortcut = new ConcurrentHashMap<>();` to `FalcoInstance`, run `InstanceFacadeTest`, and see it fail by name and by type. Then make one of the four fields non-final and see the second case fail. Remove both. A structural test that nobody has watched fail is a structural test nobody knows the shape of.

- [ ] **Step 7: Record the line count**

```bash
wc -l falco-instance/src/main/java/net/onelitefeather/falco/instance/*.java
```

Write the numbers into the commit message. The stage began with one file of 1 272 lines; the point is not that the total shrinks — it will not, because every new type carries its own javadoc — but that no single file is the place where five responsibilities meet.

- [ ] **Step 8: Commit**

```bash
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/InstanceFacadeTest.java
git commit -m "refactor(instance)!: make the instance a facade and assert that it stays one"
```

---

### Task 8: `ChunkLifecycleListener` — US-3.03 and US-3.04

**Files:**
- Create: `falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkLifecycleListener.java`
- Create: `falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkLifecycleEvent.java`
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoChunk.java`
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkLifecycle.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/ChunkLifecycleListenerTest.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/ChunkLifecycleAllocationTest.java`
- Modify: `falco-benchmarks/src/test/java/net/onelitefeather/falco/benchmark/instance/ChunkFootprintTest.java`

**Interfaces:**
- Consumes: `FalcoChunk`, `ChunkLifecycle` as Tasks 5 and 7 left them.
- Produces:
  - `interface ChunkLifecycleListener` with `default void onLoad(ChunkLifecycleEvent)`, `onPublish(ChunkLifecycleEvent)`, `onTick(ChunkLifecycleEvent)`, `onUnload(ChunkLifecycleEvent)`, `default void onBlockChange(FalcoChunk chunk, int x, int y, int z, Block block)`, and `static ChunkLifecycleListener of(ChunkLifecycleListener first, ChunkLifecycleListener second)`
  - `record ChunkLifecycleEvent(FalcoChunk chunk, long time)`
  - `FalcoChunk#addLifecycleListener(ChunkLifecycleListener)`, `FalcoChunk#lifecycleListener()`, `FalcoChunk#notifyPublished()`
  - `ChunkLifecycle#addListener(ChunkLifecycleListener)`, `ChunkLifecycle#listener()`

  Task 10 depends on all of them.

**The correction this task makes to the design.** §4.5 names four hooks, `onLoad`, `onPublish`, `onTick` and `onUnload`, and says they replace the four `FalcoLightingChunk` occupies by inheritance. Reading `FalcoLightingChunk` shows five overrides, not four: `setBlock`, `onLoad`, `tick`, `invalidate` and `onLightUpdated`. Two of them are not lifecycle transitions at all.

- `setBlock` is where light learns *which block* moved, and that is the difference between replaying one position and searching nine chunks — `FalcoLightingChunk:128` hands the coordinates to `markChanged`. It becomes a fifth listener method, `onBlockChange`, and it takes primitives rather than an event, because it runs once per block write and an event per write would be an allocation on the hottest path this module has.
- `invalidate` and `onLightUpdated` need per-chunk state — a `CachedPacket` — and a listener registered once for a whole instance has nowhere to put it. They stay on the chunk class, which Task 10 keeps for exactly that reason.

The listener is therefore five methods, four of which carry an event and one of which does not, and the asymmetry is the measurement talking rather than taste.

**Why a single nullable reference and not a list.** A `List<ChunkLifecycleListener>` costs an object per chunk whether or not anybody listens, and an enhanced-for over it allocates an iterator per transition. One reference field costs four bytes and composes through `of`, which nests two listeners into one and allocates once, at registration. `FalcoChunk` is the class stage 2 got down to 25 objects and 840 bytes; a list per chunk would give a quarter of that back for a feature almost no chunk uses.

- [ ] **Step 1: Write the failing tests**

`ChunkLifecycleListenerTest` — US-3.03:

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Establishes that a chunk can carry more than one lifecycle extension, which is US-3.03.
 * <p>
 * Before this stage a chunk had exactly one extension point and it was its superclass, so
 * {@code FalcoLightingChunk} occupied it and nothing else could be installed beside light. Two
 * listeners on one chunk, both notified on every transition, is the shape that removes that limit.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The lifecycle listeners of a chunk")
class ChunkLifecycleListenerTest {

    /**
     * A listener which writes down what it was told, in order.
     */
    private static final class Recording implements ChunkLifecycleListener {

        /**
         * The name this listener writes in front of every entry.
         */
        private final String name;

        /**
         * Where the entries go.
         */
        private final List<String> log;

        /**
         * Creates a recording listener.
         *
         * @param name the name of this listener
         * @param log  where the entries go
         */
        private Recording(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public void onPublish(ChunkLifecycleEvent event) {
            this.log.add(this.name + ":publish:" + event.chunk().getChunkX());
        }

        @Override
        public void onLoad(ChunkLifecycleEvent event) {
            this.log.add(this.name + ":load");
        }

        @Override
        public void onTick(ChunkLifecycleEvent event) {
            this.log.add(this.name + ":tick:" + event.time());
        }

        @Override
        public void onUnload(ChunkLifecycleEvent event) {
            this.log.add(this.name + ":unload");
        }

        @Override
        public void onBlockChange(FalcoChunk chunk, int x, int y, int z, Block block) {
            this.log.add(this.name + ":block:" + x + "/" + y + "/" + z);
        }
    }

    /**
     * Creates a registered instance in the environment of the test.
     *
     * @param env the environment which provides the server process
     * @return the registered instance
     */
    private static FalcoInstance registered(Env env) {
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);
        return instance;
    }

    @Test
    @DisplayName("notifies both listeners on every transition, in registration order")
    void testTwoListenersBothHearEverything(Env env) {
        final FalcoInstance instance = registered(env);
        final List<String> log = new ArrayList<>();
        instance.lifecycle().addListener(new Recording("first", log));
        instance.lifecycle().addListener(new Recording("second", log));

        final Chunk chunk = instance.loadChunk(0, 0).join();
        chunk.lockWriteLock();
        try {
            FalcoChunk.require(chunk).setBlock(1, 64, 1, Block.STONE, null, null);
        } finally {
            chunk.unlockWriteLock();
        }
        chunk.tick(7L);
        instance.unloadChunk(chunk);

        assertEquals(List.of(
                "first:publish:0", "second:publish:0",
                "first:load", "second:load",
                "first:block:1/64/1", "second:block:1/64/1",
                "first:tick:7", "second:tick:7",
                "first:unload", "second:unload"), log);
    }

    @Test
    @DisplayName("holds no listener until one is registered")
    void testAChunkStartsWithoutAListener(Env env) {
        final FalcoInstance instance = registered(env);

        assertNull(new FalcoChunk(instance, 0, 0).lifecycleListener(),
                "a chunk nobody listens to has to hold null, not an empty composite");
        assertNull(instance.lifecycle().listener());
    }

    @Test
    @DisplayName("keeps the single listener single when there is only one")
    void testOneListenerIsNotWrapped(Env env) {
        final FalcoInstance instance = registered(env);
        final ChunkLifecycleListener only = new Recording("only", new ArrayList<>());

        instance.lifecycle().addListener(only);

        assertSame(only, instance.lifecycle().listener(),
                "one listener composes with nothing, so it has to be stored as it is");
    }

    @Test
    @DisplayName("gives a chunk of a plain container a listener too")
    void testAChunkCanCarryItsOwnListener(Env env) {
        final FalcoInstance instance = registered(env);
        final List<String> log = new ArrayList<>();
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);

        chunk.addLifecycleListener(new Recording("own", log));
        chunk.tick(3L);

        assertEquals(List.of("own:tick:3"), log,
                "the listener lives on the chunk, so a chunk outside a Falco instance can carry one");
    }
}
```

`ChunkLifecycleAllocationTest` — US-3.04, and the whole point of it is that it measures both arms:

```java
package net.onelitefeather.falco.instance;

import com.sun.management.ThreadMXBean;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.management.ManagementFactory;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Counts what a lifecycle transition allocates, with no listener and with one, which is US-3.04.
 * <p>
 * The requirement is that a chunk nobody listens to pays nothing per transition. That is a claim
 * about an allocation, and an allocation is measured rather than argued: the two arms below run the
 * identical loop and differ only in whether a listener is installed, and the difference between them
 * is the cost of the event.
 * </p>
 *
 * <h2>Why the listener arm has to publish the event</h2>
 * <p>
 * A test which only measured the null arm would pass against an implementation that allocates an
 * event on every transition, as long as escape analysis noticed that nothing escaped and deleted the
 * allocation. The listener below therefore writes the event into a {@code static volatile} field,
 * which no compiler may remove, so the second arm is a positive control: if it does not allocate, the
 * measurement itself is broken and the first arm proves nothing.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("What a lifecycle transition allocates")
class ChunkLifecycleAllocationTest {

    /**
     * How many transitions each arm performs.
     */
    private static final int TRANSITIONS = 200_000;

    /**
     * How many transitions are run before the measurement, so both arms are compiled.
     */
    private static final int WARMUP = 50_000;

    /**
     * Where the listener arm publishes its events, so that nothing can be optimised away.
     */
    private static volatile Object sink;

    /**
     * Ticks a chunk the given number of times and reports what the calling thread allocated.
     *
     * @param chunk the chunk to tick
     * @param times how often to tick it
     * @return the bytes the calling thread allocated during the loop
     */
    private static long allocatedWhileTicking(FalcoChunk chunk, int times) {
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long before = threads.getCurrentThreadAllocatedBytes();

        for (int index = 0; index < times; index++) {
            chunk.tick(index);
        }
        return threads.getCurrentThreadAllocatedBytes() - before;
    }

    @Test
    @DisplayName("costs nothing without a listener and one event with one")
    void testTheEventIsBuiltOnlyWhenSomebodyListens(Env env) {
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported(),
                "this JVM cannot report per thread allocation, so the question cannot be answered here");
        threads.setThreadAllocatedMemoryEnabled(true);

        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);
        final FalcoChunk silent = new FalcoChunk(instance, 0, 0);
        final FalcoChunk heard = new FalcoChunk(instance, 1, 0);
        heard.addLifecycleListener(new ChunkLifecycleListener() {

            @Override
            public void onTick(ChunkLifecycleEvent event) {
                sink = event;
            }
        });

        allocatedWhileTicking(silent, WARMUP);
        allocatedWhileTicking(heard, WARMUP);
        final long withoutListener = allocatedWhileTicking(silent, TRANSITIONS);
        final long withListener = allocatedWhileTicking(heard, TRANSITIONS);

        System.out.printf("lifecycle transitions: %,d without a listener -> %,d B (%.3f B each)%n",
                TRANSITIONS, withoutListener, (double) withoutListener / TRANSITIONS);
        System.out.printf("lifecycle transitions: %,d with one listener  -> %,d B (%.3f B each)%n",
                TRANSITIONS, withListener, (double) withListener / TRANSITIONS);

        assertTrue(withListener >= 16L * TRANSITIONS,
                "the positive control failed: a listener that stores its event has to allocate one per "
                        + "transition, but the arm with a listener allocated " + withListener
                        + " B over " + TRANSITIONS + " transitions, so this measurement cannot see "
                        + "allocations at all and its other half proves nothing");
        assertTrue(withoutListener < TRANSITIONS,
                "a chunk nobody listens to allocated " + withoutListener + " B over " + TRANSITIONS
                        + " transitions, which is more than a byte each: the event is being built before "
                        + "the listener is checked");
    }
}
```

- [ ] **Step 2: Run both and watch them fail**

```bash
./gradlew :falco-instance:test --tests "*ChunkLifecycle*Test*"
```

Expected: compilation failure — neither type exists.

- [ ] **Step 3: Write the event and the listener**

```java
package net.onelitefeather.falco.instance;

import org.jetbrains.annotations.ApiStatus;

/**
 * The {@link ChunkLifecycleEvent} record is what a {@link ChunkLifecycleListener} is told about a
 * transition of a chunk.
 * <p>
 * It is a record with two components rather than four method parameters because a transition will
 * grow things worth reporting and a parameter list cannot. It is built by the chunk, once per
 * transition, and <em>only</em> when a listener is installed — {@code FalcoChunk} checks the listener
 * field before it constructs anything, which is what makes a chunk nobody listens to free.
 * {@code ChunkLifecycleAllocationTest} measures both halves of that sentence.
 * </p>
 * <p>
 * The instance is not a component: it is {@code chunk.getInstance()} and duplicating it would make
 * the record wider for every transition to save one call on the few that need it.
 * </p>
 *
 * @param chunk the chunk the transition happened to
 * @param time  the tick time in milliseconds for {@link ChunkLifecycleListener#onTick}, and
 *              {@code 0} for every other transition, because the other three do not happen at a tick
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public record ChunkLifecycleEvent(FalcoChunk chunk, long time) {
}
```

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * The {@link ChunkLifecycleListener} interface is how something is told what happens to a chunk,
 * without being that chunk.
 * <p>
 * Before this interface a chunk had exactly one extension point and it was its superclass.
 * {@code FalcoLightingChunk} occupied it, which is why Falco's light and Falco's instance could not
 * be used together at all — {@code FalcoChunk} and {@code FalcoLightingChunk} both extended
 * {@code DynamicChunk}, a class has one superclass, and a server had to pick one of the two. A
 * listener is a field, and a field composes.
 * </p>
 *
 * <h2>Why the block change is not an event</h2>
 * <p>
 * Four of these five methods happen once in the life of a chunk or once per tick, and they carry a
 * {@link ChunkLifecycleEvent}. {@link #onBlockChange} happens once per block written and takes
 * primitives, because an event object there would be an allocation on the hottest path of this
 * module. The asymmetry is deliberate and it is measured rather than argued: see
 * {@code ChunkLifecycleAllocationTest}.
 * </p>
 * <p>
 * Every method is a default doing nothing, so a listener implements what it cares about. Every one of
 * them runs on the thread that caused the transition, under whatever lock that thread holds — a
 * listener which blocks blocks a chunk load, a tick or a block write.
 * </p>
 * <p>
 * This type is experimental. The instance module is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public interface ChunkLifecycleListener {

    /**
     * Reports that a chunk has become part of its instance and has a tick partition.
     * <p>
     * Fired after the position of the chunk was released and therefore outside the lock of that
     * position, which is what makes it safe for a listener to call back into the instance.
     * </p>
     *
     * @param event what happened, to which chunk
     */
    default void onPublish(ChunkLifecycleEvent event) {
    }

    /**
     * Reports that a chunk has finished loading and is now reported as loaded.
     *
     * @param event what happened, to which chunk
     */
    default void onLoad(ChunkLifecycleEvent event) {
    }

    /**
     * Reports that a chunk was ticked.
     * <p>
     * Fired on every tick of the chunk, before the block handlers of that chunk run and regardless of
     * whether the chunk holds any, so a listener which needs a heartbeat gets one from every chunk
     * rather than only from the ones that carry a block entity.
     * </p>
     *
     * @param event what happened, to which chunk, and at which tick time
     */
    default void onTick(ChunkLifecycleEvent event) {
    }

    /**
     * Reports that a chunk is no longer part of its instance.
     *
     * @param event what happened, to which chunk
     */
    default void onUnload(ChunkLifecycleEvent event) {
    }

    /**
     * Reports that one block of a chunk was written.
     * <p>
     * Fired after the block is in the storage and after the handlers of the old and the new block
     * ran, holding the write lock of the chunk. The position is world coordinates, as the chunk
     * received them.
     * </p>
     *
     * @param chunk the chunk which received the block
     * @param x     the block X
     * @param y     the block Y
     * @param z     the block Z
     * @param block the block which was written
     */
    default void onBlockChange(FalcoChunk chunk, int x, int y, int z, Block block) {
    }

    /**
     * Composes two listeners into one which notifies both, in order.
     * <p>
     * Composition rather than a list because a list is an object per chunk and an iterator per
     * transition, and almost every chunk of a world has no listener at all. Two listeners nest into
     * one object, three into two, and the allocation happens once, at registration.
     * </p>
     *
     * @param first  the listener notified first
     * @param second the listener notified second
     * @return a listener which notifies both
     */
    static ChunkLifecycleListener of(ChunkLifecycleListener first, ChunkLifecycleListener second) {
        Objects.requireNonNull(first, "the first listener cannot be null");
        Objects.requireNonNull(second, "the second listener cannot be null");
        return new ChunkLifecycleListener() {

            @Override
            public void onPublish(ChunkLifecycleEvent event) {
                first.onPublish(event);
                second.onPublish(event);
            }

            @Override
            public void onLoad(ChunkLifecycleEvent event) {
                first.onLoad(event);
                second.onLoad(event);
            }

            @Override
            public void onTick(ChunkLifecycleEvent event) {
                first.onTick(event);
                second.onTick(event);
            }

            @Override
            public void onUnload(ChunkLifecycleEvent event) {
                first.onUnload(event);
                second.onUnload(event);
            }

            @Override
            public void onBlockChange(FalcoChunk chunk, int x, int y, int z, Block block) {
                first.onBlockChange(chunk, x, y, z, block);
                second.onBlockChange(chunk, x, y, z, block);
            }
        };
    }
}
```

- [ ] **Step 4: Wire it into `FalcoChunk`**

One field, one adder, one reader, and five notification points. Every one of them checks the field before it builds anything:

```java
    /**
     * What is told about the transitions of this chunk, null while nobody listens.
     * <p>
     * One reference and not a list. A list is an object per chunk and an iterator per transition, and
     * a fresh chunk of this class retains 840 bytes in total — a per-chunk collection for a feature
     * almost no chunk uses would give back a quarter of what stage 2 bought. More than one listener
     * composes through {@link ChunkLifecycleListener#of}, which allocates once, at registration.
     * </p>
     * <p>
     * Volatile because a listener may be installed by the thread that loads a chunk and read by the
     * thread that ticks it.
     * </p>
     */
    private volatile @Nullable ChunkLifecycleListener lifecycleListener;

    /**
     * Adds a listener to this chunk.
     *
     * @param listener the listener to add
     * @since 0.4.0
     */
    public void addLifecycleListener(ChunkLifecycleListener listener) {
        final ChunkLifecycleListener current = this.lifecycleListener;
        this.lifecycleListener = current == null ? Objects.requireNonNull(listener,
                "the listener cannot be null") : ChunkLifecycleListener.of(current, listener);
    }

    /**
     * Hands out what is told about the transitions of this chunk.
     *
     * @return the listener of this chunk, or null if nothing listens
     * @since 0.4.0
     */
    public @Nullable ChunkLifecycleListener lifecycleListener() {
        return this.lifecycleListener;
    }

    /**
     * Tells the chunk that it has become part of its instance.
     * <p>
     * Separate from {@link #markLoaded()} because publishing and finishing a load are two different
     * moments: a chunk is in the registry and has a tick partition before its loaded flag is set, and
     * a listener that wants to see the world exactly as the instance does needs the first, not the
     * second.
     * </p>
     *
     * @since 0.4.0
     */
    public void notifyPublished() {
        final ChunkLifecycleListener listener = this.lifecycleListener;
        if (listener != null) listener.onPublish(new ChunkLifecycleEvent(this, 0L));
    }
```

`markLoaded()` and `markUnloaded()` gain the same two lines with `onLoad` and `onUnload`. `tick(long)` notifies **before** its early exit:

```java
    @Override
    public void tick(long time) {
        final ChunkLifecycleListener listener = this.lifecycleListener;
        // Before the early exit, not after: a listener which wants a heartbeat has to get one from
        // every chunk, and almost every chunk has no tickable block at all.
        if (listener != null) listener.onTick(new ChunkLifecycleEvent(this, time));
        if (this.tickableCount == 0) return;
        …
    }
```

and `setBlock` ends with:

```java
        final ChunkLifecycleListener listener = this.lifecycleListener;
        if (listener != null) listener.onBlockChange(this, x, y, z, block);
```

placed **after** the two heightmap refreshes, so a listener reading the chunk sees the finished state.

In `ChunkLifecycle`, one field, `addListener` composing into it, and two lines: `create` calls `falcoChunk.addLifecycleListener(current)` when the lifecycle has one, and `publish` calls `chunk.notifyPublished()` after the registry returned true and released the position.

- [ ] **Step 5: Run the two tests, then the module**

```bash
./gradlew :falco-instance:test --tests "*ChunkLifecycle*Test*"
./gradlew :falco-instance:test
```

Expected: PASS. `ChunkLifecycleAllocationTest` prints two lines; the first has to be `0.000 B each` or very close to it, the second at least 16 B each.

- [ ] **Step 6: Prove both arms of the allocation test**

Move the event construction in `tick` in front of the null check — `final ChunkLifecycleEvent event = new ChunkLifecycleEvent(this, time); if (listener != null) listener.onTick(event);` — and watch the null arm go red with roughly 24 B per transition. Restore it. Then change the test's listener to ignore its event instead of storing it, and watch the positive control go red because escape analysis removed the allocation. Restore that too. A test that measures nothing looks exactly like a test that measured zero.

- [ ] **Step 7: Re-measure the chunk footprint and re-declare it**

```bash
./gradlew :falco-benchmarks:test --tests "*ChunkFootprintTest*" -i
```

`FalcoChunk` has one reference field more than it had. Two assertions can move:

1. the per-class difference table, if the extra field pushed the shallow size of the chunk up;
2. `assertEquals(ClassLayout.parseInstance(minestomChunk).instanceSize(), ClassLayout.parseInstance(falcoChunk).instanceSize(), …)`, which demands that `FalcoChunk` and `DynamicChunk` weigh the same as objects.

If either goes red, **update the declared number and write down why**, in the test's own javadoc: one reference field for the lifecycle listener, four bytes under compressed references, and what it bought. Do not widen a comparison into a tolerance — the stage 2 result already records that a `boolean` field is invisible to this test, and a tolerance would make a reference field invisible too. Raise the `@version` of `ChunkFootprintTest`.

- [ ] **Step 8: Commit**

```bash
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkLifecycleListener.java \
        falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkLifecycleEvent.java \
        falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoChunk.java \
        falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkLifecycle.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/ChunkLifecycleListenerTest.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/ChunkLifecycleAllocationTest.java \
        falco-benchmarks/src/test/java/net/onelitefeather/falco/benchmark/instance/ChunkFootprintTest.java
git commit -m "feat(instance): let a chunk carry more than one lifecycle extension"
```

---

### Task 9: The viewer cache entry goes with the chunk — US-3.01

**Files:**
- Create: `falco-instance/src/main/java/net/minestom/server/instance/ChunkViewerCache.java`
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkLifecycle.java`
- Test: `falco-instance/src/test/java/net/minestom/server/instance/ChunkViewerCacheTest.java`
- Modify: `falco-benchmarks/src/test/java/net/minestom/server/instance/ChunkViewerCacheLeakTest.java`

**Interfaces:**
- Consumes: `EntityTrackerImpl.targetEntries`, `EntityTrackerImpl.TargetEntry#viewers`, `EntityTrackerImpl.ChunkViewKey` — all package-private members of `net.minestom.server.instance`.
- Produces: `static boolean ChunkViewerCache.release(Instance instance, int chunkX, int chunkZ)` and `static int ChunkViewerCache.size(Instance instance)`.

**What is actually being fixed.** M14 of the spec measures an `InstanceContainer` leaking one viewer cache entry per chunk **construction**, linear and unbounded, because `InstanceContainer#getSharedInstances` hands out a fresh `unmodifiableList` every time and `ChunkViewKey#equals` compares that list by identity. A `FalcoInstance` is not an `InstanceContainer`, receives the `List.of()` singleton and therefore escapes the growth — by accident, not by design. What it does not escape is the entry itself: one per chunk position, created when the first chunk there is constructed, never removed, alive for the life of the process. `ChunkViewerCacheLeakTest` already says so in its own javadoc: *that is a far smaller quantity than one per construction, and it is not nothing.*

**Why a class in Minestom's package.** `EntityTracker#viewable` is `computeIfAbsent` and has no counterpart; there is no public way to remove an entry. The map, its key type and `EntityTrackerImpl` itself are package-private (`EntityTrackerImpl.java:31, :252, :269`), which makes them reachable from a class declared in `net.minestom.server.instance` and from nowhere else without reflection. `ChunkViewerCacheLeakTest` has done exactly this since stage 1; this task moves the same technique from a test into the module.

**What that costs, stated rather than discovered later.** `falco-instance.jar` then contains a package that also exists in `minestom.jar`. On the classpath — which is how every consumer of this repository runs today, and how Minestom's own test harness runs — a split package is invisible and package-private access works, because both jars land in the same runtime package of the same classloader. On the module path it is fatal: Minestom ships a `module-info.java` and two modules may not export the same package. Falco has no `module-info.java`, so this changes nothing that works today, and it closes the door on Falco ever becoming a named module without moving this class. That sentence belongs in the class comment.

- [ ] **Step 1: Write the failing test**

```java
package net.minestom.server.instance;

import net.minestom.server.world.DimensionType;
import net.onelitefeather.falco.instance.FalcoInstance;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Establishes that a chunk which is unloaded takes its viewer cache entry with it, which is US-3.01.
 * <p>
 * The entry is created by the constructor of {@code Chunk} ({@code Chunk.java:74-76}), which asks the
 * entity tracker of the instance for a viewable and gets one out of a
 * {@code computeIfAbsent}. Nothing in Minestom ever removes it: not unloading the chunk, not dropping
 * the last reference to it, not unregistering the instance. A world which streams chunks in and out
 * therefore accumulates one entry per position ever visited, for the life of the process.
 * </p>
 * <p>
 * This test lives in {@code net.minestom.server.instance} for the same reason the class it tests
 * does: the map is package-private and reading it from anywhere else would need reflection.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The viewer cache entry of a chunk")
class ChunkViewerCacheTest {

    /**
     * Creates a registered instance in the environment of the test.
     *
     * @param env the environment which provides the server process
     * @return the registered instance
     */
    private static FalcoInstance registered(Env env) {
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);
        return instance;
    }

    @Test
    @DisplayName("is created by the chunk constructor and removed by the release")
    void testTheEntryCanBeReleased(Env env) {
        final FalcoInstance instance = registered(env);
        final int before = ChunkViewerCache.size(instance);

        new net.onelitefeather.falco.instance.FalcoChunk(instance, 4, 4);
        assertEquals(before + 1, ChunkViewerCache.size(instance),
                "constructing a chunk has to leave exactly one entry behind, or this test is measuring "
                        + "something other than the leak it is named after");

        assertTrue(ChunkViewerCache.release(instance, 4, 4));
        assertEquals(before, ChunkViewerCache.size(instance));
    }

    @Test
    @DisplayName("reports that there was nothing to release when there was not")
    void testReleasingNothing(Env env) {
        final FalcoInstance instance = registered(env);

        assertFalse(ChunkViewerCache.release(instance, 77, 77),
                "no chunk was ever built at that position, so no entry can be removed");
    }

    @Test
    @DisplayName("leaves the cache where it found it across a load and an unload")
    void testALoadAndUnloadCycleIsNeutral(Env env) {
        final FalcoInstance instance = registered(env);
        final int before = ChunkViewerCache.size(instance);

        for (int round = 0; round < 32; round++) {
            instance.unloadChunk(instance.loadChunk(round, 0).join());
        }

        assertEquals(before, ChunkViewerCache.size(instance),
                "thirty-two load and unload cycles have to leave the cache exactly as they found it");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :falco-instance:test --tests "*ChunkViewerCacheTest*"
```

Expected: compilation failure — `ChunkViewerCache` does not exist.

- [ ] **Step 3: Write the class**

```java
package net.minestom.server.instance;

import net.minestom.server.entity.Entity;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * The {@link ChunkViewerCache} class removes the viewer cache entry a chunk leaves behind, which
 * Minestom offers no way to do.
 * <p>
 * The constructor of {@code Chunk} asks the entity tracker of its instance for a {@code Viewable}
 * and receives it out of a {@code computeIfAbsent} keyed by the chunk position
 * ({@code Chunk.java:74-76}, {@code EntityTrackerImpl.java:207-210}). Nothing removes that entry
 * again — not unloading the chunk, not dropping the last reference to it, not unregistering the
 * instance — so a world which streams chunks accumulates one entry per position it has ever visited
 * and keeps them until the process ends.
 * </p>
 *
 * <h2>Why this class lives in a package of Minestom</h2>
 * <p>
 * {@code EntityTracker#viewable(List, int, int)} is the only public door to that map and it only
 * inserts. The map itself ({@code EntityTrackerImpl.TargetEntry#viewers}), its key type
 * ({@code EntityTrackerImpl.ChunkViewKey}) and {@code EntityTrackerImpl} are all package-private, so
 * a class declared in {@code net.minestom.server.instance} can reach them and nothing else can
 * without reflection — which NFR-001 forbids, and which would break on the first JDK that closes the
 * door.
 * </p>
 * <p>
 * The price is a split package: this jar carries a package that {@code minestom.jar} also carries. On
 * the classpath that is invisible and package-private access works, because both jars land in the
 * same runtime package of the same classloader; on the module path it is fatal, because Minestom is a
 * named module and two modules may not own one package. Falco declares no module and neither does
 * anything that consumes it, so nothing that works today changes. What this does close is the option
 * of Falco becoming a named module while this class stays where it is.
 * </p>
 *
 * <h2>What it does not fix</h2>
 * <p>
 * An {@code InstanceContainer} hands the tracker a fresh {@code unmodifiableList} of its shared
 * instances on every chunk construction, and {@code ChunkViewKey#equals} compares that list by
 * identity, so no key built here can ever match one of its entries. The unbounded growth of a
 * container is not reachable from the outside and is not addressed. What is addressed is the bounded
 * entry a {@code FalcoInstance} leaves per position, which is the one this repository is responsible
 * for.
 * </p>
 * <p>
 * A second live chunk at the same position — a copy, for instance — holds its own reference to the
 * view and keeps working after the entry is gone; the next chunk constructed there simply receives a
 * new one. The view is derived from the tracker on every read, so two of them for one position are
 * two caches of the same answer and never two different answers.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Internal
public final class ChunkViewerCache {

    /**
     * Blocks the creation of an instance because this class only reaches into a foreign map.
     */
    private ChunkViewerCache() {
    }

    /**
     * Removes the cached view of a chunk position.
     *
     * @param instance the instance the chunk belonged to
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     * @return true if an entry was removed, false if there was none or the tracker is a foreign
     *         implementation
     */
    public static boolean release(Instance instance, int chunkX, int chunkZ) {
        if (!(instance.getEntityTracker() instanceof EntityTrackerImpl tracker)) return false;
        final EntityTrackerImpl.TargetEntry<Entity> entry =
                tracker.targetEntries[EntityTracker.Target.PLAYERS.ordinal()];

        // keySet().remove(...) rather than remove(...), because the value type of that map is a
        // private nested class and naming what remove would return is not allowed here.
        return entry.viewers.keySet().remove(new EntityTrackerImpl.ChunkViewKey(List.of(), chunkX, chunkZ));
    }

    /**
     * Reports how many views the tracker of an instance currently caches.
     *
     * @param instance the instance to read
     * @return the amount of cached views, or {@code -1} if the tracker is a foreign implementation
     */
    public static int size(Instance instance) {
        if (!(instance.getEntityTracker() instanceof EntityTrackerImpl tracker)) return -1;
        return tracker.targetEntries[EntityTracker.Target.PLAYERS.ordinal()].viewers.size();
    }
}
```

Only the `PLAYERS` entry is touched, because `EntityTrackerImpl#viewable:208` only ever writes into that one.

- [ ] **Step 4: Call it from the unload**

In `ChunkLifecycle#unload`, after the loader was told:

```java
        this.persistence.unloaded(falcoChunk);
        // Last, because everything above may still want to reach the viewers of this chunk. The view
        // object stays alive in the chunk itself; what goes is the entry that kept it findable, which
        // is what nothing in Minestom ever removes.
        ChunkViewerCache.release(this.owner, chunkX, chunkZ);
```

- [ ] **Step 5: Run it, then the module**

```bash
./gradlew :falco-instance:test --tests "*ChunkViewerCacheTest*"
./gradlew :falco-instance:test
```

Expected: PASS, three and then 185. `FalcoInstanceTest#testAPlayerBecomesAViewerOfTheChunksAroundIt` is the net here: it is the only case that reads the viewers of a chunk, and a release that removed the wrong entry would take its viewers with it.

- [ ] **Step 6: Extend the leak test in `falco-benchmarks`**

Add a third nested class to `ChunkViewerCacheLeakTest`:

```java
    /**
     * The test that shows the leak being cleaned up rather than merely being smaller.
     */
    @Nested
    @DisplayName("for a FalcoInstance across a load and unload cycle")
    class ForAFalcoInstanceThatUnloads {

        /**
         * How many load and unload cycles the cache is measured across.
         */
        private static final int CYCLES = 64;

        /**
         * Establishes that a cycle leaves the cache exactly as it found it.
         */
        @Test
        @DisplayName("gives the entry back when the chunk goes")
        void testTheCacheReturnsToItsSize() {
            final FalcoInstance falco = MinestomChunks.newFalcoInstance();

            try {
                final int before = viewerCacheSize(falco);

                for (int cycle = 0; cycle < CYCLES; cycle++) {
                    falco.unloadChunk(falco.loadChunk(cycle, cycle).join());
                }
                final int after = viewerCacheSize(falco);

                System.out.printf("viewer cache of a FalcoInstance: %d cycles, %d -> %d entries%n",
                        CYCLES, before, after);
                assertEquals(before, after, "a load and unload cycle has to be neutral, but the cache grew by "
                        + (after - before) + " entries over " + CYCLES + " cycles");
            } finally {
                MinestomChunks.release(falco);
            }
        }
    }
```

The existing `ForAContainer` case stays exactly as it is: it measures Minestom's behaviour, not Falco's, and that behaviour is unchanged. The class javadoc's sentence *"its own unload path does not clear the entry either"* is now false and has to be rewritten to say what happens instead, with a pointer to `ChunkViewerCache`. Raise its `@version` to `1.1.0`.

- [ ] **Step 7: Run the benchmark module tests**

```bash
./gradlew :falco-benchmarks:test --tests "*ChunkViewerCacheLeakTest*" -i
```

Expected: PASS, three.

- [ ] **Step 8: Prove it bites**

Comment out the `ChunkViewerCache.release` call in `ChunkLifecycle#unload` and watch both new cases fail with a growth of 32 and 64 entries. Restore it.

- [ ] **Step 9: Commit**

```bash
git add falco-instance/src/main/java/net/minestom/server/instance/ChunkViewerCache.java \
        falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkLifecycle.java \
        falco-instance/src/test/java/net/minestom/server/instance/ChunkViewerCacheTest.java \
        falco-benchmarks/src/test/java/net/minestom/server/instance/ChunkViewerCacheLeakTest.java
git commit -m "fix(instance): give the viewer cache entry back when a chunk unloads"
```

---

### Task 10: One chunk, both extensions — US-3.06

**Files:**
- Create: `falco-light/src/main/java/net/onelitefeather/falco/light/ChunkLightListener.java`
- Rewrite: `falco-light/src/main/java/net/onelitefeather/falco/light/FalcoLightingChunk.java`
- Modify: `falco-light/build.gradle.kts`
- Modify: `falco-light/src/main/java/net/onelitefeather/falco/light/ChunkLightScheduler.java` (javadoc of `supplier()`)
- Modify: `falco-demo/src/main/java/net/onelitefeather/falco/demo/ServerStack.java`
- Test: `falco-light/src/test/java/net/onelitefeather/falco/light/FalcoLightingChunkTest.java`
- Test: `falco-demo/src/test/java/net/onelitefeather/falco/demo/ServerStackTest.java`

**Interfaces:**
- Consumes: `ChunkLifecycleListener`, `ChunkLifecycleEvent`, `FalcoChunk` from Task 8.
- Produces: `ChunkLightListener(ChunkLightScheduler scheduler)` implementing `ChunkLifecycleListener`, and `FalcoLightingChunk extends FalcoChunk implements LightUpdateAware`.

**What was actually still missing, read out of the code rather than out of the design.** Stage 1 already removed the structural half of the problem: `FalcoChunk` extends `Chunk`, not `DynamicChunk`, so `FalcoLightingChunk` could extend it the day stage 1 landed. Three things were left.

1. **The four hooks were occupied by inheritance.** `FalcoLightingChunk` overrode `setBlock`, `onLoad`, `tick` and `invalidate`, so a second extension had nowhere to go. Task 8 fixed that.
2. **The block position had no route that was not an override.** `FalcoLightingChunk:128` hands `markChanged` the exact coordinates, which is what lets the engine replay one position instead of searching nine chunks; a listener with only load, publish, tick and unload would have thrown that away and made every write a full chunk search. `onBlockChange` is that route.
3. **The module edge.** `falco-light` does not depend on `falco-instance`, on purpose — `FalcoLightingChunk`'s own comment argues that a lighting chunk needs the light engine and nothing else. That argument stops holding here: a chunk cannot be a `FalcoChunk` without `falco-instance` on the compile path, and `ChunkLightScheduler#deliver:452` reaches its result through `chunk instanceof LightUpdateAware`, a type of `falco-light` that the chunk has to implement. One of the two modules has to see the other.

**The decision, with the alternatives that were rejected.** `falco-light` gains `compileOnly(project(":falco-instance"))`. Everything the light engine itself does — `ChunkLightService`, `ChunkLightPropagator`, the scheduler, the nibble handling — keeps working with `falco-instance` absent; only `FalcoLightingChunk` and `ChunkLightListener` need it, and a consumer who uses the supplier adds the second module, which `falco-bom` already publishes next to the first.

- An `api` dependency was rejected: it would put `falco-instance` on the classpath of every consumer of the light engine, including the ones running a plain `InstanceContainer`.
- Moving the combination into `falco-instance` was rejected: the edge would only point the other way, and `falco-instance` would then depend on `falco-light` for `LightUpdateAware`.
- A `Consumer<Chunk>` sink on the scheduler, so that neither module needs the other, was rejected because it moves the shipped integration into applications: every consumer would have to write the wiring, and the demo would remain the only place where the two are combined.

- [ ] **Step 1: Write the failing test**

Append to `FalcoLightingChunkTest.java`:

```java
    @Test
    @DisplayName("is a Falco chunk, so a Falco instance can hold it")
    void testTheLightingChunkIsAFalcoChunk(Env env) {
        final ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService());
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);
        instance.setChunkSupplier(scheduler.supplier());

        final Chunk chunk = instance.loadChunk(0, 0).join();

        assertInstanceOf(FalcoLightingChunk.class, chunk);
        assertInstanceOf(FalcoChunk.class, chunk,
                "the whole point of US-3.06: one chunk instance serves the lifecycle and the light");
        assertTrue(chunk.isLoaded());
        instance.unloadChunk(chunk);
        assertFalse(chunk.isLoaded(), "a Falco instance can reach the unload hook of this chunk");
    }

    @Test
    @DisplayName("keeps its storage lazy, so it costs what stage 2 measured")
    void testTheLightingChunkHoldsNoSections(Env env) {
        final ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService());
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);

        final FalcoChunk chunk = new FalcoLightingChunk(scheduler, instance, 0, 0);

        assertEquals(0, chunk.storage().materialisedSections(),
                "a lighting chunk is a Falco chunk now, so it starts with no section of its own either");
        assertFalse(chunk.hasHeightmaps());
    }

    @Test
    @DisplayName("lets a second extension sit beside the light")
    void testASecondListenerFitsBesideTheLight(Env env) {
        final ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService());
        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);
        final AtomicInteger ticks = new AtomicInteger();
        final FalcoChunk chunk = new FalcoLightingChunk(scheduler, instance, 0, 0);

        chunk.addLifecycleListener(new ChunkLifecycleListener() {

            @Override
            public void onTick(ChunkLifecycleEvent event) {
                ticks.incrementAndGet();
            }
        });
        chunk.tick(1L);

        assertEquals(1, ticks.get(),
                "before this stage the light occupied the only extension point a chunk had");
    }
```

Add the imports for `FalcoChunk`, `FalcoInstance`, `ChunkLifecycleListener`, `ChunkLifecycleEvent` and `AtomicInteger`.

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :falco-light:test --tests "*FalcoLightingChunkTest*"
```

Expected: compilation failure — `falco-instance` is not on the test compile path of `falco-light`.

- [ ] **Step 3: Add the module edge**

`falco-light/build.gradle.kts`, comment-free:

```kotlin
    compileOnly(project(":falco-instance"))
    testImplementation(project(":falco-instance"))
```

- [ ] **Step 4: Write the listener**

```java
package net.onelitefeather.falco.light;

import net.minestom.server.instance.block.Block;
import net.onelitefeather.falco.instance.ChunkLifecycleEvent;
import net.onelitefeather.falco.instance.ChunkLifecycleListener;
import net.onelitefeather.falco.instance.FalcoChunk;
import org.jetbrains.annotations.ApiStatus;

/**
 * The {@link ChunkLightListener} class reports the changes of a chunk to a
 * {@link ChunkLightScheduler}, without being that chunk.
 * <p>
 * These three reports used to be three overrides of {@code FalcoLightingChunk}, which meant that
 * light occupied the only extension point a chunk had: a class has one superclass, so a server which
 * wanted Falco's light and anything else on the same chunk had to pick one. As a listener they
 * compose, and the chunk keeps only what genuinely needs to live on it — the cached light packet,
 * which is per chunk and cannot be held by a listener registered once for a whole instance.
 * </p>
 * <p>
 * What is reported is a position and not merely a chunk. {@link #onBlockChange} knows exactly which
 * block moved, and handing that on is what lets the engine replay one position instead of searching
 * nine chunks; a chunk which arrives from a generator or a loader has no such position to offer, so
 * {@link #onLoad} reports a change of unknown extent and pays for one search.
 * </p>
 * <p>
 * This type is experimental. The light engine is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public final class ChunkLightListener implements ChunkLifecycleListener {

    /**
     * The scheduler which decides when the light of a chunk is computed.
     */
    private final ChunkLightScheduler scheduler;

    /**
     * Creates a listener reporting to a scheduler.
     *
     * @param scheduler the scheduler which decides when the light of a chunk is computed
     */
    public ChunkLightListener(ChunkLightScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Reports the changed position, which is what lets the light be updated rather than searched.
     *
     * @param chunk the chunk which received the block
     * @param x     the block X
     * @param y     the block Y
     * @param z     the block Z
     * @param block the block which was written
     */
    @Override
    public void onBlockChange(FalcoChunk chunk, int x, int y, int z, Block block) {
        this.scheduler.markChanged(chunk.getInstance(), chunk.getChunkX(), chunk.getChunkZ(), x, y, z);
    }

    /**
     * Reports the chunk dirty as soon as its instance has taken it.
     * <p>
     * Without this a world that is only ever read would stay black: no block ever changes, so nothing
     * would ever ask for the light of a chunk that came straight from a loader or a generator. The
     * neighbours are reported with it, because a chunk that appears next to an already lit one can
     * send light into it that was not there when it was lit.
     * </p>
     *
     * @param event the chunk which finished loading
     */
    @Override
    public void onLoad(ChunkLifecycleEvent event) {
        final FalcoChunk chunk = event.chunk();
        this.scheduler.markChanged(chunk.getInstance(), chunk.getChunkX(), chunk.getChunkZ());
    }

    /**
     * Drives the scheduler, once per tick of every chunk it is installed on.
     *
     * @param event the chunk which was ticked, and the tick time
     */
    @Override
    public void onTick(ChunkLifecycleEvent event) {
        this.scheduler.onTick(event.chunk().getInstance(), event.time());
    }
}
```

- [ ] **Step 5: Rewrite the chunk**

```java
public class FalcoLightingChunk extends FalcoChunk implements LightUpdateAware {

    /**
     * The light packet of this chunk, rebuilt only when somebody asks for it after an invalidation.
     */
    private final CachedPacket lightCache = new CachedPacket(
            () -> new UpdateLightPacket(getChunkX(), getChunkZ(), createLightData(false))
    );

    /**
     * Creates a chunk which reports its changes to the given scheduler.
     *
     * @param scheduler the scheduler which decides when the light of this chunk is computed
     * @param instance  the instance this chunk belongs to
     * @param chunkX    the chunk x coordinate
     * @param chunkZ    the chunk z coordinate
     */
    public FalcoLightingChunk(ChunkLightScheduler scheduler, Instance instance, int chunkX, int chunkZ) {
        super(instance, chunkX, chunkZ);
        addLifecycleListener(new ChunkLightListener(scheduler));
    }

    @Override
    public void invalidate() {
        super.invalidate();
        this.lightCache.invalidate();
    }

    @Override
    public void onLightUpdated() {
        if (!isLoaded()) {
            return;
        }
        this.lightCache.invalidate();
        sendPacketToViewers(this.lightCache);
    }
}
```

Three overrides are gone — `setBlock`, `onLoad` and `tick` — and their javadoc moves to `ChunkLightListener`. The class comment needs four changes and one deletion:

- the paragraph *"Why this lives in falco-light and not in falco-instance"* is now wrong in its premise and has to say what actually happened: the class needs `falco-instance` at compile time, gets it as a `compileOnly` dependency, and a consumer who uses `ChunkLightScheduler#supplier()` needs both modules on the classpath while a consumer of the bare light engine needs neither;
- the paragraph *"This class holds no computation logic on purpose"* stays and gets sharper: two overrides now, both about a packet;
- the paragraph about `isLoaded` not being overridden stays and its reference to `DynamicChunk` becomes `FalcoChunk`, which has the same property — a freshly constructed chunk reports itself loaded, so a batch against it is not silently skipped;
- a new paragraph states what the chunk gained by changing superclass: the lazy sections, the on-demand heightmaps and the single block index map of stage 2, which is 25 objects and 840 bytes against 192 and 6 848 for the `DynamicChunk` it used to extend;
- `@version` to `2.0.0`, and the `supplier()` javadoc of `ChunkLightScheduler` gains one line saying the chunks it produces are `FalcoChunk`s and work in a `FalcoInstance` as well as in an `InstanceContainer`. Raise its `@version`.

- [ ] **Step 6: Run the light module**

```bash
./gradlew :falco-light:test
```

Expected: PASS, 192 (189 from stage 2 plus the three new cases). The whole existing light suite is the net here: `ChunkBorderLightTest`, `SkyLightUpdateTest`, `IncrementalLightUpdateTest` and `LightEngineEquivalenceTest` all drive the chunk and would notice a report that no longer arrives.

- [ ] **Step 7: Fix the demo, which documented the impossibility**

`ServerStack:29-39` explains at length that both stacks run on an `InstanceContainer` because `FalcoInstance` cannot hold a `FalcoLightingChunk`, and `ServerStack#note:203` prints that to the log. Both stop being true. Change the Falco stack to build a `FalcoInstance` with the light supplier, make `note()` say what the stack now consists of, and update `ServerStackTest#testTheFalcoStackExplainsWhyTheFalcoInstanceIsMissing` — which asserts that the note mentions `FalcoInstance` — into a case that asserts the stack uses one. Raise `ServerStack`'s `@version` to `2.0.0`.

**Keep the comparison honest while doing it.** The two stacks exist to differ in one variable at a time, and the vanilla stack stays on an `InstanceContainer`. Changing the Falco side to a `FalcoInstance` adds a second variable to the comparison, and that has to be written into the class comment rather than glossed over: from this stage on the two stacks differ in the loader, in the chunk type **and** in the instance, and a figure taken from the demo can no longer be attributed to any one of them.

- [ ] **Step 8: Run the demo module**

```bash
./gradlew :falco-demo:test
```

Expected: PASS, 139.

- [ ] **Step 9: Prove the combination is real**

Delete `addLifecycleListener(new ChunkLightListener(scheduler));` from the constructor and watch `SkyLightUpdateTest` and `IncrementalLightUpdateTest` go dark — a world which never reports a change is never lit. Restore it. Then make `FalcoLightingChunk` extend `DynamicChunk` again and watch `testTheLightingChunkIsAFalcoChunk` fail with the `FalcoInstanceException` that names the wrong supplier, which is precisely the message the demo used to have to work around.

- [ ] **Step 10: Commit**

```bash
git add falco-light/build.gradle.kts \
        falco-light/src/main/java/net/onelitefeather/falco/light/ChunkLightListener.java \
        falco-light/src/main/java/net/onelitefeather/falco/light/FalcoLightingChunk.java \
        falco-light/src/main/java/net/onelitefeather/falco/light/ChunkLightScheduler.java \
        falco-light/src/test/java/net/onelitefeather/falco/light/FalcoLightingChunkTest.java \
        falco-demo/src/main/java/net/onelitefeather/falco/demo/ServerStack.java \
        falco-demo/src/test/java/net/onelitefeather/falco/demo/ServerStackTest.java
git commit -m "feat(light)!: put the lifecycle and the light on one chunk instance"
```

---

### Task 11: The chunk index without a box — US-3.05

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `falco-instance/build.gradle.kts`
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkRegistry.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/ChunkLookupAllocationTest.java`
- Create: `falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/instance/ChunkLookupBenchmark.java`

**Interfaces:**
- Consumes: `ChunkRegistry` from Task 2.
- Produces: no API change. `chunk(long)`, `chunk(int,int)`, `chunks()`, `snapshot()`, `size()` keep their signatures; only what is behind them changes.

**What this is and is not.** §4.3 of the spec is explicit: this is **not** sold as a performance change. `ConcurrentHashMap<Long, Chunk>#get` boxes its key, and that allocation is real and can be counted. What it costs is not established, because `getChunk` is reached on a chunk change rather than per block — `ChunkCache` memoises in between. The deliverable of this task is therefore the counted allocation and a benchmark that prices the two maps against each other, not a claim.

**The dependency this needs, checked before it was written down.**

```bash
./gradlew :falco-instance:dependencies --configuration compileClasspath | grep flare
./gradlew :falco-instance:dependencies --configuration testRuntimeClasspath | grep flare
```

The first prints nothing and the second prints `space.vectrix.flare:flare:2.0.1` and `space.vectrix.flare:flare-fastutil:2.0.1`. Minestom depends on flare and hides it from its compile classpath, so `Long2ObjectSyncMap` is present at runtime for every Minestom server that exists and absent at compile time here. `compileOnly` is exactly the right shape: no consumer gains a dependency it did not already have through Minestom.

`Long2ObjectSyncMap` is not the copy-on-write map the javadoc of `FalcoInstance` used to warn about. It is a Go-style `sync.Map`: a read map that satisfies lookups without a lock and a dirty map that takes the writes, promoted when the misses add up. Reads take no lock and box nothing; a write after many misses rebuilds the dirty map, which is O(n) and lands on the load and unload path, where a tick partition is created and an event is dispatched anyway. That trade is stated here and priced by the benchmark below, not asserted.

**Only the published chunks change.** `loadingChunks` stays a `ConcurrentHashMap<Long, CompletableFuture<Chunk>>`, because its `compute` is the lock of a position and the exact atomicity of that method is what `FalcoInstanceLoadRaceTest` protects. Swapping it for a map whose atomicity guarantees have to be re-read from a third-party source is not a boxing question and does not belong in a story marked *Could*.

- [ ] **Step 1: Write the failing test**

```java
package net.onelitefeather.falco.instance;

import com.sun.management.ThreadMXBean;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.management.ManagementFactory;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Counts what looking a chunk up allocates, which is US-3.05.
 * <p>
 * A {@code ConcurrentHashMap<Long, Chunk>} boxes its key on every call, and the chunk index of a
 * position is far outside the range {@code Long#valueOf} caches, so every lookup is a sixteen byte
 * object that lives until the next young collection. This counts them. It says nothing about time,
 * on purpose: the design refuses to sell the change as a speed gain, because {@code getChunk} is
 * reached on a chunk change rather than per block and {@code ChunkCache} memoises in between.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("What a chunk lookup allocates")
class ChunkLookupAllocationTest {

    /**
     * How many lookups the measurement performs.
     */
    private static final int LOOKUPS = 500_000;

    /**
     * How many lookups run before the measurement, so the loop is compiled.
     */
    private static final int WARMUP = 100_000;

    /**
     * Where the looked up chunk is published, so no compiler may drop the lookup.
     */
    private static volatile Object sink;

    /**
     * Performs the given number of lookups and reports what the calling thread allocated.
     *
     * @param registry the registry to look up in
     * @param times    how many lookups to perform
     * @return the bytes the calling thread allocated during the loop
     */
    private static long allocatedWhileLookingUp(ChunkRegistry registry, int times) {
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long before = threads.getCurrentThreadAllocatedBytes();

        for (int index = 0; index < times; index++) {
            sink = registry.chunk(0, 0);
        }
        return threads.getCurrentThreadAllocatedBytes() - before;
    }

    @Test
    @DisplayName("allocates nothing at all")
    void testALookupIsAllocationFree(Env env) {
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported(),
                "this JVM cannot report per thread allocation, so the question cannot be answered here");
        threads.setThreadAllocatedMemoryEnabled(true);

        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);
        instance.loadChunk(0, 0).join();
        final ChunkRegistry registry = instance.registry();
        assertNotNull(registry.chunk(0, 0), "the position has to carry a chunk, or this loop measures a miss");

        allocatedWhileLookingUp(registry, WARMUP);
        final long allocated = allocatedWhileLookingUp(registry, LOOKUPS);

        System.out.printf("chunk lookups: %,d -> %,d B (%.3f B each)%n",
                LOOKUPS, allocated, (double) allocated / LOOKUPS);
        assertTrue(allocated < LOOKUPS, "a chunk lookup allocated " + allocated + " B over " + LOOKUPS
                + " lookups, which is more than a byte each: the index is still being boxed");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :falco-instance:test --tests "*ChunkLookupAllocationTest*"
```

Expected: **failure**, at roughly 16 B per lookup. Print the number: it is the before-figure of this task and belongs in the stage result.

- [ ] **Step 3: Add the dependency**

`settings.gradle.kts`, in the version catalog:

```kotlin
            version("flare", "2.0.1")
            library("flare.fastutil", "space.vectrix.flare", "flare-fastutil").versionRef("flare")
```

`falco-instance/build.gradle.kts`:

```kotlin
    compileOnly(libs.flare.fastutil)
    testImplementation(libs.flare.fastutil)
```

Then check that the pinned version is the one Minestom brings at runtime, so that compiling against one and running against another cannot happen:

```bash
./gradlew :falco-instance:dependencies --configuration testRuntimeClasspath | grep flare
```

Both lines have to say `2.0.1`. If Minestom is bumped later and brings a different one, this is the line that has to move with it.

- [ ] **Step 4: Change the map**

In `ChunkRegistry`:

```java
    /**
     * The loaded chunks, keyed by the chunk index of their position.
     * <p>
     * A primitive keyed map rather than a {@code ConcurrentHashMap<Long, Chunk>}, which boxed its key
     * on every lookup — sixteen bytes per call, counted by {@code ChunkLookupAllocationTest}. This is
     * not offered as a speed change and no figure of this repository claims one: {@code getChunk} is
     * reached on a chunk change rather than per block, because {@code ChunkCache} memoises in between,
     * so the allocation is established and its cost is not.
     * </p>
     * <p>
     * {@code Long2ObjectSyncMap} is a read map plus a dirty map in the shape of Go's {@code sync.Map},
     * not the copy-on-write map underneath {@code InstanceContainer}. Lookups take no lock; a write
     * after a run of misses rebuilds the dirty map, which is linear and lands on the load and unload
     * path, where a tick partition is created and an event is dispatched anyway.
     * {@code ChunkLookupBenchmark} prices both sides.
     * </p>
     */
    private final Long2ObjectSyncMap<Chunk> chunks = Long2ObjectSyncMap.hashmap();
```

`chunk(long)` becomes `this.chunks.get(index)` on the primitive overload, `remove` inside `ChunkRegistry#remove` becomes `this.chunks.remove(index, chunk)` on the primitive overload, `put` becomes `this.chunks.put(index, chunk)`. `chunks()`, `snapshot()`, `size()` and `idle()` are unchanged in body — `Long2ObjectSyncMap` implements `Long2ObjectMap`, so `values()` and `isEmpty()` are there.

- [ ] **Step 5: Run the test, then the module**

```bash
./gradlew :falco-instance:test --tests "*ChunkLookupAllocationTest*"
./gradlew :falco-instance:test
```

Expected: PASS at `0.000 B each`, and 186 for the module.

- [ ] **Step 6: Write the benchmark that prices the trade**

```java
package net.onelitefeather.falco.benchmark.instance;

import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.Chunk;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import space.vectrix.flare.fastutil.Long2ObjectSyncMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Prices the boxed chunk index against the unboxed one, on the lookup and on the write.
 * <p>
 * US-3.05 asks for the boxing to go and the design refuses to sell that as a speed change, because
 * the cost of the boxing is not established. This benchmark is what would establish it, and it
 * measures both directions on purpose: the lookup, which is what the change is for, and the write,
 * which is where the map that removes the boxing is more expensive. A change that reports only the
 * side it improves is not a measurement.
 * </p>
 * <p>
 * Both maps are driven with the same key sequence and the same content. Neither arm touches a real
 * chunk — the value is a plain {@code Object} standing in for one — because the question is about the
 * map and a chunk would put a two hundred kilobyte object into a cache line argument.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ChunkLookupBenchmark {

    /**
     * How many chunk positions the maps hold, which is roughly a view distance of eight, sixteen and
     * a streaming world.
     */
    @Param({"289", "1089", "4096"})
    public int positions;

    /**
     * The boxed map, the shape this stage replaced.
     */
    private Map<Long, Object> boxed;

    /**
     * The primitive map, the shape this stage installed.
     */
    private Long2ObjectSyncMap<Object> primitive;

    /**
     * The keys, in the order the benchmark walks them.
     */
    private long[] keys;

    /**
     * The value every key maps to.
     */
    private final Object value = new Object();

    /**
     * Fills both maps with the same content.
     */
    @Setup
    public void setUp() {
        this.boxed = new ConcurrentHashMap<>();
        this.primitive = Long2ObjectSyncMap.hashmap();
        this.keys = new long[this.positions];

        final int side = (int) Math.ceil(Math.sqrt(this.positions));
        for (int index = 0; index < this.positions; index++) {
            final long key = CoordConversion.chunkIndex(index % side, index / side);
            this.keys[index] = key;
            this.boxed.put(key, this.value);
            this.primitive.put(key, this.value);
        }
    }

    /**
     * Walks every position through the boxed map.
     *
     * @param blackhole where the results go
     */
    @Benchmark
    public void boxedLookup(Blackhole blackhole) {
        for (long key : this.keys) blackhole.consume(this.boxed.get(key));
    }

    /**
     * Walks every position through the primitive map.
     *
     * @param blackhole where the results go
     */
    @Benchmark
    public void primitiveLookup(Blackhole blackhole) {
        for (long key : this.keys) blackhole.consume(this.primitive.get(key));
    }

    /**
     * Puts and removes one position in the boxed map, which is what a load and an unload do.
     *
     * @param blackhole where the results go
     */
    @Benchmark
    public void boxedLoadAndUnload(Blackhole blackhole) {
        final long key = CoordConversion.chunkIndex(9999, 9999);
        blackhole.consume(this.boxed.put(key, this.value));
        blackhole.consume(this.boxed.remove(key));
    }

    /**
     * Puts and removes one position in the primitive map.
     *
     * @param blackhole where the results go
     */
    @Benchmark
    public void primitiveLoadAndUnload(Blackhole blackhole) {
        final long key = CoordConversion.chunkIndex(9999, 9999);
        blackhole.consume(this.primitive.put(key, this.value));
        blackhole.consume(this.primitive.remove(key));
    }
}
```

`falco-benchmarks/build.gradle.kts` needs `jmhImplementation(libs.flare.fastutil)` — comment-free, one line.

- [ ] **Step 7: Run it in the scouting configuration and write the numbers down**

```bash
./gradlew :falco-benchmarks:jmh -Pjmh.quick -Pjmh.include="ChunkLookupBenchmark"
```

Record the four arms and their `gc.alloc.rate.norm` in the stage result, **with the sentence that the configuration disqualifies the timings from being quoted** — one fork, three iterations, on a machine that is not idle. What is citable from this run is the allocation column, which is deterministic: the boxed lookup arm allocates sixteen bytes per position and the primitive one zero.

- [ ] **Step 8: Prove the allocation test bites**

Change `ChunkRegistry#chunk(int,int)` back to a `ConcurrentHashMap` lookup and watch `ChunkLookupAllocationTest` fail with roughly 16 B per lookup. Restore it.

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts falco-instance/build.gradle.kts falco-benchmarks/build.gradle.kts \
        falco-instance/src/main/java/net/onelitefeather/falco/instance/ChunkRegistry.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/ChunkLookupAllocationTest.java \
        falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/instance/ChunkLookupBenchmark.java
git commit -m "perf(instance): look a chunk up without boxing its index"
```

---

### Task 12: Re-run everything and record what the stage cost

**Files:**
- Modify: `docs/superpowers/plans/2026-08-02-falco-instance-facade.md` (this file)

**Interfaces:**
- Consumes: everything above.
- Produces: a `## Stage 3 result` section.

**This is the acceptance gate of the stage.** Nothing here is new code; everything here is a number that has to exist before the stage may be called done.

- [ ] **Step 1: The whole suite**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-instance:test :falco-anvil:test :falco-light:test :falco-demo:test :falco-benchmarks:test --rerun-tasks
```

Expected: all green. Record the five counts against T4 of the starting point: 143 / 193 / 189 / 139 / 38 became something larger in three of them and has to be unchanged in `falco-anvil`. A count that fell anywhere is a test that was deleted, and deleting a test during a refactoring is the one thing this plan must not have done.

- [ ] **Step 2: The footprint**

```bash
./gradlew :falco-benchmarks:test --tests "*ChunkFootprintTest*" -i
./gradlew :falco-benchmarks:test --tests "*ChunkFootprintTest*" -Pfalco.compactHeaders -i
```

Record the fresh chunk against T1 (25 objects, 840 B) and the per-chunk instance cost against T3 (161 B). The first has one reference field more than it did — Task 8 — and the second is unchanged by this stage, because it measures construction and the entry that goes now goes on unload.

- [ ] **Step 3: The equivalence, which carries US-1.03**

```bash
./gradlew :falco-benchmarks:test --tests "*FalcoChunkEquivalenceTest*" -i
```

Eighteen fixtures, every position and both heightmaps of every column. Nothing in this stage touches block storage, and that is exactly why this has to be run: a stage that only moved code around has no excuse for a difference here.

- [ ] **Step 4: The javadoc**

```bash
./gradlew :falco-instance:javadoc :falco-light:javadoc
```

`-Werror` is on. Seven new public types shipped in this stage and every public member of them needs a complete comment.

- [ ] **Step 5: Write the result section**

Append `## Stage 3 result` to this file with, at minimum:

- the five test counts, before and after;
- the fresh chunk footprint, before and after, with the object header mode and the JOL mode;
- the two numbers of `ChunkLifecycleAllocationTest`, both arms, with the machine;
- the before and after of `ChunkLookupAllocationTest`;
- the four arms of `ChunkLookupBenchmark` with the sentence that disqualifies their timings;
- the viewer cache figures, before and after, over 64 cycles;
- the line counts of every file in `falco-instance/src/main/java/net/onelitefeather/falco/instance/`;
- **what this stage did not achieve**, in its own paragraph. At the time of writing the plan the honest candidates are: the container's unbounded viewer cache growth, which cannot be reached from outside Minestom; the module edge from `falco-light` to `falco-instance`, which reverses an argument the old `FalcoLightingChunk` javadoc made and is a cost rather than a win; the split package of `ChunkViewerCache`, which closes the module path; and the second variable the demo comparison gained in Task 10.

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/plans/2026-08-02-falco-instance-facade.md
git commit -m "docs(plan): record what stage 3 measured"
```

---

## Definition of done

- [ ] `placeBlock`, `breakBlock`, the neighbour updates, the recursion guard and all four save paths have tests, and those tests were written before the code moved
- [ ] `FalcoInstance` declares exactly four fields, every one of them final and one of the four parts, and `InstanceFacadeTest` fails when a fifth appears — proven by adding one
- [ ] `publish` and `completeLoad` are reachable in a test without driving a full load, and the refused-publish case is deterministic in a single thread
- [ ] `ChunkLifecycleListener` exists, two listeners on one chunk are both notified on all five reports, and a single listener is stored without being wrapped
- [ ] A lifecycle transition on a chunk with no listener allocates nothing, **counted**, with a positive control that proves the counter can see allocations at all
- [ ] `FalcoLightingChunk` is a `FalcoChunk`, a `FalcoInstance` loads and unloads one, and a second listener fits beside the light
- [ ] The demo runs its Falco stack on a `FalcoInstance`, and the note that said it could not is gone
- [ ] A load and unload cycle leaves the viewer cache exactly where it found it, over 64 cycles, proven by removing the release and watching it grow
- [ ] A chunk lookup allocates nothing, counted before and after, with the benchmark that prices the write side of the trade
- [ ] `ChunkFootprintTest` still asserts a declared per-class table with no tolerance anywhere in it, re-declared for the one field `FalcoChunk` gained
- [ ] All five module suites pass, with counts recorded against the stage 2 result, and no count fell
- [ ] `:falco-instance:javadoc` and `:falco-light:javadoc` pass with `-Werror`
- [ ] Every new public type carries `@ApiStatus.Experimental`, `@author`, `@version` and `@since 0.4.0`; every modified type has its `@version` raised

## What stage 3 deliberately does not do

Named here so that a reviewer does not read them as omissions.

**It does not build a shared instance (US-4.01 to US-4.04).** That is stage 4 and it rests on US-1.05, which stage 1 delivered. Nothing here is a step towards it and nothing here blocks it.

**It does not fix the viewer cache leak of `InstanceContainer` (M14).** The container hands its tracker a fresh `unmodifiableList` on every chunk construction and `ChunkViewKey#equals` compares that list by identity, so no key built from outside can ever match one of its entries. `ChunkViewerCacheLeakTest` keeps measuring it because it is the reason the Falco side is worth cleaning up, not because Falco fixed it.

**It does not remove the `instanceof InstanceContainer` branches of Minestom.** Four places in the server take a different path for anything that is not a container, and three of them are harmless here. The fourth — `InstanceManager#unregisterInstance` not unloading chunks — is still answered by `FalcoInstance#unregister`, which is still the reason the class exists.

**It does not make `FalcoInstance` faster.** Not one line of this stage was written for throughput. The lock granularity is unchanged, the write path does the same work in the same order, and the primitive chunk index is delivered with a counted allocation and an explicit refusal to claim a speed gain. If a benchmark of this stage shows a difference, it is a regression to investigate, not a result to quote.

**It does not touch block storage.** No change to `BlockStorage`, `LazySectionBlockStorage`, `SectionBlockStorage`, the flyweight, the heightmaps or the palettes. `FalcoChunk` gains one field and five notification points and nothing else, and `FalcoChunkEquivalenceTest` is what says so.

**It does not remove the last `AtomicBoolean` or the chunk `UUID`.** Both are stage 2 non-goals for reasons that have not changed: `LightCompute#compute` is package-private, and `Chunk.java:48` declares a field a subclass cannot delete.

**It does not turn `falco-light` into a module that needs `falco-instance` at runtime for everything.** The dependency is `compileOnly` and only two classes of the module use it. A consumer of the bare light engine on a plain `InstanceContainer` adds nothing; a consumer of `ChunkLightScheduler#supplier()` adds `falco-instance`, which `falco-bom` already publishes beside it. That is a real cost and it is booked here rather than hidden.

**It does not make Falco a named module.** `ChunkViewerCache` puts a class into `net.minestom.server.instance`, which is a split package with Minestom's own module. On the classpath, where every consumer runs today, this is invisible. On the module path it is fatal, and it will stay fatal until either Minestom exposes a way to release a viewer cache entry or that class moves somewhere it cannot reach the map at all.
