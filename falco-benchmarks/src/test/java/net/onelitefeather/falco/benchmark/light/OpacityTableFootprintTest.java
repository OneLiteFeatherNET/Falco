package net.onelitefeather.falco.benchmark.light;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Instance;
import net.onelitefeather.falco.benchmark.instance.JolMeasurement;
import net.onelitefeather.falco.light.ChunkLightService;
import net.onelitefeather.falco.light.SectionOpacity;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures how many bytes the opacity tables of a generated area retain.
 * <p>
 * {@code ChunkLightArea} keeps the tables of every chunk between two passes, so their retained size
 * is memory a server holds for as long as the chunks stay loaded. The allocation figures of
 * {@link TerrainAreaBenchmark} cannot show it: they count what a pass creates, not what is left
 * once it is done. The area is the terrain of that benchmark, so the two describe the same world.
 * </p>
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 3.0.2
 */
class OpacityTableFootprintTest {

    /**
     * The edge length of the measured area, in chunks.
     */
    private static final int EDGE = 8;

    @Test
    void testTheTablesOfATerrainArea() {
        JolMeasurement.require();

        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }
        Instance instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        ChunkLightService service = new ChunkLightService();
        List<List<SectionOpacity>> tables = new ArrayList<>(EDGE * EDGE);

        for (int z = 0; z < EDGE; z++) {
            for (int x = 0; x < EDGE; x++) {
                var chunk = instance.loadChunk(x, z).join();
                TerrainAreaBenchmark.generate(chunk);
                tables.add(service.opacityOf(chunk));
            }
        }
        GraphLayout layout = GraphLayout.parseInstance(tables);
        int sections = tables.stream().mapToInt(List::size).sum();

        System.out.println(JolMeasurement.describe());
        System.out.printf("chunks=%d  sections=%d  bytes=%d  bytes/chunk=%d%n",
                tables.size(), sections, layout.totalSize(), layout.totalSize() / tables.size());
        System.out.println(layout.toFootprint());

        assertTrue(layout.totalSize() > 0, "the tables retain something");
    }
}
