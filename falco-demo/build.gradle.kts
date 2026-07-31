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
