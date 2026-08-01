package net.onelitefeather.falco.instance;

import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * The {@link BlockStorage} interface is the implementation side of the chunk of Falco.
 * <p>
 * A chunk of Minestom keeps its blocks in a field its subclasses inherit, which means that a chunk
 * which wants a different memory layout has to be a different chunk class. That is why
 * {@code FalcoChunk} and {@code FalcoLightingChunk} cannot be combined today: both of them extend
 * {@code DynamicChunk}, and a class has one superclass. Moving the storage behind this interface
 * turns those two branches into two parts that compose.
 * </p>
 * <p>
 * The split is drawn where the two sides stop needing each other. Everything that is about the
 * identity of a chunk stays outside: its position, its lifecycle, its viewers, its heightmaps and
 * the packet it sends. Everything that is about where a block physically sits lives here. An
 * implementation of this interface therefore never needs an {@code Instance}, and the chunk never
 * needs to know whether the blocks below it are sections, a packed array or something off heap.
 * </p>
 * <p>
 * Coordinates are chunk-local: {@code x} and {@code z} are {@code 0} to {@code 15}, and {@code y} is
 * an absolute world height, because that is the form the anvil format uses and because a storage has
 * no chunk position to fold a world coordinate against.
 * </p>
 * <p>
 * That asymmetry is the whole point of the rule and it is the caller's job, not the storage's.
 * {@code Chunk#setBlock} is handed instance-level coordinates, so {@code FalcoChunk} masks
 * {@code x} and {@code z} once, on its side of the seam, and every implementation here may index by
 * them directly — which is what makes a packed layout possible at all, since such a layout has no
 * cheap way to detect that it was handed a coordinate belonging to a chunk far away. An
 * implementation is free to reject an out-of-range coordinate, and is expected to do so loudly
 * rather than to fold it back into range: folding turns a caller error into a block written to the
 * wrong place, which no test can distinguish from a block written correctly.
 * </p>
 * <p>
 * Implementations are not thread-safe on their own. The caller holds the lock of the chunk, which
 * {@code Chunk#lockWriteLock()} and {@code Chunk#lockReadLock()} provide.
 * </p>
 *
 * <h2>Two ways to reach a section, and why that is not one too many</h2>
 * <p>
 * {@link #section(int)} and {@link #sections()} answer {@code Chunk#getSection(int)} and
 * {@code Chunk#getSections()}, which are public methods of Minestom that hand a {@code Section} to
 * an arbitrary caller. A storage cannot know whether such a caller reads or writes, so those two
 * have to produce a section the chunk owns. {@link #view(int)} and {@link #views()} are for the
 * chunk itself, which does know: its packet builder, its light data builder and its heightmap scan
 * only read. Without the second pair a lazy layout would be undone from inside the very class that
 * chose it, on the first packet a chunk sends.
 * </p>
 *
 * @author TheMeinerLP
 * @version 2.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public interface BlockStorage {

    /**
     * Reads the block at a position.
     * <p>
     * A storage always answers with a block and never with {@code null}. A stored value the block
     * registry does not know — which a raw palette write or a world from another version can produce
     * — is answered with air, because {@code Block.Getter.Condition#NONE} promises a block "no matter
     * what" and {@code Block.Getter#getBlock(int, int, int)} dereferences the result. The
     * {@code null} case that {@code Chunk#getBlock} has belongs to the chunk, which owns the block
     * entity map the condition selects over; nothing here can answer {@code Condition#CACHED}.
     * </p>
     *
     * @param x         the chunk-local block X, {@code 0} to {@code 15}
     * @param y         the absolute block Y
     * @param z         the chunk-local block Z, {@code 0} to {@code 15}
     * @param condition what the caller is willing to accept, as {@code Block.Getter} defines it
     * @return the block, air if the stored state is unknown, never {@code null}
     */
    Block getBlock(int x, int y, int z, Block.Getter.Condition condition);

    /**
     * Writes a block to a position.
     *
     * @param x     the chunk-local block X, {@code 0} to {@code 15}
     * @param y     the absolute block Y
     * @param z     the chunk-local block Z, {@code 0} to {@code 15}
     * @param block the block to write
     */
    void setBlock(int x, int y, int z, Block block);

    /**
     * Reads the biome at a position.
     * <p>
     * Biomes are stored by their registry id, so a storage that was filled through a raw palette
     * write can hold an id no biome answers to. Such a read fails rather than returning
     * {@code null}: the caller of a biome getter has no reasonable substitute the way an unknown
     * block has air, and a {@code null} would surface far from the write that caused it.
     * </p>
     *
     * @param x the chunk-local block X, {@code 0} to {@code 15}
     * @param y the absolute block Y
     * @param z the chunk-local block Z, {@code 0} to {@code 15}
     * @return the biome, never {@code null}
     * @throws NullPointerException if the stored id belongs to no registered biome
     */
    RegistryKey<Biome> getBiome(int x, int y, int z);

    /**
     * Writes a biome to a position.
     * <p>
     * An unregistered biome is rejected here rather than stored. A biome registry lookup answers a
     * miss with {@code -1}, and a palette accepts that value like any other, counts it and hands it
     * to the chunk packet — so a storage that did not check would turn a caller error into a corrupt
     * chunk that only fails on a read, or on a client, long afterwards.
     * </p>
     *
     * @param x     the chunk-local block X, {@code 0} to {@code 15}
     * @param y     the absolute block Y
     * @param z     the chunk-local block Z, {@code 0} to {@code 15}
     * @param biome the biome to write
     * @throws IllegalStateException if the biome is not registered
     */
    void setBiome(int x, int y, int z, RegistryKey<Biome> biome);

    /**
     * Hands out the sections of this storage, from the bottom one upwards.
     * <p>
     * This is a boundary method. Minestom demands {@code Section} objects for packet serialisation,
     * for its light engine and for the anvil writer, so an implementation which does not store them
     * has to materialise them here. Calling this is therefore the one operation that can undo
     * whatever an implementation saved by not holding them, which is why the chunk calls it only
     * where Minestom leaves no choice.
     * </p>
     *
     * @return the sections of this storage
     */
    List<Section> sections();

    /**
     * Hands out one section of this storage.
     *
     * @param section the index of the section, counted from the bottom one
     * @return the section
     */
    Section section(int section);

    /**
     * Reports how many sections this storage spans.
     *
     * @return the amount of sections
     */
    int sectionCount();

    /**
     * Hands out one section of this storage as it stands, without creating one.
     * <p>
     * This is the read-only counterpart of {@link #section(int)} and the difference between the two
     * is the whole economy of a lazy layout. {@link #section(int)} exists to answer
     * {@code Chunk#getSection(int)}, which gives a {@code Section} to a caller this storage knows
     * nothing about — a chunk loader, the light engine of Minestom, the generator of an
     * {@code InstanceContainer} — and every one of those may write into it, so the slot has to hold a
     * section of its own before it is handed over. This method promises the opposite: the caller only
     * reads, so an implementation which shares one section between every empty slot may hand that
     * shared section out instead of creating a private one.
     * </p>
     * <p>
     * The contract that comes with it is therefore sharp, and violating it corrupts more than one
     * chunk: <strong>the returned section must not be written to</strong>, neither through its
     * palettes nor through its light carriers, and it must not be kept beyond the call. A write
     * through a shared section is not a write to this chunk, it is a write to every chunk in the
     * process whose slot at that height happens to be empty.
     * </p>
     * <p>
     * The section a view answers with is always the one the storage currently holds, so a view taken
     * before a write shows the write. An implementation must not answer from a snapshot.
     * </p>
     *
     * @param section the index of the section, counted from the bottom one
     * @return the section as it stands, which may be shared with other chunks
     */
    Section view(int section);

    /**
     * Hands out the sections of this storage as they stand, without creating any.
     * <p>
     * The same contract as {@link #view(int)}, over the whole chunk: read only, do not keep, and
     * expect a shared section wherever the chunk holds nothing. An implementation is expected to
     * answer with a list it owns rather than with a fresh one, because this is the method the packet
     * builder of a chunk walks, and a list allocated per send is a cost this stage exists to remove
     * rather than to add.
     * </p>
     *
     * @return the sections as they stand, from the bottom one upwards
     */
    List<Section> views();

    /**
     * Reports whether a section is still shared with other chunks rather than owned by this one.
     * <p>
     * The question a caller which is about to write needs answered without triggering the write it is
     * asking about. {@code FalcoInstance} uses it to decide whether a generated section is worth
     * committing at all, and the tests of this stage use it to prove that a saving happened rather
     * than assuming it.
     * </p>
     *
     * @param section the index of the section, counted from the bottom one
     * @return whether the slot still points at a section this storage does not own
     */
    boolean shared(int section);

    /**
     * Reports how many sections this storage owns rather than shares.
     * <p>
     * The one number that makes the whole stage assertable. Every claim about a saving is a claim
     * about this counter, and every boundary method that materialises raises it, so a test can state
     * exactly what a chunk send, a generator run or a save costs instead of estimating it.
     * </p>
     *
     * @return the amount of sections this storage holds of its own, between zero and
     *         {@link #sectionCount()}
     */
    int materialisedSections();

    /**
     * Creates a storage holding the same blocks and biomes as this one, sharing nothing with it.
     *
     * @return the copy
     */
    BlockStorage copy();

    /**
     * Resets this storage to the state it had when it was created.
     */
    void clear();
}
