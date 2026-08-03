# Falco Block Storage — Stage 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Falco a chunk that owns its block storage behind an interface, so that the memory layout becomes replaceable without touching a chunk class and the lifecycle stops being tied to `DynamicChunk` by inheritance.

**Architecture:** A bridge. `FalcoChunk` moves from `extends DynamicChunk` to `extends Chunk` and holds a `BlockStorage`. The abstraction side keeps lifecycle, viewers, heightmaps and packet building; the implementation side owns blocks and biomes. Stage 1 ships exactly one implementation, `SectionBlockStorage`, which stores Minestom `Section` objects eagerly — the same layout as today. **Stage 1 must therefore measure identical to `DynamicChunk`.** That is the point: it proves the bridge costs nothing before stage 2 changes the layout behind it.

**Tech Stack:** Java 25, Gradle, JUnit 5, Cyano (Minestom test extension), JMH + JOL for measurement, fastutil.

## Global Constraints

Copied verbatim from the spec (`docs/superpowers/specs/2026-08-01-falco-instance-chunk-design.md`):

- **NFR-001** — compile and run against the pinned Minestom version without reflection, `--add-opens` or an open module.
- **NFR-002** — only language and JDK features final in Java 25. No preview, no incubator.
- **NFR-003** — if a performance claim is published, a JMH or JOL measurement in this repository supports it, stated with its conditions.
- **NFR-004** — while a comparison benchmark runs, it fails rather than reports a number if the two sides disagree.
- **NFR-005** — when a chunk read fails, the failure reaches the caller instead of being reported as an absent chunk.
- **NFR-006** — while a block is written, the lock held is the lock of the chunk it touches, not a monitor over the instance.
- **NFR-007** — the chunk allocates no object per block read on any path.
- **NFR-009** — every new public type carries `@ApiStatus.Experimental`.

Repository conventions, non-negotiable:

- **Source and Javadoc are English**, and Javadoc *justifies* decisions in `<p>` paragraphs and `<h2>` sections. Every type carries `@author TheMeinerLP`, `@version`, `@since 0.4.0`. Model: `falco-light/src/main/java/net/onelitefeather/falco/light/ChunkLightService.java`.
- **Gradle files stay comment-free.**
- **Minestom reference is the pinned sources jar**, unpacked at `/tmp/claude-1000/-mnt-projects-oss-onelitefeather-Falco/34edb948-9dfe-4540-9666-9e29f0d44d7b/scratchpad/minestom-src/`. The clone at `/mnt/projects/oss/minestom/Minestom` is ten months stale and must not be used.
- Work happens in the worktree `/mnt/projects/oss/onelitefeather/Falco-worktrees/falco-bom`, on branch `feat/falco-bom`.

## What `Chunk` demands

`Chunk` is `public abstract` and implements `Block.Getter, Block.Setter, Biome.Getter, Biome.Setter, Viewable, Tickable, Taggable, Snapshotable`. A subclass must supply these eleven:

```java
protected abstract void setBlock(int x, int y, int z, Block block,
                                 @Nullable BlockHandler.Placement placement,
                                 @Nullable BlockHandler.Destroy destroy);   // :99
public abstract List<Section> getSections();                                // :103
public abstract Section getSection(int section);                            // :105
public abstract Heightmap motionBlockingHeightmap();                        // :107
public abstract Heightmap worldSurfaceHeightmap();                          // :108
public abstract void loadHeightmapsFromNBT(CompoundBinaryTag heightmaps);   // :109
public abstract void tick(long time);                                       // :125
public abstract SendablePacket getFullDataPacket();                         // :141
public abstract Chunk copy(Instance instance, int chunkX, int chunkZ);      // :153
public abstract void reset();                                               // :158
public abstract void invalidate();                                          // :315
```

`DynamicChunk` is the reference implementation for all of them. Read it before Task 3; do not invent behaviour that it already defines.

## File Structure

| File | Responsibility |
|---|---|
| `falco-instance/src/main/java/net/onelitefeather/falco/instance/BlockStorage.java` | **Create.** The implementation side of the bridge: blocks and biomes of one chunk, addressed in chunk-local coordinates. Knows nothing about lifecycle, viewers, packets or heightmaps. |
| `.../instance/SectionBlockStorage.java` | **Create.** The stage 1 implementation. Holds Minestom `Section` objects eagerly, exactly as `DynamicChunk` does today. |
| `.../instance/FalcoChunk.java` | **Rewrite.** From `extends DynamicChunk` (129 lines, no fields) to `extends Chunk` holding a `BlockStorage`. |
| `falco-instance/src/test/java/.../instance/BlockStorageTest.java` | **Create.** Contract tests for the interface, run against every implementation. |
| `falco-instance/src/test/java/.../instance/FalcoChunkEquivalenceTest.java` | **Create.** Position-by-position equivalence against `DynamicChunk` (US-1.03). |
| `falco-instance/src/test/java/.../instance/FalcoChunkInContainerTest.java` | **Create.** The chunk loads and unloads inside a plain `InstanceContainer` (US-1.05). |

`falco-benchmarks` already carries `ChunkComparisonBenchmark` and `ChunkFootprintTest`; both compare `FalcoChunk` against `DynamicChunk` and will start reporting the bridge automatically. They are the regression net for this stage and must be re-run at the end.

