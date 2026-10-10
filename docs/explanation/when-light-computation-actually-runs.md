# When light computation actually runs

Whether `falco-light` is relevant to your server at all depends on one thing: does the light code
path execute on your workload? For the dominant Falco use case it does not.

Honest answer first, because it decides whether the package is relevant at all:

| Workload | Does light computation run? |
| --- | --- |
| Loading pre-lit worlds from `.mca` | **No.** The stored light is applied with `Light#set`, which clears `requiresUpdate()`. Nothing is recomputed — unless you call this engine explicitly. |
| Generated worlds without stored light | Yes |
| Runtime block placement | Yes |

For loading pre-built maps from region files — the dominant Falco use case — a light engine
contributes nothing, because that code path never executes. That is a structural statement, not a
measured one: the stored arrays go straight into the section through `Light#set`, which clears the
update flag, so nothing asks either engine for a result. What the load path does cost instead is NBT
parsing and zlib inflation, and that ordering comes from one-off micro-measurements taken while
designing the loader rather than from any published table — treat it as an order of magnitude and
nothing finer.


## What this means in practice

If you load pre-lit worlds from `.mca` files and nothing else, neither light engine contributes
anything to your frame time, and swapping one for the other changes nothing you can measure. The
cost of that path is NBT parsing and zlib inflation, and that is where a chunk-loading decision
matters — see [Explanation Choosing between Falco and the built-in loader](choosing-between-falco-and-the-built-in-loader.md).

`falco-light` becomes relevant the moment something has to *produce* light rather than replay it:
generated worlds with no stored light, runtime block placement, or an explicit call into the engine.

## What this is not

This page does not claim `falco-light` is faster than Minestom's engine on the workloads where it
does run. That comparison, including where the lead shrinks, is
[Explanation Comparing the light engine with Minestoms](comparing-the-light-engine-with-minestoms.md).

Related: [How-to Compute light for a loaded world](../how-to/compute-light-for-a-loaded-world.md) ·
[Explanation Why a custom light engine](why-a-custom-light-engine.md)
