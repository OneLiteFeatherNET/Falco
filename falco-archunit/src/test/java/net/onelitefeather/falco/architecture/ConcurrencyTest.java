package net.onelitefeather.falco.architecture;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasModifiers.Predicates.modifier;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Guards the execution model the three published modules were designed against: mutable state is
 * owned by exactly one object, that ownership is protected by a private lock or by a field the JVM
 * publishes safely, and every unit of work runs on a virtual thread whose fan-out is bounded.
 *
 * <p>The wiki page {@code Rationale-Concurrency} lists five races that were found in this code and
 * would all have failed silently; three of the rules below are the scars those races left. Two of
 * them ({@link #sharedStateIsSafelyPublished} and {@link #noPublicMonitor}) were red when they were
 * written and named a defect that was still in the tree. Both were answered by changing the code,
 * not the rule — which is what these rules are for.
 *
 * <p>The class as a whole is structurally blind to one thing worth naming up front: ArchUnit models
 * bytecode members and accesses, not {@code monitorenter}/{@code monitorexit}. Nothing here sees a
 * {@code synchronized} block, only the {@code ACC_SYNCHRONIZED} flag on a method.
 */
@AnalyzeClasses(
        packages = "net.onelitefeather.falco",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ConcurrencyTest {

    static final String ANVIL    = "net.onelitefeather.falco.anvil..";
    static final String LIGHT    = "net.onelitefeather.falco.light..";
    static final String INSTANCE = "net.onelitefeather.falco.instance..";
    static final String DEMO     = "net.onelitefeather.falco.demo..";
    static final String BENCH    = "net.onelitefeather.falco.benchmark..";
    static final String[] PUBLISHED = {ANVIL, LIGHT, INSTANCE};

    /**
     * Marks a class as a shared object: it declares a field whose raw type comes from
     * {@code java.util.concurrent}, or a volatile field. Either way the author has already said
     * that more than one thread reaches this instance.
     *
     * <p>The marker is deliberately narrow. {@code SectorAllocator.totalSectors:24},
     * {@code ChunkLightState.removalQueue:72}, {@code LightNibbles.levels:53} and
     * {@code ChunkLightArea.Changes:670-676} are non-final too, but declare no concurrency
     * primitive and are either call-confined or sit behind somebody else's lock. The rule below is
     * not meant to hit such helper types.
     */
    static final DescribedPredicate<JavaClass> SHARED_OBJECT =
            new DescribedPredicate<>("declare a concurrency primitive or a volatile field") {
                @Override
                public boolean test(JavaClass owner) {
                    return owner.getFields().stream().anyMatch(field ->
                            field.getRawType().getPackageName().startsWith("java.util.concurrent")
                                    || field.getModifiers().contains(JavaModifier.VOLATILE));
                }
            };

    /**
     * Accepts the three forms of mutable state the design allows on a shared object, all three
     * demonstrated in the code: final, volatile, or a plain field whose every write sits in a
     * method carrying {@code ACC_SYNCHRONIZED}. Static fields are skipped here because
     * {@link #noMutableStaticFields} already covers them.
     */
    static final ArchCondition<JavaClass> ONLY_SAFELY_PUBLISHED_FIELDS =
            new ArchCondition<>("have only final, volatile or synchronized-written fields") {
                @Override
                public void check(JavaClass owner, ConditionEvents events) {
                    for (JavaField field : owner.getFields()) {
                        Set<JavaModifier> modifiers = field.getModifiers();
                        if (modifiers.contains(JavaModifier.FINAL)
                                || modifiers.contains(JavaModifier.VOLATILE)
                                || modifiers.contains(JavaModifier.STATIC)) {
                            continue;
                        }
                        boolean covered = field.getAccessesToSelf().stream()
                                .filter(access -> access.getAccessType() == JavaFieldAccess.AccessType.SET)
                                .allMatch(access -> access.getOrigin() instanceof JavaConstructor
                                        || access.getOrigin().getModifiers()
                                                .contains(JavaModifier.SYNCHRONIZED));
                        if (!covered) {
                            events.add(SimpleConditionEvent.violated(field,
                                    field.getFullName() + " is neither final nor volatile and is "
                                            + "written outside the constructor and synchronized methods"));
                        }
                    }
                }
            };

    /**
     * C1 — A bare mutable field on an object that several threads reach is the gap defect 5 of
     * {@code Rationale-Concurrency} came out of: the reader may see a stale value, or worse, a
     * half-constructed object that the writer published without a barrier. The rule was red when it
     * was written, against {@code FalcoInstance.chunkSupplier} and {@code .chunkLoader} — written by
     * public unsynchronized setters, read on the load path. Both are {@code volatile} now, which is
     * the answer the same class had already given for {@code generator} and {@code autoChunkLoad};
     * synchronizing the setters instead would have violated {@link #noPublicMonitor}.
     *
     * <p>Two honest limits. ArchUnit sees only {@code ACC_SYNCHRONIZED} on methods, never a
     * {@code synchronized} block, so a field guarded that way has to become volatile to pass. And
     * the marker looks at declared raw types only: {@code ChunkLightScheduler} qualifies through
     * {@code lastTick}/{@code bound:113-114}, not through {@code dirty:111}, which is declared as a
     * {@code Map}.
     */
    @ArchTest
    static final ArchRule sharedStateIsSafelyPublished =
            classes().that(SHARED_OBJECT).and().resideInAnyPackage(PUBLISHED)
                    .should(ONLY_SAFELY_PUBLISHED_FIELDS);

    /**
     * C2 — A synchronized method locks on {@code this}, and {@code this} is an object the caller
     * holds: library and caller then share a monitor neither can see in the other's code. A caller
     * writing {@code synchronized(loader)} for good reasons would block {@code close()} of all
     * things, which by its own Javadoc runs while chunk tasks are still in flight. The design is
     * otherwise consistent — {@code RegionFile} uses a private {@code ReentrantLock}, and the
     * remaining synchronized methods belong to private nested classes whose monitor never escapes.
     * The rule was red when it was written, against {@code FalcoAnvilLoader.close()}, the one place
     * that broke the pattern; it now takes a private {@code ReentrantLock} of the same shape.
     *
     * <p>Limit: this catches the {@code ACC_SYNCHRONIZED} flag only. {@code synchronized (this)} as
     * a block inside a public method — as in {@code BiomePaletteResolver:87} — is substantively the
     * same mistake and structurally invisible here.
     */
    @ArchTest
    static final ArchRule noPublicMonitor = noMethods()
            .that().areDeclaredInClassesThat().resideInAnyPackage(PUBLISHED)
            .and(modifier(JavaModifier.PUBLIC).or(modifier(JavaModifier.PROTECTED)))
            .should().haveModifier(JavaModifier.SYNCHRONIZED)
            .because("synchronized locks on this, and this is held by the caller; the internal locks of "
                   + "this design are private ReentrantLocks or monitors of private nested classes");

    /**
     * C3 — the cheapest safeguard in the set, and green today. Every protection mechanism in these
     * modules is cut to per-object ownership: the seqlock lives in the {@code AtomicIntegerArray}
     * tables of <em>one</em> {@code RegionFile}, the fan-out bound in the semaphore of <em>one</em>
     * loader ({@code FalcoAnvilLoader:115}), the instance binding in the {@code AtomicReference} of
     * <em>one</em> scheduler ({@code ChunkLightScheduler:114}). Several loaders at once is the
     * normal case, not a hypothetical — the loader is built per dimension.
     *
     * <p>A mutable static field sits outside every one of those mechanisms. There is no non-final
     * static field in the bytecode today; even {@code $VALUES} and {@code $assertionsDisabled} are
     * final. The rule pins a state that already holds.
     */
    @ArchTest
    static final ArchRule noMutableStaticFields = fields()
            .that().areStatic().and().areDeclaredInClassesThat().resideInAnyPackage(PUBLISHED)
            .should().beFinal()
            .because("state belongs to an object (RegionFile, loader, scheduler, instance); a static "
                   + "field is shared by every loader and instance in a JVM and covered by no lock of "
                   + "this design");

    /**
     * C4 — Minestom hands the loader one virtual thread per chunk because
     * {@code supportsParallelLoading()} reports true. A platform pool inside the library would put a
     * second, fixed-size parallelism next to that model which nobody can configure, and the work
     * here is file IO and computation — exactly what virtual threads were chosen for.
     *
     * <p>The rule is module-specific on purpose. {@code falco-demo} contains precisely the forbidden
     * calls, {@code Executors.newFixedThreadPool} at {@code LoadMeasurement:127} and {@code new
     * Thread} with a named factory at {@code :198}, and is allowed to, because there the thread
     * count is the experiment. That distinction is what gets lost when demo code is copied into a
     * published module. Restricted to constructor calls and factory names so that
     * {@code Thread.sleep} and {@code Thread.currentThread().interrupt()} are not caught by mistake.
     */
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

    /**
     * Identifies the classes that open their own virtual threads, by call target rather than by
     * name, so a rename does not quietly drop a class out of {@link #virtualThreadsAreBounded}.
     */
    static final DescribedPredicate<JavaClass> STARTS_VIRTUAL_THREADS =
            new DescribedPredicate<>("start virtual threads") {
                @Override
                public boolean test(JavaClass owner) {
                    return owner.getMethodCallsFromSelf().stream().anyMatch(call ->
                            (call.getTargetOwner().isEquivalentTo(Thread.class)
                                    && call.getName().equals("startVirtualThread"))
                                    || (call.getTargetOwner().isEquivalentTo(Executors.class)
                                    && call.getName().equals("newVirtualThreadPerTaskExecutor")));
                }
            };

    /**
     * C5 — this is the one place where Falco deliberately replaces a Minestom default, so the
     * coupling deserves a rule rather than a comment. {@code ChunkLoader.saveChunks} is an interface
     * default that registers one party per chunk on a {@code Phaser} and starts a virtual thread
     * whose {@code catch(Throwable)} branch never calls {@code arriveAndDeregister} — a single
     * throwable blocks the saving thread on {@code arriveAndAwaitAdvance} forever.
     * {@code FalcoAnvilLoader.saveChunks} groups by region and bounds at
     * {@code max(availableProcessors, 2)} permits ({@code :160}), and
     * {@code ChunkLightScheduler.defaultExecutor():168-171} builds the same shape, down to taking
     * the permit inside the task so the ticking chunk does not block.
     *
     * <p>Limit: the rule checks that the bound exists, not that it is correct. {@code falco-instance}
     * is out of scope because its {@code startVirtualThread} sites mirror Minestom's own "one virtual
     * thread per chunk operation" model, and the bound on saving lives in the loader. The selected
     * set is exactly two classes today; ArchUnit reports it itself if that set ever runs empty.
     */
    @ArchTest
    static final ArchRule virtualThreadsAreBounded =
            classes().that(STARTS_VIRTUAL_THREADS).and().resideInAnyPackage(ANVIL, LIGHT)
                    .should(callMethodWhere(target(owner(type(Semaphore.class)))
                            .and(target(nameMatching("acquire|acquireUninterruptibly")))))
                    .because("Minestom's saveChunks default starts one thread per chunk on a Phaser that "
                           + "never arrives on a throwable; Falco bounds at max(availableProcessors, 2) "
                           + "permits");

    /**
     * C6, first half — {@code ThreadLocal} is the obvious reflex to defect 1 of
     * {@code Rationale-Concurrency}: {@code ChunkLightService} shared its scratch buffers because a
     * {@code ChunkLightPropagator} sat in a field, and a probe found wrong light in roughly 99
     * percent of concurrent calls. The design chose per-call allocation instead, and under this
     * execution model that is the only sensible choice — with one fresh virtual thread per chunk a
     * {@code ThreadLocal} would practically never see a second hit while pinning a buffer on the
     * order of 100 KB per {@code ChunkLightState}. It would pay the cost of a cache without ever
     * being one, and would look as though the sharing problem were solved. That appearance is the
     * actual damage.
     */
    @ArchTest
    static final ArchRule noThreadLocalAsCallTarget = noClasses()
            .that().resideInAnyPackage(PUBLISHED)
            .should().accessClassesThat().areAssignableTo(ThreadLocal.class)
            .because("the answer to shared scratch buffers here is per-call allocation; with one "
                   + "virtual thread per chunk a ThreadLocal caches nothing but pins a 100 KB buffer "
                   + "per thread");

    /**
     * C6, second half. Closes the gap left by the call-target form: a {@code ThreadLocal} handed in
     * through a constructor and only ever read by somebody else produces no access from the
     * declaring class, so only the field type gives it away.
     */
    @ArchTest
    static final ArchRule noThreadLocalAsField = noFields()
            .that().areDeclaredInClassesThat().resideInAnyPackage(PUBLISHED)
            .should().haveRawType(assignableTo(ThreadLocal.class));

    /**
     * Reports every call that parks the calling thread. Written as an {@link ArchCondition} over
     * code units because the fluent call conditions of ArchUnit exist on classes only, and the
     * exemption of C7 has to name a single method rather than a whole class.
     *
     * <p>The events are added as <em>satisfied</em> on purpose: {@code noCodeUnits()} wraps the
     * condition in {@code never(...)}, which inverts every event, so a satisfied event here becomes
     * the reported violation and its message survives the inversion unchanged.
     */
    static final ArchCondition<JavaCodeUnit> BLOCKING_WAIT =
            new ArchCondition<>("block the calling thread with Thread.sleep, TimeUnit.sleep "
                    + "or LockSupport.park") {
                @Override
                public void check(JavaCodeUnit unit, ConditionEvents events) {
                    for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
                        JavaClass callTarget = call.getTargetOwner();
                        String name = call.getName();
                        boolean blocking =
                                (callTarget.isEquivalentTo(Thread.class) && name.equals("sleep"))
                                        || (callTarget.isEquivalentTo(TimeUnit.class) && name.equals("sleep"))
                                        || (callTarget.isEquivalentTo(LockSupport.class)
                                        && name.matches("park(Nanos|Until)?"));
                        if (blocking) {
                            events.add(SimpleConditionEvent.satisfied(unit,
                                    unit.getFullName() + " calls " + callTarget.getName() + "." + name));
                        }
                    }
                }
            };

    /**
     * C7 — {@code FalcoAnvilLoader.saveChunk} runs on the caller's thread, and only
     * {@code saveChunks} builds virtual threads of its own. An operator calling {@code saveChunk}
     * from a tick task therefore stalls for as long as anything three levels down decides to sleep.
     * There is exactly one such site: {@code RegionFile.retryWhileDenied:582-593}, which on an
     * {@code AccessDeniedException} sleeps up to 100 times for 1 ms — a Windows quirk when renaming
     * external chunk files, and two stalled ticks for a single chunk file.
     *
     * <p>The exemption is by name and lives here in the test code, not in a comment under the call
     * and not as an exemption of the class or the module; every further sleep trips the rule
     * immediately. The full name was read off the bytecode with {@code javap}, because a wrong
     * exemption string makes the rule silently red rather than silently green.
     *
     * <p>Limit: this measures "blocking wait", not "blocks the tick thread". A sleep on a thread the
     * library created itself would be a hit as well.
     */
    @ArchTest
    static final ArchRule noBlockingWait = noCodeUnits()
            .that().areDeclaredInClassesThat().resideInAnyPackage(PUBLISHED)
            .and().doNotHaveFullName("net.onelitefeather.falco.anvil.RegionFile.retryWhileDenied("
                    + "net.onelitefeather.falco.anvil.RegionFile$FileAction)")
            .should(BLOCKING_WAIT)
            .because("saveChunk runs on the caller's thread, often the tick thread; 100 attempts of 1 ms "
                   + "are two stalled ticks there. The one tolerated site is the Windows retry when "
                   + "replacing an .mcc file");
}
