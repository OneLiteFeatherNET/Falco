package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

@DisplayName("The block storage of a chunk")
class BlockStorageTest {

    private static final int SECTIONS = 24;
    private static final int MIN_SECTION = -4;

    @BeforeAll
    static void server() {
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }
    }

    private static BlockStorage storage() {
        return new SectionBlockStorage(MIN_SECTION, SECTIONS);
    }

    @Test
    @DisplayName("returns air for a position nothing was written to")
    void testEmptyReadsAir() {
        assertEquals(Block.AIR, storage().getBlock(0, 0, 0, Block.Getter.Condition.NONE));
    }

    @Test
    @DisplayName("returns what was written, at the position it was written to")
    void testWriteThenRead() {
        final BlockStorage storage = storage();

        storage.setBlock(1, 2, 3, Block.STONE);

        assertEquals(Block.STONE, storage.getBlock(1, 2, 3, Block.Getter.Condition.NONE));
        assertEquals(Block.AIR, storage.getBlock(1, 2, 4, Block.Getter.Condition.NONE));
    }

    @Test
    @DisplayName("holds one section per section of the chunk")
    void testSectionCount() {
        assertEquals(SECTIONS, storage().sectionCount());
        assertEquals(SECTIONS, storage().sections().size());
    }

    @Test
    @DisplayName("copies without sharing storage with the original")
    void testCopyIsIndependent() {
        final BlockStorage original = storage();
        original.setBlock(1, 2, 3, Block.STONE);

        final BlockStorage copy = original.copy();
        copy.setBlock(1, 2, 3, Block.DIRT);

        assertNotSame(original, copy);
        assertEquals(Block.STONE, original.getBlock(1, 2, 3, Block.Getter.Condition.NONE));
        assertEquals(Block.DIRT, copy.getBlock(1, 2, 3, Block.Getter.Condition.NONE));
    }

    @Test
    @DisplayName("reads air everywhere after being cleared")
    void testClear() {
        final BlockStorage storage = storage();
        storage.setBlock(1, 2, 3, Block.STONE);

        storage.clear();

        assertEquals(Block.AIR, storage.getBlock(1, 2, 3, Block.Getter.Condition.NONE));
    }
}
