package net.onelitefeather.falco.migration;

import org.jetbrains.annotations.ApiStatus;

/**
 * One versioned transformation from an older {@link BlockState} to its later form.
 * <p>
 * A rule is keyed on the whole state, never on a single property: {@link #matches(BlockState)} and
 * {@link #apply(BlockState)} both see the complete name-plus-properties pair, because some changes
 * cannot be expressed any narrower. A cauldron's new block name is decided by its {@code level}
 * property, and a wall's new value for one direction would need the direction alone in a
 * property-by-property model — the whole state is the smallest unit that stays correct.
 * </p>
 * <p>
 * A rule also carries the {@link #since()} version it belongs to, because the same block name can
 * mean different things on either side of a version boundary. {@code stone_slab} is the case that
 * forces this: unversioned, a rename rule for it would corrupt a world that already has the
 * newer meaning.
 * </p>
 *
 * @since 2.1.0
 */
@ApiStatus.Experimental
public interface BlockStateRule {

    /**
     * The {@code DataVersion} in which the change this rule encodes happened.
     * <p>
     * This is the version a fix landed in, not the version the rule targets: a rule applies to a
     * chunk's source version exactly when the source is older than this number, i.e. when
     * {@code since() > sourceVersion}. A rule with {@code since() == 1802} therefore applies to a
     * 1.13 world ({@code 1519 < 1802}) and leaves a 1.16 world ({@code 2566 > 1802}) alone, because
     * by 1.16 the change already happened and the state already carries its later meaning.
     * </p>
     *
     * @return the {@code DataVersion} the change happened in
     */
    int since();

    /**
     * Whether this rule has anything to say about {@code state}.
     * <p>
     * Called after every rule that ran before this one in version order, so {@code state} may
     * already differ from the state a chunk originally stored.
     * </p>
     *
     * @param state the state to test
     * @return {@code true} if {@link #apply(BlockState)} should run on {@code state}
     */
    boolean matches(BlockState state);

    /**
     * Transforms {@code state} into its later form.
     * <p>
     * Only called for a state {@link #matches(BlockState)} accepted. May change the block name, the
     * properties, or both — a rule is not required to keep the name stable.
     * </p>
     *
     * @param state a state for which {@link #matches(BlockState)} returned {@code true}
     * @return the transformed state
     */
    BlockState apply(BlockState state);
}
