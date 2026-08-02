package net.onelitefeather.falco.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.stream.Stream;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasModifiers.Predicates.modifier;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;

/**
 * Guards the promise that {@code falco-anvil}, {@code falco-light} and {@code falco-instance} are
 * three separately pullable artefacts, each of which can be taken without the other two.
 *
 * <p>Nothing in the build enforces this. Exactly one {@code project} dependency between the three
 * exists — {@code falco-light} sees {@code falco-instance}, {@code compileOnly}, so that
 * {@code FalcoLightingChunk} can be a {@code FalcoChunk}; see
 * {@link #onlyTheChunkOfTheLightModuleKnowsTheInstanceModule} for what keeps it from spreading. Every
 * other direction is unenforced by the compiler, so a single {@code import} plus a single line in a
 * build file would silently turn three artefacts into
 * one. The same holds for the surface towards third parties: Minestom, adventure-nbt, annotations
 * and fastutil are {@code compileOnly} everywhere and never reach the published POM, so an
 * accidental new {@code implementation} dependency is invisible inside this repository and lands on
 * every Minestom server that pulls one of the artefacts.
 *
 * <p>This class therefore checks the three things the compiler cannot: that the published modules
 * do not know each other, that they use only the declared third-party libraries, and that the two
 * Minestom implementations Falco competes with stay out of the published code and out of the
 * measuring path of the demo.
 *
 * <p>The rules see main sources only, which is deliberate: {@code falco-archunit} pulls the modules
 * as dependencies and therefore stands exactly where a consumer of the jar stands. The
 * {@code testImplementation(project(":falco-anvil"))} in {@code falco-light/build.gradle.kts:19} is
 * beyond their reach, and should be.
 */
