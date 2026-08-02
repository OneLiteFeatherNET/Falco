package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.ApiStatus;

/**
 * The {@link ChunkLocation} record says which chunk of which file of which dimension a failure came
 * from.
 * <p>
 * It exists so the context is formatted in exactly one place. Before it, every message built its own
 * variant of "chunk 3/-7 in r.0.-1.mca", which meant the same failure read differently depending on
 * which class noticed it, and a log line that already carried the coordinate got it a second time
 * from the wrapper.
 * </p>
 * <p>
 * The format classes of this package never receive one. {@code NbtReads}, {@code PaletteData} and
 * {@code SectionCodec} are deliberately about bytes rather than about worlds, which is what keeps
 * them testable without a server; the location is attached at the loader boundary through
 * {@link AnvilFormatException#at(ChunkLocation)}.
 * </p>
 * <p>
 * This type is experimental, like everything else in this package.
 * </p>
 *
 * <p>
 * The file and the dimension are held as text rather than as a {@code Path} and a {@code Key}. This
 * is a description of where something went wrong, not a handle to work with — and it keeps the type
 * clear of the two rules that hold this package's byte layer free of the file system and of the
 * registry, which a value object travelling inside an exception has no business dragging in.
 * </p>
 *
 * @param chunkX    the chunk x coordinate
 * @param chunkZ    the chunk z coordinate
 * @param region    the region file the chunk was read from or written to
 * @param dimension the dimension the loader serves
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 1.0.0
 */
@ApiStatus.Experimental
public record ChunkLocation(int chunkX, int chunkZ, String region, String dimension) {

    /**
     * Describes this location for a log line, in the one form the whole package uses.
     *
     * @return the chunk coordinate, the region file and the dimension
     */
    @Override
    public String toString() {
        return "chunk " + this.chunkX + "/" + this.chunkZ + " in " + this.region + " of " + this.dimension;
    }
}
