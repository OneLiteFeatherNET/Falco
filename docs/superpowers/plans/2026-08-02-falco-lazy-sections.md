# Falco Lazy Sections — Stage 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the chunk cost what its terrain costs. Stage 1 built the seam and proved it free; it removed nothing. This stage puts a layout behind that seam which holds no `Section` for a section that is nothing but air, holds no heightmap until something asks for one, holds one block map instead of two, and calls `optimize()` on the palettes a generator filled — and it measures every one of those against the figures stage 1 left behind.

**Architecture:** A flyweight one level above `Section`. Every empty slot of `LazySectionBlockStorage` points at one process-wide `EMPTY` section; the first write to a slot replaces it with a fresh one. The flyweight can be neither a `Section` nor a `Palette` — `Section` is a `record` and therefore final, and `Palette` is `public sealed interface Palette permits PaletteImpl`, closed by the verifier — so it lives in `BlockStorage`, which is exactly the seam stage 1 bought. The interface grows a second, non-materialising way to look at a section, because the difference between a caller that reads a section and a caller that may write to it is the difference between a saving that survives and one that does not.

**Tech Stack:** Java 25, Gradle, JUnit 5, Cyano (Minestom test extension), JMH + JOL for measurement, fastutil.

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

- **Source and Javadoc are English**, and Javadoc *justifies* decisions in `<p>` paragraphs and `<h2>` sections. Every type carries `@author TheMeinerLP`, `@version`, `@since 0.4.0`. **Changing an existing class raises its `@version`.** Model: `falco-light/src/main/java/net/onelitefeather/falco/light/ChunkLightService.java`.
- **Gradle files stay comment-free.**
- **Minestom reference is the pinned sources jar**, unpacked at `/tmp/claude-1000/-mnt-projects-oss-onelitefeather-Falco/34edb948-9dfe-4540-9666-9e29f0d44d7b/scratchpad/minestom-src/`. The clone at `/mnt/projects/oss/minestom/Minestom` is ten months stale and must not be used.
- Work happens in the worktree `/mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage`, on branch `feat/block-storage`. Every Gradle command is prefixed with `cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage &&`, because the working directory of a shell call does not persist.
- **Measured, not asserted.** No figure without its conditions, no green test that claims instead of checking.

## The measured starting point

Everything below is from the `Stage 1 result` section of `docs/superpowers/plans/2026-08-01-falco-block-storage.md` and from §2 of the spec. Legacy object headers of twelve bytes, eight byte alignment, JOL through the instrumentation agent, JDK 25.0.3 (Temurin). A number from a `-XX:+UseCompactObjectHeaders` run must never be quoted next to one of these.

| # | What | Figure | Where it came from |
|---|---|---|---|
| S1 | A fresh `DynamicChunk` | 192 objects, 6 848 B | `ChunkFootprintTest` |
| S2 | A fresh `FalcoChunk` after stage 1 | 193 objects, 6 872 B | `ChunkFootprintTest` |
| S3 | — of the fresh chunk, the section list and everything below it | 5 128 B, **74.9 %** | `ChunkFootprintTest` breakdown |
| S4 | — of the fresh chunk, both heightmaps with their two `short[256]` | 1 120 B, **16.4 %** | `ChunkFootprintTest` breakdown |
| S5 | — of the fresh chunk, the 48 `AtomicBoolean` | 768 B, 11.2 % | `ChunkFootprintTest` breakdown |
| S6 | Sharing empty sections instead of allocating 24, at construction | **2 104 against 7 096 B/op, −70 %** | `LazySectionBenchmark#buildSectionsLazy` against `#buildSectionsMinestom` |
| S7 | Empty section share of a generated overworld, finished chunks only | **62.24 %**, 441 chunks around one spawn | `EmptySectionCensusTest` |
| S8 | Flyweight saving at that share | **2 911 B per chunk**, 11.4 MB over 4 096 chunks | JOL, `EmptySectionCensusTest#testTheFootprintOfBothSectionLayouts` |
| S9 | A generated chunk stays at 15 bpe direct | **203 840 against 84 800 B**, factor 2.4 | JOL; `optimize()` has no caller in Minestom's main tree |

`LazySectionBenchmark` is not a benchmark this stage has to write. It already measures precisely the candidate of this stage, down to the reason the materialisation allocates instead of cloning, and its `LazySections` prototype is the shape `LazySectionBlockStorage` has to take. Read it before Task 2 and reproduce its decisions rather than re-deriving them.

## Four traps, verified against the pinned sources before they were written down

**The flyweight cannot be a `Section` and cannot be a `Palette`.** `Section.java:6` is `public record Section(Palette blockPalette, Palette biomePalette, Light skyLight, Light blockLight)`, and a record is final. `Palette.java:29` is `public sealed interface Palette permits PaletteImpl`. Neither a lazy section nor a lazy palette can be handed to Minestom, so the pattern lives one level above both, in `BlockStorage`. That is not a workaround; it is the reason stage 1 existed.

**Materialise with `new Section()`, never with `EMPTY.clone()`.** `Section#clone` builds two fresh carriers and then calls `skyLight.set(this.skyLight.array())` and `blockLight.set(this.blockLight.array())`. For the flyweight, `array()` returns `LightCompute.UNSET_CONTENT`, and `SkyLight#set` runs `this.content = lazyArray(copyArray)` — and `LightCompute#lazyArray` answers a zero-length array with `EMPTY_CONTENT`, the shared static `byte[2048]`. `set` then also sets `isValidBorders = true`, `contentPropagation = content` and `needsSend.set(true)`. A section materialised by cloning would therefore report that it has light to send when it has never been lit, and would point its `content` field at a process-wide mutable array shared with every other section materialised the same way. `new Section()` leaves `content` null and `needsSend` false, allocates no light array, and is what `LazySectionBenchmark#firstWriteLazy` measured at 2 720 B/op.

**`getSections()` and `getSection(int)` are the boundary, and the heightmap of Minestom walks through it.** Their callers in the pinned sources are `InstanceContainer#generateChunk` (`:413`, `:415`, writes), `AnvilLoader#loadSections` (`:214`, writes) and `AnvilLoader#saveChunk` (`:423`, reads), `LightingChunk` (`:136`, `:188`, `:204`, `:373`, `:385`, `:392`, `:459`, `:526`), `Instance#invalidateSection` (`:311`), and — the one that decides this stage — `Heightmap#refresh(int,int,int)` (`:77`) and `Heightmap#getHighestBlockSection` (`:134`). The second of those walks the chunk **from the top downwards** calling `chunk.getSection(sectionY).blockPalette()` until it meets a palette whose `count()` is not zero. Those are exactly the empty top sections the flyweight exists to avoid. `FalcoChunk#setBlock` reaches it through `calculateFullHeightmap()` on the first write, and `createChunkPacket` reaches it through `getHeightmaps()` on the first send. Left alone, one `setBlock` into a fresh chunk would materialise all twenty-four sections and this stage would save nothing at all. In the repository the same boundary is crossed by `FalcoInstance#applyGenerator:923` (`getSections()`, writes every palette), `FalcoInstance#applyFork:1047` (`getSectionAt`, writes), `FalcoAnvilLoader:1053` and `:1161`, and `ChunkLightService:160` and `:405`.

**The stage 1 footprint assertion has to be replaced, and replaced with something at least as sharp.** `ChunkFootprintTest#assertTheSeamIsTheOnlyDifference` currently demands that `FalcoChunk` and `DynamicChunk` retain identical objects and bytes in every class except `SectionBlockStorage`, of which Falco holds exactly one. Every task below breaks that by construction. Task 9 replaces it with a declared, per-class difference table, not with a tolerance. A tolerance of the form "at most N bytes" is rejected: it is what the equality existed to prevent.

## File Structure

| File | Responsibility |
|---|---|
| `falco-instance/src/main/java/net/onelitefeather/falco/instance/BlockStorage.java` | **Modify.** Gains `view(int)`, `views()`, `shared(int)` and `materialisedSections()` — the non-materialising counterpart of the boundary methods, and the counter that makes the saving assertable. `@version 1.1.0` → `2.0.0`. |
| `.../instance/SectionBlockStorage.java` | **Modify.** Implements the four new members trivially; it shares nothing and materialises nothing, so every one of them is a constant answer. `@version 1.1.0` → `1.2.0`. |
| `.../instance/LazySectionBlockStorage.java` | **Create.** The flyweight storage. One process-wide `EMPTY`, copy on first write, `new Section()` and never `EMPTY.clone()`. |
| `.../instance/FalcoChunk.java` | **Modify.** Defaults to the lazy storage; routes its own packet, light and snapshot reads through `views()`; computes the highest non-empty section itself instead of through `Heightmap#getHighestBlockSection`; builds its heightmaps on demand; drops `tickableMap`. `@version 2.1.0` → `3.0.0`. |
| `.../instance/FalcoInstance.java` | **Modify.** `applyGenerator` commits only the sections a generator actually filled and calls `Palette#optimize` on them; `applyFork` skips a fork section that carries nothing. `@version` raised. |
| `falco-instance/src/test/java/.../instance/BlockStorageTest.java` | **Modify.** Becomes parameterised over both implementations, which is the promise stage 1 made when it wrote the contract tests against the interface. |
| `falco-instance/src/test/java/.../instance/LazySectionBlockStorageTest.java` | **Create.** The properties only the flyweight has: identity of the shared slot, materialisation of exactly one section, air and biome writes that must not materialise, `copy` that keeps sharing. |
| `falco-instance/src/test/java/.../instance/SectionMaterialisationTest.java` | **Create.** Counts what each boundary caller actually materialises. This is the acceptance test of the whole stage. |
| `falco-benchmarks/src/jmh/java/.../benchmark/instance/GeneratorCommitBenchmark.java` | **Create.** Prices `Palette#optimize` in time against the generation it follows, because S9 states its byte saving and nothing states its cost. |
| `falco-benchmarks/src/test/java/.../benchmark/instance/ChunkFootprintTest.java` | **Modify.** The seam assertion becomes a declared per-class difference table. `@version 1.1.0` → `2.0.0`. |

Already in `falco-benchmarks` and **not to be reinvented**: `LazySectionBenchmark` (the candidate of this stage, with the axis at 0/62/90 percent empty), `SectionAllocationBenchmark` (what the posts of a chunk cost at construction), `EmptySectionCensusTest` (S7 and S8), `ChunkFootprintTest` (S1–S5), `PaletteFootprintTest` (the palette side of S9), `ChunkComparisonBenchmark` and `FalcoChunkEquivalenceTest` (the regression net, and the evidence for US-1.03). All of them must be re-run at the end of the stage.

---

### Task 1: A way to look at a section without creating one

**Files:**
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/BlockStorage.java`
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/SectionBlockStorage.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/BlockStorageTest.java`

**Interfaces:**
- Consumes: `BlockStorage`, `SectionBlockStorage` as stage 1 left them.
- Produces: `Section BlockStorage#view(int section)`, `List<Section> BlockStorage#views()`, `boolean BlockStorage#shared(int section)`, `int BlockStorage#materialisedSections()`. Tasks 2, 3, 6 and the tests of Tasks 4 and 9 depend on exactly these names and on the index being an offset from the bottom section, as `section(int)` already is.

**Why this is a task of its own.** `sections()` and `section(int)` are wired to `Chunk#getSections()` and `Chunk#getSection(int)`, which hand a `Section` to a stranger who may write into it, so they have to materialise (US-2.09). Every read `FalcoChunk` performs on its own sections — the chunk packet, the light data, the highest-section scan — is not such a stranger, and routing it through the same method would undo the saving from inside the chunk. The seam therefore needs both, and the difference between them has to be a documented contract rather than a habit.

- [ ] **Step 1: Write the failing test**

Append to `BlockStorageTest.java`. It is written against the interface, so Task 2's implementation inherits it unchanged:

```java
    @Test
    @DisplayName("reports every section as materialised when it holds one of its own")
    void testEagerStorageSharesNothing() {
        final BlockStorage storage = storage();

        assertEquals(SECTIONS, storage.materialisedSections());
        for (int section = 0; section < SECTIONS; section++) {
            assertFalse(storage.shared(section),
                    "section " + section + " of an eager storage cannot be shared");
        }
    }

    @Test
    @DisplayName("hands out the same section through the view as through the boundary")
    void testViewAndSectionAgree() {
        final BlockStorage storage = storage();

        storage.setBlock(1, 2, 3, Block.STONE);

        assertSame(storage.section(0), storage.view(0),
                "an eager storage has nothing to materialise, so the two accessors are one");
        assertEquals(SECTIONS, storage.views().size());
        for (int section = 0; section < SECTIONS; section++) {
            assertSame(storage.sections().get(section), storage.views().get(section),
                    "the view of section " + section + " has to be the section itself");
        }
    }

    @Test
    @DisplayName("keeps the view in step with what was written after it was handed out")
    void testViewFollowsLaterWrites() {
        final BlockStorage storage = storage();
        final List<Section> views = storage.views();

        storage.setBlock(1, 2, 3, Block.STONE);

        assertEquals(Block.STONE.stateId(), views.get(0).blockPalette().get(1, 2, 3),
                "a view that was taken before a write has to show the write, or a caller which "
                        + "holds one is reading a chunk that no longer exists");
    }
```

