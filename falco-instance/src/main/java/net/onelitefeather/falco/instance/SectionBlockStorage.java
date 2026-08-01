package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The {@link SectionBlockStorage} class stores the blocks of a chunk in Minestom {@link Section}
 * objects, one per sixteen blocks of height, allocated when the storage is created.
 * <p>
 * This is deliberately the layout {@code DynamicChunk} already has, and it is the first
 * implementation on purpose: it makes the bridge measurable before the bridge changes anything. A
 * chunk built on this storage has to be indistinguishable from a {@code DynamicChunk} in both time
 * and bytes, and {@code ChunkComparisonBenchmark} together with {@code ChunkFootprintTest} is what
 * says whether it is. A layout that saves memory is the subject of the next stage; if this one
 * already differed, the difference of that next stage could not be attributed to it.
 * </p>
 * <p>
 * The coordinate arithmetic below is copied from {@code DynamicChunk#getBlock},
 * {@code DynamicChunk#setBlock} and {@code DynamicChunk#getBiome} rather than re-derived, including
 * the biome divisor of {@code 4}: {@code Section} has no {@code BIOME_SIZE} constant to name it
 * with, and getting a section index or a biome divisor wrong here is silent, not loud, since both
 * still return a value, just the wrong one.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public final class SectionBlockStorage implements BlockStorage {

    private static final int BIOME_SIZE = 4;

    private static final DynamicRegistry<Biome> BIOME_REGISTRY = MinecraftServer.getBiomeRegistry();

    private final int minSection;
    private final List<Section> sections;

    /**
     * Creates a storage of empty sections.
     *
     * @param minSection   the index of the bottom section of the chunk
     * @param sectionCount the amount of sections the chunk spans
     */
    public SectionBlockStorage(int minSection, int sectionCount) {
        final Section[] created = new Section[sectionCount];

        Arrays.setAll(created, index -> new Section());
        this.minSection = minSection;
        this.sections = List.of(created);
    }

    /**
     * Creates a storage which takes over the given sections.
     *
     * @param minSection the index of the bottom section of the chunk
     * @param sections   the sections, from the bottom one upwards
     */
    public SectionBlockStorage(int minSection, List<Section> sections) {
        this.minSection = minSection;
        this.sections = List.copyOf(sections);
    }

    @Override
    public Block getBlock(int x, int y, int z, Block.Getter.Condition condition) {
        final Section section = section(CoordConversion.globalToChunk(y) - this.minSection);
        final int stateId = section.blockPalette()
                .get(CoordConversion.globalToSectionRelative(x),
                        CoordConversion.globalToSectionRelative(y),
                        CoordConversion.globalToSectionRelative(z));

        return Block.fromStateId(stateId);
    }

    @Override
    public void setBlock(int x, int y, int z, Block block) {
        section(CoordConversion.globalToChunk(y) - this.minSection).blockPalette()
                .set(CoordConversion.globalToSectionRelative(x),
                        CoordConversion.globalToSectionRelative(y),
                        CoordConversion.globalToSectionRelative(z), block.stateId());
    }

    @Override
    public RegistryKey<Biome> getBiome(int x, int y, int z) {
        final Section section = section(CoordConversion.globalToChunk(y) - this.minSection);
        final int id = section.biomePalette()
                .get(CoordConversion.globalToSectionRelative(x) / BIOME_SIZE,
                        CoordConversion.globalToSectionRelative(y) / BIOME_SIZE,
                        CoordConversion.globalToSectionRelative(z) / BIOME_SIZE);

        return BIOME_REGISTRY.getKey(id);
    }

    @Override
    public void setBiome(int x, int y, int z, RegistryKey<Biome> biome) {
        section(CoordConversion.globalToChunk(y) - this.minSection).biomePalette()
                .set(CoordConversion.globalToSectionRelative(x) / BIOME_SIZE,
                        CoordConversion.globalToSectionRelative(y) / BIOME_SIZE,
                        CoordConversion.globalToSectionRelative(z) / BIOME_SIZE,
                        BIOME_REGISTRY.getId(biome));
    }

    @Override
    public List<Section> sections() {
        return this.sections;
    }

    @Override
    public Section section(int section) {
        return this.sections.get(section);
    }

    @Override
    public int sectionCount() {
        return this.sections.size();
    }

    @Override
    public BlockStorage copy() {
        final List<Section> copied = new ArrayList<>(this.sections.size());

        for (Section section : this.sections) {
            copied.add(section.clone());
        }
        return new SectionBlockStorage(this.minSection, copied);
    }

    @Override
    public void clear() {
        for (Section section : this.sections) {
            section.clear();
        }
    }
}
