package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * The {@link ChunkCompression} enum describes the compression schemes which a chunk payload
 * inside a region file can use. The scheme is stored as a single byte in front of the payload.
 * <p>
 * A scheme with the {@link #EXTERNAL_FLAG} set marks a chunk which does not live inside the
 * region file itself but in a separate file next to it. The flag only describes the storage
 * location, the remaining bits still name the compression of the payload.
 * </p>
 *
 * <p>
 * This type is experimental. The Anvil loader is new and its API may still change while it is
 * being validated against real worlds.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public enum ChunkCompression {

    /**
     * The payload is compressed with gzip.
     */
    GZIP(1),

    /**
     * The payload is compressed with zlib. This is the scheme which vanilla writes by default.
     */
    ZLIB(2),

    /**
     * The payload is stored without any compression.
     */
    NONE(3);

    /**
     * The bit which marks a chunk that is stored in a separate file next to the region file.
     */
    public static final int EXTERNAL_FLAG = 0x80;

    /**
     * The fastest compression level. It produces roughly a tenth more bytes than the default one.
     */
    public static final int FASTEST_LEVEL = 1;

    /**
     * The level this loader uses unless another one is chosen.
     * <p>
     * It sits below the default of the platform on purpose. Compression is the largest single cost
     * of saving a chunk, and on a serialised 24 section chunk the platform default spends about
     * eighty percent longer to produce a result about three percent smaller. Levels between this
     * one and the platform default are not worth choosing: they cost nearly as much as the default
     * while saving almost nothing over it.
     * </p>
     * <p>
     * A caller that stores a world once and reads it many times should pass a higher level
     * explicitly. This default is chosen for the opposite case, where chunks are written repeatedly
     * while a server runs.
     * </p>
     */
    public static final int DEFAULT_LEVEL = 2;

    /**
     * The level which produces the smallest result. It is far slower than every other level.
     */
    public static final int SMALLEST_LEVEL = 9;

    private static final int BUFFER_SIZE = 8192;

    private final int id;

    /**
     * Creates a new compression scheme with the identifier the format defines for it.
     *
     * @param id the identifier of the scheme inside a region file
     */
    ChunkCompression(int id) {
        this.id = id;
    }

    /**
     * Resolves the compression scheme which belongs to the given identifier.
     * A set {@link #EXTERNAL_FLAG} is stripped before the lookup happens.
     *
     * @param id the identifier to resolve
     * @return the matching compression scheme
     * @throws IOException if no supported scheme uses the given identifier
     */
    public static ChunkCompression fromId(int id) throws IOException {
        return switch (id & ~EXTERNAL_FLAG) {
            case 1 -> GZIP;
            case 2 -> ZLIB;
            case 3 -> NONE;
            default -> throw new IOException(
                    "The compression scheme " + id + " is not supported. Only gzip (1), zlib (2) and none (3) can be read"
            );
        };
    }

    /**
     * Checks whether the given identifier marks a chunk which is stored outside of the region file.
     *
     * @param id the identifier to check
     * @return true if the chunk is stored externally, otherwise false
     */
    @Contract(pure = true)
    public static boolean isExternal(int id) {
        return (id & EXTERNAL_FLAG) != 0;
    }

    /**
     * Returns the identifier which the format uses for this scheme.
     *
     * @return the identifier of the scheme
     */
    @Contract(pure = true)
    public int id() {
        return this.id;
    }

    /**
     * Compresses the given payload with this scheme.
     *
     * @param payload the uncompressed payload
     * @return the compressed payload
     * @throws IOException if the payload cannot be compressed
     */
    public byte[] compress(byte[] payload) throws IOException {
        return compress(payload, DEFAULT_LEVEL);
    }

    /**
     * Compresses the given payload with this scheme at the given level.
     * <p>
     * A higher level spends more time to produce fewer bytes. The relation is far from linear: past
     * the middle of the range the extra time grows steeply while the saved bytes do not, so the
     * highest levels are rarely worth their cost for chunk data.
     * </p>
     *
     * @param payload the uncompressed payload
     * @param level   the compression level between {@link #FASTEST_LEVEL} and {@link #SMALLEST_LEVEL}
     * @return the compressed payload
     * @throws IOException              if the payload cannot be compressed
     * @throws IllegalArgumentException if the level is outside of the allowed range
     */
    public byte[] compress(byte[] payload, int level) throws IOException {
        if (level < FASTEST_LEVEL || level > SMALLEST_LEVEL) {
            throw new IllegalArgumentException(
                    "The compression level must be within [" + FASTEST_LEVEL + ", " + SMALLEST_LEVEL + "] but was " + level
            );
        }
        if (this == NONE) {
            return payload.clone();
        }

        ByteArrayOutputStream target = new ByteArrayOutputStream(Math.max(payload.length / 4, BUFFER_SIZE));

        if (this == GZIP) {
            // The gzip stream owns its deflater and ends it on close, so the level is set on that
            // one rather than on a deflater of our own.
            try (GZIPOutputStream stream = new GZIPOutputStream(target, BUFFER_SIZE) {
                {
                    this.def.setLevel(level);
                }
            }) {
                stream.write(payload);
            }
            return target.toByteArray();
        }

        // A deflater holds native memory which the stream does not release on its own. Closing it
        // ends it, and the stream is closed before it because it still needs a live deflater to
        // write out the last block.
        try (Deflater deflater = new Deflater(level);
             OutputStream stream = new DeflaterOutputStream(target, deflater)) {
            stream.write(payload);
        }
        return target.toByteArray();
    }

    /**
     * Decompresses the given payload with this scheme.
     *
     * @param payload the compressed payload
     * @return the uncompressed payload
     * @throws IOException if the payload cannot be decompressed
     */
    public byte[] decompress(byte[] payload) throws IOException {
        if (this == NONE) {
            return payload.clone();
        }

        try (InputStream stream = wrapForDecompression(new ByteArrayInputStream(payload))) {
            return stream.readAllBytes();
        }
    }

    /**
     * Wraps the given source stream into the decompressing stream of this scheme.
     *
     * @param source the stream which holds the compressed bytes
     * @return the wrapped stream
     * @throws IOException if the wrapping stream cannot be created
     */
    private InputStream wrapForDecompression(InputStream source) throws IOException {
        return switch (this) {
            case GZIP -> new GZIPInputStream(source, BUFFER_SIZE);
            case ZLIB -> new InflaterInputStream(source);
            case NONE -> source;
        };
    }
}
