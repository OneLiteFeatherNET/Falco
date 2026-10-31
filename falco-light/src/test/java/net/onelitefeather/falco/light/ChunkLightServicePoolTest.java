package net.onelitefeather.falco.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins down how a {@link ChunkLightService} hands out its propagators.
 * <p>
 * The pool is what keeps a call from allocating the buffers of a whole column, and it is also the
 * one piece of state a call shares with the next. Reusing a propagator is the point; handing the same
 * one to two calls at once would let them overwrite each other's buffers.
 * </p>
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 3.0.2
 */
class ChunkLightServicePoolTest {

    private static final BlockLightSource SOURCE = new BlockLightSource() {

        @Override
        public int emission(int stateId) {
            return 0;
        }

        @Override
        public boolean blocksFace(int stateId, BlockFace face) {
            return false;
        }
    };

    @Test
    void testAPropagatorIsReusedByTheNextCall() {
        ChunkLightService service = new ChunkLightService(SOURCE);

        ChunkLightPropagator first = service.withPropagator(propagator -> propagator);
        ChunkLightPropagator second = service.withPropagator(propagator -> propagator);

        assertSame(first, second, "a call after another takes the propagator the first one returned");
    }

    @Test
    void testTwoCallsAtOnceHoldDifferentPropagators() {
        ChunkLightService service = new ChunkLightService(SOURCE);

        service.withPropagator(outer -> {
            ChunkLightPropagator inner = service.withPropagator(propagator -> propagator);
            assertNotSame(outer, inner, "a call while another holds a propagator gets one of its own");
            return null;
        });
    }

    @Test
    void testAPropagatorIsReturnedWhenTheWorkFails() {
        ChunkLightService service = new ChunkLightService(SOURCE);
        ChunkLightPropagator[] held = new ChunkLightPropagator[1];

        assertThrows(IllegalStateException.class, () -> service.withPropagator(propagator -> {
            held[0] = propagator;
            throw new IllegalStateException("the work failed");
        }));

        assertSame(held[0], service.withPropagator(propagator -> propagator),
                "a failed call still hands its propagator back");
    }
}
