// A java-platform project carries no sources and no compiled output of its own — its only product
// is a POM with a <dependencyManagement> block, generated from the constraints declared below. That
// is also why it is excluded from the root's `subprojects` block rather than opted into a second
// java-library configuration: `java-library` and `java-platform` cannot coexist on one project, and
// there is nothing for a toolchain, a compiler or a test task to do here anyway.
//
// The `java-platform` plugin itself is not applied here but from the root build, right where
// java-library is applied to every other subproject. Every subproject's own build script runs after
// the root project's, so a plugin applied only here would not have registered its software component
// yet by the time the root script's publishing block further down reaches for
// `components["javaPlatform"]` — the root script is still the one running at that point, and it
// needs the plugin already active to see the component it registers.
description = "A Maven BOM pinning falco-anvil, falco-light and falco-instance to one version, so a consumer declares the version once instead of on every dependency line."

dependencies {
    constraints {
        // Written as project references rather than "net.onelitefeather:falco-anvil:<version>"
        // string literals so the pinned version can never drift from the one the sibling module
        // actually builds as. A hardcoded string would need to be kept in sync by hand on every
        // release; a project reference makes Gradle read `project(":falco-anvil").version` itself
        // when it writes the constraint, which is exactly `rootProject.version` after the root
        // build's `subprojects` block assigns it — the same single line Release Please rewrites.
        api(project(":falco-anvil"))
        api(project(":falco-light"))
        api(project(":falco-instance"))
    }
}