@AnalyzeClasses(
        packages = "net.onelitefeather.falco",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    static final String ANVIL    = "net.onelitefeather.falco.anvil..";
    static final String LIGHT    = "net.onelitefeather.falco.light..";
    static final String INSTANCE = "net.onelitefeather.falco.instance..";
    static final String DEMO     = "net.onelitefeather.falco.demo..";
    static final String BENCH    = "net.onelitefeather.falco.benchmark..";

    static final String[] PUBLISHED = {ANVIL, LIGHT, INSTANCE};

    /**
     * Builds one of the three isolation rules of M1.
     *
     * <p>Three explicit rules rather than {@code slices()}, on purpose: {@code PackageMatcher}
     * understands only {@code (*)} and {@code (**)} and has no regex alternative, so a plain
     * {@code (*)} over {@code net.onelitefeather.falco.(*)} would produce a fourth slice
     * {@code demo}, which rightly knows all three modules and would turn the rule red on the first
     * day for the wrong reason.
     *
     * @param self    the package of the module under inspection
     * @param foreign the packages that module must not reach into
     * @return the isolation rule for {@code self}
     */
    private static ArchRule isolated(String self, String... foreign) {
        return noClasses().that().resideInAPackage(self)
                .should().dependOnClassesThat().resideInAnyPackage(foreign)
                .because("the three modules are published separately and used separately; "
                       + "Installation.md promises: take one without the other");
    }

    /**
     * M1: {@code falco-anvil} knows neither of the other modules.
     *
     * <p>{@code Installation.md:14} promises "take one without the other if that is all you need",
     * and the build backs that up only by omission: no module declares a {@code project} dependency
     * on {@code falco-anvil}, so the first cross-module import compiles happily and breaks the
     * promise silently. The loader is the one of the three that genuinely needs nothing from the
     * other two: a world is read the same way whether the chunks it fills are Minestom's or Falco's.
     *
     * <p>{@code .demo} and {@code .benchmark} are forbidden targets as well: a published module
     * reaching into unpublished code would not even resolve for a consumer.
     */
    @ArchTest
    static final ArchRule anvilIsStandalone = isolated(ANVIL, LIGHT, INSTANCE, DEMO, BENCH);

    /**
     * M1: {@code falco-light} knows neither {@code falco-anvil} nor the unpublished modules.
     *
     * <p>{@code falco-instance} is no longer among the forbidden targets, and that is US-3.06 rather
     * than a relaxation of M1. {@code FalcoLightingChunk} is a {@code FalcoChunk} now, because the
     * alternative was the state this repository was in for three stages: two chunk types with one
     * superclass slot between them, so Falco's light and Falco's lifecycle could be copied together
     * but never built together. A chunk cannot be a {@code FalcoChunk} without the module that
     * defines it, so one of the two modules had to see the other, and this is the direction that
     * costs a consumer nothing they did not ask for.
     *
     * <p>What replaces the blanket ban is {@link #onlyTheChunkOfTheLightModuleKnowsTheInstanceModule}
     * below, which keeps the engine itself free of it. The dependency is {@code compileOnly} in
     * {@code falco-light/build.gradle.kts}, so it reaches no published POM and no consumer of the
     * bare engine.
     */
    @ArchTest
    static final ArchRule lightIsStandalone = isolated(LIGHT, ANVIL, DEMO, BENCH);

    /**
     * The two classes of {@code falco-light} whose job is to be, or to serve, a {@code FalcoChunk}.
     *
     * <p>Named rather than derived, because the point of the rule is that this list stays at two.
     * The trailing {@code (\$.*)?} covers an anonymous or nested class either of them may grow, which
     * javac emits under the outer name.
     */
    private static final DescribedPredicate<JavaClass> THE_CHUNK_SIDE_OF_THE_LIGHT_MODULE =
            nameMatching("net\\.onelitefeather\\.falco\\.light\\."
                       + "(FalcoLightingChunk|ChunkLightListener)(\\$.*)?")
                    .as("the chunk side of the light module")
                    .forSubtype();

    /**
     * M1b: inside {@code falco-light}, only the chunk and its listener may see {@code falco-instance}.
     *
     * <p>This is the concrete temptation the old blanket rule guarded against, and it survives the
     * edge unchanged. {@code LightUpdateAware} and {@code ChunkLightScheduler} form an interface that
     * {@code FalcoChunk} could serve directly, and a single import of {@code FalcoChunk} in
     * {@code ChunkLightArea} or {@code ChunkLightService} would put the instance module on the
     * classpath of every user of the light engine — including the ones running a plain
     * {@code InstanceContainer}, for whom {@code compileOnly} means the class is simply not there.
     *
     * <p>{@code ChunkLightScheduler} is deliberately not exempt even though its {@code supplier()}
     * hands out a {@code FalcoLightingChunk}: a method reference to a constructor is a dependency on
     * that class alone, not on its supertype, which is what keeps the scheduler loadable without
     * {@code falco-instance} present.
     */
    @ArchTest
    static final ArchRule onlyTheChunkOfTheLightModuleKnowsTheInstanceModule = noClasses()
            .that().resideInAPackage(LIGHT)
            .and(not(THE_CHUNK_SIDE_OF_THE_LIGHT_MODULE))
            .should().dependOnClassesThat().resideInAPackage(INSTANCE)
            .because("falco-instance is compileOnly here, so every other class of this module has to "
                   + "keep loading and running on a server that never pulled it");

    /**
     * M1: {@code falco-instance} knows neither of the other modules.
     *
     * <p>{@code FalcoInstance} takes its {@code ChunkLoader} and its {@code ChunkSupplier} from the
     * caller and must not reach for {@code FalcoAnvilLoader} or {@code FalcoLightingChunk} itself.
     * The moment it does, the instance artefact stops being usable with a plain Minestom loader,
     * which is the entire point of being able to take it on its own.
     */
    @ArchTest
    static final ArchRule instanceIsStandalone = isolated(INSTANCE, ANVIL, LIGHT, DEMO, BENCH);

    /**
     * The complete set of packages the three published modules are allowed to reach into.
     *
     * <p>The primitive clause is not cosmetic: ArchUnit reports {@code int} as a dependency whose
     * package name is empty, and an empty package name matches none of the patterns above. Listing
     * the three Falco packages here is only safe because M1 already forbids every dependency
     * between them.
     */
    private static final DescribedPredicate<JavaClass> ALLOWED_DEPENDENCY =
            resideInAnyPackage(ANVIL, LIGHT, INSTANCE,
                    "java..", "net.minestom..",
                    "net.kyori.adventure.nbt..", "net.kyori.adventure.key..",
                    "it.unimi.dsi.fastutil..", "org.slf4j..", "org.jetbrains.annotations..")
                    .or(describe("a primitive", JavaClass::isPrimitive));

    /**
     * M2: the published modules use only the third-party libraries the build actually declares.
     *
     * <p>Minestom, adventure-nbt, annotations and fastutil are {@code compileOnly} and never reach
     * the published POM; {@code slf4j-api} alone is {@code implementation} and lands there with
     * runtime scope, see {@code Dependency-Management.md:94-115}. Every further
     * {@code implementation} dependency is therefore a library the project forces onto every
     * Minestom server that pulls one of the artefacts, version conflict included, which the
     * consumer then has to resolve.
     *
     * <p>The rule reads bytecode, not build files. It catches the import; whether the matching
     * build change was made deliberately is nothing it can tell.
     */
    @ArchTest
    static final ArchRule publishedModulesOnlyUseDeclaredDependencies = classes()
            .that().resideInAnyPackage(PUBLISHED)
            .should().onlyDependOnClassesThat(ALLOWED_DEPENDENCY)
            .because("anything outside this list appears as a new runtime dependency in the "
                   + "published POM and is forced on every Minestom server");

    /**
     * The two Minestom types Falco exists to replace, matched by name rather than by class literal.
     *
     * <p>A name predicate keeps the rule independent of whether those Minestom classes resolve at
     * import time, and it survives a Minestom release that moves them behind a different supertype.
     */
    private static final DescribedPredicate<JavaClass> THE_REPLACED_IMPLEMENTATIONS =
            nameMatching("net\\.minestom\\.server\\.instance\\.(anvil\\.AnvilLoader|LightingChunk)")
                    .as("the Minestom loader and lighting chunk Falco replaces")
                    .forSubtype();

    /**
     * M3: no published module touches the Minestom implementation it replaces.
     *
     * <p>The likely violation is not malicious but convenient: derive {@code FalcoLightingChunk}
     * from {@code LightingChunk} to inherit its packet handling, or fall back to Minestom's
     * {@code AnvilLoader} when the own codec cannot read a format. After that every published
     * measurement compares the library against itself with nothing in the result to show for it,
     * and the {@code compileOnly} construction quietly drags exactly the implementation the
     * consumer wanted to replace into their runtime path. Today only unpublished classes name them,
     * see {@code LoaderKind.java:4}, {@code ServerStack.java:4} and the comparison benchmarks; both
     * replacements extend {@code DynamicChunk}.
     *
     * <p>{@code dependOnClassesThat} covers inheritance as well, so an {@code extends LightingChunk}
     * is caught and not only a call. What the rule cannot see is a third Minestom fallback under a
     * different name: it names exactly these two types.
     */
    @ArchTest
    static final ArchRule publishedModulesDoNotUseTheMinestomCounterpart = noClasses()
            .that().resideInAnyPackage(PUBLISHED)
            .should().dependOnClassesThat(THE_REPLACED_IMPLEMENTATIONS)
            .because("these are the two implementations Falco replaces; they may appear only "
                   + "where something is compared, and that is never a published module");

    /**
     * The three demo classes whose documented job is to name one concrete side of the comparison.
     *
     * <p>The trailing {@code ($.*)?} in the pattern is required rather than decorative:
     * {@code LoaderKind} and {@code ServerStack} are enums whose constants carry bodies, so javac
     * emits them as {@code LoaderKind$1} and friends. Without the suffix the exemption would miss
     * precisely the generated classes that hold the switch itself.
     */
    private static final DescribedPredicate<JavaClass> A_DOCUMENTED_AB_SWITCH =
            nameMatching("net\\.onelitefeather\\.falco\\.demo\\."
                       + "(LoaderKind|ServerStack|LoaderDiagnosis)(\\$.*)?")
                    .as("a documented A/B switch of the demo")
                    .forSubtype();

    /**
     * One concrete side of the A/B comparison, the Falco stack or the Minestom stack, as opposed to
     * the {@code ChunkLoader} and {@code Chunk} interfaces both sides share.
     */
    private static final DescribedPredicate<JavaClass> A_CONCRETE_STACK_COMPONENT =
            nameMatching("net\\.onelitefeather\\.falco\\.(anvil\\.FalcoAnvilLoader"
                       + "|instance\\.FalcoInstance"
                       + "|light\\.(FalcoLightingChunk|ChunkLightService|ChunkLightScheduler))"
                       + "|net\\.minestom\\.server\\.instance\\.(anvil\\.AnvilLoader|LightingChunk)")
                    .as("one side of the comparison rather than the interface both sides share")
                    .forSubtype();

    /**
     * M4: the measuring path of the demo knows only the interface both sides share.
     *
     * <p>{@code falco-demo} has exactly one job, to run the same code twice and change one
     * variable. The two enums say so themselves: {@code LoaderKind.java:12-18} ("everything after
     * that point ... is the same code") and {@code ServerStack.java:17-26} ("is the only place the
     * two differ"). If that separation breaks, through an {@code instanceof} in the stopwatch path
     * or a special case for one side, the numbers in the README, in {@code docs/charts} and in the
     * wiki are no longer symmetrically obtained, and nothing in the result would show it.
     *
     * <p>{@code ChunkInventory} does touch {@code falco-anvil}, but only {@code RegionConstants}, a
     * format constant that affects both sides equally; that is deliberately not a violation.
     */
    @ArchTest
    static final ArchRule demoMeasuresThroughTheInterface = noClasses()
            .that().resideInAPackage(DEMO)
            .and(not(A_DOCUMENTED_AB_SWITCH))
            .should().dependOnClassesThat(A_CONCRETE_STACK_COMPONENT)
            .because("LoaderKind and ServerStack are, by their own Javadoc, the only places the "
                   + "two sides are allowed to differ");

    private static final DescribedPredicate<JavaClass> SLF4J = resideInAPackage("org.slf4j..");

    /**
     * Flags every SLF4J type that appears as a raw return or parameter type of a code unit.
     *
     * <p>Raw types only. Generic type arguments, thrown types and field types are outside what this
     * condition inspects, so an SLF4J type hidden inside a {@code List<Logger>} passes it.
     */
    private static final ArchCondition<JavaCodeUnit> EXPOSE_SLF4J =
            new ArchCondition<>("expose an SLF4J type") {
                @Override
                public void check(JavaCodeUnit unit, ConditionEvents events) {
                    Stream.concat(Stream.of(unit.getRawReturnType()), unit.getRawParameterTypes().stream())
                            .filter(SLF4J)
                            .forEach(type -> events.add(SimpleConditionEvent.violated(unit,
                                    unit.getFullName() + " exposes " + type.getName())));
                }
            };

    /**
     * M5: SLF4J appears in no externally visible signature of a published module.
     *
     * <p>{@code slf4j-api} is the only dependency of the three modules that is
     * {@code implementation} rather than {@code compileOnly}. The root build applies
     * {@code java-library}, and {@code maven-publish} maps {@code implementation} to runtime scope,
     * so a consumer gets SLF4J at runtime but not on their compile classpath. A public method
     * carrying {@code org.slf4j.Logger} would fail to compile for them while this build stays
     * green: the mistake is made here and surfaces at the user.
     *
     * <p>The visibility filter is passed as one predicate on purpose. A chained
     * {@code .arePublic().or().areProtected()} would read as "(in a published package and public)
     * or protected" and would pull in protected members of arbitrary packages.
     */
    @ArchTest
    static final ArchRule slf4jStaysOutOfThePublicApi = noCodeUnits()
            .that().areDeclaredInClassesThat().resideInAnyPackage(PUBLISHED)
            .and(modifier(JavaModifier.PUBLIC).or(modifier(JavaModifier.PROTECTED)))
            .should(EXPOSE_SLF4J)
            .because("slf4j-api is implementation, so it reaches the POM with runtime scope only "
                   + "and is missing from the consumer's compile classpath");
}
