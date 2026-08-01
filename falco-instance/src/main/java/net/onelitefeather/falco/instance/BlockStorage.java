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
 * @author TheMeinerLP
 * @version 1.1.0
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
