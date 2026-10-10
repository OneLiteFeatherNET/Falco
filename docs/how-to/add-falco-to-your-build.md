# Add Falco to your build

Declare the Falco modules your project needs so that they resolve from the OneLiteFeather
repository. You end with a build that compiles against `falco-anvil`, `falco-light` or the other modules
you chose. This applies to any Gradle or Maven project that already targets Minestom.

**Before you start:** a Minestom project that compiles. No credentials are needed — the release and
snapshot endpoints are both public. Every coordinate is listed in
[Reference Modules and coordinates](../reference/modules-and-coordinates.md).

## Gradle, with the BOM

1. Add the release repository in `build.gradle.kts`:

   ```kotlin
   repositories {
       mavenCentral()
       maven("https://repo.onelitefeather.dev/releases")
   }
   ```

2. Import `falco-bom` as a platform and declare the modules you need without versions:

   <!-- x-release-please-start-version -->

   ```kotlin
   dependencies {
       implementation(platform("net.onelitefeather:falco-bom:3.0.1"))

       implementation("net.onelitefeather:falco-anvil")
       implementation("net.onelitefeather:falco-light")
       implementation("net.onelitefeather:falco-instance")
       implementation("net.onelitefeather:falco-migration:3.0.1") // not in the BOM, so it takes its version here
   }
   ```

   <!-- x-release-please-end -->

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

<!-- x-release-please-start-version -->
```kotlin
dependencies {
    implementation("net.onelitefeather:falco-anvil:3.0.1")
    implementation("net.onelitefeather:falco-light:3.0.1")
    implementation("net.onelitefeather:falco-instance:3.0.1")
    implementation("net.onelitefeather:falco-migration:3.0.1")
}
```
<!-- x-release-please-end -->

Use this only if your build cannot import a platform. Versions set by hand have to be kept in step
with each other by you; the reason the BOM exists is in
[Versioning and releases](../contributing/versioning-and-releases.md#why-consumers-take-the-version-from-the-bom).

## Maven

Add both repositories to `pom.xml`, then import the BOM and declare the modules with the same
coordinates:

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

Import `falco-bom` in `<dependencyManagement>`, so that the modules it constrains take their version from it:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>net.onelitefeather</groupId>
      <artifactId>falco-bom</artifactId>
      <version>3.0.1</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Then declare the modules you need. The three modules in the BOM have no version; `falco-migration` takes
its own:

```xml
<dependencies>
  <dependency>
    <groupId>net.onelitefeather</groupId>
    <artifactId>falco-anvil</artifactId>
  </dependency>
  <dependency>
    <groupId>net.onelitefeather</groupId>
    <artifactId>falco-light</artifactId>
  </dependency>
  <dependency>
    <groupId>net.onelitefeather</groupId>
    <artifactId>falco-instance</artifactId>
  </dependency>
  <dependency>
    <groupId>net.onelitefeather</groupId>
    <artifactId>falco-migration</artifactId>
    <version>3.0.1</version>
  </dependency>
  <dependency>
    <groupId>net.minestom</groupId>
    <artifactId>minestom</artifactId>
    <version>2026.10.07-26.2</version>
  </dependency>
</dependencies>
```

Minestom is declared last, as in the Gradle steps: Falco does not bring it.

## Resolving snapshots instead of releases

Point at the snapshot endpoint and keep the coordinates unchanged; only the version differs:

```kotlin
repositories {
    maven("https://repo.onelitefeather.dev/snapshots")
}
```

Do not release a build of your own against a snapshot. The snapshot version keeps moving as commits
land on `main`; see [Reference Modules and coordinates](../reference/modules-and-coordinates.md#snapshot-versions).

## Check it worked

`net.onelitefeather.falco.anvil.FalcoAnvilLoader` resolves in your IDE, and a build that only
declares it compiles. If the modules resolved but Minestom types do not, the Minestom dependency
(step 3 in Gradle, the last `<dependency>` in Maven) is missing.

## If it does not work

A 401 from `repo.onelitefeather.dev` means the build is reaching the *internal* endpoint rather than
the public one — check the URL against [Reference Modules and coordinates](../reference/modules-and-coordinates.md). Consuming Falco never
needs credentials; only [How-to Build Falco from source](build-falco-from-source.md) does.

A 404 on `falco-bom` below version `1.0.0` is expected: the BOM did not exist yet. See
[Reference Supported versions](../reference/supported-versions.md#artefact-availability).

See also: [Reference Modules and coordinates](../reference/modules-and-coordinates.md) for every coordinate, endpoint and Javadoc address ·
[Tutorial Load your first world with Falco](../tutorials/load-your-first-world-with-falco.md) to use what you just declared.
