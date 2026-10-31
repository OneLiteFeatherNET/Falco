package net.onelitefeather.falco.light;

import net.minestom.server.instance.block.Block;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the light of real blocks against what the vanilla game calculates for them.
 * <p>
 * Vanilla lowers light entering a block by {@code max(1, lightBlocked)}, and lets the shape of a
 * block stop light only for blocks flagged {@code lightUsesShape}. It checks the shape on both sides
 * of a face: the one light leaves through and the one it enters through. Sky light falls straight
 * down without losing a level only through blocks whose {@code lightBlocked} is zero.
 * </p>
 * <p>
 * The cases are the ones Minestom fixed in its own engine with pull request 3421: tinted glass,
 * carpets, leaves and water. Every expected value is the vanilla one.
 * </p>
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 0.1.0
 */
@ExtendWith(MicrotusExtension.class)
class VanillaLightOpacityTest {

    private static final int HEIGHT = 2 * LightNibbles.DIMENSION;
    private static final int LAYER = 20;

    private final BlockLightSource source = new MinestomBlockLightSource();

    /**
     * Supplies blocks which keep no face closed but lower light by one, so sky light under them
     * is no longer at the full level.
     *
     * @return the blocks with their names
     */
    private static Stream<Arguments> diffusingBlocks() {
        return Stream.of(
                Arguments.of("oak leaves", Block.OAK_LEAVES),
                Arguments.of("mangrove leaves", Block.MANGROVE_LEAVES),
                Arguments.of("pale oak leaves", Block.PALE_OAK_LEAVES),
                Arguments.of("water", Block.WATER),
                Arguments.of("waterlogged glass pane", Block.GLASS_PANE.withProperty("waterlogged", "true")),
                Arguments.of("ice", Block.ICE)
        );
    }

    /**
     * Builds the state ids of a column made of the given amount of air sections.
     *
     * @param sectionCount the amount of sections the column holds
     * @return the state ids of every section
     */
    private static List<int[]> airChunk(int sectionCount) {
        List<int[]> sections = new ArrayList<>(sectionCount);

        for (int i = 0; i < sectionCount; i++) {
            sections.add(new int[LightNibbles.BLOCK_COUNT]);
        }
        return sections;
    }

    /**
     * Places a block at a position of the column, addressing the section it belongs to.
     *
     * @param sections the state ids of every section
     * @param x        the x coordinate inside the chunk
     * @param y        the y coordinate inside the column
     * @param z        the z coordinate inside the chunk
     * @param block    the block to place
     */
    private static void place(List<int[]> sections, int x, int y, int z, Block block) {
        sections.get(y >> 4)[((y & 15) << 8) | (z << 4) | x] = block.stateId();
    }

