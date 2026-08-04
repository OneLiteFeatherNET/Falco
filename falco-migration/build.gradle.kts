description = "Converts stored Anvil chunk data from Minecraft 1.13 upwards"

dependencies {
    implementation(platform(libs.mycelium.bom))
    implementation(libs.slf4j.api)
    implementation(project(":falco-anvil"))

    compileOnly(platform(libs.adventure.bom))
    compileOnly(libs.adventure.nbt)
    compileOnly(libs.annotations)

    testImplementation(platform(libs.adventure.bom))
    testImplementation(libs.adventure.nbt)
    testImplementation(libs.annotations)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
