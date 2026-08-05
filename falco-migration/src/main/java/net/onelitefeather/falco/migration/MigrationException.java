package net.onelitefeather.falco.migration;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Thrown when {@link ChunkMigration} cannot convert a chunk.
 * <p>
 * Unchecked, and deliberately so: an earlier draft of this module made this type checked, following
 * {@code falco-anvil}'s {@code AnvilFormatException} hierarchy. That hierarchy is
 * {@code sealed … permits ChunkDataException, RegionFormatException}, declared in another package, so
 * it cannot be extended from here at all — and {@code falco-archunit}'s
 * {@code ErrorHandlingTest.checkedFaultsStayInsideTheHierarchy} runs over the whole
 * {@code net.onelitefeather.falco..} tree and requires every checked {@link Throwable} in it to be
 * assignable to that sealed root. A second checked hierarchy is therefore not an option, and this
 * module's own house style is unchecked anyway: {@code ErrorHandlingTest} asks every
 * {@link RuntimeException} under {@code net.onelitefeather.falco..} to carry a public
 * {@code (String, Throwable)} constructor, which this type has below.
 * </p>
 *
 * @since 2.1.0
 */
@ApiStatus.Experimental
public class MigrationException extends RuntimeException {

    /**
     * The serialisation id, fixed so a failure which crosses a version boundary still deserialises.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception with the given message and no cause.
     *
     * @param message the message which describes the failure
     */
    public MigrationException(String message) {
        this(message, null);
    }

    /**
     * Creates a new exception with the given message and cause.
     *
     * @param message the message which describes the failure
     * @param cause   the failure which caused this one, or null if there is none
     */
    public MigrationException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