---

### Task 1: The `BlockStorage` interface

**Files:**
- Create: `falco-instance/src/main/java/net/onelitefeather/falco/instance/BlockStorage.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/BlockStorageTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `BlockStorage` with `Block getBlock(int, int, int, Block.Getter.Condition)`, `void setBlock(int, int, int, Block)`, `RegistryKey<Biome> getBiome(int, int, int)`, `void setBiome(int, int, int, RegistryKey<Biome>)`, `List<Section> sections()`, `Section section(int)`, `int sectionCount()`, `BlockStorage copy()`, `void clear()`. Tasks 2 and 3 depend on exactly these names.

- [ ] **Step 1: Write the failing test**

Create `BlockStorageTest.java`. It is written against the interface so that stage 2's implementation inherits it unchanged:

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

@DisplayName("The block storage of a chunk")
class BlockStorageTest {

    private static final int SECTIONS = 24;
    private static final int MIN_SECTION = -4;

    @BeforeAll
    static void server() {
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }
    }

    private static BlockStorage storage() {
        return new SectionBlockStorage(MIN_SECTION, SECTIONS);
    }

    @Test
    @DisplayName("returns air for a position nothing was written to")
    void testEmptyReadsAir() {
        assertEquals(Block.AIR, storage().getBlock(0, 0, 0, Block.Getter.Condition.NONE));
    }

    @Test
    @DisplayName("returns what was written, at the position it was written to")
    void testWriteThenRead() {
        final BlockStorage storage = storage();

        storage.setBlock(1, 2, 3, Block.STONE);

        assertEquals(Block.STONE, storage.getBlock(1, 2, 3, Block.Getter.Condition.NONE));
        assertEquals(Block.AIR, storage.getBlock(1, 2, 4, Block.Getter.Condition.NONE));
    }

    @Test
    @DisplayName("holds one section per section of the chunk")
    void testSectionCount() {
        assertEquals(SECTIONS, storage().sectionCount());
        assertEquals(SECTIONS, storage().sections().size());
    }

    @Test
    @DisplayName("copies without sharing storage with the original")
    void testCopyIsIndependent() {
        final BlockStorage original = storage();
        original.setBlock(1, 2, 3, Block.STONE);

        final BlockStorage copy = original.copy();
        copy.setBlock(1, 2, 3, Block.DIRT);

        assertNotSame(original, copy);
        assertEquals(Block.STONE, original.getBlock(1, 2, 3, Block.Getter.Condition.NONE));
        assertEquals(Block.DIRT, copy.getBlock(1, 2, 3, Block.Getter.Condition.NONE));
    }

    @Test
    @DisplayName("reads air everywhere after being cleared")
    void testClear() {
        final BlockStorage storage = storage();
        storage.setBlock(1, 2, 3, Block.STONE);

        storage.clear();

        assertEquals(Block.AIR, storage.getBlock(1, 2, 3, Block.Getter.Condition.NONE));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/falco-bom
./gradlew :falco-instance:test --tests "*BlockStorageTest*"
```

Expected: compilation failure — `BlockStorage` and `SectionBlockStorage` do not exist.

- [ ] **Step 3: Write the interface**

