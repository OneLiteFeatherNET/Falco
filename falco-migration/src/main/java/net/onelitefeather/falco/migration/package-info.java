/**
 * An engine that upgrades stored Anvil chunk data — blocks, biomes, block entities — from Minecraft
 * 1.13 upwards.
 * <p>
 * This package works on NBT read from disk and nothing else. It does not import Minestom, does not
 * start a server and does not need one running: a world has to be convertible before anything boots,
 * because migration is a step a server takes before it ever loads the chunks it is about to serve.
 * {@code falco-archunit} enforces that boundary; see {@code MigrationBoundaryTest} there for the rule
 * and the reason behind it.
 * </p>
 * <p>
 * The floor is DataVersion 1519, the release version of Minecraft 1.13. That release rewrote the
 * chunk format from numeric block IDs plus damage values to the palette-based block state format
 * still in use today, which is the format this engine's types speak. Anything older would need a
 * translation this package does not attempt.
 * </p>
 * <p>
 * Every public type here is experimental and may still change in a minor release.
 * </p>
 *
 * @since 2.1.0
 */
@NotNullByDefault
package net.onelitefeather.falco.migration;

import org.jetbrains.annotations.NotNullByDefault;
