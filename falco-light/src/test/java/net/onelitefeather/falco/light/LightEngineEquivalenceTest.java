package net.onelitefeather.falco.light;

import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins down that the light engine of Falco produces the very same bytes as the one the server ships
 * with, on every section both of them are given.
 * <p>
 * This is the constraint every optimisation of the engine has to survive. A faster engine which
 * lights a section even slightly differently is not a faster engine, it is a second lighting of the
 * same world, and the difference would surface as a patch of wrong brightness that no player can
 * explain and no log mentions. The engines are therefore compared byte for byte and not by any
 * weaker notion of similarity.
 * </p>
 * <p>
 * The two methods which form the built-in path, {@code BlockLight.buildInternalQueue} and
 * {@code LightCompute.compute}, are package-private. They are called through reflection rather than
 * from a test placed inside the Minestom package, because that placement would split a package of
 * the server across two artifacts and would drag the queue type of the built-in path onto the test
 * classpath as a compile dependency. Reflection keeps the comparison to the one thing it is about:
 * the bytes that come out of each engine.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ExtendWith(MicrotusExtension.class)
class LightEngineEquivalenceTest {

    /**
     * The amounts of light sources a compared section is filled with.
     */
    private static final int[] LIGHT_SOURCES = {0, 1, 2, 4, 8, 16, 64, 128, 512};

    /**
     * The shares of solid blocks a compared section is filled with, in percent.
     */
    private static final int[] OCCLUSION_PERCENTS = {0, 10, 30, 50, 70, 90};

    /**
     * The seed every section is built from, so a failure can be reproduced.
     */
    private static final long SEED = 20260731L;

    /**
     * The amount of bytes a light section occupies.
     */
    private static final int LIGHT_LENGTH = LightNibbles.ARRAY_LENGTH;

    @Test
    void testBothEnginesLightEverySectionIdentically() {
        MinestomBlockLightSource source = new MinestomBlockLightSource();
        LightPropagator propagator = new LightPropagator();
        int compared = 0;

        for (int lightSources : LIGHT_SOURCES) {
            for (int occlusionPercent : OCCLUSION_PERCENTS) {
                int[] states = section(lightSources, occlusionPercent);
                Palette palette = paletteOf(states);

                byte[] expected = minestomLight(palette);
                byte[] actual = propagator.propagate(SectionOpacity.of(states, source)).toDenseArray();

                assertArrayEquals(
                        expected, actual,
                        "the engines disagree on a section with " + lightSources + " sources and "
                                + occlusionPercent + " percent solid blocks"
                );

                // A comparison of two dark sections would agree no matter what either engine does,
                // so every scenario which holds a source has to carry light to be worth anything.
                if (lightSources > 0) {
                    assertFalse(
                            Arrays.equals(new byte[LIGHT_LENGTH], actual),
                            "a section with " + lightSources + " sources and " + occlusionPercent
                                    + " percent solid blocks stayed dark, so it compares nothing"
                    );
                }
                compared++;
            }
        }
        assertEquals(LIGHT_SOURCES.length * OCCLUSION_PERCENTS.length, compared);
    }

    @Test
    void testBothEnginesAgreeOnASectionWithoutAnyLight() {
        // The engines store an unlit section differently, one as an empty array and the other
        // without an array at all. Both have to hand out the same bytes regardless.
        int[] states = section(0, 100);
        byte[] expected = minestomLight(paletteOf(states));
        byte[] actual = new LightPropagator()
                .propagate(SectionOpacity.of(states, new MinestomBlockLightSource()))
                .toDenseArray();

        assertArrayEquals(new byte[LIGHT_LENGTH], actual);
        assertArrayEquals(expected, actual);
    }

    /**
     * Builds the state ids of a section from the amount of sources and the share of solid blocks.
     *
     * @param lightSources     the amount of light emitting blocks the section holds
     * @param occlusionPercent the share of solid blocks in the section, in percent
     * @return the state id of every block of the section
     */
    private static int[] section(int lightSources, int occlusionPercent) {
        int[] states = new int[LightNibbles.BLOCK_COUNT];
        Random random = new Random(SEED);
        int air = Block.AIR.stateId();
        int stone = Block.STONE.stateId();
        int glowstone = Block.GLOWSTONE.stateId();

        for (int index = 0; index < states.length; index++) {
            states[index] = random.nextInt(100) < occlusionPercent ? stone : air;
        }
        for (int placed = 0; placed < lightSources; placed++) {
            states[random.nextInt(states.length)] = glowstone;
        }
        return states;
    }

    /**
     * Builds the block palette of a section from its state ids.
     *
     * @param states the state id of every block of the section
     * @return the created palette
     */
    private static Palette paletteOf(int[] states) {
        Palette palette = Palette.blocks();
        palette.setAll((x, y, z) -> states[(y << 8) | (z << 4) | x]);
        return palette;
    }

    /**
     * Runs the light engine the server ships with over the given palette.
     * <p>
     * An unlit section is reported as an empty array by that engine, which is how the network format
     * encodes it. It is expanded here so both engines are compared on arrays of the same length.
     * </p>
     *
     * @param palette the block palette of the section
     * @return the calculated light of the section
     */
    private static byte[] minestomLight(Palette palette) {
        try {
            Class<?> blockLight = Class.forName("net.minestom.server.instance.light.BlockLight");
            Class<?> lightCompute = Class.forName("net.minestom.server.instance.light.LightCompute");
            Class<?> queueType = Class.forName("it.unimi.dsi.fastutil.shorts.ShortArrayFIFOQueue");

            Method buildQueue = blockLight.getDeclaredMethod("buildInternalQueue", Palette.class);
            Method compute = lightCompute.getDeclaredMethod("compute", Palette.class, queueType);
            buildQueue.setAccessible(true);
            compute.setAccessible(true);

            byte[] light = (byte[]) compute.invoke(null, palette, buildQueue.invoke(null, palette));
            return light.length == LIGHT_LENGTH ? light : new byte[LIGHT_LENGTH];
        } catch (ReflectiveOperationException exception) {
            return fail("the light engine of the server could not be reached", exception);
        }
    }
}
