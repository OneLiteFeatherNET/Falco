# ArchUnit architecture rules for Falco

Design of 2026-08-01. A new module `falco-archunit` holds 33 rules the project already claims in its
README, its Javadoc and the wiki, and which so far nothing but the author's discipline enforces.

## Why

Falco sells three properties no tool in the build checks:

1. **Three separately pullable artefacts.** `Installation.md:14` promises "take one without the other
   if that is all you need". A single `project(":falco-...")` line or a single import breaks it, and
   neither the compiler nor a test says a word.
2. **A compute core that knows no server.** `RegionFile` is, by its own Javadoc, "a pure byte
   container. It neither knows the NBT structure of a chunk nor the Minestom chunk model";
   `BlockLightSource` "separates the algorithm from the registries of a running server". Minestom is
   `compileOnly` everywhere — the compiler catches nothing here, every import compiles straight
   through. That separation is also the precondition for the published measurements: Minestom's
   `AnvilLoader` cannot be touched in a bare JMH fork at all, because its static fields read the
   registry (`RegionFileComparisonBenchmark.java:45-48`).
3. **An experimental but honest API.** The build has no japicmp, no revapi, no Error Prone —
   `grep japicmp|revapi|errorprone|nullaway|checkstyle|spotbugs|pmd` across every `build.gradle.kts`,
   `.toml` and `.yml` returns nothing. Whatever lands on `repo.onelitefeather.dev` once cannot be
   taken back binary-compatibly without a diff tool.

The rule set replaces those three missing tools. It is not a style guide.

## Decisions

| Question | Decision |
| --- | --- |
| Where the rules live | A module of its own, `falco-archunit`, never published |
| Scope | 33 rules across five test classes |
| Violations in the existing code | The code is fixed and the rule stays sharp — with one named exception, justified in the test |
| ArchUnit version | 1.4.2, its own entry in the version catalog of `settings.gradle.kts` |
| Test sources | Out of scope by construction |

**Why a module of its own.** Only there do cross-module rules apply: "`falco-anvil` does not know
`falco-light`" cannot be stated inside a module that only sees itself. Second, and this is the
stronger argument, `falco-archunit` sees **only the main classes** through its dependencies. That
puts it in exactly the position of a consumer of the jar. Rule A3 (no unreachable types in public
signatures) catches a class of mistake **no test in this project can catch**, because every test
lives in the same package and can see `SectorAllocator`.

**Why ArchUnit locally rather than in a BOM.** None of the three OneLiteFeather BOMs manages
ArchUnit, and nothing outside Falco needs it. By the BOM convention, a dependency only one project
needs belongs in that project's own version catalog — the way slf4j, jmh, fastutil and annotations
already do here.

## Module setup

### `settings.gradle.kts`

```kotlin
include("falco-archunit")
```

In the version catalog, next to the existing versions:

```kotlin
version("archunit", "1.4.2")
library("archunit.junit5", "com.tngtech.archunit", "archunit-junit5").versionRef("archunit")
```

### `falco-archunit/build.gradle.kts`

```kotlin
description = "ArchUnit rules over the main sources of every module. Never published."

dependencies {
    testImplementation(platform(libs.mycelium.bom))
    testImplementation(project(":falco-anvil"))
    testImplementation(project(":falco-light"))
    testImplementation(project(":falco-instance"))
    testImplementation(project(":falco-demo"))

    testImplementation(libs.minestom)
    testImplementation(libs.annotations)
    testImplementation(libs.slf4j.api)
    testImplementation(libs.fastutil)
    testImplementation(libs.adventure.nbt)

    testImplementation(libs.archunit.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
```

`minestom`, `annotations`, `adventure-nbt` and `fastutil` have to be listed explicitly: they are
`compileOnly` in the libraries and do not come along transitively. Without them the
`ClassFileImporter` reports missing types instead of checking rules.

The module appears in **neither** publication list of the root `build.gradle.kts` and is therefore
unpublished by default. It has no `main` sources; `withJavadocJar()` and the Javadoc wiring
`falco-demo` needs do not apply.

### `falco-archunit/src/test/resources/archunit.properties`

```properties
archRule.failOnEmptyShould=true
```

That is already the default; the file pins the decision rather than leaving it to a future ArchUnit
version. Several rules below depend on ArchUnit reporting when their `that()` set runs empty — C5,
for instance, consists of exactly two classes today.

### Import

All five test classes carry the same header:

```java
@AnalyzeClasses(
        packages = "net.onelitefeather.falco",
        importOptions = ImportOption.DoNotIncludeTests.class)
```

`DoNotIncludeTests` is mandatory: the test classes of this module themselves live under
`net.onelitefeather.falco` and would otherwise import each other — `ArchitectureTest` would be a
class in a published package without `@ApiStatus.Experimental` and would break A1.

Shared constants in all five classes:

```java
static final String ANVIL    = "net.onelitefeather.falco.anvil..";
static final String LIGHT    = "net.onelitefeather.falco.light..";
static final String INSTANCE = "net.onelitefeather.falco.instance..";
static final String DEMO     = "net.onelitefeather.falco.demo..";
static final String BENCH    = "net.onelitefeather.falco.benchmark..";
static final String[] PUBLISHED = { ANVIL, LIGHT, INSTANCE };
```

The sketches below are drafts, not transcripts: they are written against the ArchUnit 1.4.2 API but
have not been compiled. Where the fluent API turns out to spell something differently, the
**statement** of the rule governs, not the sketch.

---

## ModuleBoundaryTest

### M1 — The three published modules do not know each other

**Statement.** No class in `.anvil`, `.light` or `.instance` — including nested and anonymous types —
may depend on another `net.onelitefeather.falco` package, and that includes `.demo` and `.benchmark`.

**Rationale.** None of the three build files declares a `project` dependency; the only exception is
`testImplementation(project(":falco-anvil"))` in `falco-light/build.gradle.kts:19`, a test source this
module never sees. The independence hurts and is held anyway: `ServerStack.java:27-40` argues at
length that `FalcoInstance` and `FalcoLightingChunk` cannot be combined, rather than marrying the
modules. The temptation is concrete — `LightUpdateAware` and `ChunkLightScheduler` are an interface
`FalcoChunk` could serve. One import of `FalcoChunk` in `ChunkLightArea` would force the instance
module on every user of the light engine.

```java
private static ArchRule isolated(String self, String... foreign) {
    return noClasses().that().resideInAPackage(self)
            .should().dependOnClassesThat().resideInAnyPackage(foreign)
            .because("the three modules are published separately and used separately; "
                   + "Installation.md promises: take one without the other");
}

@ArchTest static final ArchRule anvilIsStandalone    = isolated(ANVIL, LIGHT, INSTANCE, DEMO, BENCH);
@ArchTest static final ArchRule lightIsStandalone    = isolated(LIGHT, ANVIL, INSTANCE, DEMO, BENCH);
@ArchTest static final ArchRule instanceIsStandalone = isolated(INSTANCE, ANVIL, LIGHT, DEMO, BENCH);
```

Three explicit rules rather than `slices()`, deliberately: `PackageMatcher` understands only `(*)` and
`(**)`, no regex alternative, and a plain `(*)` over `net.onelitefeather.falco.(*)` would produce a
fourth slice `demo`, which rightly knows all three and would turn the rule red immediately.

### M2 — Only the declared third-party libraries

**Statement.** Classes in the three published packages may, beyond those packages, depend only on
`java..`, `net.minestom..`, `net.kyori.adventure.nbt..`, `net.kyori.adventure.key..`,
`it.unimi.dsi.fastutil..`, `org.slf4j..` and `org.jetbrains.annotations..`.

**Rationale.** The set of imports across the three modules is exactly that list. `minestom`,
`adventure-nbt`, `annotations` and `fastutil` are `compileOnly` and never reach the published POM;
`slf4j-api` alone is `implementation` and lands there with runtime scope
(`Dependency-Management.md:94-115`). Every additional `implementation` dependency would be a library
the project forces on every Minestom server pulling one of the artefacts, version conflict included,
which the consumer then has to resolve.

```java
private static final DescribedPredicate<JavaClass> ALLOWED_DEPENDENCY =
        resideInAnyPackage(ANVIL, LIGHT, INSTANCE,
                "java..", "net.minestom..",
                "net.kyori.adventure.nbt..", "net.kyori.adventure.key..",
                "it.unimi.dsi.fastutil..", "org.slf4j..", "org.jetbrains.annotations..")
        .or(describe("a primitive", JavaClass::isPrimitive));

@ArchTest
static final ArchRule publishedModulesOnlyUseDeclaredDependencies =
        classes().that().resideInAnyPackage(PUBLISHED)
                .should().onlyDependOnClassesThat(ALLOWED_DEPENDENCY)
                .because("anything outside this list appears as a new runtime dependency in the "
                       + "published POM and is forced on every Minestom server");
```

The addition for primitives is required: ArchUnit reports `int` as a dependency whose package name is
empty and matches no pattern. The three falco packages are only safe to list because M1 already
forbids every cross dependency.

### M3 — No published module uses the Minestom counterpart it replaces

**Statement.** No class in the three published packages may depend on
`net.minestom.server.instance.anvil.AnvilLoader` or `net.minestom.server.instance.LightingChunk` —
neither by call nor by inheritance.

