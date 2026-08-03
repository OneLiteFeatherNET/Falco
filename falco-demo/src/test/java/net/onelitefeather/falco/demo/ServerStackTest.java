package net.onelitefeather.falco.demo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the one place the two servers differ. The point of these tests is not the enum but the
 * claim it carries: that the difference is the loader and the chunk type, that both are named by
 * their class rather than by a label, and that the instance is the same on both sides.
 *
 * @author TheMeinerLP
 * @version 1.1.0
 * @since 0.3.0
 */
class ServerStackTest {

    /**
     * Joins the composition of a stack into one searchable string.
     *
     * @param stack the stack to describe
     * @return the composition as a single line
     */
    private String composition(ServerStack stack) {
        return String.join(" | ", stack.composition());
    }

    @Test
    void testAStackIsSelectedByItsOptionValue() {
        assertEquals(ServerStack.FALCO, ServerStack.parse("falco"));
        assertEquals(ServerStack.MINESTOM, ServerStack.parse("minestom"));
    }

    @Test
    void testTheOptionValueIsCaseInsensitive() {
        assertEquals(ServerStack.FALCO, ServerStack.parse("Falco"));
    }

    @Test
    void testAnUnknownStackNamesTheKnownOnes() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> ServerStack.parse("paper")
        );

        assertTrue(exception.getMessage().contains("falco"), exception.getMessage());
        assertTrue(exception.getMessage().contains("minestom"), exception.getMessage());
    }

    @Test
    void testTheFalcoStackUsesTheFalcoLoaderAndTheFalcoLight() {
        assertEquals(LoaderKind.FALCO, ServerStack.FALCO.loader());
        assertEquals("net.onelitefeather.falco.light.FalcoLightingChunk", ServerStack.FALCO.chunkImplementationName());
        assertTrue(ServerStack.FALCO.lightEngineDescription().contains("ChunkLightScheduler"),
                ServerStack.FALCO.lightEngineDescription());
    }

    @Test
    void testTheMinestomStackUsesNothingOfThisRepository() {
        assertEquals(LoaderKind.MINESTOM, ServerStack.MINESTOM.loader());
        assertEquals("net.minestom.server.instance.LightingChunk", ServerStack.MINESTOM.chunkImplementationName());
        assertFalse(composition(ServerStack.MINESTOM).contains("onelitefeather"), composition(ServerStack.MINESTOM));
    }

    @Test
    void testTheTwoStacksDifferInTheLoaderAndTheChunk() {
        assertNotEquals(ServerStack.FALCO.loader(), ServerStack.MINESTOM.loader());
        assertNotEquals(ServerStack.FALCO.chunkImplementationName(), ServerStack.MINESTOM.chunkImplementationName());
    }

    @Test
    void testBothStacksRunOnTheSameInstance() {
        assertTrue(composition(ServerStack.FALCO).contains("InstanceContainer"), composition(ServerStack.FALCO));
        assertTrue(composition(ServerStack.MINESTOM).contains("InstanceContainer"), composition(ServerStack.MINESTOM));
    }

    @Test
    void testEveryComponentIsNamedByItsClass() {
        for (ServerStack stack : ServerStack.values()) {
            List<String> composition = stack.composition();

            assertEquals(4, composition.size(), composition.toString());
            assertTrue(composition.get(0).contains(stack.loader().implementationName()), composition.toString());
            assertTrue(composition.get(1).contains(stack.chunkImplementationName()), composition.toString());
        }
    }

    @Test
    void testTheFalcoStackExplainsWhyTheFalcoInstanceIsMissing() {
        String note = ServerStack.FALCO.note();

        assertTrue(note.contains("FalcoInstance"), note);
        // The reason changed twice. It used to be that the combination was impossible; then
        // setChunkLifecycle made it reachable through a pair of hooks; since US-3.06 a
        // FalcoLightingChunk is a FalcoChunk and needs nothing but a chunk supplier. What keeps the
        // instance out of the demo is none of that, it is that it would make the two servers differ
        // in three things instead of one.
        assertTrue(note.contains("FalcoLightingChunk"), note);
        assertTrue(note.contains("setChunkSupplier"), note);
        assertTrue(note.contains("three things"), note);
        assertEquals("", ServerStack.MINESTOM.note());
    }

    @Test
    void testEveryStackHasAShortNameForThePlayer() {
        assertEquals("Falco", ServerStack.FALCO.displayName());
        assertEquals("Minestom", ServerStack.MINESTOM.displayName());
    }
}
