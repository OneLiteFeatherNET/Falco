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

// This module gets -Werror on javadoc from the root build, but nothing otherwise calls javadoc for
// it: unlike falco-anvil/-light/-instance it has no withJavadocJar(), on purpose (no publishing and
// no japicmp baseline for a module that has never been released — see the design doc). Without this,
// a broken javadoc comment would compile clean and pass check regardless.
tasks.named("check") {
    dependsOn(tasks.named("javadoc"))
}