Create `BlockStorage.java`. The Javadoc must state *why* the type exists, in the style of `ChunkLightService`:

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * The {@link BlockStorage} interface is the implementation side of the chunk of Falco.
 * <p>
 * A chunk of Minestom keeps its blocks in a field its subclasses inherit, which means that a chunk
 * which wants a different memory layout has to be a different chunk class. That is why
 * {@code FalcoChunk} and {@code FalcoLightingChunk} cannot be combined today: both of them extend
 * {@code DynamicChunk}, and a class has one superclass. Moving the storage behind this interface
 * turns those two branches into two parts that compose.
 * </p>
 * <p>
 * The split is drawn where the two sides stop needing each other. Everything that is about the
 * identity of a chunk stays outside: its position, its lifecycle, its viewers, its heightmaps and
 * the packet it sends. Everything that is about where a block physically sits lives here. An
 * implementation of this interface therefore never needs an {@code Instance}, and the chunk never
 * needs to know whether the blocks below it are sections, a packed array or something off heap.
 * </p>
 * <p>
 * Coordinates are chunk-local: {@code x} and {@code z} are {@code 0} to {@code 15}, and {@code y} is
 * an absolute world height, because that is the form both {@code Chunk} and the anvil format use and
 * translating twice would be a second place to get it wrong.
 * </p>
 * <p>
 * Implementations are not thread-safe on their own. The caller holds the lock of the chunk, which
 * {@code Chunk#lockWriteLock()} and {@code Chunk#lockReadLock()} provide.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public interface BlockStorage {

    /**
     * Reads the block at a position.
     *
     * @param x         the chunk-local block X, {@code 0} to {@code 15}
     * @param y         the absolute block Y
     * @param z         the chunk-local block Z, {@code 0} to {@code 15}
     * @param condition what the caller is willing to accept, as {@code Block.Getter} defines it
     * @return the block, or {@code null} if the condition excludes it
     */
    Block getBlock(int x, int y, int z, Block.Getter.Condition condition);

    /**
     * Writes a block to a position.
     *
     * @param x     the chunk-local block X, {@code 0} to {@code 15}
     * @param y     the absolute block Y
     * @param z     the chunk-local block Z, {@code 0} to {@code 15}
     * @param block the block to write
     */
    void setBlock(int x, int y, int z, Block block);

    /**
     * Reads the biome at a position.
     *
     * @param x the chunk-local block X, {@code 0} to {@code 15}
     * @param y the absolute block Y
     * @param z the chunk-local block Z, {@code 0} to {@code 15}
     * @return the biome
     */
    RegistryKey<Biome> getBiome(int x, int y, int z);

    /**
     * Writes a biome to a position.
     *
     * @param x     the chunk-local block X, {@code 0} to {@code 15}
     * @param y     the absolute block Y
     * @param z     the chunk-local block Z, {@code 0} to {@code 15}
     * @param biome the biome to write
     */
    void setBiome(int x, int y, int z, RegistryKey<Biome> biome);

    /**
     * Hands out the sections of this storage, from the bottom one upwards.
     * <p>
     * This is a boundary method. Minestom demands {@code Section} objects for packet serialisation,
     * for its light engine and for the anvil writer, so an implementation which does not store them
     * has to materialise them here. Calling this is therefore the one operation that can undo
     * whatever an implementation saved by not holding them, which is why the chunk calls it only
     * where Minestom leaves no choice.
     * </p>
     *
     * @return the sections of this storage
     */
    List<Section> sections();

    /**
     * Hands out one section of this storage.
     *
     * @param section the index of the section, counted from the bottom one
     * @return the section
     */
    Section section(int section);

    /**
     * Reports how many sections this storage spans.
     *
     * @return the amount of sections
     */
    int sectionCount();

    /**
     * Creates a storage holding the same blocks and biomes as this one, sharing nothing with it.
     *
     * @return the copy
     */
    BlockStorage copy();

    /**
     * Resets this storage to the state it had when it was created.
     */
    void clear();
}
```

- [ ] **Step 4: Run the test to verify it still fails, for the right reason**

```bash
./gradlew :falco-instance:test --tests "*BlockStorageTest*"
```

Expected: still a compilation failure, now only about `SectionBlockStorage`. If it complains about anything in `BlockStorage`, fix that before moving on.

- [ ] **Step 5: Commit the interface**

```bash
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/BlockStorage.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/BlockStorageTest.java
git commit -m "feat(instance): introduce BlockStorage, the implementation side of the chunk"
```

---

### Task 2: `SectionBlockStorage`

**Files:**
- Create: `falco-instance/src/main/java/net/onelitefeather/falco/instance/SectionBlockStorage.java`
- Test: `BlockStorageTest.java` (from Task 1, unchanged)

**Interfaces:**
- Consumes: `BlockStorage` from Task 1.
- Produces: `SectionBlockStorage(int minSection, int sectionCount)` and `SectionBlockStorage(int minSection, List<Section> sections)`. Task 3 constructs both.

**Reference:** `DynamicChunk#getBlock` (`:197`), `#setBlock` (`:74`) and `#setBiome` (`:137`) in the pinned sources. Copy their coordinate arithmetic rather than re-deriving it — `CoordConversion` holds the index helpers, and getting the section index wrong is silent, not loud.

- [ ] **Step 1: Write the implementation**

