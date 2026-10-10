# Load your first world with Falco

By the end of this you will have a Minestom server that serves a stored Anvil world through
`FalcoAnvilLoader`, lights it with `falco-light`, and shuts down without losing a chunk.

You need Java 25, a Gradle project, and a saved Minecraft world directory. Nothing else.
This takes about fifteen minutes.

## Declare the dependencies

1. Add the OneLiteFeather repository and the Falco platform to `build.gradle.kts`:

   ```kotlin
   repositories {
       mavenCentral()
       maven("https://repo.onelitefeather.dev/releases")
   }

   dependencies {
       implementation(platform("net.onelitefeather:falco-bom:3.0.0"))
       implementation("net.onelitefeather:falco-anvil")
       implementation("net.onelitefeather:falco-light")

       implementation("net.minestom:minestom:2026.09.12-26.2")
   }
   ```

2. Refresh the Gradle project.

You should now see `net.onelitefeather.falco.anvil.FalcoAnvilLoader` resolve in your editor.

## Put the world where the loader will find it

1. Copy your saved world directory into your project as `worlds/lobby`.
2. Confirm it contains a `region` directory holding `r.<x>.<z>.mca` files — either directly at
   `worlds/lobby/region`, or under `worlds/lobby/dimensions/minecraft/overworld/region`.

You should now see at least one `.mca` file inside that directory.

## Serve the world

1. Create the server class:

   ```java
   import net.kyori.adventure.key.Key;
   import net.minestom.server.MinecraftServer;
   import net.minestom.server.instance.InstanceContainer;
   import net.minestom.server.world.DimensionType;
   import net.onelitefeather.falco.anvil.FalcoAnvilLoader;

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

           Runtime.getRuntime().addShutdownHook(new Thread(loader::close));

           server.start("0.0.0.0", 25565);
       }
   }
   ```

2. Run it.

You should now see a log line at `INFO` from `FalcoAnvilLoader` naming the region directory it
resolved and the dimension. Connect with a client and you are standing in your stored world, not in
freshly generated terrain.

## Light it

1. Add the scheduler, just above `server.start`:

   ```java
   import net.onelitefeather.falco.light.ChunkLightScheduler;
   import net.onelitefeather.falco.light.ChunkLightService;

   ChunkLightScheduler scheduler = new ChunkLightScheduler(new ChunkLightService());
   instance.setChunkSupplier(scheduler.supplier());
   ```

2. Run it again and place a torch in a dark room.

You should now see the light spread from the torch on the next tick, without any call of your own.

## Shut it down cleanly

1. Stop the server.

You should now see a second `FalcoAnvilLoader` line at `INFO`: the close summary, naming loaded
chunks, skipped chunks, saved chunks and errors. `errors=0` is what a clean run looks like. If it is
above zero the line is logged at `WARN` instead, so a shutdown that lost chunks does not read like a
clean one.

## What you did

You replaced Minestom's chunk loader with one that refuses to hand back a generated chunk when a
stored one fails to read, and you handed light computation to a scheduler that keeps chunks correct
on its own. Both were opt-in: nothing in Falco switches itself on.

Next: [How-to Load an Anvil world](../how-to/load-an-anvil-world.md) covers the map-provider form and the cached-region limit, and
[Explanation Choosing between Falco and the built-in loader](../explanation/choosing-between-falco-and-the-built-in-loader.md) explains which of the differences
between the two loaders actually carry a decision.
