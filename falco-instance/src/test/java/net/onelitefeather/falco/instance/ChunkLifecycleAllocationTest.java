package net.onelitefeather.falco.instance;

import com.sun.management.ThreadMXBean;
import net.minestom.server.world.DimensionType;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.management.ManagementFactory;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Counts what a lifecycle transition allocates, with no listener and with one, which is US-3.04.
 * <p>
 * The requirement is that a chunk nobody listens to pays nothing per transition. That is a claim
 * about an allocation, and an allocation is measured rather than argued: the two arms below run the
 * identical loop and differ only in whether a listener is installed, and the difference between them
 * is the cost of the event.
 * </p>
 *
 * <h2>Why the listener arm has to publish the event</h2>
 * <p>
 * A test which only measured the null arm would pass against an implementation that allocates an
 * event on every transition, as long as escape analysis noticed that nothing escaped and deleted the
 * allocation. The listener below therefore writes the event into a {@code static volatile} field,
 * which no compiler may remove, so the second arm is a positive control: if it does not allocate, the
 * measurement itself is broken and the first arm proves nothing.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("What a lifecycle transition allocates")
class ChunkLifecycleAllocationTest {

    /**
     * How many transitions each arm performs.
     */
    private static final int TRANSITIONS = 200_000;

    /**
     * How many transitions are run before the measurement, so both arms are compiled.
     */
    private static final int WARMUP = 50_000;

    /**
     * Where the listener arm publishes its events, so that nothing can be optimised away.
     */
    private static volatile Object sink;

    /**
     * Ticks a chunk the given number of times and reports what the calling thread allocated.
     *
     * @param chunk the chunk to tick
     * @param times how often to tick it
     * @return the bytes the calling thread allocated during the loop
     */
    private static long allocatedWhileTicking(FalcoChunk chunk, int times) {
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long before = threads.getCurrentThreadAllocatedBytes();

        for (int index = 0; index < times; index++) {
            chunk.tick(index);
        }
        return threads.getCurrentThreadAllocatedBytes() - before;
    }

    @Test
    @DisplayName("costs nothing without a listener and one event with one")
    void testTheEventIsBuiltOnlyWhenSomebodyListens(Env env) {
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        assumeTrue(threads.isThreadAllocatedMemorySupported(),
                "this JVM cannot report per thread allocation, so the question cannot be answered here");
        threads.setThreadAllocatedMemoryEnabled(true);

        final FalcoInstance instance = new FalcoInstance(UUID.randomUUID(), DimensionType.OVERWORLD);
        env.process().instance().registerInstance(instance);
        final FalcoChunk silent = new FalcoChunk(instance, 0, 0);
        final FalcoChunk heard = new FalcoChunk(instance, 1, 0);
        heard.addLifecycleListener(new ChunkLifecycleListener() {

            @Override
            public void onTick(ChunkLifecycleEvent event) {
                sink = event;
            }
        });

        allocatedWhileTicking(silent, WARMUP);
        allocatedWhileTicking(heard, WARMUP);
        final long withoutListener = allocatedWhileTicking(silent, TRANSITIONS);
        final long withListener = allocatedWhileTicking(heard, TRANSITIONS);

        System.out.printf("lifecycle transitions: %,d without a listener -> %,d B (%.3f B each)%n",
                TRANSITIONS, withoutListener, (double) withoutListener / TRANSITIONS);
        System.out.printf("lifecycle transitions: %,d with one listener  -> %,d B (%.3f B each)%n",
                TRANSITIONS, withListener, (double) withListener / TRANSITIONS);

        assertTrue(withListener >= 16L * TRANSITIONS,
                "the positive control failed: a listener that stores its event has to allocate one per "
                        + "transition, but the arm with a listener allocated " + withListener
                        + " B over " + TRANSITIONS + " transitions, so this measurement cannot see "
                        + "allocations at all and its other half proves nothing");
        assertTrue(withoutListener < TRANSITIONS,
                "a chunk nobody listens to allocated " + withoutListener + " B over " + TRANSITIONS
                        + " transitions, which is more than a byte each: the event is being built before "
                        + "the listener is checked");
    }
}
