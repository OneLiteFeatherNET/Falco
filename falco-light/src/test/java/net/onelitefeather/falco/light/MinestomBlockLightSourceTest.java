package net.onelitefeather.falco.light;

import net.minestom.server.instance.block.Block;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the adapter which answers the light properties of a block from the registry of the server.
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ExtendWith(MicrotusExtension.class)
class MinestomBlockLightSourceTest {

    private final BlockLightSource source = new MinestomBlockLightSource();

    @Test
    void testAirBlocksNothing() {
        for (BlockFace face : BlockFace.values()) {
            assertFalse(this.source.blocksFace(Block.AIR.stateId(), face), "air must not block " + face);
        }
    }

    @Test
    void testStoneBlocksEveryFace() {
        for (BlockFace face : BlockFace.values()) {
            assertTrue(this.source.blocksFace(Block.STONE.stateId(), face), "stone must block " + face);
        }
    }

    @Test
    void testGlassBlocksNoFace() {
        for (BlockFace face : BlockFace.values()) {
            assertFalse(this.source.blocksFace(Block.GLASS.stateId(), face), "glass must not block " + face);
        }
    }

    @Test
    void testGlowstoneReportsItsEmission() {
        assertEquals(15, this.source.emission(Block.GLOWSTONE.stateId()));
    }

    @Test
    void testStoneEmitsNothing() {
        assertEquals(0, this.source.emission(Block.STONE.stateId()));
    }

    @Test
    void testATorchReportsItsEmission() {
        assertTrue(this.source.emission(Block.TORCH.stateId()) > 0, "a torch has to emit light");
    }

    @Test
    void testABottomSlabBlocksItsBottomFaceOnly() {
        // This is the case a single occlusion flag per block would answer wrongly.
        int slab = Block.OAK_SLAB.withProperty("type", "bottom").stateId();

        assertTrue(this.source.blocksFace(slab, BlockFace.BOTTOM));
        assertFalse(this.source.blocksFace(slab, BlockFace.TOP));
    }

    @Test
    void testATopSlabBlocksItsTopFaceOnly() {
        int slab = Block.OAK_SLAB.withProperty("type", "top").stateId();

        assertTrue(this.source.blocksFace(slab, BlockFace.TOP));
        assertFalse(this.source.blocksFace(slab, BlockFace.BOTTOM));
    }

    @Test
    void testAnUnknownStateIsTreatedAsTransparent() {
        int unknown = Integer.MAX_VALUE;

        assertEquals(0, this.source.emission(unknown));
        assertFalse(this.source.blocksFace(unknown, BlockFace.TOP));
    }

    @Test
    void testTheSourceFeedsAPropagationEndToEnd() {
        int[] states = new int[LightNibbles.BLOCK_COUNT];
        states[(8 << 8) | (8 << 4) | 8] = Block.GLOWSTONE.stateId();

        LightNibbles light = new LightPropagator().propagate(SectionOpacity.of(states, this.source));

        assertEquals(15, light.get(8, 8, 8));
        assertEquals(14, light.get(9, 8, 8));
    }

    @Test
    void testTheFaceOrderMatchesTheOneOfTheServer() {
        // The adapter maps the faces by ordinal. If the server ever reorders its enum, every
        // occlusion answer would silently refer to the wrong face.
        net.minestom.server.instance.block.BlockFace[] serverFaces =
                net.minestom.server.instance.block.BlockFace.values();

        assertEquals(serverFaces.length, BlockFace.values().length);

        for (BlockFace face : BlockFace.values()) {
            assertEquals(face.name(), serverFaces[face.ordinal()].name(),
                    "the face order of the engine and the server must stay identical");
        }
    }
}
