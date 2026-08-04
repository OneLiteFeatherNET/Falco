package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.world.biome.Biome;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

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
 * {@link UnknownEntryPolicy} hands back a name, not an id — the resolver owns the registry lookup
 * that turns a name into one, using the same registry it already needed for the original, unknown
 * name. That is what the "unusable substitute" cases pin: the resolver has to fail if the name a
 * policy substitutes is itself unresolvable, and it must not ask the policy a second time to find
 * out, which could loop. The failure surfaces as {@link IllegalStateException} rather than {@link
 * AnvilChunkException} directly, because {@code FalcoAnvilLoader} is the only class that constructs
 * the latter — it wraps this into one when a chunk is read through the loader.
 * </p>
 * <p>
 * The biome cases use {@link Biome#createDefaultRegistry()} rather than the biome registry of a
 * running server, so this class needs no Minestom test environment: {@link BiomePaletteResolver}'s
 * package-private three-argument constructor exists for exactly this, to inject both a policy and a
 * registry supplier without starting a server.
 * </p>
 *
 * @author TheMeinerLP
 * @version 2.0.0
 * @since 2.1.0
 */
class UnknownEntryPolicyTest {

    @Test
    void testTheDefaultPolicyReplacesAnUnknownBlockWithAir() {
        assertEquals("minecraft:air", new DefaultUnknownEntryPolicy().onUnknownBlock("falco:nope", null));
    }

    @Test
    void testTheDefaultPolicyReplacesAnUnknownBiomeWithPlains() {
        assertEquals("minecraft:plains", new DefaultUnknownEntryPolicy().onUnknownBiome("falco:nope"));
    }

    @Test
    void testARefusingPolicyFailsTheChunkInsteadOfSubstituting() {
        UnknownEntryPolicy refusing = new UnknownEntryPolicy() {
            @Override
            public String onUnknownBlock(String name, CompoundBinaryTag properties) {
                throw new AnvilChunkException("The block " + name + " has no mapping");
            }

            @Override
            public String onUnknownBiome(String name) {
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
            public String onUnknownBlock(String name, CompoundBinaryTag properties) {
                throw new AnvilChunkException("The block " + name + " has no mapping");
            }

            @Override
            public String onUnknownBiome(String name) {
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

        new BiomePaletteResolver(diagnostics, new DefaultUnknownEntryPolicy(), Biome::createDefaultRegistry)
                .toId("falco:nope", null);

        assertEquals(1, diagnostics.unknownBiomeCount());
    }

    @Test
    void testAnUnusableSubstituteBlockFailsTheChunkAndNamesBothNames() {
        AtomicInteger calls = new AtomicInteger();
        UnknownEntryPolicy nonsense = new UnknownEntryPolicy() {
            @Override
            public String onUnknownBlock(String name, CompoundBinaryTag properties) {
                calls.incrementAndGet();
                return "falco:still-nope";
            }

            @Override
            public String onUnknownBiome(String name) {
                throw new AssertionError("not exercised by this test");
            }
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new BlockPaletteResolver(new AnvilDiagnostics(), nonsense).toId("falco:nope", null));

        assertTrue(failure.getMessage().contains("falco:nope"), failure.getMessage());
        assertTrue(failure.getMessage().contains("falco:still-nope"), failure.getMessage());
        assertEquals(1, calls.get(), "the policy is not asked a second time for its own substitute");
    }

    @Test
    void testAnUnusableSubstituteBiomeFailsTheChunkAndNamesBothNames() {
        AtomicInteger calls = new AtomicInteger();
        UnknownEntryPolicy nonsense = new UnknownEntryPolicy() {
            @Override
            public String onUnknownBlock(String name, CompoundBinaryTag properties) {
                throw new AssertionError("not exercised by this test");
            }

            @Override
            public String onUnknownBiome(String name) {
                calls.incrementAndGet();
                return "falco:still-nope";
            }
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new BiomePaletteResolver(new AnvilDiagnostics(), nonsense, Biome::createDefaultRegistry)
                        .toId("falco:nope", null));

        assertTrue(failure.getMessage().contains("falco:nope"), failure.getMessage());
        assertTrue(failure.getMessage().contains("falco:still-nope"), failure.getMessage());
        assertEquals(1, calls.get(), "the policy is not asked a second time for its own substitute");
    }
}