Eager sections, the same layout `DynamicChunk` has today. Stage 2 replaces this class; stage 1 must not change behaviour.

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The {@link SectionBlockStorage} class stores the blocks of a chunk in Minestom {@link Section}
 * objects, one per sixteen blocks of height, allocated when the storage is created.
 * <p>
 * This is deliberately the layout {@code DynamicChunk} already has, and it is the first
 * implementation on purpose: it makes the bridge measurable before the bridge changes anything. A
 * chunk built on this storage has to be indistinguishable from a {@code DynamicChunk} in both time
 * and bytes, and {@code ChunkComparisonBenchmark} together with {@code ChunkFootprintTest} is what
 * says whether it is. A layout that saves memory is the subject of the next stage; if this one
 * already differed, the difference of that next stage could not be attributed to it.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public final class SectionBlockStorage implements BlockStorage {

    private static final DynamicRegistry<Biome> BIOME_REGISTRY = MinecraftServer.getBiomeRegistry();

    private final int minSection;
    private final List<Section> sections;

    /**
     * Creates a storage of empty sections.
     *
     * @param minSection   the index of the bottom section of the chunk
     * @param sectionCount the amount of sections the chunk spans
     */
    public SectionBlockStorage(int minSection, int sectionCount) {
        final Section[] created = new Section[sectionCount];

        Arrays.setAll(created, index -> new Section());
        this.minSection = minSection;
        this.sections = List.of(created);
    }

    /**
     * Creates a storage which takes over the given sections.
     *
     * @param minSection the index of the bottom section of the chunk
     * @param sections   the sections, from the bottom one upwards
     */
    public SectionBlockStorage(int minSection, List<Section> sections) {
        this.minSection = minSection;
        this.sections = List.copyOf(sections);
    }

    @Override
    public Block getBlock(int x, int y, int z, Block.Getter.Condition condition) {
        final Section section = section(CoordConversion.globalToChunk(y) - this.minSection);
        final int stateId = section.blockPalette()
                .get(CoordConversion.globalToSectionRelative(x),
                        CoordConversion.globalToSectionRelative(y),
                        CoordConversion.globalToSectionRelative(z));

        return Block.fromStateId(stateId);
    }

    @Override
    public void setBlock(int x, int y, int z, Block block) {
        section(CoordConversion.globalToChunk(y) - this.minSection).blockPalette()
                .set(CoordConversion.globalToSectionRelative(x),
                        CoordConversion.globalToSectionRelative(y),
                        CoordConversion.globalToSectionRelative(z), block.stateId());
    }

    @Override
    public RegistryKey<Biome> getBiome(int x, int y, int z) {
        final Section section = section(CoordConversion.globalToChunk(y) - this.minSection);
        final int id = section.biomePalette()
                .get(CoordConversion.globalToSectionRelative(x) / Section.BIOME_SIZE,
                        CoordConversion.globalToSectionRelative(y) / Section.BIOME_SIZE,
                        CoordConversion.globalToSectionRelative(z) / Section.BIOME_SIZE);

        return BIOME_REGISTRY.getKey(id);
    }

    @Override
    public void setBiome(int x, int y, int z, RegistryKey<Biome> biome) {
        section(CoordConversion.globalToChunk(y) - this.minSection).biomePalette()
                .set(CoordConversion.globalToSectionRelative(x) / Section.BIOME_SIZE,
                        CoordConversion.globalToSectionRelative(y) / Section.BIOME_SIZE,
                        CoordConversion.globalToSectionRelative(z) / Section.BIOME_SIZE,
                        BIOME_REGISTRY.getId(biome));
    }

    @Override
    public List<Section> sections() {
        return this.sections;
    }

    @Override
    public Section section(int section) {
        return this.sections.get(section);
    }

    @Override
    public int sectionCount() {
        return this.sections.size();
    }

    @Override
    public BlockStorage copy() {
        final List<Section> copied = new ArrayList<>(this.sections.size());

        for (Section section : this.sections) {
            copied.add(section.clone());
        }
        return new SectionBlockStorage(this.minSection, copied);
    }

    @Override
    public void clear() {
        for (Section section : this.sections) {
            section.clear();
        }
    }
}
```

- [ ] **Step 2: Verify the API against the pinned sources before running anything**

Every name used above must exist. Check each one and correct the code if it does not:

```bash
S=/tmp/claude-1000/-mnt-projects-oss-onelitefeather-Falco/34edb948-9dfe-4540-9666-9e29f0d44d7b/scratchpad/minestom-src
grep -nE "globalToChunk|globalToSectionRelative" $S/net/minestom/server/coordinate/CoordConversion.java | head
grep -nE "blockPalette|biomePalette|BIOME_SIZE|public void clear|public Section clone" $S/net/minestom/server/instance/Section.java
grep -nE "public .*getKey|public .*getId" $S/net/minestom/server/registry/DynamicRegistry.java | head
grep -n "getBiome\|setBiome" $S/net/minestom/server/instance/DynamicChunk.java | head
```

If `DynamicChunk` divides biome coordinates differently than the code above, **follow `DynamicChunk`** — it is the behaviour the equivalence test in Task 4 compares against.

- [ ] **Step 3: Run the tests**

```bash
./gradlew :falco-instance:test --tests "*BlockStorageTest*"
```

Expected: PASS, all five.

- [ ] **Step 4: Commit**

```bash
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/SectionBlockStorage.java
git commit -m "feat(instance): store blocks in sections behind BlockStorage"
```

---

### Task 3: `FalcoChunk` over the bridge

**Files:**
- Rewrite: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoChunk.java`
- Test: covered by Task 4

**Interfaces:**
- Consumes: `BlockStorage`, `SectionBlockStorage` from Tasks 1–2.
- Produces: `FalcoChunk(Instance, int, int)`, `FalcoChunk(Instance, int, int, BlockStorage)`, `markLoaded()`, `markUnloaded()`, `storage()`.

**Reference:** `DynamicChunk` in the pinned sources implements all eleven abstract members. Read it in full first. Everything that is not about *where blocks live* — the entries map, the tickable map, the heightmaps, the cached packet, `tick`, `getFullDataPacket`, `reset`, `invalidate`, `loadHeightmapsFromNBT` — is carried over as it stands. Only block and biome access is redirected to the storage.

- [ ] **Step 1: Read the reference and write down what carries over**

```bash
S=/tmp/claude-1000/-mnt-projects-oss-onelitefeather-Falco/34edb948-9dfe-4540-9666-9e29f0d44d7b/scratchpad/minestom-src
sed -n '1,120p' $S/net/minestom/server/instance/DynamicChunk.java
```

Note especially: `setBlock` maintains `entries` and `tickableMap` and refreshes both heightmaps; `getBlock` guards the entries lookup by condition; `createChunkPacket` runs under the read lock.

- [ ] **Step 2: Write the members that change**

These are the ones where the bridge is visible. Everything not listed here is **carried over from `DynamicChunk` unchanged** — copy the body across, keep its Javadoc intent, and do not redesign it: `motionBlockingHeightmap`, `worldSurfaceHeightmap`, `loadHeightmapsFromNBT`, `tick`, `getFullDataPacket`, `createChunkPacket`, `reset`, `invalidate`, and the viewer and tag plumbing.

