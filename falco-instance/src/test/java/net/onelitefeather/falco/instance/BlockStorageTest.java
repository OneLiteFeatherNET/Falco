package net.onelitefeather.falco.instance;

import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins down the contract of {@link BlockStorage} on every implementation of it, which since stage 2
 * means {@link SectionBlockStorage} and {@link LazySectionBlockStorage} running the same cases from
 * the same file.
 * <p>
 * Everything here therefore goes through the interface and never through a section, with two
 * deliberate exceptions. The tests that pin the height arithmetic and the unknown state read
 * through {@link BlockStorage#section(int)}, because those are precisely the properties an
 * assertion phrased in terms of {@code getBlock} alone cannot see. The view tests read through
 * {@link BlockStorage#view(int)} and {@link BlockStorage#views()} for the same reason: whether a
 * view is the live section or a copy of it is invisible to {@code getBlock}, which would answer
 * from the storage either way.
 * </p>
 *
 * <h2>Why the layout is a parameter rather than a second file</h2>
 * <p>
 * A lazy layout is only worth having if it is indistinguishable from the eager one through this
 * interface, so the cases that say what the interface promises must not be rewritten for it — a
 * second file would drift, and the first thing to drift would be exactly the guard the second layout
 * is most likely to lose. {@link #storages()} therefore names the layouts and every case takes a
 * factory rather than calling one. What the lazy layout does <em>beyond</em> the contract lives in
 * {@code LazySectionBlockStorageTest}, because none of it can be phrased about the eager one.
 * </p>
 * <p>
 * Three cases stayed behind as plain tests over {@link SectionBlockStorage}, and the reason is the
 * same for all three: they are statements about the eager layout rather than about the interface.
 * A storage that shares nothing, a view that is the section itself and a section list that exists
 * without being asked for are all false for the lazy layout by construction, and running them
 * against it would either fail or — worse — force it to materialise everything and quietly assert
 * the opposite of what stage 2 is about.
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
 * @version 3.0.0
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

    /**
     * The layouts every case of this file runs against.
     *
     * @return the name of each layout and a factory for an empty storage of it
     */
    static Stream<Arguments> storages() {
        return Stream.of(
                Arguments.of("eager",
                        (Supplier<BlockStorage>) () -> new SectionBlockStorage(MIN_SECTION, SECTIONS)),
                Arguments.of("lazy",
                        (Supplier<BlockStorage>) () -> new LazySectionBlockStorage(MIN_SECTION, SECTIONS)));
    }

    /**
     * The heights that pin the section arithmetic, once per layout.
     * <p>
     * Built as a product rather than written out, so that adding a layout cannot leave a height
     * untested for it, and adding a height cannot leave a layout untested for that height.
     * </p>
     *
     * @return the layout, its factory, a world height and the section that height belongs to
     */
    static Stream<Arguments> heights() {
        final int[][] cases = {{-64, 0}, {-49, 0}, {-48, 1}, {-1, 3}, {0, 4}, {127, 11}, {300, 22}, {319, 23}};

        return product(cases);
    }

    /**
     * The columns outside the chunk that have to be refused, once per layout.
     *
     * @return the layout, its factory and a column that does not belong to the chunk
     */
    static Stream<Arguments> columns() {
        final int[][] cases = {{16, 0}, {-1, 0}, {0, 16}, {0, -1}, {48, 48}};

        return product(cases);
    }

    /**
     * Combines every layout with every pair of a case table.
     *
     * @param cases the pairs, each of which becomes one case per layout
     * @return the layout name, its factory and the two values of the pair
     */
    private static Stream<Arguments> product(int[][] cases) {
        final List<Arguments> combined = new ArrayList<>();

        storages().forEach(layout -> {
            for (int[] values : cases) {
                combined.add(Arguments.of(layout.get()[0], layout.get()[1], values[0], values[1]));
            }
        });
        return combined.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    @DisplayName("returns air for a position nothing was written to")
    void testEmptyReadsAir(String name, Supplier<BlockStorage> factory) {
        assertEquals(Block.AIR, factory.get().getBlock(0, 0, 0, Block.Getter.Condition.NONE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    @DisplayName("returns what was written, at the position it was written to")
    void testWriteThenRead(String name, Supplier<BlockStorage> factory) {
        final BlockStorage storage = factory.get();

        storage.setBlock(1, 2, 3, Block.STONE);

        assertEquals(Block.STONE, storage.getBlock(1, 2, 3, Block.Getter.Condition.NONE));
        assertEquals(Block.AIR, storage.getBlock(1, 2, 4, Block.Getter.Condition.NONE));
    }

    @ParameterizedTest(name = "{0}: y = {2} belongs to section {3}")
    @MethodSource("heights")
    @DisplayName("writes a height into the section that height belongs to, and into no other")
    void testHeightSelectsItsSection(String name, Supplier<BlockStorage> factory, int y, int expectedSection) {
        final BlockStorage storage = factory.get();

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    @DisplayName("spans one section per section of the chunk")
    void testSectionCount(String name, Supplier<BlockStorage> factory) {
        assertEquals(SECTIONS, factory.get().sectionCount());
    }

    /**
     * The eager storage holds a section list before anyone asks for one.
     * <p>
     * Not parameterised, and the omission is the point rather than an oversight:
     * {@link BlockStorage#sections()} is the boundary method, so asking the lazy storage for its list
     * materialises every section of it. A case that asserted the size of that list for both layouts
     * would be asserting that the lazy one gives up its whole saving on being asked a question about
     * its size. What the lazy layout does at that boundary is pinned in
     * {@code LazySectionBlockStorageTest} instead, where the materialisation is the subject rather
     * than a side effect.
     * </p>
     */
    @Test
    @DisplayName("holds one section per section of the chunk when it holds them eagerly")
    void testEagerStorageHoldsEverySection() {
        assertEquals(SECTIONS, new SectionBlockStorage(MIN_SECTION, SECTIONS).sections().size());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    @DisplayName("copies without sharing storage with the original")
    void testCopyIsIndependent(String name, Supplier<BlockStorage> factory) {
        final BlockStorage original = factory.get();
        original.setBlock(1, 2, 3, Block.STONE);

        final BlockStorage copy = original.copy();
        copy.setBlock(1, 2, 3, Block.DIRT);

        assertNotSame(original, copy);
        assertEquals(Block.STONE, original.getBlock(1, 2, 3, Block.Getter.Condition.NONE));
        assertEquals(Block.DIRT, copy.getBlock(1, 2, 3, Block.Getter.Condition.NONE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    @DisplayName("copies the height of every block along with it")
    void testCopyKeepsTheHeights(String name, Supplier<BlockStorage> factory) {
        final BlockStorage original = factory.get();
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    @DisplayName("reads air everywhere after being cleared")
    void testClear(String name, Supplier<BlockStorage> factory) {
        final BlockStorage storage = factory.get();
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
    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    @DisplayName("answers every state id its table holds with a block and refuses one it does not")
    void testRawStateIdsAreNeverAnsweredWithNull(String name, Supplier<BlockStorage> factory) {
        final BlockStorage storage = factory.get();

        storage.section(4).blockPalette().set(1, 2, 3, Block.statesCount() - 1);
        assertNotNull(storage.getBlock(1, 2, 3, Block.Getter.Condition.NONE),
                "the highest state id of the table has to be a block");

        storage.section(4).blockPalette().set(1, 2, 3, Block.statesCount());
        assertThrows(IndexOutOfBoundsException.class,
                () -> storage.getBlock(1, 2, 3, Block.Getter.Condition.NONE),
                "a state id past the table has to fail rather than be reported as some block");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    @DisplayName("returns the biome that was written")
    void testBiomeRoundTrip(String name, Supplier<BlockStorage> factory) {
        final BlockStorage storage = factory.get();
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
    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    @DisplayName("refuses to write a biome that is not registered")
    void testUnregisteredBiomeIsRejected(String name, Supplier<BlockStorage> factory) {
        final BlockStorage storage = factory.get();
        final RegistryKey<Biome> unregistered = RegistryKey.unsafeOf("falco:not_a_biome");

        assertThrows(IllegalStateException.class, () -> storage.setBiome(4, 20, 8, unregistered));
        assertEquals(0, storage.section(5).biomePalette().count(),
                "a rejected biome must not have reached the palette");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    @DisplayName("refuses to read a biome id that is not registered")
    void testUnregisteredBiomeIdIsRejectedOnRead(String name, Supplier<BlockStorage> factory) {
        final BlockStorage storage = factory.get();
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
     * <p>
     * The block written is stone rather than air on purpose. A lazy layout skips a write of the
     * state its shared section already holds without ever reaching a palette, so a case phrased with
     * air would be refused by the eager storage and silently accepted by the lazy one — and would be
     * reporting the skip rather than the missing guard.
     * </p>
     */
    @ParameterizedTest(name = "{0}: x = {2}, z = {3}")
    @MethodSource("columns")
    @DisplayName("refuses a column outside the chunk instead of folding it back in")
    void testColumnOutsideTheChunkIsRejected(String name, Supplier<BlockStorage> factory, int x, int z) {
        final BlockStorage storage = factory.get();

        assertThrows(RuntimeException.class, () -> storage.setBlock(x, 20, z, Block.STONE));
    }

    @Test
    @DisplayName("reports every section as materialised when it holds one of its own")
    void testEagerStorageSharesNothing() {
        final BlockStorage storage = new SectionBlockStorage(MIN_SECTION, SECTIONS);

        assertEquals(SECTIONS, storage.materialisedSections());
        for (int section = 0; section < SECTIONS; section++) {
            assertFalse(storage.shared(section),
                    "section " + section + " of an eager storage cannot be shared");
        }
    }

    @Test
    @DisplayName("hands out the same section through the view as through the boundary")
    void testViewAndSectionAgree() {
        final BlockStorage storage = new SectionBlockStorage(MIN_SECTION, SECTIONS);

        storage.setBlock(1, 2, 3, Block.STONE);

        assertSame(storage.section(0), storage.view(0),
                "an eager storage has nothing to materialise, so the two accessors are one");
        assertEquals(SECTIONS, storage.views().size());
        for (int section = 0; section < SECTIONS; section++) {
            assertSame(storage.sections().get(section), storage.views().get(section),
                    "the view of section " + section + " has to be the section itself");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    @DisplayName("keeps the view in step with what was written after it was handed out")
    void testViewFollowsLaterWrites(String name, Supplier<BlockStorage> factory) {
        final BlockStorage storage = factory.get();
        final List<Section> views = storage.views();

        // y = 2 lands in section 4 of this fixture (MIN_SECTION = -4), the same section
        // testHeightSelectsItsSection pins for y = 0. See the concern in task-1-report.md: the
        // brief's version of this test read views.get(0), which is only the section a write to
        // y = 2 would land in for a storage whose bottom section is 0, not -4.
        storage.setBlock(1, 2, 3, Block.STONE);

        assertEquals(Block.STONE.stateId(), views.get(4).blockPalette().get(1, 2, 3),
                "a view that was taken before a write has to show the write, or a caller which "
                        + "holds one is reading a chunk that no longer exists");
    }
}