**Rationale.** Those are precisely the two types the library competes with. The likely violation is
not malicious but convenient: derive `FalcoLightingChunk` from `LightingChunk` to inherit its packet
handling, or fall back to Minestom's `AnvilLoader` in the loader when the own codec cannot read a
format. After that, every published measurement would compare the library against itself with nothing
in the result to show for it — and the `compileOnly` construction would quietly drag exactly the
implementation the consumer wanted to replace into their runtime path. Today only unpublished classes
name them (`LoaderKind.java:4`, `ServerStack.java:4`, the comparison benchmarks); the replacements
extend `DynamicChunk`.

```java
private static final DescribedPredicate<JavaClass> THE_REPLACED_IMPLEMENTATIONS =
        HasName.Predicates.nameMatching(
                "net\\.minestom\\.server\\.instance\\.(anvil\\.AnvilLoader|LightingChunk)")
                .as("the Minestom loader and lighting chunk Falco replaces");

@ArchTest
static final ArchRule publishedModulesDoNotUseTheMinestomCounterpart =
        noClasses().that().resideInAnyPackage(PUBLISHED)
                .should().dependOnClassesThat(THE_REPLACED_IMPLEMENTATIONS)
                .because("these are the two implementations Falco replaces; they may appear only "
                       + "where something is compared, and that is never a published module");
```

`dependOnClassesThat` covers inheritance too, so an `extends LightingChunk` is caught.

### M4 — The measuring path of the demo knows only the ChunkLoader interface

**Statement.** No class in `.demo` other than `LoaderKind`, `ServerStack` and `LoaderDiagnosis` may
depend on `FalcoAnvilLoader`, Minestom's `AnvilLoader`, `FalcoInstance`, `FalcoLightingChunk`,
Minestom's `LightingChunk`, `ChunkLightService` or `ChunkLightScheduler`.

