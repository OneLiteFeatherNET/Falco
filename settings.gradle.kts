// Build rationale documented in the wiki: https://github.com/OneLiteFeatherNET/Falco/wiki
rootProject.name = "falco"

include("falco-anvil")
include("falco-light")
include("falco-instance")
include("falco-benchmarks")
include("falco-demo")
include("falco-bom")

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
            version("jmh", "1.37")
            version("jmhPlugin", "0.7.3")
            version("adventureBom", "5.1.1")
            version("jol", "0.17")

            plugin("jmh", "me.champeau.jmh").versionRef("jmhPlugin")

            library("mycelium.bom", "net.onelitefeather", "mycelium-bom").versionRef("bom")

            library("slf4j.api", "org.slf4j", "slf4j-api").versionRef("slf4j")
            library("slf4j.simple", "org.slf4j", "slf4j-simple").versionRef("slf4j")
            library("annotations", "org.jetbrains", "annotations").versionRef("annotations")
            library("fastutil", "it.unimi.dsi", "fastutil").version("8.5.18")
            library("minestom", "net.minestom", "minestom").withoutVersion()
            library("adventure.nbt", "net.kyori", "adventure-nbt").withoutVersion()
            library("adventure.bom", "net.kyori", "adventure-bom").versionRef("adventureBom")
            library("cyano", "net.onelitefeather", "cyano").withoutVersion()
            library("junit-jupiter", "org.junit.jupiter", "junit-jupiter").withoutVersion()
            library("junit-jupiter-engine", "org.junit.jupiter", "junit-jupiter-engine").withoutVersion()
            library("junit.platform.launcher", "org.junit.platform", "junit-platform-launcher").withoutVersion()

            library("jmh.core", "org.openjdk.jmh", "jmh-core").versionRef("jmh")
            library("jol.core", "org.openjdk.jol", "jol-core").versionRef("jol")
        }
    }
}
