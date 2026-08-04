package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.instance.block.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link DefaultUnknownEntryPolicy} directly and pins how {@link BlockPaletteResolver} uses
 * whichever {@link UnknownEntryPolicy} it was built with.
 * <p>
 * The third case is the one that matters most: counting an unknown entry is the resolver's job, not
 * the policy's, and it has to keep happening even when the policy substitutes instead of throwing.
 * Losing it would make a substituting run silent again, which is exactly what this whole extension
 * point exists to prevent.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 1.2.0
 */
class UnknownEntryPolicyTest {

    @Test
    void testTheDefaultPolicyReplacesAnUnknownBlockWithAir() {
        assertEquals(Block.AIR.stateId(), new DefaultUnknownEntryPolicy().onUnknownBlock("falco:nope", null));
    }

    @Test
    void testARefusingPolicyFailsTheChunkInsteadOfSubstituting() {
        UnknownEntryPolicy refusing = new UnknownEntryPolicy() {
            @Override
            public int onUnknownBlock(String name, CompoundBinaryTag properties) {
                throw new AnvilChunkException("The block " + name + " has no mapping");
            }

            @Override
            public int onUnknownBiome(String name) {
                throw new AnvilChunkException("The biome " + name + " has no mapping");
            }
        };

        AnvilChunkException failure = assertThrows(AnvilChunkException.class,
                () -> new BlockPaletteResolver(new AnvilDiagnostics(), refusing).toId("falco:nope", null));
        assertTrue(failure.getMessage().contains("falco:nope"), failure.getMessage());
    }

    @Test
    void testTheResolverStillCountsWhenThePolicySubstitutes() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        new BlockPaletteResolver(diagnostics, new DefaultUnknownEntryPolicy()).toId("falco:nope", null);

        assertEquals(1, diagnostics.unknownBlockCount());
    }
}
