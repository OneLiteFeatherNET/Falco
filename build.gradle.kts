// Build rationale documented in the wiki: https://github.com/OneLiteFeatherNET/Falco/wiki

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
