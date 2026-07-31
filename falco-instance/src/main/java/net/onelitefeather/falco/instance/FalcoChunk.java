package net.onelitefeather.falco.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.DynamicChunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * The {@link FalcoChunk} class is the chunk of {@link FalcoInstance}.
 * <p>
 * It exists for a single reason: {@code Chunk#onLoad()} and {@code Chunk#unload()} are
 * {@code protected}, so no code outside {@code net.minestom.server.instance} can call them. An
 * instance living in another package therefore cannot tell a chunk that it has been loaded or
 * unloaded, and a chunk which is never told keeps reporting {@code isLoaded() == true} forever —
 * which is exactly the state every {@code ChunkUtils#isLoaded} check in Minestom trusts.
 * </p>
 * <p>
 * A subclass is the honest way out. It compiles, it needs no reflection and no open module, and the
 * only price is that this module drags a chunk implementation along. The alternative, reaching into
 * the hooks with {@code setAccessible}, would break on the next JDK that closes the door and would
 * hide the coupling instead of naming it.
 * </p>
 * <p>
 * A third member has the same problem and needs no work here: the block setter which carries a
 * placement and a destruction is {@code protected} on {@code Chunk} and only widened to public by
 * {@link DynamicChunk}. Extending {@link DynamicChunk} rather than {@code Chunk} therefore also
 * settles that one, which is a second reason for the choice of superclass.
 * </p>
 * <p>
 * Everything else is inherited from {@link DynamicChunk}. This type deliberately adds no storage,
 * no light handling and no packet handling of its own, because the block storage of Minestom is not
 * the part of the instance which needed replacing.
 * </p>
 * <p>
 * Like its superclass, this chunk is not thread-safe on its own. Callers hold the chunk lock, which
 * {@link Chunk#lockWriteLock()} and {@link Chunk#lockReadLock()} provide.
 * </p>
 * <p>
 * This type is experimental. The instance module is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public class FalcoChunk extends DynamicChunk {

    /**
     * Creates an empty chunk at the given position.
     *
     * @param instance the instance which owns the chunk
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     */
    public FalcoChunk(Instance instance, int chunkX, int chunkZ) {
        super(instance, chunkX, chunkZ);
    }

    /**
     * Creates a chunk which takes over the given sections.
     * <p>
     * Used by {@link #copy(Instance, int, int)}; the sections are not cloned here, so a caller has
     * to hand over sections nobody else writes to.
     * </p>
     *
     * @param instance the instance which owns the chunk
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     * @param sections the sections of the chunk, from the bottom one upwards
     */
    protected FalcoChunk(Instance instance, int chunkX, int chunkZ, List<Section> sections) {
        super(instance, chunkX, chunkZ, sections);
    }

    /**
     * Tells the chunk that it has finished loading.
     * <p>
     * This is the reachable form of the {@code protected} {@code Chunk#onLoad()} hook.
     * {@link FalcoInstance} calls it once, after the chunk has been put into the chunk map of the
     * instance and after its tick partition exists, which is the order Minestom uses as well.
     * </p>
     */
    public void markLoaded() {
        onLoad();
    }

    /**
     * Tells the chunk that it is no longer part of its instance.
     * <p>
     * This is the reachable form of the {@code protected} {@code Chunk#unload()} hook. It clears the
     * loaded flag, which is what every {@code ChunkUtils#isLoaded} check in Minestom reads, so a
     * chunk that is not marked here stays alive for the rest of the server for anyone holding a
     * reference to it.
     * </p>
     */
    public void markUnloaded() {
        unload();
    }

    /**
     * Creates a copy of this chunk at the given position.
     * <p>
     * Overridden so the copy is a {@link FalcoChunk} again. The inherited implementation returns a
     * plain {@link DynamicChunk}, and such a chunk could never be unloaded by
     * {@link FalcoInstance} because its hooks are out of reach.
     * </p>
     * <p>
     * The caller has to hold the read lock of this chunk.
     * </p>
     *
     * @param instance the instance which owns the copy
     * @param chunkX   the chunk X of the copy
     * @param chunkZ   the chunk Z of the copy
     * @return a copy of this chunk at the given position
     */
    @Override
    public Chunk copy(Instance instance, int chunkX, int chunkZ) {
        assertReadLock();
        final List<Section> copiedSections = this.sections.stream().map(Section::clone).toList();
        final FalcoChunk copy = new FalcoChunk(instance, chunkX, chunkZ, copiedSections);
        copy.entries.putAll(this.entries);
        copy.tickableMap.putAll(this.tickableMap);
        return copy;
    }
}
