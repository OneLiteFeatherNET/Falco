package net.onelitefeather.falco.demo;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the command line of the demo. The thread count is the condition under which a reported
 * figure is valid, so the cases which decide it are the ones worth pinning down.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class DemoOptionsTest {

    @Test
    void testTheLoaderIsRequired() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> DemoOptions.parse(new String[]{"--threads=2"}, 8)
        );

        assertTrue(exception.getMessage().contains("--loader"), exception.getMessage());
    }

    @Test
    void testTheDefaultThreadCountIsCappedForALargeMachine() {
        DemoOptions options = DemoOptions.parse(new String[]{"--loader=falco"}, 64);

        assertEquals(DemoOptions.DEFAULT_THREAD_LIMIT, options.threads());
    }

    @Test
    void testTheDefaultThreadCountFollowsASmallMachine() {
        DemoOptions options = DemoOptions.parse(new String[]{"--loader=falco"}, 2);

        assertEquals(2, options.threads());
    }

    @Test
    void testTheDefaultThreadCountIsAtLeastOne() {
        DemoOptions options = DemoOptions.parse(new String[]{"--loader=minestom"}, 0);

        assertEquals(1, options.threads());
    }

    @Test
    void testEveryOptionIsRead() {
        DemoOptions options = DemoOptions.parse(new String[]{
                "--loader=minestom",
                "--threads=12",
                "--chunks=200",
                "--warmup=1",
                "--rounds=7",
                "--dimension=minecraft:the_nether"
        }, 8);

        assertEquals(LoaderKind.MINESTOM, options.loader());
        assertEquals(12, options.threads());
        assertEquals(200, options.chunks());
        assertEquals(1, options.warmupRounds());
        assertEquals(7, options.measurementRounds());
        assertEquals(Key.key("minecraft:the_nether"), options.dimension());
    }

    @Test
    void testTheLoaderNameIgnoresCase() {
        assertEquals(LoaderKind.FALCO, DemoOptions.parse(new String[]{"--loader=FALCO"}, 4).loader());
    }

    @Test
    void testAnUnknownLoaderIsRefused() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> DemoOptions.parse(new String[]{"--loader=paper"}, 4)
        );

        assertTrue(exception.getMessage().contains("falco, minestom"), exception.getMessage());
    }

    @Test
    void testAMistypedOptionIsRefusedInsteadOfIgnored() {
        // The point of refusing: --thread=8 that silently ran on the default would be reported as
        // a measurement at a thread count the run never used.
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> DemoOptions.parse(new String[]{"--loader=falco", "--thread=8"}, 4)
        );

        assertTrue(exception.getMessage().contains("--thread"), exception.getMessage());
    }

    @Test
    void testAnArgumentWithoutAValueIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> DemoOptions.parse(new String[]{"--loader"}, 4));
    }

    @Test
    void testANonNumericThreadCountIsRefused() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> DemoOptions.parse(new String[]{"--loader=falco", "--threads=many"}, 4)
        );

        assertTrue(exception.getMessage().contains("whole number"), exception.getMessage());
    }

    @Test
    void testASingleMeasuredRoundIsRefusedBecauseItHasNoSpread() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> DemoOptions.parse(new String[]{"--loader=falco", "--rounds=1"}, 4)
        );

        assertTrue(exception.getMessage().contains("spread"), exception.getMessage());
    }

    @Test
    void testAThreadCountBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> DemoOptions.parse(new String[]{"--loader=falco", "--threads=0"}, 4));
    }

    @Test
    void testAChunkCountBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> DemoOptions.parse(new String[]{"--loader=falco", "--chunks=0"}, 4));
    }

    @Test
    void testAWarmupOfZeroIsAllowed() {
        assertEquals(0, DemoOptions.parse(new String[]{"--loader=falco", "--warmup=0"}, 4).warmupRounds());
    }

    @Test
    void testADimensionWithoutANamespaceBecomesVanilla() {
        DemoOptions options = DemoOptions.parse(new String[]{"--loader=falco", "--dimension=the_end"}, 4);

        assertEquals(Key.key("minecraft:the_end"), options.dimension());
    }

    @Test
    void testAnInvalidDimensionIsRefused() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> DemoOptions.parse(new String[]{"--loader=falco", "--dimension=Not A Key"}, 4)
        );

        assertTrue(exception.getMessage().contains("--dimension"), exception.getMessage());
    }
}
