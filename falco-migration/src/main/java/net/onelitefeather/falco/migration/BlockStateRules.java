package net.onelitefeather.falco.migration;

import org.jetbrains.annotations.ApiStatus;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The block-state facts measured for the span from Minecraft 1.13 (DataVersion 1519) to today, and
 * the rule that resolves them against a chunk's source version.
 * <p>
 * Every rule below carries a comment naming where it came from — a PaperMC/DataConverter fix
 * version, or a diff computed directly over the vendored registry lists — because none of them is
 * written from memory; see
 * {@code docs/superpowers/specs/2026-08-04-blockstate-property-research.md} for the full
 * measurement. One fact from that measurement, {@code redstone_wire}, is deliberately absent; see
 * the note above {@link #RULES} for why.
 * </p>
 *
 * @since 2.1.0
 */
@ApiStatus.Experimental
public final class BlockStateRules {

    private static final Set<String> WALL_BLOCKS =
            Set.of("minecraft:cobblestone_wall", "minecraft:mossy_cobblestone_wall");

    /**
     * The four wall directions this module rewrites. {@code up} is deliberately excluded: it exists
     * unchanged in both 1.13 and today, and the 20w06a render change concerns {@code up}, not these
     * four. Confirmed against DataConverter V2503 and Chunker's equivalent lookup, both of which
     * leave {@code up} untouched.
     */
    private static final List<String> WALL_SIDES = List.of("north", "south", "east", "west");

    /**
     * The versioned facts this module encodes, kept in ascending {@link BlockStateRule#since()}
     * order for readability — {@link #translate(BlockState, int)} sorts its own copy regardless, so
     * this ordering is not load-bearing.
     * <p>
     * <b>{@code redstone_wire} (V2531, 20w17a, Minecraft 1.16, 144 states) is not in this list.</b>
     * The research document establishes that a direction's new value depends on the other three
     * directions of the same state (DataConverter's {@code connectedX}/{@code connectedZ} in V2531),
     * that a per-property implementation is guaranteed wrong, and that 9 of the 81 direction
     * combinations change (times 16 {@code power} values = 144 states). It does not say which 9
     * combinations change or what they become — only the count. That is not enough to reproduce
     * V2531's logic exactly, and a guessed whole-state table for redstone wiring is exactly the
     * silent corruption this task was told to refuse: the chunk still loads, the wiring looks
     * subtly different, and nobody notices. A state this module does not recognize — including every
     * {@code redstone_wire} state — passes through {@link #translate(BlockState, int)} unchanged;
     * see {@code BlockStateRulesTest.testAStateNoRuleKnowsAboutPassesThroughUnchanged}.
     * </p>
     */
    private static final List<BlockStateRule> RULES = List.of(
            // sign -> oak_sign. Oak was the only wood type a sign could be in 1.13, so this is a
            // plain rename with no ambiguity. Source: blockstate-property-research.md,
            // "Widerspruch 2" (V1802, 18w43a, Minecraft 1.14).
            renameRule("minecraft:sign", "minecraft:oak_sign", 1802),

            // wall_sign -> oak_wall_sign, same reasoning as sign above.
            // Source: blockstate-property-research.md, "Widerspruch 2" (V1802, 18w43a, Minecraft 1.14).
            renameRule("minecraft:wall_sign", "minecraft:oak_wall_sign", 1802),

            // stone_slab -> smooth_stone_slab. The name is reused by a different block from 1.14 on
            // (1.13's stone_slab and 26.x's stone_slab both have 6 states, but they mean different
            // blocks), which is why this rule MUST be versioned: unversioned, it would rename a
            // 1.16+ world's already-correct stone_slab and corrupt it.
            // Source: blockstate-property-research.md, section "A) Die Zahl", two-case table
            // (V1802, 1.14; verified 1.13 stone_slab = 6 states, 26.3 stone_slab AND
            // smooth_stone_slab each = 6 states).
            renameRule("minecraft:stone_slab", "minecraft:smooth_stone_slab", 1802),

            // cobblestone_wall / mossy_cobblestone_wall: north/south/east/west go from a boolean
            // (false/true) to none/low/tall, as a pure lookup (true -> low, false -> none). `tall`
            // is not reachable from a 1.13 state and Mojang's own fix does not attempt it either.
            // Source: blockstate-property-research.md, "Widerspruch 3" (V2503, Minecraft 1.16,
            // DataConverter's BOOL_TO_WALL_HEIGHT-equivalent; confirmed no neighbor/chunk context is
            // needed, contrary to what the wiki route alone suggested).
            wallRule(2503),

            // cauldron[level]: level=0 -> cauldron (properties emptied, the property is dropped
            // entirely); level=1..3 -> water_cauldron[level=n]. The block name is decided by a
            // property value, which is why this rule changes the name rather than only a value —
            // and exactly why the case was invisible to a plain name diff.
            // Source: blockstate-property-research.md, section "A) Die Zahl", row
            // "(c) Property weggefallen" (V2679, Minecraft 1.17).
            cauldronRule(2679),

            // grass_path -> dirt_path, Minecraft 1.17.
            // Source for the RENAME ITSELF: falco-migration-design.md, "What the registry lists do
            // settle" table (names the Minecraft version, "1.17", not a DataVersion integer).
            // Source for the NUMBER 2681: not in this repository at all. minecraft.wiki's own
            // changelog for snapshot 20w45a states the rename directly ("'Grass Path' was renamed to
            // 'Dirt Path'"), and that snapshot's infobox lists DataVersion 2681 — checked via two
            // independent fetches, 2026-08-04. This replaces an earlier, wrong value of 2724 (1.17's
            // *final release* DataVersion): the rule must carry the version the change happened in,
            // per since()'s contract, and the change happened 43 versions earlier, in the snapshot,
            // not at release. Using 2724 caused no test failure here only because grass_path is never
            // reused for anything else afterward, unlike stone_slab — but it was still the wrong
            // number for what since() claims to mean.
            renameRule("minecraft:grass_path", "minecraft:dirt_path", 2681),

            // grass -> short_grass, Minecraft 1.20.3.
            // Source for the RENAME ITSELF: falco-migration-design.md, "What the registry lists do
            // settle" table (names the Minecraft version, "1.20.3", not a DataVersion integer).
            // Source for the NUMBER 3693: not in this repository at all. minecraft.wiki's own
            // changelog for "Java Edition 1.20.3 Pre-Release 1" states the rename directly
            // ("Renamed 'Grass' to 'Short Grass'. The ID has been changed from `grass` to
            // `short_grass`."), and that pre-release's infobox lists DataVersion 3693 — checked via
            // two independent fetches, 2026-08-04. This replaces an earlier, wrong value of 3698
            // (1.20.3's *final release* DataVersion), the same release-vs-snapshot mistake as
            // grass_path above: the change happened 5 versions before the release it shipped in.
            renameRule("minecraft:grass", "minecraft:short_grass", 3693)
    );

    private static final List<BlockStateRule> RULES_BY_VERSION =
            RULES.stream().sorted(Comparator.comparingInt(BlockStateRule::since)).toList();

    private BlockStateRules() {
    }

    /**
     * Applies every known rule whose change happened after {@code sourceVersion}, in ascending
     * version order, so a state may pass through more than one rule.
     * <p>
     * A rule applies exactly when {@code rule.since() > sourceVersion}: {@link BlockStateRule#since()}
     * names the {@code DataVersion} the change happened in, so a source strictly older than that
     * version has not seen the change yet and needs it, while a source at or after that version
     * already carries the change's meaning and must be left alone. A rule with
     * {@code since() == 1802} therefore applies to a 1.13 world ({@code 1519 < 1802}) and not to a
     * 1.16 world ({@code 2566 > 1802}).
     * </p>
     * <p>
     * A state no rule recognizes — including every {@code redstone_wire} state, for which this
     * module deliberately carries no rule — passes through unchanged.
     * </p>
     *
     * @param state         the state as read from the source chunk, or as already transformed by an
     *                      earlier rule in this same call
     * @param sourceVersion the chunk's {@code DataVersion}
     * @return the translated state; {@code state} itself if no rule matched
     */
    public static BlockState translate(BlockState state, int sourceVersion) {
        BlockState current = state;
        for (BlockStateRule rule : RULES_BY_VERSION) {
            if (rule.since() > sourceVersion && rule.matches(current)) {
                current = rule.apply(current);
            }
        }
        return current;
    }

    private static BlockStateRule renameRule(String from, String to, int since) {
        return new Rule(since, state -> state.name().equals(from),
                state -> new BlockState(to, state.properties()));
    }

    private static BlockStateRule wallRule(int since) {
        return new Rule(since, state -> WALL_BLOCKS.contains(state.name()), BlockStateRules::rewriteWallSides);
    }

    private static BlockState rewriteWallSides(BlockState state) {
        Map<String, String> properties = new LinkedHashMap<>(state.properties());
        for (String side : WALL_SIDES) {
            String value = properties.get(side);
            if (value != null) {
                properties.put(side, "true".equals(value) ? "low" : "none");
            }
        }
        return new BlockState(state.name(), properties);
    }

    private static BlockStateRule cauldronRule(int since) {
        return new Rule(since,
                state -> state.name().equals("minecraft:cauldron") && state.properties().containsKey("level"),
                BlockStateRules::rewriteCauldronLevel);
    }

    private static BlockState rewriteCauldronLevel(BlockState state) {
        String level = state.properties().get("level");
        if ("0".equals(level)) {
            return new BlockState("minecraft:cauldron", Map.of());
        }
        return new BlockState("minecraft:water_cauldron", Map.of("level", level));
    }

    /**
     * A {@link BlockStateRule} built from a version, a match predicate and a transform, so the
     * individual facts above can be written as data rather than as one class each.
     *
     * @param since     the {@code DataVersion} the change happened in
     * @param matcher   decides whether {@code transform} applies to a given state
     * @param transform the state transformation itself
     */
    private record Rule(int since, Predicate<BlockState> matcher, UnaryOperator<BlockState> transform)
            implements BlockStateRule {

        @Override
        public boolean matches(BlockState state) {
            return this.matcher.test(state);
        }

        @Override
        public BlockState apply(BlockState state) {
            return this.transform.apply(state);
        }
    }
}
