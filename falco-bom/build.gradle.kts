description = "A Maven BOM pinning falco-anvil, falco-light, falco-instance and falco-migration to one version, so a consumer declares the version once instead of on every dependency line."

dependencies {
    constraints {
        api(project(":falco-anvil"))
        api(project(":falco-light"))
        api(project(":falco-instance"))
        api(project(":falco-migration"))
    }
}
