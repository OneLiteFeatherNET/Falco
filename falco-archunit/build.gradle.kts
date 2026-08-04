description = "ArchUnit rules over the main sources of every module. Never published."

dependencies {
    testImplementation(platform(libs.mycelium.bom))
    testImplementation(project(":falco-anvil"))
    testImplementation(project(":falco-light"))
    testImplementation(project(":falco-instance"))
    testImplementation(project(":falco-demo"))
    testImplementation(project(":falco-migration"))

    testImplementation(libs.minestom)
    testImplementation(libs.annotations)
    testImplementation(libs.slf4j.api)
    testImplementation(libs.fastutil)
    testImplementation(libs.adventure.nbt)

    testImplementation(libs.archunit.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
