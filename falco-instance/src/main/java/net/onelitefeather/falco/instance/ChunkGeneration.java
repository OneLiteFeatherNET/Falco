package net.onelitefeather.falco.instance;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.GenerationUnit;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.instance.generator.GeneratorImpl;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.registry.Registries;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The {@link ChunkGeneration} class runs a generator over a chunk and commits what it produced.
 * <p>
 * It is a collaborator of {@code ChunkLifecycle} rather than a part of the facade, and the reason is
 * that a chunk is generated exactly once and that once is inside its load. Splitting generation off
 * as a fifth part of the facade would give the instance a field nothing but the lifecycle ever
 * touches.
 * </p>
 * <p>
 * It reaches a chunk which is not the one it was asked about through the function it was built with
 * rather than through an instance. A fork writes into a neighbour, and a neighbour is the only thing
 * this class ever needs a world for; taking that as a parameter is what lets it be driven by a test
 * that has no instance at all.
 * </p>
 * <p>
 * This type is experimental. The instance module is new and its API may still change.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Experimental
public final class ChunkGeneration {

    /**
     * The registries the biomes of a generated chunk are looked up in.
     */
    private final Registries registries;

    /**
     * How a chunk at a point is found, for the forks which land outside the generated chunk.
     */
    private final Function<Point, Chunk> chunkAt;

    /**
     * The section modifiers a generator produced for chunks which were not loaded at the time, keyed
     * by the chunk index of the chunk they belong to.
     * <p>
     * A generator may write outside the chunk it was asked about through
     * {@link GenerationUnit#fork(java.util.function.Consumer)}. Those writes cannot be applied yet
     * when their target does not exist, and dropping them would make a generator produce different
     * worlds depending on the order in which chunks happened to be requested.
     * </p>
     */
    private final Map<Long, List<GeneratorImpl.SectionModifierImpl>> generationForks = new ConcurrentHashMap<>();

    /**
     * The generator which fills a chunk no loader knows about, null while the world stays empty.
     */
    private volatile @Nullable Generator generator;

    /**
     * Creates a generation side.
     *
     * @param registries the registries the biomes of a generated chunk are looked up in
     * @param chunkAt    how a chunk at a point is found, for forks which land outside
     */
    public ChunkGeneration(Registries registries, Function<Point, Chunk> chunkAt) {
        this.registries = registries;
        this.chunkAt = chunkAt;
    }

    /**
     * Returns the generator which fills a chunk no loader knows about.
     *
     * @return the current generator, null if chunks without a loader stay empty
     */
    public @Nullable Generator generator() {
        return this.generator;
    }

    /**
     * Changes the generator which fills a chunk no loader knows about.
     * <p>
     * Chunks which are already loaded are not affected. A generator is asked for a chunk exactly
     * once, when that chunk is created, so changing it later changes the parts of the world which are
     * not there yet.
     * </p>
     *
     * @param generator the new generator, null to let chunks without a loader stay empty
     */
    public void generator(@Nullable Generator generator) {
        this.generator = generator;
    }

    /**
     * Returns how many chunk positions are waiting for a fork to be delivered to them.
     * <p>
     * Exposed because a map nothing can observe is a map nothing can assert, and a fork for a chunk
     * that is never requested is the one case that leaks quietly.
     * </p>
     *
     * @return the amount of positions with a pending fork
     */
    public int pendingForks() {
        return this.generationForks.size();
    }

    /**
     * Drops every fork which is still waiting for its chunk.
     * <p>
     * A fork whose target chunk was never requested waits forever, and after a shutdown there is
     * nothing left it could wait for.
     * </p>
     */
    public void clearPending() {
        this.generationForks.clear();
    }

