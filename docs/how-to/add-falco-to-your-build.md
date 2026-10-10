# Add Falco to your build

Declare the Falco modules your project needs so that they resolve from the OneLiteFeather
repository. This applies to any Gradle or Maven project that already targets Minestom.

**Before you start:** a Minestom project that compiles. No credentials are needed — the release and
snapshot endpoints are both public.

## Gradle, with the BOM

1. Add the release repository in `build.gradle.kts`:

   ```kotlin
   repositories {
       mavenCentral()
       maven("https://repo.onelitefeather.dev/releases")
   }
   ```

2. Import `falco-bom` as a platform and declare the modules you need without versions:

   ```kotlin
   dependencies {
       implementation(platform("net.onelitefeather:falco-bom:3.0.1"))

       implementation("net.onelitefeather:falco-anvil")
       implementation("net.onelitefeather:falco-light")
       implementation("net.onelitefeather:falco-instance")
       implementation("net.onelitefeather:falco-migration:3.0.1") // not in the BOM, so it takes its version here
   }
   ```

   Take only the modules you need. The platform constrains a version for `falco-anvil`, `falco-light`
   and `falco-instance`; it does not pull in one you did not declare, and it does not cover
   `falco-migration`.

3. Declare Minestom yourself. Falco does not bring it:

   ```kotlin
   implementation("net.minestom:minestom:<version>")
   ```

4. Refresh the Gradle project.

## Gradle, without the BOM

Version each module individually instead of step 2:

```kotlin
dependencies {
    implementation("net.onelitefeather:falco-anvil:3.0.1")
    implementation("net.onelitefeather:falco-light:3.0.1")
    implementation("net.onelitefeather:falco-instance:3.0.1")
    implementation("net.onelitefeather:falco-migration:3.0.1")
}
```

Use this only if your build cannot import a platform. Versioning the modules by hand lets them drift
into a combination that was never built or tested together.

## Maven

Add both repositories to `pom.xml`, then declare the modules with the same coordinates:

```xml
<repository>
  <id>onelitefeater-repository-releases</id>
  <name>OneLiteFeather Network Reposilite Repository</name>
  <url>https://repo.onelitefeather.dev/releases</url>
</repository>

<repository>
  <id>onelitefeater-repository-snapshots</id>
  <name>OneLiteFeather Network Reposilite Repository</name>
  <url>https://repo.onelitefeather.dev/snapshots</url>
  <snapshots>
    <enabled>true</enabled>
  </snapshots>
</repository>
```

## Resolving snapshots instead of releases

Point at the snapshot endpoint and keep the coordinates unchanged; only the version differs:

```kotlin
repositories {
    maven("https://repo.onelitefeather.dev/snapshots")
}
```

Do not build a release of your own against a snapshot — a snapshot version keeps moving as commits
land on `main`.

## Check it worked

`net.onelitefeather.falco.anvil.FalcoAnvilLoader` resolves in your IDE, and a build that only
declares it compiles. If the modules resolved but Minestom types do not, step 3 is missing.

## If it does not work

A 401 from `repo.onelitefeather.dev` means the build is reaching the *internal* endpoint rather than
the public one — check the URL against [Reference Modules and coordinates](../reference/modules-and-coordinates.md). Consuming Falco never
needs credentials; only [How-to Build Falco from source](build-falco-from-source.md) does.

A 404 on `falco-bom` below version `1.0.0` is expected: the BOM did not exist yet.

See also: [Reference Modules and coordinates](../reference/modules-and-coordinates.md) for every coordinate, endpoint and Javadoc address ·
[Tutorial Load your first world with Falco](../tutorials/load-your-first-world-with-falco.md) to use what you just declared.
