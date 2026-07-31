package net.onelitefeather.falco.anvil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the compression schemes which the Anvil format supports for a chunk payload.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class ChunkCompressionTest {

    private static final byte[] PAYLOAD = "a chunk payload which repeats a chunk payload".getBytes(StandardCharsets.UTF_8);

    @ParameterizedTest
    @EnumSource(ChunkCompression.class)
    void testCompressAndDecompressAreInverse(ChunkCompression compression) throws IOException {
        byte[] compressed = compression.compress(PAYLOAD);

        assertArrayEquals(PAYLOAD, compression.decompress(compressed));
    }

    @ParameterizedTest
    @EnumSource(ChunkCompression.class)
    void testEveryCompressionExposesItsFormatIdentifier(ChunkCompression compression) throws IOException {
        assertEquals(compression, ChunkCompression.fromId(compression.id()));
    }

    @Test
    void testGzipIsIdentifiedByOne() throws IOException {
        assertEquals(ChunkCompression.GZIP, ChunkCompression.fromId(1));
    }

    @Test
    void testZlibIsIdentifiedByTwo() throws IOException {
        assertEquals(ChunkCompression.ZLIB, ChunkCompression.fromId(2));
    }

    @Test
    void testNoneIsIdentifiedByThree() throws IOException {
        assertEquals(ChunkCompression.NONE, ChunkCompression.fromId(3));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, 5, 127})
    void testUnsupportedSchemesAreRejectedWithTheirIdentifier(int id) {
        IOException exception = assertThrows(IOException.class, () -> ChunkCompression.fromId(id));

        assertTrue(exception.getMessage().contains(String.valueOf(id)));
    }

    @Test
    void testTheExternalFlagIsDetected() {
        assertTrue(ChunkCompression.isExternal(2 | ChunkCompression.EXTERNAL_FLAG));
        assertFalse(ChunkCompression.isExternal(2));
    }

    @Test
    void testTheExternalFlagIsStrippedBeforeResolving() throws IOException {
        assertEquals(ChunkCompression.ZLIB, ChunkCompression.fromId(2 | ChunkCompression.EXTERNAL_FLAG));
    }

    @Test
    void testNoneKeepsThePayloadUntouched() throws IOException {
        assertArrayEquals(PAYLOAD, ChunkCompression.NONE.compress(PAYLOAD));
    }

    @Test
    void testZlibActuallyShrinksARepetitivePayload() throws IOException {
        byte[] repetitive = new byte[4096];

        assertTrue(ChunkCompression.ZLIB.compress(repetitive).length < repetitive.length);
    }

    @Test
    void testTheCompressionLevelCanBeChosen() throws IOException {
        byte[] payload = new byte[64 * 1024];
        new java.util.Random(7).nextBytes(payload);
        // Half the array is compressible so the level actually has an effect.
        java.util.Arrays.fill(payload, 0, payload.length / 2, (byte) 7);

        byte[] fast = ChunkCompression.ZLIB.compress(payload, ChunkCompression.FASTEST_LEVEL);
        byte[] balanced = ChunkCompression.ZLIB.compress(payload, ChunkCompression.DEFAULT_LEVEL);

        assertArrayEquals(payload, ChunkCompression.ZLIB.decompress(fast));
        assertArrayEquals(payload, ChunkCompression.ZLIB.decompress(balanced));
        assertTrue(balanced.length <= fast.length, "a higher level must not produce a larger result");
    }

    @Test
    void testEveryLevelRoundTrips() throws IOException {
        for (int level = ChunkCompression.FASTEST_LEVEL; level <= ChunkCompression.SMALLEST_LEVEL; level++) {
            assertArrayEquals(PAYLOAD, ChunkCompression.ZLIB.decompress(ChunkCompression.ZLIB.compress(PAYLOAD, level)),
                    "level " + level + " has to round trip");
        }
    }

    @Test
    void testAnInvalidLevelIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ChunkCompression.ZLIB.compress(PAYLOAD, 0));
        assertThrows(IllegalArgumentException.class, () -> ChunkCompression.ZLIB.compress(PAYLOAD, 10));
    }

    @Test
    void testTheLevelIsIgnoredWithoutCompression() throws IOException {
        assertArrayEquals(PAYLOAD, ChunkCompression.NONE.compress(PAYLOAD, ChunkCompression.SMALLEST_LEVEL));
    }
}
