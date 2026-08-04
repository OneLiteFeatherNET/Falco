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
        BlockState redstoneWire = new BlockState("minecraft:redstone_wire",
                Map.of("north", "side", "south", "none", "east", "up", "west", "none", "power", "0"));

        assertEquals(redstoneWire, BlockStateRules.translate(redstoneWire, 1519),
                "redstone_wire has no rule: the research this module is built from did not carry enough "
                        + "detail to reproduce V2531's cross-direction logic exactly, and a guessed table "
                        + "would be silent corruption rather than a fix");
    }
}
