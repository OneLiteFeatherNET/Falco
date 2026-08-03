package net.onelitefeather.falco.light;

import net.minestom.server.instance.block.Block;
import net.onelitefeather.falco.instance.ChunkLifecycleEvent;
import net.onelitefeather.falco.instance.ChunkLifecycleListener;
import net.onelitefeather.falco.instance.FalcoChunk;
import org.jetbrains.annotations.ApiStatus;

/**
 * The {@link ChunkLightListener} class reports the changes of a chunk to a
 * {@link ChunkLightScheduler}, without being that chunk.
 * <p>
 * These three reports used to be three overrides of {@link FalcoLightingChunk}, which meant that
 * light occupied the only extension point a chunk had: a class has one superclass, so a server which
 * wanted Falco's light and anything else on the same chunk had to pick one. As a listener they
 * compose, and the chunk keeps only what genuinely needs to live on it — the cached light packet,
 * which is per chunk and cannot be held by a listener registered once for a whole instance.
 * </p>
 * <p>
 * What is reported is a position and not merely a chunk. {@link #onBlockChange} knows exactly which
 * block moved, and handing that on is what lets the engine replay one position instead of searching
 * nine chunks; a chunk which arrives from a generator or a loader has no such position to offer, so
 * {@link #onLoad} reports a change of unknown extent and pays for one search.
 * </p>
 * <p>
 * Two of the five transitions are deliberately not implemented. A publish happens before the loaded
 * flag of the chunk is set and therefore before {@code ChunkLightScheduler#deliver} would find the
 * chunk worth sending to, and an unload needs nothing: the entry of a chunk which left its instance
 * is dropped by the next pass that looks for it, which {@code ChunkLightScheduler#compute} states
 * where it does it.
 * </p>
 * <p>
 * One listener belongs to one scheduler and therefore to one instance, exactly like the scheduler it
 * reports to. It holds no state of its own beyond that reference, so the same instance can be
 * registered on every chunk of that instance.
 * </p>
 * <p>
 * This type is experimental. The light engine is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public final class ChunkLightListener implements ChunkLifecycleListener {

    /**
     * The scheduler which decides when the light of a chunk is computed.
     */
    private final ChunkLightScheduler scheduler;

    /**
     * Creates a listener reporting to a scheduler.
     *
     * @param scheduler the scheduler which decides when the light of a chunk is computed
     */
    public ChunkLightListener(ChunkLightScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Reports the changed position, which is what lets the light be updated rather than searched.
     * <p>
     * The block is written first and reported afterwards, which is a property of
     * {@code FalcoChunk#setBlock} rather than of this class: a pass which reads the block states of
     * that chunk between the two either sees the new block and the position, or neither, and never
     * the block without the position.
     * </p>
     *
     * @param chunk the chunk which received the block
     * @param x     the block X
     * @param y     the block Y
     * @param z     the block Z
     * @param block the block which was written
     */
    @Override
    public void onBlockChange(FalcoChunk chunk, int x, int y, int z, Block block) {
        this.scheduler.markChanged(chunk.getInstance(), chunk.getChunkX(), chunk.getChunkZ(), x, y, z);
    }

    /**
     * Reports the chunk dirty as soon as its instance has taken it.
     * <p>
     * Without this a world that is only ever read would stay black: no block ever changes, so nothing
     * would ever ask for the light of a chunk that came straight from a loader or a generator. The
     * neighbours are reported with it, because a chunk that appears next to an already lit one can
     * send light into it that was not there when it was lit.
     * </p>
     * <p>
     * The blocks of a freshly generated chunk arrive without passing {@code setBlock}, so this is
     * reported as a change of unknown extent and the chunk is lit from its block states once.
     * </p>
     *
     * @param event the chunk which finished loading
     */
    @Override
    public void onLoad(ChunkLifecycleEvent event) {
        final FalcoChunk chunk = event.chunk();
        this.scheduler.markChanged(chunk.getInstance(), chunk.getChunkX(), chunk.getChunkZ());
    }

    /**
     * Drives the scheduler, once per tick of every chunk it is installed on.
     * <p>
     * A pass has to see every change of the tick before it forms its areas, so the scheduler and not
     * this listener decides that only the first chunk of a tick runs one. What matters here is that
     * every chunk reports: a listener which only spoke for the chunks holding a block entity would
     * leave an instance of ordinary chunks without a heartbeat.
     * </p>
     *
     * @param event the chunk which was ticked, and the tick time
     */
    @Override
    public void onTick(ChunkLifecycleEvent event) {
        this.scheduler.onTick(event.chunk().getInstance(), event.time());
    }
}
