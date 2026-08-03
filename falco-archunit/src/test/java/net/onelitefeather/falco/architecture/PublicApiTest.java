package net.onelitefeather.falco.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.jetbrains.annotations.ApiStatus;

import java.util.Optional;
import java.util.stream.Stream;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasModifiers.Predicates.modifier;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;

/**
 * Guards the promise that the published API surface of Falco is experimental but honest.
 * <p>
 * The build has no japicmp, no revapi and no Error Prone, so nothing but these rules notices when a
 * type slips into {@code repo.onelitefeather.dev} without its stability marker, when a signature
 * carries a type the consumer cannot name, or when a mutable table becomes reachable from outside.
 * Whatever is published once cannot be taken back binary-compatibly without a diff tool.
 * </p>
 * <p>
 * This module sees only the main classes of the other modules, which is exactly the position of a
 * consumer of the jar. That is what lets {@link #publicSignaturesStayReachable} find a mistake no
 * test inside the modules can find: those tests live in the same package and can see the
 * package-private types.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@AnalyzeClasses(
        packages = "net.onelitefeather.falco",
        importOptions = ImportOption.DoNotIncludeTests.class)
class PublicApiTest {

    static final String ANVIL = "net.onelitefeather.falco.anvil..";
    static final String LIGHT = "net.onelitefeather.falco.light..";
    static final String INSTANCE = "net.onelitefeather.falco.instance..";
    static final String DEMO = "net.onelitefeather.falco.demo..";
    static final String BENCH = "net.onelitefeather.falco.benchmark..";
    static final String[] PUBLISHED = {ANVIL, LIGHT, INSTANCE};

    /**
     * A {@code package-info} type is a synthetic carrier for package annotations, never part of the
     * API, and is therefore exempt from every rule about public types below.
     */
    private static final DescribedPredicate<JavaClass> PACKAGE_INFO =
            describe("package-info", clazz -> clazz.getSimpleName().equals("package-info"));

    /**
     * Every public type of a published module must carry {@code @ApiStatus.Experimental}.
     * <p>
     * All three {@code package-info.java} files say verbatim "Every public type here is experimental
     * and may still change in a minor release" ({@code falco-anvil/.../anvil/package-info.java:19}
     * and its two counterparts). Nothing else in the build carries that promise, so the annotation
     * <em>is</em> the stability contract: a public type without it quietly tells the consumer
     * "stable" while the next minor release is free to change its signature.
     * </p>
     * <p>
     * The rule was red when it was first written: of 31 public types, 30 carried the marker and
     * {@code RegionFile.RawChunk} did not — a nested record reachable through {@code readRaw}, and
     * exactly the accidental omission the rule is for. It got the annotation rather than an
     * exemption.
     * </p>
     */
    @ArchTest
    static final ArchRule publicApiIsMarkedExperimental = classes()
            .that().resideInAnyPackage(PUBLISHED)
            .and().arePublic()
            .and().doNotHaveModifier(JavaModifier.SYNTHETIC)
            .and(not(PACKAGE_INFO))
            .should().beAnnotatedWith(ApiStatus.Experimental.class)
            .because("every package-info states 'Every public type here is experimental'; the marker is "
                    + "the only carrier of that promise, and the build has no japicmp");

    /**
     * The annotation is matched by its string so the rule does not depend on
     * {@code org.jetbrains:annotations} being resolvable at import time. A package without any
     * {@code package-info} at all fails here as well, which is the case the rule is really after.
     */
    private static final ArchCondition<JavaClass> IN_NOT_NULL_BY_DEFAULT_PACKAGE =
            new ArchCondition<>("reside in a @NotNullByDefault package") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    Optional<? extends HasAnnotations<?>> info = item.getPackage().tryGetPackageInfo();
                    boolean ok = info.isPresent()
                            && item.getPackage().isAnnotatedWith(
                                    "org.jetbrains.annotations.NotNullByDefault");
                    events.add(new SimpleConditionEvent(item, ok,
                            "package " + item.getPackageName() + " has no @NotNullByDefault package-info"));
                }
            };

    /**
     * A published class may only live in a package whose {@code package-info} declares
     * {@code @NotNullByDefault}.
     * <p>
     * The nullability of the entire published API rests on three lines
     * ({@code falco-anvil/.../anvil/package-info.java:24} and its two counterparts): a bare
     * {@code @NotNull} occurs nowhere in the three modules, only the exceptions are marked
     * {@code @Nullable}. There is no subpackage today, and if someone adds one and forgets the
     * {@code package-info}, that package's whole API silently falls back to "unspecified" while the
     * code keeps being written without {@code @NotNull} and therefore keeps looking non-null.
     * </p>
     * <p>
     * Limitation: the rule checks that the default is declared, not that any single signature obeys
     * it. Nothing here verifies that a method actually never returns {@code null}.
     * </p>
     */
    @ArchTest
    static final ArchRule everyPublishedPackageDeclaresNullness = classes()
            .that().resideInAnyPackage(PUBLISHED)
            .and(not(PACKAGE_INFO))
            .should(IN_NOT_NULL_BY_DEFAULT_PACKAGE)
            .because("not one bare @NotNull exists in the three modules; the non-nullability of the "
                    + "whole API rests on the three package-info lines");

    /**
     * Decides whether a consumer of the jar could even name this type: primitives and everything
     * outside {@code net.onelitefeather.falco} always, own types only when they are {@code public}.
     * Arrays are judged by their component type, because {@code SectorAllocator[]} is as unusable as
     * {@code SectorAllocator}.
     */
    private static boolean visibleToConsumers(JavaClass type) {
        JavaClass base = type.isArray() ? type.getBaseComponentType() : type;
        return base.isPrimitive()
                || !base.getPackageName().startsWith("net.onelitefeather.falco")
                || base.getModifiers().contains(JavaModifier.PUBLIC);
    }

    private static final ArchCondition<JavaCodeUnit> ONLY_VISIBLE_TYPES_IN_SIGNATURE =
            new ArchCondition<>("carry only externally visible types in the signature") {
                @Override
                public void check(JavaCodeUnit unit, ConditionEvents events) {
                    Stream.concat(Stream.of(unit.getRawReturnType()), unit.getRawParameterTypes().stream())
                            .filter(type -> !visibleToConsumers(type))
                            .forEach(type -> events.add(SimpleConditionEvent.violated(unit,
                                    unit.getFullName() + " carries the non-public type " + type.getName())));
                }
            };

    /**
     * No visible method or constructor may carry an internal type in its signature.
     * <p>
     * {@code SectorAllocator} ({@code falco-anvil/.../anvil/SectorAllocator.java:21}) is
     * package-private on purpose, and twelve private nested types come on top. javac happily accepts
     * a public method returning one of them: it compiles, it passes every test of this project, and
     * it is unusable for a consumer, who cannot name the type. No test inside the modules can find
     * that mistake because those tests share the package; this module stands where the consumer
     * stands.
     * </p>
     * <p>
     * Limitation: ArchUnit compares raw types, so a generic argument such as {@code List<Kept>} is
     * invisible to this rule. Field types are not covered here either; they are covered by
     * {@link #visibleFieldsAreConstants}.
     * </p>
     */
    @ArchTest
    static final ArchRule publicSignaturesStayReachable = codeUnits()
            .that().areDeclaredInClassesThat().resideInAnyPackage(PUBLISHED)
            .and().areDeclaredInClassesThat().arePublic()
            .and(modifier(JavaModifier.PUBLIC).or(modifier(JavaModifier.PROTECTED)))
            .and(not(modifier(JavaModifier.SYNTHETIC)))
            .should(ONLY_VISIBLE_TYPES_IN_SIGNATURE)
            .because("SectorAllocator and the private nested types are internals; in a signature the "
                    + "consumer could not name them, and the tests in the same package cannot notice");

    private static final DescribedPredicate<JavaClass> IMMUTABLE_CONSTANT_TYPE =
            describe("primitive or String",
                    type -> type.isPrimitive() || type.isEquivalentTo(String.class));

    /**
     * A field visible from outside may only be a {@code static final} primitive or {@code String}.
     * <p>
     * The project is full of static tables held as arrays — {@code FACE_OFFSETS}
     * ({@code falco-light/.../light/ChunkArea.java:54}) and {@code SERVER_FACES}
     * ({@code falco-light/.../light/MinestomBlockLightSource.java:32}) steer light propagation, and
     * both are private today. Making one of them visible by accident hands every consumer an array
     * that is declared {@code final} and yet fully writable; a foreign write would corrupt light
     * propagation globally and silently, and the {@code final} prevents none of it.
     * </p>
     * <p>
     * The {@code ENUM} exemption is not cosmetic: {@code ChunkCompression.GZIP/ZLIB/NONE} and the six
     * {@code BlockFace} constants are {@code public static final} fields of a non-primitive type in
     * the bytecode, and without the exemption the rule would fail in nine places with nothing broken.
     * </p>
     */
    @ArchTest
    static final ArchRule visibleFieldsAreConstants = fields()
            .that().areDeclaredInClassesThat().resideInAnyPackage(PUBLISHED)
            .and(modifier(JavaModifier.PUBLIC).or(modifier(JavaModifier.PROTECTED)))
            .and(not(modifier(JavaModifier.ENUM)))
            .and(not(modifier(JavaModifier.SYNTHETIC)))
            .should().beStatic()
            .andShould().beFinal()
            .andShould().haveRawType(IMMUTABLE_CONSTANT_TYPE)
            .because("a visible array would be writable from outside despite being final, and the "
                    + "neighbouring tables FACE_OFFSETS and SERVER_FACES steer light propagation");

    private static final DescribedPredicate<JavaClass> EXTENDS_MINESTOM =
            describe("extends a Minestom type", clazz -> clazz.getRawSuperclass()
                    .map(superclass -> superclass.getPackageName().startsWith("net.minestom"))
                    .orElse(false));

    /**
     * A public class is final unless Minestom or the error hierarchy force it open.
     * <p>
     * Four of the public types are non-final and every one of them is forced: {@code FalcoChunk}
     * ({@code falco-instance/.../instance/FalcoChunk.java}) extends {@code Chunk} for its protected
     * lifecycle hooks, {@code FalcoInstance}
     * ({@code falco-instance/.../instance/FalcoInstance.java}) extends {@code Instance} because
     * Minestom branches on {@code instanceof InstanceContainer}, and the two exception types must
     * stay extensible so a consumer can refine the error hierarchy.
     * </p>
     * <p>
     * {@code FalcoLightingChunk} used to be the fifth and stopped being forced when it became a
     * {@code FalcoChunk} rather than a {@code DynamicChunk}: its superclass is no longer a Minestom
     * type, the exemption below no longer covers it, and it is {@code final} today. That is the shape
     * this rule is meant to produce — what a subclass of it would have wanted is a lifecycle
     * listener, which is an interface.
     * </p>
     * <p>
     * The real extension points of this project are interfaces — the benchmarks show it, where
     * {@code FakeBlockLightSource} implements {@code BlockLightSource} instead of subclassing
     * {@code ChunkLightService}. An accidentally non-final class in an experimental API without
     * japicmp is a silent extension point a consumer binds to, and no build reports breaking it.
     * </p>
     */
    @ArchTest
    static final ArchRule publicClassesAreFinal = classes()
            .that().resideInAnyPackage(PUBLISHED)
            .and().arePublic()
            .and().areNotInterfaces()
            .and().areNotEnums()
            .and().areNotRecords()
            .and().doNotHaveModifier(JavaModifier.SYNTHETIC)
            .and(not(PACKAGE_INFO))
            .and(not(assignableTo(Throwable.class)))
            .and(not(EXTENDS_MINESTOM))
            .should().haveModifier(JavaModifier.FINAL)
            .because("the extension points of this project are interfaces; what stays extensible is "
                    + "what Minestom's protected hooks or an error hierarchy force");

    private static final DescribedPredicate<JavaClass> ONLY_STATIC_MEMBERS =
            describe("declares only static members", clazz ->
                    clazz.getFields().stream()
                            .allMatch(field -> field.getModifiers().contains(JavaModifier.STATIC))
                            && clazz.getMethods().stream()
                            .allMatch(method -> method.getModifiers().contains(JavaModifier.STATIC))
                            && !clazz.getMethods().isEmpty());

    private static final ArchCondition<JavaClass> ONE_PRIVATE_NO_ARG_CTOR =
            new ArchCondition<>("have exactly one private no-arg constructor") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    boolean ok = item.getConstructors().size() == 1
                            && item.getConstructors().stream().allMatch(constructor ->
                                    constructor.getModifiers().contains(JavaModifier.PRIVATE)
                                            && constructor.getRawParameterTypes().isEmpty());
                    events.add(new SimpleConditionEvent(item, ok, item.getName()
                            + " declares only static members and must therefore have exactly one"
                            + " private no-arg constructor"));
                }
            };

    /**
     * A public class with only static members must hide its constructor.
     * <p>
     * Four classes are of that kind today — {@code RegionConstants}
     * ({@code falco-anvil/.../anvil/RegionConstants.java:25}), {@code BitPacker}
     * ({@code .../BitPacker.java:25}), {@code NbtReads} ({@code .../NbtReads.java:41}) and
     * {@code SectionCodec} ({@code .../SectionCodec.java:36}) — and all four already follow the
     * pattern. The point is not style but the default constructor: these four are the only places
     * where javac would generate one if it were forgotten, and a {@code public RegionConstants()}
     * would ship in the release jar unnoticed and could not be removed binary-compatibly afterwards.
     * </p>
     * <p>
     * Limitation: the rule checks the shape of the constructor, not that the class is genuinely
     * stateless — a static-only class is recognised by its members, not by intent.
     * </p>
     */
    @ArchTest
    static final ArchRule utilityClassesHideTheirConstructor = classes()
            .that().resideInAnyPackage(PUBLISHED)
            .and().arePublic()
            .and().areNotInterfaces()
            .and().areNotEnums()
            .and().areNotRecords()
            .and().doNotHaveModifier(JavaModifier.SYNTHETIC)
            .and(not(PACKAGE_INFO))
            .and(ONLY_STATIC_MEMBERS)
            .should(ONE_PRIVATE_NO_ARG_CTOR)
            .because("a forgotten default constructor becomes part of the published jar at once and "
                    + "cannot be undone without an API diff tool");
}
