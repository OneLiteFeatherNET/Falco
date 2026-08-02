# Falco Shared Instance — Stage 4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Falco a shared instance that keeps Minestom's chunk-resend fast path and repairs the four places where Minestom's `SharedInstance` writes through to the container it borrows chunks from, so that two views of one world stop reconfiguring each other.

**Architecture:** `FalcoSharedInstance extends SharedInstance`. Not a new type beside `SharedInstance` — a subclass of it, and the reason is a measurement. `SharedInstance#areLinked` decides whether `Player#setInstance` re-sends every chunk in view distance or none; it compares `getInstanceContainer()` rather than testing for a concrete class, so a subclass keeps the fast path for free. M16 puts a full resend at 765 ms and 86.5 MB of allocation against zero for the fast path. Nothing in `SharedInstance` is `final`, so every delegating method is replaceable. Four of them are replaced: `setGenerator`, `setChunkSupplier` and `enableAutoChunkLoad` stop aliasing the container, and `saveInstance` stops persisting the container's tags in place of this instance's. `setBlock` is deliberately **not** replaced — see *The wall* below.

**Tech Stack:** Java 25, Gradle, JUnit 5, Cyano 0.6.2 (Minestom test extension, `MicrotusExtension` / `Env` / `TestConnection`), Minestom `2026.06.20-26.1.2`.

## Global Constraints

Copied verbatim from the spec (`docs/superpowers/specs/2026-08-01-falco-instance-chunk-design.md`):

- **NFR-001** — compile and run against the pinned Minestom version without reflection, `--add-opens` or an open module.
- **NFR-002** — only language and JDK features final in Java 25. No preview, no incubator.
- **NFR-003** — if a performance claim is published, a JMH or JOL measurement in this repository supports it, stated with its conditions.
- **NFR-005** — when a chunk read fails, the failure reaches the caller instead of being reported as an absent chunk.
- **NFR-006** — while a block is written, the lock held is the lock of the chunk it touches, not a monitor over the instance.
- **NFR-009** — every new public type carries `@ApiStatus.Experimental`.

**NFR-006 is not met on this stage, and that is the design.** §3 of the spec lists *"removing the instance monitor from a container that carries a shared instance"* as an explicit non-goal, and §4.4 gives the reason. NFR-006 is written unconditionally in §7 and the two statements are never reconciled in the spec; this plan reads NFR-006 as scoped to Falco's own instance and treats the shared path as the carve-out §3 already made. US-4.04 exists precisely so that the gap is written down instead of papered over. If the project owner reads it the other way, stage 4 cannot be built at all and this plan is void — settle that before Task 1.

Repository conventions, non-negotiable:

- **Source and Javadoc are English**, and Javadoc *justifies* decisions in `<p>` paragraphs and `<h2>` sections rather than restating the method name. Every type carries `@author TheMeinerLP`, `@version`, `@since 0.4.0`. Model: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoInstance.java`.
- **Markdown in the repository is English. Gradle files stay comment-free.**
- The package carries `@NotNullByDefault`. Anything that may be null needs an explicit `@Nullable`.
- **Minestom reference is the pinned sources jar**, unpacked at `/tmp/claude-1000/-mnt-projects-oss-onelitefeather-Falco/34edb948-9dfe-4540-9666-9e29f0d44d7b/scratchpad/minestom-src/`. The clone at `/mnt/projects/oss/minestom/Minestom` is ten months stale, produced eleven false findings in this project's research, and must not be opened.
- Work happens in the worktree `/mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance`, on branch `feat/shared-instance`. The worktrees `Falco-worktrees/block-storage` and `/mnt/projects/oss/onelitefeather/Falco` belong to other sessions and must not be touched.

Every Gradle invocation in this plan is written as

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance && ./gradlew …
```

## What was read in the sources jar before this plan was written

Every signature below was checked in `minestom-src`, not recalled. They are the load-bearing facts of the whole stage.

| Fact | Where | Consequence |
|---|---|---|
| `public class SharedInstance extends Instance`, no `final` on the class or on any of its 18 overrides | `SharedInstance.java:21` | every delegation is replaceable by a subclass |
| `public SharedInstance(UUID uuid, InstanceContainer instanceContainer)` — the only constructor | `SharedInstance.java:24` | the block owner **must** be an `InstanceContainer`; a `FalcoInstance` cannot be one |
| `areLinked` compares `((SharedInstance) x).getInstanceContainer().equals(y)`, never a concrete class | `SharedInstance.java:141-151` | a subclass keeps the fast path automatically |
| `areLinked` has exactly one caller: `Player#setInstance` | `Player.java:618` | losing it costs one full resend per instance change and nothing goes red |
| the fast path calls `spawnPlayer(instance, spawnPosition, false, false, false)` — `updateChunks = false` | `Player.java:620` | no `UpdateViewPositionPacket`, no `UnloadChunkPacket`; those two are the observable markers |
| `public SharedInstance registerSharedInstance(SharedInstance sharedInstance)` | `InstanceManager.java:82` | public and accepts any subclass — this is the registration path |
| `registerInstance` refuses a `SharedInstance` with `Check.stateCondition` | `InstanceManager.java:38` | registering the Falco type through `registerInstance` throws `IllegalStateException` |
| `createSharedInstance(InstanceContainer)` always builds `new SharedInstance(...)` | `InstanceManager.java:102` | Minestom's factory can never produce the Falco type; registration is by hand |
| `protected void addSharedInstance(SharedInstance)` on `InstanceContainer`, package `net.minestom.server.instance` | `InstanceContainer.java:578` | `InstanceManager` sits in that package and reaches it; Falco never needs to |
| `private synchronized void UNSAFE_setBlock(...)` with **four** call sites: `:135` (`setBlock`), `:223` (`placeBlock`), `:250` (`breakBlock`), `:756` (neighbour update) | `InstanceContainer.java:149` | overriding `setBlock` bypasses one and leaves three on the private synchronised path |
| `public ChunkLoader getChunkLoader()` on `InstanceContainer` | `InstanceContainer.java:684` | reachable, so `saveInstance` can be redirected without reflection |
| `AnvilLoader#saveInstance(Instance)` writes `instance.tagHandler().asCompound()` to `level.dat` | `anvil/AnvilLoader.java:332` | which `Instance` is handed in decides whose tags are persisted |
| `Chunk`'s constructor takes `instanceContainer.getSharedInstances()`, an unmodifiable **view** over a `CopyOnWriteArrayList`, and `EntityTrackerImpl.ChunkViewKey` compares it by identity | `Chunk.java:74`, `EntityTrackerImpl.java:207`, `:253` | shared instances registered after a chunk was loaded still become viewers — but each chunk construction mints a new wrapper and therefore a new, never-removed cache entry (M14) |
| `Instance#viewDistance(int)` is public and settable per instance | `Instance.java:886` | the packet tests can shrink the view distance instead of driving 289 chunk loads |
| `Player#resetChunkQueue()` does not reset `chunkBatchLead`, and `maxChunkBatchLead` starts at 1 | `Player.java:814`, `:165` | after the first spawn no further `ChunkDataPacket` is emitted without a client reply — **`ChunkDataPacket` is not a usable marker for the slow path**, `UnloadChunkPacket` and `UpdateViewPositionPacket` are |

The last row is the trap this stage would otherwise have walked into: the obvious test — "count the chunk data packets" — would report zero on both paths and pass for the wrong reason. That is failure mode number seven of the six this project has already paid for.

## Why a subclass, when the research said no

`docs/research/shared-instances-and-batches.md` concluded that shared instances are "walled off by the compiler" and recommended a Falco-owned delegating `Instance` instead. That verdict is still correct **for the question it asked**, which was whether a `FalcoInstance` can be the block owner. It cannot: the only `SharedInstance` constructor demands an `InstanceContainer`, and the research measured the consequence of building around it — `areLinked(foreign, falcoSharedView) = false`.

Stage 4 asks a different question and accepts the container. The block owner is a plain `InstanceContainer` whose chunk supplier is `FalcoChunk::new`, which is exactly what US-1.05 bought in stage 1 and what `FalcoChunkInContainerTest` already proves. With that concession the compiler wall is gone, `areLinked` is true, and the 765 ms resend never happens. What is given up is the write path: the container's monitor comes with the container. That trade is the whole of §4.4 and the whole of US-4.04.

## The wall

`InstanceContainer#UNSAFE_setBlock` is `private synchronized` with four call sites. `setBlock` is one of them; `placeBlock`, `breakBlock` and the neighbour-update loop are the other three, and all three call it directly on the private path. Overriding `setBlock` on `FalcoSharedInstance` would therefore produce two write paths over the same chunk data, one holding the container monitor and one not. That is a race of our own making, it would be introduced by the class that claims to fix concurrency, and it is worth less than the chunk-level gains stages 1 and 2 delivered.

**Shared worlds pay the monitor and keep the chunk gains. A world that needs write throughput uses `FalcoInstance` without sharing.** US-4.04 requires that sentence to exist in the documentation rather than in a commit message, which is Task 7.

## What "per-instance state" can and cannot mean here

Three of the four repairs give the shared instance a field of its own. Only one of the three has a consumer inside Minestom, and the plan says which:

| Repair | After the fix | Who reads it |
|---|---|---|
| `setGenerator` / `generator()` | own field, seeded from the container at construction | **nobody inside Minestom.** Chunks are created by the container, which asks its own `generator()`. Configuring world generation stays `getInstanceContainer().setGenerator(…)`. |
| `setChunkSupplier` / `getChunkSupplier()` | own field, seeded from the container at construction | **nobody inside Minestom**, for the same reason. `AnvilLoader#loadMCA` calls `instance.getChunkSupplier()` on the instance it was handed, which is always the container. |
| `enableAutoChunkLoad` / `hasEnabledAutoChunkLoad()` | own field, seeded from the container at construction | **`loadOptionalChunk`, overridden in Task 5** — the one place where the flag can be given real per-instance behaviour without touching the container. |
| `saveInstance()` | the container's `ChunkLoader`, handed `this` instead of the container | `AnvilLoader#saveInstance` writes this instance's tags |

The first two are repairs of a defect, not features. Before the fix, `sharedA.setGenerator(g)` silently reconfigured `sharedB` and the container; after it, the call stores a value that only Falco code reads back. That is strictly better and still not much, and the Javadoc has to say so in both directions — otherwise the next reader assumes a shared instance can generate its own world. This is written down in Task 7, not left implicit.

