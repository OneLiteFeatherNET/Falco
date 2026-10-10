# Load an Anvil world

Serve a stored Anvil world (`r.<x>.<z>.mca` region files) from a Minestom instance using
`FalcoAnvilLoader` instead of the built-in `AnvilLoader`.

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

<!-- TODO(maintainer): this section previously documented an Aves integration built on a
     `ChunkLoaderFactory` functional interface and a four-argument
     `AbstractMapProvider#registerInstance` overload. Neither exists. `ChunkLoaderFactory` appears in
     no .java file in this repository and in no commit of its history (`git log --all -S`), Falco
     declares no dependency on Aves in any build file, and `net.theevilreaper.aves.map.provider
     .AbstractMapProvider` at Aves 1.16.0 declares only the two- and three-argument overloads. The
     section below states what is true today. Restore the factory prose once the interface is
     actually shipped, on either side. -->

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

The relevant Falco signatures are:

| Member | Declaration |
| --- | --- |
| [`FalcoAnvilLoader(Path, Key)`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java) | `public FalcoAnvilLoader(Path worldRoot, Key dimension)` |
| [`FalcoAnvilLoader(Path, Key, int)`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-anvil/src/main/java/net/onelitefeather/falco/anvil/FalcoAnvilLoader.java) | `public FalcoAnvilLoader(Path worldRoot, Key dimension, int openRegionLimit)` |

A provider that keeps a world root per map passes that root and the dimension key of the instance it
is building. Any other `ChunkLoader` is supplied the same way, which is what makes the choice
reversible.

The `falco-demo` module does exactly this and is compiled on every build:
[`LoaderKind#create`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-demo/src/main/java/net/onelitefeather/falco/demo/LoaderKind.java)
constructs either loader from the same world description, and
[`DemoServer`](https://github.com/OneLiteFeatherNET/Falco/blob/main/falco-demo/src/main/java/net/onelitefeather/falco/demo/DemoServer.java)
hands the result to `createInstanceContainer`.

## Raise the cached-region limit

The number of region files held in the cache defaults to 64 and is set through the three-argument
constructor:

```java
FalcoAnvilLoader loader = new FalcoAnvilLoader(worldRoot, dimension, 256);
```

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
