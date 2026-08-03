package net.onelitefeather.falco.instance;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.generator.Generator;
import net.minestom.server.utils.chunk.ChunkSupplier;
import net.minestom.testing.Env;
import net.minestom.testing.extension.MicrotusExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the three pieces of configuration which Minestom's shared instance writes through to the
 * container it borrows from.
 * <p>
 * The aliasing cases here use <em>two</em> shared instances over one container and inspect the one
 * which was not touched. A case that only looked at the instance it had just configured would be
 * green with the defect in place, because the defect is not that the value is lost — it is that the
 * value lands somewhere else as well.
 * </p>
 * <p>
 * The three snapshot cases run the same relation backwards, with one view and the container: they
 * change the container <em>after</em> the view exists and assert that the view does not follow. That
 * direction is the one the class documentation asserts three times over — "never a read of the
 * container as it stands now" — and it is not implied by the aliasing cases, which all configure
 * before or without a container change. The auto-load one goes as far as a chunk, because there the
 * divergence is not an answer but a chunk pulled into a container which was refusing to load one.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.4.0
 * @since 0.4.0
 */
@ExtendWith(MicrotusExtension.class)
@DisplayName("The configuration of a Falco shared instance")
class FalcoSharedInstanceStateTest {

    private static FalcoSharedInstance registered(Env env, InstanceContainer container) {
        final FalcoSharedInstance shared = new FalcoSharedInstance(UUID.randomUUID(), container);
        env.process().instance().registerSharedInstance(shared);
        return shared;
    }

    @Test
    @DisplayName("starts with the generator its container had")
    void testTheGeneratorIsSeededFromTheContainer(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final Generator generator = unit -> unit.modifier().fill(Block.STONE);
        container.setGenerator(generator);

        final FalcoSharedInstance shared = registered(env, container);

        assertSame(generator, shared.generator());
    }

    @Test
    @DisplayName("keeps a generator to itself: neither the sibling nor the container sees it")
    void testTheGeneratorDoesNotAlias(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance first = registered(env, container);
        final FalcoSharedInstance second = registered(env, container);
        final Generator generator = unit -> unit.modifier().fill(Block.STONE);

        first.setGenerator(generator);

        assertSame(generator, first.generator());
        assertNull(second.generator(), "a sibling view must not be reconfigured by this call");
        assertNull(container.generator(), "the container must not be reconfigured by this call");
    }

    @Test
    @DisplayName("does not lose the container's generator when it clears its own")
    void testClearingTheGeneratorDoesNotClearTheContainer(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final Generator generator = unit -> unit.modifier().fill(Block.STONE);
        container.setGenerator(generator);
        final FalcoSharedInstance shared = registered(env, container);

        shared.setGenerator(null);

        assertNull(shared.generator());
        assertSame(generator, container.generator(),
                "clearing a view must not empty the world it looks at");
    }

    @Test
    @DisplayName("does not follow the container's generator once it has been constructed")
    void testTheGeneratorIsTheSnapshotOfConstruction(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final Generator seeded = unit -> unit.modifier().fill(Block.STONE);
        container.setGenerator(seeded);
        final FalcoSharedInstance shared = registered(env, container);

        final Generator later = unit -> unit.modifier().fill(Block.DIRT);
        container.setGenerator(later);

        assertSame(seeded, shared.generator(),
                "the view answers what it was seeded with, never the container as it stands now");
        assertSame(later, container.generator(), "while the container keeps the value it was given");
    }

    @Test
    @DisplayName("starts with the chunk supplier its container had")
    void testTheChunkSupplierIsSeededFromTheContainer(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();

        final FalcoSharedInstance shared = registered(env, container);

        assertSame(container.getChunkSupplier(), shared.getChunkSupplier());
    }

    @Test
    @DisplayName("keeps a chunk supplier to itself: neither the sibling nor the container sees it")
    void testTheChunkSupplierDoesNotAlias(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final ChunkSupplier stock = container.getChunkSupplier();
        final FalcoSharedInstance first = registered(env, container);
        final FalcoSharedInstance second = registered(env, container);
        final ChunkSupplier supplier = FalcoChunk::new;

        first.setChunkSupplier(supplier);

        assertSame(supplier, first.getChunkSupplier());
        assertSame(stock, second.getChunkSupplier(), "a sibling view must not be reconfigured by this call");
        assertSame(stock, container.getChunkSupplier(), "the container must not be reconfigured by this call");
        assertNotSame(supplier, container.getChunkSupplier());
    }

    @Test
    @DisplayName("refuses a null chunk supplier instead of storing it")
    void testTheChunkSupplierIsNotNullable(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance shared = registered(env, container);

        assertThrows(NullPointerException.class, () -> shared.setChunkSupplier(null));
        assertSame(container.getChunkSupplier(), shared.getChunkSupplier());
    }

