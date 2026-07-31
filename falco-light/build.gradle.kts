description = "A thread-safe light engine for Minestom, independent of any chunk implementation"

dependencies {
    implementation(platform(libs.mycelium.bom))
    implementation(libs.slf4j.api)

    compileOnly(libs.adventure.nbt)
    compileOnly(libs.annotations)
    compileOnly(libs.minestom)

    testImplementation(libs.adventure.nbt)
    testImplementation(libs.annotations)
    testImplementation(libs.minestom)
    testImplementation(libs.cyano)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.jupiter.engine)
    // The equivalence test compares this engine against the one Minestom ships with and calls a
    // Minestom method taking a fastutil collection. Minestom declares fastutil at runtime scope,
    // so it is not on the compile classpath without this.
    testImplementation(libs.fastutil)
    // Test scope only, for the single case in ChunkLightServiceIntegrationTest which checks that
    // the engine works on a chunk that went through the Anvil loader. That case needs both modules
    // wherever it lives; the published falco-light artefact does not depend on falco-anvil.
    testImplementation(project(":falco-anvil"))
}
