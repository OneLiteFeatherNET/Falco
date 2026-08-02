package net.onelitefeather.falco.instance;

import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.SharedInstance;
import net.minestom.server.instance.generator.Generator;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

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
 * @version 1.1.0
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
}
