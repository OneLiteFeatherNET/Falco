# Compute light for a loaded world

Produce block and sky light for chunks that arrived without it — a generated world, or a world whose
stored light you do not trust. You end with light written into a chunk's sections, through
`Light#set(byte[])`, and with a check that it is right.

**Before you start:** `falco-light` on the classpath ([How-to Add Falco to your build](add-falco-to-your-build.md)), and a
loaded `Instance`. If your server only loads pre-lit worlds from `.mca`, none of this runs —
[Explanation When light computation actually runs](../explanation/when-light-computation-actually-runs.md) explains why.

## Light one chunk

1. Create a `ChunkLightService`. It keeps no state between calls, so one instance serves as many
   threads as you like. Load the chunk from your `Instance` (`// your Instance`):

   ```java
   import net.minestom.server.instance.Chunk;
   import net.onelitefeather.falco.light.ChunkLightService;

   ChunkLightService lighting = new ChunkLightService();
   Chunk chunk = instance.loadChunk(0, 0).join(); // your Instance
   ```

2. Compute block light and read a level back:

   ```java
   lighting.calculate(chunk);

   int level = lighting.blockLightAt(chunk, 8, 40, 8);
   ```

This works with **any** chunk, regardless of which loader produced it — the Anvil loader of Falco,
the one Minestom ships with, or a generated chunk. A test covers the round trip through
`FalcoAnvilLoader` explicitly. Why one service is enough for a whole server is explained in
[Explanation How the concurrency design works](../explanation/how-the-concurrency-design-works.md#one-light-service-serves-every-thread).

### Sky light

3. Compute sky light the same way:

   ```java
   lighting.calculateSky(chunk);
   ```

   Sky light falls from above without losing a level until something stops it; the reasoning is in
   [Explanation How the light engine works](../explanation/how-the-light-engine-works.md#sky-light).

### Across chunk borders

4. Light the chunk together with its loaded neighbours, using the chunk coordinates of the middle chunk:

   ```java
   lighting.calculateWithNeighbours(instance, chunkX, chunkZ); // your Instance and chunk coordinates
   ```

   Lighting a chunk on its own leaves a straight dark line every sixteen blocks at its borders. This
   method exchanges light with the loaded neighbours and writes only the chunk in the middle. The
   repetition, the reason the eight neighbours are never written, and the diagonal chunks it reads are
   described in [Explanation How the light engine works](../explanation/how-the-light-engine-works.md#light-across-chunk-borders).

## Choose the right call

### When to call what

| Situation | Method |
| --- | --- |
| A live world where light should simply be right | `setChunkSupplier(scheduler.supplier())`, then nothing |
| Chunk loaded without stored light, or generated | `calculate` / `calculateSky` |
| One chunk loaded and its neighbours matter | `calculateWithNeighbours` — its 3×3 includes the four diagonal chunks, which a one-chunk area never reads |
| Several connected chunks at once | `ChunkLightArea#compute` — measurably cheaper than one neighbourhood per chunk from four chunks on |
| A single block changed | `ChunkLightState#update`, or nothing at all if the scheduler is driving |

## Drive the propagator directly, without a server

`LightPropagator` is the layer below `ChunkLightService`. Use it when you have section block states
and no `Instance` — a converter, a test, an offline tool:

```java
import net.onelitefeather.falco.light.LightNibbles;
import net.onelitefeather.falco.light.LightPropagator;
import net.onelitefeather.falco.light.MinestomBlockLightSource;
import net.onelitefeather.falco.light.SectionOpacity;

// One per worker thread; it keeps reusable buffers.
LightPropagator propagator = new LightPropagator();
MinestomBlockLightSource source = new MinestomBlockLightSource();

int[] stateIds = new int[LightNibbles.BLOCK_COUNT];  // block states of one section
// ... fill stateIds from a palette ...

LightNibbles light = propagator.propagate(SectionOpacity.of(stateIds, source));

int level = light.get(8, 8, 8);
byte[] stored = light.toArray();                     // empty when the section is dark
```

`BlockLightSource` can be implemented directly to run the engine without a server. The two state ids
below are placeholders for your own: one for a lamp that emits light, one for a stone block that blocks
every face.

```java
import net.onelitefeather.falco.light.BlockFace;
import net.onelitefeather.falco.light.BlockLightSource;

int LAMP = 1;   // your state id of a lamp
int STONE = 2;  // your state id of stone

BlockLightSource fake = new BlockLightSource() {
    @Override public int emission(int stateId) { return stateId == LAMP ? 15 : 0; }
    @Override public boolean blocksFace(int stateId, BlockFace face) { return stateId == STONE; }
};
```

Keep **one `LightPropagator` per worker thread**. It holds its level buffer and queue in instance
fields, so a shared one produces wrong light rather than an exception. `ChunkLightService` is the
exception and is safe to share, because it builds a propagator inside every call.

## Check it worked

`light.get(x, y, z)` returns the expected level, and `light.toArray()` is empty exactly when the
section is dark. In a live world, a chunk lit on its own shows a straight dark line every sixteen
blocks — if you see that, use `calculateWithNeighbours` instead of `calculate`.

## If it does not work

Light that is wrong rather than absent, and only under load, is almost always a `LightPropagator`
shared across threads. Nothing enforces the confinement at compile time.

See also: [How-to Keep chunk light up to date automatically](keep-chunk-light-up-to-date-automatically.md) to stop calling any of this by hand ·
[Explanation How the light engine works](../explanation/how-the-light-engine-works.md) · [Explanation Scope and non-goals](../explanation/scope-and-non-goals.md)
