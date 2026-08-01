package net.onelitefeather.falco.instance;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Entity;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.EntityTracker;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.heightmap.Heightmap;
import net.minestom.server.instance.heightmap.MotionBlockingHeightmap;
import net.minestom.server.instance.heightmap.WorldSurfaceHeightmap;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.server.CachedPacket;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.ChunkDataPacket;
import net.minestom.server.network.packet.server.play.data.ChunkData;
import net.minestom.server.network.packet.server.play.data.LightData;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.snapshot.ChunkSnapshot;
import net.minestom.server.snapshot.SnapshotImpl;
import net.minestom.server.snapshot.SnapshotUpdater;
import net.minestom.server.utils.ArrayUtils;
import net.minestom.server.world.DimensionType;
import net.minestom.server.world.biome.Biome;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static net.minestom.server.coordinate.CoordConversion.globalToSectionRelative;

/**
 * The {@link FalcoChunk} class is the chunk of {@link FalcoInstance}.
 * <p>
 * It exists for a single reason: {@code Chunk#onLoad()} and {@code Chunk#unload()} are
 * {@code protected}, so no code outside {@code net.minestom.server.instance} can call them. An
 * instance living in another package therefore cannot tell a chunk that it has been loaded or
 * unloaded, and a chunk which is never told keeps reporting {@code isLoaded() == true} forever —
 * which is exactly the state every {@code ChunkUtils#isLoaded} check in Minestom trusts.
 * </p>
 * <p>
 * A subclass is the honest way out. It compiles, it needs no reflection and no open module, and the
 * only price is that this module drags a chunk implementation along. The alternative, reaching into
 * the hooks with {@code setAccessible}, would break on the next JDK that closes the door and would
 * hide the coupling instead of naming it.
 * </p>
 * <p>
 * A third member has the same problem and is settled here as well: the block setter which carries a
 * placement and a destruction is {@code protected} on {@code Chunk}. This class widens it to public,
 * which is what lets {@link FalcoInstance} drive a placement from its own package.
 * </p>
 *
 * <h2>Why the storage is a field and not a superclass</h2>
 * <p>
 * This chunk used to extend {@code DynamicChunk} and inherit its sections. That inheritance is what
 * this class gave up, because it cost more than it saved: {@code FalcoChunk} and
 * {@code FalcoLightingChunk} both extended {@code DynamicChunk}, and a class has one superclass, so
 * the two could never be combined. A server that wanted the instance of Falco and the light engine
 * of Falco at the same time had to pick one. The blocks now sit behind {@link BlockStorage}, which
 * is a field, and a field can be combined with anything.
 * </p>
 * <p>
 * The second reason is that a memory layout is exactly the kind of decision that should be
 * replaceable. As long as the sections were a {@code protected final} field of a Minestom class, a
 * different layout meant a different chunk class and therefore a different everything — viewers,
 * heightmaps, packet cache and lifecycle included. Behind the interface, a layout is a constructor
 * argument.
 * </p>
 * <p>
 * Everything that is not about where a block physically sits was carried over from
 * {@code DynamicChunk} as it stands: the entries map, the tickable map, the two heightmaps, the
 * cached chunk packet and the viewer and tag plumbing inherited from {@code Chunk}. Copying it is
 * deliberate. This class is measured against {@code DynamicChunk}, and a difference that came from
 * rewriting the bookkeeping would be indistinguishable from a difference that came from the
 * storage.
 * </p>
 * <p>
 * Like {@code DynamicChunk}, this chunk is not thread-safe on its own. Callers hold the chunk lock,
 * which {@link Chunk#lockWriteLock()} and {@link Chunk#lockReadLock()} provide.
 * </p>
 * <p>
 * This type is experimental. The instance module is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 2.0.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public class FalcoChunk extends Chunk {

    private static final Logger LOGGER = LoggerFactory.getLogger(FalcoChunk.class);

    private final BlockStorage storage;

    /**
     * The blocks which are worth keeping as objects, keyed by {@code CoordConversion#chunkBlockIndex}.
     * <p>
     * A palette holds a state id and nothing else, so a block with a handler, with NBT or with a
     * block entity would lose that part of itself on the way in. These are the ones that are kept
     * whole, and {@link #getBlock(int, int, int, Condition)} looks here before it asks the storage.
     * </p>
     */
    protected final Int2ObjectOpenHashMap<Block> entries = new Int2ObjectOpenHashMap<>(0);

    /**
     * The subset of {@link #entries} whose handler wants a tick, keyed the same way.
     * <p>
     * It is a separate map rather than a filter over {@link #entries} because {@link #tick(long)}
     * runs every tick for every loaded chunk, and walking the entries to find the few tickable ones
     * would make the cost of ticking depend on how many block entities a chunk happens to hold.
     * </p>
     */
    protected final Int2ObjectOpenHashMap<Block> tickableMap = new Int2ObjectOpenHashMap<>(0);

    private volatile boolean needsCompleteHeightmapRefresh = true;

    /**
     * The highest block per column which stops movement.
     */
    protected Heightmap motionBlocking = new MotionBlockingHeightmap(this);

    /**
     * The highest block per column which is not air.
     */
    protected Heightmap worldSurface = new WorldSurfaceHeightmap(this);

    /**
     * The serialised chunk, kept until something invalidates it.
     * <p>
     * A chunk is sent to every player who walks into view of it, and serialising a full chunk is far
     * more expensive than the write which changed it. The cache turns that into one serialisation per
     * change instead of one per viewer.
     * </p>
     */
    private final CachedPacket chunkCache = new CachedPacket(this::createChunkPacket);

    /**
     * Creates an empty chunk at the given position, storing its blocks in sections.
     *
     * @param instance the instance which owns the chunk
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     */
    public FalcoChunk(Instance instance, int chunkX, int chunkZ) {
        super(instance, chunkX, chunkZ, true);
        // Must be built here and not in a field initialiser: the super constructor is what computes
        // minSection and maxSection, and the storage is sized from them.
        this.storage = new SectionBlockStorage(minSection, maxSection - minSection);
    }

    /**
     * Creates a chunk which takes over the given storage.
     * <p>
     * This is the constructor that makes the layout a choice of the caller. It is also what
     * {@link #copy(Instance, int, int)} uses, with a storage that copied itself; the storage is not
     * copied here, so a caller has to hand over one that nobody else writes to.
     * </p>
     *
     * @param instance the instance which owns the chunk
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     * @param storage  the storage which holds the blocks and biomes of the chunk
     * @since 0.4.0
     */
    public FalcoChunk(Instance instance, int chunkX, int chunkZ, BlockStorage storage) {
        super(instance, chunkX, chunkZ, true);
        this.storage = storage;
    }

    /**
     * Hands out the storage which holds the blocks of this chunk.
     * <p>
     * Exposed because the storage is the part a caller may want to inspect or measure without going
     * through the chunk, and because the choice of layout is otherwise invisible from the outside.
     * </p>
     *
     * @return the storage of this chunk
     * @since 0.4.0
     */
    public BlockStorage storage() {
        return this.storage;
    }

    /**
     * Tells the chunk that it has finished loading.
     * <p>
     * This is the reachable form of the {@code protected} {@code Chunk#onLoad()} hook.
     * {@link FalcoInstance} calls it once, after the chunk has been put into the chunk map of the
     * instance and after its tick partition exists, which is the order Minestom uses as well.
     * </p>
     */
    public void markLoaded() {
        onLoad();
    }

    /**
     * Tells the chunk that it is no longer part of its instance.
     * <p>
     * This is the reachable form of the {@code protected} {@code Chunk#unload()} hook. It clears the
     * loaded flag, which is what every {@code ChunkUtils#isLoaded} check in Minestom reads, so a
     * chunk that is not marked here stays alive for the rest of the server for anyone holding a
     * reference to it.
     * </p>
     */
    public void markUnloaded() {
        unload();
    }

    /**
     * Writes a block into this chunk and runs the bookkeeping that goes with it.
     * <p>
     * Widened to public so {@link FalcoInstance}, which lives outside the Minestom package, can pass
     * a placement and a destruction along. Everything but the one line that reaches the storage is
     * the body {@code DynamicChunk} has, in its order: the cache is dropped first, then the block is
     * written, then the entry and tickable maps are brought in line with it, then the handlers of the
     * old and the new block are told, and only then are the heightmaps refreshed.
     * </p>
     * <p>
     * The order matters. A handler that reads the chunk during {@code onPlace} has to see the block
     * that was just written, which is why the storage is written before the handlers run.
     * </p>
     * <p>
     * The caller has to hold the write lock of this chunk.
     * </p>
     *
     * @param x         the block X
     * @param y         the block Y
     * @param z         the block Z
     * @param block     the block to write
     * @param placement the placement which caused the write, null if it was not a placement
     * @param destroy   the destruction which caused the write, null if it was not a break
     */
    @Override
    public void setBlock(int x, int y, int z, Block block,
                         @Nullable BlockHandler.Placement placement,
                         @Nullable BlockHandler.Destroy destroy) {
        assertWriteLock();
        final DimensionType instanceDim = instance.getCachedDimensionType();
        if (y >= instanceDim.maxY() || y < instanceDim.minY()) {
            LOGGER.warn("tried to set a block outside the world bounds, should be within [{}, {}): {}",
                    instanceDim.minY(), instanceDim.maxY(), y);
            return;
        }

        this.chunkCache.invalidate();

        final int sectionRelativeX = globalToSectionRelative(x);
        final int sectionRelativeZ = globalToSectionRelative(z);

        this.storage.setBlock(x, y, z, block);

        final int index = CoordConversion.chunkBlockIndex(x, y, z);
        // Handler
        final BlockHandler handler = block.handler();
        final Block lastCachedBlock;
        if (handler != null || block.hasNbt() || block.registry().isBlockEntity()) {
            lastCachedBlock = this.entries.put(index, block);
        } else {
            lastCachedBlock = this.entries.remove(index);
        }
        // Block tick
        if (handler != null && handler.isTickable()) {
            this.tickableMap.put(index, block);
        } else {
            this.tickableMap.remove(index);
        }

        // Update block handlers
        if (lastCachedBlock != null && lastCachedBlock.handler() != null) {
            // Previous destroy
            lastCachedBlock.handler().onDestroy(Objects.requireNonNullElseGet(destroy,
                    () -> new BlockHandler.Destroy(lastCachedBlock, block, instance,
                            CoordConversion.chunkBlockRelativeGetGlobal(sectionRelativeX, y, sectionRelativeZ, chunkX, chunkZ))));
        }
        if (handler != null) {
            // New placement
            final Point placePoint = CoordConversion.chunkBlockRelativeGetGlobal(sectionRelativeX, y, sectionRelativeZ, chunkX, chunkZ);
            handler.onPlace(Objects.requireNonNullElseGet(placement,
                    () -> new BlockHandler.Placement(block,
                            Objects.requireNonNullElseGet(lastCachedBlock, () -> this.getBlock(placePoint, Condition.TYPE)),
                            instance, placePoint)));
        }

        // UpdateHeightMaps
        if (this.needsCompleteHeightmapRefresh) calculateFullHeightmap();
        this.motionBlocking.refresh(sectionRelativeX, y, sectionRelativeZ, block);
        this.worldSurface.refresh(sectionRelativeX, y, sectionRelativeZ, block);
    }

    /**
     * Reads the block at a position.
     * <p>
     * The entries map is consulted first, because it is the only place a handler or NBT survives; a
     * caller which asked for {@link Condition#TYPE} skips that lookup, since a state id is all it
     * wants. Only what the entries do not answer reaches the storage.
     * </p>
     * <p>
     * A height outside the world is answered with air rather than with an exception, because the
     * neighbour updates of a block write walk one block up and down and would otherwise fall off the
     * world at its floor and its ceiling.
     * </p>
     * <p>
     * The caller has to hold the read lock of this chunk.
     * </p>
     *
     * @param x         the block X
     * @param y         the block Y
     * @param z         the block Z
     * @param condition what the caller is willing to accept
     * @return the block, or null if the condition excludes it
     */
    @Override
    public @Nullable Block getBlock(int x, int y, int z, Condition condition) {
        assertReadLock();
        if (y < minSection * CHUNK_SECTION_SIZE || y >= maxSection * CHUNK_SECTION_SIZE)
            return Block.AIR; // Out of bounds

        // Verify if the block object is present
        if (condition != Condition.TYPE) {
            final Block entry = !this.entries.isEmpty()
                    ? this.entries.get(CoordConversion.chunkBlockIndex(x, y, z)) : null;
            if (entry != null || condition == Condition.CACHED) {
                return entry;
            }
        }
        return this.storage.getBlock(x, y, z, condition);
    }

    /**
     * Writes a biome into this chunk.
     * <p>
     * The cached packet has to be dropped here as well: a biome is part of the section data a client
     * receives, so a chunk that kept its cache would keep sending the old biome.
     * </p>
     * <p>
     * The caller has to hold the write lock of this chunk.
     * </p>
     *
     * @param x     the block X
     * @param y     the block Y
     * @param z     the block Z
     * @param biome the biome to write
     */
    @Override
    public void setBiome(int x, int y, int z, RegistryKey<Biome> biome) {
        assertWriteLock();
        this.chunkCache.invalidate();
        this.storage.setBiome(x, y, z, biome);
    }

    /**
     * Reads the biome at a position.
     * <p>
     * The caller has to hold the read lock of this chunk.
     * </p>
     *
     * @param x the block X
     * @param y the block Y
     * @param z the block Z
     * @return the biome
     */
    @Override
    public RegistryKey<Biome> getBiome(int x, int y, int z) {
        assertReadLock();
        return this.storage.getBiome(x, y, z);
    }

    /**
     * Hands out the sections of this chunk, from the bottom one upwards.
     *
     * @return the sections of this chunk
     */
    @Override
    public List<Section> getSections() {
        return this.storage.sections();
    }

    /**
     * Hands out one section of this chunk.
     * <p>
     * {@code Chunk#getSection(int)} counts sections in world terms, which is negative below the zero
     * line, while {@link BlockStorage#section(int)} counts from the bottom section of the chunk.
     * Subtracting {@code minSection} is that translation, and it is the one place where forgetting it
     * would be silent: a wrong section still holds blocks, just not the ones that were asked for.
     * </p>
     *
     * @param section the section index in world terms
     * @return the section
     */
    @Override
    public Section getSection(int section) {
        return this.storage.section(section - minSection);
    }

    /**
     * Hands out the heightmap of the highest movement-blocking block per column.
     *
     * @return the motion blocking heightmap
     */
    @Override
    public Heightmap motionBlockingHeightmap() {
        return this.motionBlocking;
    }

    /**
     * Hands out the heightmap of the highest non-air block per column.
     *
     * @return the world surface heightmap
     */
    @Override
    public Heightmap worldSurfaceHeightmap() {
        return this.worldSurface;
    }

    /**
     * Takes over the heightmaps a chunk loader read from disk.
     * <p>
     * A heightmap that is not in the tag is left alone rather than zeroed, because an absent
     * heightmap means the file did not carry one, not that every column is empty.
     * </p>
     * <p>
     * The caller has to hold the write lock of this chunk.
     * </p>
     *
     * @param heightmapsNBT the heightmap compound of the chunk
     */
    @Override
    public void loadHeightmapsFromNBT(CompoundBinaryTag heightmapsNBT) {
        assertWriteLock();
        if (heightmapsNBT.get(motionBlockingHeightmap().type().name()) instanceof LongArrayBinaryTag array) {
            motionBlockingHeightmap().loadFrom(array.value());
        }

        if (heightmapsNBT.get(worldSurfaceHeightmap().type().name()) instanceof LongArrayBinaryTag array) {
            worldSurfaceHeightmap().loadFrom(array.value());
        }
    }

    /**
     * Ticks the block handlers of this chunk which asked to be ticked.
     * <p>
     * The empty check up front is what keeps a world of ordinary chunks cheap: almost every chunk has
     * no tickable block at all, and this makes its tick a single comparison.
     * </p>
     *
     * @param time the time of the tick in milliseconds
     */
    @Override
    public void tick(long time) {
        if (this.tickableMap.isEmpty()) return;
        this.tickableMap.int2ObjectEntrySet().fastForEach(entry -> {
            final int index = entry.getIntKey();
            final Block block = entry.getValue();
            final BlockHandler handler = block.handler();
            if (handler == null) return;
            final Point blockPosition = CoordConversion.chunkBlockIndexGetGlobal(index, chunkX, chunkZ);
            handler.tick(new BlockHandler.Tick(block, instance, blockPosition));
        });
    }

    /**
     * Hands out the packet which carries this chunk to a client.
     * <p>
     * The cache itself is returned, not a packet. It serialises on the first send after a change and
     * every further viewer receives the bytes that were already there.
     * </p>
     *
     * @return the cached chunk packet
     */
    @Override
    public SendablePacket getFullDataPacket() {
        return this.chunkCache;
    }

    /**
     * Creates a copy of this chunk at the given position.
     * <p>
     * The copy is a {@link FalcoChunk} again, which matters beyond tidiness: a plain
     * {@code DynamicChunk} could never be unloaded by {@link FalcoInstance}, because its lifecycle
     * hooks are out of reach from this package.
     * </p>
     * <p>
     * Both block maps are carried over. {@code DynamicChunk#copy} carries only the entries, which
     * leaves a copied chunk with block entities that have stopped ticking; that omission is a defect
     * this class already corrected before the storage moved, and it stays corrected.
     * </p>
     * <p>
     * The caller has to hold the read lock of this chunk.
     * </p>
     *
     * @param instance the instance which owns the copy
     * @param chunkX   the chunk X of the copy
     * @param chunkZ   the chunk Z of the copy
     * @return a copy of this chunk at the given position
     */
    @Override
    public Chunk copy(Instance instance, int chunkX, int chunkZ) {
        assertReadLock();
        final FalcoChunk copy = new FalcoChunk(instance, chunkX, chunkZ, this.storage.copy());

        copy.entries.putAll(this.entries);
        copy.tickableMap.putAll(this.tickableMap);
        return copy;
    }

    /**
     * Empties this chunk.
     * <p>
     * The caller has to hold the write lock of this chunk.
     * </p>
     */
    @Override
    public void reset() {
        assertWriteLock();
        this.storage.clear();
        this.entries.clear();
    }

    /**
     * Drops everything this chunk derived from its blocks.
     * <p>
     * Both the packet and the heightmaps are dropped, because the case this exists for is a change
     * that did not go through {@link #setBlock(int, int, int, Block, BlockHandler.Placement, BlockHandler.Destroy)}
     * — a generator or a loader writing into the sections directly — and such a change leaves no
     * trace either of them would notice on their own.
     * </p>
     */
    @Override
    public void invalidate() {
        this.needsCompleteHeightmapRefresh = true;
        this.chunkCache.invalidate();
    }

    /**
     * Takes a snapshot of this chunk.
     * <p>
     * The sections are cloned rather than shared, because a snapshot is read without any lock and a
     * shared section would keep changing underneath its reader.
     * </p>
     *
     * @param updater the updater which resolves the references of the snapshot
     * @return the snapshot of this chunk
     */
    @Override
    public ChunkSnapshot updateSnapshot(SnapshotUpdater updater) {
        final List<Section> sections = this.storage.sections();
        final Section[] clonedSections = new Section[sections.size()];
        for (int i = 0; i < clonedSections.length; i++)
            clonedSections[i] = sections.get(i).clone();
        final var entities = instance.getEntityTracker().chunkEntities(chunkX, chunkZ, EntityTracker.Target.ENTITIES);
        final int[] entityIds = ArrayUtils.mapToIntArray(entities, Entity::getEntityId);
        return new SnapshotImpl.Chunk(minSection, chunkX, chunkZ,
                clonedSections, this.entries.clone(), entityIds, updater.reference(instance),
                tagHandler().readableCopy());
    }

    /**
     * Serialises this chunk into the packet a client receives.
     * <p>
     * The lock dance is the one {@code DynamicChunk} performs and it is not decoration. The heightmap
     * refresh writes, so it needs the write lock; the light computation is left outside every lock,
     * because it reaches into neighbouring chunks and taking their locks while holding this one is
     * how two chunks deadlock each other; the section read only needs the read lock.
     * </p>
     *
     * @return the chunk packet
     */
    private ChunkDataPacket createChunkPacket() {
        final Map<Heightmap.Type, long[]> heightmaps;
        lockWriteLock();
        try {
            heightmaps = getHeightmaps();
        } finally {
            unlockWriteLock();
        }
        // Compute light data outside any locks. This *should* prevent deadlocks
        final LightData lightData = createLightData(true);

        lockReadLock();
        try {
            final NetworkBuffer.Type<ChunkData.Section> sectionSerializer =
                    ChunkData.Section.networkType(MinecraftServer.getBiomeRegistry().size());
            final byte[] data = NetworkBuffer.makeArray(networkBuffer -> {
                for (Section section : this.storage.sections()) {
                    final short blockCount = (short) section.blockPalette().count();
                    final short liquidCount = (short) (blockCount > 0 ? 1 : 0); //TODO(26.1) proper fluid count
                    networkBuffer.write(sectionSerializer,
                            new ChunkData.Section(blockCount, liquidCount, section.blockPalette(), section.biomePalette()));
                }
            });

            return new ChunkDataPacket(chunkX, chunkZ,
                    new ChunkData(heightmaps, data, this.entries),
                    lightData
            );
        } finally {
            unlockReadLock();
        }
    }

    /**
     * Collects the light arrays of the sections into the form the protocol wants.
     * <p>
     * A section whose array is empty is reported as empty rather than as zeroed, which is the
     * difference between "this section has no light data" and "this section is pitch black".
     * </p>
     * <p>
     * The flag is unused here because this chunk always reports what it has. It is part of the
     * signature so that a subclass which computes light can tell a full chunk send apart from a
     * partial light update, which is the hook {@code LightingChunk} uses.
     * </p>
     *
     * @param requiredFullChunk true if the data is meant for a full chunk send
     * @return the light data of this chunk
     */
    protected LightData createLightData(boolean requiredFullChunk) {
        final BitSet skyMask = new BitSet();
        final BitSet blockMask = new BitSet();
        final BitSet emptySkyMask = new BitSet();
        final BitSet emptyBlockMask = new BitSet();
        final List<byte[]> skyLights = new ArrayList<>();
        final List<byte[]> blockLights = new ArrayList<>();

        int index = 0;
        for (Section section : this.storage.sections()) {
            index++;
            final byte[] skyLight = section.skyLight().array();
            final byte[] blockLight = section.blockLight().array();
            if (skyLight.length != 0) {
                skyLights.add(skyLight);
                skyMask.set(index);
            } else {
                emptySkyMask.set(index);
            }
            if (blockLight.length != 0) {
                blockLights.add(blockLight);
                blockMask.set(index);
            } else {
                emptyBlockMask.set(index);
            }
        }
        return new LightData(
                skyMask, blockMask,
                emptySkyMask, emptyBlockMask,
                skyLights, blockLights
        );
    }

    /**
     * Hands out both heightmaps in the form the chunk packet wants.
     *
     * @return the heightmaps of this chunk, keyed by their type
     */
    protected Map<Heightmap.Type, long[]> getHeightmaps() {
        assertReadLock();
        if (this.needsCompleteHeightmapRefresh) calculateFullHeightmap();
        return Map.of(
                this.motionBlocking.type(), this.motionBlocking.getNBT(),
                this.worldSurface.type(), this.worldSurface.getNBT()
        );
    }

    /**
     * Rebuilds both heightmaps from the blocks of this chunk.
     * <p>
     * The scan starts at the highest section that holds anything, so an empty chunk costs nothing and
     * a chunk with a low world costs only what it fills.
     * </p>
     */
    private void calculateFullHeightmap() {
        assertWriteLock();
        final int startY = Heightmap.getHighestBlockSection(this);
        this.motionBlocking.refresh(startY);
        this.worldSurface.refresh(startY);
        this.needsCompleteHeightmapRefresh = false;
    }
}
