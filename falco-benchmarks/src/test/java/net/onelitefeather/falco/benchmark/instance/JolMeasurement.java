package net.onelitefeather.falco.benchmark.instance;

import org.junit.jupiter.api.Assumptions;
import org.openjdk.jol.vm.VM;
import org.openjdk.jol.vm.VirtualMachine;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The {@link JolMeasurement} class establishes, once per JVM, in which of its several modes JOL is
 * actually running, and refuses to let a footprint table be printed before that question is answered.
 * <p>
 * It exists because JOL does not have one way of answering how large an object is, it has two, and it
 * picks between them at runtime without the caller being told. When {@code org.openjdk.jol.vm.VM} is
 * first touched it tries to attach an instrumentation agent to the running JVM; if that works, every
 * size JOL reports afterwards comes from {@code Instrumentation#getObjectSize}, which is the JVM
 * stating the truth about its own heap. If it does not work, JOL prints one line to standard output
 * and then computes every size from a layout model built out of field offsets, alignment and a header
 * size it guessed — and it keeps answering, with numbers that look exactly like measurements. A
 * project whose architecture decisions rest on those numbers cannot afford to find that out by
 * reading log lines.
 * </p>
 *
 * <h2>The bug this class was written for</h2>
 * <p>
 * There is a second, sharper edge on the same knife. A graph walk that reaches a record class inside
 * {@code java.base} dies on JDK 25 with {@code Cannot get the field offset}, because
 * {@code Unsafe#objectFieldOffset} refuses record classes, and JOL only survives it when
 * {@code jol.magicFieldOffset} is set. That option is read exactly once, in the class initialiser of
 * {@code HotspotUnsafe}, which runs the first time any code in the JVM asks JOL for anything.
 * </p>
 * <p>
 * {@code ChunkFootprintTest} used to set the property from its own static initialiser. That works if
 * and only if {@code ChunkFootprintTest} is the first class in the test JVM to touch JOL. It shares
 * that JVM with {@code PaletteFootprintTest} and {@code EmptySectionCensusTest}, which also walk
 * object graphs, and the order in which JUnit runs test classes is not specified: it falls out of
 * classpath scanning, which follows directory order on disk and changes when the class files are
 * rewritten. Whenever one of the other two ran first, JOL had already cached
 * {@code magicFieldOffset == false} and all three measurements of {@code ChunkFootprintTest} failed;
 * whenever it ran first, they passed. Same code, same machine, two different outcomes — a flaky
 * measurement, which is worse than a red one, because the green runs invited people to quote numbers
 * from a mechanism nobody had checked.
 * </p>
 * <p>
 * The fix is that the two JOL options are now JVM arguments of the test task, set by
 * {@code falco-benchmarks/build.gradle.kts} before a single class is loaded, so no ordering exists
 * that can defeat them. This class is the second half of that fix: it reads back what JOL actually
 * decided and makes the tests state it or stop.
 * </p>
 *
 * <h2>Why the mode is read out of JOL rather than assumed</h2>
 * <p>
 * JOL exposes no API for either question. {@code VirtualMachine} has {@code details()}, which
 * describes the compressed reference layout, and nothing that says where a size came from. The two
 * facts live in fields of the package private {@code HotspotUnsafe}: {@code instrumentation}, which is
 * null exactly when sizes are modelled, and {@code MAGIC_FIELD_OFFSET}, which is the value the option
 * had when JOL initialised rather than the value the system property carries now. Reading them
 * reflectively is a coupling to an implementation detail, and it is deliberately the loud kind: if a
 * future JOL renames or removes either field, {@link Mode#UNKNOWN} is reported and every measurement
 * stops with an assumption instead of continuing with an unproven mechanism.
 * </p>
 *
 * <h2>What is checked, and what happens when it fails</h2>
 * <p>
 * Two different kinds of failure deserve two different outcomes. A JVM that JOL could not attach an
 * instrumentation agent to is an environment: another JDK, a hardened container, a future release
 * that has switched dynamic agent loading off. The measurement is impossible there, so
 * {@link #require()} stops with an {@link Assumptions assumption} that names the mode it found. The
 * missing number stays visible as a skipped test rather than turning into a modelled one.
 * </p>
 * <p>
 * A JVM whose JOL saw {@code magicFieldOffset == false} is not an environment, it is this build having
 * lost its own flag, and it is the exact regression that made these tests flaky. That one fails,
 * loudly, and the message names the line of the build file that has to come back.
 * </p>
 *
 * <h2>Running it</h2>
 * <p>
 * Nothing here is measured; it only guards and describes. It runs inside every JOL test of this
 * module:
 * </p>
 * <pre>{@code
 * ./gradlew :falco-benchmarks:test -i
 * ./gradlew :falco-benchmarks:test -Pfalco.compactHeaders -i
 * }</pre>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
final class JolMeasurement {

    /**
     * The JOL option that lets a graph walk read a field of a record class of {@code java.base}.
     */
    private static final String MAGIC_FIELD_OFFSET = "jol.magicFieldOffset";

    /**
     * The name of the field of {@code HotspotUnsafe} that holds the instrumentation agent.
     */
    private static final String INSTRUMENTATION_FIELD = "instrumentation";

    /**
     * The name of the field of {@code HotspotUnsafe} that says whether the Serviceability Agent
     * attached, which decides whether addresses are read or guessed.
     */
    private static final String ACCURATE_FIELD = "isAccurate";

    /**
     * The name of the field of {@code HotspotUnsafe} that holds the value {@link #MAGIC_FIELD_OFFSET}
     * had when JOL initialised.
     */
    private static final String MAGIC_FIELD_OFFSET_FIELD = "MAGIC_FIELD_OFFSET";

    /**
     * Where the byte figures of a JOL table come from.
     */
    enum Mode {

        /**
         * Every size is {@code Instrumentation#getObjectSize} of the running JVM, which is the only
         * mode in which a byte figure of this module is a measurement.
         */
        INSTRUMENTATION("the instrumentation agent"),

        /**
         * JOL could not attach an agent and computes every size from field offsets, the object
         * alignment and a guessed header size. The numbers are a model of the JVM, not a reading of
         * it, and no table of this module may be published from them.
         */
        LAYOUT_MODEL("a layout model, which is a computation and not a measurement"),

        /**
         * JOL no longer exposes which of the two it used, so nothing can be said about the numbers.
         */
        UNKNOWN("a mode this build could not determine");

        /**
         * What the mode means, phrased so it can be printed in a table header.
         */
        private final String description;

        /**
         * Creates a mode.
         *
         * @param description what the mode means in a table header
         */
        Mode(String description) {
            this.description = description;
        }

        /**
         * Returns what the mode means, phrased for a table header.
         *
         * @return the description of the mode
         */
        String description() {
            return this.description;
        }
    }

    /**
     * Blocks the creation of an instance because the class only answers questions about the JVM.
     */
    private JolMeasurement() {
    }

    /**
     * Stops the calling test unless JOL is in a state in which its byte figures are measurements.
     * <p>
     * Call it as the first statement of every test that takes a number out of JOL. A test that only
     * counts objects, or that does not touch JOL at all, must not call it: the object counts of a
     * graph walk are correct in every mode, and skipping a census because no agent attached would be
     * a skip for no reason.
     * </p>
     *
     * @throws org.opentest4j.TestAbortedException if JOL is not sizing through the instrumentation
     *                                             agent, or if the mode cannot be determined
     */
    static void require() {
        final State state = State.CURRENT;

        Assumptions.assumeTrue(state.mode != Mode.UNKNOWN,
                () -> "JOL no longer says which of its modes it is sizing objects in. This build reads that "
                        + "out of the fields " + INSTRUMENTATION_FIELD + " and " + MAGIC_FIELD_OFFSET_FIELD
                        + " of its HotspotUnsafe, and one of them is gone, so the numbers below could come "
                        + "from the instrumentation agent or from a layout model and nothing here can tell "
                        + "them apart. An unlabelled byte figure is not quotable, so none is produced.");
        if (!state.magicFieldOffset) {
            fail("JOL initialised with " + MAGIC_FIELD_OFFSET + "=false, so a graph walk that reaches a "
                    + "record class of java.base dies with 'Cannot get the field offset' and no footprint "
                    + "of this module can be taken. The option is read once, in the class initialiser of "
                    + "HotspotUnsafe, which runs the first time anything in this JVM touches JOL, so it "
                    + "cannot be set from a test: falco-benchmarks/build.gradle.kts has to pass "
                    + "-D" + MAGIC_FIELD_OFFSET + "=true to the test JVM and evidently no longer does.");
        }
        Assumptions.assumeTrue(state.mode == Mode.INSTRUMENTATION,
                () -> "JOL takes its object sizes from " + state.mode.description() + " on this JVM, so "
                        + "every byte figure below would be a computation presented as a measurement. The "
                        + "build passes -Djdk.attach.allowAttachSelf=true and -XX:+EnableDynamicAgentLoading "
                        + "for exactly this reason; a JVM that still refuses the agent cannot answer the "
                        + "question this test asks, and the answer is left missing rather than guessed.");
    }

    /**
     * Returns the mode JOL is sizing objects in.
     *
     * @return the mode of the running JVM
     */
    static Mode mode() {
        return State.CURRENT.mode;
    }

    /**
     * Returns the line every JOL table of this module carries in its header.
     * <p>
     * A byte figure without its measurement mode is not quotable, in the same way a footprint without
     * its object header mode is not, so the two are printed next to each other everywhere.
     * </p>
     *
     * @return the description of how the numbers below it were obtained
     */
    static String describe() {
        final State state = State.CURRENT;
        return String.format(Locale.ROOT, "jol 0.17, sizes from %s, %s=%s, Serviceability Agent %s",
                state.mode.description(), MAGIC_FIELD_OFFSET,
                state.mode == Mode.UNKNOWN ? "unknown" : Boolean.toString(state.magicFieldOffset),
                state.serviceabilityAgent
                        ? "attached"
                        : "not attached, so addresses are guesses and no number here is one");
    }

    /**
     * Holds what JOL decided, read exactly once.
     * <p>
     * A holder class rather than a lazily filled field, because the read has to happen after
     * {@code VM.current()} has initialised JOL and exactly once, which is what class initialisation
     * already guarantees.
     * </p>
     */
    private static final class State {

        /**
         * What the running JVM told about itself.
         */
        static final State CURRENT = read();

        /**
         * Where the byte figures come from.
         */
        private final Mode mode;

        /**
         * Whether the Serviceability Agent attached, which decides whether addresses are read or
         * guessed. Nothing in this module uses an address, so it is reported rather than required.
         */
        private final boolean serviceabilityAgent;

        /**
         * The value {@link JolMeasurement#MAGIC_FIELD_OFFSET} had when JOL initialised, which is the
         * only value that matters and is not necessarily the one the system property carries now.
         */
        private final boolean magicFieldOffset;

        /**
         * Creates a state.
         *
         * @param mode                where the byte figures come from
         * @param serviceabilityAgent whether the Serviceability Agent attached
         * @param magicFieldOffset    the option value JOL initialised with
         */
        private State(Mode mode, boolean serviceabilityAgent, boolean magicFieldOffset) {
            this.mode = mode;
            this.serviceabilityAgent = serviceabilityAgent;
            this.magicFieldOffset = magicFieldOffset;
        }

        /**
         * Initialises JOL and reads back what it decided.
         *
         * @return the state of the running JVM
         */
        private static State read() {
            final VirtualMachine machine = VM.current();
            final Object instrumentation = field(machine.getClass(), INSTRUMENTATION_FIELD, machine);
            final Object accurate = field(machine.getClass(), ACCURATE_FIELD, machine);
            final Object magic = field(machine.getClass(), MAGIC_FIELD_OFFSET_FIELD, null);
            final Mode mode;

            if (instrumentation == Absent.MARKER || magic == Absent.MARKER) {
                mode = Mode.UNKNOWN;
            } else if (instrumentation == null) {
                mode = Mode.LAYOUT_MODEL;
            } else {
                mode = instrumentation instanceof Instrumentation ? Mode.INSTRUMENTATION : Mode.UNKNOWN;
            }
            return new State(mode, Boolean.TRUE.equals(accurate), Boolean.TRUE.equals(magic));
        }

        /**
         * Reads a field of the JOL virtual machine implementation.
         *
         * @param type  the class to look the field up in
         * @param name  the name of the field
         * @param owner the instance to read from, null for a static field
         * @return the value of the field, {@code null} if the field holds null, or
         *         {@link Absent#MARKER} if the field does not exist or cannot be read
         */
        private static Object field(Class<?> type, String name, Object owner) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return Absent.MARKER;
            }
        }
    }

    /**
     * Distinguishes a field that holds null from a field that is not there at all.
     * <p>
     * The difference is the whole point of the reflective read: {@code instrumentation == null} means
     * JOL is modelling sizes, while a missing field means a JOL whose internals moved and about whose
     * numbers this build can no longer say anything. Collapsing the two would turn the second case
     * into a silent claim about the first.
     * </p>
     */
    private static final class Absent {

        /**
         * The value returned for a field that could not be read.
         */
        static final Object MARKER = new Object();

        /**
         * Blocks the creation of an instance because the class only holds the marker.
         */
        private Absent() {
        }
    }
}
