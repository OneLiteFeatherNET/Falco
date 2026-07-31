plugins {
    alias(libs.plugins.jmh)
}

description = "JMH benchmarks for the loader and the light engine. Never published."

// This module exists because ScalingBenchmark measures the loader and the light engine in one run,
// and because BenchmarkConstants and SectionStates are used from both sides. With a jmh source set
// inside each library module those would have to be duplicated or the two modules chained
// together. Keeping the benchmarks here also keeps the jmh plugin out of the published artefacts.
dependencies {
    jmhImplementation(platform(libs.mycelium.bom))
    jmhImplementation(platform(libs.adventure.bom))
    jmhImplementation(project(":falco-anvil"))
    jmhImplementation(project(":falco-light"))
    jmhImplementation(libs.adventure.nbt)
    jmhImplementation(libs.annotations)
    jmhImplementation(libs.jmh.core)
    // Minestom is needed by the comparison benchmarks, which measure this code against the engine
    // the server ships with. The other benchmarks avoid it on purpose, so those measure this
    // library and not a registry lookup.
    jmhImplementation(libs.minestom)
    jmhImplementation(libs.fastutil)
    // No jmh annotation processor is declared on purpose. The plugin already generates the harness
    // classes with its bytecode generator, and declaring the processor as well makes both of them
    // emit the same classes, which leaves the jar with two copies of every benchmark.
}

jmh {
    jmhVersion.set(libs.versions.jmh)
    // The tests of this project need a Minestom server, which a benchmark jar cannot start.
    includeTests.set(false)
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh/human.txt"))

    // A full run takes the better part of an hour, so a single benchmark has to be reachable
    // without editing this file.
    // Usage: ./gradlew jmh -Pjmh.include='BitPackerBenchmark.pack'
    val include = providers.gradleProperty("jmh.include").orNull

    if (include != null) {
        includes.set(listOf(include))
    }
}

tasks {
    // The benchmarks are compiled by a normal build but never executed by one. A benchmark that
    // stopped compiling after a refactoring should fail like any other source set.
    named("check") {
        dependsOn(named("compileJmhJava"))
    }
}
