rootProject.name = "falco"

include("falco-anvil")
include("falco-light")
include("falco-benchmarks")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            name = "OneLiteFeatherRepository"
            url = uri("https://repo.onelitefeather.dev/onelitefeather")
            if (System.getenv("CI") != null) {
                credentials {
                    username = System.getenv("ONELITEFEATHER_MAVEN_USERNAME")
                    password = System.getenv("ONELITEFEATHER_MAVEN_PASSWORD")
                }
            } else {
                credentials(PasswordCredentials::class)
                authentication {
                    create<BasicAuthentication>("basic")
                }
            }
        }
    }
    versionCatalogs {
        create("libs") {
            version("bom", "1.7.2")
            version("slf4j", "2.0.18")
            version("annotations", "26.1.0")
            // The mycelium bom does not manage jmh, so both the harness and the gradle plugin
            // which owns the jmh source set need an explicit version here.
            version("jmh", "1.37")
            version("jmhPlugin", "0.7.3")
            // The production modules receive adventure through minestom, which is a compileOnly
            // dependency and therefore never reaches a runtime classpath. The benchmarks run their
            // code for real and need adventure at runtime, so they import the adventure platform
            // directly. Keep this in sync with the version minestom resolves to.
            version("adventureBom", "5.1.1")

            plugin("jmh", "me.champeau.jmh").versionRef("jmhPlugin")

            library("mycelium.bom", "net.onelitefeather", "mycelium-bom").versionRef("bom")

            library("slf4j.api", "org.slf4j", "slf4j-api").versionRef("slf4j")
            library("annotations", "org.jetbrains", "annotations").versionRef("annotations")
            // Minestom declares fastutil at runtime scope, so it never reaches a compile classpath.
            // The light equivalence test and the comparison benchmarks call Minestom methods that
            // take one, which makes the dependency explicit here.
            library("fastutil", "it.unimi.dsi", "fastutil").version("8.5.18")
            library("minestom", "net.minestom", "minestom").withoutVersion()
            library("adventure.nbt", "net.kyori", "adventure-nbt").withoutVersion()
            library("adventure.bom", "net.kyori", "adventure-bom").versionRef("adventureBom")
            library("cyano", "net.onelitefeather", "cyano").withoutVersion()
            library("junit-jupiter", "org.junit.jupiter", "junit-jupiter").withoutVersion()
            library("junit-jupiter-engine", "org.junit.jupiter", "junit-jupiter-engine").withoutVersion()
            library("junit.platform.launcher", "org.junit.platform", "junit-platform-launcher").withoutVersion()

            library("jmh.core", "org.openjdk.jmh", "jmh-core").versionRef("jmh")
        }
    }
}
