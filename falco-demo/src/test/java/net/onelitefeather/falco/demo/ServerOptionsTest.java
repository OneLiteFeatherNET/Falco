package net.onelitefeather.falco.demo;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the command line of the demo server. The bounds matter here for the same reason they matter
 * in the measurement: a run which silently used a value it was not asked for cannot be compared
 * against the other one.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.3.0
 */
class ServerOptionsTest {

    /**
     * Parses a command line.
     *
     * @param arguments the arguments to parse
     * @return the parsed options
     */
    private ServerOptions parse(String... arguments) {
        return ServerOptions.parse(arguments);
    }

    @Test
    void testTheStackIsRequired() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, this::parse);

        assertTrue(exception.getMessage().contains("--stack"), exception.getMessage());
    }

    @Test
    void testAStackAloneUsesTheDefaults() {
        ServerOptions options = parse("--stack=falco");

        assertEquals(ServerStack.FALCO, options.stack());
        assertEquals(ServerOptions.DEFAULT_PORT, options.port());
        assertEquals(Key.key("minecraft:overworld"), options.dimension());
        assertEquals(ServerOptions.DEFAULT_REPORT_INTERVAL_SECONDS, options.reportIntervalSeconds());
    }

    @Test
    void testBothStacksDefaultToTheSamePort() {
        assertEquals(parse("--stack=falco").port(), parse("--stack=minestom").port());
    }

    @Test
    void testThePortCanBeChosen() {
        assertEquals(25599, parse("--stack=minestom", "--port=25599").port());
    }

    @Test
    void testAPortOutsideTheRangeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> parse("--stack=falco", "--port=0"));
        assertThrows(IllegalArgumentException.class, () -> parse("--stack=falco", "--port=70000"));
    }

    @Test
    void testADimensionWithoutANamespaceBecomesAMinecraftOne() {
        assertEquals(Key.key("minecraft:the_nether"), parse("--stack=falco", "--dimension=the_nether").dimension());
    }

    @Test
    void testAReportIntervalBelowASecondIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> parse("--stack=falco", "--report=0"));
    }

    @Test
    void testAnUnknownOptionIsRefusedRatherThanIgnored() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> parse("--stack=falco", "--prot=25565")
        );

        assertTrue(exception.getMessage().contains("--prot"), exception.getMessage());
        assertTrue(exception.getMessage().contains("--port"), exception.getMessage());
    }

    @Test
    void testAnArgumentWithoutAValueIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> parse("--stack"));
        assertThrows(IllegalArgumentException.class, () -> parse("stack=falco"));
    }

    @Test
    void testANonNumericPortIsRefusedWithItsName() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> parse("--stack=falco", "--port=here")
        );

        assertTrue(exception.getMessage().contains("--port"), exception.getMessage());
    }

    @Test
    void testTheDefaultsAreTheOnesTheParserApplies() {
        assertEquals(parse("--stack=minestom"), ServerOptions.defaults(ServerStack.MINESTOM));
    }
}
