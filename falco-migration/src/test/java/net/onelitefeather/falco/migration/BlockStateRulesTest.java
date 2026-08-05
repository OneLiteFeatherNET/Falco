package net.onelitefeather.falco.migration;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins down {@link BlockStateRules}: that rules are resolved by version, keyed on the whole state,
 * and may change a block's name rather than only its properties.
 */
class BlockStateRulesTest {

    @Test
    void testAPlainRenameIsApplied() {
        assertEquals("minecraft:short_grass",
                BlockStateRules.translate(BlockState.of("minecraft:grass"), 1519).name());
    }

    @Test
    void testStoneSlabIsRenamedFromThirteenButNotFromSixteen() {
        assertEquals("minecraft:smooth_stone_slab",
                BlockStateRules.translate(BlockState.of("minecraft:stone_slab"), 1519).name());
        assertEquals("minecraft:stone_slab",
                BlockStateRules.translate(BlockState.of("minecraft:stone_slab"), 2566).name());
    }

    @Test
    void testACauldronsLevelDecidesItsName() {
        BlockState empty = new BlockState("minecraft:cauldron", Map.of("level", "0"));
        BlockState filled = new BlockState("minecraft:cauldron", Map.of("level", "2"));

        assertEquals("minecraft:cauldron", BlockStateRules.translate(empty, 1519).name());
        assertEquals(Map.of(), BlockStateRules.translate(empty, 1519).properties());

        BlockState water = BlockStateRules.translate(filled, 1519);
        assertEquals("minecraft:water_cauldron", water.name());
        assertEquals("2", water.properties().get("level"));
    }

    @Test
    void testAWallSideBecomesLowRatherThanTrue() {
        BlockState wall = new BlockState("minecraft:cobblestone_wall",
                Map.of("north", "true", "south", "false", "up", "true"));

        BlockState converted = BlockStateRules.translate(wall, 1519);

        assertEquals("low", converted.properties().get("north"));
        assertEquals("none", converted.properties().get("south"));
        assertEquals("true", converted.properties().get("up"), "up is not one of the four sides");
    }

    @Test
    void testAMossyWallIsRewrittenByTheSameSharedTable() {
        BlockState wall = new BlockState("minecraft:mossy_cobblestone_wall", Map.of("east", "true"));

        assertEquals("low", BlockStateRules.translate(wall, 1519).properties().get("east"));
    }

    @Test
    void testARuleAppliesExactlyBelowItsOwnVersionAndNotAtOrAboveIt() {
        // stone_slab's rule fires below DataVersion 1901 (snapshot 18w43a, where the rename actually
        // happened) and not at or above it. An earlier version of this test used 1801/1802, matching
        // an earlier, wrong value for the rule itself; see BlockStateRules for the correction.
        assertEquals("minecraft:smooth_stone_slab",
                BlockStateRules.translate(BlockState.of("minecraft:stone_slab"), 1900).name());
        assertEquals("minecraft:stone_slab",
                BlockStateRules.translate(BlockState.of("minecraft:stone_slab"), 1901).name());
    }

    @Test
    void testASignIsRenamedToItsOnlyThirteenEraWoodType() {
        assertEquals("minecraft:oak_sign",
                BlockStateRules.translate(BlockState.of("minecraft:sign"), 1519).name());
        assertEquals("minecraft:oak_wall_sign",
                BlockStateRules.translate(BlockState.of("minecraft:wall_sign"), 1519).name());
    }

    @Test
    void testGrassPathIsRenamedToDirtPath() {
        assertEquals("minecraft:dirt_path",
                BlockStateRules.translate(BlockState.of("minecraft:grass_path"), 1519).name());
    }

    @Test
    void testARenameCarriesItsPropertiesAlong() {
        BlockState sign = new BlockState("minecraft:sign", Map.of("rotation", "4", "waterlogged", "false"));

        BlockState converted = BlockStateRules.translate(sign, 1519);

        assertEquals("minecraft:oak_sign", converted.name());
        assertEquals(Map.of("rotation", "4", "waterlogged", "false"), converted.properties());
    }

    @Test
    void testAStateNoRuleKnowsAboutPassesThroughUnchanged() {
        // Used to be redstone_wire, back when this module carried no rule for it at all. Now that
        // BlockStateRules does resolve redstone_wire (see the dedicated tests below), a passthrough
        // block that stays genuinely unmapped is furnace: its facing/lit properties exist unchanged
        // from 1.13 to today, with nothing in RULES that names it.
        BlockState furnace = new BlockState("minecraft:furnace", Map.of("facing", "north", "lit", "false"));

        assertEquals(furnace, BlockStateRules.translate(furnace, 1519),
                "a state no rule recognizes must pass through translate() unchanged");
    }

