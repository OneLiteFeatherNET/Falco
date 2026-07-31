/**
 * A chunk loader for the Anvil region file format.
 * <p>
 * The entry point is {@link net.onelitefeather.falco.anvil.FalcoAnvilLoader}, which implements
 * {@code net.minestom.server.instance.ChunkLoader} and is a drop-in replacement for the loader
 * Minestom ships with. Everything else in this package is a layer below it:
 * {@link net.onelitefeather.falco.anvil.RegionFile} owns the bytes of one {@code r.<x>.<z>.mca}
 * file, {@link net.onelitefeather.falco.anvil.SectionCodec} turns a section between NBT and the
 * runtime representation, and {@link net.onelitefeather.falco.anvil.PaletteData} together with
 * {@link net.onelitefeather.falco.anvil.BitPacker} does the packing the format prescribes.
 * </p>
 * <p>
 * The split exists so that no processor bound work happens while a lock is held. Reading,
 * decompression and NBT parsing are separate stages, and only the sector allocation and the header
 * update are guarded. That is the one property this loader gains over the one of the server, whose
 * region file serialises all three through a single lock.
 * </p>
 * <p>
 * Every public type here is experimental and may still change in a minor release.
 * </p>
 *
 * @since 0.1.0
 */
@NotNullByDefault
package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.NotNullByDefault;
