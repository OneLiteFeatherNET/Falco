package net.onelitefeather.falco.light;

import org.jetbrains.annotations.Contract;

import java.util.Arrays;
import java.util.List;

/**
 * The {@link BorderExchange} class hands the light of every chunk of an area across its borders
 * until nothing changes any more, injecting only the borders which can still raise anything.
 * <p>
 * Every injection only ever raises a level, so the light of a chunk only grows. Once a chunk took in
 * the border of a neighbour, the same border cannot raise anything in it again — until that
 * neighbour itself changes. The exchange therefore counts how often every chunk changed and
 * remembers, per chunk and face, which of those counts it last took the border at. A border whose
 * neighbour did not change since is skipped. A skipped injection is exactly one which would have
 * raised nothing, so the exchange settles on the same light, in the same amount of rounds, as one
 * which injects every border in every round.
 * </p>
 * <p>
 * Instances are not thread safe and serve one exchange.
 * </p>
 *
 * @author theEvilReaper
 * @version 1.0.0
 * @since 3.0.2
 */
final class BorderExchange {

    /**
     * The faces every chunk is exchanged through, in the order the neighbours are given in.
     */
    static final BlockFace[] FACES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST};

    private final List<ChunkLightState> states;
    private final List<List<SectionOpacity>> opacity;
    private final int[] neighbours;
    private final int[] versions;
    private final int[] taken;
    private int rounds;
    private long injections;

    /**
     * Creates a new exchange over the given chunks.
     *
     * @param states     the light of every chunk, raised in place by the exchange
     * @param opacity    the light properties of every section of every chunk, indexed like the states
     * @param neighbours the index of the neighbour per chunk and face, in the order of {@link #FACES},
     *                   or a negative value where a chunk has no neighbour
     * @throws IllegalArgumentException if the given lists and the neighbours do not cover the same chunks
     */
    BorderExchange(List<ChunkLightState> states, List<List<SectionOpacity>> opacity, int[] neighbours) {
        if (opacity.size() != states.size() || neighbours.length != states.size() * FACES.length) {
            throw new IllegalArgumentException(
                    "The exchange needs the opacity and the " + FACES.length + " neighbours of each of the "
                            + states.size() + " chunks"
            );
        }
        this.states = states;
        this.opacity = opacity;
        this.neighbours = neighbours;
        this.versions = new int[states.size()];
        this.taken = new int[neighbours.length];
        // No version is negative, so the first round takes every border.
        Arrays.fill(this.taken, -1);
    }

    /**
     * Exchanges the borders until no chunk changes any more or the given amount of rounds ran out.
     *
     * @param maxRounds the amount of rounds after which the exchange gives up
     * @return true if the light settled, false if it still changed in the last round
     */
    boolean settle(int maxRounds) {
        for (int round = 0; round < maxRounds; round++) {
            this.rounds++;

            if (!exchange()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hands the border of every changed neighbour to every chunk once.
     *
     * @return true if at least one chunk raised a level, otherwise false
     */
    private boolean exchange() {
        boolean changed = false;

        for (int chunk = 0; chunk < this.states.size(); chunk++) {
            for (int face = 0; face < FACES.length; face++) {
                int slot = chunk * FACES.length + face;
                int neighbour = this.neighbours[slot];

                if (neighbour < 0) {
                    continue;
                }
                int version = this.versions[neighbour];

                if (this.taken[slot] == version) {
                    continue;
                }
                this.taken[slot] = version;
                this.injections++;

                if (this.states.get(chunk).injectBorder(this.opacity.get(chunk), FACES[face], this.states.get(neighbour))) {
                    this.versions[chunk]++;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Returns the amount of rounds this exchange ran.
     *
     * @return the amount of rounds, including the last one which changed nothing
     */
    @Contract(pure = true)
    int rounds() {
        return this.rounds;
    }

    /**
     * Returns the amount of borders this exchange actually injected.
     *
     * @return the amount of injections, without the skipped ones
     */
    @Contract(pure = true)
    long injections() {
        return this.injections;
    }
}
