import org.gradle.process.CommandLineArgumentProvider

description = "A runnable comparison of the Falco and the Minestom chunk loader on your own world. Never published."

dependencies {
    implementation(platform(libs.mycelium.bom))
    implementation(project(":falco-anvil"))
    implementation(project(":falco-light"))
    implementation(project(":falco-instance"))
    implementation(libs.minestom)
    implementation(libs.slf4j.api)

    compileOnly(libs.annotations)

    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.cyano)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.named("check") {
    dependsOn(tasks.named("javadoc"))
}

val demoMain = "net.onelitefeather.falco.demo.ChunkLoadDemo"
val demoGroup = "falco demo"

val mainSourceSet = extensions.getByType<SourceSetContainer>().named("main")
val toolchains = extensions.getByType<JavaToolchainService>()

val demoWorld = providers.gradleProperty("world")
    .orElse(layout.projectDirectory.dir("world").asFile.absolutePath)

fun JavaExec.configureDemo(loader: String) {
    group = demoGroup
    mainClass.set(demoMain)
    classpath = mainSourceSet.get().runtimeClasspath
    javaLauncher.set(toolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })

    systemProperty("falco.demo.world", demoWorld.get())

    argumentProviders.add(CommandLineArgumentProvider {
        val options = mutableListOf("--loader=$loader")
        for (name in listOf("threads", "chunks", "warmup", "rounds", "dimension")) {
            providers.gradleProperty(name).orNull?.let { options += "--$name=$it" }
        }
        options
    })
}

tasks.register<JavaExec>("runFalcoLoader") {
    configureDemo("falco")
    description = "Measures chunk loading from falco-demo/world with net.onelitefeather.falco.anvil.FalcoAnvilLoader."
}

tasks.register<JavaExec>("runMinestomLoader") {
    configureDemo("minestom")
    description = "Measures chunk loading from falco-demo/world with net.minestom.server.instance.anvil.AnvilLoader."
}

val serverMain = "net.onelitefeather.falco.demo.DemoServer"

val viewDistance = providers.gradleProperty("viewDistance").orElse("10")

fun JavaExec.configureServer(stack: String) {
    group = demoGroup
    mainClass.set(serverMain)
    classpath = mainSourceSet.get().runtimeClasspath
    javaLauncher.set(toolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })

    systemProperty("falco.demo.world", demoWorld.get())
    systemProperty("minestom.chunk-view-distance", viewDistance.get())

    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "info")
    systemProperty("org.slf4j.simpleLogger.showDateTime", "true")
    systemProperty("org.slf4j.simpleLogger.dateTimeFormat", "HH:mm:ss")

    argumentProviders.add(CommandLineArgumentProvider {
        val options = mutableListOf("--stack=$stack")
        for (name in listOf("port", "dimension", "report")) {
            providers.gradleProperty(name).orNull?.let { options += "--$name=$it" }
        }
        options
    })

    isIgnoreExitValue = true
}

tasks.register<JavaExec>("runFalcoServer") {
    configureServer("falco")
    description = "Runs a joinable server on falco-demo/world with the Falco loader and the Falco light engine."
}

tasks.register<JavaExec>("runMinestomServer") {
    configureServer("minestom")
    description = "Runs a joinable server on falco-demo/world with Minestom's own AnvilLoader and LightingChunk."
}
