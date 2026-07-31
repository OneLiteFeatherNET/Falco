package net.onelitefeather.falco.benchmark.support;

import net.onelitefeather.falco.light.BlockFace;
import net.onelitefeather.falco.light.BlockLightSource;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The {@link FakeBlockLightSource} class answers the light properties of a block without touching a
 * registry.
 * <p>
 * The real implementation resolves a block through the Minestom registry, which needs a started
 * server and would put the cost of that registry into every light measurement. The benchmarks are
 * supposed to measure the propagation and the caching of this library, so the source is replaced by
 * a table with four known states.
 * </p>
 * <p>
 * The cost of a single resolution is configurable. {@link net.onelitefeather.falco.light.SectionOpacity}
 * caches every distinct state once, and how much that cache is worth depends on how expensive a
 * resolution is.
 * A source which answers in a nanosecond would make the cache look like pure overhead, which is not
 * what a registry lookup behaves like.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
public final class FakeBlockLightSource implements BlockLightSource {

    /**
     * The state of a block which neither emits nor blocks light.
     */
    public static final int AIR = 0;

    /**
     * The state of a block which blocks every face and emits nothing.
     */
    public static final int SOLID = 1;

    /**
     * The state of a block which blocks every face and emits the highest level.
     */
    public static final int GLOWSTONE = 2;

    /**
     * The state of a block which blocks only its bottom face, the way a slab does.
     */
    public static final int SLAB = 3;

    /**
     * The first state id of the filler states which only exist to grow a palette.
     * Every filler state behaves like {@link #AIR}.
     */
    public static final int FILLER_BASE = 1024;

    private final int resolveCost;

    /**
     * Creates a source which resolves a state at the given cost.
     *
     * @param resolveCost the amount of work tokens a single resolution burns
     * @throws IllegalArgumentException if the cost is negative
     */
    public FakeBlockLightSource(int resolveCost) {
        if (resolveCost < 0) {
            throw new IllegalArgumentException("The resolve cost cannot be negative but was " + resolveCost);
        }
        this.resolveCost = resolveCost;
    }

    /**
     * Creates a source which resolves a state without any simulated cost.
     */
    public FakeBlockLightSource() {
        this(0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int emission(int stateId) {
        burn();
        return stateId == GLOWSTONE ? 15 : 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean blocksFace(int stateId, BlockFace face) {
        burn();
        return switch (stateId) {
            case SOLID, GLOWSTONE -> true;
            case SLAB -> face == BlockFace.BOTTOM;
            default -> false;
        };
    }

    /**
     * Burns the configured amount of work so a resolution costs what a registry lookup costs.
     */
    private void burn() {
        if (this.resolveCost > 0) {
            Blackhole.consumeCPU(this.resolveCost);
        }
    }
}
