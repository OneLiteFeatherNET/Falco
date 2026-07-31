// The root project carries no sources and applies no plugins on purpose: it exists to hold the
// shared version and the configuration every module gets. Applying java-library here would create
// an empty, publishable `falco` artefact next to the two real ones.

// The two published modules always release together, so the version is declared once here and
// inherited by every subproject. Release Please rewrites this single line; nothing else in the
// build carries a version number.
version = "0.2.1" // x-release-please-version

// Every push to main that does not cut a release publishes the current state as a snapshot, and the
// derivation happens here rather than on the command line: a `-Pversion=…` would be silently
// overwritten by the assignment above, which runs later than the property. Reading the released
// version and bumping its patch keeps Release Please's single line the only place a number is
// written, so the marker above stays exactly as the updater expects it.
if (providers.gradleProperty("snapshot").isPresent) {
    val parts = version.toString().substringBefore('-').split('.')
    require(parts.size == 3) { "cannot derive a snapshot from version '$version'" }
    version = "${parts[0]}.${parts[1]}.${parts[2].toInt() + 1}-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    group = "net.onelitefeather"
    version = rootProject.version

    // No repositories are declared here. They come from dependencyResolutionManagement in
    // settings.gradle.kts, and a project-level block would take precedence over it, which would
    // silently drop the OneLiteFeather repository that minestom, cyano and the bom come from.

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    configurations.all {
        resolutionStrategy.cacheDynamicVersionsFor(1, "minutes")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    // The conventions of this project ask for javadoc on every type and member, and the point of
    // that rule is lost if a missing comment only produces a warning nobody reads in a CI log.
    // -Werror turns doclint findings into a failed build, which is what makes the rule hold.
    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // The chunk loader tests allocate payloads of about one mebibyte to cover the external
        // chunk file path. Without an explicit heap the worker can die while other build tasks
        // run in parallel, which surfaces as an EOFException instead of a test failure.
        maxHeapSize = "1g"
        jvmArgs("-Dminestom.inside-test=true")
        testLogging {
            events("passed", "skipped", "failed")
        }
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.withType<JacocoReport>().configureEach {
        dependsOn(tasks.withType<Test>())
        reports {
            xml.required.set(true)
            csv.required.set(true)
        }
    }
}

// Only the two library modules are published. falco-benchmarks deliberately never applies
// maven-publish, so a `./gradlew publish` at the root passes over it without a task exclusion.
configure(listOf(project(":falco-anvil"), project(":falco-light"))) {
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        withJavadocJar()
        withSourcesJar()
    }

    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("maven") {
            from(components["java"])
        }

        repositories {
            maven {
                authentication {
                    credentials(PasswordCredentials::class) {
                        username = System.getenv("ONELITEFEATHER_MAVEN_USERNAME")
                        password = System.getenv("ONELITEFEATHER_MAVEN_PASSWORD")
                    }
                }
                name = "OneLiteFeatherRepository"
                // The public Reposilite endpoints, so a consumer of a published artefact needs no
                // credentials. They are separate from the internal ones the build resolves against.
                url = if (project.version.toString().contains("SNAPSHOT")) {
                    uri("https://repo.onelitefeather.dev/snapshots")
                } else {
                    uri("https://repo.onelitefeather.dev/releases")
                }
            }
        }
    }
}