```java
public class FalcoChunk extends Chunk {

    private final BlockStorage storage;

    protected final Int2ObjectOpenHashMap<Block> entries = new Int2ObjectOpenHashMap<>(0);
    protected final Int2ObjectOpenHashMap<Block> tickableMap = new Int2ObjectOpenHashMap<>(0);

    protected Heightmap motionBlocking = new MotionBlockingHeightmap(this);
    protected Heightmap worldSurface = new WorldSurfaceHeightmap(this);

    private final CachedPacket chunkCache = new CachedPacket(this::createChunkPacket);

    public FalcoChunk(Instance instance, int chunkX, int chunkZ) {
        super(instance, chunkX, chunkZ, true);
        // Must be built here, not in a field initialiser: the super constructor is what computes
        // minSection and maxSection, and the storage is sized from them.
        this.storage = new SectionBlockStorage(minSection, maxSection - minSection);
    }

    public FalcoChunk(Instance instance, int chunkX, int chunkZ, BlockStorage storage) {
        super(instance, chunkX, chunkZ, true);
        this.storage = storage;
    }

    public BlockStorage storage() {
        return this.storage;
    }

    @Override
    public List<Section> getSections() {
        return this.storage.sections();
    }

    @Override
    public Section getSection(int section) {
        return this.storage.section(section - minSection);
    }

    @Override
    public Chunk copy(Instance instance, int chunkX, int chunkZ) {
        assertReadLock();
        final FalcoChunk copy = new FalcoChunk(instance, chunkX, chunkZ, this.storage.copy());

        copy.entries.putAll(this.entries);
        // DynamicChunk#copy copies only entries, so a copied chunk stops ticking. That omission is
        // a bug the previous FalcoChunk already fixed, and it must not return with the rewrite.
        copy.tickableMap.putAll(this.tickableMap);
        return copy;
    }

    public void markLoaded() {
        onLoad();
    }

    public void markUnloaded() {
        unload();
    }
}
```

`getBlock` and `setBlock` keep every line of bookkeeping `DynamicChunk` does — the entries map, the tickable map, both heightmap refreshes, the packet invalidation — and change only where the block itself comes from and goes to:

```java
@Override
public void setBlock(int x, int y, int z, Block block,
                     @Nullable BlockHandler.Placement placement,
                     @Nullable BlockHandler.Destroy destroy) {
    assertWriteLock();
    // ... every guard and every bookkeeping line of DynamicChunk#setBlock, unchanged ...
    this.storage.setBlock(x, y, z, block);          // was: getSectionAt(y).blockPalette().set(...)
    // ... the entries/tickableMap maintenance and both heightmap refreshes, unchanged ...
}

@Override
public @Nullable Block getBlock(int x, int y, int z, Condition condition) {
    assertReadLock();
    // ... the entries lookup and its condition guard, exactly as DynamicChunk has it ...
    return this.storage.getBlock(x, y, z, condition);   // was: the palette read
}
```

**Note the index shift in `getSection`.** `Chunk#getSection(int)` takes a section index in world terms, which can be negative; `BlockStorage#section(int)` takes an offset from the bottom section. Subtracting `minSection` is the translation, and getting it wrong is silent — the equivalence test of Task 4 is what catches it.

The class Javadoc must be rewritten. The old one says *"deliberately adds no storage, no light handling and no packet handling of its own"*, which stops being true here. State instead that the storage moved behind an interface, and why: two subclasses of `DynamicChunk` could not be combined, and a class has one superclass.

- [ ] **Step 3: Compile**

```bash
./gradlew :falco-instance:compileJava
```

Expected: BUILD SUCCESSFUL. An "does not override abstract method" error means one of the eleven is missing — add it from `DynamicChunk`.

- [ ] **Step 4: Run the existing instance tests**

```bash
./gradlew :falco-instance:test
```

Expected: PASS. `FalcoChunkTest`, `FalcoInstanceTest`, `FalcoInstanceGeneratorTest`, `FalcoInstanceUnloadTest` and `FalcoInstanceLoadRaceTest` exercise the chunk through the instance and are the first real net. If one fails, the rewrite changed behaviour — fix the rewrite, not the test.

- [ ] **Step 5: Commit**

```bash
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoChunk.java
git commit -m "refactor(instance): hold block storage instead of inheriting it"
```

---

### Task 4: Prove equivalence against `DynamicChunk`

**Files:**
- Create: `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoChunkEquivalenceTest.java`

**Interfaces:**
- Consumes: `FalcoChunk` from Task 3.
- Produces: nothing. This task exists to make US-1.03 and NFR-004 true.

**Model:** `falco-light/src/test/java/net/onelitefeather/falco/light/LightEngineEquivalenceTest.java` — fixed seed as a named constant, parameterised over arrangements, and an anti-tautology assertion that the fixture is not empty.

- [ ] **Step 1: Write the test**

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.DynamicChunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("A Falco chunk against the chunk of Minestom")
class FalcoChunkEquivalenceTest {

    private static final long SEED = 20260801L;
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;

    private static Instance instance;

