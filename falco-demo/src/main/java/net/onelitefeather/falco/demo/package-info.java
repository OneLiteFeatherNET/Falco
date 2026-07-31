/**
 * A runnable comparison of the two Anvil chunk loaders on a world the user supplies.
 * <p>
 * The entry point is {@link net.onelitefeather.falco.demo.ChunkLoadDemo}. It is started by the two
 * gradle tasks {@code runFalcoLoader} and {@code runMinestomLoader}, which differ in nothing but the
 * loader they hand to the identical measurement, so the two outputs can be put next to each other.
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