    /**
     * Builds a {@code redstone_wire} state with a fixed, deliberately odd {@code power} so tests can
     * assert it is never read or written by the rule.
     */
    private static BlockState redstoneWire(String north, String south, String east, String west) {
        return new BlockState("minecraft:redstone_wire",
                Map.of("north", north, "south", south, "east", east, "west", west, "power", "11"));
    }

    private static void assertRedstoneSides(BlockState state, String north, String south, String east,
                                             String west) {
        assertEquals(north, state.properties().get("north"), "north");
        assertEquals(south, state.properties().get("south"), "south");
        assertEquals(east, state.properties().get("east"), "east");
        assertEquals(west, state.properties().get("west"), "west");
    }

    // The nine affected direction combinations (of 81), individually, so a crossed-axis mistake in
    // the implementation cannot hide behind a shared helper's own bug. Each one is checked against
    // BlockStateRules's derivation by hand in the redstone_wire rule's own comment.

    @Test
    void testRedstoneWireWithNoConnectionsBecomesIsolatedOnAllFourSides() {
        BlockState converted = BlockStateRules.translate(redstoneWire("none", "none", "none", "none"), 1519);

        assertRedstoneSides(converted, "side", "side", "side", "side");
    }

    @Test
    void testRedstoneWireConnectedOnlyOnWestTurnsEastAndBothCrossSidesToSide() {
        BlockState converted = BlockStateRules.translate(redstoneWire("none", "none", "none", "side"), 1519);

        assertRedstoneSides(converted, "none", "none", "side", "side");
    }

    @Test
    void testRedstoneWireConnectedUpwardOnWestStillTurnsEastToSide() {
        BlockState converted = BlockStateRules.translate(redstoneWire("none", "none", "none", "up"), 1519);

        assertRedstoneSides(converted, "none", "none", "side", "up");
    }

    @Test
    void testRedstoneWireConnectedOnlyOnEastTurnsWestAndBothCrossSidesToSide() {
        BlockState converted = BlockStateRules.translate(redstoneWire("none", "none", "side", "none"), 1519);

        assertRedstoneSides(converted, "none", "none", "side", "side");
    }

    @Test
    void testRedstoneWireConnectedUpwardOnEastStillTurnsWestToSide() {
        BlockState converted = BlockStateRules.translate(redstoneWire("none", "none", "up", "none"), 1519);

        assertRedstoneSides(converted, "none", "none", "up", "side");
    }

    @Test
    void testRedstoneWireConnectedOnlyOnSouthTurnsNorthAndBothCrossSidesToSide() {
        BlockState converted = BlockStateRules.translate(redstoneWire("none", "side", "none", "none"), 1519);

        assertRedstoneSides(converted, "side", "side", "none", "none");
    }

    @Test
    void testRedstoneWireConnectedUpwardOnSouthStillTurnsNorthToSide() {
        BlockState converted = BlockStateRules.translate(redstoneWire("none", "up", "none", "none"), 1519);

        assertRedstoneSides(converted, "side", "up", "none", "none");
    }

    @Test
    void testRedstoneWireConnectedOnlyOnNorthTurnsSouthAndBothCrossSidesToSide() {
        BlockState converted = BlockStateRules.translate(redstoneWire("side", "none", "none", "none"), 1519);

        assertRedstoneSides(converted, "side", "side", "none", "none");
    }

    @Test
    void testRedstoneWireConnectedUpwardOnNorthStillTurnsSouthToSide() {
        BlockState converted = BlockStateRules.translate(redstoneWire("up", "none", "none", "none"), 1519);

        assertRedstoneSides(converted, "up", "side", "none", "none");
    }

    // Fixed points: none of the 72 combinations outside the nine above may change.

    @Test
    void testRedstoneWireWithTwoConnectionsOnTheSameAxisIsAFixedPoint() {
        BlockState state = redstoneWire("none", "none", "side", "side");

        assertEquals(state, BlockStateRules.translate(state, 1519));
    }

    @Test
    void testRedstoneWireWithThreeConnectionsIsAFixedPoint() {
        BlockState state = redstoneWire("side", "side", "side", "none");

        assertEquals(state, BlockStateRules.translate(state, 1519));
    }

    @Test
    void testRedstoneWireWithTwoConnectionsIncludingUpIsAFixedPoint() {
        BlockState state = redstoneWire("up", "side", "none", "none");

        assertEquals(state, BlockStateRules.translate(state, 1519));
    }

    @Test
    void testRedstoneWiresPowerIsNeverReadOrWritten() {
        BlockState converted = BlockStateRules.translate(redstoneWire("none", "none", "none", "none"), 1519);

        assertEquals("11", converted.properties().get("power"),
                "power is a plain multiplier and must survive translate() untouched");
    }

    @Test
    void testRedstoneWireIsUnchangedFromDataVersionTwentyFiveThirtyTwoOnwards() {
        BlockState state = redstoneWire("none", "none", "none", "none");

        assertEquals(state, BlockStateRules.translate(state, 2532),
                "a source at or after DataVersion 2532 already carries the later connection meaning");
    }
}
