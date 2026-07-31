package net.onelitefeather.falco.benchmark.support;

/**
 * The {@link BenchmarkConstants} class holds the layout constants the benchmarks share.
 * <p>
 * The values mirror the constants of the Minestom palette which the loader uses at runtime, but
 * they are repeated here on purpose. Pulling Minestom onto the benchmark classpath would drag a
 * registry and a server lifecycle into a harness that is supposed to measure the code of this
 * library alone.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
public final class BenchmarkConstants {

    /**
     * The amount of block entries a single section holds.
     * This mirrors {@code Palette.BLOCK_DIMENSION} cubed.
     */
    public static final int BLOCK_ENTRIES = 16 * 16 * 16;

    /**
     * The amount of biome entries a single section holds.
     * This mirrors {@code Palette.BIOME_DIMENSION} cubed.
     */
    public static final int BIOME_ENTRIES = 4 * 4 * 4;

    /**
     * The smallest amount of bits a block palette entry occupies.
     * This mirrors {@code Palette.BLOCK_PALETTE_MIN_BITS}.
     */
    public static final int BLOCK_PALETTE_MIN_BITS = 4;

    /**
     * The smallest amount of bits a biome palette entry occupies.
     * This mirrors {@code Palette.BIOME_PALETTE_MIN_BITS}.
     */
    public static final int BIOME_PALETTE_MIN_BITS = 1;

    /**
     * The amount of sections a chunk of a full height overworld holds.
     */
    public static final int OVERWORLD_SECTIONS = 24;

    /**
     * The seed every generator uses so two runs of the same benchmark see the same input.
     */
    public static final long SEED = 0x5DEECE66DL;

    /**
     * Blocks the creation of an instance because the class only holds constants.
     */
    private BenchmarkConstants() {
    }
}
