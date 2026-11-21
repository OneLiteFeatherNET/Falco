# Falco

[![API status: experimental](https://img.shields.io/badge/API-experimental-yellow)](https://docs.onelitefeather.net/falco/project-record/project-status)
[![Release](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo.onelitefeather.dev%2Freleases%2Fnet%2Fonelitefeather%2Ffalco-anvil%2Fmaven-metadata.xml&label=release&color=blue)](https://docs.onelitefeather.net/falco/how-to/add-falco-to-your-build)
[![Java 25](https://img.shields.io/badge/Java-25-orange)](https://docs.onelitefeather.net/falco/how-to/build-falco-from-source)
[![Documentation](https://img.shields.io/badge/docs-gitbook-lightgrey)](https://docs.onelitefeather.net/falco)
[![Licence: AGPL-3.0](https://img.shields.io/badge/licence-AGPL--3.0-lightgrey)](LICENSE)

Falco is a set of modules for [Minestom](https://github.com/Minestom/Minestom) servers that load
worlds saved in the Anvil format. It provides a chunk loader, a block and sky light engine, and an
`Instance` that unloads everything it loaded. It is for server developers who want a loader that
fails loudly on a bad read instead of quietly generating over real data.

- [Anvil chunk loader](https://docs.onelitefeather.net/falco/explanation/how-the-anvil-loader-is-built) — reads and writes region files in parallel, and throws on a read failure.
- [Light engine](https://docs.onelitefeather.net/falco/explanation/how-the-light-engine-works) — block and sky light, thread-safe per call, usable with any chunk type.
- [Automatic relighting](https://docs.onelitefeather.net/falco/how-to/keep-chunk-light-up-to-date-automatically) — chunks keep their light current as blocks change.
- [FalcoInstance](https://docs.onelitefeather.net/falco/explanation/why-falco-instance-exists) — unregistering it unloads every chunk it loaded.
- [Shared worlds](https://docs.onelitefeather.net/falco/how-to/use-falcoinstance-instead-of-instancecontainer) — `FalcoSharedInstance` on a plain container.
- [Version guard](https://docs.onelitefeather.net/falco/explanation/the-chunk-version-guard) — refuses worlds older than snapshot 21w43a instead of reading them as air.
- [World migration](https://docs.onelitefeather.net/falco/explanation/how-world-migration-works) — `falco-migration` moves older worlds forward. It has no release yet.

All published modules are **experimental**. See
[API surface and stability](https://docs.onelitefeather.net/falco/reference/api-surface-and-stability).

## Quick start

### 1. Declare the dependency

<!-- x-release-please-start-version -->
```kotlin
repositories {
    mavenCentral()
    maven("https://repo.onelitefeather.dev/releases")
}

dependencies {
    // One version for all three, so they cannot drift into a combination nobody tested.
    implementation(platform("net.onelitefeather:falco-bom:3.0.2"))

    implementation("net.onelitefeather:falco-anvil")     // reading and writing Anvil worlds
    implementation("net.onelitefeather:falco-light")     // block and sky light
    implementation("net.onelitefeather:falco-instance")  // the instance and the chunk

    // Minestom is compileOnly in Falco, so it does not arrive with these
    // artefacts. Falco declares no version for it, on purpose. You pick it.
    // The version Falco compiles and measures against is recorded in the documentation:
    // https://docs.onelitefeather.net/falco/contributing#environment
    implementation("net.minestom:minestom:<version>")
}
```
<!-- x-release-please-end -->

Take only the modules you need. The platform constrains a version for each module; it does not pull
any in. Individual coordinates, Maven and snapshots are in
[Installation](https://docs.onelitefeather.net/falco/how-to/add-falco-to-your-build).

### 2. Serve a stored world

```java
import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.world.DimensionType;
import net.onelitefeather.falco.anvil.FalcoAnvilLoader;

import java.io.IOException;
import java.nio.file.Path;

public final class FirstServer {

    public static void main(String[] args) {
        MinecraftServer server = MinecraftServer.init();

        InstanceContainer instance = MinecraftServer.getInstanceManager()
                .createInstanceContainer(DimensionType.OVERWORLD);

        Key dimension = DimensionType.OVERWORLD.key();
        FalcoAnvilLoader loader = new FalcoAnvilLoader(Path.of("worlds", "lobby"), dimension);

        instance.setChunkLoader(loader);
        instance.enableAutoChunkLoad(true);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                loader.close();
            } catch (IOException exception) {
                exception.printStackTrace();
            }
        }));

        server.start("0.0.0.0", 25565);
    }
}
```

Copy a saved world into the project as `worlds/lobby`, so that its region files sit in
`worlds/lobby/dimensions/minecraft/overworld/region`. Run the class and connect to `localhost:25565`.
You are standing in the stored world, not in generated terrain.

The [tutorial](https://docs.onelitefeather.net/falco/tutorials/load-your-first-world-with-falco) goes on
to light the world with `falco-light` and to check a clean shutdown. Worlds saved before Minecraft 26.1
use a different directory layout; see
[Load an Anvil world](https://docs.onelitefeather.net/falco/how-to/load-an-anvil-world).

## Documentation

The full documentation is on GitBook at [docs.onelitefeather.net/falco](https://docs.onelitefeather.net/falco).
It is organised in four parts:

- [Tutorials](https://docs.onelitefeather.net/falco/tutorials/load-your-first-world-with-falco) — a guided first project, start to finish.
- [How-to guides](https://docs.onelitefeather.net/falco/how-to/add-falco-to-your-build) — steps for a specific task, such as migrating a world or reproducing a measurement.
- [Reference](https://docs.onelitefeather.net/falco/reference/modules-and-coordinates) — modules, supported versions, the API surface, policies, exceptions and measured results.
- [Explanation](https://docs.onelitefeather.net/falco/explanation/choosing-between-falco-and-the-built-in-loader) — why the modules are built the way they are, and what they deliberately do not do.

## Contributing

The shortest path from a clone to a green build, and the one credential problem that stops it, are in
[`CONTRIBUTING.md`](CONTRIBUTING.md). The
[contributing guide](https://docs.onelitefeather.net/falco/contributing) covers conventions, testing,
releases and what review looks for.

## Licence

AGPL-3.0. See [`LICENSE`](LICENSE).
