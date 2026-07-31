package net.onelitefeather.falco.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the selection of the loader. Only the naming is covered; building a loader touches the
 * Minestom registries and belongs to the two run tasks, not to a unit test.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class LoaderKindTest {

    @Test
    void testTheOptionValuesAreTheOnesTheTasksPass() {
        assertEquals("falco", LoaderKind.FALCO.option());
        assertEquals("minestom", LoaderKind.MINESTOM.option());
    }

    @Test
    void testEachConstantNamesTheClassItBuilds() {
        assertEquals("net.onelitefeather.falco.anvil.FalcoAnvilLoader", LoaderKind.FALCO.implementationName());
        assertEquals("net.minestom.server.instance.anvil.AnvilLoader", LoaderKind.MINESTOM.implementationName());
    }

    @Test
    void testParsingIgnoresCase() {
        assertEquals(LoaderKind.MINESTOM, LoaderKind.parse("MineStom"));
    }

    @Test
    void testAnUnknownNameListsTheKnownOnes() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> LoaderKind.parse("vanilla"));

        assertTrue(exception.getMessage().contains("falco, minestom"), exception.getMessage());
    }
}
