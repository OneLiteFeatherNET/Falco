package net.minestom.server.instance;

import net.minestom.server.entity.Entity;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * The {@link ChunkViewerCache} class removes the viewer cache entry a chunk leaves behind, which
 * Minestom offers no way to do.
 * <p>
 * The constructor of {@code Chunk} asks the entity tracker of its instance for a {@code Viewable}
 * and receives it out of a {@code computeIfAbsent} keyed by the chunk position
 * ({@code Chunk.java:74-76}, {@code EntityTrackerImpl.java:207-210}). Nothing removes that entry
 * again — not unloading the chunk, not dropping the last reference to it, not unregistering the
 * instance — so a world which streams chunks accumulates one entry per position it has ever visited
 * and keeps them until the process ends.
 * </p>
 *
 * <h2>Why this class lives in a package of Minestom</h2>
 * <p>
 * {@code EntityTracker#viewable(List, int, int)} is the only public door to that map and it only
 * inserts. The map itself ({@code EntityTrackerImpl.TargetEntry#viewers}), its key type
 * ({@code EntityTrackerImpl.ChunkViewKey}) and {@code EntityTrackerImpl} are all package-private, so
 * a class declared in {@code net.minestom.server.instance} can reach them and nothing else can
 * without reflection — which NFR-001 forbids, and which would break on the first JDK that closes the
 * door.
 * </p>
 * <p>
 * The price is a split package: this jar carries a package that {@code minestom.jar} also carries. On
 * the classpath that is invisible and package-private access works, because both jars land in the
 * same runtime package of the same classloader; on the module path it is fatal, because Minestom is a
 * named module and two modules may not own one package. Falco declares no module and neither does
 * anything that consumes it, so nothing that works today changes. What this does close is the option
 * of Falco becoming a named module while this class stays where it is.
 * </p>
 *
 * <h2>What it does not fix</h2>
 * <p>
 * An {@code InstanceContainer} hands the tracker a fresh {@code unmodifiableList} of its shared
 * instances on every chunk construction, and {@code ChunkViewKey#equals} compares that list by
 * identity, so no key built here can ever match one of its entries. The unbounded growth of a
 * container is not reachable from the outside and is not addressed. What is addressed is the bounded
 * entry a {@code FalcoInstance} leaves per position, which is the one this repository is responsible
 * for.
 * </p>
 * <p>
 * A second live chunk at the same position — a copy, for instance — holds its own reference to the
 * view and keeps working after the entry is gone; the next chunk constructed there simply receives a
 * new one. The view is derived from the tracker on every read, so two of them for one position are
 * two caches of the same answer and never two different answers.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@ApiStatus.Internal
public final class ChunkViewerCache {

    /**
     * Blocks the creation of an instance because this class only reaches into a foreign map.
     */
    private ChunkViewerCache() {
    }

    /**
     * Removes the cached view of a chunk position.
     *
     * @param instance the instance the chunk belonged to
     * @param chunkX   the chunk X
     * @param chunkZ   the chunk Z
     * @return true if an entry was removed, false if there was none or the tracker is a foreign
     *         implementation
     */
    public static boolean release(Instance instance, int chunkX, int chunkZ) {
        if (!(instance.getEntityTracker() instanceof EntityTrackerImpl tracker)) return false;
        final EntityTrackerImpl.TargetEntry<Entity> entry =
                tracker.targetEntries[EntityTracker.Target.PLAYERS.ordinal()];

        // keySet().remove(...) rather than remove(...), because the value type of that map is a
        // private nested class and naming what remove would return is not allowed here.
        return entry.viewers.keySet().remove(new EntityTrackerImpl.ChunkViewKey(List.of(), chunkX, chunkZ));
    }

    /**
     * Reports how many views the tracker of an instance currently caches.
     *
     * @param instance the instance to read
     * @return the amount of cached views, or {@code -1} if the tracker is a foreign implementation
     */
    public static int size(Instance instance) {
        if (!(instance.getEntityTracker() instanceof EntityTrackerImpl tracker)) return -1;
        return tracker.targetEntries[EntityTracker.Target.PLAYERS.ordinal()].viewers.size();
    }
}
