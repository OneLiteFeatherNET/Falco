package net.onelitefeather.falco.instance;

import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins down the contract of {@link BlockStorage} on the storage stage 1 ships,
 * {@link SectionBlockStorage}, so that the implementation stage 2 adds can be held to the same
 * file without a line of it being rewritten for the new layout.
 * <p>
 * Everything here therefore goes through the interface and never through a section, with one
 * deliberate exception: the two tests that pin the height arithmetic and the unknown state read
 * through {@link BlockStorage#section(int)}, because those are precisely the properties an
 * assertion phrased in terms of {@code getBlock} alone cannot see.
 * </p>
 *
 * <h2>Why the height tests name the section</h2>
 * <p>
 * A storage that spans sections {@code -4} to {@code 19} has to subtract its bottom section from
 * every height it is given. Reading back what was written proves nothing about that subtraction: a
 * storage that forgot it entirely is still self-consistent, since a write and a read of the same
 * height land in the same wrong section, and every assertion of the form "what went in comes out"
 * stays green. That is not a hypothetical — it was the state of this file when the storage was
 * introduced, where every case sat at {@code y = 0..3} and the offset could have been deleted with
 * all five tests still passing. Naming the section a height belongs to is what turns the offset into
 * something a test can be wrong about, and the heights below are chosen so that both signs and both
 * ends of the world are covered.
 * </p>
 *
 * <h2>Why the biome cases exist at all</h2>
 * <p>
 * A biome is stored as a registry id, and a registry answers a lookup miss with {@code -1} rather
 * than with an exception. A palette validates its coordinates and never its values, so an
 * unregistered biome is accepted, counted and serialised like any other — the failure surfaces on a
 * read somewhere else, or on a client, and by then nothing points back to the write. The two guards
 * against that are the kind that quietly stop existing during a refactor, because no ordinary
 * round trip touches them.
 * </p>
 *
 * @author TheMeinerLP
 * @version 2.0.0
 * @since 0.4.0
 */
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

    @ParameterizedTest(name = "y = {0} belongs to section {1}")
    @CsvSource({"-64, 0", "-49, 0", "-48, 1", "-1, 3", "0, 4", "127, 11", "300, 22", "319, 23"})
    @DisplayName("writes a height into the section that height belongs to, and into no other")
    void testHeightSelectsItsSection(int y, int expectedSection) {
        final BlockStorage storage = storage();

        storage.setBlock(1, y, 3, Block.STONE);

        assertEquals(Block.STONE.stateId(),
                storage.section(expectedSection).blockPalette()
                        .get(1, CoordConversion.globalToSectionRelative(y), 3),
                "the block has to sit in section " + expectedSection);
        assertEquals(Block.STONE, storage.getBlock(1, y, 3, Block.Getter.Condition.NONE));

        int written = 0;
        for (int section = 0; section < storage.sectionCount(); section++) {
            written += storage.section(section).blockPalette().count();
        }
        assertEquals(1, written, "exactly one section of the storage may hold a block");
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
    @DisplayName("copies the height of every block along with it")
    void testCopyKeepsTheHeights() {
        final BlockStorage original = storage();
        original.setBlock(1, -64, 3, Block.STONE);
        original.setBlock(1, 300, 3, Block.DIRT);

        final BlockStorage copy = original.copy();

        assertEquals(Block.STONE, copy.getBlock(1, -64, 3, Block.Getter.Condition.NONE));
        assertEquals(Block.DIRT, copy.getBlock(1, 300, 3, Block.Getter.Condition.NONE));
        assertEquals(Block.STONE.stateId(),
                copy.section(0).blockPalette().get(1, 0, 3));
        assertEquals(Block.DIRT.stateId(),
                copy.section(22).blockPalette().get(1, 12, 3));
    }

    @Test
    @DisplayName("reads air everywhere after being cleared")
    void testClear() {
        final BlockStorage storage = storage();
        storage.setBlock(1, 2, 3, Block.STONE);

        storage.clear();

        assertEquals(Block.AIR, storage.getBlock(1, 2, 3, Block.Getter.Condition.NONE));
    }

    /**
     * A raw state id written past the storage must not come back as {@code null}.
     * <p>
     * The value is written through the palette on purpose, because that is how it gets there in
     * production: a generator fork and every chunk loader write state ids into
     * {@code section.blockPalette()} directly, without passing a {@link Block} that could have been
     * validated first. {@code Block.Getter.Condition#NONE} promises a block no matter what, and
     * callers of {@code Block.Getter#getBlock(int, int, int)} dereference the result.
     * </p>
     * <p>
     * What this test does <em>not</em> do is exercise the air fallback of the storage, and saying so
     * is more useful than pretending otherwise. Minestom as pinned here has no state id below
     * {@code Block#statesCount()} that is not a block — its table is a {@code List#of}, which cannot
     * hold a null — so the fallback is unreachable and removing it would leave this file green. The
     * two cases below are the two that do exist: the highest id the table holds, which has to answer
     * with a block, and the first id past it, which has to fail out loud exactly as
     * {@code DynamicChunk} fails rather than being quietly reported as air.
     * </p>
     */
    @Test
    @DisplayName("answers every state id its table holds with a block and refuses one it does not")
    void testRawStateIdsAreNeverAnsweredWithNull() {
        final BlockStorage storage = storage();

        storage.section(4).blockPalette().set(1, 2, 3, Block.statesCount() - 1);
        assertNotNull(storage.getBlock(1, 2, 3, Block.Getter.Condition.NONE),
                "the highest state id of the table has to be a block");

        storage.section(4).blockPalette().set(1, 2, 3, Block.statesCount());
        assertThrows(IndexOutOfBoundsException.class,
                () -> storage.getBlock(1, 2, 3, Block.Getter.Condition.NONE),
                "a state id past the table has to fail rather than be reported as some block");
    }

    @Test
    @DisplayName("returns the biome that was written")
    void testBiomeRoundTrip() {
        final BlockStorage storage = storage();
        // Minestom keeps its Biomes constants package private, so the key is resolved through the
        // registry. Desert rather than plains because an empty biome palette reads back as id zero,
        // and a round trip through the id an empty palette already answers with proves nothing.
        final RegistryKey<Biome> desert = MinecraftServer.getBiomeRegistry().getKey(Key.key("minecraft:desert"));

        assertNotNull(desert, "the fixture needs a registered biome");
        assertNotEquals(0, MinecraftServer.getBiomeRegistry().getId(desert),
                "the fixture needs a biome whose id is not the one an empty palette reads back");

        storage.setBiome(4, 20, 8, desert);

        assertEquals(desert, storage.getBiome(4, 20, 8));
    }

    /**
     * An unregistered biome has to be rejected at the write.
     * <p>
     * {@code Registry#getId} answers a miss with {@code -1} and a palette takes that value like any
     * other, so a storage without this guard stores a biome that does not exist, raises the entry
     * count for it and hands it to the chunk packet.
     * </p>
     */
    @Test
    @DisplayName("refuses to write a biome that is not registered")
    void testUnregisteredBiomeIsRejected() {
        final BlockStorage storage = storage();
        final RegistryKey<Biome> unregistered = RegistryKey.unsafeOf("falco:not_a_biome");

        assertThrows(IllegalStateException.class, () -> storage.setBiome(4, 20, 8, unregistered));
        assertEquals(0, storage.section(5).biomePalette().count(),
                "a rejected biome must not have reached the palette");
    }

    @Test
    @DisplayName("refuses to read a biome id that is not registered")
    void testUnregisteredBiomeIdIsRejectedOnRead() {
        final BlockStorage storage = storage();
        final int unregisteredId = 30_000;

        assertNull(MinecraftServer.getBiomeRegistry().getKey(unregisteredId),
                "the fixture needs a biome id that is not registered");

        storage.section(5).biomePalette().set(1, 1, 2, unregisteredId);

        // NullPointerException rather than IllegalStateException because this is the exception
        // DynamicChunk#getBiome raises through Check.notNull, and stage 1 is behavioural parity.
        assertThrows(NullPointerException.class, () -> storage.getBiome(4, 20, 8));
    }

    /**
     * A column outside the chunk has to fail rather than be folded back into it.
     * <p>
     * {@link BlockStorage} states that {@code x} and {@code z} are already chunk-local, and this is
     * the test that makes the statement worth something. An implementation that masks instead would
     * accept a coordinate belonging to a chunk far away and write it into this one, which reads
     * exactly like a correct write from every other angle.
     * </p>
     * <p>
     * The exception type is deliberately not pinned any tighter than {@code RuntimeException}. It
     * comes out of whatever the layout uses underneath — an {@code IllegalArgumentException} from
     * the palette here, an {@code IndexOutOfBoundsException} from an array in a packed layout — and
     * the contract is that the storage refuses, not which class it refuses with.
     * </p>
     */
    @ParameterizedTest(name = "x = {0}, z = {1}")
    @CsvSource({"16, 0", "-1, 0", "0, 16", "0, -1", "48, 48"})
    @DisplayName("refuses a column outside the chunk instead of folding it back in")
    void testColumnOutsideTheChunkIsRejected(int x, int z) {
        final BlockStorage storage = storage();

        assertThrows(RuntimeException.class, () -> storage.setBlock(x, 20, z, Block.STONE));
    }
}