    @BeforeAll
    static void server() {
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }
        instance = MinecraftServer.getInstanceManager().createInstanceContainer();
    }

    private static void fill(Chunk chunk, int distinctStates, long seed) {
        final Random random = new Random(seed);
        final Block[] blocks = new Block[distinctStates];

        for (int index = 0; index < distinctStates; index++) {
            blocks[index] = Block.fromStateId(index + 1);
        }
        chunk.lockWriteLock();
        try {
            for (int y = MIN_Y; y < MIN_Y + HEIGHT; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        chunk.setBlock(x, y, z, blocks[random.nextInt(distinctStates)]);
                    }
                }
            }
        } finally {
            chunk.unlockWriteLock();
        }
    }

    @ParameterizedTest(name = "{0} distinct states")
    @ValueSource(ints = {1, 2, 16, 64, 256, 1024})
    @DisplayName("holds the same block at every position")
    void testEveryPositionAgrees(int distinctStates) {
        final Chunk minestom = new DynamicChunk(instance, 0, 0);
        final Chunk falco = new FalcoChunk(instance, 0, 0);

        fill(minestom, distinctStates, SEED);
        fill(falco, distinctStates, SEED);

        int nonAir = 0;

        minestom.lockReadLock();
        falco.lockReadLock();
        try {
            for (int y = MIN_Y; y < MIN_Y + HEIGHT; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final Block expected = minestom.getBlock(x, y, z);
                        final Block actual = falco.getBlock(x, y, z);

                        assertEquals(expected, actual,
                                "block at " + x + "/" + y + "/" + z + " with " + distinctStates + " states");
                        if (!expected.isAir()) {
                            nonAir++;
                        }
                    }
                }
            }
        } finally {
            falco.unlockReadLock();
            minestom.unlockReadLock();
        }
        assertTrue(nonAir > 0, "the fixture wrote nothing, so this run compared two empty chunks");
    }
}
```

- [ ] **Step 2: Run it**

```bash
./gradlew :falco-instance:test --tests "*FalcoChunkEquivalenceTest*"
```

Expected: PASS for all six parameters. A failure names the exact position — that is the point of the message.

- [ ] **Step 3: Commit**

```bash
git add falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoChunkEquivalenceTest.java
git commit -m "test(instance): prove the bridge chunk agrees with DynamicChunk everywhere"
```

---

### Task 5: The chunk inside a plain `InstanceContainer` (US-1.05)

**Files:**
- Create: `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoChunkInContainerTest.java`

**Interfaces:**
- Consumes: `FalcoChunk` from Task 3.
- Produces: nothing. This is the gate stage 4 depends on: a shared instance needs an `InstanceContainer` as its block owner, so the chunk has to work inside one.

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@DisplayName("A Falco chunk owned by a plain InstanceContainer")
class FalcoChunkInContainerTest {

    @BeforeAll
    static void server() {
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }
    }

    @Test
    @DisplayName("is created by the container, survives a write and unloads cleanly")
    void testContainerOwnsTheChunk() {
        final InstanceContainer container = MinecraftServer.getInstanceManager().createInstanceContainer();

        container.setChunkSupplier(FalcoChunk::new);

        final Chunk chunk = container.loadChunk(0, 0).join();

        assertInstanceOf(FalcoChunk.class, chunk, "the container has to use the supplier it was given");

        container.setBlock(0, 0, 0, Block.STONE);
        assertEquals(Block.STONE, container.getBlock(0, 0, 0));

        container.unloadChunk(chunk);
        assertFalse(chunk.isLoaded(), "the container reaches the protected unload hook itself");

        MinecraftServer.getInstanceManager().unregisterInstance(container);
    }
}
```

- [ ] **Step 2: Run it**

```bash
./gradlew :falco-instance:test --tests "*FalcoChunkInContainerTest*"
```

Expected: PASS. If `isLoaded()` stays true after `unloadChunk`, the container did not reach the hook — check whether `FalcoChunk` accidentally overrides `unload()` with a wider signature.

- [ ] **Step 3: Commit**

```bash
git add falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoChunkInContainerTest.java
git commit -m "test(instance): run the bridge chunk inside a plain InstanceContainer"
```

---

### Task 6: Measure that the bridge cost nothing

**Files:**
- Modify: none. `falco-benchmarks` already carries `ChunkComparisonBenchmark` and `ChunkFootprintTest`, both comparing `FalcoChunk` against `DynamicChunk`.

**This task is the acceptance gate of the whole stage.** Before the rewrite, `ChunkFootprintTest` reported `DELTA B = 0` across all fifteen fill variants and `ChunkComparisonBenchmark` reported `getBlock`, `setBlock` and `heightmapRefresh` as indistinguishable. Stage 1 changes the structure and must not change either.

- [ ] **Step 1: Run the footprint test**

```bash
./gradlew :falco-benchmarks:test --tests "*ChunkFootprintTest*" -i
```

Expected: **`DELTA B = 24` in every row, and exactly one object more** — the `SectionBlockStorage` itself.

**Corrected after measuring.** This step first demanded `DELTA B = 0`, and that demand was wrong when it was written: an indirection is an object, so a chunk that *holds* its storage instead of *being* it must weigh one object more. Zero was never reachable while the storage is a separate type, and a separate type is the entire purpose of this stage. Measured on the pinned build: 192 → 193 objects, 6 848 → 6 872 B.