**Rationale.** `falco-demo` has exactly one job: run the same code twice and change one variable. The
two enums say so themselves — `LoaderKind.java:12-18` ("everything after that point … is the same
code") and `ServerStack.java:17-26` ("is the only place the two differ"). Of the demo's 18 main
classes only these three name a concrete implementation. If the separation breaks — an `instanceof`
in the stopwatch path, a special case for one side — the numbers in the README, `docs/charts` and the
wiki are no longer symmetrically obtained, and nothing in the result shows it.

```java
private static final DescribedPredicate<JavaClass> A_DOCUMENTED_AB_SWITCH =
        HasName.Predicates.nameMatching(
                "net\\.onelitefeather\\.falco\\.demo\\."
              + "(LoaderKind|ServerStack|LoaderDiagnosis)(\\$.*)?")
                .as("a documented A/B switch of the demo");

private static final DescribedPredicate<JavaClass> A_CONCRETE_STACK_COMPONENT =
        HasName.Predicates.nameMatching(
                "net\\.onelitefeather\\.falco\\.(anvil\\.FalcoAnvilLoader"
              + "|instance\\.FalcoInstance"
              + "|light\\.(FalcoLightingChunk|ChunkLightService|ChunkLightScheduler))"
              + "|net\\.minestom\\.server\\.instance\\.(anvil\\.AnvilLoader|LightingChunk)")
                .as("one side of the comparison rather than the interface both sides share");

@ArchTest
static final ArchRule demoMeasuresThroughTheInterface =
        noClasses().that().resideInAPackage(DEMO).and(are(not(A_DOCUMENTED_AB_SWITCH)))
                .should().dependOnClassesThat(A_CONCRETE_STACK_COMPONENT)
                .because("LoaderKind and ServerStack are, by their own Javadoc, the only places the "
                       + "two sides are allowed to differ");
```

`ChunkInventory` touches `falco-anvil`, but only `RegionConstants` — a format constant that affects
both sides equally, and therefore not a violation.

### M5 — SLF4J appears in no public signature

**Statement.** No `public`/`protected` constructor or method of a class in the published packages may
carry a type from `org.slf4j..` as its raw return or parameter type.

**Rationale.** `slf4j-api` is the only dependency of the three modules that is `implementation` rather
than `compileOnly`. The root build applies `java-library`, and `maven-publish` maps `implementation`
to runtime scope. The consumer gets slf4j at runtime but **not on their compile classpath**. A public
method with `org.slf4j.Logger` in its signature would not compile for them while the Falco build stays
green: the mistake is made here and surfaces at the user.

```java
private static final DescribedPredicate<JavaClass> SLF4J = resideInAPackage("org.slf4j..");

private static final ArchCondition<JavaCodeUnit> EXPOSE_SLF4J =
        new ArchCondition<>("expose an SLF4J type") {
            @Override public void check(JavaCodeUnit unit, ConditionEvents events) {
                Stream.concat(Stream.of(unit.getRawReturnType()), unit.getRawParameterTypes().stream())
                        .filter(SLF4J)
                        .forEach(type -> events.add(SimpleConditionEvent.violated(unit,
                                unit.getFullName() + " exposes " + type.getName())));
            }
        };

@ArchTest
static final ArchRule slf4jStaysOutOfThePublicApi = noCodeUnits()
        .that().areDeclaredInClassesThat().resideInAnyPackage(PUBLISHED)
        .and(modifier(PUBLIC).or(modifier(PROTECTED)))
        .should(EXPOSE_SLF4J)
        .because("slf4j-api is implementation, so it reaches the POM with runtime scope only and "
               + "is missing from the consumer's compile classpath");
```

The `.and(...)` has to be passed as **one** predicate; a chained `.arePublic().or().areProtected()`
reads as `(in package and public) or protected` and would pull in protected members of arbitrary
packages. The same trap recurs in A3, A4 and C2.

---

## PublicApiTest

### A1 — Every public type carries `@ApiStatus.Experimental` — **red today**

**Statement.** Every `public` type in the three published packages must be annotated with
`org.jetbrains.annotations.ApiStatus.Experimental`, nested public types included, `package-info` types
excluded.

**Rationale.** All three `package-info.java` say verbatim: "Every public type here is experimental and
may still change in a minor release". Nothing else in the build carries that promise — the annotation
**is** the stability contract. A new public type without the marker quietly tells the consumer
"stable" while a minor release is free to change its signature. Count: 31 public types, 30 carry the
marker. Exactly the accidental mistake the rule catches.

```java
private static final DescribedPredicate<JavaClass> PACKAGE_INFO =
        describe("package-info", c -> c.getSimpleName().equals("package-info"));

@ArchTest
static final ArchRule publicApiIsMarkedExperimental = classes()
        .that().resideInAnyPackage(PUBLISHED)
        .and().arePublic()
        .and().doNotHaveModifier(JavaModifier.SYNTHETIC)
        .and(not(PACKAGE_INFO))
        .should().beAnnotatedWith(ApiStatus.Experimental.class)
        .because("every package-info states 'Every public type here is experimental'; the marker is "
               + "the only carrier of that promise, and the build has no japicmp");
```

`ApiStatus.Experimental` ends up as `RuntimeInvisibleAnnotations` in the class file — ArchUnit sees
it.

### A2 — Every published class lives in a `@NotNullByDefault` package

**Statement.** Every class of a published module must live in a package whose `package-info` exists
and is annotated with `org.jetbrains.annotations.NotNullByDefault`.

**Rationale.** All the code relies on that default: grepping for `@NotNull` across the three modules
returns exactly three hits, and those are the three `@NotNullByDefault` lines themselves — a bare
`@NotNull` occurs nowhere. Only the exception is annotated (`long @Nullable [] packed`,
`byte @Nullable [] levels`, the optional `ChunkLoader` in the `FalcoInstance` constructor). The
nullability promise of the entire published API hangs on three lines. There is no subpackage today;
if someone adds one and forgets the `package-info`, that package's whole API silently falls back to
"unspecified" while the code continues to be written without `@NotNull` and therefore looks non-null.

```java
private static final ArchCondition<JavaClass> IN_NOT_NULL_BY_DEFAULT_PACKAGE =
        new ArchCondition<>("reside in a @NotNullByDefault package") {
            @Override public void check(JavaClass item, ConditionEvents events) {
                Optional<JavaClass> info = item.getPackage().tryGetPackageInfo();
                boolean ok = info.isPresent()
                        && info.get().isAnnotatedWith("org.jetbrains.annotations.NotNullByDefault");
                events.add(new SimpleConditionEvent(item, ok,
                        "package " + item.getPackageName() + " has no @NotNullByDefault package-info"));
            }
        };

@ArchTest
static final ArchRule everyPublishedPackageDeclaresNullness = classes()
        .that().resideInAnyPackage(PUBLISHED).and(not(PACKAGE_INFO))
        .should(IN_NOT_NULL_BY_DEFAULT_PACKAGE)
        .because("not one bare @NotNull exists in the three modules; the non-nullability of the "
               + "whole API rests on the three package-info lines");
```

The annotation is matched by its string so the rule does not depend on `org.jetbrains:annotations`
being resolvable at import time.

### A3 — No externally unreachable types in public signatures

**Statement.** No `public`/`protected` constructor or method of a `public` type in the published
packages may carry a non-public type from `net.onelitefeather.falco` as its raw return or parameter
type.

**Rationale.** The three modules have exactly one package-private type, `SectorAllocator`, and it is
one on purpose; twelve private nested types come on top. None appears in a visible signature today.
But javac happily accepts a public method with a package-private return type — it compiles, it passes
the project's own tests, and it is unusable for a consumer of the jar, who cannot name the type.
**No test in this project can find that class of mistake**: the tests live in the same package and can
see `SectorAllocator`. `falco-archunit` sees only main and therefore stands where the consumer
stands. This is the strongest justification in the whole catalog.

```java
private static boolean visibleToConsumers(JavaClass type) {
    JavaClass base = type.isArray() ? type.getBaseComponentType() : type;
    return base.isPrimitive()
            || !base.getPackageName().startsWith("net.onelitefeather.falco")
            || base.getModifiers().contains(JavaModifier.PUBLIC);
}

private static final ArchCondition<JavaCodeUnit> ONLY_VISIBLE_TYPES_IN_SIGNATURE =
        new ArchCondition<>("carry only externally visible types in the signature") {
            @Override public void check(JavaCodeUnit unit, ConditionEvents events) {
                Stream.concat(Stream.of(unit.getRawReturnType()), unit.getRawParameterTypes().stream())
                        .filter(t -> !visibleToConsumers(t))
                        .forEach(t -> events.add(SimpleConditionEvent.violated(unit,
                                unit.getFullName() + " carries the non-public type " + t.getName())));
            }
        };

@ArchTest
static final ArchRule publicSignaturesStayReachable = codeUnits()
        .that().areDeclaredInClassesThat().resideInAnyPackage(PUBLISHED)
        .and().areDeclaredInClassesThat().arePublic()
        .and(modifier(JavaModifier.PUBLIC).or(modifier(JavaModifier.PROTECTED)))
        .and(not(modifier(JavaModifier.SYNTHETIC)))
        .should(ONLY_VISIBLE_TYPES_IN_SIGNATURE)
        .because("SectorAllocator and the private nested types are internals; in a signature the "
               + "consumer could not name them, and the tests in the same package cannot notice");
```

ArchUnit checks raw types, so neither generic arguments (`List<Kept>`) nor field types — field types
are fully covered by A4.

### A4 — Visible fields are immutable constants only

**Statement.** Every `public`/`protected` field of a type in the published packages must — enum
constants aside — be `static final` and have a primitive or `String` raw type; in particular no array,
no collection, no mutable object type.

**Rationale.** Current state: exactly 22 visible fields outside enum constants, all
`public static final int|long|String`. Not one instance field of a published type is visible. The
danger is concrete in this code: the project is full of static tables held as arrays —
`BLOCK_UPDATE_FACES`, `HORIZONTAL_FACES`, `FACE_OFFSETS`, `SERVER_FACES`, `NO_HEIGHTMAP` — and all of
them are private today. Making one of them public by accident hands every consumer an array that is
declared `final` and yet fully writable; for `FACE_OFFSETS` or `SERVER_FACES` a foreign write would
corrupt light propagation globally and silently, and the `final` prevents none of it.

```java
private static final DescribedPredicate<JavaClass> IMMUTABLE_CONSTANT_TYPE =
        describe("primitive or String", t -> t.isPrimitive() || t.isEquivalentTo(String.class));

@ArchTest
static final ArchRule visibleFieldsAreConstants = fields()
        .that().areDeclaredInClassesThat().resideInAnyPackage(PUBLISHED)
        .and(modifier(JavaModifier.PUBLIC).or(modifier(JavaModifier.PROTECTED)))
        .and(not(modifier(JavaModifier.ENUM)))
        .and(not(modifier(JavaModifier.SYNTHETIC)))
        .should().beStatic().andShould().beFinal()
        .andShould().haveRawType(IMMUTABLE_CONSTANT_TYPE)
        .because("a visible array would be writable from outside despite being final, and the "
               + "neighbouring tables FACE_OFFSETS and SERVER_FACES steer light propagation");
```

The `ENUM` exemption is mandatory: `ChunkCompression.GZIP/ZLIB/NONE` and the six `BlockFace` constants
are `public static final` fields of the enum type in the bytecode. Without it the rule would fail in
nine places today with nothing actually broken.

### A5 — Public classes are final unless they are forced not to be

**Statement.** A `public` class in the published packages must be `final` unless its direct superclass
lives under `net.minestom` or it is assignable to `Throwable`. This also rules out public abstract
classes — the project's extension points are interfaces.

**Rationale.** 31 public types, 5 non-final classes, every one of them forced: `FalcoChunk` and
`FalcoLightingChunk` extend `DynamicChunk` because Minestom's chunk lifecycle hooks are protected;
`FalcoInstance` extends `Instance` because four places in Minestom branch on
`instanceof InstanceContainer`; the two exceptions must stay extensible so a consumer can refine the
error hierarchy. That the real extension points are interfaces is shown by the benchmarks:
`FakeBlockLightSource` implements `BlockLightSource` instead of subclassing `ChunkLightService`. An
accidentally non-final class in an `@Experimental` API without japicmp is a silent extension point a
consumer binds to, and no build reports breaking it.

```java
private static final DescribedPredicate<JavaClass> EXTENDS_MINESTOM =
        describe("extends a Minestom type", c -> c.getRawSuperclass()
                .map(s -> s.getPackageName().startsWith("net.minestom")).orElse(false));

@ArchTest
static final ArchRule publicClassesAreFinal = classes()
        .that().resideInAnyPackage(PUBLISHED).and().arePublic()
        .and().areNotInterfaces().and().areNotEnums().and().areNotRecords()
        .and().doNotHaveModifier(JavaModifier.SYNTHETIC)
        .and(not(assignableTo(Throwable.class)))
        .and(not(EXTENDS_MINESTOM))
        .should().haveModifier(JavaModifier.FINAL)
        .because("the extension points of this project are interfaces; what stays extensible is "
               + "what Minestom's protected hooks or an error hierarchy force");
```

### A6 — Utility classes have exactly one private no-arg constructor

**Statement.** A `public` class in the published packages that declares only static fields and methods
must have exactly one constructor, and it must be `private` and take no parameters.

**Rationale.** There are exactly four such classes — `RegionConstants`, `BitPacker`, `NbtReads`,
`SectionCodec` — and all four follow the pattern. The point is not style but the default constructor:
these four are the only places where javac would generate one if it were forgotten. A
`public RegionConstants()` would be pure format arithmetic nobody is meant to instantiate, and it
would go into the release jar unchanged and could neither be noticed nor later removed
binary-compatibly without japicmp. Pure hygiene next to A1–A3, hence last.

```java
private static final DescribedPredicate<JavaClass> ONLY_STATIC_MEMBERS =
        describe("declares only static members", c ->
                   c.getFields().stream().allMatch(f -> f.getModifiers().contains(JavaModifier.STATIC))
                && c.getMethods().stream().allMatch(m -> m.getModifiers().contains(JavaModifier.STATIC))
                && !c.getMethods().isEmpty());

private static final ArchCondition<JavaClass> ONE_PRIVATE_NO_ARG_CTOR =
        new ArchCondition<>("have exactly one private no-arg constructor") {
            @Override public void check(JavaClass item, ConditionEvents events) {
                Set<JavaConstructor> ctors = item.getConstructors();
                boolean ok = ctors.size() == 1
                        && ctors.iterator().next().getModifiers().contains(JavaModifier.PRIVATE)
                        && ctors.iterator().next().getRawParameterTypes().isEmpty();
                events.add(new SimpleConditionEvent(item, ok, item.getName()
                        + " declares only static members and must therefore have exactly one"
                        + " private no-arg constructor"));
            }
        };

@ArchTest
static final ArchRule utilityClassesHideTheirConstructor = classes()
        .that().resideInAnyPackage(PUBLISHED).and().arePublic()
        .and().areNotInterfaces().and().areNotEnums().and().areNotRecords()
        .and().doNotHaveModifier(JavaModifier.SYNTHETIC)
        .and(ONLY_STATIC_MEMBERS)
        .should(ONE_PRIVATE_NO_ARG_CTOR)
        .because("a forgotten default constructor becomes part of the published jar at once and "
               + "cannot be undone without an API diff tool");
```

---

## ForeignCouplingTest

### F1 — The compute core of `falco-light` knows no Minestom type

**Statement.** No class in `.light` may depend on `net.minestom..`, except the five boundary classes
`ChunkLightService`, `ChunkLightArea`, `ChunkLightScheduler`, `FalcoLightingChunk` and
`MinestomBlockLightSource`, each including nested and anonymous types.

**Rationale.** Of the package's fourteen types exactly those five name Minestom; the other nine have
neither an import nor a fully qualified reference. `BlockLightSource.java:7-12` names the seam itself:
"separates the algorithm from the registries of a running server, which is what allows the engine to
be verified without starting one". The benefit is measurable rather than narrated:
`LightPropagatorTest` and `LightEngineConcurrencyTest` import no Minestom symbol, and
`LightPropagatorBenchmark` runs in a bare JMH fork against `FakeBlockLightSource` without
`MinecraftServer.init()`.

```java
private static final String LIGHT_BOUNDARY =
        "net\\.onelitefeather\\.falco\\.light\\."
      + "(ChunkLightService|ChunkLightArea|ChunkLightScheduler"
      + "|FalcoLightingChunk|MinestomBlockLightSource)(\\$.*)?";

@ArchTest
static final ArchRule lightCoreKnowsNoMinestom = noClasses()
        .that().resideInAPackage(LIGHT).and().haveNameNotMatching(LIGHT_BOUNDARY)
        .should().dependOnClassesThat().resideInAnyPackage("net.minestom..")
        .because("propagation has to stay verifiable without a running server and work with any "
               + "chunk implementation; only those five classes are the boundary");
```

The name regex with `(\$.*)?` is mandatory instead of `doNotHaveFullyQualifiedName`: the private
records `ChunkLightService$NeighbourhoodEntry` (`:370-374`) and `ChunkLightArea$Entry` (`:582-588`)
both carry a `net.minestom.server.instance.Chunk` as a component and would otherwise be false
positives on day one.

**A side finding for the documentation, not for the rule:** `falco-light/package-info.java:12-17`
claims `MinestomBlockLightSource` is "the only type here that knows about Minestom". Four further
classes refute that. The line should be corrected to the boundary recorded here.

### F2 — Everything below the chunk loader in `falco-anvil` is Minestom-free

**Statement.** No class in `.anvil` may depend on `net.minestom..`, except `FalcoAnvilLoader`,
`BlockPaletteResolver` and `BiomePaletteResolver` including nested types.

**Rationale.** Of the fourteen types exactly three name Minestom; `RegionFile` imports nothing but
`java.*` and `org.jetbrains.annotations`. `package-info.java:4-6` says it verbatim: `FalcoAnvilLoader`
is the entry point, "Everything else in this package is a layer below it". The benefit is the
**precondition of the published numbers**: `RegionFileComparisonBenchmark.java:45-48` records that
Minestom's `AnvilLoader` cannot be touched in a bare fork at all, because its static fields read the
biome registry and the block state count, while Falco's region file reads no registry and is therefore
directly measurable. Second, the three-stage sequence only holds if stages 1 and 2 cannot touch a
chunk at all ("no CPU-bound work happens while a lock is held",
`Rationale-Chunk-Loading.md:191-192`).

```java
private static final String ANVIL_MINESTOM_BOUNDARY =
        "net\\.onelitefeather\\.falco\\.anvil\\."
      + "(FalcoAnvilLoader|BlockPaletteResolver|BiomePaletteResolver)(\\$.*)?";

@ArchTest
static final ArchRule anvilCoreKnowsNoMinestom = noClasses()
        .that().resideInAPackage(ANVIL).and().haveNameNotMatching(ANVIL_MINESTOM_BOUNDARY)
        .should().dependOnClassesThat().resideInAnyPackage("net.minestom..")
        .because("RegionFile reads no registry and is only for that reason measurable against "
               + "Minestom's RegionFile in a bare JMH fork; package-info calls everything below "
               + "the loader a layer beneath it");
```

Here too the exemption has to fall on the outermost type:
`FalcoAnvilLoader$ResolvedRegionDirectory` (`:229`), `$RegionHandle` (`:868`), `$DecodedSection`
(`:1037`) and `BiomePaletteResolver$Registries` (`:106`) would otherwise be four false positives.

### F3 — Falco does not implement Minestom's `Light` and does not call its internal compute methods

**Statement.** No Falco class may implement `net.minestom.server.instance.light.Light`, and none may
call `calculateInternal` or `calculateExternal` on a type of that package.

**Rationale.** Both mechanisms verified against the bound Minestom version `2026.06.20-26.1.2`. First:
`Section` is a record whose `clone()` at `instance/Section.java:26-33` calls `Light.sky()`/`Light.block()`
directly and copies existing light objects only via `set(array())` — a foreign implementation is
silently replaced by the built-in one when copying. The prototype that carried a marker byte `0xCD`
all the way into the `LightData` record worked and was wrong anyway (`Rationale-Lighting.md:472`).
Second: `calculateInternal` and `calculateExternal` carry `@ApiStatus.Internal`, `set` does not. Both
mistakes would have the same signature in production: light looks right locally and disappears on the
next chunk copy or update. An ArchUnit test is the only safeguard, because both compile.

```java
@ArchTest
static final ArchRule noOwnLightImplementation = noClasses()
        .that().resideInAPackage("net.onelitefeather.falco..")
        .should().implement("net.minestom.server.instance.light.Light")
        .because("Section.clone() calls Light.sky()/Light.block() directly and silently replaces a "
               + "foreign implementation while copying");

@ArchTest
static final ArchRule noInternalLightCalls = noClasses()
        .that().resideInAPackage("net.onelitefeather.falco..")
        .should().callMethodWhere(
                target(owner(nameMatching("net\\.minestom\\.server\\.instance\\.light\\..*")))
                        .and(target(nameMatching("calculate(Internal|External)"))))
        .because("both methods carry @ApiStatus.Internal and may change their signature between "
               + "Minestom versions; Light#set may not");
```

The call rule has to be phrased over the **name of the target method**, not over its annotation:
`falco-archunit` imports only Falco classes and cannot read the annotations of a Minestom method it
never imported.

### F4 — Within `falco-light`, only `ChunkLightService` writes into Minestom's `Light`

**Statement.** No class in `.light` other than `ChunkLightService` including nested types may depend
on `net.minestom.server.instance.light..`.

**Rationale.** Only three lines in `falco-light` touch the `Light` interface, all in
`ChunkLightService` (`:167`, `:170`, `:390`). `ChunkLightArea` deliberately obtains the write through
`ChunkLightService.applyLight(...)` (`ChunkLightArea.java:549`) rather than writing itself — which is
why `applyLight` is public static. The reason is in the method's Javadoc
(`ChunkLightService.java:146-151`): the write path goes through `Light#set`, which also clears the
section's update flag, so the server never recomputes; a wrong result is never corrected.

```java
@ArchTest
static final ArchRule onlyTheServiceWritesMinestomLight = noClasses()
        .that().resideInAPackage(LIGHT)
        .and().haveNameNotMatching("net\\.onelitefeather\\.falco\\.light\\.ChunkLightService(\\$.*)?")
        .should().dependOnClassesThat().resideInAPackage("net.minestom.server.instance.light..")
        .because("Light#set clears the section's update flag - the server does not recompute after "
               + "that, so computed light gets exactly one tested write path");
```

**The scope is deliberately narrowed to `falco-light`.** Over `net.onelitefeather.falco..` the rule
would not hold today: `FalcoAnvilLoader` reads from and writes into that package as well (`:1056`,
`:1059`, `:1254`, `:1255`). That is legitimate, because the loader moves stored light rather than
computed light.

### F5 — The block and biome registry is queried only in the named adapters

**Statement.** In `.light` and `.anvil`, only `MinestomBlockLightSource` and `BlockPaletteResolver`
may call the static registry entry points of `net.minestom.server.instance.block.Block`
(`fromStateId`, `fromKey`, `fromBlockId`, field `AIR`), and only `BiomePaletteResolver` may depend on
the dynamic registry in `net.minestom.server.registry..` — everything in that package except
`RegistryData` and its `*Entry` types.

> **Corrected during implementation.** The first version of this statement covered the whole package
> and was red on the first run, against `MinestomBlockLightSource.blocksFace` (`:64`) and
> `emission` (`:51`). The package holds two unrelated things: the dynamic registry, which needs a
> booted server (`DynamicRegistry`, `RegistryKey`, `Registries`, `Holder`, `TagKey`), and
> `RegistryData`, the static data tables a block carries and which `block.registry()` hands out.
> `MinestomBlockLightSource` only ever reaches the latter — which is exactly the work F5a grants it
> by name, quoting `occlusionShape()`. Over the whole package this rule revoked the grant of its own
> neighbour. Narrowed to the dynamic registry, both rules say what they meant. Written as a
> complement, like F6 and F7, so a new type in the package is covered by default.

**Rationale.** Both modules have an interface for exactly this purpose, and both state the reason in
Javadoc (`BlockLightSource.java:8-11`, `PaletteEntryResolver.java:27`). The second reason is measured:
Minestom's `LightCompute` resolves `Block.fromStateId` plus `occlusionShape()` per direction while
Falco answers the same question from an array — "Falco resolves nothing here"
(`Rationale-Lighting.md:143`). A `fromStateId` resolution in `LightPropagator` or `SectionOpacity`
would cost both the server-free testability **and** the only measured advantage.

```java
@ArchTest
static final ArchRule blockRegistryOnlyInAdapters = noClasses()
        .that().resideInAnyPackage(LIGHT, ANVIL)
        .and().haveNameNotMatching("net\\.onelitefeather\\.falco\\."
                + "(light\\.MinestomBlockLightSource|anvil\\.BlockPaletteResolver)(\\$.*)?")
        .should().callMethodWhere(
                target(owner(name("net.minestom.server.instance.block.Block")))
                        .and(target(nameMatching("from(StateId|Key|BlockId)"))))
        .orShould().accessFieldWhere(
                fieldTarget(owner(name("net.minestom.server.instance.block.Block")))
                        .and(fieldTarget(name("AIR"))))
        .because("BlockLightSource and PaletteEntryResolver exist for exactly this: algorithm and "
               + "codec stay verifiable without a running server, and the per-direction resolution "
               + "is the measured cost against LightCompute");

@ArchTest
static final ArchRule dynamicRegistryOnlyInBiomeResolver = noClasses()
        .that().resideInAnyPackage(LIGHT, ANVIL)
        .and().haveNameNotMatching(
                "net\\.onelitefeather\\.falco\\.anvil\\.BiomePaletteResolver(\\$.*)?")
        .should().dependOnClassesThat(
                resideInAPackage("net.minestom.server.registry..")
                        .and(not(nameMatching("net\\.minestom\\.server\\.registry\\.RegistryData(\\$.*)?")))
                        .as("a type of the dynamic registry, which needs a booted server"))
        .because("the biome registry depends on a running server; it is wrapped behind a supplier "
               + "in exactly one adapter");
```

**The rule addresses the static entry points, not the type `Block` as a whole.** The naive version —
"no method on `Block` outside the adapters" — would not hold today: `FalcoAnvilLoader` calls
`block.withHandler(...)` (`:1138`), `block.nbt()` (`:1215`) and `block.handler()` (`:1216`, `:1225`)
on already resolved block instances, which is not a registry resolution. `falco-instance` is out of
scope entirely: an `Instance` implementation has to query the registry
(`FalcoInstance.java:373,434`).

### F6 — The byte and packing layer of `falco-anvil` knows no NBT

**Statement.** Only `FalcoAnvilLoader`, `SectionCodec`, `NbtReads`, `PaletteEntryResolver`,
`BlockPaletteResolver` and `BiomePaletteResolver` may depend on `net.kyori..` inside `.anvil`; no
other type may.

**Rationale.** The Javadoc of `RegionFile` claims it verbatim (`:21-22`). That separation is the
precondition for the one property the loader exists for: Minestom's `RegionFile.readChunkData` holds
its `ReentrantLock` across the entire body including inflate and NBT parse
(`Rationale-Chunk-Loading.md:60-70`), **because the parser was reachable there**. Falco's `RegionFile`
cannot make that mistake as long as it does not see `adventure-nbt`: `readRaw` returns bytes, and
parsing happens a layer above without a lock. A kyori import in `RegionFile` is therefore not
"unclean" but the door through which exactly the behaviour returns that the measurement shows on
Minestom's side as a loss of predictability under concurrency.

```java
private static final String ANVIL_NBT_LAYER =
        "net\\.onelitefeather\\.falco\\.anvil\\."
      + "(FalcoAnvilLoader|SectionCodec|NbtReads|PaletteEntryResolver"
      + "|BlockPaletteResolver|BiomePaletteResolver)(\\$.*)?";

@ArchTest
static final ArchRule byteLayerKnowsNoNbt = noClasses()
        .that().resideInAPackage(ANVIL).and().haveNameNotMatching(ANVIL_NBT_LAYER)
        .should().dependOnClassesThat().resideInAnyPackage("net.kyori..")
        .because("RegionFile is, by its own Javadoc, a pure byte container; that alone keeps the "
               + "NBT parse from slipping inside the region lock the way it did in Minestom");
```

Phrased as a complement rather than a hand-maintained allow list: this covers `PaletteData` and
`AnvilChunkException`, which are NBT-free today and should stay so, plus nested types such as
`RegionFile$RawChunk` and `RegionFile$FileAction`, and it needs no maintenance when a class is added.

### F7 — Only `RegionFile` and `FalcoAnvilLoader` touch the file system

**Statement.** No class in `.anvil` other than `RegionFile` and `FalcoAnvilLoader` including nested
types may depend on `java.nio.file..`, `java.nio.channels..`, `java.io.File` or
`java.io.RandomAccessFile`.

**Rationale.** `BitPacker.java:11-12` states the reason itself: "The class only contains pure
functions so the encoding can be verified without any file or server access". That is not only
testability but the locking scheme: the loader's rule is "no CPU-bound work happens while a lock is
held", and it only holds because the decoding middle stage cannot open a file at all. Windows
correctness hangs on it too — the `.mcc` handling with `ATOMIC_MOVE`, `placeExternal` and
`removeExternal` was fixable in one place precisely because only one place knows file names. A
`Files.readAllBytes` in `SectionCodec` would be a second one, where the Windows bug returns unnoticed.

```java
@ArchTest
static final ArchRule filesystemOnlyAtTheEdge = noClasses()
        .that().resideInAPackage(ANVIL)
        .and().haveNameNotMatching(
                "net\\.onelitefeather\\.falco\\.anvil\\.(RegionFile|FalcoAnvilLoader)(\\$.*)?")
        .should().dependOnClassesThat(
                resideInAnyPackage("java.nio.file..", "java.nio.channels..")
                        .or(belongToAnyOf(java.io.File.class, java.io.RandomAccessFile.class)))
        .because("the encoding layer has to stay verifiable without file or server access, and the "
               + "rule 'no CPU-bound work under a lock' only holds if the middle stage cannot open "
               + "a file at all; java.io stays allowed because of ChunkCompression");
```

`java.io` is deliberately **not** blocked wholesale: `ChunkCompression.java:6-10` uses
`ByteArrayInputStream`/`ByteArrayOutputStream`, so never a file. And
`FalcoAnvilLoader$ResolvedRegionDirectory` (`:229`) carries a field of type `Path` — with
`doNotHaveFullyQualifiedName` instead of the regex the rule would report a false positive at once.

### F8 — `falco-light` neither parses nor reads

**Statement.** No class in `.light` may depend on `net.kyori..`, `java.io..`, `java.nio.file..` or
`java.nio.channels..`.

**Rationale.** The engine has exactly one exit, the finished `byte[2048]` through `Light#set`; it
serialises nothing and knows no chunk storage format. That is precisely why `falco-light` is verified
against a handful of fake blocks while `falco-anvil` needs region files for the same purpose. The rule
is not self-enforcing, and the evidence is in the build: `falco-light/build.gradle.kts:7` pulls
`adventure-nbt` as `compileOnly` although no main class uses it — a `CompoundBinaryTag` import in
`LightNibbles` compiles straight through and nobody notices. The `java.io` block is broad on purpose:
the realistic slip is not a `FileChannel` but a `ByteArrayOutputStream` somebody serialises light into
"just for a moment".

```java
@ArchTest
static final ArchRule lightNeitherParsesNorReads = noClasses()
        .that().resideInAPackage(LIGHT)
        .should().dependOnClassesThat().resideInAnyPackage(
                "net.kyori..", "java.io..", "java.nio.file..", "java.nio.channels..")
        .because("the engine has exactly one exit, Light#set; it knows no storage format and no "
               + "file, or it would no longer be independent of the chunk implementation");
```

---

## ErrorHandlingTest

The three published modules run inside somebody else's server, and that operator has configured
exactly two reporting paths: their SLF4J backend and Minestom's `ExceptionManager`. Anything that
bypasses both does not exist for them.

### E1 — Every own exception type is public, unchecked and takes a cause

**Statement.** Every `Throwable` subclass declared in `net.onelitefeather.falco` must be `public`,
extend `java.lang.RuntimeException` and have a public `(String, Throwable)` constructor.

**Rationale.** The inheritance clause is not a matter of taste but forced by the signature:
`FalcoAnvilLoader.saveChunk` and `loadChunk` override the Minestom `ChunkLoader` methods and declare
no `throws` — a checked Falco exception could not be thrown at that boundary at all. The cause clause
protects the single translation point: `failedLoad` wraps the `IOException` or `RuntimeException` of
the whole read chain into the `AnvilChunkException`, and without that constructor the message only
says a chunk could not be loaded, without the region file or the NBT key behind it.

```java
@ArchTest
static final ArchRule ownExceptionsAreUncheckedAndCarryACause = classes()
        .that().areAssignableTo(Throwable.class)
        .and().resideInAPackage("net.onelitefeather.falco..")
        .should().bePublic()
        .andShould().beAssignableTo(RuntimeException.class)
        .andShould(new ArchCondition<JavaClass>("have a public (String, Throwable) constructor") {
            @Override public void check(JavaClass clazz, ConditionEvents events) {
                boolean ok = clazz.getConstructors().stream().anyMatch(c ->
                        c.getModifiers().contains(JavaModifier.PUBLIC)
                        && c.getRawParameterTypes().size() == 2
                        && c.getRawParameterTypes().get(0).isEquivalentTo(String.class)
                        && c.getRawParameterTypes().get(1).isEquivalentTo(Throwable.class));
                if (!ok) events.add(SimpleConditionEvent.violated(clazz,
                        clazz.getName() + " cannot carry a cause"));
            }
        })
        .because("saveChunk declares no throws, and failedLoad is the only point where the cause of "
               + "the read chain survives");
```

A naming clause such as `haveSimpleNameEndingWith("Exception")` is deliberately **not** part of the
rule — violating it harms nobody.

### E2 — No `new RuntimeException`, `Exception`, `Throwable` or `Error`

**Statement.** No class in `net.onelitefeather.falco` may instantiate those four types directly.

**Rationale.** Zero occurrences in main and jmh today; what is thrown is 42 `IllegalArgumentException`
and 7 `IllegalStateException` for programming errors, 14 `IOException` for format errors and 3
`FalcoInstanceException` at the boundary. A bare `new RuntimeException` falls into none of those
buckets and has a concrete consequence: `FalcoAnvilLoader.loadChunk` catches
`IOException | RuntimeException` at `:318` and translates everything inside into an
`AnvilChunkException` saying the chunk is not loadable. A generic throw somewhere in the read chain
would thus be **relabelled as a data error of the chunk** although it is a programming error.
Conversely, `saveChunk` sorts its `IllegalStateException` out at `:365` ahead of the broad catch at
`:369` — and that sorting only works while the types mean something.

```java
@ArchTest
static final ArchRule noGenericThrowables = noClasses()
        .that().resideInAPackage("net.onelitefeather.falco..")
        .should().callConstructorWhere(target(owner(nameMatching(
                "java\\.lang\\.(RuntimeException|Exception|Throwable|Error)"))))
        .because("loadChunk catches IOException|RuntimeException and labels everything inside a "
               + "chunk data error; a generic throw disappears into that translation");
```

### E3 — Only the two boundary classes report to the `ExceptionManager`

**Statement.** `MinecraftServer.getExceptionManager()` may be called only from `FalcoAnvilLoader` and
`ChunkLightScheduler`.

**Rationale.** All four reporting sites are in exactly those two classes. Any further one would be a
second translation point where the same failure reaches the operator twice. The hard reason is
testability: `NbtReads`, `PaletteData`, `SectionCodec`, `BitPacker`, `RegionFile`, `SectorAllocator`
and `LightNibbles` are format- and compute-pure classes with unit tests that never boot a server; a
`getExceptionManager` call there would require a running `MinecraftServer` and make those tests
impossible.

```java
@ArchTest
static final ArchRule exceptionManagerOnlyAtTheBoundary = noClasses()
        .that().resideInAPackage("net.onelitefeather.falco..")
        .and().doNotHaveFullyQualifiedName("net.onelitefeather.falco.anvil.FalcoAnvilLoader")
        .and().doNotHaveFullyQualifiedName("net.onelitefeather.falco.light.ChunkLightScheduler")
        .should().callMethodWhere(target(name("getExceptionManager"))
                .and(target(owner(name("net.minestom.server.MinecraftServer")))))
        .because("exactly one translation point per module, and the format and compute classes have "
               + "to stay testable without a booted server");
```

Exempted by fully qualified name so a same-named class in another package is not exempted by
accident. Unlike F1 and F2, nested types are **not** included here — `$RegionHandle` should not be
allowed to report. The rule cuts off the `ExceptionManager` only, not `MinecraftServer` as a whole,
because `BiomePaletteResolver` legitimately needs the registry.

### E4 — Only the demo may end the JVM

**Statement.** No class outside `.demo` may call `System.exit`, `Runtime.exit` or `Runtime.halt`.

**Rationale.** There is exactly one call, `ChunkLoadDemo:124`, justified in the code right above it:
the Minestom registries start threads that keep the JVM alive, and a demo that hangs after its report
looks like a defect of the loader. In the published modules the same call would be fatal: a
`System.exit` from inside the chunk loader — say as a convenient reaction to a broken region file —
tears down somebody else's server without a save and produces exactly the data loss
`AnvilChunkException` exists to prevent, by its own Javadoc.

```java
@ArchTest
static final ArchRule noJvmShutdownInLibraries = noClasses()
        .that().resideOutsideOfPackage(DEMO)
        .should().callMethod(System.class, "exit", int.class)
        .orShould().callMethod(Runtime.class, "exit", int.class)
        .orShould().callMethod(Runtime.class, "halt", int.class)
        .because("the published modules run inside somebody else's server; an abort from the chunk "
               + "loader loses every unsaved chunk");
```

### E5 — `System.out` and `System.err` stay in `falco-demo`

**Statement.** No class outside `.demo` may read the fields `System.out` or `System.err`.

**Rationale.** All 13 console accesses in the project are in `ChunkLoadDemo` and `DemoServer`, the two
main classes of the command line program, where the report belongs on stdout by definition. The
libraries run inside somebody else's server whose operator has configured SLF4J: a `println` there can
neither be switched off nor carries a level nor is findable in the operator's log, and it bypasses
both `LOGGER` and the `ExceptionManager`.

```java
@ArchTest
static final ArchRule noConsoleOutsideTheDemo = noClasses()
        .that().resideOutsideOfPackage(DEMO)
        .should().accessField(System.class, "out")
        .orShould().accessField(System.class, "err")
        .because("the published modules end up in somebody else's server, which has only slf4j "
               + "configured; the demo alone owns the console");
```

### E6 — `printStackTrace` exists nowhere, not even in the demo

**Statement.** No class may call a method named `printStackTrace` on a `Throwable` type.

**Rationale.** Zero occurrences across the whole main and jmh tree. For the libraries the reason of E5
applies, sharpened: `FalcoAnvilLoader` and `ChunkLightScheduler` report exclusively through `LOGGER`
and the `ExceptionManager`; a stack trace on stderr bypasses both. For the demo the reason is spelled
out in the code: the Javadoc of `DemoServer.main` records that every user error ends in a printed
explanation and a normal exit, and that "a stack trace on this console always means a defect" — a
`printStackTrace` would dissolve exactly that distinction.

```java
@ArchTest
static final ArchRule noPrintStackTrace = noClasses()
        .should().callMethodWhere(target(name("printStackTrace"))
                .and(target(owner(assignableTo(Throwable.class)))))
        .because("DemoServer.main documents that a stack trace on this console always means a "
               + "defect, and the libraries report only through LOGGER and the ExceptionManager");
```

The owner predicate is mandatory: `callMethod(Throwable.class, "printStackTrace")` only matches when
the owner in the bytecode is literally `java/lang/Throwable`. `IOException e; e.printStackTrace()`
compiles to `invokevirtual java/io/IOException.printStackTrace` and would slip through.

### E7 — Logger fields are `private static final` and SLF4J only

**Statement.** Every field of type `org.slf4j.Logger` must be `private`, `static` and `final`, and no
field may have the type `java.util.logging.Logger` or `java.lang.System.Logger`.

**Rationale.** All seven logger fields in the project are declared identically today. Two sit in
classes created per instance and per area respectively — a non-static logger there would be an extra
object on the load and light paths. More importantly, a `public` or `protected` logger field would be
API surface from publication onwards that nobody can remove again. The second condition pins the one
backend the embedding server configures.

```java
@ArchTest
static final ArchRule loggerFieldShape = fields()
        .that().haveRawType("org.slf4j.Logger")
        .should().bePrivate().andShould().beStatic().andShould().beFinal()
        .because("an instance logger in ChunkLightArea would be one object per area, and a visible "
               + "field in a published library would be irrevocable API");

@ArchTest
static final ArchRule onlySlf4jAsLogger = noFields()
        .should().haveRawType("java.util.logging.Logger")
        .orShould().haveRawType("java.lang.System$Logger")
        .because("the embedding server configures exactly one backend, slf4j; a second logging "
               + "system lands in no file the operator owns");
```

Types as strings, because SLF4J is only `implementation` in every module. A naming clause `LOGGER` is
deliberately not part of the rule: a field called `log` harms nobody. The overlap with A4 and C3 is
intentional — both apply to the three published packages only, while this rule applies project-wide.

---

## ConcurrencyTest

`Rationale-Concurrency.md` lists five defects that were found; three of the rules here are the scars.
The two most important rules of the class are red today — that is not an argument against them but the
reason to write them down.

### C1 — Mutable fields of shared objects are safely published — **red today**

**Statement.** A class of the published modules that declares a field with a raw type from
`java.util.concurrent`, or a volatile field, and thereby identifies itself as a shared object, may not
hold a non-static, non-final field that is neither volatile nor written exclusively in the constructor
or in methods carrying `ACC_SYNCHRONIZED`.

**Rationale.** The design allows exactly three forms of mutable state on a shared object, all three
demonstrated in the code: final, volatile, or a plain field whose every access sits in synchronized
methods. The fourth case — a bare field with neither — is the gap defect 5 of the wiki came out of.
`FalcoInstance.chunkSupplier:211` and `.chunkLoader:213` sit in it today.

The marker condition matters and was verified against the bytecode:
`SectorAllocator.totalSectors:24`, `ChunkLightState.removalQueue:72`, `LightNibbles.levels:53` and
`ChunkLightArea.Changes:670-676` are non-final as well, but declare no concurrency primitive and are
either call-confined or sit behind somebody else's lock — the rule is not meant to hit such helper
types.

```java
static final DescribedPredicate<JavaClass> SHARED_OBJECT =
        new DescribedPredicate<>("declare a concurrency primitive or a volatile field") {
            @Override public boolean test(JavaClass owner) {
                return owner.getFields().stream().anyMatch(field ->
                        field.getRawType().getPackageName().startsWith("java.util.concurrent")
                                || field.getModifiers().contains(JavaModifier.VOLATILE));
            }
        };

static final ArchCondition<JavaClass> ONLY_SAFELY_PUBLISHED_FIELDS =
        new ArchCondition<>("have only final, volatile or synchronized-written fields") {
            @Override public void check(JavaClass owner, ConditionEvents events) {
                for (JavaField field : owner.getFields()) {
                    Set<JavaModifier> m = field.getModifiers();
                    // STATIC skipped: C3 covers that
                    if (m.contains(JavaModifier.FINAL) || m.contains(JavaModifier.VOLATILE)
                            || m.contains(JavaModifier.STATIC)) continue;
                    boolean covered = field.getAccessesToSelf().stream()
                            .filter(a -> a.getAccessType() == JavaFieldAccess.AccessType.SET)
                            .allMatch(a -> a.getOrigin() instanceof JavaConstructor
                                    || a.getOrigin().getModifiers().contains(JavaModifier.SYNCHRONIZED));
                    if (!covered) events.add(SimpleConditionEvent.violated(field,
                            field.getFullName() + " is neither final nor volatile and is written "
                          + "outside the constructor and synchronized methods"));
                }
            }
        };

@ArchTest
static final ArchRule sharedStateIsSafelyPublished =
        classes().that(SHARED_OBJECT).and().resideInAnyPackage(PUBLISHED)
                .should(ONLY_SAFELY_PUBLISHED_FIELDS);
```

**Two honest limits.** ArchUnit sees only `ACC_SYNCHRONIZED` on methods, not synchronized blocks — so
fields guarded that way have to be volatile. And the marker only applies to the declared raw type:
`ChunkLightScheduler` qualifies through `lastTick`/`bound:113-114`, not through `dirty:111`, which is
declared as a `Map`.

### C2 — No publicly reachable monitor — **red today**

**Statement.** No `public` or `protected` method of the published modules may carry the `synchronized`
modifier bit.

**Rationale.** A synchronized method locks on `this`, and `this` — for a loader, a scheduler or an
instance — is an object the caller holds. Library and caller then share a monitor neither of them can
see in the other's code, and the caller who writes `synchronized(loader)` for good reasons blocks
`close()` of all things, which by its own Javadoc happens while chunk tasks are still running. The
design is otherwise consistent about this: `RegionFile` has a private `ReentrantLock` instead of
synchronized (`:112`), and the remaining synchronized methods are private methods of private nested
classes whose monitor never escapes. Exactly one place breaks the pattern.

```java
static final DescribedPredicate<JavaMember> EXTERNALLY_REACHABLE =
        modifier(JavaModifier.PUBLIC).or(modifier(JavaModifier.PROTECTED));

@ArchTest
static final ArchRule noPublicMonitor = noMethods()
        .that().areDeclaredInClassesThat().resideInAnyPackage(PUBLISHED)
        .and(EXTERNALLY_REACHABLE)
        .should().haveModifier(JavaModifier.SYNCHRONIZED)
        .because("synchronized locks on this, and this is held by the caller; the internal locks of "
               + "this design are private ReentrantLocks or monitors of private nested classes");
```

### C3 — No mutable static field

**Statement.** No class in the three published modules may declare a static field that is not final.

**Rationale.** The whole concurrency design rests on mutable state being owned by exactly one object,
and every protection mechanism is cut to that ownership: the seqlock lives in the
`AtomicIntegerArray` tables of **one** `RegionFile`, the fan-out bound in the semaphore of **one**
loader, the binding to a single instance in the `AtomicReference` of **one** scheduler. A mutable
static field sits outside every one of those mechanisms. Several loaders at once is not hypothetical
but the normal case — the loader is built per dimension. In the bytecode there is not a single
non-final static field today; even `$VALUES` and `$assertionsDisabled` are final. The rule pins a
state that already holds and is the cheapest safeguard in the set.

```java
@ArchTest
static final ArchRule noMutableStaticFields = fields()
        .that().areStatic().and().areDeclaredInClassesThat().resideInAnyPackage(PUBLISHED)
        .should().beFinal()
        .because("state belongs to an object (RegionFile, loader, scheduler, instance); a static "
               + "field is shared by every loader and instance in a JVM and covered by no lock of "
               + "this design");
```

### C4 — The library starts virtual threads only

**Statement.** No class of the published modules may construct a `java.lang.Thread` or a
`ForkJoinPool`, call an `Executors` factory other than `newVirtualThreadPerTaskExecutor`, or use a
method of `Thread.Builder.OfPlatform`.

**Rationale.** Minestom hands the loader one virtual thread per chunk because
`supportsParallelLoading()` reports true. A platform pool inside the library would put a second,
fixed-size parallelism next to that model which nobody can configure — and the work of these modules
is file IO and computation, exactly what the design chose virtual threads for. The rule is
**module-specific, not abstract**: `falco-demo` contains precisely the forbidden calls —
`Executors.newFixedThreadPool` (`LoadMeasurement:127`) and `new Thread` with a named thread factory
(`:198`) — and is allowed to, because there the thread count is the experiment. That distinction is
exactly what gets lost when somebody copies demo code into a published module.

```java
@ArchTest
static final ArchRule onlyVirtualThreads = noClasses()
        .that().resideInAnyPackage(PUBLISHED)
        .should().callConstructorWhere(target(owner(assignableTo(Thread.class))))
        .orShould().callConstructorWhere(target(owner(assignableTo(ForkJoinPool.class))))
        .orShould().callMethodWhere(target(owner(type(Executors.class)))
                .and(target(nameMatching("new(Fixed|Cached|SingleThread|Scheduled|WorkStealing).*"))))
        .orShould().callMethodWhere(target(owner(type(Thread.Builder.OfPlatform.class))))
        .because("Minestom hands over one virtual thread per chunk; a platform pool in the library "
               + "puts a second, unconfigurable parallelism next to it (falco-demo may do it, "
               + "because there the thread count is the experiment)");
```

Deliberately restricted to constructor calls and factory names so that `Thread.sleep` and
`Thread.currentThread().interrupt()` are not caught by mistake.

### C5 — Whoever starts virtual threads bounds them with a semaphore

**Statement.** Every class in `falco-anvil` or `falco-light` that calls `Thread.startVirtualThread` or
`Executors.newVirtualThreadPerTaskExecutor` must also call `Semaphore.acquire` or
`Semaphore.acquireUninterruptibly` in the same class.

**Rationale.** This is the one place where Falco deliberately replaces a Minestom default.
`ChunkLoader.saveChunks` is an interface default that registers one party per chunk on a `Phaser` and
starts a virtual thread whose `catch(Throwable)` branch does **not** call `arriveAndDeregister` — a
single throwable blocks the saving thread on `arriveAndAwaitAdvance` forever.
`FalcoAnvilLoader.saveChunks` instead groups by region and bounds with `max(availableProcessors, 2)`
permits, and `ChunkLightScheduler.defaultExecutor()` builds exactly the same shape — including the
subtlety that the permit is taken *inside* the task, so the ticking chunk does not block. The comment
at the second site points explicitly at the first; the rule pins that coupling rather than leaving it
to a comment.

```java
static final DescribedPredicate<JavaClass> STARTS_VIRTUAL_THREADS =
        new DescribedPredicate<>("start virtual threads") {
            @Override public boolean test(JavaClass owner) {
                return owner.getMethodCallsFromSelf().stream().anyMatch(call ->
                        (call.getTargetOwner().isEquivalentTo(Thread.class)
                                && call.getName().equals("startVirtualThread"))
                     || (call.getTargetOwner().isEquivalentTo(Executors.class)
                                && call.getName().equals("newVirtualThreadPerTaskExecutor")));
            }
        };

@ArchTest
static final ArchRule virtualThreadsAreBounded =
        classes().that(STARTS_VIRTUAL_THREADS).and().resideInAnyPackage(ANVIL, LIGHT)
                .should(callMethodWhere(target(owner(type(Semaphore.class)))
                        .and(target(nameMatching("acquire|acquireUninterruptibly")))))
                .because("Minestom's saveChunks default starts one thread per chunk on a Phaser that "
                       + "never arrives on a throwable; Falco bounds at max(availableProcessors, 2) "
                       + "permits");
```

The affected set is exactly two classes today; ArchUnit reports by itself when it runs empty.
**Limit:** the rule checks that the bound exists, not that it is correct. `falco-instance` is out of
scope because its three `startVirtualThread` sites mirror Minestom's own "one virtual thread per chunk
operation" model, and the bound on saving lives in the loader.

### C6 — No `ThreadLocal`

**Statement.** No class of the published modules may use `ThreadLocal`, neither as a field type nor as
a call or construction target.

**Rationale.** `ThreadLocal` is the obvious reflex to defect 1 of the wiki: `ChunkLightService` shared
its scratch buffers because a `ChunkLightPropagator` sat in a field, and a probe found wrong light in
roughly 99 percent of concurrent calls. The design chose per-call allocation instead. Under this
execution model that is also the only sensible choice: Minestom gives one fresh virtual thread per
chunk, so a `ThreadLocal` would practically never see a second hit while holding, per short-lived
thread, a buffer on the order of 100 KB per `ChunkLightState`. It would pay the cost of a cache
without ever being one — and **would look as though the sharing problem were solved**, which is the
actual damage it does.

```java
@ArchTest
static final ArchRule noThreadLocalAsCallTarget = noClasses()
        .that().resideInAnyPackage(PUBLISHED)
        .should().accessClassesThat().areAssignableTo(ThreadLocal.class)
        .because("the answer to shared scratch buffers here is per-call allocation; with one "
               + "virtual thread per chunk a ThreadLocal caches nothing but pins a 100 KB buffer "
               + "per thread");

// Closes the gap of a passed-in field the class itself never touches.
@ArchTest
static final ArchRule noThreadLocalAsField = noFields()
        .that().areDeclaredInClassesThat().resideInAnyPackage(PUBLISHED)
        .should(haveRawTypeThat(assignableTo(ThreadLocal.class)));
```

### C7 — No blocking wait in the published modules

**Statement.** No class of the three published modules other than `RegionFile.retryWhileDenied` may
call `Thread.sleep`, `TimeUnit.sleep` or `LockSupport.park`/`parkNanos`/`parkUntil`.

**Rationale.** There is exactly one site, `RegionFile.retryWhileDenied`, which on an
`AccessDeniedException` sleeps up to 100 times for 1 ms each — a Windows quirk when renaming external
chunk files. The method hangs off the save path only, and `FalcoAnvilLoader.saveChunk` runs on the
caller's thread while only `saveChunks` builds virtual threads of its own. An operator calling
`saveChunk` from a tick task therefore stalls for up to 100 ms — two ticks — for a single chunk file.
Precisely the kind of mistake no review finds, because the sleep is three levels below the call.

**The exception is by name and lives in the test code**, not in a comment under the call, and not as
an exemption of the class or the module. Every further sleep trips the rule immediately.

```java
@ArchTest
static final ArchRule noBlockingWait = noCodeUnits()
        .that().areDeclaredInClassesThat().resideInAnyPackage(PUBLISHED)
        .and().doNotHaveFullyQualifiedName(
                "net.onelitefeather.falco.anvil.RegionFile.retryWhileDenied("
              + "net.onelitefeather.falco.anvil.RegionFile$FileAction)")
        .should().callMethod(Thread.class, "sleep", long.class)
        .orShould().callMethod(Thread.class, "sleep", long.class, int.class)
        .orShould().callMethod(Thread.class, "sleep", java.time.Duration.class)
        .orShould().callMethod(java.util.concurrent.TimeUnit.class, "sleep", long.class)
        .orShould().callMethodWhere(target(nameMatching("park(Nanos|Until)?"))
                .and(target(owner(name("java.util.concurrent.locks.LockSupport")))))
        .because("saveChunk runs on the caller's thread, often the tick thread; 100 attempts of 1 ms "
               + "are two stalled ticks there. The one tolerated site is the Windows retry when "
               + "replacing an .mcc file");
```

The exact signature of `retryWhileDenied` has to be read off the bytecode when writing this; a wrong
exemption string makes the rule silently red, not silently green.

**A limit, named honestly:** the rule measures "blocking wait", not "blocks the tick thread". A sleep
on a self-created thread would be a hit as well.

---

## Changes to the existing code

Four rules are red today. Three are fixed in the code; the fourth gets the named exception from C7.

### 1. `RegionFile.RawChunk` gets `@ApiStatus.Experimental`

`falco-anvil/.../RegionFile.java:682` — `public record RawChunk(ChunkCompression compression, byte[]
payload)`, the only one of the 31 public types without the marker. `RegionFile` itself carries it at
`:67`. One line, no signature change; the Javadoc block above it is already complete.

Making `RawChunk` package-private is **not** an alternative: `readRaw` returns it, so A3 would trip.

### 2. `FalcoAnvilLoader.close()` gets a private lock

`falco-anvil/.../FalcoAnvilLoader.java:618` — `public synchronized void close() throws IOException`,
the only externally visible synchronized method in the three modules. A private
`final Object closeLock = new Object();` (or a `ReentrantLock` in the shape `RegionFile:112` already
uses), with the body wrapped in `synchronized (closeLock)`. Cost: one field. After that a caller
writing `synchronized(loader)` can no longer block `close()`.

### 3. Two fields in `FalcoInstance` become `volatile`

`falco-instance/.../FalcoInstance.java:211` (`chunkSupplier`) and `:213` (`chunkLoader`), written by
the public, unsynchronized setters at `:804` and `:832`, read on the load path at `:564` and `:663`.
The same answer the author already gave for `generator:200`, `autoChunkLoad:215` and
`lastBlockChangeTime:217` in the same class.

This fixes more than a stale read: it fixes unsafe publication. Without `volatile` the reading load
thread may observe a half-constructed `ChunkLoader`, because the value is an arbitrary caller object.
Making the setters synchronized would be the wrong answer — that would violate C2.

### 4. `RegionFile.retryWhileDenied` stays, documented

The named exemption is in C7. What belongs with it: name `EXTERNAL_ATTEMPTS * EXTERNAL_RETRY_DELAY` =
100 ms in the Javadoc of `FalcoAnvilLoader.saveChunk` as the worst case, so an operator knows the
number before calling the method from a tick task.

### 5. Two Javadoc corrections, without a rule

- `falco-light/package-info.java:12-17` claims `MinestomBlockLightSource` is "the only type here that
  knows about Minestom". Four further classes refute it. Correct the line to the boundary from F1.
- `PaletteData` and `RegionFile.RawChunk` hand out their internal arrays without copying. That is a
  deliberate decision on the load path and belongs in the Javadoc of both records as "the array is not
  copied" — not in a rule, which would force a copy per section.

## Deliberately not covered

Invariants visible in the code that get no rule.

1. **`synchronized` blocks on `this`.** `BiomePaletteResolver.java:87` contains
   `synchronized (this) { ... }` in a public final class — substantively the same mistake C2 forbids,
   one level down. ArchUnit does not model `monitorenter`/`monitorexit`; C2 is **structurally blind**
   to half the cases. The site itself is harmless (double-checked locking behind
   `volatile resolved:48`, runs once per resolver). Closing the gap needs ASM directly or a review
   criterion.
2. **Arrays escaping through record components.** See point 5 above. A rule "no public method returns
   an array" would be expressible, would be red in two places, and would force a copy per section in
   `PaletteData` on the load path.
3. **The seqlock protocol in `RegionFile`.** Whether the order read-version → read-data →
   read-version-again is respected is the actual correctness condition of the parallel read path — and
   invisible in ArchUnit's bytecode model. Accesses yes, their ordering no.
4. **"No CPU-bound work happens while a lock is held".** The load-bearing claim of the three-stage
   loader. F6 and F7 secure it *indirectly* by keeping NBT and the file system out of the wrong
   layers. The claim itself is not checkable: `lock()` and `unlock()` are ordinary method calls, and
   the held region between them is not a concept of the model.
5. **`@ApiStatus.Internal` calls into Minestom in general.** F3 forbids `calculateInternal` and
   `calculateExternal` by name. `Light#array()` carries `@ApiStatus.Internal` in `2026.06.20-26.1.2`
   as well, and `FalcoAnvilLoader` calls it at `:1254` and `:1255` while saving. A general rule is not
   expressible, because `falco-archunit` does not import Minestom and cannot read the annotations of a
   method it never imported. Adding Minestom to the `ClassFileImporter` would raise import time and
   fragility against Minestom versions considerably. The `array()` call belongs on the list of things
   to check on a Minestom update.
6. **Everything that only exists in Javadoc.** "every caller of this method is covered by a test",
   "the array is not copied", the truth of the `@Nullable` promises.
7. **Test sources.** Out of scope by construction: `falco-archunit` pulls the modules as dependencies
   and sees main only. That puts the `testImplementation(project(":falco-anvil"))` in `falco-light`
   and the project's only `printStackTrace` (`falco-anvil/src/test/.../FileTestBase.java:31`) beyond
   reach — and they should be.
8. **What a subtype of `FalcoInstance` and `FalcoChunk` may do.** A5 lets both stay non-final. Their
   protected surface is therefore part of the contract, but no rule says which invariants a subtype
   has to keep. Javadoc territory.
9. **`supportsParallelLoading() == true` ⇒ `loadChunk` is thread-safe.** The promise at
   `FalcoAnvilLoader:426` is the precondition for Minestom handing over a virtual thread per chunk,
   and therefore for the entire measured advantage. It is semantic and checkable by no static tool;
   its guardian is `LightEngineConcurrencyTest`, not ArchUnit.

## Verification

The work is done when:

1. `./gradlew :falco-archunit:test` is green. The 33 catalog entries become **39 `@ArchTest` fields** —
   M1 splits into three, F3, F5, E7 and C6 into two each — distributed 7 / 6 / 10 / 8 / 8 across the
   five classes. The test report has to show those 39: a rule that passes silently over an empty
   `that()` set is not a passing test. `failOnEmptyShould` covers that.
2. `./gradlew check` is green across all modules, including the three code changes.
3. Each of the four rules that are red today is shown to have **been** red: run once before the fix and
   record the message, so there is evidence the rule bites rather than running empty.
4. A spot check shows a rule is sharp: introduce a deliberate violation (for instance an
   `import net.minestom.server.instance.Chunk` in `LightPropagator`), watch it go red, revert. At
   least for M1, F1 and A3 — the three rules carrying the core claims.
