package net.onelitefeather.falco.benchmark.support;

import java.util.Random;

/**
 * The {@link SectionStates} class generates the block state arrays which the benchmarks feed into
 * the codec and into the light engine.
 * <p>
 * Every generator is deterministic. A benchmark which is rerun after a code change has to see the
 * exact same input, otherwise the difference between two runs describes the input and not the
 * change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
public final class SectionStates {

    /**
     * Blocks the creation of an instance because the class only holds generators.
     */
    private SectionStates() {
    }

    /**
     * Builds a section in which every entry carries the same state.
     * This is the shape of the vast majority of the sections of a real world, which are either
     * completely air or completely stone.
     *
     * @param entryCount the amount of entries the section holds
     * @param stateId    the state every entry carries
     * @return the created section
     */
    public static int[] uniform(int entryCount, int stateId) {
        int[] values = new int[entryCount];
        java.util.Arrays.fill(values, stateId);
        return values;
    }

    /**
     * Builds a section which holds the requested amount of distinct states.
     * <p>
     * The states are not scattered randomly over the whole section. A real section holds runs of
     * the same block, so the generator writes the states in contiguous runs whose length follows
     * from the amount of distinct states. A purely random fill would produce a palette access
     * pattern no world ever shows.
     * </p>
     *
     * @param entryCount     the amount of entries the section holds
     * @param distinctStates the amount of distinct states the section holds
     * @param firstStateId   the state id the generator starts counting from
     * @return the created section
     * @throws IllegalArgumentException if the section cannot hold the requested amount of states
     */
    public static int[] distinct(int entryCount, int distinctStates, int firstStateId) {
        if (distinctStates <= 0 || distinctStates > entryCount) {
            throw new IllegalArgumentException(
                    "A section of " + entryCount + " entries cannot hold " + distinctStates + " distinct states"
            );
        }

        if (distinctStates == 1) {
            return uniform(entryCount, firstStateId);
        }

        int[] values = new int[entryCount];
        Random random = new Random(BenchmarkConstants.SEED);
        int runLength = Math.max(entryCount / (distinctStates * 4), 1);
        int index = 0;
        int state = 0;

        while (index < entryCount) {
            int length = Math.min(1 + random.nextInt(runLength * 2), entryCount - index);
            java.util.Arrays.fill(values, index, index + length, firstStateId + state);
            index += length;
            state = (state + 1) % distinctStates;
        }

        // The run based fill can miss a state if the section runs out of room, so the tail is
        // rewritten to guarantee that every requested state really occurs.
        for (int missing = 0; missing < distinctStates; missing++) {
            values[entryCount - 1 - missing] = firstStateId + missing;
        }
        return values;
    }

    /**
     * Builds a section which mixes air, solid blocks and light emitting blocks.
     * <p>
     * The result is what the light benchmarks run on. The amount of emitting blocks and the share
     * of solid blocks are the two properties which decide how much work a propagation performs.
     * </p>
     *
     * @param lightSources    the amount of light emitting blocks the section holds
     * @param occlusionPercent the share of solid blocks in percent
     * @return the created section
     * @throws IllegalArgumentException if the section cannot hold the requested amount of sources
     */
    public static int[] lit(int lightSources, int occlusionPercent) {
        int entryCount = BenchmarkConstants.BLOCK_ENTRIES;

        if (lightSources < 0 || lightSources > entryCount) {
            throw new IllegalArgumentException(
                    "A section of " + entryCount + " entries cannot hold " + lightSources + " light sources"
            );
        }

        int[] values = new int[entryCount];
        Random random = new Random(BenchmarkConstants.SEED);

        for (int index = 0; index < entryCount; index++) {
            values[index] = random.nextInt(100) < occlusionPercent ? FakeBlockLightSource.SOLID : FakeBlockLightSource.AIR;
        }

        // The sources are spread evenly instead of being placed randomly. A random placement can
        // cluster them, and a cluster performs far less work than the same amount of sources spread
        // over the section because the searches of the cluster overlap immediately.
        int stride = Math.max(entryCount / Math.max(lightSources, 1), 1);

        for (int source = 0; source < lightSources; source++) {
            values[Math.min(source * stride, entryCount - 1)] = FakeBlockLightSource.GLOWSTONE;
        }
        return values;
    }
}
