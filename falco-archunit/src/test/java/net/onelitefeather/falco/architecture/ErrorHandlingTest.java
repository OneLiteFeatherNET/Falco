package net.onelitefeather.falco.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import net.onelitefeather.falco.anvil.AnvilChunkException;
import net.onelitefeather.falco.anvil.AnvilFormatException;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * Guards the reporting paths of the three published modules.
 * <p>
 * {@code falco-anvil}, {@code falco-light} and {@code falco-instance} run inside somebody else's
 * server, and that operator has configured exactly two ways of learning that something went wrong:
 * their SLF4J backend and Minestom's {@code ExceptionManager}. A failure that takes any other route
 * &mdash; a console write, a stack trace on stderr, a second logging backend, a generic throwable
 * that dissolves in a broad catch, a {@code System.exit} &mdash; does not exist for that operator.
 * The rules here keep every failure on one of the two paths and keep the paths themselves narrow.
 * </p>
 */
@AnalyzeClasses(
        packages = "net.onelitefeather.falco",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ErrorHandlingTest {

    static final String ANVIL    = "net.onelitefeather.falco.anvil..";
    static final String LIGHT    = "net.onelitefeather.falco.light..";
    static final String INSTANCE = "net.onelitefeather.falco.instance..";
    static final String DEMO     = "net.onelitefeather.falco.demo..";
    static final String BENCH    = "net.onelitefeather.falco.benchmark..";
    static final String[] PUBLISHED = {ANVIL, LIGHT, INSTANCE};

    /**
     * E1 &mdash; the inheritance clause is not taste but forced by a signature: {@code saveChunk} and
     * {@code loadChunk} override Minestom's {@code ChunkLoader} methods and declare no {@code throws},
     * so a checked Falco exception could not be thrown <em>at that boundary</em>. The cause constructor
     * protects the single translation point: {@code FalcoAnvilLoader.failedLoad} wraps the
     * {@code IOException} or {@code RuntimeException} of the whole read chain into an
     * {@code AnvilChunkException}, and without it the message says only that a chunk could not be
     * loaded, not which region file or NBT key was behind it.
     * <p>
     * <b>The rule applies to the unchecked types only, and that narrowing has a history.</b> It was
     * written when the project had exactly one exception, and generalised an observation about
     * {@code AnvilChunkException} to every {@code Throwable}. The format hierarchy is checked on
     * purpose: it never reaches Minestom's signature, because {@link #exactlyOneTranslationPoint}
     * keeps the translation in one place, and being checked is what makes the compiler name every
     * site that has to decide. {@link #checkedFaultsStayInsideTheHierarchy} is what stops that
     * exemption from becoming a hole.
     * </p>
     * <p>
     * A naming clause such as {@code haveSimpleNameEndingWith("Exception")} is deliberately not part
     * of the rule &mdash; violating it harms nobody.
     * </p>
     */
    @ArchTest
    static final ArchRule ownExceptionsAreUncheckedAndCarryACause = classes()
            .that().areAssignableTo(Throwable.class)
            .and().resideInAPackage("net.onelitefeather.falco..")
            .and().areAssignableTo(RuntimeException.class)
            .should().bePublic()
            .andShould(new ArchCondition<JavaClass>("have a public (String, Throwable) constructor") {
                @Override
                public void check(JavaClass clazz, ConditionEvents events) {
                    boolean ok = clazz.getConstructors().stream().anyMatch(constructor ->
                            constructor.getModifiers().contains(JavaModifier.PUBLIC)
                                    && constructor.getRawParameterTypes().size() == 2
                                    && constructor.getRawParameterTypes().get(0)
                                            .isEquivalentTo(String.class)
                                    && constructor.getRawParameterTypes().get(1)
                                            .isEquivalentTo(Throwable.class));
                    if (!ok) {
                        events.add(SimpleConditionEvent.violated(clazz,
                                clazz.getName() + " cannot carry a cause"));
                    }
                }
            })
            .because("saveChunk declares no throws, and failedLoad is the only point where the cause of "
                   + "the read chain survives");

    /**
     * E2 &mdash; the exception types in this project still mean something, and two catch clauses depend
     * on it. {@code FalcoAnvilLoader.loadChunk:322} catches {@code IOException | RuntimeException} and
     * relabels everything inside as an unreadable chunk, so a bare {@code new RuntimeException} in the
     * read chain would be reported to the operator as a data error of their world although it is a
     * programming error. {@code saveChunk} sorts its {@code IllegalStateException} out at {@code :365}
     * ahead of the broad catch at {@code :369}, and that sorting only works while the types carry
     * meaning.
     * <p>
     * Own {@code Throwable} subclasses are excluded from the selection: their {@code super(message,
     * cause)} call is a constructor call on {@code java.lang.RuntimeException} in the bytecode, which
     * is not the direct instantiation this rule is about.
     * </p>
     */
    @ArchTest
    static final ArchRule noGenericThrowables = noClasses()
            .that().resideInAPackage("net.onelitefeather.falco..")
            .and(not(assignableTo(Throwable.class)))
            .should().callConstructorWhere(target(owner(nameMatching(
                    "java\\.lang\\.(RuntimeException|Exception|Throwable|Error)"))))
            .because("loadChunk catches IOException|RuntimeException and labels everything inside a "
                   + "chunk data error; a generic throw disappears into that translation");

    /**
     * E1a &mdash; a checked Falco exception is allowed only inside the anvil fault hierarchy.
     * <p>
     * {@link #ownExceptionsAreUncheckedAndCarryACause} no longer covers checked types, and this is
     * what keeps that from being an open door. Checked is reserved for the format branch, whose whole
     * purpose is that the compiler names every site which has to decide what a broken region file or
     * a broken chunk means. Anything else &mdash; a checked exception somewhere in the light engine,
     * say &mdash; would either be swallowed at a signature that declares no {@code throws}, or force
     * {@code throws} onto an override that cannot have one.
     * </p>
     */
    @ArchTest
    static final ArchRule checkedFaultsStayInsideTheHierarchy = classes()
            .that().areAssignableTo(Throwable.class)
            .and().resideInAPackage("net.onelitefeather.falco..")
            .and().areNotAssignableTo(RuntimeException.class)
            .should().beAssignableTo(AnvilFormatException.class)
            .because("checked is reserved for the format branch, which never reaches a Minestom "
                   + "signature because the loader translates it first");

    /**
     * E1b &mdash; exactly one class turns a checked fault into an unchecked one.
     * <p>
     * This is the invariant the design of the hierarchy rests on, and until now it was prose. If a
     * second class wrapped a format fault into an {@code AnvilChunkException}, the origin would be
     * lost halfway and the double report to the {@code ExceptionManager} that the hierarchy was
     * written against would come back. The translation happens in {@code FalcoAnvilLoader.failedLoad}
     * and its counterpart in {@code saveChunk}, and nowhere else.
     * </p>
     * <p>
     * Constructing the boundary type is the observable part of translating, so that is what the rule
     * pins. It says nothing about who may <em>catch</em> a fault, which is every caller's business.
     * </p>
     * <p>
     * The boundary type itself is exempt, because its own constructors delegate to one another and
     * that is not a translation.
     * </p>
     */
    @ArchTest
    static final ArchRule exactlyOneTranslationPoint = noClasses()
            .that().resideInAPackage("net.onelitefeather.falco..")
            .and().haveSimpleNameNotStartingWith("FalcoAnvilLoader")
            .and().areNotAssignableTo(AnvilChunkException.class)
            .should().callConstructorWhere(target(owner(assignableTo(AnvilChunkException.class))))
            .because("the origin of a failure survives only while one place wraps it");

    /**
     * E3 &mdash; all four reporting sites live in exactly these two classes
     * ({@code FalcoAnvilLoader:342}, {@code :375}, {@code :1289},
     * {@code ChunkLightScheduler.report:474}). A further one would be a second translation point where
     * the same failure reaches the operator twice. The harder reason is testability: {@code NbtReads},
     * {@code PaletteData}, {@code SectionCodec}, {@code BitPacker}, {@code RegionFile},
     * {@code SectorAllocator} and {@code LightNibbles} are format- and compute-pure classes whose unit
     * tests never boot a server, and a {@code getExceptionManager} call there would demand a running
     * {@code MinecraftServer}.
     * <p>
     * The exemption is by fully qualified name so a same-named class in another package is not
     * exempted by accident, and nested types are deliberately not covered &mdash; {@code $RegionHandle}
     * should not be allowed to report. The rule cuts off the {@code ExceptionManager} only, not
     * {@code MinecraftServer} as a whole, because {@code BiomePaletteResolver} legitimately needs the
     * registry.
     * </p>
     */
    @ArchTest
    static final ArchRule exceptionManagerOnlyAtTheBoundary = noClasses()
            .that().resideInAPackage("net.onelitefeather.falco..")
            .and().doNotHaveFullyQualifiedName("net.onelitefeather.falco.anvil.FalcoAnvilLoader")
            .and().doNotHaveFullyQualifiedName("net.onelitefeather.falco.light.ChunkLightScheduler")
            .should().callMethodWhere(target(name("getExceptionManager"))
                    .and(target(owner(name("net.minestom.server.MinecraftServer")))))
            .because("exactly one translation point per module, and the format and compute classes have "
                   + "to stay testable without a booted server");

    /**
     * E4 &mdash; there is exactly one such call, {@code ChunkLoadDemo:124}, justified in the comment
     * right above it: the Minestom registries start threads that keep the JVM alive, and a demo that
     * hangs after its report looks like a defect of the loader. The same call inside a published module
     * would be fatal &mdash; a {@code System.exit} from the chunk loader, say as a convenient reaction
     * to a broken region file, tears down somebody else's server without a save and produces exactly
     * the data loss {@code AnvilChunkException} exists to prevent, by its own Javadoc.
     */
    @ArchTest
    static final ArchRule noJvmShutdownInLibraries = noClasses()
            .that().resideOutsideOfPackage(DEMO)
            .should().callMethod(System.class, "exit", int.class)
            .orShould().callMethod(Runtime.class, "exit", int.class)
            .orShould().callMethod(Runtime.class, "halt", int.class)
            .because("the published modules run inside somebody else's server; an abort from the chunk "
                   + "loader loses every unsaved chunk");

    /**
     * E5 &mdash; all 13 console accesses of the project sit in {@code ChunkLoadDemo} and
     * {@code DemoServer}, the two main classes of the command line program, where the report belongs on
     * stdout by definition. In the libraries a {@code println} can neither be switched off nor carries
     * a level nor is findable in the log the operator owns, and it bypasses both {@code LOGGER} and the
     * {@code ExceptionManager}.
     */
    @ArchTest
    static final ArchRule noConsoleOutsideTheDemo = noClasses()
            .that().resideOutsideOfPackage(DEMO)
            .should().accessField(System.class, "out")
            .orShould().accessField(System.class, "err")
            .because("the published modules end up in somebody else's server, which has only slf4j "
                   + "configured; the demo alone owns the console");

    /**
     * E6 &mdash; for the libraries the reason of E5 applies, sharpened: {@code FalcoAnvilLoader} and
     * {@code ChunkLightScheduler} report exclusively through {@code LOGGER} and the
     * {@code ExceptionManager}, and a stack trace on stderr bypasses both. For the demo the reason is
     * written into the code: {@code DemoServer:97} records that "a stack trace on this console always
     * means a defect", and a {@code printStackTrace} would dissolve exactly that distinction. Hence no
     * exemption for the demo either.
     * <p>
     * The owner predicate is mandatory rather than cosmetic: {@code callMethod(Throwable.class,
     * "printStackTrace")} matches only when the owner in the bytecode is literally
     * {@code java/lang/Throwable}, while {@code IOException e; e.printStackTrace()} compiles to
     * {@code invokevirtual java/io/IOException.printStackTrace} and would slip through.
     * </p>
     */
    @ArchTest
    static final ArchRule noPrintStackTrace = noClasses()
            .should().callMethodWhere(target(name("printStackTrace"))
                    .and(target(owner(assignableTo(Throwable.class)))))
            .because("DemoServer.main documents that a stack trace on this console always means a "
                   + "defect, and the libraries report only through LOGGER and the ExceptionManager");

    /**
     * E7, first half &mdash; all seven logger fields of the project are declared identically today
     * ({@code FalcoAnvilLoader:84}, {@code BlockPaletteResolver:37}, {@code BiomePaletteResolver:41},
     * {@code ChunkLightService:61}, {@code ChunkLightArea:99}, {@code FalcoInstance:122},
     * {@code DemoServer:65}). Two of them sit in classes created per instance and per area, where a
     * non-static logger would be an extra object on the load and light paths. More importantly, a
     * {@code public} or {@code protected} logger field would be API surface from publication onwards
     * that nobody can take back.
     * <p>
     * A naming clause {@code LOGGER} is deliberately not part of the rule: a field called {@code log}
     * harms nobody. The overlap with A4 and C3 is intentional &mdash; those apply to the three
     * published packages, this rule applies project-wide.
     * </p>
     */
    @ArchTest
    static final ArchRule loggerFieldShape = fields()
            .that().haveRawType("org.slf4j.Logger")
            .should().bePrivate().andShould().beStatic().andShould().beFinal()
            .because("an instance logger in ChunkLightArea would be one object per area, and a visible "
                   + "field in a published library would be irrevocable API");

    /**
     * E7, second half &mdash; pins the one backend the embedding server configures. A field of
     * {@code java.util.logging.Logger} or {@code java.lang.System.Logger} would write into a file the
     * operator never set up and never sees. The types are named as strings because SLF4J is only an
     * {@code implementation} dependency in every module.
     */
    @ArchTest
    static final ArchRule onlySlf4jAsLogger = noFields()
            .should().haveRawType("java.util.logging.Logger")
            .orShould().haveRawType("java.lang.System$Logger")
            .because("the embedding server configures exactly one backend, slf4j; a second logging "
                   + "system lands in no file the operator owns");
}
