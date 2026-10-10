# The chunk version guard

Why `FalcoAnvilLoader` refuses to load a world it considers too old, why it used to hand back a
chunk of air instead, and where the boundary is drawn. This is the behaviour that surprises a server
that has been pointing the loader at a pre-`21w43a` world.

The values the guard compares against are on [Reference Supported versions](../reference/supported-versions.md); how to replace or
disable the guard is [How-to Replace the version and unknown-entry policies](../how-to/replace-the-version-and-unknown-entry-policies.md).

## The version floor

Before Minecraft snapshot `21w43a`, a chunk's data sits inside a `Level` compound. From that
snapshot on it sits directly on the root compound, under the key `sections`
([`FalcoAnvilLoader.SECTIONS_KEY`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java)).
`FalcoAnvilLoader` only ever reads `sections` from the root. Before this guard existed, pointing it
at an older world found nothing there:
[`NbtReads.optionalList`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/NbtReads.java)
returns an empty list for an absent key instead of failing, so every such chunk decoded to nothing
but air — no error, no log line — and a save could then have written that empty chunk over the real
one.

`DefaultChunkVersionPolicy#check` closes that gap
([`DefaultChunkVersionPolicy`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/DefaultChunkVersionPolicy.java))
and, as the loader's default `ChunkVersionPolicy`, runs on every load, right after the raw compound is
parsed and before anything is decoded from it — see [Replaceable
policies](../reference/replaceable-policies.md) below for how a caller can swap this check out for one of its own,
or turn it off entirely. It draws the line two independent ways:

1. **Layout.** A root compound that carries no `sections` but does carry a `Level` compound is the
   pre-`21w43a` shape, and is refused outright — regardless of what `DataVersion` says, or whether
   it says anything at all.
2. **Version.** Once the layout is the current one, the stored `DataVersion` has to be at or above
   `minimumDataVersion`. The default,
   [`FalcoAnvilLoader.DEFAULT_MINIMUM_DATA_VERSION`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java)
   (`2844`), is the data version of `21w43a` itself — the snapshot the layout check above is keyed
   on, so the two lines agree on where the boundary is.

The version check treats an absent `DataVersion` and a broken one differently, on purpose:

* **Missing is not refused.** A tool that writes `sections` on the root but never learned to stamp a
  `DataVersion` is not rejected for that omission; refusing it would make a whole category of
  externally-written worlds unreadable for a reason unconnected to the layout this guard actually
  checks. (Falco's own save path always stamps one — see `dataVersion(int)` below — this case is
  about worlds written by something else.)
* **Wrong type or negative is refused.** Both are a value somebody actually wrote, and neither
  describes a version this loader can trust, so both are treated as a version below the floor rather
  than as "nothing was written". `NbtReads.optionalInteger` cannot tell "wrong type" apart from
  "absent" by itself — both fall back to the same default — so `DefaultChunkVersionPolicy#check`
  checks presence itself first. A negative value needs no separate branch at all: it simply fails the same
  `version >= minimumDataVersion` comparison every other version is checked against.
* **Present, valid and below the floor is refused**, whatever the number actually is.

`minimumDataVersion(int)` is the builder slot for the floor above, and it is deliberately kept apart
from `dataVersion(int)`, the *write* side that stamps a version into every chunk this loader saves:
one is what a load has to clear, the other is what a save claims, and the two have no relationship to
each other that the API should imply by sharing a name.

```java
FalcoAnvilLoader loader = FalcoAnvilLoader.builder()
        .minimumDataVersion(FalcoAnvilLoader.DEFAULT_MINIMUM_DATA_VERSION)  // read: reject below this
        .dataVersion(4189)                                                  // write: stamp this
        .build(Path.of("worlds", "lobby"), dimension);
```