New imports for the test file: `net.minestom.server.instance.Section`, `java.util.List`, `org.junit.jupiter.api.Assertions.assertFalse`, `org.junit.jupiter.api.Assertions.assertSame`.

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-instance:test --tests "*BlockStorageTest*"
```

Expected: compilation failure — `view`, `views`, `shared` and `materialisedSections` do not exist on `BlockStorage`.

- [ ] **Step 3: Add the four members to the interface**

Insert into `BlockStorage.java`, after `sectionCount()`:

```java
    /**
     * Hands out one section of this storage as it stands, without creating one.
     * <p>
     * This is the read-only counterpart of {@link #section(int)} and the difference between the two
     * is the whole economy of a lazy layout. {@link #section(int)} exists to answer
     * {@code Chunk#getSection(int)}, which gives a {@code Section} to a caller this storage knows
     * nothing about — a chunk loader, the light engine of Minestom, the generator of an
     * {@code InstanceContainer} — and every one of those may write into it, so the slot has to hold a
     * section of its own before it is handed over. This method promises the opposite: the caller only
     * reads, so an implementation which shares one section between every empty slot may hand that
     * shared section out instead of creating a private one.
     * </p>
     * <p>
     * The contract that comes with it is therefore sharp, and violating it corrupts more than one
     * chunk: <strong>the returned section must not be written to</strong>, neither through its
     * palettes nor through its light carriers, and it must not be kept beyond the call. A write
     * through a shared section is not a write to this chunk, it is a write to every chunk in the
     * process whose slot at that height happens to be empty.
     * </p>
     * <p>
     * The section a view answers with is always the one the storage currently holds, so a view taken
     * before a write shows the write. An implementation must not answer from a snapshot.
     * </p>
     *
     * @param section the index of the section, counted from the bottom one
     * @return the section as it stands, which may be shared with other chunks
     */
    Section view(int section);

    /**
     * Hands out the sections of this storage as they stand, without creating any.
     * <p>
     * The same contract as {@link #view(int)}, over the whole chunk: read only, do not keep, and
     * expect a shared section wherever the chunk holds nothing. An implementation is expected to
     * answer with a list it owns rather than with a fresh one, because this is the method the packet
     * builder of a chunk walks, and a list allocated per send is a cost this stage exists to remove
     * rather than to add.
     * </p>
     *
     * @return the sections as they stand, from the bottom one upwards
     */
    List<Section> views();

    /**
     * Reports whether a section is still shared with other chunks rather than owned by this one.
     * <p>
     * The question a caller which is about to write needs answered without triggering the write it is
     * asking about. {@code FalcoInstance} uses it to decide whether a generated section is worth
     * committing at all, and the tests of this stage use it to prove that a saving happened rather
     * than assuming it.
     * </p>
     *
     * @param section the index of the section, counted from the bottom one
     * @return whether the slot still points at a section this storage does not own
     */
    boolean shared(int section);

    /**
     * Reports how many sections this storage owns rather than shares.
     * <p>
     * The one number that makes the whole stage assertable. Every claim about a saving is a claim
     * about this counter, and every boundary method that materialises raises it, so a test can state
     * exactly what a chunk send, a generator run or a save costs instead of estimating it.
     * </p>
     *
     * @return the amount of sections this storage holds of its own, between zero and
     *         {@link #sectionCount()}
     */
    int materialisedSections();
