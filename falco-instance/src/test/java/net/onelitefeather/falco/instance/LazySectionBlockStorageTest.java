package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * @version 1.2.0
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

    /**
     * A read of a shared slot answers air, materialises nothing, and does not reach a palette.
     * <p>
     * The first two are asserted by counting; the third needs an observation, because a palette read
     * of the shared section has no side effect and answers air as well. {@code Palette#get} validates
     * its coordinates <em>before</em> it takes its own {@code bitsPerEntry == 0} shortcut
     * ({@code PaletteImpl#get} calls {@code validateCoord} on its first line), so a coordinate the
     * palette rejects is exactly the input that separates a read which reached one from a read which
     * did not: the eager layout throws for {@code x = 16}, the lazy one answers air. Without the
     * shortcut in {@link LazySectionBlockStorage#getBlock(int, int, int, Block.Getter.Condition)}
     * this case throws instead of passing, which is the whole reason the pair of assertions is here —
     * every other read in this file is green with the shortcut and without it.
     * </p>
     * <p>
     * The divergence is asserted, not endorsed. {@link BlockStorage} requires the caller to have
     * folded the coordinate into the chunk already, so {@code x = 16} is a caller bug under both
     * layouts and neither answer is more correct than the other; what is pinned is that the lazy
     * layout does not pay a palette call to find that out. A later change that decides the two
     * layouts must reject it alike belongs in the contract test and has to fail here first.
     * </p>
     */
    @Test
    @DisplayName("answers a read of a shared section without touching a palette")
    void testReadingASharedSectionDoesNotMaterialise() {
        final LazySectionBlockStorage storage = storage();

        for (int y = -64; y < 320; y += 16) {
            assertEquals(Block.AIR, storage.getBlock(0, y, 0, Block.Getter.Condition.NONE));
        }
        assertEquals(0, storage.materialisedSections());

        assertThrows(IllegalArgumentException.class,
                () -> new SectionBlockStorage(MIN_SECTION, SECTIONS)
                        .getBlock(16, 20, 0, Block.Getter.Condition.NONE),
                "a read that reaches a palette is refused an x of 16; if Minestom ever stopped "
                        + "refusing it, the assertion below would no longer prove anything");
        assertEquals(Block.AIR, storage.getBlock(16, 20, 0, Block.Getter.Condition.NONE),
                "the shared slot answers without asking its palette, so the coordinate the palette "
                        + "would have refused never reaches one");
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

    /**
     * Holds the one step of this class that no chunk lock covers.
     * <p>
     * Every other case in this file runs on one thread, and on one thread a materialisation that
     * reads a slot, allocates and stores is indistinguishable from one that publishes with a compare
     * and exchange. The difference is only visible against a second thread, and that second thread is
     * not hypothetical: {@code Instance#getBlockLight}, {@code Instance#getSkyLight} and
     * {@code Instance#invalidateSection} all reach {@link BlockStorage#section(int)} through
     * {@code Chunk#getSection} or {@code Chunk#getSectionAt} while holding no chunk lock at all, so a
     * light query on any thread is exactly the reader modelled here.
     * </p>
     * <p>
     * What the assertion catches is a lost write and not a torn one. The reader below writes nothing;
     * it only asks for the section, which is the call that used to allocate one and store it over
     * whatever the writer had just put there. The block the writer stored into the overwritten
     * section is then unreachable, and — this is what makes it worth a case of its own — nothing
     * fails: no exception, no log, and the read answers air through the shortcut for a shared slot.
     * </p>
     * <p>
     * A stress case rather than a scheduled one, because the window it aims at is the handful of
     * instructions between the read of a slot and the store into it, and nothing in this class can be
     * paused inside it. The two threads are aligned at every round and both walk the same twenty-four
     * slots in the same order, which is what makes a round a genuine collision attempt rather than a
     * coin toss. With the compare and exchange replaced by the plain
     * {@code this.sections[index] = created} it used to be, this case failed in all five runs of the
     * mutation, in rounds {@code 3, 5, 0, 3} and {@code 3} — so the four thousand rounds are three
     * orders of magnitude more than the defect needs, and they are what makes the case a statement
     * rather than a coin toss. It says nothing about how likely the defect is in production, where
     * the two threads are not aligned by a barrier. The whole case costs under two seconds.
     * </p>
     *
     * @throws InterruptedException if the test thread is interrupted while joining the two workers
     */
    @Test
    @DisplayName("does not lose a written block to a reader that materialises the same slot")
    void testMaterialisationSurvivesAConcurrentReader() throws InterruptedException {
        final int rounds = 4_000;
        final CyclicBarrier start = new CyclicBarrier(2);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int round = 0; round < rounds && failure.get() == null; round++) {
            final LazySectionBlockStorage storage = storage();
            // The reader is the lock-free side: it only asks for sections, exactly as a light query
            // does, and every section it hands back it may have allocated itself.
            final Thread reader = new Thread(() -> {
                await(start, failure);
                for (int section = 0; section < SECTIONS; section++) {
                    storage.section(section);
                }
            }, "lazy-storage-reader");
            final Thread writer = new Thread(() -> {
                await(start, failure);
                for (int section = 0; section < SECTIONS; section++) {
                    storage.setBlock(0, (MIN_SECTION + section) * 16, 0, Block.STONE);
                }
            }, "lazy-storage-writer");

            reader.start();
            writer.start();
            writer.join();
            reader.join();

            for (int section = 0; section < SECTIONS; section++) {
                assertEquals(Block.STONE,
                        storage.getBlock(0, (MIN_SECTION + section) * 16, 0, Block.Getter.Condition.NONE),
                        "round " + round + ": the block written into section " + section
                                + " was stored into a section the reader then replaced");
            }
        }
        assertNull(failure.get(), "neither worker may fail on anything but the assertion above");
    }

    /**
     * Waits at the barrier and records what went wrong instead of throwing into a worker thread.
     *
     * @param barrier the barrier both workers meet at before every round
     * @param failure where a failure of a worker is recorded for the test thread to see
     */
    private static void await(CyclicBarrier barrier, AtomicReference<Throwable> failure) {
        try {
            barrier.await();
        } catch (InterruptedException | BrokenBarrierException throwable) {
            failure.compareAndSet(null, throwable);
            Thread.currentThread().interrupt();
        }
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