`setBlock` remains the container's, so `shared.enableAutoChunkLoad(false)` does **not** stop `shared.setBlock(…)` from auto-loading a chunk: that call lands in `InstanceContainer#setBlock`, which asks the container's flag. That residual is a direct consequence of the wall and is pinned by a test in Task 7 rather than left to be discovered.

## File Structure

| File | Responsibility |
|---|---|
| `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoSharedInstance.java` | **Create.** The whole of the production change: a `SharedInstance` subclass with four repaired methods and one overridden `loadOptionalChunk`. |
| `falco-instance/src/main/java/net/onelitefeather/falco/instance/package-info.java` | **Modify.** One paragraph naming the new type and the wall. |
| `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceTest.java` | **Create.** The type, its registration, and `areLinked` in all four directions (US-4.01). |
| `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceResendTest.java` | **Create.** The fast path proven on the wire, with an unlinked negative control (US-4.01). |
| `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceStateTest.java` | **Create.** Generator, chunk supplier and auto chunk load, each with a two-instance aliasing case (US-4.02). |
| `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceSaveTest.java` | **Create.** `saveInstance` writes this instance's tags, proven with a recording `ChunkLoader` (US-4.03). |
| `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceWriteTest.java` | **Create.** The wall, pinned: writes land on the container, siblings see them, the per-instance flag does not reach the write path (US-4.04). |
| `README.md` | **Modify.** The `Using falco-instance` paragraph currently says shared worlds stay with `InstanceContainer`; after this stage that is half wrong and the other half needs saying differently. |
| `docs/research/shared-instances-and-batches.md` | **Modify.** Add a "How it was actually built" subsection, in the same shape the batch section of that document already uses. |
| `docs/superpowers/plans/2026-08-02-falco-shared-instance.md` | **Modify.** This file gains a `## Stage 4 result` in Task 8. |

Nothing in `falco-benchmarks` changes. There is no footprint claim to make on this stage and no citable timing figure to produce: M16 is a scouting number from a machine at load 4.4–7.0 and may not be quoted as a result. The acceptance for US-4.01 is a packet count of zero, not a millisecond figure.

---

### Task 1: `FalcoSharedInstance`, and the fast path it exists to keep

**Files:**
- Create: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoSharedInstance.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceTest.java`

**Interfaces:**
- Consumes: `net.minestom.server.instance.SharedInstance(UUID, InstanceContainer)`, `net.minestom.server.instance.InstanceManager#registerSharedInstance(SharedInstance)`, `net.minestom.server.instance.SharedInstance#areLinked(Instance, Instance)`.
- Produces: `public class FalcoSharedInstance extends SharedInstance` with `public FalcoSharedInstance(UUID uuid, InstanceContainer instanceContainer)`. Tasks 2 to 7 extend exactly this class; no other constructor is added on this stage.

- [ ] **Step 1: Write the failing test**