    /**
     * Runs a generator over a chunk and commits everything it produced in one step.
     * <p>
     * The generator writes into copies of the palettes of the chunk, not into the palettes
     * themselves, and the copies are moved over only after the generator returned. That is the whole
     * difference to {@code InstanceContainer#generateChunk(Chunk, Generator)}, which hands the live
     * palettes over and catches whatever the generator throws into the exception manager of the
     * server. A generator which fails halfway there leaves a chunk that is half built, published and
     * reported as loaded, and the caller who asked for the chunk is told nothing. Here the failure
     * travels to that caller and the chunk is exactly as it was.
     * </p>
     * <p>
     * The copies cost one palette clone per section. On a chunk which is still empty — the case
     * which matters, because that is where a generator normally runs — a palette is in its single
     * value mode and holds no array at all, so the clone is a few bytes.
     * </p>
     * <p>
     * Those copies are taken from the <em>views</em> of the storage and not from its sections. That is
     * the difference between a lazy chunk which survives its own generator and one which does not:
     * {@code Chunk#getSections()} materialises every section it hands out, so staging a generation
     * through it would create all twenty-four sections of a chunk before the generator has written a
     * single block, and the section a generator decides not to fill would already exist by the time
     * that decision is made. {@link BlockStorage#view(int)} promises the opposite and is read-only,
     * which is all the staging needs: the palettes handed to the generator are clones either way.
     * </p>
     * <p>
     * The write lock of the chunk is held for the commit only. Minestom holds it across the whole
     * generator instead, which stops every read and every write of that chunk for as long as the
     * generator runs.
     * </p>
     *
     * <h4>Why the commit is two passes and not one</h4>
     * <p>
     * Every palette is moved over first, and only then are the blocks written that need more than a
     * palette entry. The two used to be one pass and that was a defect, because the second half is
     * not a write into a section: {@link #writeSpecialBlocks} goes through {@code Chunk#setBlock},
     * which begins with {@code if (needsCompleteHeightmapRefresh) calculateFullHeightmap()}. On a
     * chunk that was just generated the flag is true, so the first such block computes both
     * heightmaps — over whatever part of the chunk had been committed by then. Everything above the
     * section that block happens to sit in is still empty at that moment and the heights come out
     * short.
     * </p>
     * <p>
     * What made it permanent rather than merely early is that nothing can re-arm it.
     * {@code Heightmap#refresh(int)} sets a {@code private needsRefresh} to false, and the
     * {@code chunk.invalidate()} below only flips the flag of the chunk; the next
     * {@code calculateFullHeightmap} calls {@code refresh(startY)} again and that method returns on
     * its first line. The wrong heights then go into every chunk packet for the life of the chunk.
     * {@code FalcoInstanceGeneratorTest#testTheHeightmapsSeeTheWholeChunkAndNotHalfOfIt} pins the
     * case with the numbers it produced: {@code 79} instead of {@code 127}.
     * </p>
     * <p>
     * The {@code chunk.invalidate()} sits between the two passes rather than after them, so that the
     * refresh the first special block triggers is one over the complete chunk and not one over
     * heights the palettes have meanwhile invalidated. Minestom arrives at the same order by a
     * different route: {@code InstanceContainer} commits every palette in one loop and runs
     * {@code applyGenerationData} afterwards.
     * </p>
     *
     * @param chunk     the chunk to fill
     * @param generator the generator to run over the chunk
     */
    public void apply(Chunk chunk, Generator generator) {
        final BlockStorage storage = storageOf(chunk);
        final int sectionCount = storage.sectionCount();
        final GeneratorImpl.GenSection[] staged = new GeneratorImpl.GenSection[sectionCount];
        Arrays.setAll(staged, index -> {
            final Section view = storage.view(index);
            return new GeneratorImpl.GenSection(view.blockPalette().clone(), view.biomePalette().clone());
        });
        final GeneratorImpl.UnitImpl unit = GeneratorImpl.chunk(this.registries.biome(), staged,
                chunk.getChunkX(), chunk.getMinSection(), chunk.getChunkZ());

        generator.generate(unit);

        chunk.lockWriteLock();
        try {
            for (int index = 0; index < sectionCount; index++) {
                commitSection(storage, index, staged[index]);
            }
            chunk.invalidate();
            for (int index = 0; index < sectionCount; index++) {
                writeSpecialBlocks(chunk, staged[index].specials(),
                        (chunk.getMinSection() + index) * Chunk.CHUNK_SECTION_SIZE);
            }
        } finally {
            chunk.unlockWriteLock();
        }

        applyForks(chunk, unit);
        applyPending(chunk);
    }