```

Raise the class Javadoc to `@version 2.0.0` and add a section to it explaining the split, in the style the file already uses:

```java
 * <h2>Two ways to reach a section, and why that is not one too many</h2>
 * <p>
 * {@link #section(int)} and {@link #sections()} answer {@code Chunk#getSection(int)} and
 * {@code Chunk#getSections()}, which are public methods of Minestom that hand a {@code Section} to
 * an arbitrary caller. A storage cannot know whether such a caller reads or writes, so those two
 * have to produce a section the chunk owns. {@link #view(int)} and {@link #views()} are for the
 * chunk itself, which does know: its packet builder, its light data builder and its heightmap scan
 * only read. Without the second pair a lazy layout would be undone from inside the very class that
 * chose it, on the first packet a chunk sends.
 * </p>
```

- [ ] **Step 4: Implement the four members in `SectionBlockStorage`**

Append to `SectionBlockStorage.java`, after `sectionCount()`:

```java
    @Override
    public Section view(int section) {
        return section(section);
    }

    @Override
    public List<Section> views() {
        return this.sections;
    }

    @Override
    public boolean shared(int section) {
        return false;
    }

    @Override
    public int materialisedSections() {
        return this.sections.size();
    }
```

Raise the class Javadoc to `@version 1.2.0` and add one paragraph saying why all four are constant answers here:

```java
 * <p>
 * The four members that exist for a lazy layout are constant answers in this one. Every section is
 * allocated in the constructor, so nothing is ever shared and nothing is ever materialised: a view
 * is the section, {@code shared} is always false and {@code materialisedSections} is the section
 * count. That is not a stub — it is what makes this class usable as the eager control in every
 * comparison of the next stage, and it is why the same interface can describe both layouts without
 * either of them carrying a flag about which one it is.
 * </p>
```

- [ ] **Step 5: Run the test**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-instance:test --tests "*BlockStorageTest*"
```

Expected: PASS, including the three new cases.

- [ ] **Step 6: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/BlockStorage.java \
        falco-instance/src/main/java/net/onelitefeather/falco/instance/SectionBlockStorage.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/BlockStorageTest.java
git commit -m "feat(instance): give the storage a read-only way to look at a section"
```

---

### Task 2: `LazySectionBlockStorage`, the flyweight

**Files:**
- Create: `falco-instance/src/main/java/net/onelitefeather/falco/instance/LazySectionBlockStorage.java`
- Modify: `falco-instance/src/test/java/net/onelitefeather/falco/instance/BlockStorageTest.java`
- Create: `falco-instance/src/test/java/net/onelitefeather/falco/instance/LazySectionBlockStorageTest.java`

**Interfaces:**
- Consumes: `BlockStorage` from Task 1.
- Produces: `LazySectionBlockStorage(int minSection, int sectionCount)` and `LazySectionBlockStorage(int minSection, List<Section> sections)`. Task 3 constructs the first, Task 6 reads through `view`/`shared`/`section`.

**Reference:** `LazySectionBenchmark.LazySections` in `falco-benchmarks` is the prototype of this class and its Javadoc carries the reasoning that was already measured. Read it first. The differences are that this class also carries biomes, the section index offset and the copy semantics, and that it lives where a chunk can use it.

**Covers:** US-2.01, US-2.02, US-2.07, US-2.09.

- [ ] **Step 1: Write the failing test**

Create `LazySectionBlockStorageTest.java`:

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("The lazy block storage of a chunk")
class LazySectionBlockStorageTest {

    private static final int SECTIONS = 24;
    private static final int MIN_SECTION = -4;

    @BeforeAll
    static void server() {
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }
    }

    private static LazySectionBlockStorage storage() {
        return new LazySectionBlockStorage(MIN_SECTION, SECTIONS);
    }

    @Test
    @DisplayName("owns no section at all before anything is written")
    void testNothingIsMaterialisedUpFront() {
        final LazySectionBlockStorage storage = storage();

        assertEquals(0, storage.materialisedSections());
        for (int section = 0; section < SECTIONS; section++) {
            assertTrue(storage.shared(section), "section " + section + " has to start out shared");
        }
    }

    @Test
    @DisplayName("shares one and the same section between every empty slot and every chunk")
    void testEverySharedSlotIsTheSameObject() {
        final LazySectionBlockStorage first = storage();
        final LazySectionBlockStorage second = storage();
        final Section shared = first.view(0);

        for (int section = 0; section < SECTIONS; section++) {
            assertSame(shared, first.view(section));
            assertSame(shared, second.view(section));
        }
    }

    @Test
    @DisplayName("materialises exactly the section that was written to and leaves the others shared")
    void testAWriteMaterialisesOneSection() {
        final LazySectionBlockStorage storage = storage();

        storage.setBlock(1, 20, 3, Block.STONE);

        assertEquals(1, storage.materialisedSections());
        assertFalse(storage.shared(1), "y=20 belongs to section index 1 of a chunk starting at -64");
        for (int section = 0; section < SECTIONS; section++) {
            if (section == 1) continue;
            assertTrue(storage.shared(section), "section " + section + " was not written to");
        }
        assertEquals(Block.STONE, storage.getBlock(1, 20, 3, Block.Getter.Condition.NONE));
        assertEquals(Block.AIR, storage.getBlock(1, 36, 3, Block.Getter.Condition.NONE));
    }

    @Test
    @DisplayName("does not materialise a section that is written air, but does for cave air")
    void testWritingAirLeavesTheSlotShared() {
        final LazySectionBlockStorage storage = storage();

        storage.setBlock(1, 20, 3, Block.AIR);

        assertEquals(0, storage.materialisedSections(),
                "writing the state the shared section already holds everywhere changes nothing, and "
                        + "a loader that walks a whole chunk writing air would otherwise materialise "
                        + "every section it touched");

        storage.setBlock(1, 20, 3, Block.CAVE_AIR);

        assertEquals(1, storage.materialisedSections(),
                "cave air is a different state id from air and has to be stored");
        assertEquals(Block.CAVE_AIR, storage.getBlock(1, 20, 3, Block.Getter.Condition.NONE));
    }

    @Test
    @DisplayName("answers a read of a shared section without touching a palette")
    void testReadingASharedSectionDoesNotMaterialise() {
        final LazySectionBlockStorage storage = storage();

        for (int y = -64; y < 320; y += 16) {
            assertEquals(Block.AIR, storage.getBlock(0, y, 0, Block.Getter.Condition.NONE));
        }
        assertEquals(0, storage.materialisedSections());
    }

    @Test
    @DisplayName("materialises every section when the boundary hands them out")
    void testTheBoundaryMaterialisesEverything() {
        final LazySectionBlockStorage byOne = storage();
        final LazySectionBlockStorage byAll = storage();

        byOne.section(5);
        assertEquals(1, byOne.materialisedSections(),
                "section(int) is the boundary for one section, not for the chunk");

        byAll.sections();
        assertEquals(SECTIONS, byAll.materialisedSections(),
                "sections() hands the whole chunk to a caller that may write to any of it");
    }

    @Test
    @DisplayName("materialises with a fresh section rather than a clone of the shared one")
    void testMaterialisationDoesNotCloneTheFlyweight() {
        final LazySectionBlockStorage storage = storage();
        final Section shared = storage.view(0);

        storage.setBlock(0, 0, 0, Block.STONE);
        final Section materialised = storage.view(4);

        assertSame(shared, materialised, "y=0 is section index 4, which was not written to");

        final Section written = storage.view(4 + 0);
        assertNotSame(shared, storage.view(4), "guard against the fixture drifting");
        assertEquals(0, written.skyLight().array().length,
                "a materialised section has no light; Section#clone would have installed "
                        + "LightCompute.EMPTY_CONTENT through SkyLight#set and raised needsSend");
        assertFalse(written.skyLight().requiresSend(),
                "a section that has never been lit has nothing to send");
    }

    @Test
    @DisplayName("copies without materialising what the original had not materialised")
    void testCopyKeepsSharing() {
        final LazySectionBlockStorage original = storage();
        original.setBlock(1, 20, 3, Block.STONE);

        final BlockStorage copy = original.copy();

        assertEquals(1, copy.materialisedSections());
        assertEquals(Block.STONE, copy.getBlock(1, 20, 3, Block.Getter.Condition.NONE));

        copy.setBlock(1, 20, 3, Block.DIRT);
        assertEquals(Block.STONE, original.getBlock(1, 20, 3, Block.Getter.Condition.NONE),
                "a copy that shared a materialised section would change the original");
    }

    @Test
    @DisplayName("returns every slot to the shared section when it is cleared")
    void testClearReleasesEverySection() {
        final LazySectionBlockStorage storage = storage();
        storage.setBlock(1, 20, 3, Block.STONE);
        final Section materialised = storage.view(1);

        storage.clear();

        assertEquals(0, storage.materialisedSections());
        assertEquals(Block.AIR, storage.getBlock(1, 20, 3, Block.Getter.Condition.NONE));
        assertEquals(0, materialised.blockPalette().count(),
                "a caller holding the section from before the reset has to see it emptied, which is "
                        + "what Section#clear does and what DynamicChunk#reset relies on");
    }
}
```

The eighth test is deliberately awkward and has to stay that way: it asserts a property of the *materialised* section, which is only reachable through a view of the slot that was written. Fix the indices when writing it so that the section under assertion really is the written one — `y = 0` is index `4` for a chunk whose bottom section is `-4`.

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-instance:test --tests "*LazySectionBlockStorageTest*"
```

Expected: compilation failure — `LazySectionBlockStorage` does not exist.

- [ ] **Step 3: Write the implementation**

Create `LazySectionBlockStorage.java`:

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.utils.validate.Check;
import net.minestom.server.world.biome.Biome;
import org.jetbrains.annotations.ApiStatus;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The {@link LazySectionBlockStorage} class stores the blocks of a chunk in Minestom {@link Section}
 * objects again, but allocates one only for a section that holds something: every section which is
 * nothing but air points at one shared instance which no chunk owns and none writes to.
 * <p>
 * This is the layout the whole design was built for, and the reason it can exist at all is that
 * stage 1 moved the storage out of the chunk. The pattern fits nowhere else. {@code Section} is a
 * {@code record} and therefore final, and {@code Palette} is declared
 * {@code public sealed interface Palette permits PaletteImpl}, so neither a lazy section nor a lazy
 * palette can be handed to Minestom — the sharing has to live one level above both, which is exactly
 * where {@link BlockStorage} sits.
 * </p>
 * <p>
 * What it is worth was counted rather than assumed. A generated overworld holds {@code 62,24 %} of
 * the sections of its finished chunks as pure air, over four hundred and forty one chunks around one
 * spawn, and at that share the layout saves {@code 2 911} bytes per chunk. Constructing a chunk this
 * way allocates {@code 2 104} against {@code 7 096} bytes. Both figures come from
 * {@code EmptySectionCensusTest} and {@code LazySectionBenchmark} in {@code falco-benchmarks} and
 * carry the conditions stated there; neither is a projection.
 * </p>
 *
 * <h2>Why a first write allocates instead of cloning the shared section</h2>
 * <p>
 * The obvious copy on write step is {@code EMPTY.clone()} and it is the wrong one.
 * {@code Section#clone} creates two fresh light carriers and then calls
 * {@code skyLight.set(this.skyLight.array())} on them. For the shared section {@code array()} answers
 * with {@code LightCompute.UNSET_CONTENT}, a zero length array, and {@code SkyLight#set} turns that
 * into {@code LightCompute.EMPTY_CONTENT} — the process wide, mutable {@code byte[2048]} — while
 * also setting {@code isValidBorders} and raising {@code needsSend}. A section materialised that way
 * would announce that it has light to send before anything ever lit it, and would hold its
 * {@code content} field pointing at an array shared with every other section built the same way.
 * {@code new Section()} produces the same blocks, leaves the light unset and allocates less, which is
 * what {@code LazySectionBenchmark#firstWriteLazy} measured at {@code 2 720 B/op}.
 * </p>
 *
 * <h2>What is shared, and the one rule that keeps it safe</h2>
 * <p>
 * {@link #EMPTY} is never written to by this class. Every write path replaces the slot first, and the
 * two accessors that can hand the shared section to a caller — {@link #view(int)} and
 * {@link #views()} — document in {@link BlockStorage} that their result is read only. The accessors
 * Minestom itself reaches, {@link #section(int)} and {@link #sections()}, materialise instead, because
 * a chunk loader or the generator of an {@code InstanceContainer} receives a {@code Section} through
 * them and writes into its palettes directly. That is the honest price of this layout and it is
 * stated rather than hidden: any caller of {@code Chunk#getSections()} makes this storage as
 * expensive as the eager one.
 * </p>
 * <p>
 * A write of the state the shared section already holds everywhere is skipped rather than
 * materialised. That is not an optimisation of a rare case: a loader or a generator which walks a
 * whole chunk and writes air into the parts that are air would otherwise materialise every section it
 * touched and this class would be strictly worse than the eager one. The check is on the state id and
 * not on {@code Block#isAir()}, because cave air and void air are air by that predicate and are
 * different states which have to be stored.
 * </p>
 * <p>
 * Implementations of {@link BlockStorage} are not thread-safe and this one is no exception. The
 * caller holds the write lock of the chunk, which is what makes the read of a slot, the decision to
 * materialise and the store of the new section one step rather than three.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public final class LazySectionBlockStorage implements BlockStorage {

    /**
     * The section every empty slot of every chunk of this process points at.
     * <p>
     * It is never written to. Every write path of this class replaces the slot before it writes, and
     * the two read-only accessors document that a caller must not write through what they return. A
     * write that reached this object would not corrupt one chunk, it would corrupt every chunk whose
     * slot at that height happens to be empty.
     * </p>
     */
    static final Section EMPTY = new Section();

    /**
     * The state id a write has to carry to be worth materialising a shared section for.
     * <p>
     * Read from the registry rather than written down as {@code 0}, so that a Minecraft version which
     * renumbered the states cannot silently turn this guard into one that drops real blocks.
     * </p>
     */
    private static final int AIR_STATE = Block.AIR.stateId();

    /**
     * The edge length of the biome grid of a section.
     * <p>
     * Named here for the same reason {@code SectionBlockStorage} names it: {@code Section} has no
     * constant for it, and a wrong biome divisor is silent rather than loud.
     * </p>
     */
    private static final int BIOME_SIZE = 4;

    private static final DynamicRegistry<Biome> BIOME_REGISTRY = MinecraftServer.getBiomeRegistry();

    private final int minSection;
    private final Section[] sections;

    /**
     * The list {@link #views()} answers with.
     * <p>
     * It reads through to {@link #sections} rather than copying it, which is what lets a caller take
     * it once and still see a section that was materialised afterwards. It is also why {@link #views()}
     * allocates nothing: the chunk packet builder walks this list on every send, and a list built per
     * send would be a cost this class was written to remove rather than to add.
     * </p>
     */
    private final List<Section> view = new AbstractList<>() {

        @Override
        public Section get(int index) {
            return LazySectionBlockStorage.this.sections[index];
        }

        @Override
        public int size() {
            return LazySectionBlockStorage.this.sections.length;
        }
    };

    /**
     * Creates a storage in which every section is shared.
     *
     * @param minSection   the index of the bottom section of the chunk
     * @param sectionCount the amount of sections the chunk spans
     */
    public LazySectionBlockStorage(int minSection, int sectionCount) {
        this.minSection = minSection;
        this.sections = new Section[sectionCount];
        Arrays.fill(this.sections, EMPTY);
    }

    /**
     * Creates a storage which takes over the given sections.
     * <p>
     * A section which is identical to the shared one is not detected here and is taken over as it
     * stands. Deciding otherwise would mean walking four thousand and ninety six positions per
     * section to find out, which is more than the slot is worth; a caller which knows that a section
     * is empty passes {@link #EMPTY} for it.
     * </p>
     *
     * @param minSection the index of the bottom section of the chunk
     * @param sections   the sections, from the bottom one upwards
     */
    public LazySectionBlockStorage(int minSection, List<Section> sections) {
        this.minSection = minSection;
        this.sections = sections.toArray(new Section[0]);
    }

    @Override
    public Block getBlock(int x, int y, int z, Block.Getter.Condition condition) {
        final Section section = this.sections[CoordConversion.globalToChunk(y) - this.minSection];

        // US-2.07: a shared section is air everywhere, so the palette is not reached at all. The
        // palette of the shared section would answer the same; this is a shortcut, not a special case.
        if (section == EMPTY) {
            return Block.AIR;
        }
        final int stateId = section.blockPalette()
                .get(x, CoordConversion.globalToSectionRelative(y), z);

        return Objects.requireNonNullElse(Block.fromStateId(stateId), Block.AIR);
    }

    @Override
    public void setBlock(int x, int y, int z, Block block) {
        final int index = CoordConversion.globalToChunk(y) - this.minSection;
        final int stateId = block.stateId();

        if (stateId == AIR_STATE && this.sections[index] == EMPTY) {
            return;
        }
        materialise(index).blockPalette()
                .set(x, CoordConversion.globalToSectionRelative(y), z, stateId);
    }

    @Override
    public RegistryKey<Biome> getBiome(int x, int y, int z) {
        final Section section = this.sections[CoordConversion.globalToChunk(y) - this.minSection];
        final int id = section.biomePalette()
                .get(x / BIOME_SIZE,
                        CoordConversion.globalToSectionRelative(y) / BIOME_SIZE,
                        z / BIOME_SIZE);

        final RegistryKey<Biome> biome = BIOME_REGISTRY.getKey(id);

        Check.notNull(biome, "Biome with id {0} is not registered", id);
        return biome;
    }

    @Override
    public void setBiome(int x, int y, int z, RegistryKey<Biome> biome) {
        final int id = BIOME_REGISTRY.getId(biome);

        if (id == -1) throw new IllegalStateException("Biome has not been registered: " + biome.key());

        final int index = CoordConversion.globalToChunk(y) - this.minSection;

        // Unlike a block write, a biome write is not skipped when it matches what the shared section
        // holds. The zero of a biome palette is a registry id and not a sentinel, so the id which
        // happens to be zero belongs to a real biome that a caller may legitimately want stored, and
        // it is not this class's business to decide that writing it is a no-op.
        materialise(index).biomePalette()
                .set(x / BIOME_SIZE,
                        CoordConversion.globalToSectionRelative(y) / BIOME_SIZE,
                        z / BIOME_SIZE, id);
    }

    @Override
    public List<Section> sections() {
        for (int index = 0; index < this.sections.length; index++) {
            materialise(index);
        }
        return this.view;
    }

    @Override
    public Section section(int section) {
        return materialise(section);
    }

    @Override
    public Section view(int section) {
        return this.sections[section];
    }

    @Override
    public List<Section> views() {
        return this.view;
    }

    @Override
    public boolean shared(int section) {
        return this.sections[section] == EMPTY;
    }

    @Override
    public int materialisedSections() {
        int owned = 0;

        for (Section section : this.sections) {
            if (section != EMPTY) owned++;
        }
        return owned;
    }

    @Override
    public int sectionCount() {
        return this.sections.length;
    }

    @Override
    public BlockStorage copy() {
        final Section[] copied = new Section[this.sections.length];

        for (int index = 0; index < copied.length; index++) {
            final Section section = this.sections[index];
            copied[index] = section == EMPTY ? EMPTY : section.clone();
        }
        return new LazySectionBlockStorage(this.minSection, copied);
    }

    @Override
    public void clear() {
        for (int index = 0; index < this.sections.length; index++) {
            final Section section = this.sections[index];

            if (section == EMPTY) continue;
            // Emptied as well as released. A caller which took the section through the boundary
            // before the reset holds a reference this class cannot reach, and DynamicChunk#reset
            // leaves such a caller with an emptied section rather than with a stale one.
            section.clear();
            this.sections[index] = EMPTY;
        }
    }

    /**
     * Gives a slot a section of its own, if it does not have one yet.
     *
     * @param index the index of the section, counted from the bottom one
     * @return the section the slot holds afterwards, which this storage owns
     */
    private Section materialise(int index) {
        Section section = this.sections[index];

        if (section == EMPTY) {
            section = new Section();
            this.sections[index] = section;
        }
        return section;
    }

    /**
     * Creates a storage over an array this class takes ownership of.
     *
     * @param minSection the index of the bottom section of the chunk
     * @param sections   the sections, from the bottom one upwards
     */
    private LazySectionBlockStorage(int minSection, Section[] sections) {
        this.minSection = minSection;
        this.sections = sections;
    }
}
```

**Two things to verify against the pinned sources before running anything.** `Block.CAVE_AIR` has to exist and its state id has to differ from `Block.AIR`; and `Block.AIR.stateId()` has to be resolvable in a static initialiser without `MinecraftServer.init()` having run, which is the same assumption `SectionBlockStorage` already makes with `MinecraftServer.getBiomeRegistry()`.

```bash
S=/tmp/claude-1000/-mnt-projects-oss-onelitefeather-Falco/34edb948-9dfe-4540-9666-9e29f0d44d7b/scratchpad/minestom-src
grep -n "CAVE_AIR" "$S/net/minestom/server/instance/block/Block.java" | head -3
```

If the static initialiser turns out to need the server, move `AIR_STATE` into a holder class or read it in the constructor — but state which of the two happened, because it is a fact about Minestom and not about this class.

- [ ] **Step 4: Make the contract test run against both implementations**

`BlockStorageTest` was written against the interface in stage 1 precisely so this would cost nothing. Replace its private factory with a parameter:

```java
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> storages() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("eager",
                        (java.util.function.Supplier<BlockStorage>) () -> new SectionBlockStorage(MIN_SECTION, SECTIONS)),
                org.junit.jupiter.params.provider.Arguments.of("lazy",
                        (java.util.function.Supplier<BlockStorage>) () -> new LazySectionBlockStorage(MIN_SECTION, SECTIONS)));
    }
```

and turn each `@Test` into a `@ParameterizedTest(name = "{0}")` `@MethodSource("storages")` taking `(String name, Supplier<BlockStorage> factory)`, with `factory.get()` where `storage()` was. Import the parameterised annotations properly rather than fully qualifying them in the final file; the fully qualified form above is only there so the snippet stands on its own.

Three of the existing cases move out rather than being parameterised, because they are statements about the eager layout and are already asserted for the lazy one in `LazySectionBlockStorageTest`:

- `testEagerStorageSharesNothing` stays a plain `@Test` on `SectionBlockStorage`.
- `testViewAndSectionAgree` stays a plain `@Test` on `SectionBlockStorage`; `assertSame(section, view)` is false for the lazy storage by construction.
- `testSectionCount`'s `sections().size()` half stays, since calling `sections()` on the lazy storage materialises everything and would make the case assert the opposite of what this stage is about. Its `sectionCount()` half is parameterised.

- [ ] **Step 5: Run both test classes**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-instance:test --tests "*BlockStorageTest*" --tests "*LazySectionBlockStorageTest*"
```

Expected: PASS. Every contract case passes for both implementations, and the flyweight cases pass for the lazy one. A failure of `testColumnOutsideTheChunkIsRejected` under the lazy storage means the shortcut in `getBlock` swallowed a coordinate the eager one rejected — fix the shortcut, not the test: an out-of-range `x` has to reach a palette and be refused there, which it does for every materialised section and for a shared one only through `setBlock`. If the two layouts genuinely cannot agree on that case, that is a finding about the contract and belongs in the stage result, not in a weakened assertion.

- [ ] **Step 6: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/LazySectionBlockStorage.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/LazySectionBlockStorageTest.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/BlockStorageTest.java
git commit -m "feat(instance): share one empty section instead of allocating twenty-four"
```

---

### Task 3: `FalcoChunk` stops materialising its own sections

**Files:**
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoChunk.java`
- Test: covered by Tasks 4 and 9, and by the existing `FalcoChunkEquivalenceTest` in both modules

**Interfaces:**
- Consumes: `LazySectionBlockStorage`, `BlockStorage#views()`, `#view(int)` from Tasks 1–2.
- Produces: `FalcoChunk` whose default storage is lazy. `FalcoChunk#storage()` is unchanged and is what Task 4 asserts through.

**Covers:** US-2.01 and US-2.09 at the chunk.

**Why this is where the stage is won or lost.** `Heightmap#getHighestBlockSection` (`Heightmap.java:134`) walks a chunk from the top downwards calling `chunk.getSection(sectionY).blockPalette()` until it finds a palette with a non-zero count. Those are precisely the empty top sections of an overworld. `FalcoChunk#calculateFullHeightmap` calls it, `setBlock` calls `calculateFullHeightmap` on the first write, and `createChunkPacket` reaches it through `getHeightmaps`. Left as it is, the first block written into a fresh chunk materialises all twenty-four sections and this stage saves nothing.

- [ ] **Step 1: Read the two methods that force the issue**

```bash
S=/tmp/claude-1000/-mnt-projects-oss-onelitefeather-Falco/34edb948-9dfe-4540-9666-9e29f0d44d7b/scratchpad/minestom-src
sed -n '60,95p' "$S/net/minestom/server/instance/heightmap/Heightmap.java"
sed -n '128,142p' "$S/net/minestom/server/instance/heightmap/Heightmap.java"
```

Note what can and cannot be replaced. `getHighestBlockSection` is `public static` and takes the chunk — it can be replaced by a method of `FalcoChunk` that reads the storage. `refresh(int x, int z, int startY)` cannot: it ends in the `private` `setHeightY`, and `heights` is `private final`, so a subclass override has no way to write the result back. The consequence is stated rather than worked around: an empty section **below** the highest non-empty one is still materialised by a heightmap refresh. In a generated overworld that is no sections at all — the census reports the sections below world height 64 as empty in `0,0 %` of the chunks — but in a world with floating islands it is not, and Task 4 measures which.

- [ ] **Step 2: Change the four places the chunk reads its own sections**

Default storage:

```java
    public FalcoChunk(Instance instance, int chunkX, int chunkZ) {
        super(instance, chunkX, chunkZ, true);
        // Must be built here and not in a field initialiser: the super constructor is what computes
        // minSection and maxSection, and the storage is sized from them.
        this.storage = new LazySectionBlockStorage(minSection, maxSection - minSection);
    }
```

The packet builder, in `createChunkPacket`:

```java
            final byte[] data = NetworkBuffer.makeArray(networkBuffer -> {
                for (Section section : this.storage.views()) {
                    final short blockCount = (short) section.blockPalette().count();
                    final short liquidCount = (short) (blockCount > 0 ? 1 : 0); //TODO(26.1) proper fluid count
                    networkBuffer.write(sectionSerializer,
                            new ChunkData.Section(blockCount, liquidCount, section.blockPalette(), section.biomePalette()));
                }
            });
```

The light data builder, in `createLightData`:

```java
        for (Section section : this.storage.views()) {
```

The snapshot, in `updateSnapshot`:

```java
        final List<Section> sections = this.storage.views();
        final Section[] clonedSections = new Section[sections.size()];
        for (int i = 0; i < clonedSections.length; i++) {
            final Section section = sections.get(i);
            // A shared section must not end up inside a snapshot even though it never changes: a
            // snapshot is read without any lock and by callers this class does not know, and one that
            // wrote into it would write into every empty section of the process. A fresh section is
            // the same content and cannot be aliased.
            clonedSections[i] = this.storage.shared(i) ? new Section() : section.clone();
        }
```

And the highest-section scan, replacing the call to Minestom's static helper:

```java
    /**
     * Reports the world height at which a heightmap scan of this chunk may start.
     * <p>
     * The body of {@code Heightmap#getHighestBlockSection} with one substitution: it reaches its
     * sections through {@code Chunk#getSection(int)}, which is the boundary that hands a section to
     * an arbitrary caller and therefore has to create one. Walking a chunk from the build limit
     * downwards through that method materialises exactly the empty top sections this chunk exists not
     * to hold, on the first block anybody writes into it. Reading through
     * {@link BlockStorage#view(int)} answers the same question and creates nothing.
     * </p>
     * <p>
     * The arithmetic is copied rather than re-derived, including the descent by one section per step
     * and the break on the first palette whose count is not zero, because the two have to agree: a
     * heightmap computed from a different starting height than Minestom's is not a faster heightmap,
     * it is a different one.
     * </p>
     *
     * @return the world Y at which the scan starts
     */
    private int highestBlockSection() {
        int y = instance.getCachedDimensionType().maxY();

        for (int index = this.storage.sectionCount() - 1; index >= 0; index--) {
            if (this.storage.view(index).blockPalette().count() != 0) break;
            y -= CHUNK_SECTION_SIZE;
        }
        return y;
    }

    private void calculateFullHeightmap() {
        assertWriteLock();
        final int startY = highestBlockSection();
        this.motionBlocking.refresh(startY);
        this.worldSurface.refresh(startY);
        this.needsCompleteHeightmapRefresh = false;
    }
```

`getSections()` and `getSection(int)` stay exactly as they are. They are the boundary of US-2.09 and their new behaviour comes entirely from the storage behind them.

Raise `@version` to `3.0.0` and add a section to the class Javadoc:

```java
 * <h2>Which of its own sections this chunk is allowed to create</h2>
 * <p>
 * None, on any path of its own. The packet it sends, the light data it collects, the snapshot it
 * takes and the scan that starts a heightmap refresh all read through {@link BlockStorage#views()}
 * and {@link BlockStorage#view(int)}, which hand out whatever the storage currently holds and create
 * nothing. Only {@link #getSections()} and {@link #getSection(int)} materialise, because those two
 * are what Minestom calls when it is about to write into a section — the generator of an
 * {@code InstanceContainer}, a chunk loader, the light engine — and a storage cannot tell a reader
 * from a writer through them.
 * </p>
 * <p>
 * The one place where that boundary is crossed against this chunk's will is the heightmap.
 * {@code Heightmap#refresh(int, int, int)} reaches its sections through {@code Chunk#getSection(int)}
 * and cannot be overridden, because it ends in a {@code private} setter over a {@code private}
 * array. A refresh therefore materialises every empty section it walks through below the highest
 * non-empty one. In a generated overworld that is none; the height profile of the census puts the
 * empty share below world height sixty-four at {@code 0,0 %}. In a world of floating islands it is
 * not none, and {@code SectionMaterialisationTest} states the number rather than leaving it to the
 * imagination.
 * </p>
```

- [ ] **Step 3: Compile and run the equivalence tests of both modules**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-instance:test
./gradlew :falco-benchmarks:test --tests "*FalcoChunkEquivalenceTest*"
```

Expected: PASS. `FalcoChunkEquivalenceTest` in `falco-benchmarks` is the strongest net on the branch — eighteen fixtures through `MinestomChunks#assertSameBlocks`, every one of the `16·16·16·sectionCount` positions and both heightmaps of every column, plus a scatter batch, a full heightmap refresh and three copy comparisons. If the lazy layout differs from `DynamicChunk` anywhere, this is what says so, and it names the position.

- [ ] **Step 4: Run the neighbouring modules**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-light:test :falco-anvil:test
```

Expected: PASS. `falco-light` and `falco-anvil` reach chunks through `getSections()` (`ChunkLightService:160` and `:405`, `FalcoAnvilLoader:1161`) and `getSection(int)` (`FalcoAnvilLoader:1053`), which materialise. That is the boundary behaving as designed and it must not fail — a failure here means a materialising accessor was missed somewhere.

- [ ] **Step 5: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoChunk.java
git commit -m "refactor(instance): read the chunk's own sections without creating them"
```

---

### Task 4: Count what the boundary actually costs

**Files:**
- Create: `falco-instance/src/test/java/net/onelitefeather/falco/instance/SectionMaterialisationTest.java`

**Interfaces:**
- Consumes: `FalcoChunk#storage()`, `BlockStorage#materialisedSections()`.
- Produces: nothing. This is the acceptance test of the stage and the answer to the open question the spec's §8 records as *"Materialisation at the three boundaries may undo the saving for workloads that call `getSection` often. No workload has been measured for how often that happens."*

**Covers:** the boundary half of US-2.09, and the evidence for US-2.01 and US-2.02 at chunk level.

- [ ] **Step 1: Write the test**

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("What a caller of a Falco chunk makes it allocate")
class SectionMaterialisationTest {

    private static final int SECTIONS = 24;

    private static InstanceContainer container;

    @BeforeAll
    static void server() {
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }
        container = MinecraftServer.getInstanceManager().createInstanceContainer();
    }

    private static FalcoChunk chunk() {
        return new FalcoChunk(container, 0, 0);
    }

    private static int owned(FalcoChunk chunk) {
        return chunk.storage().materialisedSections();
    }

    @Test
    @DisplayName("a fresh chunk owns nothing")
    void testAFreshChunkOwnsNoSection() {
        assertEquals(0, owned(chunk()));
    }

    @Test
    @DisplayName("reading a whole empty chunk owns nothing")
    void testReadingOwnsNothing() {
        final FalcoChunk chunk = chunk();

        chunk.lockReadLock();
        try {
            for (int y = -64; y < 320; y++) {
                chunk.getBlock(0, y, 0);
            }
        } finally {
            chunk.unlockReadLock();
        }
        assertEquals(0, owned(chunk));
    }

    @Test
    @DisplayName("one write owns one section, and the heightmap refresh that follows owns none")
    void testOneWriteOwnsOneSection() {
        final FalcoChunk chunk = chunk();

        chunk.lockWriteLock();
        try {
            chunk.setBlock(0, 64, 0, Block.STONE);
        } finally {
            chunk.unlockWriteLock();
        }
        assertEquals(1, owned(chunk),
                "a write refreshes both heightmaps, and the scan that starts them walks the chunk "
                        + "from the build limit downwards; if it goes through Chunk#getSection this "
                        + "number is 24 and the whole stage is worth nothing");
    }

    @Test
    @DisplayName("sending a chunk owns only what the chunk already held")
    void testTheFullDataPacketOwnsNothing() {
        final FalcoChunk fresh = chunk();

        fresh.getFullDataPacket();
        assertEquals(0, owned(fresh),
                "a chunk that holds nothing has nothing to serialise, and the cached packet is the "
                        + "hottest boundary there is");

        final FalcoChunk written = chunk();
        written.lockWriteLock();
        try {
            written.setBlock(0, 64, 0, Block.STONE);
        } finally {
            written.unlockWriteLock();
        }
        written.getFullDataPacket();
        assertEquals(1, owned(written));
    }

    @Test
    @DisplayName("asking for one section through the Minestom boundary owns exactly that one")
    void testGetSectionOwnsOne() {
        final FalcoChunk chunk = chunk();

        chunk.getSection(4);

        assertEquals(1, owned(chunk));
    }

    @Test
    @DisplayName("asking for the section list through the Minestom boundary owns the whole chunk")
    void testGetSectionsOwnsEverything() {
        final FalcoChunk chunk = chunk();

        chunk.getSections();

        assertEquals(SECTIONS, owned(chunk),
                "this is the price of the boundary and it is stated rather than hidden: a caller "
                        + "which reaches into the chunk this way makes the lazy layout cost exactly "
                        + "what the eager one costs");
    }

    @Test
    @DisplayName("a copy owns what the original owned")
    void testCopyOwnsWhatWasOwned() {
        final FalcoChunk chunk = chunk();

        chunk.lockWriteLock();
        try {
            chunk.setBlock(0, 64, 0, Block.STONE);
        } finally {
            chunk.unlockWriteLock();
        }
        chunk.lockReadLock();
        final Chunk copy;
        try {
            copy = chunk.copy(container, 1, 1);
        } finally {
            chunk.unlockReadLock();
        }
        assertEquals(1, ((FalcoChunk) copy).storage().materialisedSections());
    }

    @Test
    @DisplayName("a heightmap refresh over a gap owns the empty sections under the terrain")
    void testAGapUnderTheTerrainIsTheKnownLeak() {
        final FalcoChunk chunk = chunk();

        chunk.lockWriteLock();
        try {
            chunk.setBlock(0, -64, 0, Block.STONE);
            chunk.setBlock(0, 200, 0, Block.STONE);
        } finally {
            chunk.unlockWriteLock();
        }
        chunk.getFullDataPacket();

        final int owned = owned(chunk);

        assertEquals(SECTIONS - 7, owned,
                "Heightmap#refresh(int,int,int) reaches its sections through Chunk#getSection and "
                        + "cannot be overridden, so every empty section between the floor block and "
                        + "the one at y=200 is materialised by the column scan. The seven that stay "
                        + "shared are the ones above y=207. This number is the known cost of the "
                        + "heightmap and it is asserted so that it cannot grow unnoticed.");
    }
}
```

- [ ] **Step 2: Run it and let the last case tell the truth**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-instance:test --tests "*SectionMaterialisationTest*"
```

Expected: the first seven PASS. `testAGapUnderTheTerrainIsTheKnownLeak` is written with a number derived from the source and will very likely be wrong on the first run — the descent of `Heightmap#refresh(int,int,int)` skips whole sections through `currentY = (sectionY << 4) - 1` and stops at the first matching block per column, so the count depends on the fixture in a way that is easier to read off than to derive. **Correct the number to what the run reports, and only after reading why it is that number.** Write the reason into the message. A number changed until the test is green without an explanation is a plan failure and is worse than no assertion.

If the reported number is `SECTIONS`, the scan is still going through `Chunk#getSection` somewhere — go back to Task 3 Step 2.

- [ ] **Step 3: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
git add falco-instance/src/test/java/net/onelitefeather/falco/instance/SectionMaterialisationTest.java
git commit -m "test(instance): state what every boundary caller makes a lazy chunk allocate"
```

---

### Task 5: Price `optimize()` before booking it as a gain

**Files:**
- Create: `falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/instance/GeneratorCommitBenchmark.java`

**Interfaces:**
- Consumes: `MinestomChunks#ensureServer`, `#newFalcoInstance`, `#newChunk`, `#fill`, `#release`, `BenchmarkConstants#OVERWORLD_SECTIONS`, `#SEED`.
- Produces: nothing but numbers.

**Covers:** the open risk the spec's §8 records as *"`optimize()` after generation costs time that has not been measured against the generation itself."*

**Why a new class rather than an arm on an existing one.** `SectionAllocationBenchmark` measures what a chunk costs at construction, `LazySectionBenchmark` measures the flyweight, `ChunkComparisonBenchmark` compares two chunk types on the block accessor path. None of them measures the commit step of a generation, and adding an arm to any of them would mix two subjects in one class whose Javadoc argues for exactly one. The byte side of the question is already answered and must not be re-measured: S9 says a chunk whose sections all went direct holds `203 840` bytes against `84 800` for the same content packed indirectly, from `ChunkFootprintTest` and `PaletteFootprintTest`. What is missing is the time, and only the time.

- [ ] **Step 1: Write the benchmark**

```java
package net.onelitefeather.falco.benchmark.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.palette.Palette;
import net.onelitefeather.falco.benchmark.support.BenchmarkConstants;
import net.onelitefeather.falco.benchmark.support.MinestomChunks;
import net.onelitefeather.falco.instance.FalcoInstance;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The {@link GeneratorCommitBenchmark} class measures what it costs to call
 * {@code Palette#optimize(Optimization.SIZE)} on the palettes of a chunk a generator has just
 * filled, and states that cost next to the copy it follows rather than on its own.
 * <p>
 * The byte side of this question is already answered and is not measured again here. A chunk whose
 * sections all ended up in direct mode retains {@code 203 840} bytes against {@code 84 800} for the
 * same content stored indirectly, a factor of {@code 2,4}, and {@code Palette#optimize} has no caller
 * anywhere in the main source tree of Minestom. What nothing in this repository states is the price
 * in time. A saving of more than half the memory of a generated chunk is worth a great deal of time,
 * but "a great deal" is not a measurement, and this stage refuses to book a gain whose cost is
 * unknown.
 * </p>
 *
 * <h2>The three arms and why the middle one exists</h2>
 * <p>
 * {@link #commitPlain()} copies the staged palettes into the sections of a chunk, which is what
 * {@code FalcoInstance#applyGenerator} does today. {@link #commitOptimized()} does the same and then
 * optimises each palette it wrote. The difference between the two is the whole answer, and it is a
 * difference rather than an absolute on purpose: a number for {@code optimize} alone would be
 * compared against nothing, while the commit is the step it was added to.
 * </p>
 * <p>
 * {@link #optimizeAlreadyPacked()} is the control. It optimises palettes which are already at their
 * minimum width, which is the case a server pays on every chunk whose generator did not produce a
 * wide palette in the first place. {@code PaletteImpl#optimize} still walks all four thousand and
 * ninety six entries through {@code getAll} to collect the unique values before it can decide that
 * there is nothing to do, so this arm is not free and its distance from zero is what a generator pays
 * for chunks the optimisation cannot help.
 * </p>
 *
 * <h2>Why the state count is the axis</h2>
 * <p>
 * {@code PaletteImpl#optimize} branches on the number of distinct values it finds: one value collapses
 * to the single value mode through {@code fill}, and anything else goes to {@code downsizeWithPalette}
 * under {@code Optimization.SIZE}. The cost of the collection walk is the same in both cases and the
 * cost of the rewrite is not, so a single state count would answer one of the two questions and hide
 * the other. The axis is the one every other chunk benchmark of this module uses, cut down to the
 * three points that separate the branches.
 * </p>
 *
 * <h2>Running it</h2>
 * <pre>{@code
 * ./gradlew :falco-benchmarks:jmhJar
 * java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
 *     "GeneratorCommitBenchmark" -p distinctStates=1,64,1024 -f 3 -wi 5 -i 5 -prof gc
 * }</pre>
 * <p>
 * {@code -prof gc} is not optional. {@code downsizeWithPalette} allocates a new backing array and the
 * allocation is part of what the optimisation costs, so a run without the profiler reports half the
 * price.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class GeneratorCommitBenchmark {

    /**
     * The amount of distinct block states the staged palettes are filled from.
     */
    @Param({"1", "64", "1024"})
    public int distinctStates;

    /**
     * The instance the fixture chunks are built in.
     */
    private FalcoInstance instance;

    /**
     * The palettes a generator produced, which every arm copies from and never writes to.
     */
    private List<Palette> staged;

    /**
     * The same palettes already reduced to their minimum width, for the control arm.
     */
    private List<Palette> packed;

    /**
     * The sections the arms commit into, rebuilt per invocation is too slow, so they are reused and
     * overwritten; a commit is a full overwrite of every entry, so nothing carries over.
     */
    private Section[] target;

    @Setup(Level.Trial)
    public void setUp() {
        MinestomChunks.ensureServer();
        this.instance = MinestomChunks.newFalcoInstance();

        final Chunk source = MinestomChunks.newChunk(this.instance, 0, 0);
        MinestomChunks.fill(source, this.distinctStates, MinestomChunks.FillShape.RANDOM_RUNS,
                BenchmarkConstants.SEED);

        final List<Section> sections = source.getSections();

        if (sections.size() != BenchmarkConstants.OVERWORLD_SECTIONS) {
            throw new IllegalStateException("The fixture chunk holds " + sections.size()
                    + " sections but the benchmark is written for "
                    + BenchmarkConstants.OVERWORLD_SECTIONS);
        }
        this.staged = new ArrayList<>(sections.size());
        this.packed = new ArrayList<>(sections.size());
        this.target = new Section[sections.size()];

        for (int index = 0; index < sections.size(); index++) {
            final Palette blocks = sections.get(index).blockPalette();

            this.staged.add(blocks.clone());
            final Palette alreadyPacked = blocks.clone();
            alreadyPacked.optimize(Palette.Optimization.SIZE);
            this.packed.add(alreadyPacked);
            this.target[index] = new Section();
        }
        verifyTheFixtureIsWide();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        MinestomChunks.release(this.instance);
        this.instance = null;
    }

    /**
     * Measures the commit as {@code FalcoInstance#applyGenerator} performs it today.
     *
     * @return the sections that were written, so that nothing can be eliminated
     */
    @Benchmark
    public Section[] commitPlain() {
        for (int index = 0; index < this.target.length; index++) {
            this.target[index].blockPalette().copyFrom(this.staged.get(index));
        }
        return this.target;
    }

    /**
     * Measures the same commit with the optimisation this stage adds after it.
     *
     * @return the sections that were written, so that nothing can be eliminated
     */
    @Benchmark
    public Section[] commitOptimized() {
        for (int index = 0; index < this.target.length; index++) {
            final Palette palette = this.target[index].blockPalette();
            palette.copyFrom(this.staged.get(index));
            palette.optimize(Palette.Optimization.SIZE);
        }
        return this.target;
    }

    /**
     * Measures the optimisation of palettes that are already at their minimum width.
     *
     * @return the sections that were written, so that nothing can be eliminated
     */
    @Benchmark
    public Section[] optimizeAlreadyPacked() {
        for (int index = 0; index < this.target.length; index++) {
            final Palette palette = this.target[index].blockPalette();
            palette.copyFrom(this.packed.get(index));
            palette.optimize(Palette.Optimization.SIZE);
        }
        return this.target;
    }

    /**
     * Refuses a fixture in which the optimisation would have nothing to do.
     *
     * @throws IllegalStateException if no staged palette is wider than its packed form, which would
     *                               make every number of this run a measurement of a no-op
     */
    private void verifyTheFixtureIsWide() {
        if (this.distinctStates == 1) {
            return;
        }
        for (int index = 0; index < this.staged.size(); index++) {
            if (this.staged.get(index).bitsPerEntry() > this.packed.get(index).bitsPerEntry()) {
                return;
            }
        }
        throw new IllegalStateException("Not one of the " + this.staged.size() + " staged palettes is "
                + "wider than its optimised form at " + this.distinctStates + " distinct states, so "
                + "this trial would report the cost of an optimisation that changes nothing");
    }
}
```

- [ ] **Step 2: Run it in the scouting configuration and then in the citable one**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-benchmarks:jmh -Pjmh.quick -Pjmh.include="GeneratorCommitBenchmark"
```

Then, for a figure that may be quoted:

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-benchmarks:jmhJar
java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
    "GeneratorCommitBenchmark" -p distinctStates=1,64,1024 -f 3 -wi 5 -i 5 -prof gc
```

Record the three numbers per state count and the ratio `commitOptimized / commitPlain` for the plan's result section. State the machine and its load, as the stage 1 result does.

- [ ] **Step 3: Decide, in writing, before Task 6 changes anything**

Append a short block to this file under `## What optimize() costs`, stating the measured ratio and the conditions. If the optimisation turns out to cost more than the generation it follows, Task 6 still adds it but behind a documented decision that says so — the requirement in the spec is US-2.03, a Must, and a Must that is expensive is a Must with a stated price, not a Must that is dropped.

- [ ] **Step 4: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
git add falco-benchmarks/src/jmh/java/net/onelitefeather/falco/benchmark/instance/GeneratorCommitBenchmark.java \
        docs/superpowers/plans/2026-08-02-falco-lazy-sections.md
git commit -m "test(benchmarks): price the palette optimisation against the commit it follows"
```

---

### Task 6: The generator commits what it filled, and optimises it

**Files:**
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/SectionMaterialisationTest.java` (extended)

**Interfaces:**
- Consumes: `BlockStorage#view(int)`, `#shared(int)`, `#section(int)`, `#sectionCount()`; `SectionBlockStorage(int, List<Section>)`; `FalcoChunk#storage()`.
- Produces: nothing new in the public API. `FalcoInstance#applyGenerator` and `#applyFork` change behaviour only.

**Covers:** US-2.03.

**The problem.** `FalcoInstance#applyGenerator:923` opens with `chunk.getSections()`, which under the lazy storage materialises all twenty-four sections before the generator has written a single block — so a generated chunk would cost exactly what it costs today and S7's `62,24 %` would buy nothing on the path that matters most. `applyFork:1047` reaches `chunk.getSectionAt(sectionStartY)` for every fork section, including ones that carry nothing.

- [ ] **Step 1: Write the failing test**

Append to `SectionMaterialisationTest`:

```java
    @Test
    @DisplayName("a generator owns only the sections it filled, and leaves the palettes packed")
    void testAGeneratorOwnsOnlyWhatItFilled() {
        final FalcoInstance instance = new FalcoInstance(MinecraftServer.getDimensionTypeRegistry()
                .getKey(net.minestom.server.world.DimensionType.OVERWORLD));

        instance.setChunkSupplier(FalcoChunk::new);
        instance.setGenerator(unit -> unit.modifier()
                .fillHeight(-64, 0, Block.STONE));

        final FalcoChunk chunk = (FalcoChunk) instance.loadChunk(0, 0).join();

        assertEquals(4, chunk.storage().materialisedSections(),
                "stone from y=-64 to y=0 fills exactly four sections; the other twenty hold nothing "
                        + "and must stay shared");

        for (int index = 0; index < 4; index++) {
            assertEquals(0, chunk.storage().view(index).blockPalette().bitsPerEntry(),
                    "a section holding one state has to end in the single value mode after "
                            + "Palette#optimize, not at fifteen bits per entry");
        }
        MinecraftServer.getInstanceManager().unregisterInstance(instance);
    }
```

Construct the `FalcoInstance` the way the existing `FalcoInstanceGeneratorTest` in the same package does rather than the way above if that test uses a different constructor; read it first and follow it, because the instance API is not the subject here.

- [ ] **Step 2: Run it to verify it fails**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-instance:test --tests "*SectionMaterialisationTest*"
```

Expected: FAIL with `24` against the expected `4`, from the `getSections()` at `FalcoInstance:923`.

- [ ] **Step 3: Rewrite the commit**

Replace the body of `applyGenerator`:

```java
    private void applyGenerator(Chunk chunk, Generator generator) {
        final BlockStorage storage = storageOf(chunk);
        final int sectionCount = storage.sectionCount();
        final GeneratorImpl.GenSection[] staged = new GeneratorImpl.GenSection[sectionCount];
        Arrays.setAll(staged, index -> {
            final Section view = storage.view(index);
            return new GeneratorImpl.GenSection(view.blockPalette().clone(), view.biomePalette().clone());
        });
        final GeneratorImpl.UnitImpl unit = GeneratorImpl.chunk(this.registries.biome(), staged,
                chunk.getChunkX(), chunk.getMinSection(), chunk.getChunkZ());

        generator.generate(unit);

        chunk.lockWriteLock();
        try {
            for (int index = 0; index < sectionCount; index++) {
                commitSection(chunk, storage, index, staged[index]);
            }
            chunk.invalidate();
        } finally {
            chunk.unlockWriteLock();
        }

        applyForks(chunk, unit);
        applyPendingForks(chunk);
        refreshLastBlockChangeTime();
    }

    /**
     * Writes one generated section back into the chunk, or leaves the chunk alone if it produced
     * nothing.
     * <p>
     * The skip is what makes a lazy layout survive its own generator. A generator normally fills the
     * lower third of a chunk and leaves everything above the terrain untouched — the census of a real
     * overworld puts that untouched share at {@code 62,24 %} of the sections of a finished chunk — and
     * committing an empty palette into an empty section would create twenty-four sections to write
     * nothing into twenty of them. The condition is the one {@code InstanceContainer} already applies
     * to fork sections at {@code InstanceContainer.java:434}, extended by the biomes and by the special
     * blocks, since either of those can be the only thing a generator produced for a section.
     * </p>
     * <p>
     * A section that is still shared and received nothing needs no write at all, and that is exactly
     * what the condition tests. A section the chunk already owns is committed unconditionally: it
     * holds content from a loader or an earlier write, and an empty generated palette is a statement
     * about what the generator produced and not about what the chunk should end up holding.
     * </p>
     * <p>
     * The optimisation afterwards is US-2.03. A generator writes through {@code GenSection} palettes
     * which grow to fifteen bits per entry and never shrink again, because nothing in the main source
     * tree of Minestom ever calls {@code Palette#optimize} — a generated chunk retains
     * {@code 203 840} bytes where the same content packed to its minimum width retains {@code 84 800}.
     * What that costs in time is measured by {@code GeneratorCommitBenchmark} and stated in the plan
     * of this stage; it is not assumed to be free.
     * </p>
     *
     * @param chunk     the chunk which receives the section
     * @param storage   the storage of the chunk
     * @param index     the index of the section, counted from the bottom one
     * @param generated the section the generator produced
     */
    private void commitSection(Chunk chunk, BlockStorage storage, int index,
                               GeneratorImpl.GenSection generated) {
        final boolean producedNothing = generated.blocks().count() == 0
                && generated.biomes().count() == 0
                && generated.specials().isEmpty();

        if (producedNothing && storage.shared(index)) {
            return;
        }
        final Section section = storage.section(index);

        section.blockPalette().copyFrom(generated.blocks());
        section.biomePalette().copyFrom(generated.biomes());
        section.blockPalette().optimize(Palette.Optimization.SIZE);
        section.biomePalette().optimize(Palette.Optimization.SIZE);
        writeSpecialBlocks(chunk, generated.specials(),
                (chunk.getMinSection() + index) * Chunk.CHUNK_SECTION_SIZE);
    }

    /**
     * Hands out the storage of a chunk, whatever kind of chunk it is.
     * <p>
     * A chunk supplier is a setting of the instance and a caller is free to install one which does not
     * produce a {@link FalcoChunk}. Rather than carrying two generation paths, a foreign chunk is
     * wrapped in a {@link SectionBlockStorage} over its own live sections: that storage shares nothing
     * and materialises nothing, so every decision below it collapses into the behaviour Minestom has,
     * and the writes go straight into the sections of the chunk because the list holds the same
     * {@code Section} references.
     * </p>
     *
     * @param chunk the chunk to reach the sections of
     * @return the storage of the chunk
     */
    private static BlockStorage storageOf(Chunk chunk) {
        if (chunk instanceof FalcoChunk falcoChunk) {
            return falcoChunk.storage();
        }
        return new SectionBlockStorage(chunk.getMinSection(), chunk.getSections());
    }
```

And guard `applyFork` against a fork section that carries nothing, which is the same guard `InstanceContainer` has at `:434`:

```java
    private void applyFork(Chunk chunk, GeneratorImpl.SectionModifierImpl modifier) {
        if (modifier.genSection().blocks().count() == 0 && modifier.genSection().specials().isEmpty()) {
            // A fork which produced nothing for this section must not be the reason the section
            // exists. Minestom applies the same test at InstanceContainer.java:434 for the same reason.
            return;
        }
        final int sectionStartY = modifier.start().blockY();
        chunk.lockWriteLock();
        try {
            final Palette blocks = chunk.getSectionAt(sectionStartY).blockPalette();
            // A forked section marks an untouched position with a zero, so every block it does carry
            // was stored with its state raised by one and has to be lowered again here.
            modifier.genSection().blocks().getAllPresent((x, y, z, value) -> blocks.set(x, y, z, value - 1));
            writeSpecialBlocks(chunk, modifier.genSection().specials(), sectionStartY);
            chunk.invalidate();
        } finally {
            chunk.unlockWriteLock();
        }
    }
```

New imports for `FalcoInstance`: `net.minestom.server.instance.palette.Palette` if it is not already there. Raise the `@version` of the class and add a paragraph to the Javadoc of `applyGenerator` saying that the palettes the generator wrote into are copies sized from the *views* of the chunk, so that staging a generation no longer creates the sections the generator may decide not to fill.

- [ ] **Step 4: Run the tests**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-instance:test
./gradlew :falco-anvil:test :falco-light:test
```

Expected: PASS, including the new generator case at `4` materialised sections and `bitsPerEntry() == 0`. `FalcoInstanceGeneratorTest` is the regression net for the commit and must stay green without being touched; if it fails, the commit changed what a generator produces and that is a defect, not a test to update.

- [ ] **Step 5: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/SectionMaterialisationTest.java
git commit -m "feat(instance): commit only the sections a generator filled, and pack their palettes"
```

---

### Task 7: Heightmaps on demand

**Files:**
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoChunk.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoChunkTest.java` (extended)

**Interfaces:**
- Consumes: nothing new.
- Produces: `FalcoChunk#motionBlockingHeightmap()` and `#worldSurfaceHeightmap()` create their heightmap on first call. `FalcoChunk#hasHeightmaps()` is added for the tests and for Task 9, because a property that cannot be observed cannot be asserted.

**Covers:** US-2.04.

**What this is worth and under which condition.** Both heightmaps together are `1 120` bytes and `16,4 %` of a fresh chunk — the second largest post after the sections, and one the research that preceded this design never listed. The condition has to be stated with the saving: `FalcoChunk#setBlock` refreshes both heightmaps on every write and `createChunkPacket` asks for both on every send, so a chunk that is written to or sent builds them immediately. What is saved is the chunk that is loaded and read and never sent — and, on the generator path, the whole window between construction and the first send.

- [ ] **Step 1: Write the failing test**

Append to `FalcoChunkTest`:

```java
    @Test
    @DisplayName("builds no heightmap until something asks for one")
    void testHeightmapsAreBuiltOnDemand() {
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);

        assertFalse(chunk.hasHeightmaps(), "a chunk that was only constructed needs no heightmap");

        chunk.lockReadLock();
        try {
            chunk.getBlock(0, 0, 0);
        } finally {
            chunk.unlockReadLock();
        }
        assertFalse(chunk.hasHeightmaps(), "a block read does not need a heightmap either");

        assertNotNull(chunk.motionBlockingHeightmap());
        assertTrue(chunk.hasHeightmaps());
    }

    @Test
    @DisplayName("hands out the same heightmap on every call")
    void testTheHeightmapIsBuiltOnce() {
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);

        assertSame(chunk.motionBlockingHeightmap(), chunk.motionBlockingHeightmap());
        assertSame(chunk.worldSurfaceHeightmap(), chunk.worldSurfaceHeightmap());
        assertNotSame(chunk.motionBlockingHeightmap(), chunk.worldSurfaceHeightmap());
    }
```

Use the fixture the existing `FalcoChunkTest` already sets up; the `instance` above is whatever that class names it.

- [ ] **Step 2: Run it to verify it fails**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-instance:test --tests "*FalcoChunkTest*"
```

Expected: compilation failure — `hasHeightmaps` does not exist.

- [ ] **Step 3: Implement**

Replace the two heightmap fields and their accessors in `FalcoChunk`:

```java
    /**
     * The highest block per column which stops movement, built when something first asks for it.
     * <p>
     * Volatile because the creation below is a double-checked lock, and a non-volatile field would
     * let a second thread see a partly constructed {@code MotionBlockingHeightmap} — which carries a
     * {@code short[256]} of its own that would then be read before it exists.
     * </p>
     */
    private volatile Heightmap motionBlocking;

    /**
     * The highest block per column which is not air, built when something first asks for it.
     */
    private volatile Heightmap worldSurface;
```

```java
    /**
     * Hands out the heightmap of the highest movement-blocking block per column, building it if this
     * chunk does not have one yet.
     * <p>
     * A heightmap is a {@code short[256]} plus its carrier, and both heightmaps together are one sixth
     * of everything a fresh chunk retains. Minestom builds them in a field initialiser, so a chunk
     * pays for them whether or not anybody ever reads a height. Most chunks do get asked eventually —
     * a chunk that is sent to a client hands both of them to the packet, and a chunk that is written
     * to refreshes both — but the window between construction and that first question is exactly the
     * window a chunk loader and a generator work in, and a chunk that is loaded, read and never sent
     * never leaves it.
     * </p>
     * <p>
     * The creation is a double-checked lock over the monitor of this chunk rather than a plain lazy
     * field. The read lock and the write lock of a chunk do not cover this method — a caller may reach
     * it without either — and two threads which both created a heightmap would leave one of them
     * holding heights that the chunk then throws away.
     * </p>
     *
     * @return the motion blocking heightmap
     */
    @Override
    public Heightmap motionBlockingHeightmap() {
        Heightmap heightmap = this.motionBlocking;

        if (heightmap != null) return heightmap;
        synchronized (this) {
            heightmap = this.motionBlocking;
            if (heightmap == null) {
                heightmap = new MotionBlockingHeightmap(this);
                this.motionBlocking = heightmap;
            }
            return heightmap;
        }
    }

    /**
     * Hands out the heightmap of the highest non-air block per column, building it if this chunk does
     * not have one yet.
     *
     * @return the world surface heightmap
     */
    @Override
    public Heightmap worldSurfaceHeightmap() {
        Heightmap heightmap = this.worldSurface;

        if (heightmap != null) return heightmap;
        synchronized (this) {
            heightmap = this.worldSurface;
            if (heightmap == null) {
                heightmap = new WorldSurfaceHeightmap(this);
                this.worldSurface = heightmap;
            }
            return heightmap;
        }
    }

    /**
     * Reports whether this chunk has built its heightmaps yet.
     * <p>
     * Exposed because a property nothing can observe is a property nothing can assert, and the whole
     * value of building them on demand is the claim that a chunk which was only loaded holds none.
     * </p>
     *
     * @return whether either heightmap exists
     * @since 0.4.0
     */
    public boolean hasHeightmaps() {
        return this.motionBlocking != null || this.worldSurface != null;
    }
```

Then replace every remaining direct use of the two fields inside the class with the accessors — `setBlock`, `calculateFullHeightmap` and `getHeightmaps` all read `this.motionBlocking` and `this.worldSurface` today and must go through `motionBlockingHeightmap()` and `worldSurfaceHeightmap()` instead, or they will dereference null.

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
grep -n "this.motionBlocking\|this.worldSurface" falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoChunk.java
```

Every hit outside the two accessors and the two field declarations is a bug.

- [ ] **Step 4: Run the tests**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-instance:test
./gradlew :falco-benchmarks:test --tests "*FalcoChunkEquivalenceTest*"
```

Expected: PASS. The equivalence test compares both heightmaps of every column against `DynamicChunk` and is what says that building them later did not build them differently.

- [ ] **Step 5: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoChunk.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoChunkTest.java
git commit -m "perf(instance): build a heightmap when something asks for one"
```

---

### Task 8: One block map instead of two

**Files:**
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoChunk.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoChunkTest.java` (extended)

**Interfaces:**
- Consumes: nothing new.
- Produces: `FalcoChunk#tickableMap` is gone. Nothing outside the class reads it; it was `protected`, so the removal is a source-compatible change only for subclasses in this repository, of which there are none.

**Covers:** US-2.06.

**The trade, stated before it is made.** `tickableMap` holds a subset of `entries` under identical keys with identical references. Removing it saves one `Int2ObjectOpenHashMap` and its two backing arrays per chunk, and costs a walk over `entries` in `tick` instead of over a smaller map. The walk is guarded by a counter, so a chunk with block entities and no tickable ones still ticks in a single comparison — which is the property the current Javadoc of `tickableMap` argues for and which must not be lost while the map is.

- [ ] **Step 1: Write the failing test**

Append to `FalcoChunkTest`:

```java
    @Test
    @DisplayName("ticks a tickable handler and stops ticking it when it is replaced")
    void testTickReachesOnlyTickableBlocks() {
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final java.util.concurrent.atomic.AtomicInteger ticks = new java.util.concurrent.atomic.AtomicInteger();
        final BlockHandler tickable = new BlockHandler() {

            @Override
            public net.kyori.adventure.key.Key getKey() {
                return net.kyori.adventure.key.Key.key("falco", "tickable");
            }

            @Override
            public boolean isTickable() {
                return true;
            }

            @Override
            public void tick(Tick tick) {
                ticks.incrementAndGet();
            }
        };

        chunk.lockWriteLock();
        try {
            chunk.setBlock(0, 0, 0, Block.STONE.withHandler(tickable));
        } finally {
            chunk.unlockWriteLock();
        }
        chunk.tick(0L);
        assertEquals(1, ticks.get());

        chunk.lockWriteLock();
        try {
            chunk.setBlock(0, 0, 0, Block.STONE);
        } finally {
            chunk.unlockWriteLock();
        }
        chunk.tick(0L);
        assertEquals(1, ticks.get(), "a block that was replaced must stop being ticked");
    }

    @Test
    @DisplayName("carries the tickable blocks of a chunk into its copy")
    void testCopyKeepsTicking() {
        final FalcoChunk chunk = new FalcoChunk(instance, 0, 0);
        final java.util.concurrent.atomic.AtomicInteger ticks = new java.util.concurrent.atomic.AtomicInteger();

        chunk.lockWriteLock();
        try {
            chunk.setBlock(0, 0, 0, Block.STONE.withHandler(tickingHandler(ticks)));
        } finally {
            chunk.unlockWriteLock();
        }
        chunk.lockReadLock();
        final Chunk copy;
        try {
            copy = chunk.copy(instance, 1, 1);
        } finally {
            chunk.unlockReadLock();
        }
        copy.tick(0L);
        assertEquals(1, ticks.get(),
                "DynamicChunk#copy carries only the entries, which stops a copied chunk from ticking; "
                        + "that omission was corrected before the storage moved and stays corrected");
    }
```

Extract the handler into a `tickingHandler(AtomicInteger)` helper of the test class and use it in both cases; the inline form above is written out once so the shape of the handler is on the page.

- [ ] **Step 2: Run it to verify it passes before the change and after it**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-instance:test --tests "*FalcoChunkTest*"
```

Expected: PASS **before** the change. This is a characterisation test rather than a red one: the behaviour it pins already exists and the point of writing it first is that the change must not alter it. Commit it separately, so that the diff of the change shows a green test staying green rather than a test appearing next to it.

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
git add falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoChunkTest.java
git commit -m "test(instance): pin what ticking a chunk does before the second map goes"
```

- [ ] **Step 3: Remove the map**

Delete the `tickableMap` field and add the counter:

```java
    /**
     * How many of {@link #entries} carry a handler which asked to be ticked.
     * <p>
     * This is what is left of the second map {@code DynamicChunk} keeps. That map held a subset of
     * {@link #entries} under the same keys pointing at the same blocks, so it was a second copy of
     * information the chunk already had, at the price of one {@code Int2ObjectOpenHashMap} and its two
     * backing arrays per chunk — for every chunk in a world, whether or not it holds a single block
     * entity.
     * </p>
     * <p>
     * What that map bought was the early exit of {@link #tick(long)}: almost every chunk has nothing
     * to tick, and a tick which had to walk the entries to find that out would make the cost of
     * ticking depend on how many block entities a chunk happens to hold. The counter buys the same
     * exit for four bytes. What is genuinely paid is the case that remains — a chunk which holds both
     * tickable and non-tickable block entities now walks all of them once per tick instead of only the
     * tickable ones.
     * </p>
     */
    private int tickableCount;
```

In `setBlock`, replace the two map updates with one map update and a counter correction:

```java
        final int index = CoordConversion.chunkBlockIndex(x, y, z);
        // Handler
        final BlockHandler handler = block.handler();
        final Block lastCachedBlock;
        if (handler != null || block.hasNbt() || block.registry().isBlockEntity()) {
            lastCachedBlock = this.entries.put(index, block);
        } else {
            lastCachedBlock = this.entries.remove(index);
        }
        // Block tick. A tickable block always carries a handler and is therefore always in the
        // entries above, so the counter and the map can never disagree about who is in which.
        final BlockHandler previousHandler = lastCachedBlock == null ? null : lastCachedBlock.handler();
        final boolean wasTickable = previousHandler != null && previousHandler.isTickable();
        final boolean isTickable = handler != null && handler.isTickable();
        if (wasTickable != isTickable) {
            this.tickableCount += isTickable ? 1 : -1;
        }
```

In `tick`:

```java
    @Override
    public void tick(long time) {
        if (this.tickableCount == 0) return;
        this.entries.int2ObjectEntrySet().fastForEach(entry -> {
            final Block block = entry.getValue();
            final BlockHandler handler = block.handler();
            if (handler == null || !handler.isTickable()) return;
            final Point blockPosition = CoordConversion.chunkBlockIndexGetGlobal(entry.getIntKey(), chunkX, chunkZ);
            handler.tick(new BlockHandler.Tick(block, instance, blockPosition));
        });
    }
```

In `copy`, replace `copy.tickableMap.putAll(this.tickableMap);` with `copy.tickableCount = this.tickableCount;` and keep the paragraph of the Javadoc that explains why a copy has to keep ticking at all.

In `reset`, add `this.tickableCount = 0;` next to `this.entries.clear();`.

- [ ] **Step 4: Run the tests**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-instance:test
```

Expected: PASS, with the two characterisation cases unchanged. If `testTickReachesOnlyTickableBlocks` fails on the second assertion, the counter was not decremented when the handler was replaced by one without a handler.

- [ ] **Step 5: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoChunk.java
git commit -m "perf(instance): keep one block map and a counter instead of two maps"
```

---

### Task 9: Reset the footprint expectation, deliberately

**Files:**
- Modify: `falco-benchmarks/src/test/java/net/onelitefeather/falco/benchmark/instance/ChunkFootprintTest.java`

**Interfaces:**
- Consumes: `FalcoChunk` and `LazySectionBlockStorage` as Tasks 2, 3, 7 and 8 left them.
- Produces: nothing. This task changes an assertion and nothing else.

**Covers:** NFR-003; the measurement half of US-2.05; and the refusal of US-2.08, with its evidence.

**Why the old assertion has to go, and what must not go with it.** Stage 1 asserted that `FalcoChunk` and `DynamicChunk` retain identical objects and identical bytes in every class except `SectionBlockStorage`, of which the Falco side holds exactly one — with three injected defects used to prove the assertion still bit. Every task of this stage breaks that by construction, because removing objects is the point. What must survive the rewrite is the property the equality had: **a class the Falco chunk retains and the plan did not declare has to fail the test.** A tolerance of the form "at most N bytes" would not have that property and is rejected. The replacement is a declared difference table: every class in it is asserted at an exact count, and every class not in it is still asserted equal on both objects and bytes.

- [ ] **Step 1: Read what the chunk now holds, before writing what it should hold**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-benchmarks:test --tests "*ChunkFootprintTest*" -i 2>&1 | tee /tmp/claude-1000/-mnt-projects-oss-onelitefeather-Falco/34edb948-9dfe-4540-9666-9e29f0d44d7b/scratchpad/footprint-stage2.txt
```

The test fails at this point; the class table it printed before failing is what this step is for. Read the two per-class tables — the fresh chunk and the filled rows — and write down, for every class where the two sides differ, the count on each side.

- [ ] **Step 2: Derive the table from the source, then compare it with what was printed**

The expected difference for a **fresh** chunk, derived from the tasks above and not from the measurement:

| class | `DynamicChunk` | `FalcoChunk` | why |
|---|---|---|---|
| `net.minestom.server.instance.Section` | 24 | 0 | every slot shares `LazySectionBlockStorage.EMPTY`, which is static and therefore not retained by the chunk |
| `...instance.palette.PaletteImpl` | 48 | 0 | two per section, and there are no sections |
| `...instance.light.SkyLight` | 24 | 0 | one per section |
| `...instance.light.BlockLight` | 24 | 0 | one per section |
| `java.util.concurrent.atomic.AtomicBoolean` | 48 | 0 | one per light carrier — US-2.05 for every empty section, achieved by the flyweight rather than by packing |
| `...heightmap.MotionBlockingHeightmap` | 1 | 0 | Task 7 |
| `...heightmap.WorldSurfaceHeightmap` | 1 | 0 | Task 7 |
| `[S` | 2 | 0 | the `short[256]` of each heightmap |
| `it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap` | 2 | 1 | Task 8 |
| `[Ljava.lang.Object;` | *n* | *n* − 2 | the two backing arrays of the removed map; read the count off the table |
| `net.onelitefeather.falco.instance.LazySectionBlockStorage` | 0 | 1 | the storage |
| `[Lnet.minestom.server.instance.Section;` | 0 | 1 | the slot array of the storage |
| `net.onelitefeather.falco.instance.LazySectionBlockStorage$1` | 0 | 1 | the `AbstractList` that `views()` answers with |
| `java.util.ImmutableCollections$ListN` | 1 | 0 | Minestom's `List.of(Section[])`; Falco holds the array directly |

For the **filled** rows the difference is smaller and must be declared separately: `MinestomChunks#fill` writes through `Chunk#setBlock`, so every section is materialised and both heightmaps are built. What remains is the second block map, the storage, the slot array, the view list and the list wrapper.

**Where the printed table disagrees with this one, the disagreement is the result, not the table.** Do not adjust a row until it is green. Find out which object the chunk holds that this plan did not predict, name it, and write it into the stage result as a finding.

- [ ] **Step 3: Rewrite the assertion**

Replace `assertTheSeamIsTheOnlyDifference` with a version that takes the declared table:

```java
    /**
     * Fails unless the two chunks differ in exactly the classes this stage declared they differ in.
     * <p>
     * The comparison of stage 1 demanded equality everywhere except one class, and it could, because
     * the seam added one object and removed none. Stage 2 removes a hundred and seventy of them, so
     * equality is no longer the right shape — but the property it existed for is unchanged and is
     * preserved here: a class the Falco chunk retains and this table does not name still fails, on
     * both its object count and its bytes. What is asserted per declared class is the count, exactly,
     * on both sides; what is asserted over the whole footprint is that the byte difference is the sum
     * of the bytes of the declared classes and of nothing else. That is a stronger statement than the
     * old equality and not a weaker one, because the old equality never had to add anything up.
     * </p>
     * <p>
     * A tolerance was considered and rejected. "The Falco chunk retains at most six kibibytes" would
     * pass for a chunk that saved the sections and grew a field, which is the exact failure the strict
     * comparison of stage 1 was written to catch and the reason three defects were injected into it to
     * prove that it did.
     * </p>
     *
     * @param minestom      the footprint of the Minestom side
     * @param minestomChunk the chunk the Minestom side was measured from
     * @param falcoSide     the footprint of the Falco side
     * @param falcoChunk    the chunk the Falco side was measured from
     * @param declared      the expected count per class on the Falco side, for every class the two
     *                      sides may differ in
     * @param context       what was measured, named in every failure message
     */
    private static void assertOnlyTheDeclaredClassesDiffer(Footprint minestom, Chunk minestomChunk,
                                                           Footprint falcoSide, Chunk falcoChunk,
                                                           Map<String, Long> declared,
                                                           String context) {
        final String minestomType = minestomChunk.getClass().getName();
        final String falcoType = falcoChunk.getClass().getName();

        final Set<String> classNames = new TreeSet<>(minestom.perClass().keySet());
        classNames.addAll(falcoSide.perClass().keySet());

        long declaredBytes = 0;

        for (String className : classNames) {
            if (className.startsWith(minestomType) || className.startsWith(falcoType)) {
                continue;
            }
            final Long expected = declared.get(className);

            if (expected == null) {
                assertEquals(minestom.objectsOf(className), falcoSide.objectsOf(className),
                        context + ": FalcoChunk retains " + falcoSide.objectsOf(className) + " objects of "
                                + className + " against " + minestom.objectsOf(className) + " of DynamicChunk, "
                                + "and this class is not one the plan of stage 2 declared a difference for");
                assertEquals(minestom.bytesOf(className), falcoSide.bytesOf(className),
                        context + ": FalcoChunk retains " + falcoSide.bytesOf(className) + " bytes of "
                                + className + " against " + minestom.bytesOf(className) + " of DynamicChunk, "
                                + "and this class is not one the plan of stage 2 declared a difference for");
                continue;
            }
            assertEquals(expected.longValue(), falcoSide.objectsOf(className),
                    context + ": the plan declares " + expected + " objects of " + className
                            + " on the Falco side and the chunk holds " + falcoSide.objectsOf(className));
            declaredBytes += falcoSide.bytesOf(className) - minestom.bytesOf(className);
        }
        assertEquals(declaredBytes, falcoSide.bytes() - minestom.bytes(),
                context + ": the two chunks differ by " + (falcoSide.bytes() - minestom.bytes())
                        + " bytes while the classes the plan declared account for " + declaredBytes
                        + ". The remainder belongs to a class this comparison did not look at, which "
                        + "means a post moved without anybody deciding that it should.");
        assertEquals(ClassLayout.parseInstance(minestomChunk).instanceSize(),
                ClassLayout.parseInstance(falcoChunk).instanceSize(),
                context + ": the two chunk objects themselves must still have the same shallow size");
    }
```

Declare the two tables as constants of the class, each entry carrying the count derived in Step 2, and pass the right one from each of the two test methods. Then add the three assertions that carry the *result* of the stage rather than its bookkeeping:

```java
        assertEquals(0, falcoFootprint.objectsOf(SECTION),
                "a fresh Falco chunk shares every section and must own none");
        assertEquals(0, falcoFootprint.objectsOf(NEEDS_SEND),
                "the 48 AtomicBoolean send flags of a fresh chunk are gone with the sections that "
                        + "held them; the ones that come back with a materialised section are two per "
                        + "section and are what US-2.05 does not remove");
        assertTrue(falcoFootprint.bytes() * 4 < minestom.bytes(),
                "a fresh Falco chunk retained " + falcoFootprint.bytes() + " bytes against "
                        + minestom.bytes() + " for a DynamicChunk; the sections are 74,9 % of that "
                        + "figure and both heightmaps another 16,4 %, so anything above a quarter "
                        + "means one of the two did not actually go");
```

Finally, add the measurement that refuses US-2.08 with evidence rather than with an opinion:

```java
    /**
     * States what the chunk identifier costs and why this stage does not remove it.
     * <p>
     * US-2.08 asks for the {@code UUID} of a chunk to go, on the grounds that {@code grep
     * getIdentifier} finds only its declaration in all of Minestom. It cannot go from here.
     * {@code Chunk.java:47} declares {@code private final UUID identifier} and {@code Chunk.java:65}
     * assigns it {@code UUID.randomUUID()} in the constructor every subclass has to call. A subclass
     * cannot remove a field of its superclass, and not extending {@code Chunk} is not available
     * either, because {@code Instance} is typed on it throughout. What this test does instead is
     * state the price, so that the story is closed by a number rather than by a shrug.
     * </p>
     */
    @Test
    @DisplayName("The chunk identifier cannot be removed from a subclass, and this is what it costs")
    void theChunkIdentifierIsOutOfReach() {
        JolMeasurement.require();

        final Chunk falcoChunk = MinestomChunks.newChunk(falco, 0, 0);
        final Footprint falcoFootprint = measure(falcoChunk, falco);

        assertEquals(1, falcoFootprint.objectsOf("java.util.UUID"),
                "every chunk of Minestom allocates one UUID in the constructor of Chunk");
        report(new StringBuilder()
                .append(" The chunk identifier costs ")
                .append(falcoFootprint.bytesOf("java.util.UUID"))
                .append(" bytes per chunk and is unreachable from a subclass (Chunk.java:47, :65).")
                .append(System.lineSeparator()));
    }
```

Raise the class Javadoc of `ChunkFootprintTest` to `@version 2.0.0` and rewrite its `<h2>What the seam costs, and why the delta is not zero</h2>` section into one that describes the declared difference table and names the stage that set it.

- [ ] **Step 4: Prove the new assertion still bites**

Inject each of these into `FalcoChunk`, run the test, confirm it fails with a message that names the cause, then revert:

| injected | has to be caught by |
|---|---|
| `private final Object probe = new Object();` | the undeclared-class branch, `1` object of `java.lang.Object` against `0` |
| `private final BlockStorage probe = new LazySectionBlockStorage(0, 0);` | the declared count, `2` storages against the declared `1` |
| `private final long probe = System.nanoTime();` | the shallow size comparison of the chunk object |
| a `new Section()` in the constructor, stored in a field | the declared count of `Section`, `1` against `0` |

The fourth is new and is the one this stage needs: it is the shape of the defect where somebody quietly materialises a section to make something else work.

- [ ] **Step 5: Run it**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-benchmarks:test --tests "*ChunkFootprintTest*" -i
```

Expected: PASS, with the tables printed and the fresh-chunk row showing a `DELTA B` that is now large and negative.

- [ ] **Step 6: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
git add falco-benchmarks/src/test/java/net/onelitefeather/falco/benchmark/instance/ChunkFootprintTest.java
git commit -m "test(benchmarks): declare what the lazy chunk no longer holds, class by class"
```

---

### Task 10: Run the measurements and record what stage 2 bought

**Files:**
- Modify: `docs/superpowers/plans/2026-08-02-falco-lazy-sections.md`

**Interfaces:** none. This task produces the numbers and the sentence that is allowed to be quoted from them.

- [ ] **Step 1: The whole test suite**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-instance:test :falco-light:test :falco-anvil:test :falco-benchmarks:test --rerun-tasks
```

Expected: no failure, no error. Record the test counts per module against the stage 1 result — 66, 189, 193 and 36 — so that a test that quietly stopped running is visible.

- [ ] **Step 2: The footprint, which is the citable number of this stage**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-benchmarks:test --tests "*ChunkFootprintTest*" -i
./gradlew :falco-benchmarks:test --tests "*ChunkFootprintTest*" -Pfalco.compactHeaders -i
```

Record both, and never quote a number from one next to a number from the other. The row that matters is the fresh chunk: `192` objects and `6 848` bytes for `DynamicChunk`, `193` and `6 872` after stage 1, and whatever this stage leaves.

- [ ] **Step 3: The flyweight benchmark, which already exists**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-benchmarks:jmhJar
java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
    "LazySectionBenchmark.(scatteredRead|buildSections).*" \
    -p emptyPercent=0,62,90 -f 3 -wi 5 -i 5 -prof gc
java -jar falco-benchmarks/build/libs/falco-benchmarks-*-jmh.jar \
    "LazySectionBenchmark.(readEmpty|readFull|steadyWrite|firstWrite).*" \
    -p emptyPercent=90 -f 3 -wi 5 -i 5 -prof gc
```

The second command answers US-2.07 directly: `readEmptyLazy` against `readEmptyEager` has to be at least as fast, and `readFullLazy` against `readFullEager` has to be indistinguishable. A `readFullLazy` outside the error bars of `readFullEager` means the branch this stage added is being paid on every block read of every non-empty section in the server, and that is a finding which outweighs the memory.

- [ ] **Step 4: The comparison benchmark, the regression net**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
./gradlew :falco-benchmarks:jmh -Pjmh.quick \
  -Pjmh.include="ChunkComparisonBenchmark" \
  -Pjmh.params="distinctStates=1,64,1024;fillShape=RANDOM_RUNS"
```

`falcoGetBlock`, `falcoSetBlock`, `falcoHeightmapRefresh` and `falcoCopyIsolated` against their `minestom*` counterparts. `minestomCopy`/`falcoCopy` are not comparable and are omitted, as the stage 1 result explains. Note that the stage 1 run left `falcoHeightmapRefresh` at 1 024 states unresolved — an error bar twenty-eight times its own mean on a loaded machine — and that this stage changes exactly that path, so it has to be rerun on an idle machine before anything is concluded from it in either direction.

- [ ] **Step 5: Write the result section**

Append `## Stage 2 result` to this file, in the shape the stage 1 result uses: the footprint table first because it is citable, then the tests, then the benchmark numbers with the conditions that disqualify them if they are scouting figures, then a closing paragraph on what the stage bought and what it cost. State at minimum:

- the fresh chunk in objects and bytes, against S1 and S2, under both header modes
- what the declared difference table ended up containing, and every row of Step 2 of Task 9 that had to be corrected against the source
- the materialisation counts from `SectionMaterialisationTest`, including the number for the heightmap gap, with the reason
- the measured price of `Palette#optimize` from Task 5, as a ratio against the commit
- the saving at the counted share of `62,24 %`, which is what may be quoted, and the fact that S7 rests on 441 finished chunks around one spawn and licenses no claim beyond a generated overworld near its spawn

- [ ] **Step 6: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/block-storage
git add docs/superpowers/plans/2026-08-02-falco-lazy-sections.md
git commit -m "docs(plan): record what stage 2 measured"
```

---

## Definition of done

- [ ] `BlockStorage` distinguishes a caller that may write from one that only reads, and the difference is documented as a contract rather than as a convention
- [ ] `LazySectionBlockStorage` exists, shares one `EMPTY` section between every empty slot of every chunk, and materialises with `new Section()` rather than `EMPTY.clone()` — with a test that proves the materialised section has no light and nothing to send
- [ ] A fresh `FalcoChunk` owns zero sections, zero palettes, zero light carriers, zero `AtomicBoolean` and zero heightmaps, asserted per class rather than in total
- [ ] One block write owns exactly one section, and the heightmap refresh that follows it owns none
- [ ] Sending a chunk owns nothing beyond what the chunk already held
- [ ] A generator owns only the sections it filled, and the palettes it filled end at their minimum width rather than at fifteen bits per entry
- [ ] The price of `Palette#optimize` is measured against the commit it follows, and stated, before it is booked
- [ ] `FalcoChunk` holds one block index map and a counter, and a copied chunk still ticks
- [ ] `ChunkFootprintTest` asserts a declared per-class difference table, four injected defects were used to prove it still bites, and no assertion in it is a byte tolerance
- [ ] `FalcoChunkEquivalenceTest` in `falco-benchmarks` passes over all eighteen fixtures — every position and both heightmaps of every column
- [ ] `falco-instance`, `falco-light`, `falco-anvil` and `falco-benchmarks` tests all pass, with the test counts recorded against the stage 1 result
- [ ] Every new public type carries `@ApiStatus.Experimental`, `@author`, `@version` and `@since 0.4.0`; every modified type has its `@version` raised

## What stage 2 deliberately does not do

Named here so that a reviewer does not read them as omissions.

**It does not remove the last `AtomicBoolean` (US-2.05, partially).** The flyweight removes the send flags of every empty section, which is all forty-eight of them for a fresh chunk and the great majority of them for a generated one. The two per materialised section stay. Removing them would mean handing Minestom a `Light` implementation of Falco's own, and that cannot be done: `LightCompute#compute` and `LightCompute#getLight` are package-private, so a foreign carrier can reproduce the storage shape of `SkyLight` and `BlockLight` but not their algorithm — `SectionAllocationBenchmark` states this in as many words and its own replicas refuse both calculate methods for exactly that reason. A carrier that wrapped a real one to fold only the flag would add an object per section and remove one, which is not a saving. Task 9 pins the count so that it cannot grow back unnoticed.

**It does not remove the chunk `UUID` (US-2.08).** `Chunk.java:47` declares `private final UUID identifier` and `Chunk.java:65` assigns it in the constructor every subclass calls. A subclass cannot delete a field of its superclass, and `Instance` is typed on `Chunk` throughout, so not extending it is not on the table either. Task 9 measures what it costs instead of leaving the story open.

**It does not stop the heightmap from materialising sections below the terrain.** `Heightmap#refresh(int, int, int)` reaches its sections through `Chunk#getSection(int)` and ends in a `private` setter over a `private` array, so it can be neither overridden nor bypassed. The scan that *starts* a refresh is replaced (Task 3) because it is a `public static` method taking the chunk, and that one is the expensive half — it walks the empty top of the chunk. The column descent below the highest non-empty section is not replaced, and `SectionMaterialisationTest` states what it costs in a world with a gap.

**It does not touch `FalcoInstance`'s structure.** No facade split, no `ChunkRegistry`, no `ChunkLifecycle`, no lifecycle listeners, no viewer cache cleanup, no primitive chunk index. Those are stage 3. The only change to `FalcoInstance` here is the generator commit, and it is there because a generator is what fills a chunk and therefore what decides whether a lazy layout survives contact with a world.

**It does not build a shared instance.** That is stage 4, and it rests on US-1.05, which stage 1 delivered.

**It does not replace `Palette`.** `public sealed interface Palette permits PaletteImpl` is closed by the verifier, and the break-even measurement puts Minestom's choice of representation between 192 and 224 entries, which is sound. What this stage does with palettes is call the method Minestom already has and never calls.

## What optimize() costs

Measured by `GeneratorCommitBenchmark` (Task 5), committed as `f790f0d`. AMD Ryzen 7 5800X, 16 hardware threads, JDK 25.0.3 Temurin, `-Xms2g -Xmx2g`, 3 forks × 5 × 1 s warmup × 5 × 1 s measurement, 15 samples per point, `-prof gc`. The machine was **not** idle — an IntelliJ session and a file indexer were running, load average 5.4 rising to 7.0 across the run. The error bars below are JMH's and are tight; the absolute microseconds still carry that load and should be read as a ratio rather than as a wall clock figure for a quiet server.

One chunk, 24 overworld sections, block palettes only.

| distinct states | `commitPlain` | `commitOptimized` | ratio | `optimizeAlreadyPacked` | width staged → packed |
|---|---|---|---|---|---|
| 1 | 0.044 ± 0.001 µs | 0.049 ± 0.002 µs | **1.1×** | 0.047 ± 0.003 µs | 0 → 0 |
| 64 | 22.637 ± 1.280 µs | 545.602 ± 20.449 µs | **24.1×** | 285.788 ± 8.994 µs | 15 → 6 |
| 1024 | 23.108 ± 3.123 µs | 529.288 ± 12.895 µs | **22.9×** | 534.017 ± 15.703 µs | 15 → 15 |

Allocation, `gc.alloc.rate.norm`, same run: `commitPlain` 196 992 B/op at both 64 and 1024 states; `commitOptimized` 336 203 B/op at 64 and 393 259 B/op at 1024. The optimisation therefore adds **139 kB/op** where it narrows and **196 kB/op** where it does not.

**The decision: Task 6 adds it, and skips nothing.** `optimize()` costs about **0.5 ms per generated chunk**, a little over twenty times the commit it follows. Against S7's census of 441 chunks around one spawn that is roughly 0.23 s of one-off CPU for the whole spawn area, paid on the generation path and never again. For that price a section whose content fits an indirect palette goes from 15 bpe to 6, which is the conversion S9 priced at 203 840 against 84 800 B. The cost is real and is hereby booked; US-2.03 stays a Must and now has its number.

**Three findings that change what Task 6 may claim.**

1. **The uniform case is free, not cheap.** At one distinct state the generator has already left the palette in single value mode — `PaletteImpl#setAll` sends a constant supplier to `fill(fillValue)` — and `optimize` returns on its opening `bitsPerEntry == 0`. 0.049 against 0.044 µs. Task 6 must not describe the optimisation as costing something on every chunk; on flat and on empty sections it costs nothing.

2. **Above 256 distinct states per section the optimisation charges full price and returns nothing.** `PaletteImpl#downsizeWithPalette` opens with `if (newBpe >= bpe || newBpe > maxBitsPerEntry) return;` and `maxBitsPerEntry` is 8 for blocks. A section holding more than 256 distinct states cannot be narrowed at all, yet `optimize` has already walked all 4 096 entries through `getAll` and built an `IntOpenHashSet` over them before it finds out. At 1 024 states `commitOptimized` (529.3 µs) and the control `optimizeAlreadyPacked` (534.0 µs) are the same number within their error bars, and the widths stay at 15 — 506 µs and 196 kB of garbage for zero bytes saved. This is a real hazard for worlds with very heterogeneous sections, and it cannot be cheaply guarded: the walk that would detect it *is* the cost.

3. **The benchmark had to re-stage its fixture, and the reason is a trap for Task 6 as well.** `MinestomChunks#fill` writes through `Chunk#setBlock`, and a palette grown one block at a time is never more than about a bit wider than its content needs — the survey found 7 → 6 at 64 states, not 15 → 6. A generator does not write that way: `UnitModifier#setAllRelative` ends in `PaletteImpl#setAll`, which calls `makeDirect()` **unconditionally** for any non-constant supplier, without looking at how many distinct values it saw. A generated section is at the direct width because of *how* it was written, not because of *what* it holds, and that — not the block count — is what `optimize` reclaims. `GeneratorCommitBenchmark#widthAGeneratorWouldLeave` reproduces both branches through the only public door to them (`Optimization.SPEED` is `makeDirect`, `Optimization.SIZE` on single-valued content is the `fill`). Measuring the `setBlock` shape and reporting it as the generator shape understated cost and benefit at two of the three points on the axis, and the first draft of this benchmark did exactly that.

---

## Stage 2 result

Measured 2026-08-02 on branch `feat/block-storage`, against Minestom as pinned by the build and
JDK 25.0.3 (Temurin), legacy object headers of twelve bytes with eight byte alignment
(`falco.compactHeaders=false`), sizes through the JOL instrumentation agent.

### The footprint, which is citable

JOL walks a reachable object graph and counts it. That is deterministic, so these figures hold
despite the machine having been under load throughout.

| | objects | bytes |
| --- | ---: | ---: |
| fresh chunk, `DynamicChunk` | 192 | 6 848 |
| fresh chunk, `FalcoChunk` | **25** | **840** |
| difference | **−167** | **−6 008**, or −87.7 % |

A **filled** chunk saves 104 bytes and nothing more, at every state count and every arrangement from
67 kB to 230 kB. That is the honest shape of this stage: the flyweight pays for sections that hold
nothing, and a chunk whose sections all hold something has none of those. The 62.24 % empty share
measured in a real generated overworld is what decides how much of the −6 008 a running server sees,
and that share is itself a measurement of 441 finished chunks around one spawn — not a general claim.

The instance-side cost is unchanged by this stage: 185 B per chunk for an `InstanceContainer`,
161 B for a `FalcoInstance`.

### What materialises, and when

From `SectionMaterialisationTest`, counted rather than timed:

| operation | sections materialised |
| --- | ---: |
| fresh chunk | 0 |
| pure read pass | 0 |
| one `setBlock` at y=64 | 10 |
| serialising a fresh chunk into a packet | 1 |
| `getSection(4)` | 1 |
| `getSections()` | 24 |
| generation of y=−64..0 | 4 of 24 |
| building a heightmap | 0 |
| **first `Heightmap#getHeight` on a fresh chunk** | **24** |
| write order y=200 then y=−64 | 18 |
| the same two writes in the opposite order | 3 |

Two of these deserve to be read twice. The **heightmap descent is the dominant driver**, not the
block write — a single `setBlock` costs ten sections, almost all of them through
`getHighestBlockSection` walking down from the build limit. And the **write order is worth a factor
of six**, which is a property of the storage that no API expresses and that a caller can only exploit
if it is told.

The last line of the first table is the one this stage did *not* fix: `Heightmap#getHeight` still
materialises all twenty-four on a fresh chunk, because Minestom's own fallback walks
`Chunk#getSection`. Building the heightmap is free now; asking it a question is not.

### What `optimize()` costs — a trade, not a win

`GeneratorCommitBenchmark`, µs per chunk of 24 sections, at 1 / 64 / 1 024 distinct states:

| arm | 1 | 64 | 1 024 |
| --- | ---: | ---: | ---: |
| `commitPlain` | 0.044 | 22.0 | 26.3 |
| `commitOptimized`, unconditional | 0.049 | 576.7 | 529.8 |
| `commitGuarded`, asks first | 0.049 | **714.0** | **185.1** |
| `packAlreadyPacked` | 0.047 | **31.8** | 176.1 |

The guard costs **24 % more** where `optimize()` genuinely narrows a palette, and saves **2.9×**
above the indirect limit and **8.5×** on palettes that are already packed. It is worth having because
the unconditional call charges full price for nothing above 256 distinct states per section —
`downsizeWithPalette` gives up when the required width exceeds `maxBitsPerEntry = 8`, but only after
walking all 4 096 entries to find out.

**These timings are not citable.** One fork of a scouting configuration on a machine at load 4.4 to
7.0. They establish direction and rough magnitude, nothing finer. `docs/benchmarks/full-run.sh` has
still never run.

### What the footprint comparison cannot see

Task 9 replaced a single asserted number with a declared per-class difference table, then attacked it
with seven injected defects. Six were caught by name. **The seventh was a `boolean` field on
`FalcoChunk`**, which adds no object and fits into padding the object already carries — it changes
neither the object count nor the shallow size, so nothing in this comparison can observe it. That
limit is now stated in the test's own javadoc rather than left for someone to discover.

A second honesty note the test prints itself: proving the two chunks equivalent is not free on a lazy
chunk. The equivalence check leaves the fresh Falco chunk at 36 objects and 2 168 bytes, against 25
and 840 before it — both heightmaps and the one section the descent materialised. The check runs
after the measurement, so it lands outside the tables, and saying so is cheaper than someone later
finding a discrepancy and mistrusting the numbers.

### Tests

`:falco-instance:` 143, `:falco-anvil:` 193, `:falco-light:` 189, `:falco-demo:` 139,
`:falco-benchmarks:` 38 — all green, none skipped. `ChunkFootprintTest` is green again after having
been deliberately red since stage 1.

### What stage 2 did not do

No off-heap storage, no replacement of `Palette`, no facade split of `FalcoInstance`, no shared
instance — those are stages 3 and 4, or explicit non-goals of the spec. And within its own scope it
leaves the heightmap descent standing: the largest single materialisation driver is Minestom code
this stage chose not to reach into.
