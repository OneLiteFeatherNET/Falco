# Supported versions

The versions Falco is built against, the versions it accepts, and the floors it refuses below.

> **This page is a generation target, currently written by hand.**
> Source of truth: the version catalog and Java toolchain in the
> [Falco build](https://github.com/OneLiteFeatherNET/Falco), and the constants in
> [`FalcoAnvilLoader`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java).

## Build and runtime

| | Value | Note |
| --- | --- | --- |
| Java | 25 | required to build Falco and to run it |
| Minestom | `2026.06.20-26.1.2` | the version every claim in this documentation was checked against; `compileOnly`, so a consumer picks its own |

## Chunk data version floor

| | |
| --- | --- |
| Constant | [`FalcoAnvilLoader.DEFAULT_MINIMUM_DATA_VERSION`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java) |
| Value | `2844` |
| Corresponds to | Minecraft snapshot `21w43a` |
| Builder slot (read side) | `minimumDataVersion(int)` |
| Builder slot (write side) | `dataVersion(int)` — stamped into every saved chunk; unrelated to the floor |
| Enforced by | `ChunkVersionPolicy`, by default `DefaultChunkVersionPolicy` |

`21w43a` is where a chunk's data moved out of the `Level` compound onto the root compound, under the
key `sections` ([`FalcoAnvilLoader.SECTIONS_KEY`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java)).

### What the guard does with each case

| Stored state | Result |
| --- | --- |
| No `sections`, but a `Level` compound | refused — pre-`21w43a` layout, whatever `DataVersion` says |
| Current layout, `DataVersion` absent | accepted |
| Current layout, `DataVersion` wrong type | refused |
| Current layout, `DataVersion` negative | refused |
| Current layout, `DataVersion` below the floor | refused |
| Current layout, `DataVersion` at or above the floor | accepted |
| `Status: minecraft:full` with neither `sections` nor `Level` | refused, `MISSING_OR_MISTYPED_KEY` |
| Current layout, empty `sections` list | accepted |

## Compression schemes

| Id | Scheme | Read | Write |
| --- | --- | --- | --- |
| 1 | gzip | yes | no |
| 2 | zlib | yes | yes — always |
| 3 | uncompressed | yes | no |
| 4 | LZ4 | no | no |
| 127 | custom | no | no |

The external bit `0x80` is masked off. Any other id raises `The compression scheme <id> is not
supported. Only gzip (1), zlib (2) and none (3) can be read`
([`ChunkCompression#fromId`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/ChunkCompression.java)).

## World directory layout

| Layout | Path resolved from the world root |
| --- | --- |
| 26.1 and later | `worldRoot/dimensions/<namespace>/<value>/region` |
| pre-26.1 | `worldRoot/region` — used only when the above does not exist |

Related: [Explanation The chunk version guard](../explanation/the-chunk-version-guard.md) · [How-to Migrate a world from an older version](../how-to/migrate-a-world-from-an-older-version.md) ·
[Reference Modules and coordinates](modules-and-coordinates.md)
