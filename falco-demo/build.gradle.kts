import org.gradle.process.CommandLineArgumentProvider

description = "A runnable comparison of the Falco and the Minestom chunk loader on your own world. Never published."

// This module is deliberately absent from the publish list in the root build. It is a tool for the
// person who wants to check the loader claim on their own hardware, not something a consumer of the
// library should ever resolve — exactly like falco-benchmarks.
dependencies {
    // Minestom is compileOnly in every library module because the consumer picks the version. Here
    // it is the opposite: this module starts a real server and calls the loader Minestom ships
    // with, so it needs Minestom on the runtime classpath. `implementation` gives it both.
    implementation(platform(libs.mycelium.bom))
    implementation(project(":falco-anvil"))
    // The Falco server hands its instance a FalcoLightingChunk through ChunkLightScheduler, which is
    // the half of the stack the loader cannot show: whether the light of a streamed-in chunk is
    // right is the first thing anybody looks at while flying.
    implementation(project(":falco-light"))
    // Not used to run anything. FalcoInstance is named in the log and in the javadoc of ServerStack
    // as the component this demo deliberately leaves out, and a reader following that explanation
    // should land in the real type rather than in a string that was accurate when it was written.
    implementation(project(":falco-instance"))
    implementation(libs.minestom)
    implementation(libs.slf4j.api)

    // The annotations are CLASS retention and never read at runtime, so compileOnly is enough even
    // though everything else here has to be present when the demo runs.
    compileOnly(libs.annotations)

    // Without a binding slf4j prints a warning to stderr before the demo prints its first line.
    // src/main/resources/simplelogger.properties turns the level down so the report stays readable.
    runtimeOnly(libs.slf4j.simple)

    // No cyano and no MicrotusExtension. Everything tested here is plain logic — finding the world,
    // reading a region header, turning samples into a mean and a spread. The server start itself is
    // exercised by running the two tasks, not by a test.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

// The root build puts -Werror on every Javadoc task, but only the published modules ever run one:
// `build` reaches `javadoc` through `withJavadocJar()`, which this module does not apply. Without
// the line below the convention "javadoc on every class and method" would hold for three modules
// and quietly not for this one. Wiring it into `check` restores it, the same way falco-benchmarks
// wires its jmh compilation in.
tasks.named("check") {
    dependsOn(tasks.named("javadoc"))
}

// Both run tasks execute the identical class with the identical measurement; the loader is the only
// thing that differs. Anything else would make the comparison meaningless, so the configuration
// lives in one place and the two registrations only pass a different --loader.
val demoMain = "net.onelitefeather.falco.demo.ChunkLoadDemo"
val demoGroup = "falco demo"

// Type safe accessors such as `sourceSets` or `javaToolchains` are not used here. The java-library
// plugin is applied by the `subprojects` block of the root build rather than by a `plugins` block in
// this file, and relying on accessor generation across that boundary is exactly the kind of thing
// that breaks on a gradle upgrade.
val mainSourceSet = extensions.getByType<SourceSetContainer>().named("main")
val toolchains = extensions.getByType<JavaToolchainService>()

fun JavaExec.configureDemo(loader: String) {
    group = demoGroup
    mainClass.set(demoMain)
    classpath = mainSourceSet.get().runtimeClasspath
    // The measurement must run on the same jvm the module is compiled for. Taking whatever jvm
    // happens to run the gradle daemon would put an unreported variable into every number.
    javaLauncher.set(toolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })

    // The directory the world is looked for in is passed explicitly. Relying on the working
    // directory of a JavaExec would make the demo depend on a gradle detail, and a run started from
    // an ide would silently look somewhere else.
    systemProperty("falco.demo.world", layout.projectDirectory.dir("world").asFile.absolutePath)

    // The knobs are gradle properties rather than task arguments, so a run reads
    // `./gradlew :falco-demo:runFalcoLoader -Pthreads=8`.
    argumentProviders.add(CommandLineArgumentProvider {
        val options = mutableListOf("--loader=$loader")
        for (name in listOf("threads", "chunks", "warmup", "rounds", "dimension")) {
            providers.gradleProperty(name).orNull?.let { options += "--$name=$it" }
        }
        options
    })

    // isIgnoreExitValue is deliberately left alone. The demo exits zero for everything it handles
    // itself, including a missing world, so a non-zero status really is a defect and should still
    // fail the build instead of scrolling past.
}

tasks.register<JavaExec>("runFalcoLoader") {
    configureDemo("falco")
    description = "Measures chunk loading from falco-demo/world with net.onelitefeather.falco.anvil.FalcoAnvilLoader."
}

tasks.register<JavaExec>("runMinestomLoader") {
    configureDemo("minestom")
    description = "Measures chunk loading from falco-demo/world with net.minestom.server.instance.anvil.AnvilLoader."
}

// The second half of the module: a server somebody can join and judge by eye. It shares the world,
// the toolchain and the option style with the measurement above, and for the same reason the two
// measurement tasks share theirs — the comparison is only worth something if the two servers differ
// in the stack and in nothing else, so everything except --stack is configured once here.
val serverMain = "net.onelitefeather.falco.demo.DemoServer"

// The view distance decides how many chunks are streamed at all and is therefore the condition the
// whole session is read under. It has to be the same on both servers, which is why it is set here
// and not per task.
//
// It travels as a jvm system property rather than as a command line argument, and that is forced by
// Minestom: ServerFlag.CHUNK_VIEW_DISTANCE is a static final read from minestom.chunk-view-distance
// when the class is initialised, which happens before main could apply anything. A -PviewDistance
// that only reached our own option parser would be printed in the log while the server used eight.
val viewDistance = providers.gradleProperty("viewDistance").orElse("10")

fun JavaExec.configureServer(stack: String) {
    group = demoGroup
    mainClass.set(serverMain)
    classpath = mainSourceSet.get().runtimeClasspath
    javaLauncher.set(toolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })

    systemProperty("falco.demo.world", layout.projectDirectory.dir("world").asFile.absolutePath)
    systemProperty("minestom.chunk-view-distance", viewDistance.get())

    // src/main/resources/simplelogger.properties turns the level down to warn, because the report of
    // a measurement must not be buried under the registry start. A server is the opposite case: its
    // output *is* the result, and Minestom's own startup lines are part of what tells the user that
    // it came up. A system property wins over the properties file in slf4j-simple.
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

    // Unlike the measurement, where a non-zero status really is a defect, the normal way to end a
    // server is a signal — and a signal leaves the jvm at 130 or 143 no matter how cleanly it shut
    // down. Without this, every session that was stopped with ctrl-c would end in a red BUILD
    // FAILED under the shutdown lines that say the loader closed properly. A genuine failure still
    // prints its stack trace to this console, which is the output somebody watching a server reads
    // anyway.
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
