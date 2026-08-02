package net.onelitefeather.falco.instance;

import net.minestom.server.instance.palette.Palette;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds {@link PaletteCompaction} to the one promise that matters: a palette it packs has to come out
 * exactly as {@code Palette#optimize(Optimization.SIZE)} would have left it.
 * <p>
 * The class is a performance guard, and a performance guard has a dangerous failure mode that no
 * timing can see. Skipping work that would have achieved nothing is invisible in the result; skipping
 * work that would have narrowed a palette is also invisible in the result, unless something compares
 * the two. That comparison is what every case here does: the same content is packed once through the
 * guard and once through the unconditional call, and both the width and the content have to agree.
 * A threshold that is off by one, a probe that samples the wrong positions or a rule that forgets the
 * {@code fill} branch of {@code optimize} all show up as a disagreement rather than as a slow server.
 * </p>
 *
 * <h2>Why the fixtures are written through setAll</h2>
 * <p>
 * {@code PaletteImpl#setAll} is the method a generated section comes out of, and it decides the width
 * of the palette without looking at the content: a supplier that answered one constant value goes to
 * {@code fill}, and every other supplier goes to {@code makeDirect}, so a section holding two states
 * and a section holding a thousand are both fifteen bits wide. That is the input the commit is handed
 * and therefore the input the guard has to be right about. The two cases that build their fixture
 * through {@code Palette#set} cover the other shape, an indirect palette grown one write at a time,
 * which is what a chunk loader and every block write leave behind.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@DisplayName("What the palette compaction may skip")
class PaletteCompactionTest {

    /**
     * Builds a block palette the way {@code UnitModifier#setAllRelative} leaves one.
     *
     * @param distinctStates how many distinct values the palette ends up holding
     * @return the palette
     */
    private static Palette generated(int distinctStates) {
        final Palette palette = Palette.blocks();

        palette.setAll((x, y, z) -> 1 + (x + (y << 4) + (z << 8)) % distinctStates);
        return palette;
    }

    /**
     * Demands that two palettes hold the same value at every position.
     * <p>
     * The walk is written out rather than left to {@code Palette#compare}, which cannot answer this
     * question here. {@code PaletteImpl#compare} opens on {@code palette.count != this.count}, and the
     * {@code count} field carries the stored value in the single value mode and the number of non-air
     * entries in every other mode, so a palette that was just collapsed by {@code fill} is reported as
     * different from the indirect palette it was collapsed from. Comparing a packed palette against
     * its source is exactly the case that runs into it.
     * </p>
     *
     * @param expected the palette the content is taken from
     * @param actual   the palette the content is compared against
     * @param message  what a difference would mean
     */
    private static void assertSameContent(Palette expected, Palette actual, String message) {
        final int dimension = expected.dimension();

        for (int x = 0; x < dimension; x++) {
            for (int y = 0; y < dimension; y++) {
                for (int z = 0; z < dimension; z++) {
                    assertEquals(expected.get(x, y, z), actual.get(x, y, z),
                            message + " at " + x + ", " + y + ", " + z);
                }
            }
        }
    }

    /**
     * Packs a palette twice, once through the guard and once unconditionally, and demands the same
     * outcome from both.
     *
     * @param fixture the palette to pack, which is cloned and left alone
     * @return the width both routes ended at
     */
    private static int packedWidthOf(Palette fixture) {
        final Palette guarded = fixture.clone();
        final Palette unconditional = fixture.clone();

        PaletteCompaction.packBlocks(guarded);
        unconditional.optimize(Palette.Optimization.SIZE);

        assertEquals(unconditional.bitsPerEntry(), guarded.bitsPerEntry(),
                "the guard skipped an optimisation that would have changed the width, which is the one "
                        + "way it can be wrong that no benchmark would notice");
        assertSameContent(fixture, guarded, "packing changed the content of the palette");
        assertSameContent(unconditional, guarded, "the two routes disagree about the content");
        return guarded.bitsPerEntry();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 16, 17, 200, 255, 256, 257, 300, 1024, 4096})
    @DisplayName("a generated palette is packed exactly as the unconditional call would pack it")
    void testTheGuardedPackMatchesTheUnconditionalOne(int distinctStates) {
        packedWidthOf(generated(distinctStates));
    }

    @Test
    @DisplayName("the widths the two sides of the threshold end at are the ones the palette source promises")
    void testTheWidthsAroundTheThreshold() {
        assertEquals(0, packedWidthOf(generated(1)),
                "one state never reaches makeDirect: Palette#setAll sends a constant supplier to fill");
        assertEquals(Palette.BLOCK_PALETTE_MIN_BITS, packedWidthOf(generated(2)),
                "two states fit in the minimum width of four bits");
        assertEquals(Palette.BLOCK_PALETTE_MAX_BITS, packedWidthOf(generated(256)),
                "256 states are exactly what an indirect block palette can index, and this is the case "
                        + "a threshold that is off by one gets wrong");
        assertEquals(Palette.BLOCK_PALETTE_DIRECT_BITS, packedWidthOf(generated(257)),
                "one state more than the indirect mode can index, so the palette has to stay direct - "
                        + "and this is the case the guard exists to stop paying for");
    }

    @Test
    @DisplayName("the guard refuses a palette that is provably past the indirect ceiling")
    void testTheGuardSkipsWhatCannotBeNarrowed() {
        assertFalse(PaletteCompaction.canNarrow(generated(1024),
                        Palette.BLOCK_PALETTE_MIN_BITS, Palette.BLOCK_PALETTE_MAX_BITS),
                "1024 distinct states in 4096 entries: the probe reaches 257 of them long before its "
                        + "sample is exhausted, and downsizeWithPalette could not have stored them");
        assertTrue(PaletteCompaction.canNarrow(generated(256),
                        Palette.BLOCK_PALETTE_MIN_BITS, Palette.BLOCK_PALETTE_MAX_BITS),
                "256 distinct states still fit, so this one has to be attempted");
    }

    @Test
    @DisplayName("the guard refuses a palette that is already in the single value mode")
    void testTheGuardSkipsASingleValuePalette() {
        final Palette palette = Palette.blocks();

        palette.fill(1);

        assertFalse(PaletteCompaction.canNarrow(palette,
                        Palette.BLOCK_PALETTE_MIN_BITS, Palette.BLOCK_PALETTE_MAX_BITS),
                "optimize returns on its opening bitsPerEntry == 0, so there is nothing to attempt");
    }

    @Test
    @DisplayName("a palette grown by single writes is packed as the unconditional call packs it")
    void testAPaletteGrownByWritesIsPackedTheSameWay() {
        final Palette twoStates = Palette.blocks();
        final Palette oneState = Palette.blocks();

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    twoStates.set(x, y, z, (x + y + z) % 2 == 0 ? 1 : 2);
                    oneState.set(x, y, z, 1);
                }
            }
        }

        assertEquals(Palette.BLOCK_PALETTE_MIN_BITS, twoStates.bitsPerEntry(),
                "Palette#set grows the palette to what it needs, which for two states is the minimum "
                        + "width; there is nothing left for optimize to take");
        assertEquals(Palette.BLOCK_PALETTE_MIN_BITS, packedWidthOf(twoStates));
        assertEquals(0, packedWidthOf(oneState),
                "one distinct value collapses to the single value mode even at the minimum width, "
                        + "through the fill branch of optimize rather than through a downsize. A guard "
                        + "which refused every palette that is already at the minimum width would "
                        + "leave this palette holding 2048 bytes for one value");
    }

    @Test
    @DisplayName("a biome palette is packed as the unconditional call packs it")
    void testABiomePaletteIsPackedTheSameWay() {
        final Palette guarded = Palette.biomes();
        final Palette unconditional = Palette.biomes();

        guarded.setAll((x, y, z) -> (x + y + z) % 2 == 0 ? 1 : 2);
        unconditional.setAll((x, y, z) -> (x + y + z) % 2 == 0 ? 1 : 2);

        PaletteCompaction.packBiomes(guarded);
        unconditional.optimize(Palette.Optimization.SIZE);

        assertEquals(unconditional.bitsPerEntry(), guarded.bitsPerEntry());
        assertSameContent(unconditional, guarded, "the two routes disagree about the content");
        assertEquals(Palette.BIOME_PALETTE_MIN_BITS, guarded.bitsPerEntry(),
                "two biomes need one bit, the minimum width of a biome palette");
    }
}
