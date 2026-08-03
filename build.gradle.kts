// Build rationale documented in the wiki: https://github.com/OneLiteFeatherNET/Falco/wiki

plugins {
    alias(libs.plugins.japicmp) apply false
}

version = "0.3.0" // x-release-please-version

if (providers.gradleProperty("snapshot").isPresent) {
    val parts = version.toString().substringBefore('-').split('.')
    require(parts.size == 3) { "cannot derive a snapshot from version '$version'" }
    version = "${parts[0]}.${parts[1]}.${parts[2].toInt() + 1}-SNAPSHOT"
}

subprojects {
    group = "net.onelitefeather"
    version = rootProject.version
}

configure(subprojects - project(":falco-bom")) {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

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

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
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

project(":falco-bom") {
    apply(plugin = "java-platform")
}

val publishedModules = listOf(project(":falco-anvil"), project(":falco-light"), project(":falco-instance"), project(":falco-bom"))

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
                url = if (project.version.toString().contains("SNAPSHOT")) {
                    uri("https://repo.onelitefeather.dev/snapshots")
                } else {
                    uri("https://repo.onelitefeather.dev/releases")
                }
            }
        }
    }
}

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

val apiBaselineVersion: String = providers.gradleProperty("apiBaselineVersion").get()

val apiBreaksFile: File = rootProject.file("gradle/api-breaks.properties")

val apiBreaks: Map<String, String> = if (!apiBreaksFile.exists()) emptyMap() else
    java.util.Properties()
        .apply { apiBreaksFile.inputStream().use { load(it) } }
        .entries
        .associate { it.key.toString() to it.value.toString().trim() }

configure(publishedModules - project(":falco-bom")) {
    apply(plugin = "me.champeau.gradle.japicmp")

    val apiBaseline = configurations.detachedConfiguration(
        dependencies.create("net.onelitefeather:${project.name}:$apiBaselineVersion")
    ).apply {
        isTransitive = false
    }

    val declaredBreaks: List<String> = apiBreaks["${project.name}.classExcludes"]
        ?.split(",")
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty()

    val checkApiCompatibility = tasks.register<me.champeau.gradle.japicmp.JapicmpTask>("checkApiCompatibility") {
        oldClasspath.from(apiBaseline)
        newClasspath.from(tasks.named<Jar>("jar").flatMap { it.archiveFile })
        onlyBinaryIncompatibleModified.set(true)
        failOnModification.set(true)
        ignoreMissingClasses.set(true)
        classExcludes.set(declaredBreaks)
        htmlOutputFile.set(layout.buildDirectory.file("reports/japicmp/${project.name}.html"))
        txtOutputFile.set(layout.buildDirectory.file("reports/japicmp/${project.name}.txt"))

        doFirst {
            if (apiBreaks.keys.any { it.endsWith(".classExcludes") }) {
                require(apiBreaks["baseline"] == apiBaselineVersion) {
                    "${apiBreaksFile.name} declares accepted API breaks against baseline " +
                            "${apiBreaks["baseline"]}, but apiBaselineVersion is $apiBaselineVersion. " +
                            "Every exception in that file was judged against the older baseline and " +
                            "excludes its type from the check entirely, so each one has to be " +
                            "re-examined and either deleted or re-justified before the version moves."
                }
            }
            val resolved = apiBaseline.resolve()
            require(resolved.isNotEmpty()) {
                "the API baseline net.onelitefeather:${project.name}:$apiBaselineVersion resolved to nothing"
            }
            require(resolved.none { it.absolutePath.contains("${File.separator}build${File.separator}libs${File.separator}") }) {
                "the API baseline resolved to this build's own jar instead of $apiBaselineVersion from the repository, " +
                        "so the comparison would pass no matter what changed"
            }
        }
    }

    tasks.named("check") {
        dependsOn(checkApiCompatibility)
    }
}
