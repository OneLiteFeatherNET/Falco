# Extension points for the Anvil loader

Design of 2026-08-04. `falco-anvil` turns three hard-wired decisions into services a caller can
replace: the version guard, the fallback for an unknown palette entry, and — already specified
separately — chunk migration. All three use the platform `ServiceLoader`.

Sits between [#45](https://github.com/OneLiteFeatherNET/Falco/pull/45), which built the guard, and
`falco-migration`, which needs the third of them. `2026-08-04-falco-migration-design.md` specifies
`ChunkMigrator` and is not repeated here.

## Why

Two behaviours in the loader are decisions the project made for its own use and then fixed in code:

1. **Which worlds are readable.** #45 refuses anything below `DEFAULT_MINIMUM_DATA_VERSION` or
   carrying a `Level` compound. That is right for a server that only wants worlds it can read, and
   wrong for a tool that wants to inspect one it cannot.
2. **What an unknown block or biome becomes.** `BlockPaletteResolver` substitutes air, and
   `BiomePaletteResolver` substitutes plains, both counting the substitution in `AnvilDiagnostics`.
   For a server that keeps a world loadable this is the reasonable last resort. For anything that
   converts or audits a world it is the wrong reaction, because a substitution that is counted is
   still a substitution written back on the next save.

Neither is wrong. Both are policy, and policy that only one consumer can choose is not policy.

`PaletteEntryResolver` already proves the shape: an interface in `falco-anvil`, handed in through the
builder. What it does not offer is discovery — a consumer must know the type exists and wire it. The
services below add that, and keep the builder slot as the explicit route.

## Decisions

| Question | Decision |
| --- | --- |
| Mechanism | Plain classpath `ServiceLoader`. No `module-info.java` exists in this project and none is added |
| Guard default | **None. No provider means no version check** |
| Guard shipped by `falco-anvil` | Yes, as a registered provider of its own |
| Fallback default | The current behaviour: air and plains, counted |
| Explicit route | Kept. A builder slot overrides discovery for every service |
| API compatibility | Additive only. No existing signature changes |

**The guard is fully optional, and that is the project owner's decision, taken with its consequence
stated.** The consequence: a loader with no guard provider reads a pre-21w43a world as air again,
with no error and no log line — the defect #45 exists to close. This spec does not soften that. What
it does is make the state visible rather than silent:

- `falco-anvil` registers its own guard in its `META-INF/services`, so a normal dependency on the
  module has the guard. Losing it takes an exclusion somebody writes, not a classpath that happens to
  be empty.
- The loader's existing startup line — which already reports the chosen region layout, and exists
  because "without this line the choice between the two layouts happens invisibly" — gains the guard
  it resolved, or the word `none`.

## The services

### `ChunkVersionPolicy`

```java
public interface ChunkVersionPolicy {

    void check(CompoundBinaryTag data, int minimumDataVersion) throws ChunkDataException;
}
```

Called at the seam where `requireReadableVersion` is called today. The built-in implementation is the
body #45 wrote, moved rather than rewritten: layout first, version second, one `Reason`, the
diagnostics counter.

A policy that wants to allow everything implements an empty body. A policy that wants a different
floor reads its own configuration. The interface deliberately takes the whole compound, not a version
number, because the layout check does not rest on a version at all.

### `UnknownEntryPolicy`

```java
public interface UnknownEntryPolicy {

    int onUnknownBlock(String name, @Nullable CompoundBinaryTag properties) throws ChunkDataException;

    int onUnknownBiome(String name) throws ChunkDataException;
}
```

Consulted by `BlockPaletteResolver` and `BiomePaletteResolver` where they substitute today. Returning
an id substitutes it; throwing fails the chunk. The built-in implementation returns air and plains and
keeps the existing counting, so behaviour without a provider is byte-for-byte what it is now.

This is the seam `falco-migration` needs: a converter installs a policy that throws, because on the
upgrade path an unmappable block means the mapping data is incomplete and must be seen.

### Resolution rules, shared by all three services

Applied uniformly to every service here:

- **The shipped default steps aside for a foreign provider.** `falco-anvil` registers its own
  implementations, so without this rule a third party taking the documented route would *always*
  produce two providers and always hit the refusal below. Discovery could never return anything but
  the default, and the extension point would be decoration. A named default is therefore removed from
  the candidate set before the candidates are counted. **Added after the first implementation review
  found the point unusable as this document originally specified it.**
- **More than one *foreign* provider throws**, naming them — the default is not among the names,
  because by then it is not a candidate. Silent selection between two foreign providers is how a
  world gets read under a policy nobody chose.
- **The builder slot and discovery are exclusive.** Setting both is a configuration error.
- **A builder slot always wins over the classpath** when only it is used — that is what "explicit"
  means, and it short-circuits before the class path is consulted at all.
- **Discovery loads with the service's own class loader**, not the thread context loader. Under
  CloudNet, extension or plugin class loaders the context loader may not see the `falco-anvil` jar;
  discovery would then find nothing, resolve to no policy, and put the pre-21w43a air chunk back —
  silently, which is the failure mode this whole line of work exists to end. The contract and its
  shipped provider live in the same module, so that module's loader is the one to ask.

Discovery happens once, when the loader is built, not per chunk.

## What this does not do

- **No behaviour change with no provider present**, for the fallback. Air and plains, counted, exactly
  as today.
- **A behaviour change for the guard**, and it is the point of the change: the guard becomes losable.
  See the consequence above.
- **No new module.** All three interfaces live in `falco-anvil`.
- **No JPMS.** Plain classpath services.
- **`ChunkMigrator` is not specified here** — see the migration design.

## Evidence

- One case per service that the built-in provider is used when nothing is registered, and that
  behaviour matches the current tree. For the fallback this is a regression guard on all of #45's
  and the existing resolver tests; for the guard it is the assertion that `falco-anvil`'s own
  registration is found.
- One case per service that a registered provider replaces the built-in one.
- One case that two providers throw, and one that builder slot plus discovery throws.
- **The Gegenprobe that matters:** remove `falco-anvil`'s own guard registration and assert that a
  pre-21w43a world loads as air again. That test documents the cost of the chosen default in
  executable form, so nobody has to take this document's word for it.

## Open for the plan

- Whether `ChunkVersionPolicy` and `UnknownEntryPolicy` are one service or two. They are written as
  two here because they answer unrelated questions and a consumer will usually want one, not both.
- Where the built-in providers live: a package of their own, or beside the interfaces.
- Whether `AnvilDiagnostics` gains a counter for "policy replaced", so a run says it did not use the
  defaults.
