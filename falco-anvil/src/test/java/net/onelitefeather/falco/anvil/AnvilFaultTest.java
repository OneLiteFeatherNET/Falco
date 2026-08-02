package net.onelitefeather.falco.anvil;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the fault hierarchy of the loader.
 * <p>
 * The point of these types is that a caller can tell one failure from another without reading a
 * message, so the tests are about exactly that: which type carries which case, that the root is not
 * an {@link IOException} and therefore not caught by anything that already catches one, and that the
 * location is attached where the format classes cannot know it.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 1.0.0
 */
class AnvilFaultTest {

    private static final ChunkLocation LOCATION =
            new ChunkLocation(3, -7, "world/region/r.0.-1.mca", "minecraft:overworld");

    /**
     * The whole point of the decision: a format fault is not an {@link IOException}.
     * <p>
     * Every existing {@code catch (IOException)} would otherwise keep swallowing the new types,
     * including the one in {@code saveChunk} that leaves a chunk unsaved with a log line.
     * </p>
     */
    @Test
    void testAFormatFaultIsNotAnIoException() {
        AnvilFormatException fault = new ChunkDataException(
                ChunkDataException.Reason.EMPTY_PALETTE, "the palette is empty");

        // Written through the class rather than with instanceof on purpose: `fault instanceof
        // IOException` does not compile, because the two types are unrelated and the test would be
        // provably false. That refusal is the strongest form of the property under test.
        assertFalse(IOException.class.isAssignableFrom(AnvilFormatException.class),
                "a caller that catches IOException must not catch a format fault by accident");
        assertInstanceOf(Exception.class, fault, "it stays checked, so the compiler names every site");
    }

    @Test
    void testEveryFaultCarriesItsReason() {
        ChunkDataException data = new ChunkDataException(
                ChunkDataException.Reason.PALETTE_INDEX_OUT_OF_RANGE, "index 9 of 4");
        RegionFormatException region = new RegionFormatException(
                RegionFormatException.Reason.HEADER_TOO_SHORT, "12 of 8192 bytes");

        assertEquals(ChunkDataException.Reason.PALETTE_INDEX_OUT_OF_RANGE, data.reason());
        assertEquals(RegionFormatException.Reason.HEADER_TOO_SHORT, region.reason());
    }

    /**
     * A fault without a location can be given one, because the format classes are not allowed to
     * know where the bytes came from.
     */
    @Test
    void testALocationIsAttachedWithoutLosingTheOriginal() {
        ChunkDataException raw = new ChunkDataException(
                ChunkDataException.Reason.EMPTY_PALETTE, "the palette is empty");

        assertNull(raw.location(), "the format classes cannot know it");

        ChunkDataException located = raw.at(LOCATION);

        assertNotSame(raw, located, "an exception is not modified after the fact");
        assertEquals(LOCATION, located.location());
        assertEquals(raw.reason(), located.reason(), "the reason survives");
        assertSame(raw, located.getCause(), "and so does the original, with its stack trace");
    }

    /**
     * The context appears once, in the location, and not a second time inside the message.
     */
    @Test
    void testTheLocatedMessageNamesTheContextExactlyOnce() {
        ChunkDataException located = new ChunkDataException(
                ChunkDataException.Reason.EMPTY_PALETTE, "the palette is empty").at(LOCATION);

        String message = located.getMessage();

        assertTrue(message.contains("the palette is empty"), message);
        assertEquals(1, countOccurrences(message, "3/-7"), "the coordinate is formatted once: " + message);
    }

    @Test
    void testTheLocationFormatsItselfForALogLine() {
        String text = LOCATION.toString();

        assertTrue(text.contains("3/-7"), text);
        assertTrue(text.contains("r.0.-1.mca"), text);
        assertTrue(text.contains("minecraft:overworld"), text);
    }

    /**
     * Both branches are reachable through the sealed root, which is what makes a switch over them
     * exhaustive at compile time.
     */
    @Test
    void testTheSealedRootCoversBothBranches() {
        AnvilFault data = new ChunkDataException(ChunkDataException.Reason.EMPTY_PALETTE, "x");
        AnvilFault region = new RegionFormatException(RegionFormatException.Reason.TRUNCATED_FILE, "y");

        assertEquals("chunk data", describe(data));
        assertEquals("region format", describe(region));
    }

    /**
     * Describes a fault through an exhaustive switch, which only compiles while the root is sealed.
     *
     * @param fault the fault to describe
     * @return a word for the branch the fault belongs to
     */
    private static String describe(AnvilFault fault) {
        return switch (fault) {
            case ChunkDataException ignored -> "chunk data";
            case RegionFormatException ignored -> "region format";
            case AnvilChunkException ignored -> "boundary";
        };
    }

    /**
     * Counts how often a fragment appears in a text.
     *
     * @param text     the text to search
     * @param fragment the fragment to count
     * @return the number of occurrences
     */
    private static int countOccurrences(String text, String fragment) {
        int count = 0;
        int index = text.indexOf(fragment);

        while (index >= 0) {
            count++;
            index = text.indexOf(fragment, index + fragment.length());
        }
        return count;
    }
}
