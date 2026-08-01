plugins {
    alias(libs.plugins.jmh)
}

description = "JMH benchmarks for the loader and the light engine. Never published."

dependencies {
    jmhImplementation(platform(libs.mycelium.bom))
    jmhImplementation(platform(libs.adventure.bom))
    jmhImplementation(project(":falco-anvil"))
    jmhImplementation(project(":falco-light"))
    jmhImplementation(libs.adventure.nbt)
    jmhImplementation(libs.annotations)
    jmhImplementation(libs.jmh.core)
    jmhImplementation(libs.minestom)
    jmhImplementation(libs.fastutil)
}

jmh {
    jmhVersion.set(libs.versions.jmh)
    includeTests.set(false)
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh/results.json"))
    humanOutputFile.set(layout.buildDirectory.file("reports/jmh/human.txt"))

    val include = providers.gradleProperty("jmh.include").orNull

    if (include != null) {
        includes.set(listOf(include))
    }
}

tasks {
    named("check") {
        dependsOn(named("compileJmhJava"))
    }
}
