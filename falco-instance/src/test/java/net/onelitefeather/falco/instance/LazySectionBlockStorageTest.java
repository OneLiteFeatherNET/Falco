package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down what {@link LazySectionBlockStorage} does beyond the contract of {@link BlockStorage},
 * which is the whole of stage 2: that a section which holds nothing costs nothing.
 * <p>
 * The contract itself is not repeated here. {@code BlockStorageTest} runs every one of its cases
 * against both layouts, so a case that only says "what went in comes out" belongs there and would be
 * a duplicate here. What is left is exactly the set of statements that are false for the eager
 * storage: how many sections the storage owns, which slot points at the shared section, and which
 * call moves a slot from one state to the other.
 * </p>
 *
 * <h2>Why every case counts sections instead of reading blocks</h2>
 * <p>
 * A lazy storage that materialised every section on the first touch would satisfy every read and
 * write assertion in this file and save nothing at all, which is the failure mode this stage exists
 * to prevent. {@link BlockStorage#materialisedSections()} and {@link BlockStorage#shared(int)} are
 * the only two observations that can tell the two apart, so they carry the assertions and the block
 * reads are there to prove that the saving did not cost correctness.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@DisplayName("The lazy block storage of a chunk")
class LazySectionBlockStorageTest {

    private static final int SECTIONS = 24;
    private static final int MIN_SECTION = -4;

    @BeforeAll
    static void server() {
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }
    }

    private static LazySectionBlockStorage storage() {
        return new LazySectionBlockStorage(MIN_SECTION, SECTIONS);
    }

    @Test
    @DisplayName("owns no section at all before anything is written")
    void testNothingIsMaterialisedUpFront() {
        final LazySectionBlockStorage storage = storage();

        assertEquals(0, storage.materialisedSections());
        for (int section = 0; section < SECTIONS; section++) {
            assertTrue(storage.shared(section), "section " + section + " has to start out shared");
        }
    }

    @Test
    @DisplayName("shares one and the same section between every empty slot and every chunk")
    void testEverySharedSlotIsTheSameObject() {
        final LazySectionBlockStorage first = storage();
        final LazySectionBlockStorage second = storage();
        final Section shared = first.view(0);

        for (int section = 0; section < SECTIONS; section++) {
            assertSame(shared, first.view(section));
            assertSame(shared, second.view(section));
        }
    }

    @Test
    @DisplayName("materialises exactly the section that was written to and leaves the others shared")
    void testAWriteMaterialisesOneSection() {
        final LazySectionBlockStorage storage = storage();

        storage.setBlock(1, 20, 3, Block.STONE);

        assertEquals(1, storage.materialisedSections());
        assertFalse(storage.shared(5), "y=20 belongs to section index 5 of a chunk starting at -64");
        for (int section = 0; section < SECTIONS; section++) {
            if (section == 5) continue;
            assertTrue(storage.shared(section), "section " + section + " was not written to");
        }
        assertEquals(Block.STONE, storage.getBlock(1, 20, 3, Block.Getter.Condition.NONE));
        assertEquals(Block.AIR, storage.getBlock(1, 36, 3, Block.Getter.Condition.NONE));
    }

    @Test
    @DisplayName("does not materialise a section that is written air, but does for cave air")
    void testWritingAirLeavesTheSlotShared() {
        final LazySectionBlockStorage storage = storage();

        storage.setBlock(1, 20, 3, Block.AIR);

        assertEquals(0, storage.materialisedSections(),
                "writing the state the shared section already holds everywhere changes nothing, and "
                        + "a loader that walks a whole chunk writing air would otherwise materialise "
                        + "every section it touched");

        storage.setBlock(1, 20, 3, Block.CAVE_AIR);

        assertEquals(1, storage.materialisedSections(),
                "cave air is a different state id from air and has to be stored");
        assertEquals(Block.CAVE_AIR, storage.getBlock(1, 20, 3, Block.Getter.Condition.NONE));
    }

    @Test
    @DisplayName("answers a read of a shared section without touching a palette")
    void testReadingASharedSectionDoesNotMaterialise() {
        final LazySectionBlockStorage storage = storage();

        for (int y = -64; y < 320; y += 16) {
            assertEquals(Block.AIR, storage.getBlock(0, y, 0, Block.Getter.Condition.NONE));
        }
        assertEquals(0, storage.materialisedSections());
    }

    @Test
    @DisplayName("materialises every section when the boundary hands them out")
    void testTheBoundaryMaterialisesEverything() {
        final LazySectionBlockStorage byOne = storage();
        final LazySectionBlockStorage byAll = storage();

        byOne.section(5);
        assertEquals(1, byOne.materialisedSections(),
                "section(int) is the boundary for one section, not for the chunk");

        byAll.sections();
        assertEquals(SECTIONS, byAll.materialisedSections(),
                "sections() hands the whole chunk to a caller that may write to any of it");
    }

    /**
     * A materialised section must be a fresh one and not a clone of the shared one.
     * <p>
     * The property that separates the two is the light. A fresh {@code Section} has never had
     * {@code SkyLight#set} called on it, so it does not claim to have light to send; a clone of the
     * shared section has, because {@code Section#clone} runs {@code skyLight.set(skyLight.array())}
     * unconditionally. The clone in this test is not decoration: it states the fact about Minestom
     * the implementation rests on, so that a Minestom which stopped raising {@code needsSend} there
     * would fail this case rather than silently turn the assertion below into one that holds for
     * both branches.
     * </p>
     * <p>
     * What is deliberately <em>not</em> asserted is {@code skyLight().array().length}. The brief for
     * this task proposed it, but it does not separate the two: {@code SkyLight#set} stores
     * {@code LightCompute.EMPTY_CONTENT}, and {@code SkyLight#array} bakes that back into
     * {@code UNSET_CONTENT} and returns a zero length array for a clone exactly as it does for a
     * fresh section.
     * </p>
     */
    @Test
    @DisplayName("materialises with a fresh section rather than a clone of the shared one")
    void testMaterialisationDoesNotCloneTheFlyweight() {
        final LazySectionBlockStorage storage = storage();
        final Section shared = storage.view(0);

        storage.setBlock(0, 0, 0, Block.STONE);

        assertSame(shared, storage.view(0),
                "y=0 is section index 4, so section 0 must have been left alone");

        final Section written = storage.view(4);

        assertNotSame(shared, written, "the write to y=0 has to have materialised section 4");
        assertTrue(shared.clone().skyLight().requiresSend(),
                "the reason this class must not materialise through clone: Section#clone hands the "
                        + "unset light of the shared section to SkyLight#set, which raises needsSend");
        assertFalse(written.skyLight().requiresSend(),
                "a section that has never been lit has nothing to send");
    }

    @Test
    @DisplayName("copies without materialising what the original had not materialised")
    void testCopyKeepsSharing() {
        final LazySectionBlockStorage original = storage();
        original.setBlock(1, 20, 3, Block.STONE);

        final BlockStorage copy = original.copy();

        assertEquals(1, copy.materialisedSections());
        assertEquals(Block.STONE, copy.getBlock(1, 20, 3, Block.Getter.Condition.NONE));

        copy.setBlock(1, 20, 3, Block.DIRT);
        assertEquals(Block.STONE, original.getBlock(1, 20, 3, Block.Getter.Condition.NONE),
                "a copy that shared a materialised section would change the original");
    }

    @Test
    @DisplayName("returns every slot to the shared section when it is cleared")
    void testClearReleasesEverySection() {
        final LazySectionBlockStorage storage = storage();
        storage.setBlock(1, 20, 3, Block.STONE);
        final Section materialised = storage.view(5);

        storage.clear();

        assertEquals(0, storage.materialisedSections());
        assertEquals(Block.AIR, storage.getBlock(1, 20, 3, Block.Getter.Condition.NONE));
        assertEquals(0, materialised.blockPalette().count(),
                "a caller holding the section from before the reset has to see it emptied, which is "
                        + "what Section#clear does and what DynamicChunk#reset relies on");
    }
}
