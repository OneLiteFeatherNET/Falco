# Compute light for a loaded world

Produce block and sky light for chunks that arrived without it — a generated world, or a world whose
stored light you do not trust. Each call below writes its result into the chunk's sections through
`Light#set(byte[])`.

**Before you start:** `falco-light` on the classpath ([How-to Add Falco to your build](add-falco-to-your-build.md)), and a
loaded `Instance`. If your server only loads pre-lit worlds from `.mca`, none of this runs —
[Explanation When light computation actually runs](../explanation/when-light-computation-actually-runs.md) explains why.

## Light one chunk

1. Create a `ChunkLightService`. It keeps no state between calls, so one instance serves as many
   threads as you like:

   ```java
   import net.onelitefeather.falco.light.ChunkLightService;

   ChunkLightService lighting = new ChunkLightService();
   Chunk chunk = instance.loadChunk(0, 0).join();
   ```

2. Compute block light and read a level back:

   ```java
   lighting.calculate(chunk);

   int level = lighting.blockLightAt(chunk, 8, 40, 8);
   ```

This works with **any** chunk, regardless of which loader produced it — the Anvil loader of Falco,
the one Minestom ships with, or a generated chunk. A test covers the round trip through
`FalcoAnvilLoader` explicitly.

**One service is enough for a whole server.** Unlike `LightPropagator` below, `ChunkLightService`
holds exactly one field, the `BlockLightSource` it was constructed with, and every working buffer
lives in a `ChunkLightPropagator` built inside the call. A single instance may therefore be used by
as many threads as you like — a property of the class layout rather than of a benchmark.

### Sky light

```java
lighting.calculateSky(chunk);
```

Sky light enters from above and falls straight down **without losing a level** until something stops
it — which is why an open field is fully lit at every height while a cave is dark. Only after the
fall is interrupted does it spread like any other light, losing one level per block.

### Across chunk borders

```java
lighting.calculateWithNeighbours(instance, chunkX, chunkZ);
```

Lighting a chunk on its own ends its light at the border, which shows up as a straight dark line
every sixteen blocks. This method exchanges the border levels with every already loaded neighbour in
both directions, and **writes only the chunk in the middle**. Neighbours that are not loaded are
skipped rather than forced to load.

One round of that exchange is not enough. A source in the corner of a chunk sends light through two
borders, and the light that entered a neighbour has to leave it again on another side to arrive in
the chunk diagonally behind it. The exchange therefore repeats over the whole area — the chunk and
the eight positions around it — until no chunk of it raises a level any more:

| Property | How it is reached |
| --- | --- |
| Terminates | An injection only ever raises a level, and a level is capped at fifteen, so the repetition walks towards a fixed point. |
| Same result every time | The area is a fixed-size array walked in a fixed order, not a map. Since every step only raises levels, the fixed point does not depend on the order either. |
| Reads a chunk once | The opacity tables of every participating chunk are built once, before the first round, and reused by all of them. |
| Cannot loop forever | The amount of rounds is capped at sixteen, which is one more than the highest level that can exist. Hitting the cap is reported through `LOGGER.warn` instead of being accepted silently. |

A radius of one chunk is enough because a level of fifteen cannot survive sixteen blocks of travel,
so nothing the middle chunk emits can reach a second ring.

**The eight chunks around the middle are read and never written, and that is not a shortcut.** They
only exchanged light inside the 3×3, so whatever they legitimately receive from outside it is missing
from their result; writing that back would replace their correct light with a darker one. The middle
chunk does not have the problem, and provably so: a source outside the 3×3 is at least seventeen
blocks away from it and no path can be shorter than the direct distance, so not even a level of
fifteen survives the trip. Writing one chunk instead of nine is therefore cheaper *and* correct. The
method used to write all nine, which is the defect this replaces.

**What this method still has over a one-chunk area.** The 3×3 includes the four **diagonal** chunks;
the ring of a [`ChunkLightArea`](keep-chunk-light-up-to-date-automatically.md) is built from face neighbours
only. A source in a diagonal chunk reaches the middle chunk through the chunk between them, and the
neighbourhood carries that — an area of a single chunk never reads it. For one chunk this is
therefore the more accurate of the two calls; for several connected chunks the area is the cheaper
one, because it reads every chunk once instead of once per neighbourhood. That an area misses its own
diagonals is a gap of its own, and it is recorded in [Project Status](../project-record/project-status.md) rather than
glossed over here.


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

`BlockLightSource` can be implemented directly to run the engine without a server:

```java
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