    @Test
    @DisplayName("does not follow the container's chunk supplier once it has been constructed")
    void testTheChunkSupplierIsTheSnapshotOfConstruction(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final ChunkSupplier stock = container.getChunkSupplier();
        final FalcoSharedInstance shared = registered(env, container);

        final ChunkSupplier later = FalcoChunk::new;
        container.setChunkSupplier(later);

        assertSame(stock, shared.getChunkSupplier(),
                "the view answers what it was seeded with, never the container as it stands now");
        assertSame(later, container.getChunkSupplier(), "while the container keeps the value it was given");
    }

    @Test
    @DisplayName("starts with the auto chunk load setting its container had")
    void testAutoChunkLoadIsSeededFromTheContainer(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        container.enableAutoChunkLoad(false);

        final FalcoSharedInstance shared = registered(env, container);

        assertFalse(shared.hasEnabledAutoChunkLoad());
    }

    @Test
    @DisplayName("keeps the auto chunk load flag to itself")
    void testAutoChunkLoadDoesNotAlias(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance first = registered(env, container);
        final FalcoSharedInstance second = registered(env, container);

        first.enableAutoChunkLoad(false);

        assertFalse(first.hasEnabledAutoChunkLoad());
        assertTrue(second.hasEnabledAutoChunkLoad(), "a sibling view must not be reconfigured by this call");
        assertTrue(container.hasEnabledAutoChunkLoad(), "the container must not be reconfigured by this call");
    }

    @Test
    @DisplayName("does not trigger a load of its own when the flag is off, and the container still can")
    void testAutoChunkLoadDecidesTheOptionalLoad(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance disabled = registered(env, container);
        final FalcoSharedInstance enabled = registered(env, container);
        disabled.enableAutoChunkLoad(false);

        assertNull(disabled.loadOptionalChunk(4, 4).join(),
                "a view with auto load off must not pull a chunk into the world");
        assertNull(container.getChunk(4, 4), "and it must not have done so as a side effect either");

        final Chunk loaded = enabled.loadOptionalChunk(4, 4).join();

        assertNotNull(loaded);
        assertSame(loaded, container.getChunk(4, 4));
    }

    @Test
    @DisplayName("hands back a chunk that is already there even with the flag off")
    void testAutoChunkLoadDoesNotHideLoadedChunks(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance shared = registered(env, container);
        shared.enableAutoChunkLoad(false);
        final Chunk loaded = container.loadChunk(4, 4).join();

        assertSame(loaded, shared.loadOptionalChunk(4, 4).join(),
                "the flag governs whether a load is started, not whether the world is visible");
    }

    @Test
    @DisplayName("loads on its own flag even where the container would have refused")
    void testAutoChunkLoadIsTheDecisionOfTheView(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        container.enableAutoChunkLoad(false);
        final FalcoSharedInstance shared = registered(env, container);

        shared.enableAutoChunkLoad(true);

        assertNotNull(shared.loadOptionalChunk(4, 4).join(),
                "the view owns this decision, so turning it back on at the view is enough");
        assertFalse(container.hasEnabledAutoChunkLoad(),
                "and the container is still refusing loads asked of it directly");
    }

    @Test
    @DisplayName("goes on loading after the container turned its own flag off, and pulls chunks into it")
    void testTheContainerCannotStopAViewItAlreadyHas(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance shared = registered(env, container);

        container.enableAutoChunkLoad(false);

        assertTrue(shared.hasEnabledAutoChunkLoad(),
                "the flag is the snapshot of construction, so the container's later call misses the view");
        assertNull(container.loadOptionalChunk(9, 9).join(),
                "and the container is genuinely refusing the loads asked of itself");

        final Chunk pulled = shared.loadOptionalChunk(4, 4).join();

        assertNotNull(pulled, "while the view carries on loading, with no act at the view at all");
        assertSame(pulled, container.getChunk(4, 4),
                "and the chunk lands in the container that had just refused to load one");
    }

    @Test
    @DisplayName("refuses an entity the spawn chunk it needs, while a sibling view grants it")
    void testAutoChunkLoadOffRefusesAnEntityItsSpawnChunk(Env env) {
        final InstanceContainer container = env.process().instance().createInstanceContainer();
        final FalcoSharedInstance disabled = registered(env, container);
        final FalcoSharedInstance enabled = registered(env, container);
        disabled.enableAutoChunkLoad(false);
        final List<Throwable> handled = new CopyOnWriteArrayList<>();
        env.process().exception().setExceptionHandler(handled::add);

        final Entity refused = new Entity(EntityType.ZOMBIE);
        refused.setInstance(disabled, new Pos(72, 40, 72)).join();

        assertFalse(disabled.getEntities().contains(refused),
                "a view which may not load the spawn chunk never registers the entity");
        assertEquals(1, handled.size(),
                "and the only trace is one throwable handed to the exception manager");

        final Entity spawned = new Entity(EntityType.ZOMBIE);
        spawned.setInstance(enabled, new Pos(72, 40, 72)).join();

        assertTrue(enabled.getEntities().contains(spawned),
                "while a sibling view over the same container takes the same entity in");
        assertEquals(1, handled.size(), "without a second failure");
    }
}
