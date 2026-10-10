# Choosing between FalcoInstance and InstanceContainer

Whether `falco-instance` is worth adopting, and where `InstanceContainer` remains the right choice.

Take it if any of these apply:

- **You unregister worlds while the server runs.** `InstanceManager#unregisterInstance` unloads
  chunks only for an `InstanceContainer`; every chunk, tick partition and entity of any other
  instance is left behind. This is the reason the module exists.
- **You want the light engine to keep a chunk up to date without a listener of your own.**
  `ChunkLightScheduler#supplier()` produces `FalcoChunk`s, and since `1.0.0` those carry Falco's
  light *and* Falco's lifecycle on one class. Before `1.0.0` this combination did not work at all.
- **Empty sections cost you.** A chunk that allocates its sections on demand retains 25 objects and
  840 bytes where Minestom's retains 192 and 6 848, and on a generated overworld 62.24 % of sections
  hold nothing. The tables and what they do *not* say are in
  [Counted](../reference/measured-results.md#counted).

Keep using `InstanceContainer` if you need `SharedInstance` views onto the world you are building —
see [Shared worlds](why-falco-instance-exists.md) for what is and is not possible — or if you are relying on any of
the four Minestom sites that branch on `instanceof InstanceContainer`.

**No speed claim.** Nothing here is faster, no benchmark in this repository says it is, and the
memory figures above are counts rather than timings. The distinction is spelled out in
[No speed gain is claimed](why-falco-instance-exists.md#no-speed-gain-is-claimed).


## What the chunk does differently

`FalcoChunk extends Chunk` and holds a `BlockStorage`. Sections are created on the first write into
them; every section that holds nothing shares one instance. The read-only accessors that avoid
materialising a section are in [How-to Use FalcoInstance instead of InstanceContainer](../how-to/use-falcoinstance-instead-of-instancecontainer.md).

The counts per operation — a `setBlock` at y = 64 materialises ten sections, all of them the
heightmap descent rather than the write — are in [Reference Measured results](../reference/measured-results.md).


## Sources

- [Why falco-instance exists](why-falco-instance-exists.md) — why the module exists, the four
  places a foreign instance behaves differently, and what it refuses to attempt
- [Counted](../reference/measured-results.md#counted) — the object and byte figures quoted above, with what they do
  not say
- [Research: Instance Container](research/instance-container.md) — the investigation that preceded it
- [Project Status](../contributing/project-status.md#instance) — the working record, including what is still open

Related: [Explanation Why falco-instance exists](why-falco-instance-exists.md) · [Explanation Scope and non-goals](scope-and-non-goals.md) ·
[How-to Use FalcoInstance instead of InstanceContainer](../how-to/use-falcoinstance-instead-of-instancecontainer.md)
