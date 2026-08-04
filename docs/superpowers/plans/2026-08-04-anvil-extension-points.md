# Anvil extension points — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two hard-wired policies in `falco-anvil` — which worlds are readable, and what an unknown
palette entry becomes — become services a caller can replace or discover.

**Architecture:** One shared resolution helper implements the discovery rules once. Two service
interfaces use it: `ChunkVersionPolicy` (the guard from #45, moved rather than rewritten) and
`UnknownEntryPolicy` (consulted where the two palette resolvers substitute today). Each has a
built-in implementation, an explicit builder slot, and a `discover…()` slot.

**Tech Stack:** Java 25, Gradle, `java.util.ServiceLoader` (plain classpath, no JPMS), Adventure NBT,
JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-04-anvil-extension-points-design.md`

**Base:** `main` **after #45 is merged**. This plan moves the body of `requireReadableVersion`, which
only exists on that branch. Do not start before the merge.

## Global Constraints

- All three interfaces live in `net.onelitefeather.falco.anvil`. No new module.
- **No `module-info.java`** is added. Plain classpath services.
- `checkApiCompatibility` runs: every signature change is additive. **`PaletteEntryResolver.toId` and `toEntry` keep their exact signatures** — see the decision below.
- Javadoc under `-Werror`, `@param`/`@return`/`@throws` complete, `@since 1.2.0` on new members, `@version` of every changed type raised one minor.
- Builders are immutable: a new field means the constructor, `build()`, and **every** existing setter.
- Test names read as sentences. Tests are package-private, plain JUnit assertions.
- Conventional Commits, lower case.
- No timing figure anywhere.
- Check `uptime` before any test run and record it. Counts come from the JUnit XML, not the console.

## Two decisions this plan makes that the spec left open

Both are recorded here because the spec's "Open for the plan" section names them, and because an
implementer would otherwise have to invent them.

**1. `ChunkVersionPolicy` does not count and does not log.** The spec's sketch was
`check(CompoundBinaryTag, int)`, but the body being moved reads three instance fields of the loader:
`minimumDataVersion`, `diagnostics` and `regionDirectory`. Passing all three into a service would put
the loader's infrastructure into a public contract. Instead the policy **only decides and throws**;
the loader catches, counts and logs, deriving the reported version from the compound it already has.
This keeps the interface free of `AnvilDiagnostics` and keeps every diagnostic in one place.

**2. `UnknownEntryPolicy` throws an *unchecked* fault.** `PaletteEntryResolver.toId` is
`int toId(String, CompoundBinaryTag)` with no `throws` clause, and it is published API on a 1.0.0
artefact. Adding a checked exception to it would break every implementor. `AnvilChunkException`
already exists as `non-sealed class … extends RuntimeException implements AnvilFault`, so a policy
that refuses throws that, and no published signature changes.

## File Structure

| File | Responsibility | Change |
| --- | --- | --- |
| `…/anvil/ServiceResolution.java` | The discovery rules, once | Create (package-private) |
| `…/anvil/ChunkVersionPolicy.java` | The readable-world contract | Create |
| `…/anvil/DefaultChunkVersionPolicy.java` | #45's body, moved | Create |
| `…/anvil/UnknownEntryPolicy.java` | The unknown-entry contract | Create |
| `…/anvil/DefaultUnknownEntryPolicy.java` | Air and plains, as today | Create |
| `…/anvil/FalcoAnvilLoader.java` | Loader and builder | Modify: two field pairs, four builder slots, `requireReadableVersion` becomes a call |
| `…/anvil/BlockPaletteResolver.java` | Block palette | Modify: substitution goes through the policy |
| `…/anvil/BiomePaletteResolver.java` | Biome palette | Modify: same |
| `falco-anvil/src/main/resources/META-INF/services/…ChunkVersionPolicy` | `falco-anvil`'s own registration | Create |
| Five test classes | | Create/modify per task |

---

### Task 1: The resolution rules, once

**Files:**
- Create: `falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ServiceResolution.java`
- Test: `falco-anvil/src/test/java/net/onelitefeather/falco/anvil/ServiceResolutionTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `static <T> @Nullable T ServiceResolution.discover(Class<T> service)` — returns the single
  provider, `null` if none, throws `IllegalStateException` naming all providers if more than one;
  `static <T> @Nullable T ServiceResolution.choose(Class<T> service, @Nullable T explicit, boolean discover)`
  — throws `IllegalStateException` if both an explicit instance and `discover` are given, otherwise
  returns the explicit one, the discovered one, or `null`.

- [ ] **Step 1: Write the failing tests**

The tests need services to find. Register two dummies through the **test** resources so the real
module is unaffected:

`falco-anvil/src/test/resources/META-INF/services/net.onelitefeather.falco.anvil.ServiceResolutionTest$Dummy`
containing two lines, the two nested implementation class names.

```java
class ServiceResolutionTest {

    interface Dummy { String name(); }

    public static final class FirstDummy implements Dummy {
        public FirstDummy() { }
        @Override public String name() { return "first"; }
    }

    public static final class SecondDummy implements Dummy {
        public SecondDummy() { }
        @Override public String name() { return "second"; }
    }

    interface Absent { }

    @Test
    void testAServiceWithNoProviderResolvesToNothing() {
        assertNull(ServiceResolution.discover(Absent.class));
    }

    @Test
    void testTwoProvidersAreRefusedAndBothAreNamed() {
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> ServiceResolution.discover(Dummy.class));

        assertTrue(failure.getMessage().contains("FirstDummy"), failure.getMessage());
        assertTrue(failure.getMessage().contains("SecondDummy"), failure.getMessage());
    }

    @Test
    void testAnExplicitInstanceAndDiscoveryTogetherAreRefused() {
        Dummy explicit = () -> "explicit";

        assertThrows(IllegalStateException.class,
                () -> ServiceResolution.choose(Dummy.class, explicit, true));
    }

    @Test
    void testAnExplicitInstanceIsUsedWithoutTouchingTheClasspath() {
        Dummy explicit = () -> "explicit";

        assertEquals("explicit", ServiceResolution.choose(Dummy.class, explicit, false).name());
    }

    @Test
    void testNeitherExplicitNorDiscoveredResolvesToNothing() {
        assertNull(ServiceResolution.choose(Dummy.class, null, false));
    }
}
```

The fourth case matters more than it looks: it proves an explicit instance short-circuits before
`ServiceLoader` runs. Without that, `Dummy`'s two providers would make it throw.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :falco-anvil:test --tests "*ServiceResolutionTest*"`
Expected: compilation failure — `ServiceResolution` does not exist.

- [ ] **Step 3: Implement it**

```java
final class ServiceResolution {

    private ServiceResolution() {
    }

    /**
     * Finds the single provider of the given service on the classpath.
     *
     * @param service the service interface
     * @param <T>     the service type
     * @return the provider, or null if the classpath carries none
     * @throws IllegalStateException if more than one provider is registered
     */
    static <T> @Nullable T discover(Class<T> service) {
        List<T> providers = new ArrayList<>();
        ServiceLoader.load(service).forEach(providers::add);

        if (providers.isEmpty()) {
            return null;
        }
        if (providers.size() > 1) {
            // Naming them is the whole value of this branch: "several providers" sends the reader
            // to the classpath, the two class names send them to the jar that should not be there.
            throw new IllegalStateException(
                    "Several providers of " + service.getName() + " are registered and none can be chosen for you: "
                            + providers.stream().map(provider -> provider.getClass().getName()).sorted().toList()
                            + ". Set one explicitly on the builder instead."
            );
        }
        return providers.getFirst();
    }

    /**
     * Chooses between an explicitly configured instance and classpath discovery.
     *
     * @param service  the service interface
     * @param explicit the instance the caller configured, or null
     * @param discover whether the caller asked for discovery
     * @param <T>      the service type
     * @return the chosen provider, or null if the caller asked for neither
     * @throws IllegalStateException if the caller asked for both, or if discovery is ambiguous
     */
    static <T> @Nullable T choose(Class<T> service, @Nullable T explicit, boolean discover) {
        if (explicit != null && discover) {
            throw new IllegalStateException(
                    "An explicit " + service.getSimpleName() + " and discovery were both configured. "
                            + "Choose one: the explicit instance, or the classpath."
            );
        }
        if (explicit != null) {
            return explicit;
        }
        return discover ? discover(service) : null;
    }
}
```

- [ ] **Step 4: Run them and watch them pass**

Run: `./gradlew :falco-anvil:test --tests "*ServiceResolutionTest*"`
Expected: PASS, five cases.

- [ ] **Step 5: Gegenprobe**

Change `providers.size() > 1` to `providers.size() > 2`.
`testTwoProvidersAreRefusedAndBothAreNamed` must go red and the other four stay green. Revert, verify
`git status` is clean.

- [ ] **Step 6: Commit**

```bash
git add falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ServiceResolution.java \
        falco-anvil/src/test/java/net/onelitefeather/falco/anvil/ServiceResolutionTest.java \
        falco-anvil/src/test/resources/META-INF/services/
git commit -m "feat(anvil): resolve a service once, and refuse to guess between two"
```

---

### Task 2: The version policy

**Files:**
- Create: `…/anvil/ChunkVersionPolicy.java`, `…/anvil/DefaultChunkVersionPolicy.java`
- Create: `falco-anvil/src/main/resources/META-INF/services/net.onelitefeather.falco.anvil.ChunkVersionPolicy`
- Modify: `…/anvil/FalcoAnvilLoader.java` — the guard call, one field, two builder slots
- Test: `…/anvil/ChunkVersionPolicyTest.java`, and the existing `FalcoAnvilLoaderIntegrationTest`

**Interfaces:**
- Consumes: `ServiceResolution.choose` from Task 1.
- Produces: `ChunkVersionPolicy` with
  `void check(CompoundBinaryTag data, int minimumDataVersion) throws ChunkDataException`;
  `DefaultChunkVersionPolicy` implementing it; builder slots
  `versionPolicy(ChunkVersionPolicy)` and `discoverVersionPolicy()`.

- [ ] **Step 1: Write the failing tests**

In a new `ChunkVersionPolicyTest`, against the default policy directly — no loader, no environment:

```java
@Test
void testTheDefaultPolicyRefusesALevelLayout() {
    CompoundBinaryTag legacy = CompoundBinaryTag.builder()
            .put("Level", CompoundBinaryTag.builder().put("Sections", ListBinaryTag.empty()).build())
            .build();

    ChunkDataException failure = assertThrows(ChunkDataException.class,
            () -> new DefaultChunkVersionPolicy().check(legacy, 2844));
    assertEquals(ChunkDataException.Reason.UNSUPPORTED_CHUNK_VERSION, failure.reason());
}

@Test
void testTheDefaultPolicyAcceptsAChunkWithoutAStoredVersion() throws Exception {
    CompoundBinaryTag toolWritten = CompoundBinaryTag.builder()
            .put("sections", ListBinaryTag.empty())
            .build();

    new DefaultChunkVersionPolicy().check(toolWritten, 2844);
}

@Test
void testTheDefaultPolicyRefusesAMistypedVersion() {
    CompoundBinaryTag broken = CompoundBinaryTag.builder()
            .putString("DataVersion", "not-a-number")
            .put("sections", ListBinaryTag.empty())
            .build();

    assertThrows(ChunkDataException.class, () -> new DefaultChunkVersionPolicy().check(broken, 2844));
}
```

In `FalcoAnvilLoaderIntegrationTest`, that the loader honours a replacement — the fixture helper
`writeRawChunk` is already there:

```java
@Test
void testAPolicyThatAllowsEverythingLetsALegacyChunkThrough(Env env) throws Exception {
    CompoundBinaryTag legacy = CompoundBinaryTag.builder()
            .put("Level", CompoundBinaryTag.builder().put("Sections", ListBinaryTag.empty()).build())
            .putString("Status", "minecraft:full")
            .build();
    writeRawChunk(11, 11, legacy);

    try (FalcoAnvilLoader loader = FalcoAnvilLoader.builder()
            .versionPolicy((data, minimum) -> { })
            .build(this.worldRoot, OVERWORLD)) {
        Instance instance = env.createEmptyInstance(loader);

        assertNotNull(loader.loadChunk(instance, 11, 11));
    }
}

@Test
void testWithoutAnyPolicyALegacyChunkIsNotChecked(Env env) throws Exception {
    CompoundBinaryTag legacy = CompoundBinaryTag.builder()
            .put("Level", CompoundBinaryTag.builder().put("Sections", ListBinaryTag.empty()).build())
            .putString("Status", "minecraft:full")
            .build();
    writeRawChunk(12, 12, legacy);

    try (FalcoAnvilLoader loader = FalcoAnvilLoader.builder()
            .versionPolicy(null)
            .build(this.worldRoot, OVERWORLD)) {
        Instance instance = env.createEmptyInstance(loader);

        assertNotNull(loader.loadChunk(instance, 12, 12));
    }
}
```

**The second case is the one the spec asks for in executable form.** It documents the cost of the
chosen default: with no policy, a pre-21w43a chunk loads, and it loads as air. Its Javadoc says so.

Check what `versionPolicy(null)` should mean before writing it — if the builder rejects null, express
"no policy" the way the implementation actually offers it, and say so in the report.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :falco-anvil:test --tests "*ChunkVersionPolicyTest*" --tests "*FalcoAnvilLoaderIntegrationTest*"`
Expected: compilation failure — the type and the slots do not exist.

- [ ] **Step 3: Create the interface**

```java
/**
 * Decides whether a chunk is one this loader can read.
 * <p>
 * A policy only decides. It does not count and it does not log: the loader catches the failure,
 * records it in its {@link AnvilDiagnostics} and writes the log line, so every diagnostic of a load
 * stays in one place and this contract stays free of the loader's infrastructure.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 1.2.0
 */
@ApiStatus.Experimental
public interface ChunkVersionPolicy {

    /**
     * Checks the given chunk data and throws if the loader cannot read it.
     *
     * @param data               the root compound of the chunk
     * @param minimumDataVersion the lowest data version the loader was configured to accept
     * @throws ChunkDataException if the chunk cannot be read
     */
    void check(CompoundBinaryTag data, int minimumDataVersion) throws ChunkDataException;
}
```

- [ ] **Step 4: Move #45's body into the default**

`DefaultChunkVersionPolicy` takes the body of `requireReadableVersion` **unchanged in its decision
logic**: `versionMissing` from `data.get(DATA_VERSION_KEY) == null`, `versionMistyped`,
`legacyChunkLayout` from `!(… instanceof ListBinaryTag) && optionalCompound(Level) != null`, the same
early return, the same three message branches. What it drops is the diagnostics call and the log
line, which move to the loader per the decision above.

- [ ] **Step 5: Register it and wire the loader**

`falco-anvil/src/main/resources/META-INF/services/net.onelitefeather.falco.anvil.ChunkVersionPolicy`
holds one line: `net.onelitefeather.falco.anvil.DefaultChunkVersionPolicy`.

In `FalcoAnvilLoader`: one field `@Nullable ChunkVersionPolicy versionPolicy`, resolved in the
constructor through `ServiceResolution.choose(...)`. Two builder slots, threaded through **every**
setter. At the seam, `requireReadableVersion(data)` becomes:

```java
            if (this.versionPolicy != null) {
                checkVersion(data);
            }
```

where `checkVersion` calls the policy, catches `ChunkDataException`, does the counting and logging
#45 did, and rethrows.

Extend the existing startup log line with the resolved policy's class name or `none`.

- [ ] **Step 6: Run them and watch them pass**

Run: `./gradlew :falco-anvil:test`
Expected: PASS. **Every case #45 added must still pass** — they now exercise the default policy
through the loader instead of a private method, which is the point.

- [ ] **Step 7: Gegenprobe**

Two defects, one at a time, each reverted:

1. Make `checkVersion` swallow the exception instead of rethrowing. Every #45 refusal case must go
   red.
2. Delete the `META-INF/services` line. `testAPreRootLayoutChunkIsRefusedInsteadOfReadAsAir` must go
   red, because nothing is registered and nothing checks — **and that is the documented cost of the
   chosen default, reproduced on purpose.** Note in the report which cases went red; that list is
   what the acceptance quotes.

- [ ] **Step 8: Commit**

```bash
git add falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ \
        falco-anvil/src/main/resources/META-INF/services/ \
        falco-anvil/src/test/java/net/onelitefeather/falco/anvil/
git commit -m "feat(anvil): make the version guard a service the caller can replace"
```

---

### Task 3: The unknown-entry policy

**Files:**
- Create: `…/anvil/UnknownEntryPolicy.java`, `…/anvil/DefaultUnknownEntryPolicy.java`
- Modify: `…/anvil/BlockPaletteResolver.java`, `…/anvil/BiomePaletteResolver.java`, `…/anvil/FalcoAnvilLoader.java`
- Test: `…/anvil/UnknownEntryPolicyTest.java`

**Interfaces:**
- Consumes: `ServiceResolution.choose` from Task 1.
- Produces: `UnknownEntryPolicy` with
  `int onUnknownBlock(String name, @Nullable CompoundBinaryTag properties)` and
  `int onUnknownBiome(String name)`, both unchecked;
  `DefaultUnknownEntryPolicy`; builder slots `unknownEntryPolicy(...)` and `discoverUnknownEntryPolicy()`.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void testTheDefaultPolicyReplacesAnUnknownBlockWithAir() {
    assertEquals(Block.AIR.stateId(), new DefaultUnknownEntryPolicy().onUnknownBlock("falco:nope", null));
}

@Test
void testARefusingPolicyFailsTheChunkInsteadOfSubstituting() {
    UnknownEntryPolicy refusing = new UnknownEntryPolicy() {
        @Override public int onUnknownBlock(String name, CompoundBinaryTag properties) {
            throw new AnvilChunkException("The block " + name + " has no mapping");
        }
        @Override public int onUnknownBiome(String name) {
            throw new AnvilChunkException("The biome " + name + " has no mapping");
        }
    };

    AnvilChunkException failure = assertThrows(AnvilChunkException.class,
            () -> new BlockPaletteResolver(new AnvilDiagnostics(), refusing).toId("falco:nope", null));
    assertTrue(failure.getMessage().contains("falco:nope"), failure.getMessage());
}

@Test
void testTheResolverStillCountsWhenThePolicySubstitutes() {
    AnvilDiagnostics diagnostics = new AnvilDiagnostics();

    new BlockPaletteResolver(diagnostics, new DefaultUnknownEntryPolicy()).toId("falco:nope", null);

    assertEquals(1, diagnostics.unknownBlockCount());
}
```

Check `AnvilChunkException`'s public constructors before writing case two and use one that exists.
The third case pins that counting stays in the resolver and does not move into the policy — losing it
would make a substituting run silent.

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :falco-anvil:test --tests "*UnknownEntryPolicyTest*"`
Expected: compilation failure — the type and the two-argument resolver constructor do not exist.

- [ ] **Step 3: Create the interface and the default**

```java
/**
 * Decides what becomes of a palette entry the running server does not know.
 * <p>
 * Returning an id substitutes it; throwing {@link AnvilChunkException} fails the chunk. Substituting
 * is right for a server that wants a world to stay loadable and wrong for a tool that converts one,
 * which is why this is a policy and not a constant.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 1.2.0
 */
@ApiStatus.Experimental
public interface UnknownEntryPolicy {

    /**
     * Decides what an unknown block becomes.
     *
     * @param name       the block name stored in the palette
     * @param properties the stored properties, or null if the entry carries none
     * @return the state id to use instead
     * @throws AnvilChunkException if the chunk should fail rather than carry a substitute
     */
    int onUnknownBlock(String name, @Nullable CompoundBinaryTag properties);

    /**
     * Decides what an unknown biome becomes.
     *
     * @param name the biome name stored in the palette
     * @return the id to use instead
     * @throws AnvilChunkException if the chunk should fail rather than carry a substitute
     */
    int onUnknownBiome(String name);
}
```

`DefaultUnknownEntryPolicy` returns `Block.AIR.stateId()` and the plains id, exactly the values the
two resolvers hard-code today.

- [ ] **Step 4: Route the resolvers through it**

`BlockPaletteResolver` gains a second constructor argument. **Keep the one-argument constructor**,
delegating to the new one with the default policy — it is published API and removing it would break
`checkApiCompatibility`. In `toId`, the branch that today does

```java
            if (this.diagnostics.reportUnknownBlock(name)) {
                LOGGER.warn("The block '{}' is unknown and is replaced with air, …", name);
            }
            return Block.AIR.stateId();
```

keeps the reporting and the log **and** ends with `return this.policy.onUnknownBlock(name, properties);`.
The log wording must stop claiming "is replaced with air" unconditionally, because with a refusing
policy it is not. `BiomePaletteResolver` gets the same treatment.

Add the two builder slots to `FalcoAnvilLoader`, threaded through every setter, and pass the resolved
policy into the resolvers the loader constructs itself.

- [ ] **Step 5: Run them and watch them pass**

Run: `./gradlew :falco-anvil:test`
Expected: PASS, including every existing resolver test unchanged.

- [ ] **Step 6: Gegenprobe**

Remove the `reportUnknownBlock` call while keeping the policy call.
`testTheResolverStillCountsWhenThePolicySubstitutes` must go red alone. Revert.

- [ ] **Step 7: Commit**

```bash
git add falco-anvil/src/
git commit -m "feat(anvil): let a caller decide what an unknown palette entry becomes"
```

---

### Task 4: Acceptance

- [ ] **Step 1: Check the load, then run every module**

`uptime` before and after, both into the report. Then:

```bash
./gradlew :falco-anvil:test :falco-light:test :falco-instance:test \
          :falco-demo:test :falco-benchmarks:test :falco-archunit:test --rerun-tasks
```

Counts from the JUnit XML. No count may fall against the baseline recorded at the merge of #45.

- [ ] **Step 2: Build with javadoc and the API check**

```bash
./gradlew build -x test --rerun-tasks
```

Javadoc genuinely executed for all published modules, zero warnings, `checkApiCompatibility`
executed. **If japicmp flags anything, stop and record it verbatim** — the plan's claim is that every
change is additive, and a flag means that claim is wrong.

- [ ] **Step 3: Attack the gate**

Re-inject Task 2's Gegenprobe #2 — delete the `META-INF/services` registration — and confirm the full
suite catches it, not just one class. This is also the executable record of what the optional guard
costs. Revert; `git status --short` empty.

- [ ] **Step 4: Write the result into the plan and commit**

Append `## Result`: cases added per task, which injected defect each caught, module counts from the
XML, both load figures, and explicitly what this does **not** change — no module, no JPMS, no
published signature, and no behaviour at all when nothing is registered except that the guard is now
losable.

---

### Task 5: Documentation

- [ ] **Step 1: README**

One paragraph under the `falco-anvil` material: the loader has replaceable policies, the guard is one
of them, and it can be removed. Do not add a section.

- [ ] **Step 2: The wiki page**

Extend `Anvil-Chunk-Loader` in `/mnt/projects/oss/onelitefeather/Falco.wiki` — the page already has a
`### The version floor` subsection from #45, which is where this belongs. Cover all three services,
the three resolution rules, and **the consequence of removing the guard, in the same words the spec
uses.** No new page, so the sidebar is untouched.

- [ ] **Step 3: Commit both, separately**

The wiki is its own repository. Do not push either.

---

## Self-Review

**Spec coverage.** `ChunkVersionPolicy` → Task 2; `UnknownEntryPolicy` → Task 3; the three resolution
rules → Task 1, exercised again in 2 and 3; `falco-anvil`'s own registration → Task 2 Step 5; the
startup line → Task 2 Step 5; the Gegenprobe the spec explicitly asks for → Task 2 Step 7 case 2 and
Task 4 Step 3; documentation → Task 5. `ChunkMigrator` is out of scope and belongs to the migration
plan.

**Placeholders.** None. Two steps carry a verify-before-you-write instruction rather than an
assumption — `versionPolicy(null)` in Task 2 Step 1 and `AnvilChunkException`'s constructors in Task 3
Step 1 — because both depend on code this plan does not quote in full, and guessing them would put a
wrong call into a test.

**Type consistency.** `ServiceResolution.discover`/`choose` are named identically in Task 1's
Interfaces block, its code, and the consumers in Tasks 2 and 3. `ChunkVersionPolicy.check` takes
`(CompoundBinaryTag, int)` everywhere. `UnknownEntryPolicy` has exactly the two methods named in the
spec, both unchecked. `@since 1.2.0` throughout, because #45 already took the module to 1.1.0.

## Result

Acceptance run against `1664dcdd` (branch tip before this section), worktree
`/mnt/projects/oss/onelitefeather/Falco-worktrees/anvil-extension-points`, base `1bdd0cca` (#45,
merged into `origin/main`). The branch had already been rebased onto `origin/main` and the 19 `@since`
tags redated to `2.1.0` before this acceptance began (`1664dcdd`); both were re-verified below rather
than taken on faith.

### `@since`/`@version` check (redone, not assumed)

`grep -rn "@since 1.2.0" falco-anvil/src/main` returns nothing — no stale tag survived the rebase.
`grep -c "@since 2.1.0"` finds 17 in `falco-anvil/src/main` plus 2 more in the two new test classes
(`ChunkVersionPolicyTest`, `UnknownEntryPolicyTest`), 19 total, matching commit `1664dcdd`'s own
"Nineteen tags across ten files" exactly. `@version` tags on the touched types are untouched by that
commit and still read as the individual tasks left them (`1.0.0`–`1.3.0` depending on the type) — they
count class revisions, not the artefact version, per the commit's own message.

### Cases added per task (measured, not estimated)

22 new `@Test` methods, confirmed by `git diff 1bdd0cca..HEAD -- falco-anvil/src/test` (22 `+` lines
carrying `@Test`, 0 removed) and cross-checked per file against the `<testcase>` count in this run's
JUnit XML:

- Task 1 (`ServiceResolution`) — `ServiceResolutionTest.java` (new file): **7** —
  `testAServiceWithNoProviderResolvesToNothing`, `testTwoProvidersAreRefusedAndBothAreNamed`,
  `testAnExplicitInstanceAndDiscoveryTogetherAreRefused`,
  `testAnExplicitInstanceIsUsedWithoutTouchingTheClasspath`,
  `testNeitherExplicitNorDiscoveredResolvesToNothing` (the original five), plus
  `testAForeignProviderWinsOverTheShippedDefault` and
  `testTwoForeignProvidersAreStillRefusedEvenWithAShippedDefaultRegistered` (added during Task 2's
  review fix round, since the shipped-default-yields rule lives in `ServiceResolution`).
- Task 2 (`ChunkVersionPolicy`) — **7**: `ChunkVersionPolicyTest.java` (new file, 3 cases —
  `testTheDefaultPolicyRefusesALevelLayout`, `testTheDefaultPolicyAcceptsAChunkWithoutAStoredVersion`,
  `testTheDefaultPolicyRefusesAMistypedVersion`); `FalcoAnvilLoaderIntegrationTest` +2
  (`testAPolicyThatAllowsEverythingLetsALegacyChunkThrough`,
  `testWithoutAnyPolicyALegacyChunkIsNotChecked`); `FalcoAnvilLoaderBuilderTest` +2
  (`testAnExplicitVersionPolicySurvivesEveryOtherSetter`,
  `testDiscoverVersionPolicySurvivesEveryOtherSetterAfterClearingAnExplicitOne`).
- Task 3 (`UnknownEntryPolicy`) — **8**: `UnknownEntryPolicyTest.java` (new file, 6 cases — the 3
  original block cases plus 3 biome mirrors added in the review fix round —
  `testTheDefaultPolicyReplacesAnUnknownBlockWithAir`,
  `testARefusingPolicyFailsTheChunkInsteadOfSubstituting`,
  `testTheResolverStillCountsWhenThePolicySubstitutes`,
  `testTheDefaultPolicyReplacesAnUnknownBiomeWithPlains`,
  `testARefusingPolicyFailsTheChunkInsteadOfSubstitutingForABiome`,
  `testTheResolverStillCountsWhenThePolicySubstitutesForABiome`); `FalcoAnvilLoaderBuilderTest` +2
  (`testAnExplicitUnknownEntryPolicySurvivesEveryOtherSetter`,
  `testDiscoverUnknownEntryPolicySurvivesEveryOtherSetterAfterClearingAnExplicitOne`).

Total: 22 identified by name. The measured `falco-anvil` module delta from the JUnit XML is **+23**
(230 → 253, see table below), one higher than the 22 named methods above. Every individual touched
file's `<testcase>` count reconciles exactly against the per-task breakdown its own task report
recorded (`ServiceResolutionTest` 7, `ChunkVersionPolicyTest` 3, `UnknownEntryPolicyTest` 6,
`FalcoAnvilLoaderIntegrationTest` 35 total against a recorded baseline of 33,
`FalcoAnvilLoaderBuilderTest` 20 total against a recorded baseline of 16) — so the one-test gap sits in
the *baseline* figure (230) carried over from the prior acceptance report, not in anything this branch
added. Flagged for transparency rather than silently rounded; it does not change the "nothing fell"
conclusion, since it only moves in the direction of "more tests than accounted for," not fewer.

### Gate attack 1 — delete the `ChunkVersionPolicy` service registration

Removed `falco-anvil/src/main/resources/META-INF/services/net.onelitefeather.falco.anvil.ChunkVersionPolicy`
and ran the **full** `:falco-anvil:test` module (253 cases, not a filtered class):

```
253 tests completed, 6 failed
```

- `FalcoAnvilLoaderIntegrationTest.testAPreRootLayoutChunkIsRefusedInsteadOfReadAsAir` — the specific
  regression this whole plan (and #45 before it) exists to prevent: a pre-21w43a chunk goes back to
  loading as air, silently.
- `FalcoAnvilLoaderIntegrationTest.testAChunkBelowTheFloorIsRefused`
- `FalcoAnvilLoaderIntegrationTest.testASectionsKeyStoredAsTheWrongTypeWithLevelIsRefused`
- `FalcoAnvilLoaderIntegrationTest.testAChunkWithADataVersionStoredAsTheWrongTypeIsRefused`
- `FalcoAnvilLoaderIntegrationTest.testAChunkWithANegativeDataVersionIsRefused`
- `FalcoAnvilLoaderBuilderTest.testDiscoverVersionPolicySurvivesEveryOtherSetterAfterClearingAnExplicitOne`
  — new in this run relative to Task 2's own Gegenprobe: with nothing registered, `discoverVersionPolicy()`
  resolves to `null` instead of a `DefaultChunkVersionPolicy` instance, so the pass-through assertion
  fails too. This is the executable proof that the failure mode reaches beyond the five rejection cases
  into the builder's own contract test — two classes, not one.

This is also the executable record of what the optional guard costs: with no `ChunkVersionPolicy`
provider on the classpath, every chunk that would have been refused loads instead, unchecked, and a
caller who explicitly asked to fall back to discovery silently gets nothing.

Reverted (`git checkout -- falco-anvil/src/main/resources/META-INF/services/...ChunkVersionPolicy`).
`git status --short` empty afterward.

### Gate attack 2 — drop the two new policy fields from one builder setter

In `FalcoAnvilLoader.Builder.openRegionLimit(int)`, replaced the trailing constructor arguments
`this.versionPolicy, this.discoverVersionPolicy, this.unknownEntryPolicy, this.discoverUnknownEntryPolicy`
with `null, true, null, true` — simulating "forgot to thread the new fields through this setter."
Ran `FalcoAnvilLoaderBuilderTest`:

```
20 tests completed, 2 failed
```

- `testAnExplicitVersionPolicySurvivesEveryOtherSetter` — chaining `.versionPolicy(policy).openRegionLimit(4)…`
  now loses the explicit policy at the mutated setter.
- `testAnExplicitUnknownEntryPolicySurvivesEveryOtherSetter` — same loss for the other policy.

Both pass-through tests went red, exactly the ones the mutation should bite; the other 18 cases,
including the two `discover…SurvivesEveryOtherSetter` tests (whose expected post-mutation state
happens to coincide with the mutation's fallback), stayed green. Reverted
(`git checkout -- falco-anvil/.../FalcoAnvilLoader.java`); `git diff --stat` before the revert showed
exactly the one intended file, `git status --short` empty after. Re-ran the full `falco-anvil` module
afterward: 253/253 green, no residue.

### Module counts (from JUnit XML under `build/test-results/test/`, `<testcase>` elements counted
directly — not the console summary, not the `testsuite` summary attribute, though the two agreed on
every file in this run)

| Module | Baseline at `1bdd0cca` (#45, from the prior acceptance report¹) | Count now | Delta |
| --- | --- | --- | --- |
| falco-anvil | 230 | 253 | +23 |
| falco-light | 223 | 223 | 0 |
| falco-instance | 259 | 259 | 0 |
| falco-demo | 167 | 167 | 0 |
| falco-benchmarks | 42 (1 skipped) | 42 (1 skipped) | 0 |
| falco-archunit | 47 | 47 total, **4 failing** | **regression** |

No count *fell* in the sense of fewer test cases existing. **falco-archunit is the exception that
matters**: total case count held at 47, but 4 that passed at the baseline now fail — see "Defect
found" below.

¹ `docs/superpowers/plans/2026-08-03-anvil-version-guard.md`, its own `## Result` section, measured at
branch tip `a74cd042` (which became `1bdd0cca` on `origin/main` via #45) — reused per this task's
instruction to take baseline numbers from the last acceptance report of an earlier undertaking rather
than standing up a second worktree. That report itself notes light/instance/demo/archunit at its
baseline commit `2d3955d8` were, by construction, identical to `1bdd0cca` (the branch touched only
`falco-anvil` files), so these five numbers are read as valid for `1bdd0cca` directly. No new worktree
was created for this acceptance.

### Defect found: `falco-archunit`'s `ForeignCouplingTest` was never updated for the new policy classes

**Not fixed — reported, per this task's explicit instruction not to silently repair a real defect found
during acceptance.**

`./gradlew :falco-anvil:test :falco-light:test :falco-instance:test :falco-demo:test
:falco-benchmarks:test :falco-archunit:test --rerun-tasks` fails on `:falco-archunit:test`. Four cases
in `net.onelitefeather.falco.architecture.ForeignCouplingTest` go red:

- `anvilCoreKnowsNoMinestom` (9 violations)
- `blockRegistryOnlyInAdapters` (1 violation)
- `dynamicRegistryOnlyInBiomeResolver` (3 violations)
- `byteLayerKnowsNoNbt` (9 violations)

All four rules use an allow-list regex naming the specific classes in `net.onelitefeather.falco.anvil`
that are permitted to depend on Minestom/Kyori-NBT types, e.g. (`ForeignCouplingTest.java:52`):

```java
+ "(FalcoAnvilLoader|BlockPaletteResolver|BiomePaletteResolver)(\\$.*)?";
```

Tasks 1–3 added `ChunkVersionPolicy`, `DefaultChunkVersionPolicy`, `UnknownEntryPolicy` and
`DefaultUnknownEntryPolicy` to `net.onelitefeather.falco.anvil`. Three of these four legitimately touch
the types the rule forbids — `DefaultChunkVersionPolicy` reads `CompoundBinaryTag` (needed to decide
chunk readability), `DefaultUnknownEntryPolicy` calls `Block.AIR`/`Block.AIR.stateId()` and
`MinecraftServer.getBiomeRegistry()`/`DynamicRegistry.getId(...)` (needed to reproduce the exact
air/plains fallback the two resolvers used to hard-code), and `UnknownEntryPolicy`'s own interface
method signature carries a `CompoundBinaryTag` parameter. None of these four classes is named in the
allow-list regex, so every one of these — architecturally intentional — dependencies now reads as a
violation.

**Why none of the three implementation tasks caught this themselves:** each task's own verification ran
`:falco-anvil:test` (and `javadoc`/`checkApiCompatibility`), never `:falco-archunit:test` — that module
lives outside `falco-anvil` and was outside each task's own file list. This acceptance is the first point
in the plan that runs all six modules together, which is exactly why it exists.

This is a real defect against the plan's own claim of "no module, no JPMS, no published signature" —
the *behaviour* is unchanged, but the *architecture rule* meant to keep `falco-anvil`'s core free of a
running-server dependency no longer reflects where the boundary actually is now that policies live
inside that same package. Left unfixed here; the two defensible directions for whoever picks this up
are (a) widen the allow-list regex to include the four new classes, or (b) move
`DefaultChunkVersionPolicy`/`DefaultUnknownEntryPolicy` to a place the existing rules already permit —
a design call outside this acceptance's scope.

### `./gradlew build -x test --rerun-tasks`

`BUILD SUCCESSFUL`. `javadoc` genuinely executed (no `UP-TO-DATE`, full `--rerun-tasks` output grepped
for "warning" case-insensitively: zero matches) for the four modules that carry a javadoc task:
falco-anvil, falco-light, falco-instance, falco-demo. `checkApiCompatibility` genuinely executed for
the three configured modules — falco-anvil, falco-light, falco-instance. All three reports read
literally:

```
Comparing binary compatibility of falco-anvil-1.0.0.jar against falco-anvil-1.0.0.jar
No changes.
```

(same for `falco-light` and `falco-instance`, each against its own `-1.0.0.jar`). `onlyBinaryIncompatibleModified`
is `true` for this task, so purely-additive surface (every new type and setter this plan added) does
not appear here by design — consistent with every prior task's own japicmp run. No exception entry was
needed in `gradle/api-breaks.properties` and none was added.

### Machine load

`uptime` before the module run (14:39:57): `load average: 4.17, 5.18, 3.56`
`uptime` after the gate attacks and the full re-verification run (14:45:49): `load average: 8.68, 8.57,
5.79`

No timing figure was produced or is quoted anywhere in this section.

### What this work does not do

- No new module. Everything lives in `net.onelitefeather.falco.anvil`, in the six existing published
  modules.
- No `module-info.java`, no JPMS. Plain classpath `ServiceLoader`.
- No published signature was removed or changed — `checkApiCompatibility` confirms this literally
  ("No changes.") for all three configured modules.
- No behaviour changes for a caller who registers nothing and calls the builder as before:
  `discoverVersionPolicy`/`discoverUnknownEntryPolicy` default to `true`, so `builder()` and the two
  public constructors resolve `DefaultChunkVersionPolicy`/`DefaultUnknownEntryPolicy` exactly as #45's
  guard and the two resolvers' hard-coded fallbacks always did — **except that the guard is now
  losable**: deleting one `META-INF/services` line (Gate attack 1, above) turns the same "default"
  configuration into "no check at all," which was not possible before this plan. That loss is a
  deliberate project decision (documented in Task 2's own report and re-confirmed by this run), not an
  oversight.

### Status

**DONE_WITH_CONCERNS.** All four acceptance steps ran to completion; every module's test count held or
grew; javadoc and `checkApiCompatibility` are clean; both gate attacks landed exactly where expected and
both were reverted cleanly. The concern is the `falco-archunit` regression above — a real defect,
reported rather than silently repaired, that needs a follow-up decision before this branch should be
considered fully clean.
