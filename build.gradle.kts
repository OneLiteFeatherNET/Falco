// The root project carries no sources and applies no plugins on purpose: it exists to hold the
// shared version and the configuration every module gets. Applying java-library here would create
// an empty, publishable `falco` artefact next to the two real ones.

// The two published modules always release together, so the version is declared once here and
// inherited by every subproject. Release Please rewrites this single line; nothing else in the
// build carries a version number.
version = "0.1.0" // x-release-please-version

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
