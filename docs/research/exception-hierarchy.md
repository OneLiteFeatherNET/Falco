# Research: exception hierarchy for the Anvil package

**Question.** Replace the generic JDK exceptions in `net.onelitefeather.falco.anvil` with a
dedicated hierarchy that has both checked and unchecked types, so that "the server never loads a
world in a strange state".

**Status.** Design complete, not implemented. One decision is open and is documented below.

Three agents worked on this: hierarchy semantics, a complete catalogue of every throw site, and the
world-consistency guarantees. They agreed on almost everything and disagreed on one point that
changes the whole migration.

## What Java allows here

Verified by compiling probe code with javac 25.0.3.

**A common root over checked *and* unchecked is impossible as a class.** Java has exactly two roots,
`Exception` and `RuntimeException`, and a class cannot be both. The only bracket is an interface —
but it cannot be caught:

```
catch (AnvilFault f)
  -> error: incompatible types: AnvilFault cannot be converted to Throwable
```

An interface therefore only helps *after* a broad catch, for `instanceof` or a pattern switch. It is
not a way to catch both families in one clause. This is worth stating plainly because the opposite
is a natural assumption.

**Sealed exceptions compile, but give no exhaustiveness in `catch`.** A `try` block that catches both
permitted subtypes still does not satisfy the compiler; a catch of the sealed supertype remains
necessary. Java has no exhaustiveness analysis for catch clauses. Sealing is still worth it for two
other reasons: it prevents a downstream project from breaking a pattern switch, and the repository
already uses `sealed` in eight places (`MapEntry`, `ClickHolder`, `IItem`, `InventoryLayout`, …).

**All sealed members must live in one package.** Falco has no `module-info.java`, so it runs in the
unnamed module, where a sealed class may only permit subtypes from its own package. Verified:

```
error: class Root in unnamed module cannot extend a sealed class in a different package
```

Consequence: either every exception type moves into `…anvil.exception`, or all stay in `…anvil`.
A mix breaks compilation. Moving `AnvilChunkException` is safe — it is `@since 1.16.0` and the
released version is 1.15.2, so nothing depends on it yet.

**`java.nio.file.Path` must not be stored in an exception field.** It is not serializable
(`NotSerializableException: sun.nio.fs.UnixPath`, verified), which would make the exception
unserializable. Store the region path as a `String`.

**No `serialVersionUID`.** The repository has zero occurrences, the build sets no `-Xlint`, and these
exceptions never leave the process.

## The open decision: does the checked root extend `IOException`?

The two agents that looked at this reached opposite conclusions, and both arguments are sound.

**For extending `IOException`** — it is a migration question. Roughly 40 `throws` clauses, both
multi-catches in `FalcoAnvilLoader`, and 14 `assertThrows(IOException.class)` assertions in the tests
keep working untouched. The change becomes additive.

**Against extending `IOException`** — it is a correctness question. Every existing
`catch (IOException)` would silently keep catching the new types, including the swallowing block in
`saveChunk`. The migration would then be compile-time only and change nothing at runtime. The cost
(8 signatures in `main`) is exactly what the compiler is for.

This is a genuine trade-off between migration cost and enforcement, not a case where one side is
wrong. It needs a decision before implementation.

## Proposed types

The variant below is the one both agents converged on apart from the inheritance question. Six types
for thirteen classes; both agents explicitly warned against more, since a type nobody catches is
Javadoc maintenance without a decision point.

| Type | Kind | Purpose |
| --- | --- | --- |
| `AnvilFault` | sealed interface | Common contract, carries the `ChunkLocation`. Not catchable — for pattern switches after a broad catch. |
| `AnvilFormatException` | checked, abstract sealed | Root for everything the file itself got wrong. |
| `RegionFormatException` | checked, final | Broken `.mca` structure: header too short, implausible length field, overlapping sectors, unsupported compression scheme. |
| `ChunkDataException` | checked, final | Broken chunk NBT: missing key, wrong type, empty palette, index outside the palette. |
| `AnvilChunkException` | unchecked, non-sealed | The boundary type. Exists already; gains `implements AnvilFault`. |
| `ChunkLocation` | record | `(chunkX, chunkZ, region, dimension)` — the single definition of the log context. |

`AnvilChunkException` must be declared `non-sealed` once it implements the sealed interface — a
compiler requirement, and correct in substance since downstream may subclass it.

## Rules the implementation has to follow

1. **Exactly one translation point** from checked to unchecked: the catch in `FalcoAnvilLoader.loadChunk`,
   and its counterpart in `saveChunk`. No other class may wrap checked into unchecked, or the origin
   is lost and the double report to the `ExceptionManager` returns.
2. **Programmer errors keep the JDK types.** `BitPacker`, `SectorAllocator` and `PaletteData.singleValue`
   keep throwing `IllegalArgumentException` / `IllegalStateException`. Nobody catches them, they lead
   into no recovery path, and most are provably unreachable from the disk path.
3. **One exception to rule 2:** `SectorAllocator.reserve` rejecting overlapping sectors *is* reachable
   from the disk path via `RegionFile.readHeader`. It is corruption detection, not a programmer error,
   and is translated at the `RegionFile` boundary — without changing the allocator, which would cost
   it its file independence.
4. **Real IO stays `java.io.IOException`.** There is no explicit throw site for it in the package;
   `FileChannel`, `Files` and `channel.force` produce it implicitly. Wrapping adds no knowledge.
5. **No throw without a location.** Every message from the format branch names at least the region
   path and the chunk coordinate. `RegionFile` already does this; `NbtReads` and `PaletteData` name
   only the key or the number and must have the location passed in — otherwise the tick log reads
   `palette must hold at least one entry` with no hint which file it came from.
6. **The context format is defined once**, in `ChunkLocation.toString()`. Exception messages must not
   repeat the coordinates as text, or today's double formatting persists.
7. **Never pass coordinates into `NbtReads`, `PaletteData`, `SectionCodec`, `BitPacker`.** These are
   deliberately format-only and testable without a running server. The location is attached at the
   loader boundary.

## Style

New package `…anvil.exception` with a `package-info.java` in the exact repository form
(`@NotNullByDefault` + package + import, four lines). Class Javadoc starting with `The {@link X} is …`
explaining the *why*, plus `@author` / `@version` / `@since`. Constructors `(String)` and
`(String, Throwable)`; the `(Throwable)`-only constructor is deliberately omitted for the format
exceptions, because a format violation without a description is useless.
