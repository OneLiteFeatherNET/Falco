description = "An Anvil chunk loader for Minestom that loads in parallel for real"

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
    // Test-only, one-directional: falco-migration's own main sources already depend on this
    // module's main sources, and this does not add a cycle, only a test-classpath dependency the
    // other way. It exists for MigrationRoundTripTest, the round trip from ChunkMigration's output
    // through a real region file into this module's own loader — the acceptance test the final
    // review found missing, and which cannot live in falco-migration itself: falco-archunit's
    // migrationKnowsNoMinestom rule forbids that module from depending on net.minestom at all.
    testImplementation(project(":falco-migration"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
