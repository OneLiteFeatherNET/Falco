// The root project carries no sources and applies no plugins on purpose: it exists to hold the
// shared version and the configuration every module gets. Applying java-library here would create
// an empty, publishable `falco` artefact next to the two real ones.

// The two published modules always release together, so the version is declared once here and
// inherited by every subproject. Release Please rewrites this single line; nothing else in the
// build carries a version number.
version = "0.3.0" // x-release-please-version

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

// group and version are the same for every subproject regardless of what kind of project it is, so
// this assignment stays outside the java-library configuration below and applies to falco-bom too —
// the BOM pins its siblings by project reference (see falco-bom/build.gradle.kts) and that only
// produces the right numbers in the generated POM if falco-bom carries the same version they do.
subprojects {
    group = "net.onelitefeather"
    version = rootProject.version
}

// java-library and java-platform are mutually exclusive Gradle plugins — applying both to one
// project fails the build — so falco-bom, which is a java-platform project with no sources of its
// own, cannot go through this block. Subtracting it from the subproject list here, rather than
// guarding every apply() call inside the block with an `if (name != "falco-bom")`, keeps the block
// itself unaware that an exception exists: a plugin added to it later inherits the exclusion for
// free instead of needing its own guard, and the diff against the pre-BOM version of this file is a
// one-line change of scope rather than a scattering of conditionals through the body.
configure(subprojects - project(":falco-bom")) {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

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

// falco-bom gets the one plugin it needs the same way every other subproject gets java-library: from
// here, rather than from a `plugins { }` block inside falco-bom/build.gradle.kts. The reason is
// evaluation order, not style — a subproject's own build script only runs after the root project's,
// so applying java-platform there would leave the `javaPlatform` software component unregistered
// until after this file's publishing block, further down, already tried to read it.
project(":falco-bom") {
    apply(plugin = "java-platform")
}

// The three library modules and falco-bom are published; falco-benchmarks and falco-demo
// deliberately never apply maven-publish, so a `./gradlew publish` at the root passes over them
// without a task exclusion.
val publishedModules = listOf(project(":falco-anvil"), project(":falco-light"), project(":falco-instance"), project(":falco-bom"))

// The repository is the one thing all four published modules share, regardless of what they
// publish or how: a library module ships a jar built from `components["java"]`, falco-bom ships only
// a POM built from `components["javaPlatform"]`, but both land in the same Reposilite behind the
// same release/snapshot switch and the same credentials. Configuring that once here, instead of
// once per module, is what keeps a future change to the repository (a new host, a different
// credential scheme) a one-line edit instead of a four-line one — and it is why this block applies
// maven-publish itself rather than leaving each module to apply it before configuring its
// publication.
configure(publishedModules) {
    apply(plugin = "maven-publish")

    extensions.configure<PublishingExtension> {
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

// What gets published differs by project kind, so it stays split from the shared repository block
// above. The three library modules carry a JavaPluginExtension to ask for sources and javadoc jars
// and publish the `java` component; falco-bom has neither a JavaPluginExtension nor anything to
// document or compile — a platform module's only published file is the POM that its `javaPlatform`
// component becomes, so asking it for withJavadocJar()/withSourcesJar() would fail outright.
configure(listOf(project(":falco-anvil"), project(":falco-light"), project(":falco-instance"))) {
    extensions.configure<JavaPluginExtension> {
        withJavadocJar()
        withSourcesJar()
    }

    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

project(":falco-bom") {
    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("maven") {
            from(components["javaPlatform"])
        }
    }
}