    /**
     * Writes one generated section back into the chunk, or leaves the chunk alone if it produced
     * nothing.
     * <p>
     * The skip is what makes a lazy layout survive its own generator. A generator normally fills the
     * lower third of a chunk and leaves everything above the terrain untouched — the census of a real
     * overworld puts that untouched share at {@code 62,24 %} of the sections of a finished chunk — and
     * committing an empty palette into an empty section would create twenty-four sections to write
     * nothing into twenty of them. The condition is the one {@code InstanceContainer} already applies
     * to fork sections at {@code InstanceContainer.java:434}, extended by the biomes and by the special
     * blocks, since either of those can be the only thing a generator produced for a section.
     * </p>
     * <p>
     * That {@code 62,24 %} is a census of <em>block</em> content, and the saving is worth that much
     * only for a generator which leaves the sections above its terrain alone entirely. A biome is
     * stored per section whether the section holds a block or not, so a generator which calls
     * {@code UnitModifier#fillBiome} on the chunk unit — the ordinary way to give a chunk a biome —
     * reaches every one of the twenty-four section modifiers, fills every biome palette, and this
     * method then materialises all twenty-four sections. Nothing is lost there and nothing is wrong:
     * a section which has to carry a biome has to exist. It is written down because the number on its
     * own reads like a promise about every generator, and it is a promise about generators which write
     * blocks. {@code SectionMaterialisationTest} holds both cases side by side, at {@code 4} and at
     * {@code 24}.
     * </p>
     * <p>
     * Each of the three clauses is the last line of defence for one kind of content, and each is
     * covered by a case which fails if that clause is dropped. The blocks are the ordinary case; a
     * section whose only content is a biome is the second; a section whose only content is a handler on
     * air is the third, and it is the subtle one, because {@code SectionModifierImpl#handleCache}
     * writes such a block into the palette as its state id, which for air is {@code 0} — the palette
     * reports {@code count() == 0} and the specials map is the only evidence the generator was there.
     * </p>
     * <p>
     * The specials clause stays in the condition even though this method no longer writes them —
     * {@link #apply} does, in a second pass, for the reason stated there. It has to: the
     * block of that third case is air with a handler, and {@link BlockStorage#setBlock} skips a write
     * of air into a shared slot, so the section would stay shared and the storage would answer a
     * question about it without ever having one. Materialising it here is what keeps that section a
     * section.
     * </p>
     * <p>
     * A section that is still shared and received nothing needs no write at all, and that is exactly
     * what the condition tests. A section the chunk already owns is committed unconditionally: it
     * holds content from a loader or an earlier write, and an empty generated palette is a statement
     * about what the generator produced and not about what the chunk should end up holding.
     * </p>
     * <p>
     * The compaction afterwards is US-2.03. A generator writes through {@code GenSection} palettes
     * which grow to fifteen bits per entry and never shrink again, because nothing in the main source
     * tree of Minestom ever calls {@code Palette#optimize} — a generated chunk retains
     * {@code 203 840} bytes where the same content packed to its minimum width retains {@code 84 800}.
     * What that costs in time is measured by {@code GeneratorCommitBenchmark} and it is not assumed to
     * be free: {@code 576,7 µs} against {@code 22,0 µs} for the bare commit of a chunk of twenty-four
     * sections. It goes through {@link PaletteCompaction} rather than straight to
     * {@code Palette#optimize} because the same benchmark found the two cases where that price buys
     * nothing at all — a section past the indirect ceiling comes out exactly as wide as it went in, and
     * a section that is already packed was never going to move — and the guard turns those into
     * {@code 185,1 µs} and {@code 31,8 µs}. What it costs is stated there too: {@code 24 %} on the case
     * where the optimisation does narrow something.
     * </p>
     *
     * @param storage   the storage of the chunk
     * @param index     the index of the section, counted from the bottom one
     * @param generated the section the generator produced
     */
    private void commitSection(BlockStorage storage, int index, GeneratorImpl.GenSection generated) {
        final boolean producedNothing = generated.blocks().count() == 0
                && generated.biomes().count() == 0
                && generated.specials().isEmpty();

        if (producedNothing && storage.shared(index)) {
            return;
        }
        final Section section = storage.section(index);

        section.blockPalette().copyFrom(generated.blocks());
        section.biomePalette().copyFrom(generated.biomes());
        PaletteCompaction.packBlocks(section.blockPalette());
        PaletteCompaction.packBiomes(section.biomePalette());
    }

    /**
     * Hands out the storage of a chunk, whatever kind of chunk it is.
     * <p>
     * A chunk supplier is a setting of the instance and a caller is free to install one which does not
     * produce a {@link FalcoChunk}. Rather than carrying two generation paths, a foreign chunk is
     * wrapped in a {@link SectionBlockStorage} over its own live sections: that storage shares nothing
     * and materialises nothing, so every decision below it collapses into the behaviour Minestom has,
     * and the writes go straight into the sections of the chunk because the list holds the same
     * {@code Section} references.
     * </p>
     *
     * @param chunk the chunk to reach the sections of
     * @return the storage of the chunk
     */
    private static BlockStorage storageOf(Chunk chunk) {
        if (chunk instanceof FalcoChunk falcoChunk) {
            return falcoChunk.storage();
        }
        return new SectionBlockStorage(chunk.getMinSection(), chunk.getSections());
    }

    /**
     * Writes the blocks of a generated section which need more than a palette entry.
     * <p>
     * A palette holds a block state and nothing else, so a block which carries nbt, a handler or a
     * block entity has to be written through the chunk as well. The generator collected those
     * separately, keyed by a position relative to its section.
     * </p>
     * <p>
     * The caller has to hold the write lock of the chunk.
     * </p>
     *
     * @param chunk         the chunk which receives the blocks
     * @param specials      the blocks of the section which need their own entry
     * @param sectionStartY the block Y at which the section begins
     */
    private void writeSpecialBlocks(Chunk chunk, Int2ObjectMap<Block> specials, int sectionStartY) {
        if (specials.isEmpty()) return;
        for (Int2ObjectMap.Entry<Block> entry : specials.int2ObjectEntrySet()) {
            final int position = entry.getIntKey();
            chunk.setBlock(CoordConversion.chunkBlockIndexGetX(position),
                    CoordConversion.chunkBlockIndexGetY(position) + sectionStartY,
                    CoordConversion.chunkBlockIndexGetZ(position),
                    entry.getValue());
        }
    }

