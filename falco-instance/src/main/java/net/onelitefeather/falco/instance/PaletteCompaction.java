package net.onelitefeather.falco.instance;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minestom.server.instance.palette.Palette;
import org.jetbrains.annotations.ApiStatus;

/**
 * The {@link PaletteCompaction} class decides whether {@code Palette#optimize(Optimization.SIZE)} can
 * still narrow a palette before that call is made.
 * <p>
 * {@code optimize} is not a cheap call and it is not a call that reports whether it achieved anything.
 * {@code PaletteImpl#optimize} first walks all entries of the palette through {@code getAll} and
 * collects the distinct values into a hash set, and only then does
 * {@code PaletteImpl#downsizeWithPalette} decide, on its opening
 * {@code if (newBpe >= bpe || newBpe > maxBitsPerEntry) return;}, that the width the content needs is
 * not one it can store. The walk is the expensive half and it is paid either way.
 * {@code GeneratorCommitBenchmark} measured it on a chunk of twenty-four sections: {@code 576,7 µs}
 * against {@code 22,0 µs} for the bare commit at sixty-four distinct states, where the palettes went
 * from fifteen bits to six, and {@code 529,8 µs} against {@code 26,3 µs} at one thousand and
 * twenty-four distinct states, where they stayed at fifteen. The second figure is the whole reason
 * this class exists: half a millisecond of a chunk generation for zero bytes.
 * </p>
 *
 * <h2>What can be decided without doing the work</h2>
 * <p>
 * Nothing on the {@link Palette} interface reports how many distinct values a palette holds.
 * {@code count()} answers how many entries are not air, which is a different question, and the palette
 * list that would answer it exists only in the indirect mode and is not exposed. The number therefore
 * has to be found by looking at entries — but not at all of them, and that is the difference between
 * this class and the call it guards. A palette narrows only if its distinct count is small, so a
 * <em>bound</em> on the count is enough to rule the narrowing out, and a bound is reached as soon as
 * enough distinct values have been seen. The probe below reads a fixed sample of positions and stops
 * at the first value that puts the count past what {@code downsizeWithPalette} could store.
 * </p>
 * <p>
 * That makes the answer one-sided on purpose. A {@code false} is a proof — the palette provably holds
 * more distinct values than the mode below it can index, so {@code optimize} would walk everything and
 * return unchanged. A {@code true} is not a promise that anything will be gained; it only says that
 * the sample gave no reason to skip. Being wrong in that direction costs the call that would have
 * happened anyway, while being wrong in the other direction would silently leave chunks at the direct
 * width, which is what this whole stage is trying to avoid. The sample is bounded rather than complete
 * so that the guard cannot become as expensive as the call it is guarding.
 * </p>
 *
 * <h2>The threshold</h2>
 * <p>
 * {@code optimize} does something in exactly two cases. One distinct value goes to {@code fill} and
 * collapses the palette into the single value mode, whatever its width was. Anything else goes to
 * {@code downsizeWithPalette}, which computes
 * {@code newBpe = max(bitsToRepresent(distinct - 1), minBitsPerEntry)} and returns unchanged unless
 * that width is both smaller than the current one and no larger than {@code maxBitsPerEntry}. Writing
 * {@code reachable = min(bitsPerEntry, maxBitsPerEntry + 1)} for the width a narrowing has to beat,
 * the second case therefore needs {@code minBitsPerEntry < reachable} and
 * {@code distinct <= 2^(reachable - 1)}. For a block palette at the direct width that is
 * {@code 2^8 = 256} distinct states; for one already at the minimum of four bits it is {@code 1}, so
 * two distinct values are enough to prove that only the {@code fill} case could still apply and it
 * cannot.
 * </p>
 *
 * <h2>What the guard is worth, and what it costs</h2>
 * <p>
 * Measured by {@code GeneratorCommitBenchmark} over a chunk of twenty-four sections, all of them at
 * the width a generator leaves, in microseconds per commit of the whole chunk:
 * </p>
 * <pre>{@code
 * distinct states per section            1        64      1024
 * commit only                        0,044    22,045    26,254
 * commit + optimize                  0,049   576,655   529,768
 * commit + this guard                0,049   713,962   185,081
 * optimize an already packed chunk   0,047   270,642   535,028
 * this guard on the same chunk       0,047    31,799   176,137
 * }</pre>
 * <p>
 * Three readings, and the third one is the price rather than the gain. A chunk whose palettes are
 * already as narrow as their content allows — what every commit onto content that a loader or an
 * earlier generation produced is handed — costs {@code 270,6 µs} to find that out and {@code 31,8 µs}
 * to be told in advance, a factor of {@code 8,5}. A chunk past the indirect ceiling costs
 * {@code 529,8 µs} against {@code 185,1 µs}, a factor of {@code 2,9}. And a chunk the optimisation can
 * genuinely narrow costs {@code 137 µs} more than it would without the guard, because the probe walks
 * its whole sample before it lets the call through: {@code 714,0 µs} against {@code 576,7 µs}, which is
 * {@code 24 %} on the one case where the work was worth doing. The sections of a real chunk that carry
 * a single state pay none of this, since the guard returns on the same {@code bitsPerEntry == 0} the
 * optimisation returns on.
 * </p>
 * <p>
 * Conditions: Ryzen 7 5800X, sixteen threads, JDK 25.0.3, JMH with three forks, five warmup and five
 * measurement iterations of one second each, fifteen samples per point, {@code -prof gc}. The machine
 * was <strong>not</strong> idle — load average between {@code 4,4} and {@code 6,8} — so the absolute
 * values carry more noise than the ratios between arms of the same run do, and the {@code ± 42 µs} on
 * the sixty-four state arm is real.
 * </p>
 * <p>
 * This type is internal. It states a property of {@code PaletteImpl} that the {@link Palette}
 * interface does not promise, so it belongs to the commit path of this module and not to its API, and
 * it is worth nothing to anyone who is not about to call {@code optimize}.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Internal
@ApiStatus.Experimental
public final class PaletteCompaction {

    /**
     * How many positions the probe reads before it gives up and lets {@code optimize} run.
     * <p>
     * A block palette holds {@code 4096} entries, so this is one eighth of one. It has to be larger
     * than the largest threshold the rule below can produce — {@code 256} for a block palette — because
     * a probe that cannot reach the threshold can never skip anything, and it has to stay far enough
     * below the full count that the guard remains cheap next to the walk it replaces.
     * </p>
     */
    private static final int PROBE_SAMPLES = 512;

    /**
     * The stride the probe walks the entry indices with.
     * <p>
     * Any odd number is coprime with the entry count of a palette, which is a power of two, so a stride
     * of nine visits {@link #PROBE_SAMPLES} distinct positions and spreads them over every x, y and z
     * of the section rather than over one plane of it. A stride of eight — the obvious one for a
     * sample of one eighth — would read two of the sixteen x values and nothing else.
     * </p>
     */
    private static final int PROBE_STRIDE = 9;

    private PaletteCompaction() {
    }

    /**
     * Narrows a block palette to the width its content needs, unless that is provably impossible.
     *
     * @param palette the block palette to pack
     */
    public static void packBlocks(Palette palette) {
        pack(palette, Palette.BLOCK_PALETTE_MIN_BITS, Palette.BLOCK_PALETTE_MAX_BITS);
    }

    /**
     * Narrows a biome palette to the width its content needs, unless that is provably impossible.
     * <p>
     * A biome palette holds sixty-four entries, so the probe reads all of them and the answer is exact
     * rather than one-sided. The guard is kept for it anyway, because a palette that cannot narrow is
     * the common case for biomes too — a generator usually writes one biome per section — and because
     * a rule that holds for one palette and not for the other is a rule nobody can check at a glance.
     * </p>
     *
     * @param palette the biome palette to pack
     */
    public static void packBiomes(Palette palette) {
        pack(palette, Palette.BIOME_PALETTE_MIN_BITS, Palette.BIOME_PALETTE_MAX_BITS);
    }

    /**
     * Runs {@code Palette#optimize(Optimization.SIZE)} unless {@link #canNarrow(Palette, int, int)}
     * has ruled it out.
     *
     * @param palette         the palette to pack
     * @param minBitsPerEntry the narrowest indirect width this palette can take
     * @param maxBitsPerEntry the widest indirect width this palette can take
     */
    static void pack(Palette palette, int minBitsPerEntry, int maxBitsPerEntry) {
        if (canNarrow(palette, minBitsPerEntry, maxBitsPerEntry)) {
            palette.optimize(Palette.Optimization.SIZE);
        }
    }

    /**
     * Reports whether {@code Palette#optimize(Optimization.SIZE)} could still change this palette.
     * <p>
     * A {@code false} means it provably could not: either the palette is already in the single value
     * mode, where {@code optimize} returns on its first line, or the probe has seen more distinct
     * values than the widest mode below the current one can index. A {@code true} means the sample
     * found no such proof, which is not the same as a saving.
     * </p>
     *
     * @param palette         the palette to look at, which is read and never written
     * @param minBitsPerEntry the narrowest indirect width this palette can take
     * @param maxBitsPerEntry the widest indirect width this palette can take
     * @return whether the optimisation is still worth attempting
     */
    static boolean canNarrow(Palette palette, int minBitsPerEntry, int maxBitsPerEntry) {
        final int bitsPerEntry = palette.bitsPerEntry();

        if (bitsPerEntry == 0) return false;

        final int dimension = palette.dimension();

        if (Integer.bitCount(dimension) != 1) return true;

        final int reachable = Math.min(bitsPerEntry, maxBitsPerEntry + 1);
        final int distinctLimit = minBitsPerEntry < reachable ? 1 << (reachable - 1) : 1;
        final int entries = dimension * dimension * dimension;
        final int samples = Math.min(entries, PROBE_SAMPLES);
        final int shift = Integer.numberOfTrailingZeros(dimension);
        final int mask = dimension - 1;
        final IntSet distinct = new IntOpenHashSet();

        for (int sample = 0; sample < samples; sample++) {
            final int index = (sample * PROBE_STRIDE) & (entries - 1);
            final int x = index & mask;
            final int z = (index >> shift) & mask;
            final int y = index >> (shift + shift);
            final int value = palette.get(x, y, z);

            if (distinct.add(value) && distinct.size() > distinctLimit) return false;
        }
        return true;
    }
}
