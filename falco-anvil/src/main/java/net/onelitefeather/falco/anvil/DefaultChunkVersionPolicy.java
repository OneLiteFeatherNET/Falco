package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.NumberBinaryTag;
import org.jetbrains.annotations.ApiStatus;

/**
 * The {@link ChunkVersionPolicy} a loader resolves when nothing else was configured: refuses the
 * pre-1.18 {@code Level} layout and a stored {@code DataVersion} below the configured floor, and
 * accepts a chunk that carries no {@code DataVersion} at all.
 * <p>
 * This is the guard against a specific piece of data loss: before snapshot {@code 21w43a}, a
 * chunk's block data lived under a {@code Level} compound instead of {@code sections} on the root.
 * Reading such a chunk with a loader that only looks for {@code sections} on the root does not
 * fail, it silently decodes to an empty section list — a chunk of air. This policy is what turns
 * that silent data loss into a thrown exception.
 * </p>
 * <p>
 * This type is experimental, like everything else in this package.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 1.2.0
 */
@ApiStatus.Experimental
public final class DefaultChunkVersionPolicy implements ChunkVersionPolicy {

    private static final String SECTIONS_KEY = "sections";
    private static final String LEGACY_LEVEL_KEY = "Level";
    private static final String DATA_VERSION_KEY = "DataVersion";

    /**
     * Creates the policy. It holds no state of its own, so every instance behaves the same way.
     */
    public DefaultChunkVersionPolicy() {
    }

    /**
     * Checks the given chunk data and throws if the loader cannot read it.
     * <p>
     * The layout is checked before the version, because a version number is a claim about the data
     * while the layout is the data: a chunk may carry no version at all, and one that carries a
     * version may not hold what that version promises. A root compound without {@code sections} but
     * with a {@code Level} compound is the pre-1.18 shape, which would otherwise decode to an empty
     * section list and reach the caller as a chunk of air.
     * </p>
     * <p>
     * A missing {@code DataVersion} is the one case that is not a rejection: a tool which writes
     * {@code sections} on the root but never learned to stamp a version has to keep loading, or a
     * whole category of externally-written world becomes unreadable. A key that is present but is not
     * the number it claims to be, and a key that holds a negative number, are both a different
     * situation from absent: something wrote a value there and it does not describe a version this
     * loader can trust, so both are refused rather than waved through the same path as "nothing was
     * ever written".
     * </p>
     *
     * @param data               the root compound of the chunk
     * @param minimumDataVersion the lowest data version the loader was configured to accept
     * @throws ChunkDataException if the chunk cannot be read
     */
    @Override
    public void check(CompoundBinaryTag data, int minimumDataVersion) throws ChunkDataException {
        boolean versionMissing = data.get(DATA_VERSION_KEY) == null;
        // A stored value that is not a number falls back to the same -1 as an absent key, but the
        // two are not the same failure: this flag is what lets the exception below say "not a
        // number" instead of misreporting a value ("-1") that was never actually stored.
        boolean versionMistyped = !versionMissing && !(data.get(DATA_VERSION_KEY) instanceof NumberBinaryTag);
        int version = NbtReads.optionalInteger(data, DATA_VERSION_KEY, -1);
        boolean legacyChunkLayout = !(data.get(SECTIONS_KEY) instanceof ListBinaryTag)
                && NbtReads.optionalCompound(data, LEGACY_LEVEL_KEY) != null;

        if (!legacyChunkLayout && (versionMissing || version >= minimumDataVersion)) {
            return;
        }

        throw new ChunkDataException(
                ChunkDataException.Reason.UNSUPPORTED_CHUNK_VERSION,
                legacyChunkLayout
                        ? "The chunk stores its data under Level, which means a version before 1.18"
                        : versionMistyped
                                ? "The chunk does not store its DataVersion as a number"
                                : "The chunk stores data version " + version
                                        + " but the loader accepts " + minimumDataVersion + " and above"
        );
    }
}
