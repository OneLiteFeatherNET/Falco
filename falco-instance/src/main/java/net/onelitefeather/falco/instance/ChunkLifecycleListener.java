package net.onelitefeather.falco.instance;

import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * The {@link ChunkLifecycleListener} interface is how something is told what happens to a chunk,
 * without being that chunk.
 * <p>
 * Before this interface a chunk had exactly one extension point and it was its superclass.
 * {@code FalcoLightingChunk} occupied it, which is why Falco's light and Falco's instance could not
 * be used together at all — {@code FalcoChunk} and {@code FalcoLightingChunk} both extended
 * {@code DynamicChunk}, a class has one superclass, and a server had to pick one of the two. A
 * listener is a field, and a field composes.
 * </p>
 *
 * <h2>Why the block change is not an event</h2>
 * <p>
 * Four of these five methods happen once in the life of a chunk or once per tick, and they carry a
 * {@link ChunkLifecycleEvent}. {@link #onBlockChange} happens once per block written and takes
 * primitives, because an event object there would be an allocation on the hottest path of this
 * module. The asymmetry is deliberate and it is measured rather than argued: see
 * {@code ChunkLifecycleAllocationTest}.
 * </p>
 * <p>
 * Every method is a default doing nothing, so a listener implements what it cares about. Every one of
 * them runs on the thread that caused the transition, under whatever lock that thread holds — a
 * listener which blocks blocks a chunk load, a tick or a block write. Which lock that is, is stated
 * per method, because the five are not the same — and, where the two arms below differ, per arm.
 * </p>
 *
 * <h2>Two instances drive these five, and they do not hold the same locks</h2>
 * <p>
 * A {@link FalcoChunk} is reached through two doors. {@link FalcoInstance} drives it through
 * {@link ChunkLifecycle}, and an {@code InstanceContainer} drives it through the {@code protected}
 * hooks of {@code Chunk} — which is not a corner case but the arrangement US-3.06 was built for:
 * {@code FalcoLightingChunk} is a {@link FalcoChunk} and is meant to run in a plain container, where
 * its listener is the light engine. The two arms differ in what a listener is allowed to do, and the
 * difference is stated per method rather than averaged into one sentence.
 * </p>
 * <p>
 * Written short: only {@link #onPublish} is missing on the container arm, and everything else that
 * differs makes the container arm the <em>stricter</em> of the two — it holds the monitor of the
 * instance where {@link FalcoInstance} holds a per-chunk lock or nothing. A listener written for the
 * container arm therefore works on both, and that is the one to write.
 * </p>
 * <p>
 * This type is experimental. The instance module is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public interface ChunkLifecycleListener {

    /**
     * Reports that a chunk has become part of its instance and has a tick partition.
     * <p>
     * Fired after the position of the chunk was released and therefore outside the lock of that
     * position, which is what makes it safe for a listener to call back into the instance.
     * </p>
     * <p>
     * <b>This is the one of the five which only a {@link FalcoInstance} ever fires.</b> Publishing is
     * a step of {@link ChunkLifecycle} and nothing on a chunk marks it, so a chunk driven by an
     * {@code InstanceContainer} is told that it loaded and never that it was published. A listener
     * which wants one moment per chunk and has to work in both takes {@link #onLoad}.
     * </p>
     *
     * @param event what happened, to which chunk
     */
    default void onPublish(ChunkLifecycleEvent event) {
    }

    /**
     * Reports that a chunk has finished loading and is now reported as loaded.
     * <p>
     * Under a {@link FalcoInstance} this is fired after {@link #onPublish}, outside the lock of the
     * position as well, and before the {@code InstanceChunkLoadEvent} of the server reaches anybody.
     * </p>
     * <p>
     * Under an {@code InstanceContainer} there is no {@link #onPublish} before it and no position of
     * a {@link ChunkRegistry} in the picture at all: the container calls the {@code protected}
     * {@code Chunk#onLoad()} hook itself, from {@code retrieveChunk}, after the chunk is in its map
     * and before it completes the future and dispatches the event. That call holds no lock either —
     * {@code retrieveChunk} is not {@code synchronized}, unlike the unload — and it runs on whichever
     * thread read the chunk, which for a loader with parallel support is a virtual thread of its own.
     * </p>
     * <p>
     * So neither arm holds a lock here, and a listener may call back into its instance. What both
     * arms do pay is time: this call sits between the chunk being ready and the caller of
     * {@code loadChunk} being told, so a slow listener slows down every chunk load.
     * </p>
     *
     * @param event what happened, to which chunk
     */
    default void onLoad(ChunkLifecycleEvent event) {
    }

    /**
     * Reports that a chunk was ticked.
     * <p>
     * Fired on every tick of the chunk, before the block handlers of that chunk run and regardless of
     * whether the chunk holds any, so a listener which needs a heartbeat gets one from every chunk
     * rather than only from the ones that carry a block entity.
     * </p>
     * <p>
     * A tick holds no lock of the chunk at all — {@code Chunk#tick} says of itself that it "doesn't
     * necessary have to be thread-safe" — so a listener which reads blocks here has to take the read
     * lock the way any other reader would.
     * </p>
     * <p>
     * This is the one method where the two arms are the same call. A tick reaches a chunk from the
     * {@code ThreadDispatcher} of the server and never through its instance, so it looks identical
     * whether a {@link FalcoInstance} or an {@code InstanceContainer} owns the chunk.
     * </p>
     *
     * @param event what happened, to which chunk, and at which tick time
     */
    default void onTick(ChunkLifecycleEvent event) {
    }

    /**
     * Reports that a chunk is no longer part of its instance.
     * <p>
     * <b>This is the one of the five which always runs under a lock, on both arms, and they are not
     * the same lock.</b> Under a {@link FalcoInstance} the position of the chunk is held by
     * {@link ChunkRegistry}, because clearing the loaded flag of a chunk and taking it out of the
     * registry are deliberately one step. Everything {@link ChunkRegistry} says about a step handed
     * to it therefore applies to a listener here as well: short, non-blocking, no call back into the
     * instance and no exception, because a throwing listener leaves the chunk removed and only half
     * unloaded.
     * </p>
     * <p>
     * Under an {@code InstanceContainer} there is no position lock, and the constraint is if anything
     * tighter. {@code InstanceContainer#unloadChunk} is {@code synchronized} on the instance for its
     * whole body, so the monitor of the instance is held while this runs; it has also already sent
     * the unload packet, dispatched its event, removed the entities and taken the chunk out of its
     * map before it calls the hook, so a listener asking that instance for this chunk is told there
     * is none. Calling back into the instance from here does not deadlock the calling thread, since
     * an intrinsic monitor is reentrant, but every other thread waiting on a {@code synchronized}
     * method of that instance waits for the listener to return. The rule that holds on both arms is
     * the short one: report, and return.
     * </p>
     * <p>
     * The single exception to the sentence above is the discarded load on the {@link FalcoInstance}
     * arm, which holds nothing because the position was released before the chunk was disowned — see
     * {@link ChunkLifecycle#completeLoad}. A listener may not tell that case apart and must not try.
     * </p>
     * <p>
     * It is also the one which can arrive without {@link #onPublish} and {@link #onLoad} ever having
     * arrived. A chunk whose position was claimed while its loader was still working is told that it
     * was unloaded so that whatever it holds is released, even though it never became part of the
     * instance — see {@link ChunkLifecycle#completeLoad}. A listener which tears down state it built
     * in {@link #onLoad} has to survive being asked to tear down nothing.
     * </p>
     *
     * @param event what happened, to which chunk
     */
    default void onUnload(ChunkLifecycleEvent event) {
    }

    /**
     * Reports that one block of a chunk was written.
     * <p>
     * Fired after the block is in the storage and after the handlers of the old and the new block
     * ran, holding the write lock of the chunk. The position is world coordinates, as the chunk
     * received them.
     * </p>
     * <p>
     * That write lock is all a {@link FalcoInstance} holds, which is the point of {@link BlockWriter}
     * — a write into one chunk does not stop a write into another. An {@code InstanceContainer}
     * reaches the same line through its {@code private synchronized UNSAFE_setBlock} and therefore
     * holds the monitor of the whole instance on top of it. A listener here is on the hottest path of
     * this module either way and belongs nowhere near a blocking call.
     * </p>
     *
     * @param chunk the chunk which received the block
     * @param x     the block X
     * @param y     the block Y
     * @param z     the block Z
     * @param block the block which was written
     */
    default void onBlockChange(FalcoChunk chunk, int x, int y, int z, Block block) {
    }

    /**
     * Composes two listeners into one which notifies both, in order.
     * <p>
     * Composition rather than a list because a list is an object per chunk and an iterator per
     * transition, and almost every chunk of a world has no listener at all. Two listeners nest into
     * one object, three into two, and the allocation happens once, at registration.
     * </p>
     *
     * @param first  the listener notified first
     * @param second the listener notified second
     * @return a listener which notifies both
     * @throws NullPointerException if either listener is null
     */
    static ChunkLifecycleListener of(ChunkLifecycleListener first, ChunkLifecycleListener second) {
        Objects.requireNonNull(first, "the first listener cannot be null");
        Objects.requireNonNull(second, "the second listener cannot be null");
        return new ChunkLifecycleListener() {

            @Override
            public void onPublish(ChunkLifecycleEvent event) {
                first.onPublish(event);
                second.onPublish(event);
            }

            @Override
            public void onLoad(ChunkLifecycleEvent event) {
                first.onLoad(event);
                second.onLoad(event);
            }

            @Override
            public void onTick(ChunkLifecycleEvent event) {
                first.onTick(event);
                second.onTick(event);
            }

            @Override
            public void onUnload(ChunkLifecycleEvent event) {
                first.onUnload(event);
                second.onUnload(event);
            }

            @Override
            public void onBlockChange(FalcoChunk chunk, int x, int y, int z, Block block) {
                first.onBlockChange(chunk, x, y, z, block);
                second.onBlockChange(chunk, x, y, z, block);
            }
        };
    }
}
