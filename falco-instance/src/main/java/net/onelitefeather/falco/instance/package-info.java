/**
 * An {@code Instance} implementation for Minestom which cleans up after itself.
 * <p>
 * The entry point is {@link net.onelitefeather.falco.instance.FalcoInstance}, a world that extends
 * {@code Instance} directly instead of deriving from {@code InstanceContainer}. Four places in
 * Minestom branch on {@code instanceof InstanceContainer} and quietly take a different path for
 * anything else; the one that matters is
 * {@code InstanceManager#unregisterInstance}, which leaves every chunk of a foreign instance loaded.
 * {@link net.onelitefeather.falco.instance.FalcoInstance#unregister(net.minestom.server.instance.InstanceManager)}
 * is the answer to that and the reason this package exists.
 * </p>
 * <p>
 * {@link net.onelitefeather.falco.instance.FalcoChunk} comes along because it has to. The chunk
 * lifecycle hooks of Minestom are {@code protected}, so no instance outside
 * {@code net.minestom.server.instance} can tell a chunk that it was loaded or unloaded. A subclass
 * re-exposes them; reflection would hide the coupling rather than solve it.
 * </p>
 * <p>
 * {@link net.onelitefeather.falco.instance.FalcoInstanceException} marks the two ways a chunk can
 * fail to arrive: it was not a {@code FalcoChunk}, or its load was thrown away because the instance
 * was unregistered while it ran.
 * </p>
 * <p>
 * {@link net.onelitefeather.falco.instance.FalcoSharedInstance} is the one type here which does not
 * avoid {@code InstanceContainer} but builds on it. It extends {@code SharedInstance}, because the
 * fast path that spares a player a full chunk resend on an instance change is decided by
 * {@code SharedInstance#areLinked}, and that method compares containers rather than classes — a
 * subclass keeps it, a look-alike does not. What it repairs is the three setters and the save which
 * Minestom's shared instance writes through to the container it borrows from. What it does not
 * repair is the write path: the block owner is an {@code InstanceContainer} and its instance monitor
 * comes with it.
 * </p>
 * <p>
 * This package is about clarity, not throughput. The parallelism of chunk and entity ticking lives
 * in the global {@code ThreadDispatcher} of the server process, so no instance implementation can
 * change it. What does change is that a block write is guarded by the lock of the chunk it touches
 * rather than by a monitor over the whole world, and that publishing a chunk and unloading one are
 * a single step of the position they share, so neither of the two can leave half of the other
 * behind.
 * </p>
 * <p>
 * A world here comes from its chunk loader, from a
 * {@code net.minestom.server.instance.generator.Generator}, or stays empty. The generator writes
 * into copies of the palettes of the chunk which are moved over only once it returned, so a
 * generator that fails halfway changes nothing and the failure reaches whoever asked for the chunk.
 * </p>
 * <p>
 * Every public type here is experimental and may still change in a minor release.
 * </p>
 *
 * @since 0.1.0
 */
@NotNullByDefault
package net.onelitefeather.falco.instance;

import org.jetbrains.annotations.NotNullByDefault;
