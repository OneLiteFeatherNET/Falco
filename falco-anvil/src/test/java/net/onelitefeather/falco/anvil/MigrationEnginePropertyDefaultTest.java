package net.onelitefeather.falco.anvil;

import net.minestom.server.instance.block.Block;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the assumption {@code falco-migration}'s Task 7 (Step 3 of the acceptance plan) relies on but
 * deliberately does not implement: for the 258 block states where a Minecraft 1.13 source omits a
 * property the target block gained later, the correct value is <b>the target version's own default
 * for that block</b>, resolved by Minestom itself — never a hardcoded {@code false}.
 * <p>
 * This is not the obvious rule. Nearly every waterloggable block defaults {@code waterlogged} to
 * {@code false} — fences, stairs, walls — which makes {@code false} look like a safe universal
 * substitute. It is not: every coral variant and the conduit default to {@code waterlogged=true},
 * because they cannot exist outside water in the first place. A converter that filled the gap with
 * {@code false} for every block would quietly dry out every reef and beach every conduit in a
 * migrated world, and the chunk would still load without complaint.
 * </p>
 * <p>
 * The exact call this test exercises, {@code Block.fromKey(name).withProperties(partialProperties)},
 * is the same one {@link BlockPaletteResolver#toId} already uses in production — so this pins
 * Minestom's real resolution path, not a hypothetical one.
 * </p>
 * <p>
 * This test lives in {@code falco-anvil} rather than {@code falco-migration} because resolving a
 * default requires {@link Block}, and {@code falco-migration}'s own ArchUnit rule
 * ({@code MigrationBoundaryTest.migrationKnowsNoMinestom}) forbids that module from depending on
 * Minestom at all — the boundary is worth more than the convenience of putting this test next to the
 * engine it documents.
 * </p>
 * <p>
 * The defaults asserted below are cross-checked against {@code net.minestom:data}'s own
 * {@code block.json} (the {@code defaultStateId} recorded for {@code minecraft:tube_coral},
 * {@code minecraft:conduit}, and {@code minecraft:oak_fence} each resolve to the state asserted
 * here) rather than assumed from general Minecraft knowledge.
 * </p>
 */
@ExtendWith(MicrotusExtension.class)
class MigrationEnginePropertyDefaultTest {

    @Test
    void testAConvertedCoralWithoutWaterloggedEndsUpWaterloggedTrue() {
        Block coral = Block.fromKey("minecraft:tube_coral").withProperties(Map.of());

        assertEquals("true", coral.properties().get("waterlogged"),
                "a 1.13 coral entry that carries no waterlogged property must resolve to Minestom's "
                        + "own default for the target block, which is true for coral - not a "
                        + "hardcoded false");
    }

    @Test
    void testAConvertedConduitWithoutWaterloggedEndsUpWaterloggedTrue() {
        Block conduit = Block.fromKey("minecraft:conduit").withProperties(Map.of());

        assertEquals("true", conduit.properties().get("waterlogged"),
                "a conduit is not a coral subtype - this is a second, independently sourced fact, "
                        + "not a corollary of the coral case above");
    }

    @Test
    void testAnOrdinaryWaterloggableBlockWithoutWaterloggedEndsUpWaterloggedFalse() {
        Block fence = Block.fromKey("minecraft:oak_fence").withProperties(Map.of());

        assertEquals("false", fence.properties().get("waterlogged"),
                "the contrast case: for most blocks the target default really is false, so the rule "
                        + "a migrated chunk must follow is \"the target version's default\", never a "
                        + "rule that would look like \"always true\" if only coral were checked");
    }
}