Create `FalcoSharedInstanceTest.java`:

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.SharedInstance;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that a Falco shared instance is registrable and that Minestom still recognises it as sharing
 * the chunks of its container.
 * <p>
 * The second half is the one that matters. {@code areLinked} is consulted in exactly one place,
 * {@code Player#setInstance}, and when it answers false the player receives every chunk in view
 * distance again. Nothing fails, nothing logs, the world simply costs a full resend per instance
 * change. A test is the only thing that notices.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("A Falco shared instance")
class FalcoSharedInstanceTest {

    private static FalcoSharedInstance registered(Env env, InstanceContainer container) {
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        return shared;
    }

    @Test
    @DisplayName("registers through registerSharedInstance and is known to its container")
    void testRegistration(Env env) {
        final InstanceManager manager = env.process().instance();
        final InstanceContainer container = manager.createInstanceContainer();

        final FalcoSharedInstance shared = registered(env, container);

        assertTrue(shared.isRegistered());
        assertTrue(manager.getInstances().contains(shared));
        assertSame(shared, manager.getInstance(shared.getUuid()));
        assertSame(container, shared.getInstanceContainer());
        assertTrue(container.getSharedInstances().contains(shared),
                "the container has to know the view, or its chunks never take the view's players as viewers");
    }

    @Test
    @DisplayName("is refused by registerInstance, which is why registerSharedInstance exists")
    void testPlainRegistrationIsRefused(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);

        assertThrows(IllegalStateException.class, () -> env.process().instance().registerInstance(shared));
        assertFalse(shared.isRegistered());
    }

    @Test
    @DisplayName("cannot come from createSharedInstance, which always builds the stock type")
    void testTheFactoryOfMinestomBuildsTheStockType(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();

        final SharedInstance stock = env.process().instance().createSharedInstance(container);

        assertSame(SharedInstance.class, stock.getClass(),
                "if this ever changes, the hand registration in the README can go");
    }

    @Test
    @DisplayName("counts as linked to its container in both argument orders")
    void testLinkedToItsContainer(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance shared = registered(env, container);

        assertTrue(SharedInstance.areLinked(container, shared));
        assertTrue(SharedInstance.areLinked(shared, container));
    }

    @Test
    @DisplayName("counts as linked to a sibling view of the same container")
    void testLinkedToASibling(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance first = registered(env, container);
        final FalcoSharedInstance second = registered(env, container);

        assertTrue(SharedInstance.areLinked(first, second));
        assertTrue(SharedInstance.areLinked(second, first));
    }

    @Test
    @DisplayName("counts as linked to a stock shared instance over the same container")
    void testLinkedToAStockSharedInstance(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance falco = registered(env, container);
        final SharedInstance stock = env.process().instance().createSharedInstance(container);

        assertTrue(SharedInstance.areLinked(falco, stock));
    }

    @Test
    @DisplayName("counts as unlinked to a view of a different container")
    void testUnlinkedAcrossContainers(Env env) {
        final InstanceManager manager = env.process().instance();
        final InstanceContainer first = manager.createInstanceContainer();
        final InstanceContainer second = manager.createInstanceContainer();
        final FalcoSharedInstance sharedOnFirst = registered(env, first);
        final FalcoSharedInstance sharedOnSecond = registered(env, second);

        assertFalse(SharedInstance.areLinked(sharedOnFirst, sharedOnSecond));
        assertFalse(SharedInstance.areLinked(sharedOnFirst, second));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
./gradlew :falco-instance:test --tests "*FalcoSharedInstanceTest*"
```

Expected: compilation failure — `FalcoSharedInstance` does not exist.

- [ ] **Step 3: Write the class**

Create `FalcoSharedInstance.java`. The class Javadoc explains the choice of a subclass; Task 7 appends the two `<h2>` sections about the wall and the residual limits.

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.SharedInstance;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.UUID;

/**
 * A {@link SharedInstance} which keeps its own configuration instead of writing it through to the
 * container it borrows its chunks from.
 * <p>
 * This is a subclass of {@link SharedInstance} rather than a type of its own, and the reason is a
 * single static method. {@code SharedInstance#areLinked} decides whether a player who moves between
 * two instances keeps the chunks it already has or receives all of them again, and it is consulted
 * in exactly one place: {@code Player#setInstance}. It compares
 * {@link SharedInstance#getInstanceContainer()} rather than testing for a concrete class, so a
 * subclass inherits the answer. A separate type with the same behaviour would answer false, nothing
 * would fail, nothing would be logged, and every instance change would silently cost a full resend
 * of the view distance.
 * </p>
 * <p>
 * The price of the subclass is that the block owner has to be an {@link InstanceContainer}: that is
 * the only constructor {@link SharedInstance} has. A world used this way therefore sets its chunk
 * supplier to {@code FalcoChunk::new} on the container and keeps everything stages one and two
 * bought at the chunk, while the container keeps its own write path.
 * </p>
 * <p>
 * Registration goes through {@code InstanceManager#registerSharedInstance}. Its sibling
 * {@code createSharedInstance} always constructs the stock type and can never produce this one, and
 * {@code registerInstance} refuses anything that is a {@link SharedInstance} outright.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public class FalcoSharedInstance extends SharedInstance {

    /**
     * Creates a view over the chunks of a container.
     *
     * @param uuid              the identity of this instance
     * @param instanceContainer the container which owns the chunks this instance shows
     */
    public FalcoSharedInstance(UUID uuid, InstanceContainer instanceContainer) {
        super(uuid, Objects.requireNonNull(instanceContainer, "a shared instance needs a container to share"));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
./gradlew :falco-instance:test --tests "*FalcoSharedInstanceTest*"
```

Expected: seven tests, all PASS.

- [ ] **Step 5: Prove the test bites, by mutation**

The `areLinked` cases would stay green against a class that had lost the link, if they were written carelessly. Prove they are not. Add this to `FalcoSharedInstance`, temporarily:

```java
    // TEMPORARY MUTATION — revert after the run
    @Override
    public InstanceContainer getInstanceContainer() {
        return new InstanceContainer(UUID.randomUUID(), getDimensionType());
    }
```

Re-run the same command. Expected: `testLinkedToItsContainer`, `testLinkedToASibling`, `testLinkedToAStockSharedInstance` and `testRegistration` all FAIL — the last one because `registerSharedInstance` reads the getter and would attach the view to a container nobody holds. Then **delete the mutation** and re-run to confirm green again. Do not commit with the mutation in place.

- [ ] **Step 6: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoSharedInstance.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceTest.java
git commit -m "feat(instance): add FalcoSharedInstance, a shared instance Minestom still recognises"
```

---

### Task 2: The resend fast path, proven on the wire

**Files:**
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceResendTest.java`
- Modify: nothing. This task adds no production code on purpose.

**Interfaces:**
- Consumes: `FalcoSharedInstance` from Task 1, `net.minestom.testing.TestConnection#trackIncoming(Class)`, `net.minestom.server.entity.Player#setInstance(Instance, Pos)`.
- Produces: nothing. It produces evidence.

Task 1 proved `areLinked` answers true. That is the mechanism, not the outcome. US-4.01 is about what the player's connection receives, and the two are only the same as long as `Player#setInstance` keeps its shape. This task asserts the outcome directly, so that an upgrade which changes the shape is caught here rather than in production.

**Read this before writing the assertion.** The intuitive marker — `ChunkDataPacket` — does not work. `Player#resetChunkQueue()` does not reset `chunkBatchLead`, and `maxChunkBatchLead` starts at 1, so after the first spawn no further chunk batch is emitted until the client replies with a batch acknowledgement, which a `TestConnection` never does. A count of zero `ChunkDataPacket` would therefore be green on both paths and prove nothing. The markers that are emitted unconditionally on the slow path are `UpdateViewPositionPacket` (once) and `UnloadChunkPacket` (one per chunk in the old view), both from `Player#spawnPlayer` under `updateChunks == true`.

- [ ] **Step 1: Write the test**

Create `FalcoSharedInstanceResendTest.java`:

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.network.packet.server.play.ChunkDataPacket;
import net.minestom.server.network.packet.server.play.UnloadChunkPacket;
import net.minestom.server.network.packet.server.play.UpdateViewPositionPacket;
import net.minestom.testing.Collector;
import net.minestom.testing.Env;
import net.minestom.testing.TestConnection;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Asserts what US-4.01 actually asks for: a player moving into a shared instance receives no chunk
 * traffic at all.
 * <p>
 * {@code areLinked} is the mechanism and is covered next door; this class covers the outcome, so
 * that a change to {@code Player#setInstance} is caught here instead of costing a full resend per
 * transfer in production. The markers are {@code UpdateViewPositionPacket} and
 * {@code UnloadChunkPacket}, both sent unconditionally by the slow path.
 * {@code ChunkDataPacket} is asserted as well but carries no weight on its own: after the first
 * spawn Minestom holds the chunk queue until the client acknowledges a batch, which a test
 * connection never does, so that counter reads zero on both paths.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("A player moving into a Falco shared instance")
class FalcoSharedInstanceResendTest {

    private static final Pos SPAWN = new Pos(0.5, 40, 0.5);

    private static InstanceContainer container(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        // Keeps the transfer at 25 chunks instead of 289; the paths under test do not depend on it.
        container.viewDistance(1);
        return container;
    }

    @Test
    @DisplayName("receives no view update and no chunk unload, because the chunks are the same")
    void testTheFastPathSendsNothing(Env env) {
        final InstanceContainer container = container(env);
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        shared.viewDistance(1);

        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(container, SPAWN);

        final Collector<UpdateViewPositionPacket> views = connection.trackIncoming(UpdateViewPositionPacket.class);
        final Collector<UnloadChunkPacket> unloads = connection.trackIncoming(UnloadChunkPacket.class);
        final Collector<ChunkDataPacket> chunks = connection.trackIncoming(ChunkDataPacket.class);

        player.setInstance(shared, SPAWN).join();

        assertSame(shared, player.getInstance());
        views.assertEmpty();
        unloads.assertEmpty();
        chunks.assertEmpty();
    }

    @Test
    @DisplayName("receives the full treatment when the target does not share the chunks")
    void testTheSlowPathSendsTheMarkers(Env env) {
        final InstanceContainer container = container(env);
        final InstanceContainer unrelated = container(env);

        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(container, SPAWN);

        final Collector<UpdateViewPositionPacket> views = connection.trackIncoming(UpdateViewPositionPacket.class);
        final Collector<UnloadChunkPacket> unloads = connection.trackIncoming(UnloadChunkPacket.class);

        player.setInstance(unrelated, SPAWN).join();

        assertSame(unrelated, player.getInstance());
        views.assertCount(1);
        assertFalse(unloads.collect().isEmpty(),
                "the slow path unloads the old view chunk by chunk; if this is empty the markers are wrong, "
                        + "not the fast path");
    }

    @Test
    @DisplayName("takes the slow path when it lands in a different chunk, linked or not")
    void testTheFastPathNeedsTheSameChunk(Env env) {
        final InstanceContainer container = container(env);
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        shared.viewDistance(1);

        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(container, SPAWN);

        final Collector<UpdateViewPositionPacket> views = connection.trackIncoming(UpdateViewPositionPacket.class);

        player.setInstance(shared, new Pos(500.5, 40, 500.5)).join();

        views.assertCount(1);
    }
}
```

The second test is the control that keeps the first honest: it uses the same connection, the same collectors and the same call, and differs only in whether the target shares the chunks. Without it, "zero packets" could mean "the tracker is attached to nothing". The third pins the other half of the condition in `Player#setInstance` — `spawnPosition.sameChunk(this.position)` — so that nobody later reads the first test as "a Falco shared instance never resends".

- [ ] **Step 2: Run the test**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
./gradlew :falco-instance:test --tests "*FalcoSharedInstanceResendTest*"
```

Expected: three tests, all PASS. This is a characterisation test over code that already works; there is no red-first step to fake. The bite is proven in step 3 instead, and that step is not optional.

- [ ] **Step 3: Prove the test bites, by mutation**

Add this to `FalcoSharedInstance`, temporarily:

```java
    // TEMPORARY MUTATION — revert after the run
    @Override
    public InstanceContainer getInstanceContainer() {
        return new InstanceContainer(UUID.randomUUID(), getDimensionType());
    }
```

Re-run. Expected: `testTheFastPathSendsNothing` FAILS on `views.assertEmpty()` — the link is gone, `Player#setInstance` takes the slow path and sends the view update. `testTheSlowPathSendsTheMarkers` stays green, which is what tells you the collectors work and the first test failed for the right reason. **Delete the mutation** and re-run to confirm green.

- [ ] **Step 4: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
git add falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceResendTest.java
git commit -m "test(instance): pin that a move into a shared instance sends no chunk traffic"
```

---

### Task 3: A generator of its own

> **Decided by the owner, after the open question was put to them.** The setter **stores per instance
> and the getter returns what was stored** — the aliasing is repaired. The alternative, throwing and
> pointing at the container, was considered and rejected.
>
> The consequence must be written where a caller will see it, because this is a value with no reader:
> chunks are created by the container, which asks *its* generator, so a generator set on a view never
> generates anything. The javadoc of `setGenerator` and `getGenerator` has to say both halves in plain
> words — what it fixes (two views no longer overwrite each other's setting) and what it does not do
> (it does not make this view generate). A setter that silently has no effect is precisely the kind of
> trap this project spends its reviews hunting. Stating it is the whole difference between a
> documented limitation and a defect.
>
> The same applies to `setChunkSupplier` in Task 4.

**Files:**
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoSharedInstance.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceStateTest.java`

**Interfaces:**
- Consumes: `net.minestom.server.instance.generator.Generator`.
- Produces: `public @Nullable Generator generator()` and `public void setGenerator(@Nullable Generator generator)` on `FalcoSharedInstance`, both overriding `SharedInstance`. Tasks 4 and 5 add fields to the same class and the same test file.

- [ ] **Step 1: Write the failing test**

Create `FalcoSharedInstanceStateTest.java` with the generator cases. Tasks 4 and 5 append to this file.

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.Generator;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers the three pieces of configuration which Minestom's shared instance writes through to the
 * container it borrows from.
 * <p>
 * Every case here uses <em>two</em> shared instances over one container and inspects the one which
 * was not touched. A case that only looked at the instance it had just configured would be green
 * with the defect in place, because the defect is not that the value is lost — it is that the value
 * lands somewhere else as well.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The configuration of a Falco shared instance")
class FalcoSharedInstanceStateTest {

    private static FalcoSharedInstance registered(Env env, InstanceContainer container) {
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        return shared;
    }

    @Test
    @DisplayName("starts with the generator its container had")
    void testTheGeneratorIsSeededFromTheContainer(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final Generator generator = unit -> unit.modifier().fill(Block.STONE);
        container.setGenerator(generator);

        final FalcoSharedInstance shared = registered(env, container);

        assertSame(generator, shared.generator());
    }

    @Test
    @DisplayName("keeps a generator to itself: neither the sibling nor the container sees it")
    void testTheGeneratorDoesNotAlias(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance first = registered(env, container);
        final FalcoSharedInstance second = registered(env, container);
        final Generator generator = unit -> unit.modifier().fill(Block.STONE);

        first.setGenerator(generator);

        assertSame(generator, first.generator());
        assertNull(second.generator(), "a sibling view must not be reconfigured by this call");
        assertNull(container.generator(), "the container must not be reconfigured by this call");
    }

    @Test
    @DisplayName("does not lose the container's generator when it clears its own")
    void testClearingTheGeneratorDoesNotClearTheContainer(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final Generator generator = unit -> unit.modifier().fill(Block.STONE);
        container.setGenerator(generator);
        final FalcoSharedInstance shared = registered(env, container);

        shared.setGenerator(null);

        assertNull(shared.generator());
        assertSame(generator, container.generator(),
                "clearing a view must not empty the world it looks at");
    }
}
```

The third case is the one that would hurt in production: with the defect, `shared.setGenerator(null)` empties the world for everyone.

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
./gradlew :falco-instance:test --tests "*FalcoSharedInstanceStateTest*"
```

Expected: `testTheGeneratorIsSeededFromTheContainer` PASSES (the inherited delegation happens to answer correctly), the other two FAIL — `testTheGeneratorDoesNotAlias` because `second.generator()` and `container.generator()` both return the generator, `testClearingTheGeneratorDoesNotClearTheContainer` because the container's generator is gone. That split is worth noticing: the first case cannot distinguish the fix from the defect, and it is kept anyway because it pins the seeding that tasks 4 and 5 repeat.

- [ ] **Step 3: Implement**

Add the field, seed it in the constructor and override both methods. The constructor becomes:

```java
    /**
     * The generator of this instance, which is deliberately not the generator of the container.
     * <p>
     * Volatile because a shared instance is configured from wherever the world is set up and read
     * from wherever it is asked, and those are not the same thread.
     * </p>
     */
    private volatile @Nullable Generator generator;

    /**
     * Creates a view over the chunks of a container.
     * <p>
     * The configuration of the container is copied once, here. That is what makes a fresh view
     * behave like the world it looks at while still being able to diverge from it — the alternative,
     * starting empty, would answer {@code null} to {@link #generator()} on a world that has one.
     * </p>
     *
     * @param uuid              the identity of this instance
     * @param instanceContainer the container which owns the chunks this instance shows
     */
    public FalcoSharedInstance(UUID uuid, InstanceContainer instanceContainer) {
        super(uuid, Objects.requireNonNull(instanceContainer, "a shared instance needs a container to share"));
        this.generator = instanceContainer.generator();
    }

    /**
     * Gets the generator of this instance.
     *
     * @return the generator of this instance, null if it has none
     */
    @Override
    public @Nullable Generator generator() {
        return this.generator;
    }

    /**
     * Sets the generator of this instance, and of nothing else.
     * <p>
     * Minestom's shared instance forwards this call to its container, which means that configuring
     * one view reconfigures the world and every other view of it. That is the defect this class
     * exists to repair, and repairing it has a consequence worth stating: no chunk is generated from
     * this value. Chunks are created by the container, and the container asks its own generator. Use
     * {@code getInstanceContainer().setGenerator(…)} to decide what the world is made of.
     * </p>
     *
     * @param generator the generator of this instance, null to have none
     */
    @Override
    public void setGenerator(@Nullable Generator generator) {
        this.generator = generator;
    }
```

New imports on the class: `net.minestom.server.instance.generator.Generator`, `org.jetbrains.annotations.Nullable`.

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
./gradlew :falco-instance:test --tests "*FalcoSharedInstanceStateTest*"
```

Expected: three tests, all PASS.

- [ ] **Step 5: Prove the test bites, by mutation**

Two mutations, run one at a time and reverted each time. The pair of methods can be broken independently, and a test that caught only one of them would be a false witness for the other.

1. Replace the body of `setGenerator` with `super.setGenerator(generator);`, keeping `generator()` as it is. Expected: `testTheGeneratorDoesNotAlias` FAILS on `assertNull(container.generator())` — the write reached the container. `testClearingTheGeneratorDoesNotClearTheContainer` FAILS as well, on `assertSame(generator, container.generator())`.
2. Restore `setGenerator`, then delete the `generator()` override. Expected: `testTheGeneratorDoesNotAlias` FAILS on `assertSame(generator, first.generator())` — the reader now answers with the container's value, which the repaired setter no longer wrote. `testTheGeneratorIsSeededFromTheContainer` stays green, which is exactly why it is not evidence for this story.

Restore both overrides and confirm green.

- [ ] **Step 6: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoSharedInstance.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceStateTest.java
git commit -m "fix(instance): stop a shared instance from writing its generator into the container"
```

---

### Task 4: A chunk supplier of its own

**Files:**
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoSharedInstance.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceStateTest.java` (append)

**Interfaces:**
- Consumes: `net.minestom.server.utils.chunk.ChunkSupplier`.
- Produces: `public ChunkSupplier getChunkSupplier()` and `public void setChunkSupplier(ChunkSupplier chunkSupplier)` on `FalcoSharedInstance`.

- [ ] **Step 1: Write the failing test**

Append to `FalcoSharedInstanceStateTest.java` (and add the imports `net.minestom.server.utils.chunk.ChunkSupplier` and `static org.junit.jupiter.api.Assertions.assertNotSame`):

```java
    @Test
    @DisplayName("starts with the chunk supplier its container had")
    void testTheChunkSupplierIsSeededFromTheContainer(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();

        final FalcoSharedInstance shared = registered(env, container);

        assertSame(container.getChunkSupplier(), shared.getChunkSupplier());
    }

    @Test
    @DisplayName("keeps a chunk supplier to itself: neither the sibling nor the container sees it")
    void testTheChunkSupplierDoesNotAlias(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final ChunkSupplier stock = container.getChunkSupplier();
        final FalcoSharedInstance first = registered(env, container);
        final FalcoSharedInstance second = registered(env, container);
        final ChunkSupplier supplier = FalcoChunk::new;

        first.setChunkSupplier(supplier);

        assertSame(supplier, first.getChunkSupplier());
        assertSame(stock, second.getChunkSupplier(), "a sibling view must not be reconfigured by this call");
        assertSame(stock, container.getChunkSupplier(), "the container must not be reconfigured by this call");
        assertNotSame(supplier, container.getChunkSupplier());
    }

    @Test
    @DisplayName("refuses a null chunk supplier instead of storing it")
    void testTheChunkSupplierIsNotNullable(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance shared = registered(env, container);

        assertThrows(NullPointerException.class, () -> shared.setChunkSupplier(null));
        assertSame(container.getChunkSupplier(), shared.getChunkSupplier());
    }
```

Add `static org.junit.jupiter.api.Assertions.assertThrows` to the imports as well.

The second case is the sharpest of the three: `assertSame(stock, container.getChunkSupplier())` fails the moment the setter writes through, and it names the value it expected rather than merely asserting "not the new one".

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
./gradlew :falco-instance:test --tests "*FalcoSharedInstanceStateTest*"
```

Expected: `testTheChunkSupplierIsSeededFromTheContainer` PASSES through the inherited delegation, the other two FAIL.

- [ ] **Step 3: Implement**

Add to `FalcoSharedInstance`:

```java
    /**
     * The chunk supplier of this instance, which is deliberately not the one of the container.
     */
    private volatile ChunkSupplier chunkSupplier;
```

Seed it in the constructor, next to the generator:

```java
        this.chunkSupplier = instanceContainer.getChunkSupplier();
```

And the two methods:

```java
    /**
     * Gets the chunk supplier of this instance.
     *
     * @return the chunk supplier of this instance
     */
    @Override
    public ChunkSupplier getChunkSupplier() {
        return this.chunkSupplier;
    }

    /**
     * Sets the chunk supplier of this instance, and of nothing else.
     * <p>
     * Minestom's shared instance forwards this call to its container, so configuring one view
     * changes what type of chunk the whole world is made of. That is repaired here, with the same
     * consequence the generator has: no chunk is created from this value, because chunks are created
     * by the container and the container asks its own supplier — as does a chunk loader, which is
     * handed the container rather than the view. Use
     * {@code getInstanceContainer().setChunkSupplier(FalcoChunk::new)} to decide what the world is
     * built from.
     * </p>
     *
     * @param chunkSupplier the chunk supplier of this instance
     * @throws NullPointerException if {@code chunkSupplier} is null
     */
    @Override
    public void setChunkSupplier(ChunkSupplier chunkSupplier) {
        this.chunkSupplier = Objects.requireNonNull(chunkSupplier, "the chunk supplier cannot be null");
    }
```

New import: `net.minestom.server.utils.chunk.ChunkSupplier`.

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
./gradlew :falco-instance:test --tests "*FalcoSharedInstanceStateTest*"
```

Expected: six tests, all PASS.

- [ ] **Step 5: Prove the test bites, by mutation**

Replace the body of `setChunkSupplier` with `super.setChunkSupplier(chunkSupplier);` — the exact defect, restored. Re-run. Expected: `testTheChunkSupplierDoesNotAlias` FAILS on `assertSame(stock, container.getChunkSupplier())` and `testTheChunkSupplierIsNotNullable` FAILS because the container accepts what this class refuses. Restore the body and confirm green.

- [ ] **Step 6: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoSharedInstance.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceStateTest.java
git commit -m "fix(instance): stop a shared instance from writing its chunk supplier into the container"
```

---

### Task 5: Auto chunk load of its own, and the one place it can act

**Files:**
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoSharedInstance.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceStateTest.java` (append)

**Interfaces:**
- Consumes: `InstanceContainer#getChunk(int, int)`, `InstanceContainer#loadChunk(int, int)`.
- Produces: `public void enableAutoChunkLoad(boolean enable)`, `public boolean hasEnabledAutoChunkLoad()` and `public CompletableFuture<@Nullable Chunk> loadOptionalChunk(int chunkX, int chunkZ)` on `FalcoSharedInstance`.

This is the only one of the three flags that can be given behaviour rather than merely storage. `loadOptionalChunk` is a method of the view, so the view may answer it: hand back a chunk the container already holds, refuse to trigger a load when this view has auto load disabled, and otherwise delegate. It is also the method `Player#chunkAdder` calls, so the flag reaches something a player can observe.

- [ ] **Step 1: Write the failing test**

Append to `FalcoSharedInstanceStateTest.java` (imports: `net.minestom.server.instance.Chunk`, and the assertions `assertFalse`, `assertTrue`, `assertNotNull`):

```java
    @Test
    @DisplayName("starts with the auto chunk load setting its container had")
    void testAutoChunkLoadIsSeededFromTheContainer(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        container.enableAutoChunkLoad(false);

        final FalcoSharedInstance shared = registered(env, container);

        assertFalse(shared.hasEnabledAutoChunkLoad());
    }

    @Test
    @DisplayName("keeps the auto chunk load flag to itself")
    void testAutoChunkLoadDoesNotAlias(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance first = registered(env, container);
        final FalcoSharedInstance second = registered(env, container);

        first.enableAutoChunkLoad(false);

        assertFalse(first.hasEnabledAutoChunkLoad());
        assertTrue(second.hasEnabledAutoChunkLoad(), "a sibling view must not be reconfigured by this call");
        assertTrue(container.hasEnabledAutoChunkLoad(), "the container must not be reconfigured by this call");
    }

    @Test
    @DisplayName("does not trigger a load of its own when the flag is off, and the container still can")
    void testAutoChunkLoadDecidesTheOptionalLoad(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance disabled = registered(env, container);
        final FalcoSharedInstance enabled = registered(env, container);
        disabled.enableAutoChunkLoad(false);

        assertNull(disabled.loadOptionalChunk(4, 4).join(),
                "a view with auto load off must not pull a chunk into the world");
        assertNull(container.getChunk(4, 4), "and it must not have done so as a side effect either");

        final Chunk loaded = enabled.loadOptionalChunk(4, 4).join();

        assertNotNull(loaded);
        assertSame(loaded, container.getChunk(4, 4));
    }

    @Test
    @DisplayName("hands back a chunk that is already there even with the flag off")
    void testAutoChunkLoadDoesNotHideLoadedChunks(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance shared = registered(env, container);
        shared.enableAutoChunkLoad(false);
        final Chunk loaded = container.loadChunk(4, 4).join();

        assertSame(loaded, shared.loadOptionalChunk(4, 4).join(),
                "the flag governs whether a load is started, not whether the world is visible");
    }
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
./gradlew :falco-instance:test --tests "*FalcoSharedInstanceStateTest*"
```

Expected: `testAutoChunkLoadIsSeededFromTheContainer` and `testAutoChunkLoadDoesNotHideLoadedChunks` PASS through the inherited delegation; `testAutoChunkLoadDoesNotAlias` FAILS on the sibling and the container, and `testAutoChunkLoadDecidesTheOptionalLoad` FAILS because the delegation asks the container's flag, which is on.

- [ ] **Step 3: Implement**

Add to `FalcoSharedInstance`:

```java
    /**
     * Whether this instance pulls chunks into the world when it is asked for one it has not got.
     */
    private volatile boolean autoChunkLoad;
```

Seed it in the constructor:

```java
        this.autoChunkLoad = instanceContainer.hasEnabledAutoChunkLoad();
```

And the three methods:

```java
    /**
     * Decides whether this instance pulls chunks into the world on demand.
     * <p>
     * Minestom's shared instance forwards this to its container, which turns a per-view decision
     * into a per-world one. Here it stays with the view, and it reaches exactly one method:
     * {@link #loadOptionalChunk(int, int)}. It does <em>not</em> reach {@code setBlock} — that call
     * belongs to the container and asks the container's flag, for the reason given in the class
     * documentation.
     * </p>
     *
     * @param enable true to pull chunks in on demand
     */
    @Override
    public void enableAutoChunkLoad(boolean enable) {
        this.autoChunkLoad = enable;
    }

    /**
     * Gets whether this instance pulls chunks into the world on demand.
     *
     * @return true if it does
     */
    @Override
    public boolean hasEnabledAutoChunkLoad() {
        return this.autoChunkLoad;
    }

    /**
     * Hands back the chunk at a position, loading it only if this instance is allowed to.
     * <p>
     * A chunk the container already holds is handed back whatever the flag says: the flag decides
     * whether this view may cause a load, not whether it may see the world. Only the second branch
     * consults it, and only that branch is a decision this instance is entitled to make — the chunk
     * itself is still created, cached and published by the container.
     * </p>
     *
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @return a future completed with the chunk, or with null if it is absent and may not be loaded
     */
    @Override
    public CompletableFuture<@Nullable Chunk> loadOptionalChunk(int chunkX, int chunkZ) {
        final InstanceContainer container = getInstanceContainer();
        final Chunk loaded = container.getChunk(chunkX, chunkZ);
        if (loaded != null) return CompletableFuture.completedFuture(loaded);
        if (!this.autoChunkLoad) return CompletableFuture.completedFuture(null);
        return container.loadChunk(chunkX, chunkZ);
    }
```

New imports: `net.minestom.server.instance.Chunk`, `java.util.concurrent.CompletableFuture`.

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
./gradlew :falco-instance:test --tests "*FalcoSharedInstanceStateTest*"
```

Expected: ten tests, all PASS.

- [ ] **Step 5: Prove the test bites, by mutation**

Two mutations, run one at a time and reverted each time:

1. Replace the body of `enableAutoChunkLoad` with `super.enableAutoChunkLoad(enable);`. Expected: `testAutoChunkLoadDoesNotAlias` FAILS on the sibling and on the container.
2. Delete the `loadOptionalChunk` override. Expected: `testAutoChunkLoadDecidesTheOptionalLoad` FAILS on its first assertion — the container's flag is on, so a chunk arrives where none should have.

Confirm green after each revert.

- [ ] **Step 6: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoSharedInstance.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceStateTest.java
git commit -m "fix(instance): give a shared instance its own auto chunk load decision"
```

---

### Task 6: `saveInstance` writes this instance's tags

**Files:**
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoSharedInstance.java`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceSaveTest.java`

**Interfaces:**
- Consumes: `InstanceContainer#getChunkLoader()`, `ChunkLoader#saveInstance(Instance)`, `ChunkLoader#supportsParallelSaving()`.
- Produces: `public CompletableFuture<Void> saveInstance()` on `FalcoSharedInstance`.

`SharedInstance#saveInstance` calls `instanceContainer.saveInstance()`, and `InstanceContainer#saveInstance` passes `this` to the loader. `AnvilLoader#saveInstance` writes `instance.tagHandler().asCompound()`. So today the tags of a shared instance are never written and no error says so — the container's are written twice instead. The repair is to reach the loader directly and hand it `this`.

- [ ] **Step 1: Write the failing test**

Create `FalcoSharedInstanceSaveTest.java`:

```java
package net.onelitefeather.falco.instance;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.tag.Tag;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins whose tags a shared instance writes when it is asked to save.
 * <p>
 * The defect this covers is silent by construction: Minestom's shared instance forwards the call to
 * its container, the container hands itself to the loader, the loader writes the container's tags,
 * and the operation reports success. Nothing is lost that anyone could notice at the time — the tags
 * of the view are simply never written. So the assertion has to be on the argument the loader
 * received, not on whether the call succeeded.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("Saving a Falco shared instance")
class FalcoSharedInstanceSaveTest {

    private static final Tag<String> OWNER = Tag.String("owner");

    @Test
    @DisplayName("hands the loader this instance, with this instance's tags")
    void testTheViewSavesItsOwnTags(Env env) {
        final RecordingChunkLoader loader = new RecordingChunkLoader();
        final InstanceContainer container = env.process().instance().createInstanceContainer(loader);
        container.setTag(OWNER, "container");
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        shared.setTag(OWNER, "shared");

        shared.saveInstance().join();

        assertEquals(1, loader.saved().size());
        assertSame(shared, loader.saved().getFirst());
        assertEquals("shared", loader.written().getFirst().getString("owner"));
    }

    @Test
    @DisplayName("leaves the container's own save alone")
    void testTheContainerStillSavesItself(Env env) {
        final RecordingChunkLoader loader = new RecordingChunkLoader();
        final InstanceContainer container = env.process().instance().createInstanceContainer(loader);
        container.setTag(OWNER, "container");
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        shared.setTag(OWNER, "shared");

        container.saveInstance().join();

        assertEquals(1, loader.saved().size());
        assertSame(container, loader.saved().getFirst());
        assertEquals("container", loader.written().getFirst().getString("owner"));
    }

    /**
     * A loader which records the instance it was asked to save and the tags that instance carried
     * at that moment.
     */
    private static final class RecordingChunkLoader implements ChunkLoader {

        private final List<Instance> saved = new CopyOnWriteArrayList<>();
        private final List<CompoundBinaryTag> written = new CopyOnWriteArrayList<>();

        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            return null;
        }

        @Override
        public void saveInstance(Instance instance) {
            this.saved.add(instance);
            this.written.add(instance.tagHandler().asCompound());
        }

        @Override
        public void saveChunk(Chunk chunk) {
        }

        private List<Instance> saved() {
            return this.saved;
        }

        private List<CompoundBinaryTag> written() {
            return this.written;
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
./gradlew :falco-instance:test --tests "*FalcoSharedInstanceSaveTest*"
```

Expected: `testTheViewSavesItsOwnTags` FAILS on `assertSame(shared, …)` — the loader was handed the container. `testTheContainerStillSavesItself` PASSES already and is there to make the first failure unambiguous.

- [ ] **Step 3: Implement**

Add to `FalcoSharedInstance`:

```java
    /**
     * Saves the data of this instance through the loader of its container.
     * <p>
     * Minestom's shared instance forwards this to {@code InstanceContainer#saveInstance()}, which
     * hands the loader the container. The tags of the view are therefore never written and the call
     * still reports success — the anvil loader writes {@code instance.tagHandler().asCompound()} of
     * whatever it was given. Reaching the loader directly and handing it {@code this} is the whole
     * of the repair.
     * </p>
     * <p>
     * What it does not repair: a loader writes to one place per world. An
     * {@code AnvilLoader} puts instance data in a single {@code level.dat}, so a container and every
     * view of it write over one another and the last save wins. Saving one view of a world is
     * therefore meaningful; saving several and expecting to read all of them back is not.
     * </p>
     *
     * @return a future completed once the data is written, completed exceptionally if it threw
     */
    @Override
    public CompletableFuture<Void> saveInstance() {
        final ChunkLoader loader = getInstanceContainer().getChunkLoader();
        if (!loader.supportsParallelSaving()) {
            try {
                loader.saveInstance(this);
                return CompletableFuture.completedFuture(null);
            } catch (Throwable throwable) {
                return CompletableFuture.failedFuture(throwable);
            }
        }
        final CompletableFuture<Void> future = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                loader.saveInstance(this);
                future.complete(null);
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }
```

New import: `net.minestom.server.instance.ChunkLoader`.

The failure handling mirrors `FalcoInstance#runSave` rather than `InstanceContainer#optionalAsync`: a failure is returned to the caller and not additionally pushed into the server's exception manager, because a failure that is both returned and reported is handled twice and logged twice. `saveChunkToStorage` and `saveChunksToStorage` keep the inherited delegation — those write chunks, and the chunks are the container's.

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
./gradlew :falco-instance:test --tests "*FalcoSharedInstanceSaveTest*"
```

Expected: two tests, both PASS.

- [ ] **Step 5: Prove the test bites, by mutation**

Replace the whole body of `saveInstance` with `return super.saveInstance();` — the defect, restored. Re-run. Expected: `testTheViewSavesItsOwnTags` FAILS on `assertSame(shared, loader.saved().getFirst())` and would also fail on the tag value. `testTheContainerStillSavesItself` stays green, which confirms the recording loader is wired up and the first failure is about the argument, not about the plumbing. Restore the body and confirm green.

- [ ] **Step 6: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoSharedInstance.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceSaveTest.java
git commit -m "fix(instance): save the tags of the shared instance instead of the container's"
```

---

### Task 7: The wall, written down and pinned

**Files:**
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoSharedInstance.java` (class Javadoc)
- Modify: `falco-instance/src/main/java/net/onelitefeather/falco/instance/package-info.java`
- Modify: `README.md`
- Modify: `docs/research/shared-instances-and-batches.md`
- Test: `falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceWriteTest.java`

**Interfaces:**
- Consumes: everything built in Tasks 1 to 6.
- Produces: no new API. It produces the documentation US-4.04 requires, and a test that keeps the documentation true.

US-4.04 asks for documentation, and documentation that nothing checks rots. So this task does both: it writes down that writes serialise on the container, and it pins the two observable consequences of that — the chunk is the container's, and the per-view auto-load flag does not reach the write path.

- [ ] **Step 1: Write the test**

Create `FalcoSharedInstanceWriteTest.java`:

```java
package net.onelitefeather.falco.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the wall of stage four: a Falco shared instance does not own its writes.
 * <p>
 * The block owner is the container, its {@code UNSAFE_setBlock} is private and synchronised on the
 * instance, and it is reached from four places of which {@code setBlock} is only one. Overriding
 * {@code setBlock} here would leave the other three on the private path and create two write paths
 * over one chunk, one of them unsynchronised. This class asserts the consequences of not doing that,
 * so that the documentation which states them cannot quietly stop being true.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("Writing through a Falco shared instance")
class FalcoSharedInstanceWriteTest {

    private static FalcoSharedInstance registered(Env env, InstanceContainer container) {
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        return shared;
    }

    @Test
    @DisplayName("shows the same chunk object as the container and as its siblings")
    void testTheChunkIsTheContainersChunk(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance first = registered(env, container);
        final FalcoSharedInstance second = registered(env, container);

        final Chunk chunk = container.loadChunk(0, 0).join();

        assertSame(chunk, first.getChunk(0, 0));
        assertSame(chunk, second.getChunk(0, 0));
    }

    @Test
    @DisplayName("lands in the container, where every other view reads it")
    void testAWriteReachesEveryView(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        container.setChunkSupplier(FalcoChunk::new);
        final FalcoSharedInstance first = registered(env, container);
        final FalcoSharedInstance second = registered(env, container);
        container.loadChunk(0, 0).join();

        first.setBlock(1, 40, 2, Block.DIAMOND_BLOCK);

        assertEquals(Block.DIAMOND_BLOCK, first.getBlock(1, 40, 2));
        assertEquals(Block.DIAMOND_BLOCK, second.getBlock(1, 40, 2));
        assertEquals(Block.DIAMOND_BLOCK, container.getBlock(1, 40, 2));
    }

    @Test
    @DisplayName("still auto-loads on write when the container does, whatever the view was told")
    void testTheViewFlagDoesNotReachTheWritePath(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        container.setChunkSupplier(FalcoChunk::new);
        final FalcoSharedInstance shared = registered(env, container);

        shared.enableAutoChunkLoad(false);
        shared.setBlock(20, 40, 20, Block.STONE);

        assertNotNull(container.getChunk(1, 1),
                "setBlock belongs to the container and asks the container's flag; this is the wall, not a defect");
        assertEquals(Block.STONE, container.getBlock(20, 40, 20));
    }
}
```

The third case asserts a limitation rather than a feature, and it is deliberate: it is the one place where a reader of the class could reasonably expect the per-view flag to act, and the answer has to be recorded rather than discovered.

- [ ] **Step 2: Run the test**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
./gradlew :falco-instance:test --tests "*FalcoSharedInstanceWriteTest*"
```

Expected: three tests, all PASS. Like Task 2, this characterises behaviour that already exists; the bite is proven in step 3.

- [ ] **Step 3: Prove the test bites, by mutation**

Add this to `FalcoSharedInstance`, temporarily:

```java
    // TEMPORARY MUTATION — revert after the run
    @Override
    public @Nullable Chunk getChunk(int chunkX, int chunkZ) {
        final Chunk chunk = getInstanceContainer().getChunk(chunkX, chunkZ);
        return chunk == null ? null : chunk.copy(this, chunkX, chunkZ);
    }
```

Re-run. Expected: `testTheChunkIsTheContainersChunk` FAILS on both `assertSame` calls, and `testAWriteReachesEveryView` FAILS because the reads now go through per-view copies. **Delete the mutation** and confirm green.

- [ ] **Step 4: Extend the class Javadoc**

Append two `<h2>` sections to the Javadoc of `FalcoSharedInstance`, after the existing paragraphs and before the tags:

```java
 * <h2>Why writes still serialise on the container</h2>
 * <p>
 * They have to, and the reason is a {@code private} modifier in a foreign class.
 * {@code InstanceContainer#UNSAFE_setBlock} is {@code private synchronized} and is called from four
 * places: {@code setBlock}, {@code placeBlock}, {@code breakBlock} and the neighbour update that
 * runs a block placement rule. Overriding {@code setBlock} here would take over one of the four and
 * leave the other three on the private, synchronised path — two write paths over the same chunk
 * data, one holding the monitor of the whole instance and one not. That is a race introduced by the
 * class which claims to remove one, and it is worth less than what it would buy.
 * </p>
 * <p>
 * A shared world therefore pays the container's monitor on every block write and keeps everything
 * the chunk layer gained. A world that needs write throughput and does not need to be shared uses
 * {@code FalcoInstance}, which holds the lock of the chunk it touches instead.
 * </p>
 * <h2>What the per-instance state reaches, and what it does not</h2>
 * <p>
 * {@code enableAutoChunkLoad} reaches {@link #loadOptionalChunk(int, int)}, which is the method a
 * player's chunk loading goes through, so the flag has an effect a player can observe. It does not
 * reach {@code setBlock}: that call is the container's and asks the container's flag, which follows
 * directly from the paragraph above.
 * </p>
 * <p>
 * {@code setGenerator} and {@code setChunkSupplier} reach nothing inside Minestom at all. Chunks are
 * created by the container, which asks its own generator and its own supplier, and a chunk loader is
 * handed the container rather than a view. Both setters exist here so that configuring one view
 * stops reconfiguring the world and every other view of it — a repair, not a capability. To decide
 * what the world is made of, call them on {@link #getInstanceContainer()}.
 * </p>
```

Bump `@version` on the class from `1.0.0` to `1.1.0`, since the documented contract of the type grew after Task 1.

- [ ] **Step 5: Extend `package-info.java`**

Insert this paragraph after the one about `FalcoInstanceException` and before the one beginning "This package is about clarity, not throughput.":

```java
 * <p>
 * {@link net.onelitefeather.falco.instance.FalcoSharedInstance} is the one type here which does not
 * avoid {@code InstanceContainer} but builds on it. It extends {@code SharedInstance}, because the
 * fast path that spares a player a full chunk resend on an instance change is decided by
 * {@code SharedInstance#areLinked}, and that method compares containers rather than classes — a
 * subclass keeps it, a look-alike does not. What it repairs is the three setters and the save which
 * Minestom's shared instance writes through to the container it borrows from. What it does not
 * repair is the write path: the block owner is an {@code InstanceContainer} and its instance monitor
 * comes with it.
 * </p>
```

- [ ] **Step 6: Correct the README**

Replace the paragraph that currently begins "A foreign instance has to be registered by hand" (in `### Using falco-instance`) with:

````markdown
A foreign instance has to be registered by hand, which is what `registerInstance` above does. The
chunk supplier stays at `FalcoChunk::new` — the lifecycle hooks a chunk needs to be marked unloaded
are `protected` in Minestom's own package, so any other chunk type is refused rather than accepted
and then left unloadable. The reasoning, and the four places where Minestom quietly treats a foreign
instance differently, are in
[Rationale: Instances and Chunks](https://github.com/OneLiteFeatherNET/Falco/wiki/Rationale-Instances-And-Chunks).

Shared worlds are the one case this instance cannot serve, because `SharedInstance` takes an
`InstanceContainer` and nothing else. `FalcoSharedInstance` accepts that and builds on the container
instead:

```java
InstanceManager manager = MinecraftServer.getInstanceManager();
InstanceContainer world = manager.createInstanceContainer();
world.setChunkSupplier(FalcoChunk::new);

// Not manager.createSharedInstance(world): that factory always builds Minestom's own type.
FalcoSharedInstance view = new FalcoSharedInstance(UUID.randomUUID(), world);
manager.registerSharedInstance(view);
```

The view keeps its own generator, chunk supplier, auto-load setting and tags, where Minestom's writes
all four through to the container and lets one view reconfigure another. What it does not change is
who owns the blocks: `setBlock` reaches the container, and the container serialises every write on
its own monitor. A world built this way keeps what the Falco chunk saves and keeps the container's
write path; a world that needs the write path uses `FalcoInstance` and gives up sharing.
````

- [ ] **Step 7: Record the outcome in the research document**

`docs/research/shared-instances-and-batches.md` recommended a Falco-owned delegating instance and recorded that `areLinked` would stay false. That recommendation was not taken. Append this subsection at the end of section 1, after *Cost and recommendation*, in the same shape the batch section already uses for its own correction:

```markdown
#### How it was actually built: neither of the two options above

The question this section asked was whether a `FalcoInstance` can back a shared instance. It cannot,
and everything above about that is still true. Stage 4 asked a different question and gave up the
premise instead: the block owner is a plain `InstanceContainer` whose chunk supplier is
`FalcoChunk::new`, and `FalcoSharedInstance extends SharedInstance` on top of it.

With the premise gone, so is the wall. The constructor is satisfied, `InstanceManager
#registerSharedInstance` takes any subclass, `InstanceContainer#addSharedInstance` is reached by
`InstanceManager` from inside its own package and never by Falco, and `areLinked` — which compares
`getInstanceContainer()` and not a class — answers **true**. The measured `areLinked(foreign,
falcoSharedView) = false` in the list above stands as a statement about the delegating view that was
never built; it does not describe what exists.

The viewer union this section worried about is not needed either. `Chunk`'s constructor takes
`instanceContainer.getSharedInstances()`, and a `FalcoSharedInstance` is in that list, so the players
of a view are viewers of the container's chunks without a line of Falco code. The cost is the one
this section did not price: the container is the block owner, its `UNSAFE_setBlock` is
`private synchronized`, and a shared world therefore keeps the instance monitor on every write.
```

- [ ] **Step 8: Run the module suite**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
./gradlew :falco-instance:test
```

Expected: all PASS, including everything stages 1 to 3 left behind.

- [ ] **Step 9: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
git add falco-instance/src/main/java/net/onelitefeather/falco/instance/FalcoSharedInstance.java \
        falco-instance/src/main/java/net/onelitefeather/falco/instance/package-info.java \
        falco-instance/src/test/java/net/onelitefeather/falco/instance/FalcoSharedInstanceWriteTest.java \
        README.md docs/research/shared-instances-and-batches.md
git commit -m "docs(instance): state that a shared world keeps the container's write monitor"
```

---

### Task 8: Acceptance

**Files:**
- Modify: `docs/superpowers/plans/2026-08-02-falco-shared-instance.md`

**Interfaces:**
- Consumes: everything.
- Produces: a `## Stage 4 result` section in this file.

- [ ] **Step 1: Run every module**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
./gradlew :falco-instance:test :falco-light:test :falco-anvil:test :falco-demo:test :falco-benchmarks:test --rerun-tasks
```

Expected: all PASS. `falco-light` and `falco-anvil` matter because both build chunks into instances; `falco-benchmarks` carries the equivalence and footprint tests of stages 1 and 2 and is the regression net for anything this stage might have disturbed. Record the test counts per module — the stage 2 result records 143 / 189 / 193 / 139 / 38 and a divergence needs explaining rather than accepting.

- [ ] **Step 2: Check the whole branch compiles as a published artefact**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
./gradlew build -x test
```

Expected: PASS, with Javadoc generation included. A missing `@param` or a broken `{@link}` in the new type fails here and not in the test task.

- [ ] **Step 3: Re-read the four stories against the code**

Walk US-4.01 to US-4.04 and name, for each, the test method that carries it. If a story has no method name, it is not done. The mapping this plan intends:

| Story | Carried by |
|---|---|
| US-4.01 | `FalcoSharedInstanceResendTest#testTheFastPathSendsNothing`, backed by `FalcoSharedInstanceTest#testLinkedToItsContainer` and its siblings |
| US-4.02 | `FalcoSharedInstanceStateTest#testTheGeneratorDoesNotAlias` and `#testClearingTheGeneratorDoesNotClearTheContainer` |
| US-4.03 | `FalcoSharedInstanceSaveTest#testTheViewSavesItsOwnTags` |
| US-4.04 | the two `<h2>` sections of `FalcoSharedInstance`, the README section, and `FalcoSharedInstanceWriteTest` |

- [ ] **Step 4: Write the stage result**

Append a `## Stage 4 result` section to this file. It states, at minimum: the date and the JDK; the test counts per module; which mutation was applied for each task and what went red; and — required — the sentence that no timing figure was produced on this stage, because none can be produced honestly until `docs/benchmarks/full-run.sh` has run on an idle machine. If any acceptance item is not met, state it as unmet with the number rather than rounding it away.

- [ ] **Step 5: Commit**

```bash
cd /mnt/projects/oss/onelitefeather/Falco-worktrees/shared-instance
git add docs/superpowers/plans/2026-08-02-falco-shared-instance.md
git commit -m "docs(plan): record what stage 4 repaired and what it could not"
```

---

## Definition of done

- [ ] `FalcoSharedInstance extends SharedInstance` exists, carries `@ApiStatus.Experimental`, `@author TheMeinerLP`, `@version` and `@since 0.4.0`, and is registered through `InstanceManager#registerSharedInstance`
- [ ] `SharedInstance#areLinked` reports it linked to its container, to a sibling view and to a stock `SharedInstance` over the same container, and unlinked across containers
- [ ] A player moving from the container into the view at the same position receives **zero** `UpdateViewPositionPacket` and **zero** `UnloadChunkPacket`, with a negative control in the same class proving those markers are emitted on the slow path
- [ ] `setGenerator`, `setChunkSupplier` and `enableAutoChunkLoad` change this instance and nothing else, each proven with a second view over the same container that must stay unchanged
- [ ] `enableAutoChunkLoad(false)` prevents `loadOptionalChunk` from causing a load, and still hands back a chunk the container already holds
- [ ] `saveInstance()` hands the container's `ChunkLoader` **this** instance, and the container's own `saveInstance()` is unaffected
- [ ] Every task's test was proven to bite by an injected defect that was reverted afterwards, and each injection is named in the stage result
- [ ] The monitor limitation is stated in the class Javadoc, in `package-info.java` and in the README, and is pinned by `FalcoSharedInstanceWriteTest`
- [ ] `docs/research/shared-instances-and-batches.md` no longer leaves its superseded recommendation as the last word
- [ ] `:falco-instance:`, `:falco-light:`, `:falco-anvil:`, `:falco-demo:` and `:falco-benchmarks:` tests all pass, and `./gradlew build -x test` succeeds including Javadoc

## What stage 4 deliberately does not do

Named here so that a reviewer does not read them as omissions.

- **It does not override `setBlock`, `placeBlock` or `breakBlock`.** That is the wall, and the whole of US-4.04 is the decision to document it instead of working around it. Reaching `UNSAFE_setBlock` is not possible without reflection, and reflection is refused by NFR-001 and would not help anyway: the remaining three call sites are inside the container's own compiled code.
- **It does not make `FalcoInstance` able to back a shared instance.** `SharedInstance` has exactly one constructor and it takes an `InstanceContainer`. Changing that is a Minestom change; `docs/research/shared-instances-and-batches.md` already writes the upstream proposal.
- **It does not fix Minestom's viewer-cache leak for shared worlds.** M14 — one `EntityTrackerImpl` viewer entry per chunk construction, 257 B, never removed — lives on `InstanceContainer`, which this stage requires. Stage 3 removes it for `FalcoInstance`; a shared world keeps it. Fixing it needs `EntityTracker`, and `EntityTracker` is `sealed`.
- **It adds no factory to `InstanceManager`.** `createSharedInstance` is Minestom's and always builds the stock type; a two-line hand registration is honest and adds no surface that a Minestom upgrade can silently change under it.
- **It produces no timing figure.** M16 (765 ms, 86.5 MB for a full resend) is the justification for this stage's architecture and remains a scouting number from a machine at load 4.4 to 7.0. It may not be quoted in the README or the wiki, and this stage does not requote it. The claim this stage is allowed to make is a packet count of zero.
- **It does not touch the anvil loader.** A view and its container write instance data to the same `level.dat` and the last save wins. That is stated in the Javadoc of `saveInstance` and left as it is: separating them means a loader change, which is stage 4 of a different plan.

## Questions this plan could not answer from the spec

Carry these to the project owner rather than deciding them silently in code.

1. **NFR-006 against §3.** NFR-006 forbids an instance-wide monitor on a block write without qualification; §3 lists removing that monitor from a container with a shared instance as a non-goal, and §4.4 explains why. The spec never says which one governs stage 4. This plan reads §3 as the carve-out. If it is the other way round, stage 4 cannot be built as specified.
2. **What a per-instance generator is supposed to mean.** US-4.02 asks that no other instance observe a `setGenerator` on a shared instance. That is achievable and this plan achieves it, but the resulting value is read by nothing inside Minestom, because chunks are generated by the container. Whether a setter with no consumer is the desired outcome, or whether it should throw `UnsupportedOperationException` and point at the container — louder, and further from the type it overrides — is a decision the spec does not take. The same question applies to `setChunkSupplier`.
3. **Whether a view should be able to save separately at all.** US-4.03 asks that a shared instance's tags be written. They now are, into the same `level.dat` the container writes, so two views and their container overwrite one another. Making that meaningful needs a loader that addresses instance data per instance, which no story asks for.

---

## Stage 4 result

Run 2026-08-02 on branch `feat/shared-instance`, JDK 25.0.3 (Temurin), Minestom as pinned by the
build. First taken at `e9c5cce` at a `/proc/loadavg` of 11.77, then taken again in full at `9271642`
after the review follow-up of Task 7 had added a module's worth of rules — that second run is the one
the numbers below report, and it read 33.75 before the suite and 44.81 after it. The machine carried
an IntelliJ session and a parallel worktree throughout. Nothing below is a timing figure, and that is
deliberate — see *What may be quoted*.

### Tests

`./gradlew :falco-instance:test :falco-light:test :falco-anvil:test :falco-demo:test
:falco-benchmarks:test :falco-archunit:test --rerun-tasks` — **BUILD SUCCESSFUL**, no failures.

| module | stage 2 | now | difference |
| --- | ---: | ---: | ---: |
| `:falco-instance:` | 143 | **182** | +39 |
| `:falco-light:` | 189 | **205** | +16 |
| `:falco-anvil:` | 193 | **217** | +24 |
| `:falco-demo:` | 139 | **166** | +27 |
| `:falco-benchmarks:` | 38 | **42** | +4 |
| `:falco-archunit:` | — | **46** | new module |

The divergence is accounted for rather than accepted. Stage 4 touched **ten files** and no others,
and they are named here one by one so that the count can be checked against the list rather than
believed: **seven** in `falco-instance` — `FalcoSharedInstance`, `package-info` and the five test
classes `FalcoSharedInstanceTest`, `FalcoSharedInstanceResendTest`, `FalcoSharedInstanceStateTest`,
`FalcoSharedInstanceSaveTest`, `FalcoSharedInstanceWriteTest` — **one** in `falco-archunit`
(`ForeignWritePathTest`, added by the review follow-up), `README.md` and this plan. 7 + 1 + 1 + 1 =
10, and the same five test classes are counted again by test case two sentences below. The union of
`git show --name-only` over the stage's own commits is the check, and it has to be taken per commit
rather than over a range, because the branch merged `feat/block-storage` and `main` mid-stage — a
range check drops exactly the two classes it is easiest to lose here, `FalcoSharedInstanceTest` and
`FalcoSharedInstanceResendTest`, which tasks 1 and 2 committed before the first of those merges. Of the +39 in `falco-instance`, **30 are this stage**
(`FalcoSharedInstanceTest` 7, `FalcoSharedInstanceResendTest` 3, `FalcoSharedInstanceStateTest` 12,
`FalcoSharedInstanceSaveTest` 5, `FalcoSharedInstanceWriteTest` 3). The remaining 9, and the whole of
the other four columns, arrived with the two merges this branch took mid-stage — `feat/block-storage`
(stage 3) and `main` — six of the nine being the new `FalcoInstanceBuilderTest` and three being cases
added to classes that already existed. `:falco-archunit:` is a module `main` brought with it; it held
42 rules on arrival and holds **46** after this stage, the four added ones being `ForeignWritePathTest`
— the only column stage 4 moved outside `falco-instance`, and it moved it on purpose.

**One test is skipped, and it is not this stage's.** `EmptySectionCensusTest
#testTheEmptySectionShareOfARealWorld` opens with an `Assumptions.assumeTrue` on an Anvil world being
present on disk and there is none in CI or in this worktree. It has been skipped since stage 2 and is
recorded here rather than rounded into "`:falco-benchmarks:` 42 green".

`./gradlew build -x test` — **BUILD SUCCESSFUL**. Re-run with `--rerun-tasks` so that the verdict is
not an up-to-date check: `javadoc` genuinely executed for all four published modules and emitted no
warning and no error, `checkApiCompatibility` genuinely executed and passed.

### The stories, and the method that carries each

| Story | Carried by | State |
|---|---|---|
| US-4.01 | `FalcoSharedInstanceResendTest#testTheFastPathSendsNothing`, with `#testTheSlowPathSendsTheMarkers` as the control and `FalcoSharedInstanceTest#testLinkedToItsContainer`, `#testLinkedToASibling`, `#testLinkedToAStockSharedInstance`, `#testUnlinkedAcrossContainers` beneath it | met |
| US-4.02 | `FalcoSharedInstanceStateTest#testTheGeneratorDoesNotAlias` and `#testClearingTheGeneratorDoesNotClearTheContainer`, plus the same shape for the chunk supplier and the auto-load flag | met, with the reservation below |
| US-4.03 | `FalcoSharedInstanceSaveTest#testTheViewSavesItsOwnTags`, with `#testTheContainerStillSavesItself`, `#testAParallelSaveStillGetsThisInstance`, `#testAFailureIsReturnedOnceOnBothBranches` and `#testAFreshViewHasNothingToWrite` | met, with the reservation below |
| US-4.04 | the two `<h2>` sections of `FalcoSharedInstance`, the paragraph in `package-info.java`, the *Shared worlds* section of `README.md`, `FalcoSharedInstanceWriteTest` (3 cases) for the observable half and `ForeignWritePathTest` (4 rules) for the premise | met |

### Every mutation that was injected, and what went red

No test in this stage was accepted because it was green. Each was made to fail on purpose first, and
each injection was reverted and re-run.

| Task | Injected defect | What went red |
|---|---|---|
| 1 | `getInstanceContainer()` overridden to return a foreign container | the four `areLinked` cases |
| 2 | the same override | only `testTheFastPathSendsNothing` — the slow-path control stayed green, which is what makes it a control |
| 3 | `setGenerator` delegates to `super`; separately, the `generator()` override deleted | both aliasing cases; the seeding case stayed green under both |
| 4 | `setChunkSupplier` delegates to `super`; separately, setter *and* getter restored to full delegation | the alias case at its first assertion and the null case; under full delegation the sibling assertion and the null case |
| 5 | `enableAutoChunkLoad` delegates to `super`; separately, the `loadOptionalChunk` override deleted | the alias case and the load case at its first assertion; the deletion additionally took the entity-spawn case |
| 6 | body replaced by `super.saveInstance()`; separately, the synchronous branch forced; separately, `handleException` added next to `failedFuture`; separately, the constructor copying the container's tag compound | the view case at `assertSame`; the parallel case; the failure case; `testAFreshViewHasNothingToWrite` |
| 7 | `getChunk` overridden to return `chunk.copy(this, chunkX, chunkZ)` | `testTheChunkIsTheContainersChunk` at the first `assertSame` and `testAWriteReachesEveryView` at the first `assertEquals`; the auto-load case stayed green, because it asserts the container's state |
| 7, review follow-up | four separately, one per rule of `ForeignWritePathTest`: the guarded method name pointed at `setBlock`; `breakBlock` swapped for `loadChunk` in the expected caller set; the forward demanded to go to `Chunk`; an override of `setBlock` added to `FalcoSharedInstance` that only calls `super` | W1 twice (not private, not synchronized), W2 with one missing and one unexpected caller, W3 three times (one per forwarded method), W4 at `FalcoSharedInstance.java:355`. The fourth mutation left all three cases of `FalcoSharedInstanceWriteTest` green, which is why the rule exists |

### What the acceptance re-proved for itself

Rows in a table above are a report of what an earlier session did. An acceptance that only copies them
forward checks nothing, so two of them were injected again from scratch at `9271642`, by a session
that had not written them.

- **`setGenerator` delegating to `super` again.** `FalcoSharedInstanceStateTest` reports 12 tests, 2
  failed: *keeps a generator to itself* and *does not lose the container's generator when it clears
  its own*. The ten others stay green, the seeding case among them — which is the point of the
  two-view construction, since a test looking at one instance would have passed with the defect in
  place. Reverted, 12 of 12 green, tree verified clean.
- **A `super`-only `setBlock` override on `FalcoSharedInstance` again.** `ForeignWritePathTest`'s
  W4 is the single failure in 46 archunit rules, and with the very same override in the tree all
  three cases of `FalcoSharedInstanceWriteTest` still pass. That is the gap the rule was written for,
  re-measured rather than quoted. Reverted, both modules green, tree verified clean.

### What may be quoted

A **packet count**, and nothing else. A player moving from the container into a view at the same
position receives zero `UpdateViewPositionPacket` and zero `UnloadChunkPacket`; on the slow path the
same move at `viewDistance(1)` sends one view update and **25** `ChunkDataPacket`. Both numbers come
from a deterministic packet collector, so the machine's load does not touch them.

**No timing figure was produced on this stage, and none may be.** M16 (765 ms and 86.5 MB for a full
resend) is the measurement that motivated the architecture and remains a scouting number from a
machine at load 4.4 to 7.0; it is not requoted here, in the README or in the wiki. Nothing citable in
wall-clock terms can be produced until `docs/benchmarks/full-run.sh` has run on an idle machine, and
it still never has. The load during this stage's own suites was 11.77 the first time and 33.75 to
44.81 the second — a machine on which the packet counts are still exact and a millisecond would be
fiction.

### What stage 4 did not reach

A result that lists only what was gained is not a result.

- **The viewer-cache leak survives on the shared path.** M14 — one `EntityTrackerImpl` viewer entry
  per chunk construction, 257 B, never removed — lives on `InstanceContainer`, and this stage
  *requires* an `InstanceContainer` as the block owner. Stage 3 removed it for `FalcoInstance`; a
  shared world keeps it, and `ChunkViewerCacheLeakTest` in `falco-benchmarks` still asserts both
  halves of that. Fixing it needs `EntityTracker`, which is `sealed`.
- **A view's save is still not separable from its container's.** `saveInstance()` now hands the
  loader `this` rather than the container, which is the whole of US-4.03 — but an `AnvilLoader`
  writes instance data into one `level.dat` per world, so a container and every view of it write over
  one another and the last save wins. Worse, the repair takes something away: code that called
  `saveInstance()` on a view and got the world written now writes the view, and a fresh view has an
  empty tag handler, so on an untagged view the call writes nothing at all. Both halves are in the
  Javadoc; neither is fixed.
- **The per-instance generator and chunk supplier have no reader.** `setGenerator` on a view stores a
  value that nothing in Minestom consults: chunks are created by the container, which asks its own
  generator and its own supplier, and a `ChunkLoader` is handed the container. The aliasing defect
  US-4.02 named is genuinely gone — `sharedA.setGenerator(g)` no longer reconfigures `sharedB` — but
  a generator set on a view generates nothing. The project owner chose *store and return* over
  *throw and point at the container*; question 2 below is answered by that decision and the
  consequence is stated at the setter, at the getter, in the class documentation and in the README,
  because a silently ineffective setter is the trap this project hunts in review.
- **The write path is unchanged, on purpose.** `setBlock` on a view reaches the container and the
  container serialises it on its own monitor. That is US-4.04 rather than an omission, and it is now
  pinned by a test instead of only asserted in prose.

### The premise of US-4.04, guarded after review

Review of Task 7 found the documentation's load-bearing sentence unguarded: "`UNSAFE_setBlock` is
`private synchronized`, four callers, so every shared write pays the instance monitor" is a claim
about *Minestom's* bytecode, and all three cases of `FalcoSharedInstanceWriteTest` observe blocks and
chunks only. Every one of them stays green if Minestom drops the `synchronized`, opens the method up
or grows a fifth caller — and all three stayed green under an override of `setBlock` in
`FalcoSharedInstance`, which was measured, not assumed.

`falco-archunit` now carries `ForeignWritePathTest`, four rules with an `@AnalyzeClasses` scope of
its own over `net.minestom.server.instance` and `net.onelitefeather.falco.instance`: W1 the two
modifiers, W2 the caller set as an exact set (missing and unexpected reported apart, because they
mean opposite things), W3 that `SharedInstance` forwards `setBlock`, `placeBlock` and `breakBlock` to
the container, W4 that `FalcoSharedInstance` overrides none of the three. Three of the four fail on a
Minestom upgrade rather than on a Falco commit, which is intended: what they report is not that
Minestom is wrong but that the paragraph has to be rewritten and the decision not to override
`setBlock` has to be taken again. The four rules are the whole of the module's move from 42 to the
**46** in the table above; `falco-instance` stays at 182, because nothing there was added — the gap
was never a missing case, it was a case that could not exist in a module which does not read foreign
bytecode.

Its structural limit is the one `ConcurrencyTest` already names: ArchUnit sees `ACC_SYNCHRONIZED` on
a method, never a `synchronized` block. A lock moved into the body of `UNSAFE_setBlock` would turn W1
red although nothing changed for a caller — a false alarm on the safe side, since the paragraph would
have to be re-read either way.

### One claim of this plan that the sources did not support

Task 7's README text listed tags beside the generator, the chunk supplier and the auto-load flag as a
fourth value the stock `SharedInstance` writes through to its container. It is not one. `Instance`
declares `protected TagHandler tagHandler = TagHandler.newHandler()` at `Instance.java:127` and
`SharedInstance` never overrides `tagHandler()`, so a view's tags were always the view's. What was
broken is narrower and was repaired in Task 6: `saveInstance()` handed the loader the container, so
the view's own tags were never written. The README says that instead.

### Two acceptance items that could not be met as written

- The plan's Definition of done names `docs/research/shared-instances-and-batches.md`. That file no
  longer exists in this repository — the long-form documentation moved into the wiki, where the page
  is `Research-Shared-Instances-And-Batches.md`. The correction the item asks for was written there
  instead, as `abedd9e` in the wiki repository: section 1's recommendation is no longer the last
  word, and the measured `areLinked(foreign, falcoSharedView) = false` is explicitly re-scoped to the
  delegating view that was never built.
- The plan's Task 7 asked for a replacement of the README paragraph beginning "A foreign instance has
  to be registered by hand", in a section `### Using falco-instance`. Neither exists any more: the
  README was rewritten on `main` before this branch merged it. The monitor limitation went into a new
  `## Shared worlds` section instead. The same rewrite had already removed the false claim that
  `FalcoInstance#setGenerator` throws, so nothing was left to correct there — the wiki states it in
  the past tense at `Rationale-Instances-And-Chunks.md`, and `FalcoInstanceGeneratorTest` (11 cases)
  demonstrates generation.
