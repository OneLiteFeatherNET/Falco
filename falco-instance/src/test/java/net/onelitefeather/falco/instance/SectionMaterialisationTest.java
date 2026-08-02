package net.onelitefeather.falco.instance;

import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.SendablePacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Counts what a caller makes a {@link FalcoChunk} allocate at the three boundaries where Minestom
 * insists on real {@code Section} objects.
 * <p>
 * The saving of {@link LazySectionBlockStorage} is only worth what survives contact with those
 * boundaries. A chunk which holds nothing until something is written into it, and then materialises
 * all twenty-four sections the first time anybody sends it, has saved nothing; the spec records
 * exactly that as an open risk. Every case below therefore states a number rather than a direction,
 * and the number is read off {@link BlockStorage#materialisedSections()}, which is the one counter
 * that cannot be satisfied by an intention.
 * </p>
 *
 * <h2>Why the measurement never goes through the chunk</h2>
 * <p>
 * {@code Chunk#getSections()} and {@code Chunk#getSection(int)} are not reads on a lazy chunk, they
 * are writes: both materialise, which is what the two cases named after them assert. A test which
 * counted sections by walking either of them would build the very sections it claims to have found,
 * and the previous footprint measurement of this project was wrong for precisely that reason. The
 * counter is asked directly instead, through {@link FalcoChunk#storage()}, and no case here touches
 * a section except the two that exist to price the boundary.
 * </p>
 *
 * <h2>Where the numbers come from</h2>
 * <p>
 * Every expectation is derived from the source of Minestom {@code 2026.06.20-26.1.2} before it is
 * run, not corrected afterwards until the bar turns green. The dimension is the overworld: twenty-four
 * sections, world Y from {@code -64} to {@code 319}, so {@code Heightmap#minHeight} is {@code -65} and
 * a column scan that finds nothing walks to the floor.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@DisplayName("What a caller of a Falco chunk makes it allocate")
class SectionMaterialisationTest {

    private static final int SECTIONS = 24;

    private static InstanceContainer container;

    @BeforeAll
    static void server() {
        if (MinecraftServer.process() == null) {
            MinecraftServer.init();
        }
        container = MinecraftServer.getInstanceManager().createInstanceContainer();
    }

    private static FalcoChunk chunk() {
        return new FalcoChunk(container, 0, 0);
    }

    private static int owned(FalcoChunk chunk) {
        return chunk.storage().materialisedSections();
    }

    private static void write(FalcoChunk chunk, int y, Block block) {
        chunk.lockWriteLock();
        try {
            chunk.setBlock(0, y, 0, block);
        } finally {
            chunk.unlockWriteLock();
        }
    }

    /**
     * Forces the chunk packet to be built.
     * <p>
     * {@code Chunk#getFullDataPacket()} hands out a {@code CachedPacket}, which serialises nothing
     * until somebody asks it for a packet; calling it alone would measure a field read and would pass
     * on a chunk that never serialises at all. {@code SendablePacket#extractServerPacket} is the route
     * a player connection takes and is what actually runs {@code FalcoChunk#createChunkPacket}.
     * </p>
     *
     * @param chunk the chunk to serialise
     */
    private static void serialise(FalcoChunk chunk) {
        SendablePacket.extractServerPacket(ConnectionState.PLAY, chunk.getFullDataPacket());
    }

    @Test
    @DisplayName("a fresh chunk owns nothing")
    void testAFreshChunkOwnsNoSection() {
        assertEquals(0, owned(chunk()));
    }

    @Test
    @DisplayName("reading a whole empty chunk owns nothing")
    void testReadingOwnsNothing() {
        final FalcoChunk chunk = chunk();

        chunk.lockReadLock();
        try {
            for (int y = -64; y < 320; y++) {
                chunk.getBlock(0, y, 0);
            }
        } finally {
            chunk.unlockReadLock();
        }
        assertEquals(0, owned(chunk));
    }

    @Test
    @DisplayName("one write owns its own section and everything the first heightmap refresh walks over")
    void testOneWriteOwnsItsSectionAndTheHeightmapDescent() {
        final FalcoChunk chunk = chunk();

        write(chunk, 64, Block.STONE);

        assertEquals(10, owned(chunk),
                "one for the section the block landed in, nine for the heightmap. The first write of "
                        + "a chunk triggers the full refresh, which starts at world Y 80 - the bottom "
                        + "of the section above the highest non-empty one, as "
                        + "Heightmap#getHighestBlockSection computes it - and then runs "
                        + "Heightmap#refresh(int,int,int) for all 256 columns. That descent reaches "
                        + "its sections through Chunk#getSection, which materialises, and 255 of the "
                        + "columns find nothing and walk to the floor: sections 5 down to -4. The "
                        + "number is 10 and not 1 because the heightmap of Minestom cannot be told to "
                        + "read instead of to take; if it ever becomes 24 the scan has stopped "
                        + "starting from highestBlockSection() and the whole stage is worth nothing");
    }

    @Test
    @DisplayName("serialising a fresh chunk owns only the floor section")
    void testSerialisingAFreshChunkOwnsOnlyTheFloorSection() {
        final FalcoChunk chunk = chunk();

        serialise(chunk);

        assertEquals(1, owned(chunk),
                "the packet body and the light data both read through BlockStorage#views() and own "
                        + "nothing at all; the one section is the heightmap again. An empty chunk has "
                        + "no non-empty section, so the refresh starts at world Y -64, every column "
                        + "asks Chunk#getSection(-4) and immediately falls off the bottom of the "
                        + "world. This is the price of a chunk send: one section, not twenty-four");
    }

    @Test
    @DisplayName("serialising a written chunk owns nothing beyond what the write already cost")
    void testSerialisingAWrittenChunkOwnsNothingMore() {
        final FalcoChunk chunk = chunk();

        write(chunk, 64, Block.STONE);
        final int beforeSend = owned(chunk);

        serialise(chunk);

        assertEquals(beforeSend, owned(chunk),
                "the heightmaps were already refreshed by the write, so the send is left with the "
                        + "serialisation itself - and that is the claim this whole stage rests on: "
                        + "the network boundary reads through views() and materialises nothing, no "
                        + "matter how often a chunk is sent");
        assertEquals(10, beforeSend);
    }

    @Test
    @DisplayName("asking for one section through the Minestom boundary owns exactly that one")
    void testGetSectionOwnsOne() {
        final FalcoChunk chunk = chunk();

        chunk.getSection(4);

        assertEquals(1, owned(chunk));
    }

    @Test
    @DisplayName("asking for the section list through the Minestom boundary owns the whole chunk")
    void testGetSectionsOwnsEverything() {
        final FalcoChunk chunk = chunk();

        chunk.getSections();

        assertEquals(SECTIONS, owned(chunk),
                "this is the price of the boundary and it is stated rather than hidden: a caller "
                        + "which reaches into the chunk this way makes the lazy layout cost exactly "
                        + "what the eager one costs");
    }

    @Test
    @DisplayName("a copy owns what the original owned")
    void testCopyOwnsWhatWasOwned() {
        final FalcoChunk chunk = chunk();

        write(chunk, 64, Block.STONE);
        chunk.lockReadLock();
        final Chunk copy;
        try {
            copy = chunk.copy(container, 1, 1);
        } finally {
            chunk.unlockReadLock();
        }

        assertEquals(owned(chunk), ((FalcoChunk) copy).storage().materialisedSections(),
                "LazySectionBlockStorage#copy() carries the sharing over slot by slot, so a copy is "
                        + "neither cheaper nor more expensive than what it copied");
        assertEquals(10, owned(chunk));
    }

    @Test
    @DisplayName("the first heightmap refresh costs whatever sits below the terrain")
    void testTheFirstHeightmapRefreshCostsWhatSitsBelowTheTerrain() {
        final FalcoChunk chunk = chunk();

        write(chunk, 200, Block.STONE);
        write(chunk, -64, Block.STONE);

        assertEquals(SECTIONS - 6, owned(chunk),
                "this is the known leak and its size is the height of the terrain at the moment of "
                        + "the first full refresh, not the number of gaps. The block at y=200 makes "
                        + "Heightmap#getHighestBlockSection stop at section 12, so the scan starts at "
                        + "world Y 208 and the 255 columns which hold nothing walk from section 13 "
                        + "down to section -4 through Chunk#getSection: 18 sections. The six that "
                        + "stay shared are the ones above world Y 223. Heightmap#refresh(int,int,int) "
                        + "cannot be overridden - it ends in a private setter over a private array - "
                        + "so this number is asserted rather than fixed, and it must not grow");
    }

    @Test
    @DisplayName("the same two blocks cost three sections when the low one is written first")
    void testTheOrderOfTheFirstTwoWritesDecidesTheHeightmapCost() {
        final FalcoChunk chunk = chunk();

        write(chunk, -64, Block.STONE);
        write(chunk, 200, Block.STONE);
        serialise(chunk);

        assertEquals(3, owned(chunk),
                "the same two blocks as the case above, in the other order, and the chunk holds a "
                        + "sixth of the sections. The full refresh runs on the first write, when the "
                        + "only block sits on the floor, so it starts at world Y -48 and touches "
                        + "sections -3 and -4; the write at y=200 afterwards only reaches "
                        + "Heightmap#refresh(int,int,int,Block), which compares heights and never "
                        + "asks for a section. Sending the chunk adds nothing, because both "
                        + "heightmaps have stopped needing a refresh. The leak is therefore paid "
                        + "once, on the first full refresh of a chunk, and a chunk which is written "
                        + "from the bottom up never pays it in full");
    }

    @Test
    @DisplayName("the eager storage owns everything from the start, which is what makes the counts above a saving")
    void testTheEagerStorageOwnsEverythingFromTheStart() {
        final FalcoChunk eager = new FalcoChunk(container, 0, 0,
                new SectionBlockStorage(-4, SECTIONS));

        assertEquals(SECTIONS, owned(eager),
                "the control. Without it every number above could be read as a property of the "
                        + "counter rather than of the layout");

        write(eager, 64, Block.STONE);
        serialise(eager);

        assertEquals(SECTIONS, owned(eager));
    }
}
