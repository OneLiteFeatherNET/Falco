# Table of contents

* [Falco](README.md)

## Tutorials

* [Load your first world with Falco](tutorials/load-your-first-world-with-falco.md)

## How-to guides

* [How-to guides](how-to/README.md)
  * [Add Falco to your build](how-to/add-falco-to-your-build.md)
  * [Load an Anvil world](how-to/load-an-anvil-world.md)
  * [Compute light for a loaded world](how-to/compute-light-for-a-loaded-world.md)
  * [Keep chunk light up to date automatically](how-to/keep-chunk-light-up-to-date-automatically.md)
  * [Use FalcoInstance instead of InstanceContainer](how-to/use-falcoinstance-instead-of-instancecontainer.md)
  * [Migrate a world from an older version](how-to/migrate-a-world-from-an-older-version.md)
  * [Replace the version and unknown-entry policies](how-to/replace-the-version-and-unknown-entry-policies.md)
  * [Build Falco from source](how-to/build-falco-from-source.md)
  * [Run the JMH benchmark suite](how-to/run-the-jmh-benchmark-suite.md)
  * [Reproduce a published measurement](how-to/reproduce-a-published-measurement.md)

## Reference

* [Reference](reference/README.md)
  * [Modules and coordinates](reference/modules-and-coordinates.md)
  * [Supported versions](reference/supported-versions.md)
  * [Measured results](reference/measured-results.md)
  * [API surface and stability](reference/api-surface-and-stability.md)
  * [Replaceable policies](reference/replaceable-policies.md)
  * [Migration modes](reference/migration-modes.md)
  * [Exceptions and faults](reference/exceptions-and-faults.md)
  * [Logging](reference/logging.md)
  * [Benchmark catalogue](reference/benchmark-catalogue.md)

## Explanation

* [Explanation](explanation/README.md)
  * [Choosing between Falco and the built-in loader](explanation/choosing-between-falco-and-the-built-in-loader.md)
  * [Scope and non-goals](explanation/scope-and-non-goals.md)
  * [When light computation actually runs](explanation/when-light-computation-actually-runs.md)
  * [Measurement](explanation/what-a-measurement-here-means.md)
  * [Choosing between FalcoInstance and InstanceContainer](explanation/choosing-between-falcoinstance-and-instancecontainer.md)
  * [How the Anvil loader is built](explanation/how-the-anvil-loader-is-built.md)
  * [How the light engine works](explanation/how-the-light-engine-works.md)
  * [Why the concurrency design is what it is](explanation/how-the-concurrency-design-works.md)
  * [How world migration works](explanation/how-world-migration-works.md)
  * [The chunk version guard](explanation/the-chunk-version-guard.md)
  * [Why Falco has its own Anvil chunk loader](explanation/why-a-second-anvil-loader.md)
  * [Why Falco has its own light engine](explanation/why-a-custom-light-engine.md)
  * [Why falco-instance exists](explanation/why-falco-instance-exists.md)
  * [Comparing the light engine with Minestom's](explanation/comparing-the-light-engine-with-minestoms.md)
  * [What the benchmarks establish](explanation/what-the-benchmarks-establish.md)

## Project record

* [Project record](project-record/README.md)
* [Status — Anvil chunk loader and light engine](project-record/project-status.md)
* [Research notes](project-record/research/README.md)
* [Research: could `falco-instance` be faster, and how would anyone know?](project-record/research/instance-performance-research.md)
* [Research: exception hierarchy for the Anvil package](project-record/research/exception-hierarchy.md)
* [Research: a fluent construction API for the three modules](project-record/research/fluent-api.md)
* [Research: a multithreaded `InstanceContainer` replacement](project-record/research/instance-container.md)
* [Research: a faster, lower-memory light engine](project-record/research/light-engine.md)
* [Research: shared instances and batch integration for a foreign instance](project-record/research/shared-instances-and-batches.md)

## Contributing

* [Contributing](contributing/README.md)
  * [Build setup](contributing/build-setup.md)
  * [Dependency management](contributing/dependency-management.md)
  * [Versioning and releases](contributing/versioning-and-releases.md)
  * [Publishing](contributing/publishing.md)
  * [Testing and Javadoc](contributing/testing-and-javadoc.md)
  * [Benchmarks and demo](contributing/benchmarks-and-demo.md)
  * [Architecture rules](contributing/architecture-rules.md)
  * [Documentation conventions](contributing/documentation-conventions.md)
  * [Benchmark baselines](benchmarks/README.md)
