# Build Falco from source

Compile Falco itself, run its tests and render its Javadoc. Needed only to work on Falco. Consuming
the published artefacts needs none of this — that is [How-to Add Falco to your build](add-falco-to-your-build.md).

**Before you start:** Java 25, and OneLiteFeather Maven credentials. Falco compiles against Minestom
and the internal `mycelium-bom`, both served from an authenticated endpoint.

1. Export the credentials:

   ```bash
   export ONELITEFEATHER_MAVEN_USERNAME=...
   export ONELITEFEATHER_MAVEN_PASSWORD=...
   ```

2. Build — this compiles, renders Javadoc and runs the tests:

   ```bash
   ./gradlew build
   ```

3. Render the Javadoc on its own, one tree per module under
   `falco-<module>/build/docs/javadoc/index.html`:

   ```bash
   ./gradlew javadoc
   ```

4. Build the benchmark jar. A build never runs the benchmarks:

   ```bash
   ./gradlew :falco-benchmarks:jmhJar
   ```

## Check it worked

`./gradlew build` reports `BUILD SUCCESSFUL` and each library module has a jar under
`falco-<module>/build/libs/`.

## If it does not work

A 401 during dependency resolution means the two environment variables from step 1 are unset or
wrong. This is the failure that actually occurs; it is not a repository outage.

See also: [Build Setup](../contributing/build-setup.md) for what the build is made of ·
[How-to Run the JMH benchmark suite](run-the-jmh-benchmark-suite.md) to run what step 4 produced.