The assertion is therefore **tightened, not relaxed**. It must require that the extra weight is exactly one object of class `SectionBlockStorage`; a second object, or 24 bytes belonging to any other class, still fails. That preserves what the equality check existed for — catching a field somebody adds behind the project's back. A tolerance such as "at most 64 bytes" would not, and is explicitly rejected.

For scale: 24 B is 0.35 % of a fresh chunk and 0.01 % of a generated one, against the 2 911 B per chunk that stage 2 removes.

- [ ] **Step 2: Run the comparison benchmark, scouting configuration**

```bash
./gradlew :falco-benchmarks:jmh -Pjmh.quick \
  -Pjmh.include="ChunkComparisonBenchmark" \
  -Pjmh.params="distinctStates=1,64,1024;fillShape=RANDOM_RUNS"
```

Expected: `falcoGetBlock`, `falcoSetBlock` and `falcoHeightmapRefresh` within the error bars of their `minestom*` counterparts. Note that `minestomCopy`/`falcoCopy` are **not** comparable — they measure Minestom's viewer cache leak; use `minestomCopyIsolated`/`falcoCopyIsolated` instead.

- [ ] **Step 3: Run the full test suite**

```bash
./gradlew :falco-instance:test :falco-light:test :falco-anvil:test
```

Expected: all PASS. `falco-light` matters here because `FalcoLightingChunk` still extends `DynamicChunk` — stage 1 does not touch it, and it must keep working.

- [ ] **Step 4: Record the result in the plan**

Append a short section to this file under a heading `## Stage 1 result`, stating the measured delta and the date. If the delta is not zero, state the number and the suspected cause instead of rounding it away.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/plans/2026-08-01-falco-block-storage.md
git commit -m "docs(plan): record what stage 1 measured"
```

---

## Definition of done

- [ ] `BlockStorage` exists, with contract tests that stage 2's implementation will inherit unchanged
- [ ] `FalcoChunk` extends `Chunk` and holds its storage instead of inheriting it
- [ ] Equivalence against `DynamicChunk` is proven position by position over six state counts
- [ ] The chunk loads, is written to, and unloads inside a plain `InstanceContainer`
- [ ] `ChunkFootprintTest` reports `DELTA B = 24`, and its assertion requires that the extra object is a `SectionBlockStorage` rather than merely allowing 24 bytes from anywhere
- [ ] `falco-instance`, `falco-light` and `falco-anvil` tests all pass
- [ ] Every new public type carries `@ApiStatus.Experimental`, `@author`, `@version` and `@since 0.4.0`

## What stage 1 deliberately does not do

Named here so that a reviewer does not read them as omissions: no flyweight for empty sections, no lazy heightmaps, no packed flags, no `optimize()` after generation, no facade split of `FalcoInstance`, no shared instance. Those are stages 2 to 4. Stage 1 buys the seam they all need, and pays for it with a measurement that proves the seam is free.

---

## Stage 1 result

Measured on 2026-08-01, on the branch `feat/block-storage` at the commit that precedes this section,
against Minestom as pinned by the build and JDK 25.0.3 (Temurin).

### The footprint, which is citable

`./gradlew :falco-benchmarks:test --tests "*ChunkFootprintTest*" -i`, all three tests pass. Legacy
object headers of twelve bytes, eight byte alignment, sizes taken through the JOL instrumentation
agent (`falco.compactHeaders=false`), so every figure below is a number under that mode and must not
be quoted next to a number taken under `-XX:+UseCompactObjectHeaders`.

| | objects | bytes |
| --- | --- | --- |
| `DynamicChunk`, fresh | 192 | 6 848 |
| `FalcoChunk`, fresh | 193 | 6 872 |
| difference | **+1** | **+24** |

`DELTA B = 24` in all sixteen rows of the second table as well — every distinct state count from one
to a thousand and twenty-four, in all three arrangements, from a chunk of 6 848 bytes to one of
229 784. The delta does not grow with the chunk, because the seam is one object and not a per-section
or per-block cost.

The one extra object is the `SectionBlockStorage`. That is asserted rather than assumed: the
comparison runs per class over the union of the classes either side retains and demands equality
everywhere except `net.onelitefeather.falco.instance.SectionBlockStorage`, of which the Falco side has
to hold exactly one and the Minestom side none, plus the requirement that the whole byte difference is
the size of that one object. The chunk class itself and the lambda classes the JVM generates from it
are compared as a single post, since `DynamicChunk` and `FalcoChunk` are different classes by
construction and the generated names are not stable between runs; their object count and their bytes
still have to match, which is what holds the shallow size of the chunk under assertion.

Three injected defects were used to confirm the assertion still bites, each reverted afterwards:

| injected into `FalcoChunk` | caught by |
| --- | --- |
| `private final Object probe = new Object();` | per class comparison, `1` object of `java.lang.Object` against `0` |
| `private final BlockStorage probe = new SectionBlockStorage(0, 0);` | `FalcoChunk has to hold exactly one BlockStorage, not 2` |
| `private final long probe = System.nanoTime();` | the chunk post weighing 104 bytes against 96 |

The third is the interesting one: a primitive field adds no object at all, and the run showed that
adding a reference field does not necessarily change the shallow size either, because the eighty byte
`FalcoChunk` had padding to spare. The byte comparison of the chunk post is what catches that case,
and it is the reason the assertion is not merely a count.

### The tests

`./gradlew :falco-instance:test :falco-light:test :falco-anvil:test --rerun-tasks`: 48, 189 and 193
tests, no failure, no error, nothing skipped. `falco-light` matters because `FalcoLightingChunk` still
extends `DynamicChunk` and was deliberately left alone by this stage.

`./gradlew :falco-benchmarks:test --rerun-tasks`: 36 tests over six classes, no failure, no error, one
skipped — `EmptySectionCensusTest` needs a real Anvil world next to the repository and aborts its
assumption when there is none.

The closing review of this stage added tests to `falco-instance`, which is why a run today reports 66
there instead of 48; the other three counts are unchanged. What they cover: the section a height
belongs to, asserted through `BlockStorage#section(int)` rather than by reading back what was written,
because every case used to sit at `y = 0..3` where `- minSection` contributes a constant and could be
deleted with the whole file staying green; the biomes, which nothing read or wrote through the seam in
either direction; and a column outside the chunk, which is the contract stage 2's storage inherits.

