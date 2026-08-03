package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.utils.validate.Check;
import net.minestom.server.world.biome.Biome;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
 * <p>
 * What is deliberately <em>not</em> copied is the masking of {@code x} and {@code z}.
 * {@code DynamicChunk} receives the instance-level coordinates and has to fold them into its chunk
 * itself; {@link BlockStorage} states that the caller has already done that, and this class takes
 * the contract at its word. The gain is that a violation is loud: {@code Palette#set} rejects a
 * coordinate outside {@code 0} to {@code 15} with an {@code IllegalArgumentException}, whereas a
 * mask would have silently folded a block from a neighbouring chunk into this one.
 * </p>
 *
 * <h2>The two guards on values a palette cannot check</h2>
 * <p>
 * A palette holds plain integers, so nothing in it is a block or a biome, and
 * {@code Palette#set} validates the coordinates and never the value. Both a state id and a biome id
 * can therefore reach a section without ever passing {@link #setBlock} or {@link #setBiome}:
 * {@code ChunkGeneration#applyFork} and every chunk loader write through {@code section.blockPalette()}
 * directly. {@code DynamicChunk} guards both reads and one of the writes, and both guards are copied
 * here rather than re-derived — but they are worth different things, and the difference is stated
 * because the block one reads like dead code and is.
 * </p>
 * <p>
 * The biome guards are live. {@code Registry#getId} answers a lookup miss with {@code -1}, a palette
 * stores that like any other value and counts it, and the chunk packet carries it to a client, so a
 * write without the check turns a caller error into a corrupt chunk that fails somewhere else
 * entirely. {@link #getBiome(int, int, int)} guards the read for the same reason, since a chunk
 * loaded from disk can carry an id this server does not know.
 * </p>
 * <p>
 * The air fallback in {@link #getBlock(int, int, int, Block.Getter.Condition)} is the opposite: with
 * Minestom as pinned here it cannot fire. {@code BlockImpl} builds its state table through
 * {@code ObjectArray#toList}, which is a {@code List#of} and therefore rejects a null element, so
 * every id below {@code Block#statesCount()} is a block and every id at or above it throws out of
 * the list before the fallback is reached. It is kept anyway, for one reason that is not
 * superstition: {@code Block#fromStateId} is declared {@code @Nullable}, and this method promises a
 * block, which is what {@code Block.Getter.Condition#NONE} means. A promise that rests on a fact
 * about the current registry rather than on the signature of the method it calls is a promise that
 * breaks silently and elsewhere.
 * </p>
 * <p>
 * The four members that exist for a lazy layout are constant answers in this one. Every section is
 * allocated in the constructor, so nothing is ever shared and nothing is ever materialised: a view
 * is the section, {@code shared} is always false and {@code materialisedSections} is the section
 * count. That is not a stub — it is what makes this class usable as the eager control in every
 * comparison of the next stage, and it is why the same interface can describe both layouts without
 * either of them carrying a flag about which one it is.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.2.1
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
                .get(x, CoordConversion.globalToSectionRelative(y), z);

        return Objects.requireNonNullElse(Block.fromStateId(stateId), Block.AIR);
    }

    @Override
    public void setBlock(int x, int y, int z, Block block) {
        section(CoordConversion.globalToChunk(y) - this.minSection).blockPalette()
                .set(x, CoordConversion.globalToSectionRelative(y), z, block.stateId());
    }

    @Override
    public RegistryKey<Biome> getBiome(int x, int y, int z) {
        final Section section = section(CoordConversion.globalToChunk(y) - this.minSection);
        final int id = section.biomePalette()
                .get(x / BIOME_SIZE,
                        CoordConversion.globalToSectionRelative(y) / BIOME_SIZE,
                        z / BIOME_SIZE);

        final RegistryKey<Biome> biome = BIOME_REGISTRY.getKey(id);

        Check.notNull(biome, "Biome with id {0} is not registered", id);
        return biome;
    }

    @Override
    public void setBiome(int x, int y, int z, RegistryKey<Biome> biome) {
        final int id = BIOME_REGISTRY.getId(biome);

        if (id == -1) throw new IllegalStateException("Biome has not been registered: " + biome.key());

        section(CoordConversion.globalToChunk(y) - this.minSection).biomePalette()
                .set(x / BIOME_SIZE,
                        CoordConversion.globalToSectionRelative(y) / BIOME_SIZE,
                        z / BIOME_SIZE, id);
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
    public Section view(int section) {
        return section(section);
    }

    @Override
    public List<Section> views() {
        return this.sections;
    }

    @Override
    public boolean shared(int section) {
        return false;
    }

    @Override
    public int materialisedSections() {
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
