package net.onelitefeather.falco.instance;

import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.SharedInstance;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that a Falco shared instance is registrable and that Minestom still recognises it as sharing
 * the chunks of its container.
 * <p>
 * The second half is the one that matters. {@code areLinked} is consulted in exactly one place,
 * {@code Player#setInstance}, and when it answers false the player receives every chunk in view
 * distance again. Nothing fails, nothing logs, the world simply costs a full resend per instance
 * change. A test is the only thing that notices.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("A Falco shared instance")
class FalcoSharedInstanceTest {

    private static FalcoSharedInstance registered(Env env, InstanceContainer container) {
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        return shared;
    }

    @Test
    @DisplayName("registers through registerSharedInstance and is known to its container")
    void testRegistration(Env env) {
        final InstanceManager manager = env.process().instance();
        final InstanceContainer container = manager.createInstanceContainer();

        final FalcoSharedInstance shared = registered(env, container);

        assertTrue(shared.isRegistered());
        assertTrue(manager.getInstances().contains(shared));
        assertSame(shared, manager.getInstance(shared.getUuid()));
        assertSame(container, shared.getInstanceContainer());
        assertTrue(container.getSharedInstances().contains(shared),
                "the container has to know the view, or its chunks never take the view's players as viewers");
    }

    @Test
    @DisplayName("is refused by registerInstance, which is why registerSharedInstance exists")
    void testPlainRegistrationIsRefused(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);

        assertThrows(IllegalStateException.class, () -> env.process().instance().registerInstance(shared));
        assertFalse(shared.isRegistered());
    }

    @Test
    @DisplayName("cannot come from createSharedInstance, which always builds the stock type")
    void testTheFactoryOfMinestomBuildsTheStockType(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();

        final SharedInstance stock = env.process().instance().createSharedInstance(container);

        assertSame(SharedInstance.class, stock.getClass(),
                "if this ever changes, the hand registration in the README can go");
    }

    @Test
    @DisplayName("counts as linked to its container in both argument orders")
    void testLinkedToItsContainer(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance shared = registered(env, container);

        assertTrue(SharedInstance.areLinked(container, shared));
        assertTrue(SharedInstance.areLinked(shared, container));
    }

    @Test
    @DisplayName("counts as linked to a sibling view of the same container")
    void testLinkedToASibling(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance first = registered(env, container);
        final FalcoSharedInstance second = registered(env, container);

        assertTrue(SharedInstance.areLinked(first, second));
        assertTrue(SharedInstance.areLinked(second, first));
    }

    @Test
    @DisplayName("counts as linked to a stock shared instance over the same container")
    void testLinkedToAStockSharedInstance(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance falco = registered(env, container);
        final SharedInstance stock = env.process().instance().createSharedInstance(container);

        assertTrue(SharedInstance.areLinked(falco, stock));
    }

    @Test
    @DisplayName("counts as unlinked to a view of a different container")
    void testUnlinkedAcrossContainers(Env env) {
        final InstanceManager manager = env.process().instance();
        final InstanceContainer first = manager.createInstanceContainer();
        final InstanceContainer second = manager.createInstanceContainer();
        final FalcoSharedInstance sharedOnFirst = registered(env, first);
        final FalcoSharedInstance sharedOnSecond = registered(env, second);

        assertFalse(SharedInstance.areLinked(sharedOnFirst, sharedOnSecond));
        assertFalse(SharedInstance.areLinked(sharedOnFirst, second));
    }
}
