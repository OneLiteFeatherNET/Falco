package net.onelitefeather.falco.architecture;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * Guards the one sentence of Falco's documentation that is a statement about somebody else's code:
 * a shared world pays the container's instance monitor on every block write, and that is a wall
 * rather than an omission.
 *
 * <p>The sentence is repeated in four places — the class Javadoc of
 * {@code FalcoSharedInstance}, {@code net.onelitefeather.falco.instance.package-info}, the
 * {@code Shared worlds} section of the README and the stage 4 result — and every one of them rests
 * on a {@code private} modifier and an {@code ACC_SYNCHRONIZED} flag inside Minestom.
 * {@code FalcoSharedInstanceWriteTest} cannot reach any of that: it observes blocks and chunks, so
 * all three of its cases stay green if Minestom drops the {@code synchronized}, opens
 * {@code UNSAFE_setBlock} up, or grows a fifth caller. The documentation would then be false and
 * nothing would say so, which is precisely the failure mode this module exists for.
 *
 * <p>These rules read the bytecode of {@code net.minestom.server.instance} instead of Falco's own,
 * which is why they sit in a class of their own with its own {@code @AnalyzeClasses} scope rather
 * than in {@link ConcurrencyTest}: widening the shared scope would put a few thousand foreign
 * classes in front of every rule that only ever wanted to look at Falco. Three of the four rules
 * below therefore fail on a Minestom upgrade rather than on a Falco commit — that is the point. The
 * message they carry is not "Minestom is wrong" but "the paragraph in {@code FalcoSharedInstance}
 * has to be rewritten, and the decision not to override {@code setBlock} has to be taken again".
 *
 * <p>Verified against Minestom {@code 2026.06.20-26.1.2} with {@code javap} before the rules were
 * written: {@code UNSAFE_setBlock} is {@code private synchronized} at {@code InstanceContainer:149},
 * and the four call sites are in {@code setBlock} ({@code :135}), {@code placeBlock} ({@code :223}),
 * {@code breakBlock} ({@code :250}) and {@code executeNeighboursBlockPlacementRule} ({@code :756}).
 * The rules assert exactly that, so all four were green on their first run and each had to be proved
 * to bite on its own: W1 by pointing {@link #UNSAFE_SET_BLOCK} at {@code setBlock}, which is neither
 * private nor synchronised; W2 by swapping {@code breakBlock} for {@code loadChunk} in
 * {@link #KNOWN_WRITE_ENTRY_POINTS}, which reported one missing and one unexpected caller; W3 by
 * demanding the forward go to {@code Chunk}; W4 by giving {@code FalcoSharedInstance} an override of
 * {@code setBlock} that does nothing but call {@code super}. That last mutation left all three cases
 * of {@code FalcoSharedInstanceWriteTest} green, which is the gap this class was written to close.
 *
 * <p>One structural limit, the same one {@link ConcurrencyTest} names: ArchUnit models members and
 * accesses, never {@code monitorenter}. W1 sees the {@code ACC_SYNCHRONIZED} flag on the method. If
 * Minestom ever moved the same lock into a {@code synchronized (this)} block inside the body, W1
 * would go red although nothing had changed for a caller — which is a false alarm on the safe side,
 * because the paragraph would still have to be re-read.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@AnalyzeClasses(
        packages = {"net.minestom.server.instance", "net.onelitefeather.falco.instance"},
        importOptions = ImportOption.DoNotIncludeTests.class)
class ForeignWritePathTest {

    static final String INSTANCE_CONTAINER = "net.minestom.server.instance.InstanceContainer";
    static final String SHARED_INSTANCE = "net.minestom.server.instance.SharedInstance";
    static final String FALCO_SHARED_INSTANCE =
            "net.onelitefeather.falco.instance.FalcoSharedInstance";

    /** The private, synchronised method every block write of a container ends in. */
    static final String UNSAFE_SET_BLOCK = "UNSAFE_setBlock";

    /**
     * The three write entry points a subclass could take over, and the neighbour update it could
     * not. {@code executeNeighboursBlockPlacementRule} is {@code private} itself and runs a block
     * placement rule on the four horizontal neighbours of a placed block; it is the reason an
     * override of {@code setBlock} cannot be complete even if the other two were overridden as well.
     */
    static final Set<String> KNOWN_WRITE_ENTRY_POINTS = Set.of(
            "setBlock", "placeBlock", "breakBlock", "executeNeighboursBlockPlacementRule");

    /** The three write entry points {@code SharedInstance} declares and forwards. */
    static final Set<String> FORWARDED_WRITE_ENTRY_POINTS =
            Set.of("setBlock", "placeBlock", "breakBlock");

    /**
     * Asserts the two modifiers the whole argument hangs from. {@code private} is why an override of
     * {@code setBlock} in a subclass leaves the other three callers on a path it cannot see;
     * {@code synchronized} is why a shared world pays the monitor of the entire instance per block.
     * Losing either one alone would already make the documented reasoning wrong, so both are
     * reported separately.
     */
    static final ArchCondition<JavaClass> KEEP_UNSAFE_SET_BLOCK_PRIVATE_AND_SYNCHRONIZED =
            new ArchCondition<>("declare " + UNSAFE_SET_BLOCK + " as private synchronized") {
                @Override
                public void check(JavaClass owner, ConditionEvents events) {
                    Set<JavaMethod> declared = declaredMethodsNamed(owner, UNSAFE_SET_BLOCK);
                    if (declared.isEmpty()) {
                        events.add(SimpleConditionEvent.violated(owner, owner.getName()
                                + " no longer declares " + UNSAFE_SET_BLOCK + "; the write path of a "
                                + "container has moved and FalcoSharedInstance documents the old one"));
                        return;
                    }
                    for (JavaMethod method : declared) {
                        Set<JavaModifier> modifiers = method.getModifiers();
                        if (!modifiers.contains(JavaModifier.PRIVATE)) {
                            events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                    + " is no longer private, so a subclass can reach the write path "
                                    + "directly; FalcoSharedInstance documents that it cannot"));
                        }
                        if (!modifiers.contains(JavaModifier.SYNCHRONIZED)) {
                            events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                    + " no longer carries ACC_SYNCHRONIZED, so a shared world no longer "
                                    + "pays the instance monitor per block; FalcoSharedInstance says it "
                                    + "does"));
                        }
                    }
                }
            };

    /**
     * Asserts the caller set, which is the half of the argument the modifiers do not carry. Two
     * findings are reported, and they mean opposite things. An <em>unexpected</em> caller means the
     * count in the Javadoc is wrong and an override of {@code setBlock} would leave even more of the
     * write path behind. A <em>missing</em> caller means a route has been renamed or removed, and
     * the four names quoted in four documents no longer exist.
     *
     * <p>Read from {@code getAccessesToSelf()} rather than from the call sites, so a method
     * reference — {@code this::UNSAFE_setBlock} handed to something else — counts as a caller too.
     */
    static final ArchCondition<JavaClass> REACH_UNSAFE_SET_BLOCK_FROM_THE_FOUR_KNOWN_PLACES =
            new ArchCondition<>("reach " + UNSAFE_SET_BLOCK + " from exactly "
                    + new TreeSet<>(KNOWN_WRITE_ENTRY_POINTS)) {
                @Override
                public void check(JavaClass owner, ConditionEvents events) {
                    Set<JavaMethod> declared = declaredMethodsNamed(owner, UNSAFE_SET_BLOCK);
                    if (declared.isEmpty()) {
                        events.add(SimpleConditionEvent.violated(owner, owner.getName()
                                + " declares no " + UNSAFE_SET_BLOCK + " whose callers could be counted"));
                        return;
                    }
                    Set<String> expected = new TreeSet<>();
                    for (String name : KNOWN_WRITE_ENTRY_POINTS) {
                        expected.add(owner.getSimpleName() + "." + name);
                    }
                    Set<String> callers = new TreeSet<>();
                    for (JavaMethod method : declared) {
                        for (JavaCodeUnitAccess<?> access : method.getAccessesToSelf()) {
                            JavaCodeUnit origin = access.getOrigin();
                            callers.add(origin.getOwner().getSimpleName() + "." + origin.getName());
                        }
                    }
                    Set<String> unexpected = new TreeSet<>(callers);
                    unexpected.removeAll(expected);
                    Set<String> missing = new TreeSet<>(expected);
                    missing.removeAll(callers);

                    if (!unexpected.isEmpty()) {
                        events.add(SimpleConditionEvent.violated(owner, UNSAFE_SET_BLOCK
                                + " is reached from " + unexpected + " as well; the write path of a "
                                + "container has more entry points than FalcoSharedInstance documents"));
                    }
                    if (!missing.isEmpty()) {
                        events.add(SimpleConditionEvent.violated(owner, UNSAFE_SET_BLOCK
                                + " is no longer reached from " + missing + "; the routes named in the "
                                + "Javadoc of FalcoSharedInstance no longer exist under those names"));
                    }
                }
            };

    /**
     * Asserts that a view hands its block writes to the container instead of doing them itself. This
     * is the link between the two halves: {@code FalcoSharedInstance} inherits these three methods
     * unchanged (W4), they forward to the container, and the container's own path is private and
     * synchronised (W1, W2). Only with this rule in place does the chain from a caller of
     * {@code view.setBlock(...)} to the instance monitor hold end to end.
     */
    static final ArchCondition<JavaClass> FORWARD_EVERY_BLOCK_WRITE_TO_THE_CONTAINER =
            new ArchCondition<>("forward " + new TreeSet<>(FORWARDED_WRITE_ENTRY_POINTS)
                    + " to the instance container") {
                @Override
                public void check(JavaClass owner, ConditionEvents events) {
                    for (String name : new TreeSet<>(FORWARDED_WRITE_ENTRY_POINTS)) {
                        Set<JavaMethod> declared = declaredMethodsNamed(owner, name);
                        if (declared.isEmpty()) {
                            events.add(SimpleConditionEvent.violated(owner, owner.getName()
                                    + " no longer declares " + name + ", so it no longer decides where "
                                    + "the write of a view goes"));
                            continue;
                        }
                        for (JavaMethod method : declared) {
                            boolean forwards = method.getMethodCallsFromSelf().stream()
                                    .anyMatch(call -> isForwardTo(call, name));
                            if (!forwards) {
                                events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                        + " no longer calls " + INSTANCE_CONTAINER + "." + name
                                        + "; a view may have gained a write path of its own, which is "
                                        + "not what FalcoSharedInstance documents"));
                            }
                        }
                    }
                }
            };

    /**
     * W1 — the two modifiers. Selected by fully qualified name, so the rule also fails if
     * {@code InstanceContainer} is renamed or moved: {@code archRule.failOnEmptyShould=true} in
     * {@code archunit.properties} turns an empty selection into a failure rather than a pass.
     */
    @ArchTest
    static final ArchRule theContainersWritePathStaysPrivateAndSynchronized = classes()
            .that().haveFullyQualifiedName(INSTANCE_CONTAINER)
            .should(KEEP_UNSAFE_SET_BLOCK_PRIVATE_AND_SYNCHRONIZED)
            .because("FalcoSharedInstance documents that a shared world pays the container's instance "
                   + "monitor per block and that a subclass cannot take the write path over, and both "
                   + "halves of that sentence are these two modifiers");

    /**
     * W2 — the caller set. This is the rule the review asked for: the Javadoc says "four places" and
     * names them, and until now nothing checked the number or the names.
     */
    @ArchTest
    static final ArchRule theContainersWritePathHasExactlyFourEntryPoints = classes()
            .that().haveFullyQualifiedName(INSTANCE_CONTAINER)
            .should(REACH_UNSAFE_SET_BLOCK_FROM_THE_FOUR_KNOWN_PLACES)
            .because("overriding setBlock would take over one of four routes and leave three on the "
                   + "private path; the count is the whole reason the override was not written");

    /**
     * W3 — the forwarding. Keeps the chain from a view's caller to the container's monitor complete,
     * so that a Minestom release giving {@code SharedInstance} a write path of its own is a failure
     * here rather than a quiet change of meaning in Falco's documentation.
     */
    @ArchTest
    static final ArchRule aViewHandsItsWritesToTheContainer = classes()
            .that().haveFullyQualifiedName(SHARED_INSTANCE)
            .should(FORWARD_EVERY_BLOCK_WRITE_TO_THE_CONTAINER)
            .because("FalcoSharedInstance inherits these three unchanged, so this is where a view's "
                   + "write becomes the container's write");

    /**
     * W4 — the Falco half, and the only one of the four that a Falco commit can break. The decision
     * documented in {@code FalcoSharedInstance} is a decision <em>not</em> to write code; nothing but
     * this rule notices when somebody writes it anyway, and an override would pass every existing
     * test in {@code FalcoSharedInstanceWriteTest} as long as it happened to keep the observable
     * behaviour.
     */
    @ArchTest
    static final ArchRule falcoOverridesNoBlockWrite = noMethods()
            .that().areDeclaredInClassesThat().haveFullyQualifiedName(FALCO_SHARED_INSTANCE)
            .should().haveNameMatching("setBlock|placeBlock|breakBlock")
            .because("an override would take over one route and leave the container's three private "
                   + "ones untouched: two write paths over one chunk, only one of them under the "
                   + "instance monitor");

    private static Set<JavaMethod> declaredMethodsNamed(JavaClass owner, String name) {
        Set<JavaMethod> matching = new LinkedHashSet<>();
        for (JavaMethod method : owner.getMethods()) {
            if (method.getName().equals(name)) {
                matching.add(method);
            }
        }
        return matching;
    }

    private static boolean isForwardTo(JavaMethodCall call, String name) {
        return call.getName().equals(name)
                && call.getTargetOwner().getName().equals(INSTANCE_CONTAINER);
    }
}
