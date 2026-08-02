package net.onelitefeather.falco.instance;

import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.Generator;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers the three pieces of configuration which Minestom's shared instance writes through to the
 * container it borrows from.
 * <p>
 * Every case here uses <em>two</em> shared instances over one container and inspects the one which
 * was not touched. A case that only looked at the instance it had just configured would be green
 * with the defect in place, because the defect is not that the value is lost — it is that the value
 * lands somewhere else as well.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The configuration of a Falco shared instance")
class FalcoSharedInstanceStateTest {

    private static FalcoSharedInstance registered(Env env, InstanceContainer container) {
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        return shared;
    }

    @Test
    @DisplayName("starts with the generator its container had")
    void testTheGeneratorIsSeededFromTheContainer(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final Generator generator = unit -> unit.modifier().fill(Block.STONE);
        container.setGenerator(generator);

        final FalcoSharedInstance shared = registered(env, container);

        assertSame(generator, shared.generator());
    }

    @Test
    @DisplayName("keeps a generator to itself: neither the sibling nor the container sees it")
    void testTheGeneratorDoesNotAlias(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance first = registered(env, container);
        final FalcoSharedInstance second = registered(env, container);
        final Generator generator = unit -> unit.modifier().fill(Block.STONE);

        first.setGenerator(generator);

        assertSame(generator, first.generator());
        assertNull(second.generator(), "a sibling view must not be reconfigured by this call");
        assertNull(container.generator(), "the container must not be reconfigured by this call");
    }

    @Test
    @DisplayName("does not lose the container's generator when it clears its own")
    void testClearingTheGeneratorDoesNotClearTheContainer(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final Generator generator = unit -> unit.modifier().fill(Block.STONE);
        container.setGenerator(generator);
        final FalcoSharedInstance shared = registered(env, container);

        shared.setGenerator(null);

        assertNull(shared.generator());
        assertSame(generator, container.generator(),
                "clearing a view must not empty the world it looks at");
    }
}