A refused chunk fails exactly like every other unreadable chunk described under
[Error handling and world consistency](../reference/exceptions-and-faults.md): `loadChunk` throws
`AnvilChunkException` instead of returning `null`. Its cause is a `ChunkDataException` whose
`reason()` is the new
[`ChunkDataException.Reason.UNSUPPORTED_CHUNK_VERSION`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkDataException.java).
The guard itself converts nothing: a chunk it refuses is exactly as unreadable as it was before the
guard existed, and the loader now says so instead of quietly handing back air. **A loader can be
asked to migrate such a chunk rather than refuse it** — see [How world migration works](how-world-migration-works.md).
Migration runs *before* this guard for that reason, since lifting a chunk over the floor is what
turns it into one the guard accepts; the other order would reject every world worth rescuing. Without
a migration mode selected, nothing of the sort happens and this paragraph describes the whole
behaviour.

[`AnvilDiagnostics#reportUnsupportedChunkVersion(String)`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilDiagnostics.java)
throttles the log line per distinct stored version, the same way every other diagnostic in the class
does, and counts every occurrence whether or not it was logged. A chunk with no stored `DataVersion`
at all is filed under
[`AnvilDiagnostics.UNKNOWN_DATA_VERSION`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilDiagnostics.java)
(`"<none>"`) rather than under a literal `"-1"`, because the two describe different worlds: one never
had a version stamped, the other had one stamped and rejected. But an absent `DataVersion` on a
current-layout chunk is accepted, as above — so `UNKNOWN_DATA_VERSION` only appears in this breakdown
for the layout case, where it is refused for a reason that has nothing to do with the version being
absent.
[`AnvilDiagnostics#unsupportedChunkVersions()`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilDiagnostics.java)
returns the breakdown by that string, sorted by key;
[`AnvilDiagnostics#chunksSkippedAsUnsupported()`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/AnvilDiagnostics.java)
returns the total across every version, alongside `chunksSkippedAsPartial()` and the other counters
this class already exposed.

**This is a behaviour change from earlier releases.** A world below the floor used to load as a chunk
of air, silently, and a save could then write that air back over the original data. It now fails the
load instead. A server that has been pointing this loader at a pre-`21w43a` world will see it start
throwing where it previously — incorrectly — succeeded.

### The truncated chunk in the current layout

The guard above covers a world in an *old* format. A chunk in the **current** one could reach the
caller as air by a second route, closed separately in
[#49](https://github.com/OneLiteFeatherNET/Falco/pull/49): one carrying `Status: minecraft:full` and
**neither** `sections` **nor** `Level` has nothing for the guard to object to — current layout,
current `DataVersion` — and the status check waves it through. `NbtReads.optionalList` answers the
absent key with an empty list, nothing is decoded from it, and the caller receives a chunk of air
that reports itself as loaded: `chunksLoaded()` is 1 and `errors()` is 0.

Such a chunk is now refused with
[`ChunkDataException.Reason.MISSING_OR_MISTYPED_KEY`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkDataException.java).
Three boundaries are deliberate:

- **It runs after the status check, not in the version policy.** A chunk that is honestly unfinished
  carries no `sections` either, and for it that is the normal state of a world edge rather than a
  contradiction. Only a chunk claiming to be complete has to prove it; an unfinished one stays a
  skip.
- **A chunk carrying `Level` is left to the version policy.** That is the pre-1.18 layout and a
  different failure, and a caller who passed `versionPolicy(null)` asked for that check not to run —
  a promise this must not quietly take back.
- **An empty `sections` list is accepted.** An empty list is a statement that this chunk has none;
  an absent key is the absence of a statement. Refusing the empty list would reject legitimately
  empty chunks written by other tools, a far larger decision than this defect calls for.

The check does not live in `NbtReads.optionalList` itself. That method exists precisely so an absent
optional key is not an error, other callers depend on it, and it cannot know what its caller
considers required.


Related: [How-to Migrate a world from an older version](../how-to/migrate-a-world-from-an-older-version.md) · [Reference Exceptions and faults](../reference/exceptions-and-faults.md) ·
[Explanation Scope and non-goals](scope-and-non-goals.md)
