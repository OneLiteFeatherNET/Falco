package net.onelitefeather.falco.light;

import net.minestom.server.instance.DynamicChunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.play.UpdateLightPacket;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;


/**
 * The {@link FalcoLightingChunk} class is a chunk that keeps its own light up to date.
 * <p>
 * Using the light engine has been manual until now: a caller had to notice that a chunk changed,
 * decide when to recompute it and call {@link ChunkLightService} itself. Minestom sets a much lower
 * bar — {@code instance.setChunkSupplier(LightingChunk::new)} and light maintains itself. This class
 * offers the same entry point without giving up what the engine gained, because the computation
 * still happens in a service that is thread safe per call and hands its results over through
 * {@code Light#set} rather than being welded to one chunk implementation. Minestom's own engine is
 * the more restricted one here: it computes light only for {@code LightingChunk}, so anyone using a
 * different chunk gets none at all.
 * </p>
 * <p>
 * <b>Why this lives in {@code falco-light} and not in {@code falco-instance}.</b> A replacement for
 * {@code LightingChunk} needs the light engine and nothing else — it holds no computation logic of
 * its own, it only reports what changed and when the tick happened. Putting it next to
 * {@code FalcoInstance} would create a dependency from one published module to another for a type
 * that has no relationship to an instance implementation, while putting it here creates no new
 * coupling at all. A {@code FalcoInstance} does not need a lighting chunk, and this chunk does not
 * need a {@code FalcoInstance}: it works on any {@code Instance}, including the plain
 * {@code InstanceContainer} the server ships with.
 * </p>
 * <p>
 * <b>This class holds no computation logic on purpose.</b> Three overrides, and every one of them
 * only reports something to the scheduler. Everything else — the dirty set, the areas, the executor,
 * the back pressure — lives in {@link ChunkLightScheduler}, so a reader looking for the behaviour
 * finds it in one place rather than spread across a chunk and a scheduler.
 * </p>
 * <p>
 * <b>What it reports is a position, not just a chunk.</b> {@code setBlock} knows exactly which block
 * moved, and handing that on is what lets the engine replay one position instead of searching nine
 * chunks. A chunk that arrives from a generator or a loader has no such position to offer, so
 * {@code onLoad} reports the change as one of unknown extent and pays for one search of the chunk.
 * </p>
 * <p>
 * <b>{@code createLightData} is deliberately not overridden.</b> It reads the sections, and those
 * are exactly what the light is written into, so the inherited implementation already returns the
 * current state and already never blocks. An override would add a second path to the same data with
 * nothing to gain. If a computation is still running, the previous result is handed out, which is
 * the intended behaviour and not a gap.
 * </p>
 * <p>
 * <b>{@code isLoaded} is deliberately not overridden either.</b> {@code LightingChunk} answers
 * {@code super.isLoaded() && doneInit} and only sets {@code doneInit} in its {@code protected
 * onLoad()}, so a freshly constructed one reports itself unloaded. Both {@code ChunkBatch} and
 * {@code AbsoluteBlockBatch} begin with a check on exactly that and return with a warning about an
 * unloaded chunk, which makes a batch against such a chunk silently do nothing. Inheriting from
 * {@link DynamicChunk} avoids the trap, and rebuilding it here would be a defect, not a feature.
 * </p>
 * <p>
 * <b>The batch light gap is closed from this side.</b> {@code AbsoluteBlockBatch#apply} ends by
 * calling {@code sendLighting()} on every touched chunk that is a {@code LightingChunk} and skips
 * every other type, so this chunk would never be resent by it. It does not have to be: a batch
 * writes through {@code setBlock}, which marks the chunk dirty here, so the following tick computes
 * the light of the whole touched region and sends it through {@link #onLightUpdated()}. The result
 * arrives one tick later than Minestom's would, and it arrives for the ring around the batch as
 * well, which Minestom's path does not manage.
 * </p>
 * <p>
 * <b>{@code copy} is deliberately not overridden.</b> Minestom copies a chunk into <em>another</em>
 * instance, and a scheduler serves exactly one. A copy that kept this binding would turn the first
 * block change placed into it into an {@link IllegalStateException}, because reporting that change
 * would try to bind a second instance. The inherited implementation returns a {@link DynamicChunk}
 * that carries the cloned sections and therefore the light as a snapshot, with nothing updating it
 * afterwards — which is the only correct answer here. Note that this is the opposite of
 * {@code FalcoChunk}, which does override {@code copy} so its instance can still unload the copy;
 * the two look inconsistent and are not.
 * </p>
 * <p>
 * This type is experimental. The light engine is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public class FalcoLightingChunk extends DynamicChunk implements LightUpdateAware {

    private final ChunkLightScheduler scheduler;

    /**
     * The light packet of this chunk, rebuilt only when somebody asks for it after an invalidation.
     */
    private final CachedPacket lightCache = new CachedPacket(
            () -> new UpdateLightPacket(getChunkX(), getChunkZ(), createLightData(false))
    );

    /**
     * Creates a chunk which reports its changes to the given scheduler.
     * <p>
     * The scheduler comes first because {@code ChunkSupplier} fixes the trailing three parameters,
     * which lets {@link ChunkLightScheduler#supplier()} bind it with a method reference.
     * </p>
     *
     * @param scheduler the scheduler which decides when the light of this chunk is computed
     * @param instance  the instance this chunk belongs to
     * @param chunkX    the chunk x coordinate
     * @param chunkZ    the chunk z coordinate
     */
    public FalcoLightingChunk(ChunkLightScheduler scheduler, Instance instance, int chunkX, int chunkZ) {
        super(instance, chunkX, chunkZ);
        this.scheduler = scheduler;
    }

    /**
     * Reports the changed position, which is what lets the light be updated rather than searched.
     * <p>
     * The block is written first and reported afterwards, so a pass which reads the block states of
     * this chunk between the two either sees the new block and the position, or neither, and never
     * the block without the position.
     * </p>
     *
     * @param x         the x coordinate of the block
     * @param y         the y coordinate of the block
     * @param z         the z coordinate of the block
     * @param block     the block to place
     * @param placement the placement rule of the block, or null if there is none
     * @param destroy   the destroy rule of the replaced block, or null if there is none
     */
    @Override
    public void setBlock(int x, int y, int z, Block block,
                         @Nullable BlockHandler.Placement placement,
                         @Nullable BlockHandler.Destroy destroy) {
        super.setBlock(x, y, z, block, placement, destroy);
        this.scheduler.markChanged(this.instance, this.chunkX, this.chunkZ, x, y, z);
    }

    /**
     * Reports this chunk dirty as soon as the instance has taken it.
     * <p>
     * Without this a world that is only ever read would stay black: no block ever changes, so
     * nothing would ever ask for the light of a chunk that came straight from a loader or a
     * generator. The neighbours are reported with it, because a chunk that appears next to an
     * already lit one can send light into it that was not there when it was lit.
     * </p>
     * <p>
     * The blocks of a freshly generated chunk arrive without passing {@code setBlock}, so this is
     * reported as a change of an unknown extent and the chunk is lit from its block states once.
     * </p>
     */
    @Override
    protected void onLoad() {
        super.onLoad();
        this.scheduler.markChanged(this.instance, this.chunkX, this.chunkZ);
    }

    @Override
    public void tick(long time) {
        super.tick(time);
        this.scheduler.onTick(this.instance, time);
    }

    @Override
    public void invalidate() {
        super.invalidate();
        this.lightCache.invalidate();
    }

    /**
     * Sends the freshly computed light of this chunk to everybody who is looking at it.
     * <p>
     * This is the step Minestom has no hook for. {@code DynamicChunk#invalidate} drops the cached
     * full chunk packet, which carries the light inside it, but that only reaches a player who
     * receives the chunk afterwards — somebody already standing in it would see the old light until
     * they reload. {@code LightingChunk} solves this with a resend timer and a private packet cache;
     * the same result is reached here through the one piece of that machinery which is reachable
     * from the outside, the {@code protected createLightData}.
     * </p>
     */
    @Override
    public void onLightUpdated() {
        if (!isLoaded()) {
            return;
        }
        this.lightCache.invalidate();
        sendPacketToViewers(this.lightCache);
    }
}
