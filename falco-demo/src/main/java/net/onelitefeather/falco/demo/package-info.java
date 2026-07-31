/**
 * A runnable comparison of the Falco and the Minestom chunk stack on a world the user supplies, in
 * two forms: a headless measurement and a server to walk into.
 * <p>
 * The entry point of the measurement is {@link net.onelitefeather.falco.demo.ChunkLoadDemo}. It is
 * started by the two gradle tasks {@code runFalcoLoader} and {@code runMinestomLoader}, which differ
 * in nothing but the loader they hand to the identical measurement, so the two outputs can be put
 * next to each other.
 * </p>
 * <p>
 * The entry point of the server is {@link net.onelitefeather.falco.demo.DemoServer}, started by
 * {@code runFalcoServer} and {@code runMinestomServer}. It exists because the measurement answers a
 * question nobody plays: a reproducible figure over a fixed list of chunks says nothing about
 * whether a world streams in smoothly while flying, whether the light is where it belongs, and
 * whether the server keeps its tick while doing it. {@link net.onelitefeather.falco.demo.ServerStack}
 * is the single place the two servers differ, {@link net.onelitefeather.falco.demo.TimingChunkLoader}
 * measures the one call they differ in, {@link net.onelitefeather.falco.demo.LiveMetrics} and
 * {@link net.onelitefeather.falco.demo.SampleWindow} keep the outliers a mean would remove, and
 * {@link net.onelitefeather.falco.demo.LiveStatusLine} puts them where somebody flying can see them.
 * </p>
 * <p>
 * The two halves are kept side by side on purpose. The measurement is reproducible and says nothing
 * about how a world feels; the server is an impression and cannot be quoted. Neither replaces the
 * other.
 * </p>
 * <p>
 * The types around it exist because the interesting part of a measurement is everything that is not
 * the stopwatch. {@link net.onelitefeather.falco.demo.WorldLocator} decides which directory is the
 * world and which layout it uses, {@link net.onelitefeather.falco.demo.ChunkInventory} finds the
 * chunks that really exist in it, {@link net.onelitefeather.falco.demo.LoadMeasurement} separates
 * the warm-up from the measurement, {@link net.onelitefeather.falco.demo.Statistics} turns the
 * samples into a mean with a spread, and {@link net.onelitefeather.falco.demo.DemoReport} states
 * the conditions the numbers were taken under. Each of those is a plain function over plain data
 * and is covered by a test; the server start is not.
 * </p>
 * <p>
 * Nothing here is published. What it produces is a rough figure from one machine and is not a
 * substitute for the JMH benchmarks in {@code falco-benchmarks}.
 * </p>
 *
 * @since 0.1.0
 */
@NotNullByDefault
package net.onelitefeather.falco.demo;

import org.jetbrains.annotations.NotNullByDefault;
