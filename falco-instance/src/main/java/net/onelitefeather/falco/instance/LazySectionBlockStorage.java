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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The {@link LazySectionBlockStorage} class stores the blocks of a chunk in Minestom {@link Section}
 * objects again, but allocates one only for a section that holds something: every section which is
 * nothing but air points at one shared instance which no chunk owns and none writes to.
 * <p>
 * This is the layout the whole design was built for, and the reason it can exist at all is that
 * stage 1 moved the storage out of the chunk. The pattern fits nowhere else. {@code Section} is a
 * {@code record} and therefore final, and {@code Palette} is declared
 * {@code public sealed interface Palette permits PaletteImpl}, so neither a lazy section nor a lazy
 * palette can be handed to Minestom — the sharing has to live one level above both, which is exactly
 * where {@link BlockStorage} sits.
 * </p>
 * <p>
 * What it is worth was counted rather than assumed. A generated overworld holds {@code 62,24 %} of
 * the sections of its finished chunks as pure air, over four hundred and forty one chunks around one
 * spawn, and at that share the layout saves {@code 2 911} bytes per chunk. Constructing a chunk this
 * way allocates {@code 2 104} against {@code 7 096} bytes. Both figures come from
 * {@code EmptySectionCensusTest} and {@code LazySectionBenchmark} in {@code falco-benchmarks} and
 * carry the conditions stated there; neither is a projection.
 * </p>
 *
 * <h2>Why a first write allocates instead of cloning the shared section</h2>
 * <p>
 * The obvious copy on write step is {@code EMPTY.clone()} and it is the wrong one.
 * {@code Section#clone} creates two fresh light carriers and then calls
 * {@code skyLight.set(this.skyLight.array())} on them. For the shared section {@code array()} answers
 * with {@code LightCompute.UNSET_CONTENT}, a zero length array, and {@code SkyLight#set} turns that
 * into {@code LightCompute.EMPTY_CONTENT} — the process wide, mutable {@code byte[2048]} — while
 * also setting {@code isValidBorders} and raising {@code needsSend}. A section materialised that way
 * would announce that it has light to send before anything ever lit it, and would hold its
 * {@code content} field pointing at an array shared with every other section built the same way.
 * {@code new Section()} produces the same blocks, leaves the light unset and allocates less, which is
 * what {@code LazySectionBenchmark#firstWriteLazy} measured at {@code 2 720 B/op}.
 * </p>
 *
 * <h2>What is shared, and the one rule that keeps it safe</h2>
 * <p>
 * {@link #EMPTY} is never written to by this class. Every write path replaces the slot first, and the
 * two accessors that can hand the shared section to a caller — {@link #view(int)} and
 * {@link #views()} — document in {@link BlockStorage} that their result is read only. The accessors
 * Minestom itself reaches, {@link #section(int)} and {@link #sections()}, materialise instead, because
 * a chunk loader or the generator of an {@code InstanceContainer} receives a {@code Section} through
 * them and writes into its palettes directly. That is the honest price of this layout and it is
 * stated rather than hidden: any caller of {@code Chunk#getSections()} makes this storage as
 * expensive as the eager one.
 * </p>
 * <p>
 * A write of the state the shared section already holds everywhere is skipped rather than
 * materialised. That is not an optimisation of a rare case: a loader or a generator which walks a
 * whole chunk and writes air into the parts that are air would otherwise materialise every section it
 * touched and this class would be strictly worse than the eager one. The check is on the state id and
 * not on {@code Block#isAir()}, because cave air and void air are air by that predicate and are
 * different states which have to be stored.
 * </p>
 *
 * <h2>The one step that cannot rely on the chunk lock</h2>
 * <p>
 * Implementations of {@link BlockStorage} are not thread-safe and this one is no exception: two
 * writers into the same palette race here exactly as they race in {@code DynamicChunk}, and the write
 * lock of the chunk is what keeps them apart. Materialising a slot is the one step that cannot be
 * left to that lock, because the boundary this class sits behind is reachable without it.
 * {@code Chunk#getSection(int)} and {@code Chunk#getSectionAt(int)} end in {@link #section(int)}, and
 * three inherited methods of {@code Instance} reach them holding no chunk lock at all —
 * {@code Instance#getBlockLight}, {@code Instance#getSkyLight} and
 * {@code Instance#invalidateSection}, each of which takes a section before it has decided what kind
 * of chunk it is looking at.
 * </p>
 * <p>
 * A read of the slot, an allocation and a plain store would therefore lose blocks. A writer holding
 * the write lock materialises a slot and writes stone into its palette; a light query on another
 * thread read the same slot before that store, saw {@link #EMPTY}, allocates its own section and
 * stores it over the first one. The stone is gone with no exception and no log, and the next read
 * answers air through the shortcut in {@link #getBlock}. {@code DynamicChunk} cannot have this race,
 * because its section list is final and complete from construction; it exists here only because a
 * slot can change at all.
 * </p>
 * <p>
 * A slot is therefore published with {@link VarHandle#compareAndExchange}: the loser of a race drops
 * the section it allocated and answers with the winner's, so every caller of a slot ends up with the
 * same section and no store is ever lost. Taking the write lock inside {@link #section(int)} would
 * not do it and is not a matter of taste — {@code Heightmap#refresh(int)} holds the <em>read</em>
 * lock of the chunk while it walks its columns through {@code Chunk#getSection(int)}, and
 * {@code Chunk#lockWriteLock} asserts that its caller holds no read lock. What this costs when
 * nothing races is one acquiring read of a slot, and a compare and exchange only on the step which
 * was going to allocate a section anyway.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public final class LazySectionBlockStorage implements BlockStorage {

    /**
     * The section every empty slot of every chunk of this process points at.
     * <p>
     * It is never written to. Every write path of this class replaces the slot before it writes, and
     * the two read-only accessors document that a caller must not write through what they return. A
     * write that reached this object would not corrupt one chunk, it would corrupt every chunk whose
     * slot at that height happens to be empty.
     * </p>
     */
    static final Section EMPTY = new Section();

    /**
     * The state id a write has to carry to be worth materialising a shared section for.
     * <p>
     * Read from the registry rather than written down as {@code 0}, so that a Minecraft version which
     * renumbered the states cannot silently turn this guard into one that drops real blocks.
     * </p>
     */
    private static final int AIR_STATE = Block.AIR.stateId();

    /**
     * The edge length of the biome grid of a section.
     * <p>
     * Named here for the same reason {@code SectionBlockStorage} names it: {@code Section} has no
     * constant for it, and a wrong biome divisor is silent rather than loud.
     * </p>
     */
    private static final int BIOME_SIZE = 4;

    private static final DynamicRegistry<Biome> BIOME_REGISTRY = MinecraftServer.getBiomeRegistry();

    /**
     * The handle every read and every write of a slot of {@link #sections} goes through.
     * <p>
     * A {@code VarHandle} over the array rather than an {@code AtomicReferenceArray} because the two
     * differ in what they cost: the atomic array is an object plus a second array per storage, which
     * is a post {@code ChunkFootprintTest} would have to declare and every chunk would pay, while a
     * static handle over the array this class already holds costs nothing per storage and leaves the
     * single threaded read a plain load on every architecture this runs on.
     * </p>
     */
    private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(Section[].class);

    private final int minSection;
    private final Section[] sections;

    /**
     * The list {@link #views()} answers with.
     * <p>
     * It reads through to {@link #sections} rather than copying it, which is what lets a caller take
     * it once and still see a section that was materialised afterwards. It is also why {@link #views()}
     * allocates nothing: the chunk packet builder walks this list on every send, and a list built per
     * send would be a cost this class was written to remove rather than to add.
     * </p>
     */
    private final List<Section> view = new AbstractList<>() {

        @Override
        public Section get(int index) {
            return LazySectionBlockStorage.this.slot(index);
        }

        @Override
        public int size() {
            return LazySectionBlockStorage.this.sections.length;
        }
    };

    /**
     * Creates a storage in which every section is shared.
     *
     * @param minSection   the index of the bottom section of the chunk
     * @param sectionCount the amount of sections the chunk spans
     */
    public LazySectionBlockStorage(int minSection, int sectionCount) {
        this.minSection = minSection;
        this.sections = new Section[sectionCount];
        Arrays.fill(this.sections, EMPTY);
    }

    /**
     * Creates a storage which takes over the given sections.
     * <p>
     * A section which is identical to the shared one is not detected here and is taken over as it
     * stands. Deciding otherwise would mean walking four thousand and ninety six positions per
     * section to find out, which is more than the slot is worth; a caller which knows that a section
     * is empty passes {@link #EMPTY} for it.
     * </p>
     *
     * @param minSection the index of the bottom section of the chunk
     * @param sections   the sections, from the bottom one upwards
     */
    public LazySectionBlockStorage(int minSection, List<Section> sections) {
        this.minSection = minSection;
        this.sections = sections.toArray(new Section[0]);
    }

    /**
     * Creates a storage over an array this class takes ownership of.
     *
     * @param minSection the index of the bottom section of the chunk
     * @param sections   the sections, from the bottom one upwards
     */
    private LazySectionBlockStorage(int minSection, Section[] sections) {
        this.minSection = minSection;
        this.sections = sections;
    }

    @Override
    public Block getBlock(int x, int y, int z, Block.Getter.Condition condition) {
        final Section section = slot(CoordConversion.globalToChunk(y) - this.minSection);

        // US-2.07: a shared section is air everywhere, so the palette is not reached at all. The
        // palette of the shared section would answer the same; this is a shortcut, not a special case.
        if (section == EMPTY) {
            return Block.AIR;
        }
        final int stateId = section.blockPalette()
                .get(x, CoordConversion.globalToSectionRelative(y), z);

        return Objects.requireNonNullElse(Block.fromStateId(stateId), Block.AIR);
    }

    @Override
    public void setBlock(int x, int y, int z, Block block) {
        final int index = CoordConversion.globalToChunk(y) - this.minSection;
        final int stateId = block.stateId();

        if (stateId == AIR_STATE && slot(index) == EMPTY) {
            return;
        }
        materialise(index).blockPalette()
                .set(x, CoordConversion.globalToSectionRelative(y), z, stateId);
    }

    @Override
    public RegistryKey<Biome> getBiome(int x, int y, int z) {
        final Section section = slot(CoordConversion.globalToChunk(y) - this.minSection);
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

        final int index = CoordConversion.globalToChunk(y) - this.minSection;

        // Unlike a block write, a biome write is not skipped when it matches what the shared section
        // holds. The zero of a biome palette is a registry id and not a sentinel, so the id which
        // happens to be zero belongs to a real biome that a caller may legitimately want stored, and
        // it is not this class's business to decide that writing it is a no-op.
        materialise(index).biomePalette()
                .set(x / BIOME_SIZE,
                        CoordConversion.globalToSectionRelative(y) / BIOME_SIZE,
                        z / BIOME_SIZE, id);
    }

    @Override
    public List<Section> sections() {
        for (int index = 0; index < this.sections.length; index++) {
            materialise(index);
        }
        return this.view;
    }

    @Override
    public Section section(int section) {
        return materialise(section);
    }

    @Override
    public Section view(int section) {
        return slot(section);
    }

    @Override
    public List<Section> views() {
        return this.view;
    }

    @Override
    public boolean shared(int section) {
        return slot(section) == EMPTY;
    }

    @Override
    public int materialisedSections() {
        int owned = 0;

        for (int index = 0; index < this.sections.length; index++) {
            if (slot(index) != EMPTY) owned++;
        }
        return owned;
    }

    @Override
    public int sectionCount() {
        return this.sections.length;
    }

    @Override
    public BlockStorage copy() {
        final Section[] copied = new Section[this.sections.length];

        for (int index = 0; index < copied.length; index++) {
            final Section section = slot(index);
            copied[index] = section == EMPTY ? EMPTY : section.clone();
        }
        return new LazySectionBlockStorage(this.minSection, copied);
    }

    @Override
    public void clear() {
        for (int index = 0; index < this.sections.length; index++) {
            final Section section = slot(index);

            if (section == EMPTY) continue;
            // Emptied as well as released. A caller which took the section through the boundary
            // before the reset holds a reference this class cannot reach, and DynamicChunk#reset
            // leaves such a caller with an emptied section rather than with a stale one.
            section.clear();
            // Released with the same handle the materialisation publishes through, so that a
            // materialisation racing this reset either sees the slot before it was released and
            // keeps its section, or sees it afterwards and materialises a new one. A plain store
            // here would leave that compare and exchange comparing against a value it cannot be
            // ordered against.
            SLOT.setRelease(this.sections, index, EMPTY);
        }
    }

    /**
     * Reads what a slot currently holds.
     *
     * @param index the index of the section, counted from the bottom one
     * @return the section the slot holds, which may be the shared one
     */
    private Section slot(int index) {
        return (Section) SLOT.getAcquire(this.sections, index);
    }

    /**
     * Gives a slot a section of its own, if it does not have one yet.
     * <p>
     * The store is a compare and exchange rather than an assignment, and the section this method
     * answers with is the one that ended up in the slot rather than the one it happens to have
     * allocated. Both halves of that matter: the first is what stops a caller without the chunk lock
     * from overwriting a section somebody else already wrote a block into, and the second is what
     * stops the loser of such a race from writing into a section no slot holds. The section the loser
     * allocated is garbage by the time it returns, which is the whole price of the race and is paid
     * only when there is one.
     * </p>
     *
     * @param index the index of the section, counted from the bottom one
     * @return the section the slot holds afterwards, which this storage owns
     */
    private Section materialise(int index) {
        final Section section = slot(index);

        if (section != EMPTY) return section;

        final Section created = new Section();
        final Section witness = (Section) SLOT.compareAndExchange(this.sections, index, EMPTY, created);

        return witness == EMPTY ? created : witness;
    }
}
