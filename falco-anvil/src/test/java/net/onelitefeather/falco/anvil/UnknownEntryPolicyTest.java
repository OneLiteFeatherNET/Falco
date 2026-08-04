package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.world.biome.Biome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link DefaultUnknownEntryPolicy} directly and pins how {@link BlockPaletteResolver} and
 * {@link BiomePaletteResolver} each use whichever {@link UnknownEntryPolicy} they were built with.
 * <p>
 * The counting cases are the ones that matter most: counting an unknown entry is the resolver's job,
 * not the policy's, and it has to keep happening even when the policy substitutes instead of
 * throwing. Losing it would make a substituting run silent again, which is exactly what this whole
 * extension point exists to prevent.
 * </p>
 * <p>
 * The biome cases use {@link Biome#createDefaultRegistry()} rather than the biome registry of a
 * running server, so this class needs no Minestom test environment: {@link BiomePaletteResolver}'s
 * package-private three-argument constructor exists for exactly this, to inject both a policy and a
 * registry supplier without starting a server.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
 * @since 2.1.0
 */
class UnknownEntryPolicyTest {

    @Test
    void testTheDefaultPolicyReplacesAnUnknownBlockWithAir() {
        assertEquals(Block.AIR.stateId(), new DefaultUnknownEntryPolicy().onUnknownBlock("falco:nope", null));
    }

    @Test
    void testTheDefaultPolicyReplacesAnUnknownBiomeWithPlains() {
        DynamicRegistry<Biome> registry = Biome.createDefaultRegistry();

        assertEquals(registry.getId(Biome.PLAINS),
                new DefaultUnknownEntryPolicy(() -> registry).onUnknownBiome("falco:nope"));
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
    void testARefusingPolicyFailsTheChunkInsteadOfSubstitutingForABiome() {
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
                () -> new BiomePaletteResolver(new AnvilDiagnostics(), refusing, Biome::createDefaultRegistry)
                        .toId("falco:nope", null));
        assertTrue(failure.getMessage().contains("falco:nope"), failure.getMessage());
    }

    @Test
    void testTheResolverStillCountsWhenThePolicySubstitutes() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        new BlockPaletteResolver(diagnostics, new DefaultUnknownEntryPolicy()).toId("falco:nope", null);

        assertEquals(1, diagnostics.unknownBlockCount());
    }

    @Test
    void testTheResolverStillCountsWhenThePolicySubstitutesForABiome() {
        AnvilDiagnostics diagnostics = new AnvilDiagnostics();

        new BiomePaletteResolver(diagnostics, new DefaultUnknownEntryPolicy(Biome::createDefaultRegistry),
                Biome::createDefaultRegistry).toId("falco:nope", null);

        assertEquals(1, diagnostics.unknownBiomeCount());
    }
}