    /**
     * Fills a whole layer of the column with a block.
     *
     * @param sections the state ids of every section
     * @param y        the y coordinate inside the column
     * @param block    the block to place
     */
    private static void fillLayer(List<int[]> sections, int y, Block block) {
        for (int x = 0; x < LightNibbles.DIMENSION; x++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                place(sections, x, y, z, block);
            }
        }
    }

    /**
     * Converts the state ids of a column into opacity tables through the registry of the server.
     *
     * @param sections the state ids of every section
     * @return the opacity table of every section
     */
    private List<SectionOpacity> tables(List<int[]> sections) {
        return sections.stream().map(states -> SectionOpacity.of(states, this.source)).toList();
    }

    /**
     * Asserts that the given state holds exactly what a sky light calculation from scratch would
     * produce.
     *
     * @param state    the incrementally updated state
     * @param sections the state ids of every section after the change
     */
    private void assertMatchesFullRecalculation(ChunkLightState state, List<int[]> sections) {
        ChunkLightState fresh = ChunkLightState.skyLight(tables(sections));

        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                    assertEquals(fresh.get(x, y, z), state.get(x, y, z), "mismatch at " + x + "/" + y + "/" + z);
                }
            }
        }
    }

    @Test
    void testTintedGlassBlocksBlockLight() {
        List<int[]> sections = airChunk(1);
        place(sections, 8, 8, 8, Block.GLOWSTONE);
        place(sections, 9, 8, 8, Block.TINTED_GLASS);

        ChunkLightState state = ChunkLightState.blockLight(tables(sections));

        assertEquals(0, state.get(9, 8, 8), "tinted glass lowers light by 15, so none is left inside it");
        assertEquals(11, state.get(10, 8, 8), "behind the glass light only arrives around it, four steps away");
    }

    @Test
    void testTintedGlassBlocksSkyLight() {
        List<int[]> sections = airChunk(2);
        fillLayer(sections, LAYER, Block.TINTED_GLASS);

        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        assertEquals(15, state.get(8, LAYER + 1, 8), "above the glass the sky is untouched");
        assertEquals(0, state.get(8, LAYER, 8), "tinted glass lowers sky light by 15");
        assertEquals(0, state.get(8, LAYER - 1, 8), "below a closed layer of tinted glass it is dark");
    }

    @Test
    void testPlacingTintedGlassLowersTheSkyLightBelowIt() {
        List<int[]> sections = airChunk(2);
        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        place(sections, 8, LAYER, 8, Block.TINTED_GLASS);
        state.update(tables(sections), 8, LAYER, 8);

        assertEquals(0, state.get(8, LAYER, 8), "tinted glass lowers sky light by 15");
        assertEquals(14, state.get(8, LAYER - 1, 8), "below the glass the column is only lit from the side");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("diffusingBlocks")
    void testADiffusingBlockLowersTheSkyLightFallingThroughIt(String name, Block block) {
        List<int[]> sections = airChunk(2);
        fillLayer(sections, LAYER, block);

        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        assertEquals(15, state.get(8, LAYER + 1, 8), "above the " + name + " the sky is untouched");
        assertEquals(14, state.get(8, LAYER, 8), name + " lowers sky light by one");
        assertEquals(13, state.get(8, LAYER - 1, 8), "light below level 15 loses a level per block, also downwards");
        assertEquals(4, state.get(8, LAYER - 10, 8), "the light keeps falling off below the layer");
    }

    @Test
    void testADimmingLayerAtTheTopOfTheChunkIsLit() {
        // There is no open position above the layer inside the chunk, so the sky has to be handed
        // to the layer itself.
        List<int[]> sections = airChunk(2);
        fillLayer(sections, HEIGHT - 1, Block.OAK_LEAVES);

        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        assertEquals(14, state.get(8, HEIGHT - 1, 8), "the top layer receives the sky one level weaker");
        assertEquals(13, state.get(8, HEIGHT - 2, 8), "and passes it on one level weaker again");
    }

    @Test
    void testPlacingADimmingBlockMatchesAFullRecalculation() {
        List<int[]> sections = airChunk(2);
        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        place(sections, 8, LAYER, 8, Block.OAK_LEAVES);
        state.update(tables(sections), 8, LAYER, 8);

        assertEquals(14, state.get(8, LAYER, 8), "the leaves receive the sky one level weaker");
        assertMatchesFullRecalculation(state, sections);
    }

    @Test
    void testRemovingADimmingBlockMatchesAFullRecalculation() {
        List<int[]> sections = airChunk(2);
        fillLayer(sections, LAYER, Block.WATER);
        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        place(sections, 8, LAYER, 8, Block.AIR);
        state.update(tables(sections), 8, LAYER, 8);

        assertEquals(15, state.get(8, 0, 8), "the opened column sees the sky down to the bottom");
        assertMatchesFullRecalculation(state, sections);
    }

    @Test
    void testPlacingADimmingBlockAtTheTopOfTheChunkMatchesAFullRecalculation() {
        List<int[]> sections = airChunk(2);
        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        place(sections, 8, HEIGHT - 1, 8, Block.OAK_LEAVES);
        state.update(tables(sections), 8, HEIGHT - 1, 8);

        assertEquals(14, state.get(8, HEIGHT - 1, 8), "the leaves receive the sky one level weaker");
        assertMatchesFullRecalculation(state, sections);
    }

    @Test
    void testPlacingADimmingBlockBelowAnotherOneMatchesAFullRecalculation() {
        // The changed block lies below the top of its column, so the top does not move.
        List<int[]> sections = airChunk(2);
        fillLayer(sections, LAYER, Block.OAK_LEAVES);
        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        place(sections, 8, LAYER - 5, 8, Block.WATER);
        state.update(tables(sections), 8, LAYER - 5, 8);

        assertMatchesFullRecalculation(state, sections);
    }

    @Test
    void testSkyLightFallsThroughACarpetUnchanged() {
        List<int[]> sections = airChunk(2);
        fillLayer(sections, LAYER, Block.WHITE_CARPET);

        ChunkLightState state = ChunkLightState.skyLight(tables(sections));

        assertEquals(15, state.get(8, LAYER, 8), "a carpet blocks no light");
        assertEquals(15, state.get(8, 0, 8), "the sky falls through a carpet down to the bottom of the column");
    }

    @Test
    @Disabled("Needs Block#lightUsesShape, which arrives with Minestom 26.3 (Minestom/Minestom#3421)")
    void testBlockLightRisesThroughACarpet() {
        // A carpet has a full bottom face, but vanilla does not let its shape stop light, so light
        // from below passes it like air.
        List<int[]> sections = airChunk(1);
        fillLayer(sections, 8, Block.WHITE_CARPET);
        place(sections, 8, 4, 8, Block.GLOWSTONE);

        ChunkLightState state = ChunkLightState.blockLight(tables(sections));

        assertEquals(11, state.get(8, 8, 8), "light enters the carpet from below");
        assertEquals(10, state.get(8, 9, 8), "and leaves it through the top");
    }

    @Test
    @Disabled("Needs Block#lightUsesShape, which arrives with Minestom 26.3 (Minestom/Minestom#3421)")
    void testLightCannotLeaveATopSlabThroughItsTopFace() {
        // A closed layer of top slabs with one hole, the hole capped by stone. Light from below
        // reaches the hole and enters the slabs through their open sides, but a slab uses its shape
        // for light, so nothing leaves through its full top face and everything above stays dark.
        List<int[]> sections = airChunk(1);
        fillLayer(sections, 8, Block.OAK_SLAB.withProperty("type", "top"));
        place(sections, 8, 8, 8, Block.AIR);
        place(sections, 8, 9, 8, Block.STONE);
        place(sections, 8, 7, 8, Block.GLOWSTONE);

        ChunkLightState state = ChunkLightState.blockLight(tables(sections));

        assertEquals(14, state.get(8, 8, 8), "the hole is lit from below");
        assertEquals(13, state.get(9, 8, 8), "the slab next to the hole is lit through its side");

        for (int y = 9; y < LightNibbles.DIMENSION; y++) {
            for (int z = 0; z < LightNibbles.DIMENSION; z++) {
                for (int x = 0; x < LightNibbles.DIMENSION; x++) {
                    assertEquals(0, state.get(x, y, z), "light leaked through a slab top at " + x + "/" + y + "/" + z);
                }
            }
        }
    }
}
