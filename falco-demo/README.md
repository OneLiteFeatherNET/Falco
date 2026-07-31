# falco-demo

Put your own world in here and measure, on your own machine, whether Falco or Minestom loads its
chunks faster. Nothing in this module is published; it exists so the claim in
[`STATUS.md`](../STATUS.md) can be checked rather than believed.

## Put the world here

Copy your world folder into [`world/`](world). The result should look like `world/my-world/`.

Copy the **world root** — the folder that holds `level.dat` and either a `region/` directory or a
`dimensions/` directory. Not the `region/` directory itself: both loaders resolve
`<world>/dimensions/<namespace>/<value>/region` and fall back to `<world>/region`, so handing them
the region directory leaves them looking for a `region` inside it. Both layouts work, and the demo
tells you which one it used.

Keep exactly one world in there. Nothing you put in that directory is ever committed — it carries
its own `.gitignore` — and starting a run without a world prints what is missing and where it goes
instead of a stack trace.

## Run it twice

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
  [`docs/benchmarks.md`](../docs/benchmarks.md) — and report the machine, the jvm and the JMH
  configuration alongside the number, as that document requires.
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

## How it is put together

| Type | Responsibility |
| --- | --- |
| `ChunkLoadDemo` | the entry point both tasks start |
| `DemoOptions` | the command line, with the bounds that keep a figure meaningful |
| `WorldLocator` | which directory is the world, and which of the two layouts it uses |
| `ChunkInventory` | which chunks actually exist, read from the region headers before anything is timed |
| `LoaderKind` | builds either loader; the single place the two runs differ |
| `LoadMeasurement` | the rounds, the fixed thread pool and the stopwatch |
| `Statistics` | mean, sample standard deviation, extremes |
| `DemoReport` | the output, including everything it refuses to claim |

Everything except the server start has a test. The measurement runs on a fixed pool of platform
threads rather than on the virtual threads Minestom would start per chunk, because the thread count
is the condition the whole result is reported under and it has to be exact.
