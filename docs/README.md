# Falco

Falco is a high-performance Anvil chunk loader and light engine for
[Minestom](https://github.com/Minestom/Minestom), plus an `Instance` implementation that cleans up
after itself and a migration engine for worlds older than the server you run. Four modules, no
dependency between them, all opt-in.

The [repository README](https://github.com/OneLiteFeatherNET/Falco#readme) says what the project is
and carries a five-step quick start. Everything below is the documentation.

---

**New here?** [Tutorial Load your first world with Falco](tutorials/load-your-first-world-with-falco.md) takes you from an empty Gradle project to
a server serving a stored world with working light, in about fifteen minutes.

**Trying to get something done?** The how-to guides cover adding Falco to a build, loading a world,
computing light, migrating an old world and running the benchmarks.

**Looking up a value?** [Reference Modules and coordinates](reference/modules-and-coordinates.md) for artefacts and endpoints,
[Reference Supported versions](reference/supported-versions.md) for the floors, [Reference Measured results](reference/measured-results.md) for every published
table, [Reference Exceptions and faults](reference/exceptions-and-faults.md) for what a failure means.

**Wondering how it works, or why?** [Explanation Choosing between Falco and the built-in loader](explanation/choosing-between-falco-and-the-built-in-loader.md)
and [Explanation Scope and non-goals](explanation/scope-and-non-goals.md) are the two pages most people need first.

---

## Getting started

- [Tutorial Load your first world with Falco](tutorials/load-your-first-world-with-falco.md) — the one guided path through the project.

## How-to guides

**Setting up**

- [How-to Add Falco to your build](how-to/add-falco-to-your-build.md)
- [How-to Build Falco from source](how-to/build-falco-from-source.md)

**Loading worlds**

- [How-to Load an Anvil world](how-to/load-an-anvil-world.md)
- [How-to Migrate a world from an older version](how-to/migrate-a-world-from-an-older-version.md)
- [How-to Replace the version and unknown-entry policies](how-to/replace-the-version-and-unknown-entry-policies.md)

**Light**

- [How-to Compute light for a loaded world](how-to/compute-light-for-a-loaded-world.md)
- [How-to Keep chunk light up to date automatically](how-to/keep-chunk-light-up-to-date-automatically.md)

**Instances**

- [How-to Use FalcoInstance instead of InstanceContainer](how-to/use-falcoinstance-instead-of-instancecontainer.md)

**Measuring**

- [How-to Run the JMH benchmark suite](how-to/run-the-jmh-benchmark-suite.md)
- [How-to Reproduce a published measurement](how-to/reproduce-a-published-measurement.md)

## Reference

- [Reference Modules and coordinates](reference/modules-and-coordinates.md) — artefacts, repositories, Javadoc addresses
- [Reference Supported versions](reference/supported-versions.md) — Java, Minestom, the chunk version floor, compression schemes
- [Reference API surface and stability](reference/api-surface-and-stability.md) — what is experimental and what that means
- [Reference Replaceable policies](reference/replaceable-policies.md) — the three services and their resolution rules
- [Reference Migration modes](reference/migration-modes.md) — the modes, their cost, and the backup
- [Reference Exceptions and faults](reference/exceptions-and-faults.md) — the exception types and what each entry point does on failure
- [Reference Logging](reference/logging.md) — loggers, levels, message schema, throttling
- [Reference Measured results](reference/measured-results.md) — **every published table, with the run behind each one**
- [Reference Benchmark catalogue](reference/benchmark-catalogue.md) — every benchmark class and its parameters

**[Reference Measured results](reference/measured-results.md) owns every figure quoted anywhere in this documentation.** Where a table
disagrees with anything printed outside it, the table wins.

## Background

**Deciding whether to adopt**

- [Explanation Choosing between Falco and the built-in loader](explanation/choosing-between-falco-and-the-built-in-loader.md)
- [Explanation Choosing between FalcoInstance and InstanceContainer](explanation/choosing-between-falcoinstance-and-instancecontainer.md)
- [Explanation When light computation actually runs](explanation/when-light-computation-actually-runs.md) — for most Falco workloads, it does not
- [Explanation Scope and non-goals](explanation/scope-and-non-goals.md) — what Falco deliberately does not do

**How it works**

- [Explanation How the Anvil loader is built](explanation/how-the-anvil-loader-is-built.md)
- [Explanation How the light engine works](explanation/how-the-light-engine-works.md)
- [Explanation How the concurrency design works](explanation/how-the-concurrency-design-works.md)
- [Explanation How world migration works](explanation/how-world-migration-works.md)
- [Explanation The chunk version guard](explanation/the-chunk-version-guard.md)

**Why it is built this way**

- [Explanation Why a second Anvil loader](explanation/why-a-second-anvil-loader.md)
- [Explanation Why a custom light engine](explanation/why-a-custom-light-engine.md)
- [Explanation Why falco-instance exists](explanation/why-falco-instance-exists.md)
- [Explanation Comparing the light engine with Minestoms](explanation/comparing-the-light-engine-with-minestoms.md)

**What the numbers mean**

- [Explanation What a measurement here means](explanation/what-a-measurement-here-means.md) — the one definition of what the `±` covers
- [Explanation What the benchmarks establish](explanation/what-the-benchmarks-establish.md)

## Project record

Not documentation of the software — the record of building it. Kept because each answers a question
that will be asked again.

- [Project Status](project-record/project-status.md) — what is in the branch, the defects found and fixed, what is still open, and
  what was investigated and deliberately not built
- [Research](project-record/research/README.md) — the index of the investigations
- [Documentation conventions](contributing/documentation-conventions.md) — how this documentation is organised and which page owns which fact

## Working on Falco

- [Contributing](contributing/README.md) — what a change is built, tested and released with
- [Build Setup](contributing/build-setup.md) — module structure and why `falco-bom` is special-cased
- [Versioning and Releases](contributing/versioning-and-releases.md) · [Dependency Management](contributing/dependency-management.md) · [Publishing](contributing/publishing.md)
- [Testing and Javadoc](contributing/testing-and-javadoc.md) · [Benchmarks and Demo](contributing/benchmarks-and-demo.md) · [Architecture Rules](contributing/architecture-rules.md)
