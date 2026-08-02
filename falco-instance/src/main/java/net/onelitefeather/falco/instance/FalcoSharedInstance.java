package net.onelitefeather.falco.instance;

import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.SharedInstance;
import org.jetbrains.annotations.ApiStatus;

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
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public class FalcoSharedInstance extends SharedInstance {

    /**
     * Creates a view over the chunks of a container.
     *
     * @param uuid              the identity of this instance
     * @param instanceContainer the container which owns the chunks this instance shows
     */
    public FalcoSharedInstance(UUID uuid, InstanceContainer instanceContainer) {
        super(uuid, Objects.requireNonNull(instanceContainer, "a shared instance needs a container to share"));
    }
}
