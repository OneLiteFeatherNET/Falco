# Load an Anvil world

Serve a stored Anvil world (`r.<x>.<z>.mca` region files) from a Minestom instance using
`FalcoAnvilLoader` instead of the built-in `AnvilLoader`. You end with an instance that streams its chunks
from the world directory and refuses to replace a chunk it cannot read.

**Before you start:** `falco-anvil` on the classpath ([How-to Add Falco to your build](add-falco-to-your-build.md)), and a
world directory whose chunks are at or above the version floor on
[Reference Supported versions](../reference/supported-versions.md). Nothing switches to this loader by itself — a server keeps using
whatever loader it uses today until it constructs a `FalcoAnvilLoader` explicitly.

## Set the loader on an instance

1. Create the instance, construct the loader from the **world root** and the dimension key, and set
   it:

```java
import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.world.DimensionType;
import net.onelitefeather.falco.anvil.FalcoAnvilLoader;

import java.nio.file.Path;

public final class Bootstrap {

    public static InstanceContainer createLobby() {
        InstanceContainer instance = MinecraftServer.getInstanceManager()
                .createInstanceContainer(DimensionType.OVERWORLD);

        Key dimension = DimensionType.OVERWORLD.key();
        FalcoAnvilLoader loader = new FalcoAnvilLoader(Path.of("worlds", "lobby"), dimension);

        instance.setChunkLoader(loader);
        instance.enableAutoChunkLoad(true);
        return instance;
    }
}
```

2. Pass the world root, not the region directory. The loader resolves
   `worldRoot/dimensions/<namespace>/<value>/region` itself, and falls back to `worldRoot/region`
   when only the pre-26.1 layout exists.

3. Call `close()` on server shutdown. It flushes and closes every open region file and writes the
   summary line. During operation a region file closes on its own once the last chunk read from it
   has been unloaded, so nothing has to be closed per chunk.

## Hand the loader to a map provider instead

`FalcoAnvilLoader` is a plain `net.minestom.server.instance.ChunkLoader`, so anything that already
accepts one takes it without knowing this library exists. Pass it to
`InstanceManager#createInstanceContainer(RegistryKey<DimensionType>, ChunkLoader)`:

```java
import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.DimensionType;
import net.onelitefeather.falco.anvil.FalcoAnvilLoader;

import java.nio.file.Path;

public final class MapLoaders {

    public static InstanceContainer open(Path worldRoot, RegistryKey<DimensionType> dimensionKey) {
        Key dimension = dimensionKey.key();
        ChunkLoader loader = new FalcoAnvilLoader(worldRoot, dimension);

        return MinecraftServer.getInstanceManager().createInstanceContainer(dimensionKey, loader);
    }
}
```

A provider that keeps a world root per map passes that root and the dimension key of the instance it
is building. The constructors it can call are listed in
[Reference API surface and stability](../reference/api-surface-and-stability.md#entry-points-and-signatures),
and why this choice is reversible is explained in
[Explanation Choosing between Falco and the built-in loader](../explanation/choosing-between-falco-and-the-built-in-loader.md).

The `falco-demo` module does exactly this and is compiled on every build:
[`LoaderKind#create`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-demo/src/main/java/net/onelitefeather/falco/demo/LoaderKind.java)
constructs either loader from the same world description, and
[`DemoServer`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-demo/src/main/java/net/onelitefeather/falco/demo/DemoServer.java)
hands the result to `createInstanceContainer`.

## Raise the cached-region limit

The three-argument constructor sets how many region files stay open. The default is in
[Reference API surface and stability](../reference/api-surface-and-stability.md#entry-points-and-signatures).

```java
FalcoAnvilLoader loader = new FalcoAnvilLoader(worldRoot, dimension, 256);
```

## Tune the builder

The two-argument constructor is enough for most servers. The builder is there when the defaults do not
fit:

```java
FalcoAnvilLoader loader = FalcoAnvilLoader.builder()
        .openRegionLimit(64)          // region files kept open at once
        .compressionLevel(2)          // 1..9, the trade between write time and file size
        .saveParallelism(4)           // threads a saveChunks call may use
        .dataVersion(4189)            // what a written chunk claims to be
        .diagnostics(new AnvilDiagnostics())
        .exceptionHandler(throwable -> log.warn("chunk load failed", throwable))
        .build(Path.of("worlds", "lobby"), DimensionType.OVERWORLD.key());
```

`diagnostics()` matters on a live server. A world written by another version, or by a mod, can contain
blocks and biomes this loader does not know. The loader substitutes them and counts the substitutions
rather than failing:

```java
AnvilDiagnostics diagnostics = loader.diagnostics();
diagnostics.reportUnknownBlock("mod:strange_block");   // true the first time, false after
```

## See what the loader resolved

Three accessors tell you what the loader is doing right now:

- `regionDirectory()` gives the directory it resolved.
- `legacyLayout()` is true when it fell back to the pre-26.1 layout.
- `openRegionCount()` is how many region files are open at this moment.

`close()` flushes every open region file. `ownsLoader(true)` on a `FalcoInstance` calls it for you.

## Check it worked

The loader writes an opening log line naming the resolved region directory and the policies it
resolved. Players see the stored world rather than freshly generated terrain, and
`AnvilDiagnostics#chunksLoaded()` climbs while `errors()` stays at zero.

## If it does not work

A load that throws `AnvilChunkException` rather than returning air is the loader working as designed
— it found a chunk it could not read and refused to let a replacement be generated over it. Read
`reason()` off the causing `ChunkDataException` and look it up on
[Reference Exceptions and faults](../reference/exceptions-and-faults.md). `UNSUPPORTED_CHUNK_VERSION` means the world predates the floor;
[How-to Migrate a world from an older version](migrate-a-world-from-an-older-version.md) is the fix.

An `IllegalStateException` from `loadChunk`, `saveChunk` or `saveChunks` means `close()` has already
run on this loader.

See also: [Reference Exceptions and faults](../reference/exceptions-and-faults.md) · [Reference Supported versions](../reference/supported-versions.md) ·
[How-to Replace the version and unknown-entry policies](replace-the-version-and-unknown-entry-policies.md) ·
[Explanation How the Anvil loader is built](../explanation/how-the-anvil-loader-is-built.md)
