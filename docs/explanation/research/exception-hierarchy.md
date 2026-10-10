# Research: exception hierarchy for the Anvil package

**Question.** Replace the generic JDK exceptions in `net.onelitefeather.falco.anvil` with a
dedicated hierarchy that has both checked and unchecked types, so that "the server never loads a
world in a strange state". This page is the design that came out of that question, kept as the record
of it.

> **Implemented in [#21](https://github.com/OneLiteFeatherNET/Falco/pull/21).** The open decision was
> made — the checked root does **not** extend `IOException` — and twelve of the fourteen bare throws
> now carry a type and a reason. Three statements below did not survive contact with the code and are
> corrected in place: the new package, the type count, and an architecture rule the design could not
> know about. **Where this page and the code disagree, the code wins.**

**Method.** Three agents worked on this — hierarchy semantics, a complete catalogue of every throw
site, and the world-consistency guarantees. The language rules below were established by compiling
probe code, which the investigation records as javac 25.0.3; the probes are not checked in. The
counts over the package were established by reading `falco-anvil/src`. They agreed on almost
everything and disagreed on one point that changes the whole migration.

**Established.** What Java permits and forbids for a mixed checked/unchecked hierarchy — four hard
rules, each with the compiler error that produces it. A proposed set of six types. Seven rules the
implementation would have to follow.

**Open when written.** Whether the checked root extends `IOException`, and whether moving
`AnvilChunkException` into a new package is acceptable. Both are settled now: the root does not
extend `IOException`, and nothing moved — the package could not exist (see *Style*).

**True at.** Written 2026-07-31, entered the repository at
[`fc0aef5`](https://github.com/OneLiteFeatherNET/Falco/commit/fc0aef5). The counts and the two
corrections below were re-checked against `falco-anvil/src` at `ca79507`. The implementation followed
in #21; read the quote boxes for where it departed from this page.

> **Two premises of this document were imported from another codebase and do not hold for Falco.**
> They are corrected in place below, with the source citation that replaces them. One of them
> reverses the conclusion it was supporting. Everything else on the page survived the re-check.

## What Java allows here

Verified by compiling probe code, recorded by the investigation as javac 25.0.3.

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
necessary. Java has no exhaustiveness analysis for catch clauses. One reason to seal anyway survives:
it prevents a downstream project from breaking a pattern switch by adding a subtype.

**Correction.** The document originally offered a second reason — that the repository "already uses
`sealed` in eight places (`MapEntry`, `ClickHolder`, `IItem`, `InventoryLayout`, …)". **Falco
declares no `sealed` type at all**, verified at `ca79507`: `grep -rn sealed` over `falco-anvil/src`,
`falco-light/src` and `falco-instance/src` returns nothing, and none of the four named types exists
in this project. That premise was carried in from another codebase. Sealing the hierarchy would
therefore be the first use of `sealed` here, not a continuation of house style — which does not make
it wrong, but removes the argument that it is already settled practice.

**All sealed members must live in one package.** Falco has no `module-info.java`, so it runs in the
unnamed module, where a sealed class may only permit subtypes from its own package. Verified:

```
error: class Root in unnamed module cannot extend a sealed class in a different package
```

Consequence: either every exception type moves into `…anvil.exception`, or all stay in `…anvil`.
A mix breaks compilation.

**Correction, and it reverses the conclusion.** The document originally argued that moving
`AnvilChunkException` is safe "because it is `@since 1.16.0` and the released version is 1.15.2, so
nothing depends on it yet". Both figures belong to another codebase. At `ca79507`,
[`AnvilChunkException`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilChunkException.java)
carries `@since 0.1.0` and Falco's released version is `0.3.0`, so the type has shipped in every
release the project has made and moving it is a source- and binary-breaking change for anyone who
catches it by name. What does license a move is a different fact on the same file: the class is
annotated `@ApiStatus.Experimental`, and its own Javadoc says the API "may still change while it is
being validated against real worlds". That is an argument the maintainer can accept or reject; it is
not the free move the original sentence described.

<!-- TODO(maintainer): decide whether AnvilChunkException may move to …anvil.exception now that it is
     known to be a released, @ApiStatus.Experimental type rather than an unreleased one. The
     alternative is to keep all six types in …anvil, which the sealing rule above permits. -->

**`java.nio.file.Path` must not be stored in an exception field.** It is not serializable
(`NotSerializableException: sun.nio.fs.UnixPath`, verified), which would make the exception
unserializable. Store the region path as a `String`.

**No `serialVersionUID`.** The repository has zero occurrences across all five modules, re-checked at
`ca79507`; the build sets no `-Xlint`, and these exceptions never leave the process.

## The open decision: does the checked root extend `IOException`?

The two agents that looked at this reached opposite conclusions, and both arguments are sound.

**For extending `IOException`** — it is a migration question. The 40 `throws` clauses in
`falco-anvil/src/main`, both `catch (IOException | RuntimeException)` multi-catches in
`FalcoAnvilLoader`, and the 14 `assertThrows(IOException.class)` assertions in the tests keep working
untouched. The change becomes additive. All three counts were re-checked at `ca79507` and are exact,
not approximate; they were also exact when the investigation ran at `fc0aef5`.

**Against extending `IOException`** — it is a correctness question. Every existing
`catch (IOException)` would silently keep catching the new types, including the swallowing block in
`saveChunk`. The migration would then be compile-time only and change nothing at runtime. The cost
(8 signatures in `main`) is exactly what the compiler is for.

This is a genuine trade-off between migration cost and enforcement, not a case where one side is
wrong. It needs a decision before implementation.

> **Decided against extending, in [#21](https://github.com/OneLiteFeatherNET/Falco/pull/21).** The
> correctness argument won on one detail this section already names: one of the multi-catches is the
> swallowing block in `saveChunk`. Under an `IOException` root the new types would keep being
> swallowed exactly there — in the path where a silent failure costs a whole chunk — and the
> migration would have been a compile-time rename. The 8 signatures were the price for the compiler
> naming every site, and the compiler turned out to be blunter than the argument: `fault instanceof
> IOException` does not even compile, the types being unrelated.
>
> Two counts in this section were slightly off against the code at implementation time: the
> multi-catches are **three**, not two — `RegionFile.java:161` besides the two in `FalcoAnvilLoader`
> — while the 40 `throws` clauses and the 14 `assertThrows(IOException.class)` were exact.

## Proposed types

The variant below is the one both agents converged on apart from the inheritance question. Six types
for the thirteen classes of the package that are not themselves an exception — the package holds 14
classes plus its `package-info.java` at `ca79507`, of which only `AnvilChunkException` is an
exception today. Both agents explicitly warned against more types, since one nobody catches is
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

> **Built as designed, plus two `Reason` enums.** The six types are exactly these six. What the table
> does not show is how a caller tells one *case* from another: not by a type per case, which both
> agents warned against, but by a constant on the type. `RegionFormatException.Reason` has five,
> `ChunkDataException.Reason` six. Adding a detection is an added constant rather than an added type
> that has to be published, documented and caught.
>
> The sixth chunk-data reason is one this page does not list. `NbtReads` throws through a single
> `missing()` helper rather than at its six call sites, so "a key is absent or holds the wrong tag"
> is a distinct failure that the catalogue of fourteen throw sites had folded away. The compiler
> surfaced it during the conversion.

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

> **The new package is impossible, and that is a language rule rather than a preference.** Without a
> module system a `sealed` type may only permit subtypes of its own package; `javac` rejects
> `permits b.Boom` from package `a` outright. `AnvilFault` therefore has to live beside
> `AnvilChunkException`, and the alternative — moving `AnvilChunkException` into the new package —
> is a binary break for a package name. Everything lives in `net.onelitefeather.falco.anvil`, next
> to the classes that throw it. The rest of this section held: the javadoc form, and the omitted
> `(Throwable)`-only constructor.

New package `…anvil.exception` with a `package-info.java` in the exact repository form
(`@NotNullByDefault` + package + import, four lines). Class Javadoc starting with `The {@link X} is …`
explaining the *why*, plus `@author` / `@version` / `@since`. Constructors `(String)` and
`(String, Throwable)`; the `(Throwable)`-only constructor is deliberately omitted for the format
exceptions, because a format violation without a description is useless.

## What the design could not know: an architecture rule forbade it

`falco-archunit` did not exist when this page was written; it arrived in
[#12](https://github.com/OneLiteFeatherNET/Falco/pull/12), a fortnight later. One of its rules,
`ownExceptionsAreUncheckedAndCarryACause`, required **every** Falco throwable to extend
`RuntimeException` — which forbids the checked branch this whole design rests on.

Its reasoning is sound but narrower than its scope: `saveChunk` and `loadChunk` override Minestom's
`ChunkLoader` methods and declare no `throws`, so a checked exception cannot be thrown *at that
boundary*. That says nothing about types which never reach it — and rule 1 above exists precisely to
keep them from reaching it. The rule was written when the project had one exception, and generalised
an observation about `AnvilChunkException` to every `Throwable`.

It was narrowed to the unchecked types, and three rules were added so the exemption does not become a
hole. Two of them enforce what this page could only state as prose:

| Rule | What it pins |
| --- | --- |
| `checkedFaultsStayInsideTheHierarchy` | checked is allowed only inside the format branch |
| `exactlyOneTranslationPoint` | rule 1 of *Rules the implementation has to follow*, machine-checked |
| `formatClassesDoNotThrowBareIoExceptions` | the four byte-reading classes cannot return to a generic throw |

41 rules before, 42 after. Each was verified by deliberately breaking it — a foreign checked
exception in `falco-light`, a second translation point in `RegionFile`, a re-added bare
`IOException` in `NbtReads`. That last check earned its keep: the first version of the rule was green
while two bare throws stood in the package, because `target(owner(name(...)))` does not match a
constructor owner the way `nameMatching` does.

## References

- [`falco-anvil/src/main/java/net/onelitefeather/falco/anvil/`](https://github.com/OneLiteFeatherNET/Falco/tree/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil)
  — the 14 classes and the `package-info.java` this design would cover, and the source of the 40
  `throws` clauses, the two multi-catches in `FalcoAnvilLoader` and the `@since` / annotation facts
  above. Re-checked at `ca79507`.
- Why a failed read throws rather than reporting the chunk as absent — the design constraint this
  hierarchy has to preserve — is argued in
  [Why a second Anvil loader](../why-a-second-anvil-loader.md).
- What the rest of these investigations are worth, and why their numbers are not benchmark numbers,
  is on [Research](README.md).
