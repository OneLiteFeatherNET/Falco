package net.onelitefeather.falco.light;

import net.minestom.server.instance.Instance;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.play.UpdateLightPacket;
import net.onelitefeather.falco.instance.FalcoChunk;
import net.onelitefeather.falco.instance.FalcoInstance;
import org.jetbrains.annotations.ApiStatus;


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
 * <b>Why this needs {@code falco-instance} on the compile path.</b> It used to need nothing but the
 * light engine, and that argument stopped holding the moment this class became a
 * {@link FalcoChunk}: a chunk cannot be one without the module that defines it. The dependency is
 * {@code compileOnly}, so the light engine itself — {@link ChunkLightService},
 * {@code ChunkLightPropagator}, {@link ChunkLightScheduler}, the nibble handling — keeps compiling
 * and running with {@code falco-instance} absent, and only this class and {@link ChunkLightListener}
 * need it. A consumer who calls {@link ChunkLightScheduler#supplier()} therefore has to put both
 * modules on the classpath, which {@code falco-bom} publishes next to one another; a consumer of the
 * bare light engine needs neither. An {@code api} dependency was rejected for exactly that
 * asymmetry, since it would push {@code falco-instance} onto every server running a plain
 * {@code InstanceContainer}.
 * </p>
 * <p>
 * <b>This class holds no computation logic on purpose.</b> Two overrides now, and both of them are
 * about a packet rather than about light: {@code invalidate} drops the cached light packet and
 * {@code onLightUpdated} drops it and sends a fresh one. Everything the chunk used to report — the
 * block change, the load, the tick — moved into {@link ChunkLightListener}, and the dirty set, the
 * areas, the executor and the back pressure live in {@link ChunkLightScheduler}. A reader looking
 * for the behaviour finds it in one place rather than spread across a chunk and a scheduler.
 * </p>
 * <p>
 * <b>What the change of superclass bought.</b> A {@link FalcoChunk} allocates no section until
 * something writes into one and builds its two heightmaps on the first question rather than in a
 * field initialiser, so a fresh chunk of this class retains {@code 840} bytes in 25 objects where
 * the {@code DynamicChunk} it used to extend retains {@code 6 848} in 192 — the figures
 * {@code ChunkFootprintTest} measures with jol 0.17 on OpenJDK 25.0.3 over an overworld chunk of 24
 * sections. The light itself is unaffected: writing light materialises the sections it writes into,
 * exactly as before.
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
 * unloaded chunk, which makes a batch against such a chunk silently do nothing. {@link FalcoChunk}
 * has the same property {@code DynamicChunk} has — a freshly constructed chunk reports itself
 * loaded — so inheriting from it avoids the trap, and rebuilding it here would be a defect, not a
 * feature.
 * </p>
 * <p>
 * <b>The batch light gap is closed from this side.</b> {@code AbsoluteBlockBatch#apply} ends by
 * calling {@code sendLighting()} on every touched chunk that is a {@code LightingChunk} and skips
 * every other type, so this chunk would never be resent by it. It does not have to be: a batch
 * writes through {@code setBlock}, which reports the position to {@link ChunkLightListener}, so the
 * following tick computes the light of the whole touched region and sends it through
 * {@link #onLightUpdated()}. The result arrives one tick later than Minestom's would, and it arrives
 * for the ring around the batch as well, which Minestom's path does not manage.
 * </p>
 * <p>
 * <b>{@code copy} is deliberately not overridden.</b> Minestom copies a chunk into <em>another</em>
 * instance, and a scheduler serves exactly one. A copy that kept this binding would turn the first
 * block change placed into it into an {@link IllegalStateException}, because reporting that change
 * would try to bind a second instance. {@link FalcoChunk#copy(Instance, int, int)} returns a plain
 * {@link FalcoChunk} carrying the copied storage and therefore the light as a snapshot, with nothing
 * updating it afterwards and no listener on it — which is the only correct answer here, and which
 * {@link FalcoInstance} can still unload, unlike the {@code DynamicChunk} the old superclass handed
 * back.
 * </p>
 * <p>
 * <b>Final, where it used to be open.</b> Nothing forced it open but the rule that let it be: a
 * class extending a Minestom type is exempt from {@code PublicApiTest#publicClassesAreFinal}, and
 * this one no longer extends one. Closing it is also the point of the stage. What a subclass of this
 * chunk would have wanted — a second thing happening on a load, a tick or a block write — is exactly
 * what {@code FalcoChunk#addLifecycleListener} now gives without a superclass slot, and a subclass
 * would take back the one that was just freed.
 * </p>
 * <p>
 * This type is experimental. The light engine is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 2.0.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public final class FalcoLightingChunk extends FalcoChunk implements LightUpdateAware {

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
     * <p>
     * The listener is added here and not by whoever builds the instance, because a chunk which is
     * handed out by a supplier has no other moment before its blocks arrive: a generator writes into
     * the chunk immediately after this constructor returns.
     * </p>
     *
     * @param scheduler the scheduler which decides when the light of this chunk is computed
     * @param instance  the instance this chunk belongs to
     * @param chunkX    the chunk x coordinate
     * @param chunkZ    the chunk z coordinate
     */
    public FalcoLightingChunk(ChunkLightScheduler scheduler, Instance instance, int chunkX, int chunkZ) {
        super(instance, chunkX, chunkZ);
        addLifecycleListener(new ChunkLightListener(scheduler));
    }

    /**
     * Drops the cached light packet along with everything else this chunk derived from its blocks.
     *
     * @see FalcoChunk#invalidate()
     */
    @Override
    public void invalidate() {
        super.invalidate();
        this.lightCache.invalidate();
    }

    /**
     * Sends the freshly computed light of this chunk to everybody who is looking at it.
     * <p>
     * This is the step Minestom has no hook for. {@code Chunk#invalidate} drops the cached full chunk
     * packet, which carries the light inside it, but that only reaches a player who receives the
     * chunk afterwards — somebody already standing in it would see the old light until they reload.
     * {@code LightingChunk} solves this with a resend timer and a private packet cache; the same
     * result is reached here through the one piece of that machinery which is reachable from the
     * outside, the {@code protected createLightData}.
     * </p>
     * <p>
     * This is also why the packet cache stays on the chunk while the three reports moved to a
     * listener: it is per chunk, and a listener registered once for a whole instance has nowhere to
     * keep it.
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
