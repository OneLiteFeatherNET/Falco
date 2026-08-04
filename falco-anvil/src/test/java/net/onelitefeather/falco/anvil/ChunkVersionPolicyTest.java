package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link DefaultChunkVersionPolicy} directly, against chunk data built by hand rather than
 * through the loader. What used to be the loader's private {@code requireReadableVersion} guard now
 * lives here, unchanged in its decision logic and reachable without a running Minestom environment.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 1.2.0
 */
class ChunkVersionPolicyTest {

    @Test
    void testTheDefaultPolicyRefusesALevelLayout() {
        CompoundBinaryTag legacy = CompoundBinaryTag.builder()
                .put("Level", CompoundBinaryTag.builder().put("Sections", ListBinaryTag.empty()).build())
                .build();

        ChunkDataException failure = assertThrows(ChunkDataException.class,
                () -> new DefaultChunkVersionPolicy().check(legacy, 2844));
        assertEquals(ChunkDataException.Reason.UNSUPPORTED_CHUNK_VERSION, failure.reason());
    }

    @Test
    void testTheDefaultPolicyAcceptsAChunkWithoutAStoredVersion() throws Exception {
        CompoundBinaryTag toolWritten = CompoundBinaryTag.builder()
                .put("sections", ListBinaryTag.empty())
                .build();

        new DefaultChunkVersionPolicy().check(toolWritten, 2844);
    }

    @Test
    void testTheDefaultPolicyRefusesAMistypedVersion() {
        CompoundBinaryTag broken = CompoundBinaryTag.builder()
                .putString("DataVersion", "not-a-number")
                .put("sections", ListBinaryTag.empty())
                .build();

        assertThrows(ChunkDataException.class, () -> new DefaultChunkVersionPolicy().check(broken, 2844));
    }
}
