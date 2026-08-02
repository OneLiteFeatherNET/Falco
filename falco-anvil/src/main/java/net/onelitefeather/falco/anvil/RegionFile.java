package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The {@link RegionFile} class represents a single Anvil region file which stores up to
 * {@code 32 x 32} chunks. The class is a pure byte container. It neither knows the NBT structure
 * of a chunk nor the Minestom chunk model which keeps the file format concern isolated.
 * <p>
 * Reading uses positional channel operations which do not touch the channel position and are
 * therefore safe to run from multiple threads at the same time. Only the sector allocation, the
 * header update and the switch between the two storage locations of a chunk need the internal lock,
 * so the expensive work of a caller stays outside of any critical section.
 * </p>
 * <p>
 * A reader still has to notice when the bytes below it changed while it read them. A chunk which is
 * rewritten moves to a different sector range and releases the one it occupied, and the allocator
 * may hand that range to the very next write of any chunk. A reader which took no lock can therefore
 * be somewhere inside a range which now belongs to a different chunk. Every chunk entry carries a
 * version counter for that reason. The counter is raised once when a writer enters its critical
 * section and once when it leaves it, so an odd value marks an entry which is currently being
 * changed. A reader takes the counter, rejects an odd one, reads the bytes and takes the counter
 * again: an unchanged even counter proves that no writer touched this chunk in between, and a range
 * can only be recycled after the chunk which owned it was rewritten. Everything else makes the
 * reader start over. Readers therefore never block each other and never block a writer, which is the
 * property the whole design rests on.
 * </p>
 * <p>
 * A chunk which does not fit into the {@link RegionConstants#MAX_SECTORS_PER_CHUNK} sectors a
 * location entry can address is moved into a separate file next to the region file. The header entry
 * decides which of the two locations a reader has to follow, so the file and the entry are switched
 * inside the same critical section. Only the payload bytes of such a chunk are written outside of
 * it, into a staging file which is moved into place while the lock is held.
 * </p>
 * <p>
 * The external file is the one place where the lock free reads meet a name in the file system
 * instead of a range inside the region file, and a name is not a POSIX concept. A reader opens the
 * external file while a writer may replace or remove it, which POSIX allows without any further
 * thought but Windows does not. Every operation on such a file therefore goes through
 * {@link #placeExternal(Path, Path)} and {@link #removeExternal(Path)} which keep the name usable
 * for a writer while a reader still holds a handle on the file behind it.
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
public final class RegionFile implements AutoCloseable {

    /**
     * The amount of times a read is repeated before it falls back to the lock.
     * <p>
     * A repetition only happens when a writer touched the very chunk which is being read, which is
     * rare enough that a handful of attempts practically always succeed. The fallback exists so a
     * chunk which is rewritten in a tight loop cannot starve a reader forever. It is the only case
     * in which a reader waits for a writer at all.
     * </p>
     */
    private static final int OPTIMISTIC_ATTEMPTS = 4;

    /**
     * The suffix of the file which holds the payload of an oversized chunk until it is moved into
     * place. The suffix differs from the one of a finished external file so a reader can never pick
     * up a staging file by name.
     */
    private static final String STAGING_SUFFIX = ".mcc.tmp";

    /**
     * The amount of times an operation on an external chunk file is repeated before it gives up.
     * <p>
     * A repetition only happens on a file system which refuses to touch a name while another thread
     * has the file behind it open. The refusal lasts exactly as long as that handle, and a handle on
     * an external file only exists for the duration of a single {@link Files#readAllBytes(Path)} in
     * {@link #readEntry(int, int, int)}. No reader can open a new one while the writer holds the
     * lock, because the version counter of the entry is odd for that whole time and makes every
     * reader either spin or wait for the lock. The outstanding handles therefore drain within one
     * read, and the limit only exists so a file which is held open by something outside of this
     * process reports a failure instead of blocking a writer forever.
     * </p>
     */
    private static final int EXTERNAL_ATTEMPTS = 100;

    /**
     * The time in milliseconds a thread waits before it repeats an operation on an external chunk
     * file.
     */
    private static final long EXTERNAL_RETRY_DELAY = 1L;

    private final Path path;
    private final Path directory;
    private final FileChannel channel;
    private final ReentrantLock lock;
    private final AtomicIntegerArray locations;
    private final AtomicIntegerArray timestamps;
    private final AtomicIntegerArray versions;
    private final SectorAllocator allocator;

    private volatile boolean closed;

    /**
     * Creates a new region file around the given channel and header state.
     *
     * @param path       the path of the region file
     * @param channel    the channel which is used for all read and write operations
     * @param locations  the location table of the region file
     * @param timestamps the timestamp table of the region file
     * @param allocator  the allocator which tracks the sector usage
     */
    private RegionFile(Path path, FileChannel channel, int[] locations, int[] timestamps, SectorAllocator allocator) {
        this.path = path;
        this.directory = path.getParent() == null ? Path.of(".") : path.getParent();
        this.channel = channel;
        this.lock = new ReentrantLock();
        this.locations = new AtomicIntegerArray(locations);
        this.timestamps = new AtomicIntegerArray(timestamps);
        this.versions = new AtomicIntegerArray(RegionConstants.ENTRY_COUNT);
        this.allocator = allocator;
    }

    /**
     * Opens the region file under the given path and reads its header.
     * A file which does not exist yet is created with an empty header.
     *
     * @param path the path of the region file
     * @return the opened region file
     * @throws IOException           if the file cannot be opened
     * @throws RegionFormatException if the header does not describe a usable region file
     */
    public static RegionFile open(Path path) throws IOException, RegionFormatException {
        Path parent = path.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        FileChannel channel = FileChannel.open(
                path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE
        );

        try {
            return readHeader(path, channel);
        } catch (IOException | RuntimeException | RegionFormatException exception) {
            // RegionFormatException belongs in this list for the same reason the other two do, and
            // it is easy to miss: it is not an IOException, so leaving it out would return from a
            // broken header with the channel still open.
            channel.close();
            throw exception;
        }
    }

    /**
     * Reads the header of an already opened region file and rebuilds the sector usage from it.
     *
     * @param path    the path of the region file
     * @param channel the channel of the region file
     * @return the region file which is described by the header
     * @throws IOException if the header is incomplete or describes an invalid layout
     */
    private static RegionFile readHeader(Path path, FileChannel channel) throws IOException, RegionFormatException {
        long size = channel.size();
        int[] locations = new int[RegionConstants.ENTRY_COUNT];
        int[] timestamps = new int[RegionConstants.ENTRY_COUNT];

        if (size == 0) {
            channel.write(ByteBuffer.allocate(RegionConstants.HEADER_SIZE), 0);
            return new RegionFile(path, channel, locations, timestamps, new SectorAllocator(RegionConstants.HEADER_SECTORS));
        }

        if (size < RegionConstants.HEADER_SIZE) {
            throw new RegionFormatException(
                    RegionFormatException.Reason.HEADER_TOO_SHORT,
                    "The region file " + path + " holds " + size + " bytes which is less than the header size of "
                            + RegionConstants.HEADER_SIZE + " bytes"
            );
        }

        ByteBuffer header = readFully(channel, 0, RegionConstants.HEADER_SIZE, path);

        for (int index = 0; index < RegionConstants.ENTRY_COUNT; index++) {
            locations[index] = header.getInt(RegionConstants.locationOffset(index));
            timestamps[index] = header.getInt(RegionConstants.timestampOffset(index));
        }

        int totalSectors = (int) Math.max(size / RegionConstants.SECTOR_SIZE, RegionConstants.HEADER_SECTORS);
        SectorAllocator allocator = new SectorAllocator(totalSectors);

        for (int index = 0; index < RegionConstants.ENTRY_COUNT; index++) {
            int location = locations[index];

            if (location == 0) {
                continue;
            }

            int offset = location >>> 8;
            int count = location & 0xFF;

            if (offset < RegionConstants.HEADER_SECTORS || count <= 0) {
                locations[index] = 0;
                continue;
            }
            allocator.reserve(offset, count);
        }
        return new RegionFile(path, channel, locations, timestamps, allocator);
    }

    /**
     * Reads the raw payload of the given chunk without decompressing it.
     * The caller is expected to decompress the payload outside of any lock this class holds.
     * <p>
     * The read takes no lock and is validated against the version counter of the chunk afterwards.
     * A read which raced a writer is repeated, and a read which keeps racing falls back to the lock
     * which the writers use, so it cannot be starved. A failure of the read itself is only reported
     * when the version counter proves that no writer was involved, because the bytes of a recycled
     * sector range can describe any length and any compression scheme.
     * </p>
     *
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     * @return the raw chunk or null if the region file does not hold the chunk
     * @throws IOException           if the chunk cannot be read
     * @throws RegionFormatException if the stored bytes do not describe a usable chunk entry
     */
    public @Nullable RawChunk readRaw(int chunkX, int chunkZ) throws IOException, RegionFormatException {
        ensureOpen();
        int index = RegionConstants.index(chunkX, chunkZ);

        for (int attempt = 0; attempt < OPTIMISTIC_ATTEMPTS; attempt++) {
            int version = this.versions.get(index);

            // An odd counter marks an entry which is being changed right now. The change is not
            // limited to the tables: the external file of the chunk may already be gone while the
            // entry still points at it, so such a read cannot be trusted at all.
            if ((version & 1) != 0) {
                continue;
            }

            try {
                RawChunk chunk = readEntry(index, chunkX, chunkZ);

                if (this.versions.get(index) == version) {
                    return chunk;
                }
            } catch (IOException | RegionFormatException exception) {
                // A format fault is caught here as well, and that is load bearing rather than
                // tidy. A reader whose entry changed under it can read a length or a compression
                // id that never existed as a whole, which now surfaces as a RegionFormatException
                // instead of an IOException. Both mean the same thing at this point: if the version
                // moved, the bytes were torn and the attempt is retried; only an unchanged version
                // makes it a real failure of the stored data.
                if (this.versions.get(index) == version) {
                    throw exception;
                }
            }
        }

        this.lock.lock();
        try {
            ensureOpen();
            return readEntry(index, chunkX, chunkZ);
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Reads the payload the location entry of the given index currently points at.
     * <p>
     * The method performs no validation of its own result. A caller which did not take the lock has
     * to confirm through the version counter of the chunk that the entry did not change while the
     * bytes were read.
     * </p>
     *
     * @param index  the index of the chunk inside the region tables
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     * @return the raw chunk or null if the region file does not hold the chunk
     * @throws IOException           if the chunk cannot be read
     * @throws RegionFormatException if the stored bytes do not describe a usable chunk entry
     */
    private @Nullable RawChunk readEntry(int index, int chunkX, int chunkZ) throws IOException, RegionFormatException {
        int location = this.locations.get(index);

        if (location == 0) {
            return null;
        }

        int sectorOffset = location >>> 8;
        int sectorCount = location & 0xFF;
        long position = (long) sectorOffset * RegionConstants.SECTOR_SIZE;
        int available = sectorCount * RegionConstants.SECTOR_SIZE;

        ByteBuffer head = readFully(this.channel, position, RegionConstants.LENGTH_FIELD_SIZE + RegionConstants.COMPRESSION_FIELD_SIZE, this.path);
        int length = head.getInt();
        int scheme = head.get() & 0xFF;

        if (length <= 0 || length > available) {
            throw new RegionFormatException(
                    RegionFormatException.Reason.CHUNK_LENGTH_OUT_OF_RANGE,
                    "The chunk " + chunkX + "/" + chunkZ + " in " + this.path + " declares a length of " + length
                            + " bytes which does not fit into its " + sectorCount + " sectors"
            );
        }

        ChunkCompression compression = ChunkCompression.fromId(scheme);

        if (ChunkCompression.isExternal(scheme)) {
            return new RawChunk(compression, Files.readAllBytes(externalPath(chunkX, chunkZ)));
        }

        int payloadLength = length - RegionConstants.COMPRESSION_FIELD_SIZE;
        ByteBuffer payload = readFully(
                this.channel, position + RegionConstants.LENGTH_FIELD_SIZE + RegionConstants.COMPRESSION_FIELD_SIZE,
                payloadLength, this.path
        );
        byte[] bytes = new byte[payloadLength];
        payload.get(bytes);
        return new RawChunk(compression, bytes);
    }

    /**
     * Writes the raw payload of the given chunk into the region file.
     * The payload is expected to be compressed already so the compression can happen outside of
     * the lock this method acquires.
     * <p>
     * An oversized payload is written into a staging file before the lock is taken and only moved
     * into its final place while the lock is held. The header entry and the external file therefore
     * always describe the same storage location, no matter how two writers of the same chunk
     * interleave, while the bytes still leave the process outside of the critical section.
     * </p>
     *
     * @param chunkX      the absolute chunk x coordinate
     * @param chunkZ      the absolute chunk z coordinate
     * @param compression the compression scheme of the payload
     * @param payload     the compressed payload of the chunk
     * @throws IOException if the chunk cannot be written
     */
    public void writeRaw(int chunkX, int chunkZ, ChunkCompression compression, byte[] payload) throws IOException {
        ensureOpen();
        int index = RegionConstants.index(chunkX, chunkZ);
        int totalLength = RegionConstants.LENGTH_FIELD_SIZE + RegionConstants.COMPRESSION_FIELD_SIZE + payload.length;
        boolean external = RegionConstants.sectorsFor(totalLength) > RegionConstants.MAX_SECTORS_PER_CHUNK;
        Path staged = external ? Files.createTempFile(this.directory, "c.", STAGING_SUFFIX) : null;

        try {
            if (staged != null) {
                Files.write(staged, payload);
            }

            byte[] stored = external ? new byte[0] : payload;
            int scheme = external ? compression.id() | ChunkCompression.EXTERNAL_FLAG : compression.id();
            // The specification defines the length field as the compression byte plus the payload.
            int length = RegionConstants.COMPRESSION_FIELD_SIZE + stored.length;
            int sectorCount = RegionConstants.sectorsFor(RegionConstants.LENGTH_FIELD_SIZE + length);

            ByteBuffer buffer = ByteBuffer.allocate(sectorCount * RegionConstants.SECTOR_SIZE);
            buffer.putInt(length).put((byte) scheme).put(stored);
            buffer.rewind();

            Path externalPath = externalPath(chunkX, chunkZ);

            this.lock.lock();
            try {
                // The counter turns odd before the first change becomes visible and even again once
                // every change is done, so a reader can tell a finished state from a state which is
                // still being assembled.
                this.versions.incrementAndGet(index);

                int previous = this.locations.get(index);
                int sectorOffset = this.allocator.allocate(sectorCount);

                writeFully(this.channel, buffer, (long) sectorOffset * RegionConstants.SECTOR_SIZE);

                // The external file has to exist before the entry points at it and may only be
                // removed after the entry stopped pointing at it, so a crash between the two steps
                // can leave an unused file but never a missing one.
                if (staged != null) {
                    placeExternal(staged, externalPath);
                }

                this.locations.set(index, (sectorOffset << 8) | sectorCount);
                this.timestamps.set(index, (int) (System.currentTimeMillis() / 1000L));
                writeEntry(index);

                if (staged == null) {
                    removeExternal(externalPath);
                }
                if (previous != 0) {
                    this.allocator.free(previous >>> 8, previous & 0xFF);
                }
            } finally {
                // The counter is raised while the lock is still held, so a range which was freed
                // above cannot be handed to another writer before every reader can see the change.
                this.versions.incrementAndGet(index);
                this.lock.unlock();
            }
        } finally {
            if (staged != null) {
                retryWhileDenied(() -> Files.deleteIfExists(staged));
            }
        }
    }

    /**
     * Removes the given chunk from the region file.
     * The sectors the chunk occupied become available for a later write.
     *
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     * @throws IOException if the header cannot be updated
     */
    public void delete(int chunkX, int chunkZ) throws IOException {
        ensureOpen();
        int index = RegionConstants.index(chunkX, chunkZ);

        this.lock.lock();
        try {
            this.versions.incrementAndGet(index);
            int previous = this.locations.get(index);

            if (previous == 0) {
                return;
            }

            this.locations.set(index, 0);
            this.timestamps.set(index, 0);
            writeEntry(index);
            removeExternal(externalPath(chunkX, chunkZ));
            this.allocator.free(previous >>> 8, previous & 0xFF);
        } finally {
            this.versions.incrementAndGet(index);
            this.lock.unlock();
        }
    }

    /**
     * Checks whether the region file holds the given chunk.
     *
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     * @return true if the chunk is present, otherwise false
     */
    @Contract(pure = true)
    public boolean hasChunk(int chunkX, int chunkZ) {
        return this.locations.get(RegionConstants.index(chunkX, chunkZ)) != 0;
    }

    /**
     * Returns the path of the region file.
     *
     * @return the path of the region file
     */
    @Contract(pure = true)
    public Path path() {
        return this.path;
    }

    /**
     * Forces all pending changes of the region file to the underlying storage.
     *
     * @throws IOException if the changes cannot be written
     */
    public void flush() throws IOException {
        ensureOpen();
        this.channel.force(false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        this.lock.lock();
        try {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.channel.close();
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Writes the location and the timestamp entry of the given index into the header.
     * Only the eight affected bytes are touched so a crash cannot destroy the whole header.
     *
     * @param index the index of the chunk inside the region tables
     * @throws IOException if the entry cannot be written
     */
    private void writeEntry(int index) throws IOException {
        ByteBuffer location = ByteBuffer.allocate(Integer.BYTES).putInt(this.locations.get(index)).rewind();
        writeFully(this.channel, location, RegionConstants.locationOffset(index));

        ByteBuffer timestamp = ByteBuffer.allocate(Integer.BYTES).putInt(this.timestamps.get(index)).rewind();
        writeFully(this.channel, timestamp, RegionConstants.timestampOffset(index));
    }

    /**
     * Builds the path of the file which holds an oversized chunk.
     *
     * @param chunkX the absolute chunk x coordinate
     * @param chunkZ the absolute chunk z coordinate
     * @return the path of the external chunk file
     */
    @Contract(pure = true)
    private Path externalPath(int chunkX, int chunkZ) {
        return this.directory.resolve("c." + chunkX + "." + chunkZ + ".mcc");
    }

    /**
     * Moves the staging file of an oversized chunk onto the external file of that chunk.
     * <p>
     * The move replaces a file which a reader may have open at this very moment. POSIX lets a name
     * be re-pointed at any time and keeps every open handle valid, so the move always succeeds
     * there. Windows only agrees as long as the readers opened the file in a way which shares the
     * deletion right, which the NIO file system provider does, and as long as the name itself is not
     * poisoned. That is why {@link #removeExternal(Path)} exists, and the repetition here covers
     * what is left: a handle which is still being torn down or a virus scanner which opened the file
     * behind the back of this process both deny the move for a moment and let it through afterwards.
     * </p>
     *
     * @param staged the staging file which holds the payload of the chunk
     * @param target the external file of the chunk
     * @throws IOException if the staging file cannot be moved into place
     */
    private void placeExternal(Path staged, Path target) throws IOException {
        retryWhileDenied(() -> Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE));
    }

    /**
     * Removes the external file of a chunk which no longer needs one.
     * <p>
     * The file is renamed onto a private name before it is deleted instead of being deleted where it
     * lies. A plain deletion looks equivalent and is equivalent under POSIX, where the name is
     * detached immediately and only the unnamed file lives on until the last reader closed it.
     * Windows instead keeps the name in the directory and marks the file for deletion, and for as
     * long as a reader holds it open every attempt to open that name or to move another file onto it
     * fails with an {@link AccessDeniedException}. A writer which switches the same chunk back to an
     * external payload right after would therefore be denied its move for as long as any reader is
     * still busy with the old file, which is precisely the window this class is built to keep open.
     * Renaming the file away detaches the name at once on both systems, so only the private name is
     * left in that state and nobody ever asks for it again.
     * </p>
     *
     * @param target the external file of the chunk
     * @throws IOException if the external file cannot be removed
     */
    private void removeExternal(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        Path discarded = Files.createTempFile(this.directory, "c.", STAGING_SUFFIX);

        try {
            retryWhileDenied(() -> Files.move(target, discarded, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE));
        } catch (NoSuchFileException _) {
            // Another writer removed the file between the check above and the move, which is the
            // outcome this method wants anyway.
        }
        retryWhileDenied(() -> Files.deleteIfExists(discarded));
    }

    /**
     * Runs the given action and repeats it while the file system denies the access to a name.
     * <p>
     * The action is repeated at most {@link #EXTERNAL_ATTEMPTS} times with a pause of
     * {@link #EXTERNAL_RETRY_DELAY} milliseconds in between. A denial which outlives every attempt
     * is reported to the caller, and a thread which is interrupted while it waits stops immediately
     * and reports the denial which made it wait.
     * </p>
     *
     * @param action the action to run
     * @throws IOException if the action keeps failing or fails for another reason
     */
    private static void retryWhileDenied(FileAction action) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                action.run();
                return;
            } catch (AccessDeniedException exception) {
                if (attempt >= EXTERNAL_ATTEMPTS) {
                    throw exception;
                }

                try {
                    Thread.sleep(EXTERNAL_RETRY_DELAY);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
    }

    /**
     * An operation on a file which may fail with an {@link IOException}.
     */
    @FunctionalInterface
    private interface FileAction {

        /**
         * Runs the operation.
         *
         * @throws IOException if the operation fails
         */
        void run() throws IOException;
    }

    /**
     * Verifies that the region file is still usable.
     *
     * @throws IOException if the region file is already closed
     */
    private void ensureOpen() throws IOException {
        if (this.closed) {
            throw new IOException("The region file " + this.path + " is already closed");
        }
    }

    /**
     * Reads the requested amount of bytes from the given position.
     * A channel is allowed to return fewer bytes than requested, so the read is repeated until
     * the buffer is filled or the file ends.
     *
     * @param channel the channel to read from
     * @param position the position to start reading at
     * @param length   the amount of bytes to read
     * @param path     the path which is used for the error message
     * @return a buffer which holds the requested bytes and is ready to be read
     * @throws IOException if the file ends before the requested amount of bytes was read
     */
    private static ByteBuffer readFully(FileChannel channel, long position, int length, Path path) throws IOException, RegionFormatException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long offset = position;

        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset);

            if (read < 0) {
                throw new RegionFormatException(
                        RegionFormatException.Reason.TRUNCATED_FILE,
                        "The file " + path + " ended after " + buffer.position() + " of " + length + " expected bytes"
                );
            }
            offset += read;
        }
        return buffer.rewind();
    }

    /**
     * Writes the complete buffer to the given position.
     *
     * @param channel  the channel to write to
     * @param buffer   the buffer which holds the bytes to write
     * @param position the position to start writing at
     * @throws IOException if the bytes cannot be written
     */
    private static void writeFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long offset = position;

        while (buffer.hasRemaining()) {
            offset += channel.write(buffer, offset);
        }
    }

    /**
     * The {@link RawChunk} record holds the untouched payload of a chunk together with the
     * compression scheme which is required to decode it.
     * <p>
     * The payload array is not copied, neither on the way in nor on the way out. A record which
     * copied it would double the cost of every chunk read for a guarantee the load path does not
     * need, because the array is handed straight from the read to the decompressor and no caller
     * keeps it. Whoever holds a raw chunk owns the array and must not hand it to a second reader
     * that writes into it.
     * </p>
     * <p>
     * This type is experimental. The Anvil loader is new and its API may still change while it is
     * being validated against real worlds.
     * </p>
     *
     * @param compression the compression scheme of the payload
     * @param payload     the payload as it is stored on disk, not copied
     * @author TheMeinerLP
     * @version 1.0.0
     * @since 0.1.0
     */
    @ApiStatus.Experimental
    public record RawChunk(ChunkCompression compression, byte[] payload) {

        /**
         * Decompresses the payload of the chunk.
         *
         * @return the decompressed payload
         * @throws IOException if the payload cannot be decompressed
         */
        public byte[] decompress() throws IOException {
            return this.compression.decompress(this.payload);
        }
    }
}
