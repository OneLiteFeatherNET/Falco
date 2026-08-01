# falco-demo

Put your own world in here and find out, on your own machine, whether the Falco stack does better
than the one Minestom ships with. Nothing in this module is published; it exists so the claims in
[Project Status](https://github.com/OneLiteFeatherNET/Falco/wiki/Project-Status) can be checked
rather than believed.

There are two ways to check, and they answer different questions:

| | What it is | What it answers |
| --- | --- | --- |
| `runFalcoLoader` / `runMinestomLoader` | headless, no server, a fixed list of chunks, printed with a spread | *Which loader is faster, and by how much?* Reproducible. Run both and put the numbers next to each other. |
| `runFalcoServer` / `runMinestomServer` | a server you join with a Minecraft client and fly around in | *Does it feel better?* Does the world keep up while flying, is the light right, does it stutter. Not reproducible, and not meant to be. |

Keep both. The measurement produces a number and tells you nothing about how a world feels; the
server gives you an impression you cannot quote. Neither replaces the other.

## Put the world here

Copy your world folder into [`world/`](world). The result should look like `world/my-world/`.

Copy the **world root** — the folder that holds `level.dat` and either a `region/` directory or a
`dimensions/` directory. Not the `region/` directory itself: both loaders resolve
`<world>/dimensions/<namespace>/<value>/region` and fall back to `<world>/region`, so handing them
the region directory leaves them looking for a `region` inside it. Both layouts work, and both the
measurement and the server tell you which one they used.

Keep exactly one world in there. Nothing you put in that directory is ever committed — it carries
its own `.gitignore` — and starting anything in this module without a world prints what is missing
and where it goes instead of a stack trace.

**Nothing is ever written back.** Minestom saves neither on chunk unload nor on shutdown unless it is
asked to, and this module never asks. Your world is read and left exactly as it was.

---

# The server — judging it by eye

```bash
./gradlew :falco-demo:runFalcoServer        # FalcoAnvilLoader + the Falco light engine
./gradlew :falco-demo:runMinestomServer     # Minestom's AnvilLoader + its LightingChunk
```

Then connect to **`localhost:25565`**. Stop the server with ctrl-c.

## Which client

| | |
| --- | --- |
| **Minecraft client** | **26.1.2** |
| Protocol | 775 |
| Authentication | **offline mode** — no Mojang login, any username is accepted |

The version follows from the Minestom this repository builds against, `2026.06.20-26.1.2`: the part
after the dash is the Minecraft version it speaks. A client on any other version is turned away
during the handshake, which is by far the most likely reason a connection fails. The server prints
both numbers on startup, so after a Minestom upgrade believe the log rather than this table.

Offline mode means the server neither talks to Mojang nor verifies who you are. That is right for
something you run on your own machine for ten minutes and unacceptable for anything else. It binds
to all interfaces so you can watch from the machine next to it — do not put it on a public address.

You spawn in creative mode with flight already switched on, above the first chunk the world actually
contains.

## Options

Both server tasks accept the same ones, and there is no option that changes only one of them:

| Option | Default | What it changes |
| --- | --- | --- |
| `-Pport=<n>` | `25565` | the port the server listens on |
| `-PviewDistance=<n>` | `10` | chunks in every direction — this decides how much streaming there is to watch |
| `-Pdimension=<key>` | `minecraft:overworld` | which dimension is read from the world |
| `-Preport=<seconds>` | `10` | how often a summary line goes to the console |

**`-PviewDistance` is the one worth varying.** It decides how many chunks have to arrive while you
fly, which is the whole load the comparison is about. It reaches Minestom as the system property
`minestom.chunk-view-distance`, because Minestom reads that into a `static final` before any command
line of ours could be applied — which is also why the server prints the value it really uses rather
than the value it was handed.

## Where the numbers are

Three places, all of them without leaving the game:

- **The action bar**, above your hotbar, once a second:

  ```
  Falco | chunk 0.42/1.90/6.10 | 34.0 chunks/s | tick 1.20/3.80/9.00 | 421 loaded
  ```

  The three numbers are **p50 / p95 / max in milliseconds** — the normal case, how bad it gets
  regularly, and the worst single sample in the recent window. A mean is deliberately not among
  them: forty chunks at one millisecond and one at ninety average to a perfectly healthy three, and
  that one chunk is exactly the stutter you felt.

- **`/falco`** prints the same figures in full, with the mean, the sample counts, the components of
  the stack you are on, the view distance and what the session does not prove.

- **The console**, every `-Preport` seconds, so there is a record left after you stop flying.

The chunk durations are taken around `ChunkLoader#loadChunk` and around nothing else, by the same
decorator class in both servers. A chunk the world never generated returns nothing and is not
counted; timing those would report the speed of a header lookup as the speed of the loader.

## What to look at while flying

- **Fly in a straight line at creative speed** and watch the far edge of the loaded area. If it stays
  ahead of you, the loader is keeping up at that view distance. If you fly into fog and have to wait,
  it is not.
- **Watch the maximum, not the median.** Both stacks manage the median. The question is how often and
  how badly the maximum jumps, because that number *is* the stutter.
- **Watch the tick figures.** A loader that is fast while pushing the server tick to fifty
  milliseconds has moved the cost, not removed it.
- **Look at the light.** Fly into a cave, under an overhang, over a lit area at night, and along
  chunk borders. Light that arrives late, is wrong at a border, or never arrives is what this half of
  the comparison exists for; a headless measurement cannot see any of it.
- **Run one, then the other, and fly the same route.** They are separate processes and share nothing.

## What the server is not

- **It is not a benchmark and cannot be quoted.** One session, one machine, one route flown by hand,
  no repetition, no isolation, no statistical model. Anything you intend to publish belongs in the
  JMH benchmarks of [`falco-benchmarks`](../falco-benchmarks) — see
  [Benchmarking](https://github.com/OneLiteFeatherNET/Falco/wiki/Benchmarking) — with the machine, the
  jvm and the JMH configuration next to the number.
- **Your route is a variable.** Two flights over the same world load different chunks in a different
  order. Two impressions that differ by a little are not a result.
- **The first minute is warm-up.** The first chunks pay for class loading, for the interpreter
  running the whole codec path before it is compiled, and for the first read of every region file.
  Fly for a while before believing anything.
- **A desktop machine is noisy.** Whatever else is running lands in one of the two sessions and not
  in the other.
- **The chunk figures cover the loader, not the whole pipeline.** Light, packet building and
  compression sit outside the measured call. They show up in the tick figures instead, which is where
  they belong.

## What the Falco server is made of, and what it leaves out

The server prints this on startup, so what follows is the reasoning rather than the list.

| | Falco server | Minestom server |
| --- | --- | --- |
| Chunk loader | `net.onelitefeather.falco.anvil.FalcoAnvilLoader` | `net.minestom.server.instance.anvil.AnvilLoader` |
| Chunk and light | `net.onelitefeather.falco.light.FalcoLightingChunk` via `ChunkLightScheduler#supplier()` | `net.minestom.server.instance.LightingChunk` |
| Instance | `InstanceContainer` | `InstanceContainer` |

The light engine belongs in the Falco server because the loader is only half of what somebody flying
looks at. `FalcoLightingChunk` needs no calls from the outside — `instance.setChunkSupplier(scheduler.supplier())`
and every chunk reports its own changes — which is the same one-line setup Minestom asks for with
`LightingChunk::new`. The two sides are therefore compared at the same level of effort.

**`FalcoInstance` is deliberately not in it**, and for a hard reason rather than a preference.
`FalcoInstance` accepts only `FalcoChunk`: `Chunk#onLoad` and `Chunk#unload` are package-private in
Minestom, `FalcoChunk` re-exposes them, and an instance in another package has no other way to reach
them — so it refuses anything else with a `FalcoInstanceException` on the first chunk it loads.
`FalcoLightingChunk` extends `DynamicChunk` and is not a `FalcoChunk`, so the two cannot be combined
at all. Given that choice the light wins: what `FalcoInstance` buys — a clean unregister and a block
write guarded per chunk rather than per instance — is invisible to somebody flying through a world
nobody edits, while the light is the first thing they look at. Running both servers on the same
`InstanceContainer` has a second benefit worth as much: the two then differ in the loader and the
chunk type and in nothing else.

---

# The measurement — a number you can compare

```bash
./gradlew :falco-demo:runFalcoLoader        # net.onelitefeather.falco.anvil.FalcoAnvilLoader
./gradlew :falco-demo:runMinestomLoader     # net.minestom.server.instance.anvil.AnvilLoader
```

The two tasks run the identical code over the identical chunks in the identical order. The loader is
the only difference. Run them back to back on an otherwise idle machine; they are separate processes,
so anything else you are doing lands in one of them and not in the other.

Both accept the same options:

| Option | Default | What it changes |
| --- | --- | --- |
| `-Pthreads=<n>` | `min(4, processors)` | how many chunks are loaded at the same time |
| `-Pchunks=<n>` | `64` | chunks per round; raise it for longer, steadier rounds |
| `-Pwarmup=<n>` | `3` | rounds run and printed before the measurement starts |
| `-Prounds=<n>` | `10` | measured rounds, at least two |
| `-Pdimension=<key>` | `minecraft:overworld` | which dimension to read |

**Vary `-Pthreads`.** It is the option that decides what the run is about, which is why the report
prints it next to every figure — see below.

## Reading the result

The report opens with the conditions — loader, world, dimension, chunks, threads, cores, heap, jvm —
then lists the warm-up rounds, then the measured ones, then the summary:

```
  Per round           17.3 ms  ± 5.3 ms  (31 %)
                      smallest 12.6 ms, largest 31.3 ms
  Per chunk           0.0676 ms  ± 0.0207 ms
```

- **The warm-up is printed and then thrown away.** The first round of a fresh jvm pays for class
  loading, for the interpreter running the whole codec path before it is compiled, and for the first
  read of every region file. It is usually several times a settled round, and you can watch it settle
  in those lines.
- **The `±` is one sample standard deviation over the measured rounds.** It is not a confidence
  interval. If the two loaders differ by less than their spreads overlap, this demo has not told you
  which is faster.
- **Above ten percent relative deviation the report says so** and asks you to close what else is
  running and raise `-Prounds`.
- **Figures are cut to three significant digits**, which is roughly what this instrument supports.

## What this does not tell you

- **It is not a benchmark.** No forks, no statistical model, no isolation. For anything you intend to
  quote, use the JMH benchmarks in [`falco-benchmarks`](../falco-benchmarks) — see
  [Benchmarking](https://github.com/OneLiteFeatherNET/Falco/wiki/Benchmarking) — and report the
  machine, the jvm and the JMH configuration alongside the number, as that document requires.
- **A number without its thread count means nothing here.** The Falco loader wins on lock
  granularity, which by definition cannot show itself without contention. Single threaded it is if
  anything the slower of the two; the JMH comparison has its region file about a tenth behind
  Minestom's at one thread and several times ahead from two upwards. A demo run at `-Pthreads=1` that
  finds Falco slower has reproduced a known result, not found a defect.
- **It is not a disk measurement.** After the warm-up the page cache is warm, so this measures the
  code path above the disk — which is where the two loaders differ.
- **The chunks come from one corner of the world.** The listing walks the region files in a fixed
  order and takes the first `-Pchunks` chunks that exist, so with the default they usually all come
  from one region file. That is the contended case, and the one the loader is about.
- **Watch the returned chunk count.** If the two runs return different numbers they did not do the
  same work: the Falco loader skips a chunk that is not fully generated, the Minestom loader loads
  it. The report prints the count for every round.

---

## An old world reads on both sides

Minestom's `AnvilLoader(Path, Key)` resolves **only** `dimensions/<namespace>/<value>/region` and has
no fallback, so on a world written before 26.1 it finds nothing at all — and a loader that finds
nothing is spectacularly fast. `FalcoAnvilLoader` handles both layouts. `LoaderKind` therefore hands
a world in the old layout to Minestom's deprecated single-argument constructor, which is the only way
to make that loader read it, and both the measurement and the server print which layout they found.
The server additionally warns when the spawn chunk is listed in the region header but arrives without
a single block, which is what a loader looking in the wrong directory looks like from the inside.

## How it is put together

| Type | Responsibility |
| --- | --- |
| `ChunkLoadDemo` | the entry point of the two measurement tasks |
| `DemoOptions` | their command line, with the bounds that keep a figure meaningful |
| `LoadMeasurement` | the rounds, the fixed thread pool and the stopwatch |
| `Statistics` | mean, sample standard deviation, extremes |
| `DemoServer` | the entry point of the two server tasks |
| `ServerOptions` | their command line |
| `ServerStack` | the single place the two servers differ, and the reasoning behind it |
| `TimingChunkLoader` | wraps the loader under test and times the one call the two stacks differ in |
| `SampleWindow` | the recent samples of one figure, with the outlier a mean would remove |
| `LiveMetrics` | chunk durations, throughput and tick times of a running server |
| `LiveStatusLine` | the action bar, the log line and the `/falco` block |
| `WorldLocator` | which directory is the world, and which of the two layouts it uses — shared |
| `ChunkInventory` | which chunks actually exist, read from the region headers — shared |
| `LoaderKind` | builds either loader; the single place the two loaders are constructed — shared |
| `DemoReport` | the report, and the messages for a missing world or a refused command line — shared |

Everything except the server start itself has a test; the stopwatch inside `TimingChunkLoader` is
exercised by starting a server rather than asserted, the same way the loaders are exercised by
running the measurement tasks. The measurement runs on a fixed pool of
platform threads rather than on the virtual threads Minestom would start per chunk, because the
thread count is the condition the whole result is reported under and it has to be exact. The server
does the opposite on purpose: it lets Minestom schedule chunk loads exactly as it would for a real
player, because that scheduling is part of what is being judged.
