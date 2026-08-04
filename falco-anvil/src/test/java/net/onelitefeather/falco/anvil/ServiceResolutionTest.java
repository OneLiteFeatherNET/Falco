package net.onelitefeather.falco.anvil;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the resolution rules {@link ServiceResolution} offers to the two extension points built
 * on top of it: how a service is discovered on the classpath, and how an explicitly configured
 * instance relates to that discovery.
 * <p>
 * The dummy providers this test resolves are registered through the test resources, under
 * {@code META-INF/services/net.onelitefeather.falco.anvil.ServiceResolutionTest$Dummy} and two more
 * files named after {@link DefaultAware} and {@link DefaultAwareWithTwoForeign} below, so the main
 * module registers nothing extra.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
 * @since 1.0.0
 */
class ServiceResolutionTest {

    interface Dummy {
        String name();
    }

    public static final class FirstDummy implements Dummy {
        public FirstDummy() {
        }

        @Override
        public String name() {
            return "first";
        }
    }

    public static final class SecondDummy implements Dummy {
        public SecondDummy() {
        }

        @Override
        public String name() {
            return "second";
        }
    }

    interface Absent {
    }

    /**
     * A service with a "shipped default" registered next to a single foreign provider, for the two
     * {@link ServiceResolution#discover(Class, Class)} cases below.
     */
    interface DefaultAware {
        String name();
    }

    public static final class ShippedDefault implements DefaultAware {
        public ShippedDefault() {
        }

        @Override
        public String name() {
            return "shipped";
        }
    }

    public static final class ForeignProvider implements DefaultAware {
        public ForeignProvider() {
        }

        @Override
        public String name() {
            return "foreign";
        }
    }

    /**
     * A separate service, registered with a shipped default and <em>two</em> foreign providers, so
     * that "the default steps aside" and "two foreign providers still refuse each other" can be
     * pinned down independently of one another.
     */
    interface DefaultAwareWithTwoForeign {
        String name();
    }

    public static final class AnotherShippedDefault implements DefaultAwareWithTwoForeign {
        public AnotherShippedDefault() {
        }

        @Override
        public String name() {
            return "shipped";
        }
    }

    public static final class FirstForeignProvider implements DefaultAwareWithTwoForeign {
        public FirstForeignProvider() {
        }

        @Override
        public String name() {
            return "first-foreign";
        }
    }

    public static final class SecondForeignProvider implements DefaultAwareWithTwoForeign {
        public SecondForeignProvider() {
        }

        @Override
        public String name() {
            return "second-foreign";
        }
    }

    @Test
    void testAServiceWithNoProviderResolvesToNothing() {
        assertNull(ServiceResolution.discover(Absent.class));
    }

    @Test
    void testTwoProvidersAreRefusedAndBothAreNamed() {
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> ServiceResolution.discover(Dummy.class));

        assertTrue(failure.getMessage().contains("FirstDummy"), failure.getMessage());
        assertTrue(failure.getMessage().contains("SecondDummy"), failure.getMessage());
    }

    @Test
    void testAnExplicitInstanceAndDiscoveryTogetherAreRefused() {
        Dummy explicit = () -> "explicit";

        assertThrows(IllegalStateException.class,
                () -> ServiceResolution.choose(Dummy.class, explicit, true));
    }

    @Test
    void testAnExplicitInstanceIsUsedWithoutTouchingTheClasspath() {
        Dummy explicit = () -> "explicit";

        assertEquals("explicit", ServiceResolution.choose(Dummy.class, explicit, false).name());
    }

    @Test
    void testNeitherExplicitNorDiscoveredResolvesToNothing() {
        assertNull(ServiceResolution.choose(Dummy.class, null, false));
    }

    @Test
    void testAForeignProviderWinsOverTheShippedDefault() {
        DefaultAware resolved = ServiceResolution.discover(DefaultAware.class, ShippedDefault.class);

        assertEquals("foreign", resolved.name());
    }

    @Test
    void testTwoForeignProvidersAreStillRefusedEvenWithAShippedDefaultRegistered() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ServiceResolution.discover(DefaultAwareWithTwoForeign.class, AnotherShippedDefault.class));

        assertTrue(failure.getMessage().contains("FirstForeignProvider"), failure.getMessage());
        assertTrue(failure.getMessage().contains("SecondForeignProvider"), failure.getMessage());
    }
}
