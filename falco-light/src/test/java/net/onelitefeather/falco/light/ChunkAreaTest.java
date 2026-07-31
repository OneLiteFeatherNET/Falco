package net.onelitefeather.falco.light;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down how a set of dirty chunks is cut into the areas which are lit together.
 * <p>
 * The grouping is pure coordinate arithmetic and therefore needs neither a server nor a chunk. That
 * is the reason it lives in its own type: the two rules it has to obey — a corner is not a border,
 * and an area has an upper bound — are the ones a reader of the scheduler would otherwise have to
 * reconstruct from a flood fill written in the middle of a tick handler.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
class ChunkAreaTest {

    @Test
    void testTouchingChunksFormOneArea() {
        List<List<ChunkArea>> areas = ChunkArea.group(
                List.of(new ChunkArea(0, 0), new ChunkArea(1, 0), new ChunkArea(1, 1)), 16);

        assertEquals(1, areas.size());
        assertEquals(3, areas.getFirst().size());
    }

    @Test
    void testSeparateChunksFormSeparateAreas() {
        List<List<ChunkArea>> areas = ChunkArea.group(
                List.of(new ChunkArea(0, 0), new ChunkArea(10, 10)), 16);

        assertEquals(2, areas.size());
    }

    @Test
    void testDiagonalNeighboursDoNotJoin() {
        // Light crosses a face, not a corner. Two chunks meeting only at a corner do not
        // exchange a border and therefore do not have to be computed together.
        List<List<ChunkArea>> areas = ChunkArea.group(
                List.of(new ChunkArea(0, 0), new ChunkArea(1, 1)), 16);

        assertEquals(2, areas.size());
    }

    @Test
    void testAnAreaIsSplitAtTheCap() {
        List<ChunkArea> row = new ArrayList<>();

        for (int x = 0; x < 10; x++) {
            row.add(new ChunkArea(x, 0));
        }
        List<List<ChunkArea>> areas = ChunkArea.group(row, 4);

        assertEquals(3, areas.size());
        assertEquals(10, areas.stream().mapToInt(List::size).sum());
    }

    @Test
    void testNoAreaExceedsTheCap() {
        List<ChunkArea> block = new ArrayList<>();

        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                block.add(new ChunkArea(x, z));
            }
        }

        for (List<ChunkArea> area : ChunkArea.group(block, 5)) {
            assertTrue(area.size() <= 5, "the cap is what keeps an area from allocating hundreds of megabytes");
        }
    }

    @Test
    void testNoChunkIsLostOrDuplicated() {
        List<ChunkArea> dirty = new ArrayList<>();

        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                dirty.add(new ChunkArea(x, z));
            }
        }
        List<ChunkArea> flattened = ChunkArea.group(dirty, 5).stream().flatMap(List::stream).toList();

        assertEquals(dirty.size(), flattened.size());
        assertEquals(Set.copyOf(dirty), Set.copyOf(flattened));
    }

    @Test
    void testAnEmptyInputProducesNoAreas() {
        assertTrue(ChunkArea.group(List.of(), 16).isEmpty());
    }

    @Test
    void testACapBelowOneIsRejected() {
        // Without a positive cap the flood fill would produce empty areas forever, so the mistake
        // has to be an error at the call rather than a hang inside it.
        assertThrows(IllegalArgumentException.class, () -> ChunkArea.group(List.of(new ChunkArea(0, 0)), 0));
    }

    @Test
    void testEveryFaceNeighbourJoinsTheSameArea() {
        List<List<ChunkArea>> areas = ChunkArea.group(
                List.of(
                        new ChunkArea(0, 0),
                        new ChunkArea(1, 0),
                        new ChunkArea(-1, 0),
                        new ChunkArea(0, 1),
                        new ChunkArea(0, -1)
                ), 16);

        assertEquals(1, areas.size());
        assertEquals(5, areas.getFirst().size());
    }
}
