description = "An Instance implementation for Minestom that cleans up after itself"

dependencies {
    implementation(platform(libs.mycelium.bom))
    implementation(libs.slf4j.api)

    compileOnly(libs.adventure.nbt)
    compileOnly(libs.annotations)
    compileOnly(libs.minestom)
    compileOnly(libs.fastutil)
    compileOnly(libs.flare.fastutil)

    testImplementation(libs.adventure.nbt)
    testImplementation(libs.fastutil)
    testImplementation(libs.flare.fastutil)
    testImplementation(libs.annotations)
    testImplementation(libs.minestom)
    testImplementation(libs.cyano)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
