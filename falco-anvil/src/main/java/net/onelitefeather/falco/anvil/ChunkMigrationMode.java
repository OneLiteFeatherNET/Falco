package net.onelitefeather.falco.anvil;

import org.jetbrains.annotations.ApiStatus;

/**
 * How far a loader carries a chunk that was written by an older version of the game.
 * <p>
 * The three modes differ in one question only — what happens to the migrated chunk after it has
 * been decoded — and that question is a trade between time, disk and safety rather than a matter of
 * correctness. All three read the same chunks and hand the same blocks to the server; what changes
 * is how often that work is repeated and whether the world on disk is touched.
 * </p>
 * <p>
 * <b>Why this is off unless asked for.</b> Migration is not free and it is not reversible in the
 * {@link #ON_DISK} case, so neither cost may be taken on behalf of a caller who never asked. A
 * loader in {@link #OFF} behaves exactly as it did before this option existed.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 2.2.0
 */
@ApiStatus.Experimental
public enum ChunkMigrationMode {

    /**
     * No migration at all: a chunk is decoded exactly as it is stored.
     * <p>
     * This is the default, and it is the mode in which a world older than the running server loses
     * whatever it holds that the server no longer knows by name. A block whose name was changed
     * since the chunk was written is not recognised, and the configured {@link UnknownEntryPolicy}
     * decides what stands in its place — air, with the shipped default. Nothing reports how much
     * of the world that affected beyond one log line per distinct name.
     * </p>
     */
    OFF,

    /**
     * Every chunk is migrated as it is read, and the world on disk is left untouched.
     * <p>
     * <b>This costs time on every single load.</b> A chunk that is loaded, unloaded and loaded again
     * is migrated twice, because nothing of the first migration was kept. On a world whose chunks
     * are mostly older than the server, that work lands on the chunk loading path a player waits
     * for, and it does not diminish with uptime the way a cache would.
     * </p>
     * <p>
     * What it buys is that the world on disk is exactly what it was before the server started. A
     * world in this mode can still be opened by the older server it came from, and a mistake in a
     * migration rule cannot damage anything permanently, because nothing is written back.
     * </p>
     */
    IN_MEMORY,

    /**
     * Every chunk is migrated as it is read and the migrated form is written back to the region
     * file, so each chunk pays the cost once rather than on every load.
     * <p>
     * <b>This rewrites the world.</b> After a chunk has been migrated in this mode, the stored chunk
     * is the migrated one and the original is gone from the region file — which is why a loader in
     * this mode refuses to start without a backup directory it could restore from. See
     * {@code FalcoAnvilLoader.Builder#migration} for how that backup is taken.
     * </p>
     * <p>
     * The rewrite also means the world stops being readable by the older server it came from, since
     * its chunks now carry the running server's data version. That is the point of the mode and not
     * a side effect, but it is a one-way step and the reason the backup is not optional.
     * </p>
     */
    ON_DISK
}