That run is the one that carries US-1.03 and it is named separately because it is easy to miss: the
benchmark module's `test` task is an ordinary test task under `check`, but the module's name suggests
JMH and nothing else. The equivalence it proves is the strongest on the branch.
`FalcoChunkEquivalenceTest` drives 18 fixtures — three fill shapes against six state counts — through
`MinestomChunks#assertSameBlocks`, which compares every one of the 16·16·16·`sectionCount` positions
**and both heightmaps of every column**, and each fixture additionally goes through a scattered write
batch, a full heightmap refresh on both sides and three copy comparisons. The criterion US-1.03
spells out is exactly that, heightmaps included.

`falco-instance`'s own `FalcoChunkEquivalenceTest` is deliberately not the evidence for US-1.03. It is
weaker by construction: one fill shape, no heightmap comparison, no copy, so it would stay green if
the two `refresh` calls were deleted from `FalcoChunk#setBlock`. It earns its place by needing nothing
but the module it lives in, which is what makes it run in the fast loop; the criterion is met in
`falco-benchmarks`.

### The comparison benchmark, which is NOT citable

`./gradlew :falco-benchmarks:jmh -Pjmh.quick -Pjmh.include="ChunkComparisonBenchmark"
-Pjmh.params="distinctStates=1,64,1024;fillShape=RANDOM_RUNS"`, average time in microseconds per
operation, ± the 99.9 % confidence interval.

**The conditions disqualify every number here from being quoted.** The scouting configuration is one
fork, two warmup iterations and three measurement iterations of one second — enough to see whether
two curves lie on top of each other, far too little to state a difference. The machine was under
other load throughout: sixteen hardware threads on an AMD Ryzen 7 5800X, load average 4.7 at the
start of the run. These numbers answer "is there a regression large enough to see through the noise",
and nothing else.

| benchmark | states | Minestom | Falco |
| --- | --- | --- | --- |
| `getBlock` | 1 | 24,482 ± 6,267 | 24,779 ± 0,910 |
| `getBlock` | 64 | 28,958 ± 9,285 | 29,688 ± 4,784 |
| `getBlock` | 1024 | 34,794 ± 4,800 | 34,682 ± 11,410 |
| `setBlock` | 1 | 68,489 ± 6,880 | 73,740 ± 9,023 |
| `setBlock` | 64 | 101,137 ± 2,771 | 105,127 ± 8,939 |
| `setBlock` | 1024 | 103,151 ± 34,972 | 103,009 ± 35,957 |
| `heightmapRefresh` | 1 | 6,448 ± 0,525 | 6,819 ± 0,833 |
| `heightmapRefresh` | 64 | 8,289 ± 0,697 | 8,101 ± 0,495 |
| `heightmapRefresh` | 1024 | 7,437 ± 1,585 | **76,423 ± 2147,865** |
| `copyIsolated` | 1 | 7,058 ± 3,348 | 6,607 ± 0,273 |
| `copyIsolated` | 64 | 12,957 ± 3,059 | 12,895 ± 3,495 |
| `copyIsolated` | 1024 | 20,520 ± 9,433 | 20,107 ± 6,239 |

Every pair but one overlaps inside its error bars. `minestomCopy` and `falcoCopy` are omitted on
purpose: they measure Minestom's viewer cache leak along with the copy and are not comparable to
anything, which is why `copyIsolated` exists.

The exception is `falcoHeightmapRefresh` at 1 024 states, and it is not a finding. Its three
iterations were 212,367, 8,737 and 8,164 µs/op — the second and third sit next to Minestom's 7,437,
the first is a stall of the loaded machine, and the resulting error bar of ± 2 147 µs is larger than
the mean by a factor of twenty-eight, which is JMH stating that the trial measured nothing. The two
warmup iterations of the same trial were 8,506 and 8,106. It has to be rerun on an idle machine
before anyone treats it as either a regression or its absence.

### What this stage bought and what it cost

The seam costs one object of 24 bytes per chunk: 0,35 % of a fresh chunk of 6 848 bytes, 0,01 % of a
generated one of roughly two hundred kibibytes, against the 2 911 bytes per chunk stage 2 is planned
to remove. Nothing in the time measurements survives its own error bars as a difference. The stage is
accepted with the delta stated rather than rounded to zero.
