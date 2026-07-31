package net.onelitefeather.falco.benchmark.support;

import java.util.Random;

/**
 * The {@link ChunkColumn} record holds the raw arrays of a chunk in the shape the save path reads
 * them from a Minestom section.
 * <p>
 * The loader copies exactly these arrays while it holds the read lock of the chunk and converts
 * them afterwards. Modelling the chunk as plain arrays lets the benchmark reproduce that split
 * without a running server.
 * </p>
 *
 * @param blockStates the state id of every block of every section
 * @param biomes      the biome id of every biome cell of every section
 * @param skyLight    the stored sky light of every section
 * @param blockLight  the stored block light of every section
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
public record ChunkColumn(int[][] blockStates, int[][] biomes, byte[][] skyLight, byte[][] blockLight) {

    private static final int LIGHT_BYTES = BenchmarkConstants.BLOCK_ENTRIES / 2;

    /**
     * Builds a chunk whose sections hold the given amount of distinct block states.
     *
     * @param sectionCount   the amount of sections the chunk holds
     * @param distinctStates the amount of distinct block states a single section holds
     * @return the created chunk
     * @throws IllegalArgumentException if the chunk holds no section
     */
    public static ChunkColumn of(int sectionCount, int distinctStates) {
        if (sectionCount <= 0) {
            throw new IllegalArgumentException("A chunk has to hold at least one section but held " + sectionCount);
        }

        int[][] blockStates = new int[sectionCount][];
        int[][] biomes = new int[sectionCount][];
        byte[][] skyLight = new byte[sectionCount][];
        byte[][] blockLight = new byte[sectionCount][];
        Random random = new Random(BenchmarkConstants.SEED);

        for (int section = 0; section < sectionCount; section++) {
            // Every section starts its states at its own offset so the whole chunk holds a
            // realistic amount of distinct states instead of repeating one palette everywhere.
            blockStates[section] = SectionStates.distinct(
                    BenchmarkConstants.BLOCK_ENTRIES, distinctStates, 1 + section * distinctStates
            );
            biomes[section] = SectionStates.distinct(BenchmarkConstants.BIOME_ENTRIES, Math.min(distinctStates, 4), 1);
            skyLight[section] = new byte[LIGHT_BYTES];
            blockLight[section] = new byte[LIGHT_BYTES];
            random.nextBytes(skyLight[section]);
        }
        return new ChunkColumn(blockStates, biomes, skyLight, blockLight);
    }

    /**
     * Returns the amount of sections the chunk holds.
     *
     * @return the amount of sections
     */
    public int sectionCount() {
        return this.blockStates.length;
    }

    /**
     * Copies every array of the chunk.
     * <p>
     * This is the work the loader performs while it holds the read lock of the chunk. Everything
     * that follows runs on the copy and therefore outside of that lock.
     * </p>
     *
     * @return a chunk which shares no array with this one
     */
    public ChunkColumn copy() {
        int sectionCount = sectionCount();
        int[][] states = new int[sectionCount][];
        int[][] biomeCopy = new int[sectionCount][];
        byte[][] sky = new byte[sectionCount][];
        byte[][] block = new byte[sectionCount][];

        for (int section = 0; section < sectionCount; section++) {
            states[section] = this.blockStates[section].clone();
            biomeCopy[section] = this.biomes[section].clone();
            sky[section] = this.skyLight[section].clone();
            block[section] = this.blockLight[section].clone();
        }
        return new ChunkColumn(states, biomeCopy, sky, block);
    }
}
