description = "A thread-safe light engine for Minestom, independent of any chunk implementation"

dependencies {
    implementation(platform(libs.mycelium.bom))
    implementation(libs.slf4j.api)

    compileOnly(libs.adventure.nbt)
    compileOnly(libs.annotations)
    compileOnly(libs.minestom)
    compileOnly(project(":falco-instance"))

    testImplementation(project(":falco-instance"))
    testImplementation(libs.adventure.nbt)
    testImplementation(libs.annotations)
    testImplementation(libs.minestom)
    testImplementation(libs.cyano)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.fastutil)
    testImplementation(project(":falco-anvil"))
}
