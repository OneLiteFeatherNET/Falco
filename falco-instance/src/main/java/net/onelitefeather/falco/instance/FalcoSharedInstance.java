package net.onelitefeather.falco.instance;

import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.SharedInstance;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.utils.chunk.ChunkSupplier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A {@link SharedInstance} which keeps its own configuration instead of writing it through to the
 * container it borrows its chunks from.
 * <p>
 * This is a subclass of {@link SharedInstance} rather than a type of its own, and the reason is a
 * single static method. {@code SharedInstance#areLinked} decides whether a player who moves between
 * two instances keeps the chunks it already has or receives all of them again, and it is consulted
 * in exactly one place: {@code Player#setInstance}. It compares
 * {@link SharedInstance#getInstanceContainer()} rather than testing for a concrete class, so a
 * subclass inherits the answer. A separate type with the same behaviour would answer false, nothing
 * would fail, nothing would be logged, and every instance change would silently cost a full resend
 * of the view distance.
 * </p>
 * <p>
 * The price of the subclass is that the block owner has to be an {@link InstanceContainer}: that is
 * the only constructor {@link SharedInstance} has. A world used this way therefore sets its chunk
 * supplier to {@code FalcoChunk::new} on the container and keeps everything stages one and two
 * bought at the chunk, while the container keeps its own write path.
 * </p>
 * <p>
 * Registration goes through {@code InstanceManager#registerSharedInstance}. Its sibling
 * {@code createSharedInstance} always constructs the stock type and can never produce this one, and
 * {@code registerInstance} refuses anything that is a {@link SharedInstance} outright.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.4.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public class FalcoSharedInstance extends SharedInstance {

    /**
     * The generator of this instance, which is deliberately not the generator of the container.
     * <p>
     * Volatile because a shared instance is configured from wherever the world is set up and read
     * from wherever it is asked, and those are not the same thread.
     * </p>
     */
    private volatile @Nullable Generator generator;

    /**
     * The chunk supplier of this instance, which is deliberately not the one of the container.
     * <p>
     * Volatile for the same reason the generator is: the thread which configures a view is not the
     * thread which reads it back.
     * </p>
     */
    private volatile ChunkSupplier chunkSupplier;

    /**
     * Whether this instance pulls chunks into the world when it is asked for one it has not got.
     * <p>
     * Volatile for the reason the other two fields are: a view is configured from wherever the world
     * is set up and read from the thread which happens to ask it for a chunk.
     * </p>
     */
    private volatile boolean autoChunkLoad;

    /**
     * Creates a view over the chunks of a container.
     * <p>
     * The configuration of the container is copied once, here. That is what makes a fresh view
     * behave like the world it looks at while still being able to diverge from it — the alternative,
     * starting empty, would answer {@code null} to {@link #generator()} on a world that has one.
     * </p>
     *
     * @param uuid              the identity of this instance
     * @param instanceContainer the container which owns the chunks this instance shows
     */
    public FalcoSharedInstance(UUID uuid, InstanceContainer instanceContainer) {
        super(uuid, Objects.requireNonNull(instanceContainer, "a shared instance needs a container to share"));
        this.generator = instanceContainer.generator();
        this.chunkSupplier = instanceContainer.getChunkSupplier();
        this.autoChunkLoad = instanceContainer.hasEnabledAutoChunkLoad();
    }

    /**
     * Gets the generator of this instance.
     * <p>
     * The answer is what {@link #setGenerator(Generator)} last stored on this view, or the
     * generator the container carried when this view was created — never a read of the container as
     * it stands now. That is the repair: two views over one container answer independently instead
     * of overwriting each other. It is also the limit: no chunk is ever generated from this value,
     * because chunks are created by the container and the container asks its own generator. Ask
     * {@code getInstanceContainer().generator()} for the one that actually builds the world.
     * </p>
     *
     * @return the generator of this instance, null if it has none
     */
    @Override
    public @Nullable Generator generator() {
        return this.generator;
    }

    /**
     * Sets the generator of this instance, and of nothing else.
     * <p>
     * Minestom's shared instance forwards this call to its container, which means that configuring
     * one view reconfigures the world and every other view of it. That is the defect this class
     * exists to repair, and repairing it has a consequence worth stating: no chunk is generated from
     * this value. Chunks are created by the container, and the container asks its own generator. Use
     * {@code getInstanceContainer().setGenerator(…)} to decide what the world is made of.
     * </p>
     *
     * @param generator the generator of this instance, null to have none
     */
    @Override
    public void setGenerator(@Nullable Generator generator) {
        this.generator = generator;
    }

    /**
     * Gets the chunk supplier of this instance.
     * <p>
     * The answer is what {@link #setChunkSupplier(ChunkSupplier)} last stored on this view, or the
     * supplier the container carried when this view was created — never a read of the container as it
     * stands now. Two views over one container therefore answer independently instead of overwriting
     * each other. The value is also inert: no chunk is ever created from it, because chunks are
     * created by the container, which asks its own supplier, and a chunk loader is handed the
     * container rather than the view. Ask {@code getInstanceContainer().getChunkSupplier()} for the
     * one the world is actually built from.
     * </p>
     *
     * @return the chunk supplier of this instance
     */
    @Override
    public ChunkSupplier getChunkSupplier() {
        return this.chunkSupplier;
    }

    /**
     * Sets the chunk supplier of this instance, and of nothing else.
     * <p>
     * Minestom's shared instance forwards this call to its container, so configuring one view
     * changes what type of chunk the whole world is made of, for every other view along with it.
     * That is the defect repaired here, and the repair has the same consequence the generator has:
     * no chunk is created from this value, because chunks are created by the container and the
     * container asks its own supplier — as does a chunk loader, which is handed the container rather
     * than the view. Use {@code getInstanceContainer().setChunkSupplier(FalcoChunk::new)} to decide
     * what the world is built from.
     * </p>
     *
     * @param chunkSupplier the chunk supplier of this instance
     * @throws NullPointerException if {@code chunkSupplier} is null
     */
    @Override
    public void setChunkSupplier(ChunkSupplier chunkSupplier) {
        this.chunkSupplier = Objects.requireNonNull(chunkSupplier, "the chunk supplier cannot be null");
    }

    /**
     * Decides whether this instance pulls chunks into the world on demand.
     * <p>
     * Minestom's shared instance forwards this to its container, which turns a per-view decision
     * into a per-world one. Here the value stays with the view, so two views over one container no
     * longer overwrite each other. The one method that reads it is
     * {@link #loadOptionalChunk(int, int)}; it does <em>not</em> reach {@code setBlock} — that call
     * belongs to the container and asks the container's flag, for the reason given in the class
     * documentation.
     * </p>
     * <p>
     * One method is not a small reach. Minestom routes the player view, entity spawns, entity
     * teleports and player instance changes through {@link #loadOptionalChunk(int, int)}, and most
     * of those callers do not survive the {@code null} it hands back once this is off — the list is
     * on that method. Passing {@code false} therefore configures a failure mode rather than a
     * saving, and because the value is now the view's, it is a failure mode stock
     * {@link SharedInstance} could only produce for a whole world at once. Use it on a view whose
     * chunks are brought in through the container beforehand.
     * </p>
     *
     * @param enable true to pull chunks in on demand
     */
    @Override
    public void enableAutoChunkLoad(boolean enable) {
        this.autoChunkLoad = enable;
    }

    /**
     * Gets whether this instance pulls chunks into the world on demand.
     * <p>
     * The answer is what {@link #enableAutoChunkLoad(boolean)} last stored on this view, or the
     * setting the container carried when this view was created — never a read of the container as it
     * stands now. Two views over one container therefore answer independently. Unlike the generator
     * and the chunk supplier this value is not inert: {@link #loadOptionalChunk(int, int)} consults
     * it, and every Minestom path that wants a chunk this world has not got yet goes through that
     * method — five call sites, enumerated there. So the players and entities of this view, and of
     * no other view, feel the answer. It stops at the block write: {@code setBlock} is forwarded to
     * the container and asks {@code getInstanceContainer().hasEnabledAutoChunkLoad()}.
     * </p>
     *
     * @return true if it does
     */
    @Override
    public boolean hasEnabledAutoChunkLoad() {
        return this.autoChunkLoad;
    }

    /**
     * Hands back the chunk at a position, loading it only if this instance is allowed to.
     * <p>
     * A chunk the container already holds is handed back whatever the flag says: the flag decides
     * whether this view may cause a load, not whether it may see the world. Only the second branch
     * consults it, and only that branch is a decision this instance is entitled to make — the chunk
     * itself is still created, cached and published by the container.
     * </p>
     *
     * <h4>What a null answer costs its caller</h4>
     * <p>
     * Handing back {@code null} is not a clean skip anywhere in Minestom. Five call sites reach this
     * method, and each of them treats the absent chunk differently:
     * </p>
     * <ul>
     *   <li>{@code Player#chunkAdder} chains {@code thenAccept(Player::sendChunk)}, and
     *       {@code sendChunk} dereferences its argument on its first line. The result is a
     *       {@link NullPointerException} parked in a future nobody observes — the chunk is not
     *       skipped, the send path is aborted mid-way.</li>
     *   <li>{@code Entity#setInstance} requires the chunk to be non-null before it registers the
     *       entity with the tracker and spawns it. The throwable is handed to the
     *       {@code ExceptionManager} and the entity never arrives in this view at all, while a
     *       sibling view over the same container would have taken it.</li>
     *   <li>{@code Player#setInstance} pre-loads the surrounding chunks and reads an already
     *       completed future as a loaded chunk, then walks into the {@code Entity#setInstance}
     *       above through {@code spawnPlayer}.</li>
     *   <li>{@code Entity} teleport chains {@code thenRun} and carries on regardless, so the entity
     *       ends up standing in a chunk this view has not got.</li>
     *   <li>{@code ChunkUtils#optionalLoadAll} only counts the futures; Minestom's own comment
     *       there warns that the player will be stuck.</li>
     * </ul>
     * <p>
     * Stock {@link SharedInstance} can reach every one of those states too, but only for a whole
     * world at a time, because the flag it asks belongs to the container. Per view is the new part:
     * this view can be that strict while its siblings and the container are not, and that is the
     * consequence to weigh before turning the flag off.
     * </p>
     * <p>
     * The flag consulted is this view's alone, which has a consequence in the other direction: a view
     * whose flag is on loads a chunk even where the container's own flag is off, because an explicit
     * {@code loadChunk} was never governed by that flag either. It takes a deliberate act to get
     * there — a fresh view is seeded from the container, so the setting has to be turned back on at
     * the view after the container refused it.
     * </p>
     *
     * @param chunkX the chunk X
     * @param chunkZ the chunk Z
     * @return a future completed with the chunk, or with null if it is absent and may not be loaded
     */
    @Override
    public CompletableFuture<@Nullable Chunk> loadOptionalChunk(int chunkX, int chunkZ) {
        final InstanceContainer container = getInstanceContainer();
        final Chunk loaded = container.getChunk(chunkX, chunkZ);
        if (loaded != null) return CompletableFuture.completedFuture(loaded);
        if (!this.autoChunkLoad) return CompletableFuture.completedFuture(null);
        return container.loadChunk(chunkX, chunkZ);
    }
}
