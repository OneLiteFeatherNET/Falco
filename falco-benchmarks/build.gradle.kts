plugins {
    alias(libs.plugins.jmh)
}

description = "JMH benchmarks for the loader and the light engine. Never published."

dependencies {
    jmhImplementation(platform(libs.mycelium.bom))
    jmhImplementation(platform(libs.adventure.bom))
    jmhImplementation(project(":falco-anvil"))
    jmhImplementation(project(":falco-light"))
    jmhImplementation(project(":falco-instance"))
    jmhImplementation(libs.adventure.nbt)
    jmhImplementation(libs.annotations)
    jmhImplementation(libs.jmh.core)
    jmhImplementation(libs.jol.core)
    jmhImplementation(libs.minestom)
    jmhImplementation(libs.fastutil)
    jmhImplementation(libs.flare.fastutil)

    testImplementation(platform(libs.mycelium.bom))
    testImplementation(platform(libs.adventure.bom))
    testImplementation(sourceSets["jmh"].output)
    testImplementation(project(":falco-anvil"))
    testImplementation(project(":falco-instance"))
    testImplementation(libs.adventure.nbt)
    testImplementation(libs.annotations)
    testImplementation(libs.jmh.core)
    testImplementation(libs.jol.core)
    testImplementation(libs.minestom)
    testImplementation(libs.fastutil)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

jmh {
    jmhVersion.set(libs.versions.jmh)
    includeTests.set(false)
    resultFormat.set("JSON")

    val include = providers.gradleProperty("jmh.include").orNull
    val quick = providers.gradleProperty("jmh.quick").isPresent
    val threads = providers.gradleProperty("jmh.threads").orNull
    val forks = providers.gradleProperty("jmh.forks").orNull
    val params = providers.gradleProperty("jmh.params").orNull
    val resultsPath = providers.gradleProperty("jmh.resultsFile").orNull
    val humanPath = providers.gradleProperty("jmh.humanFile").orNull

    if (resultsPath != null) {
        resultsFile.set(rootProject.file(resultsPath))
    } else {
        resultsFile.set(
            layout.buildDirectory.file(if (quick) "reports/jmh/results-quick.json" else "reports/jmh/results.json")
        )
    }

    if (humanPath != null) {
        humanOutputFile.set(rootProject.file(humanPath))
    } else {
        humanOutputFile.set(layout.buildDirectory.file("reports/jmh/human.txt"))
    }

    profilers.set(if (providers.gradleProperty("jmh.noProfiler").isPresent) emptyList() else listOf("gc"))

    if (include != null) {
        includes.set(listOf(include))
    }

    if (quick) {
        fork.set(1)
        warmupIterations.set(2)
        iterations.set(3)
    }

    if (threads != null) {
        this.threads.set(threads.toInt())
    }

    if (forks != null) {
        fork.set(forks.toInt())
    }

    params?.split(';')?.filter { it.isNotBlank() }?.forEach { entry ->
        val name = entry.substringBefore('=').trim()
        val values = entry.substringAfter('=').split(',').map { it.trim() }.filter { it.isNotEmpty() }

        require(name.isNotEmpty() && values.isNotEmpty()) {
            "jmh.params expects name=value[,value][;name=value], got '$entry'"
        }

        benchmarkParameters.put(name, objects.listProperty(String::class.java).value(values))
    }
}

tasks {
    named("check") {
        dependsOn(named("compileJmhJava"))
    }

    withType<Test>().configureEach {
        val compactHeaders = providers.gradleProperty("falco.compactHeaders").isPresent
        val onMacOs = providers.systemProperty("os.name").getOrElse("").startsWith("Mac")
        val forceOnMacOs = providers.gradleProperty("falco.macOsFootprintTests").isPresent

        onlyIf("footprint measurement hangs on macOS; see docs/benchmarks/README.md") {
            forceOnMacOs || !onMacOs
        }

        maxHeapSize = "4g"
        jvmArgs("-Djdk.attach.allowAttachSelf=true")
        jvmArgs("-XX:+EnableDynamicAgentLoading")
        jvmArgs("-Djol.magicFieldOffset=true")
        jvmArgs(if (compactHeaders) "-XX:+UseCompactObjectHeaders" else "-XX:-UseCompactObjectHeaders")
        systemProperty("falco.compactHeaders", compactHeaders.toString())

        providers.gradlePropertiesPrefixedBy("falco.census.").get().forEach { (name, value) ->
            systemProperty(name, value)
        }
    }
}
