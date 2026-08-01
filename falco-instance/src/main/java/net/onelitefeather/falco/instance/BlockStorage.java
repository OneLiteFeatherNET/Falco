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
 * an absolute world height, because that is the form both {@code Chunk} and the anvil format use and
 * translating twice would be a second place to get it wrong.
 * </p>
 * <p>
 * Implementations are not thread-safe on their own. The caller holds the lock of the chunk, which
 * {@code Chunk#lockWriteLock()} and {@code Chunk#lockReadLock()} provide.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public interface BlockStorage {

    /**
     * Reads the block at a position.
     *
     * @param x         the chunk-local block X, {@code 0} to {@code 15}
     * @param y         the absolute block Y
     * @param z         the chunk-local block Z, {@code 0} to {@code 15}
     * @param condition what the caller is willing to accept, as {@code Block.Getter} defines it
     * @return the block, or {@code null} if the condition excludes it
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
     *
     * @param x the chunk-local block X, {@code 0} to {@code 15}
     * @param y the absolute block Y
     * @param z the chunk-local block Z, {@code 0} to {@code 15}
     * @return the biome
     */
    RegistryKey<Biome> getBiome(int x, int y, int z);

    /**
     * Writes a biome to a position.
     *
     * @param x     the chunk-local block X, {@code 0} to {@code 15}
     * @param y     the absolute block Y
     * @param z     the chunk-local block Z, {@code 0} to {@code 15}
     * @param biome the biome to write
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
