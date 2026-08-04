package net.onelitefeather.falco.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down where {@link WorldLayout} looks for region files, across both the layout Anvil worlds
 * used before Minecraft 1.16 and the one every version since keeps.
 */
class WorldLayoutTest {

    @Test
    void testALegacyWorldYieldsAllThreeDimensions(@TempDir Path worldRoot) throws Exception {
        Files.createDirectories(worldRoot.resolve("region"));
        Files.createDirectories(worldRoot.resolve("DIM-1/region"));
        Files.createDirectories(worldRoot.resolve("DIM1/region"));

        List<WorldLayout.Region> found = WorldLayout.discover(worldRoot);

        assertEquals(
                Set.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
                found.stream().map(WorldLayout.Region::dimensionKey).collect(Collectors.toSet()));
        assertTrue(found.stream().allMatch(WorldLayout.Region::legacy));
    }

    @Test
    void testAModernWorldYieldsWhateverItContains(@TempDir Path worldRoot) throws Exception {
        Files.createDirectories(worldRoot.resolve("dimensions/minecraft/overworld/region"));
        Files.createDirectories(worldRoot.resolve("dimensions/mypack/mining/region"));

        List<WorldLayout.Region> found = WorldLayout.discover(worldRoot);

        assertEquals(
                Set.of("minecraft:overworld", "mypack:mining"),
                found.stream().map(WorldLayout.Region::dimensionKey).collect(Collectors.toSet()));
        assertFalse(found.stream().anyMatch(WorldLayout.Region::legacy));
    }

    @Test
    void testADatapackDimensionIsNotHardCodedAway(@TempDir Path worldRoot) throws Exception {
        Files.createDirectories(worldRoot.resolve("dimensions/mypack/mining/region"));

        assertEquals(1, WorldLayout.discover(worldRoot).size());
    }

    @Test
    void testTheNetherLandsInItsModernPlace(@TempDir Path worldRoot) {
        assertEquals(
                worldRoot.resolve("dimensions/minecraft/the_nether/region"),
                WorldLayout.targetDirectory(worldRoot, "minecraft:the_nether"));
    }

    @Test
    void testAWorldWithNoRegionsAtAllIsEmptyRatherThanAnError(@TempDir Path worldRoot) throws Exception {
        assertTrue(WorldLayout.discover(worldRoot).isEmpty());
    }

    @Test
    void testAPartiallyMigratedOverworldIsReturnedOnceLegacyAndOnceModern(@TempDir Path worldRoot) throws Exception {
        Files.createDirectories(worldRoot.resolve("region"));
        Files.createDirectories(worldRoot.resolve("dimensions/minecraft/overworld/region"));

        List<WorldLayout.Region> found = WorldLayout.discover(worldRoot);

        assertEquals(2, found.size());
        assertTrue(found.stream().allMatch(region -> region.dimensionKey().equals("minecraft:overworld")));
        assertEquals(
                Set.of(true, false),
                found.stream().map(WorldLayout.Region::legacy).collect(Collectors.toSet()));
    }

    @Test
    void testTargetDirectoryRejectsAKeyWithoutANamespaceSeparator() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> WorldLayout.targetDirectory(Path.of("world"), "overworld"));

        assertTrue(exception.getMessage().contains("overworld"));
    }
}