    /**
     * Delivers the writes a generator made outside the chunk it was asked about.
     * <p>
     * A fork which lands in a chunk that exists is applied right away, and one which lands in a
     * chunk that does not is remembered until that chunk is created. Dropping the second kind is
     * what would make a generator produce a different world depending on the order in which chunks
     * were requested, which is the property a fork exists to avoid.
     * </p>
     *
     * @param chunk the chunk the generator was asked about
     * @param unit  the unit the generator wrote into
     */
    private void applyForks(Chunk chunk, GeneratorImpl.UnitImpl unit) {
        final int chunkX = chunk.getChunkX();
        final int chunkZ = chunk.getChunkZ();
        for (GeneratorImpl.UnitImpl fork : unit.forks()) {
            if (!(fork.modifier() instanceof GeneratorImpl.AreaModifierImpl area)) continue;
            for (GenerationUnit section : area.sections()) {
                if (!(section.modifier() instanceof GeneratorImpl.SectionModifierImpl modifier)) continue;
                if (modifier.genSection().blocks().count() == 0) continue;
                final Point start = section.absoluteStart();
                if (start.chunkX() == chunkX && start.chunkZ() == chunkZ) {
                    applyFork(chunk, modifier);
                    continue;
                }
                final Chunk target = this.chunkAt.apply(start);
                if (target != null && target.isLoaded()) {
                    applyFork(target, modifier);
                    target.sendChunk();
                    continue;
                }
                this.generationForks.compute(CoordConversion.chunkIndex(start), (_, modifiers) -> {
                    final List<GeneratorImpl.SectionModifierImpl> pending =
                            modifiers == null ? new ArrayList<>() : modifiers;
                    pending.add(modifier);
                    return pending;
                });
            }
        }
    }

    /**
     * Applies the forks which were waiting for the given chunk to exist.
     *
     * @param chunk the chunk which just came into being
     */
    public void applyPending(Chunk chunk) {
        final long index = CoordConversion.chunkIndex(chunk.getChunkX(), chunk.getChunkZ());
        this.generationForks.compute(index, (_, modifiers) -> {
            if (modifiers != null) {
                for (GeneratorImpl.SectionModifierImpl modifier : modifiers) applyFork(chunk, modifier);
            }
            return null;
        });
    }

    /**
     * Writes one section of a fork into a chunk.
     * <p>
     * A fork which produced nothing for this section returns before the chunk is touched, because
     * {@code Chunk#getSectionAt} materialises on a lazy storage and a fork covers whole areas of which
     * it usually fills a few sections. Minestom applies the same test at
     * {@code InstanceContainer.java:434}.
     * </p>
     * <p>
     * The honest statement about this guard is that it cannot fire today, and it is kept anyway rather
     * than presented as a saving: {@link #applyForks(Chunk, GeneratorImpl.UnitImpl)} already drops a
     * fork section whose palette is empty, and it is the only writer of the map
     * {@link #applyPending(Chunk)} reads, so no empty section reaches this method by either route.
     * It stays because this method is the point where the two routes converge and the only one that
     * touches a section, and because a guard that lives where the materialisation happens does not
     * depend on a caller remembering to filter. The special blocks are part of the condition for the
     * same reason, even though a special block always writes its state into the palette as well.
     * </p>
     *
     * @param chunk    the chunk which receives the blocks
     * @param modifier the section of the fork to write
     */
    private void applyFork(Chunk chunk, GeneratorImpl.SectionModifierImpl modifier) {
        if (modifier.genSection().blocks().count() == 0 && modifier.genSection().specials().isEmpty()) {
            return;
        }
        final int sectionStartY = modifier.start().blockY();
        chunk.lockWriteLock();
        try {
            final Palette blocks = chunk.getSectionAt(sectionStartY).blockPalette();
            // A forked section marks an untouched position with a zero, so every block it does carry
            // was stored with its state raised by one and has to be lowered again here.
            modifier.genSection().blocks().getAllPresent((x, y, z, value) -> blocks.set(x, y, z, value - 1));
            writeSpecialBlocks(chunk, modifier.genSection().specials(), sectionStartY);
            chunk.invalidate();
        } finally {
            chunk.unlockWriteLock();
        }
    }
}
