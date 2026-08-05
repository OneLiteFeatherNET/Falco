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
 * measurement. One fact from that measurement, {@code redstone_wire}, took a second pass beyond it
 * to land here safely; see the note above {@link #RULES} for how.
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
     * <b>{@code redstone_wire}</b> (DataVersion 2532, snapshot 20w18a, Minecraft 1.16, 144 of 1296
     * states) took a second pass to land in this list. The research document establishes only the
     * shape of the change — a direction's new value depends on the other three directions of the
     * same state, a per-property implementation is guaranteed wrong, and 9 of the 81 direction
     * combinations change (times 16 {@code power} values = 144 states) — not which nine or what they
     * become. That was not enough on its own: a guessed whole-state table for redstone wiring would
     * have been exactly the silent corruption this module exists to avoid, the chunk still loads, the
     * wiring looks subtly different, and nobody notices. The rule below closes that gap directly from
     * the connection semantics: a direction becomes {@code side} exactly when it is itself
     * unconnected AND the axis perpendicular to it has no connection either — {@code north}/
     * {@code south} are decided by whether {@code east}/{@code west} connects, {@code east}/
     * {@code west} by whether {@code north}/{@code south} connects. The axes are crossed on purpose,
     * not mirrored, and getting that backwards produces a rule that looks plausible and is wrong; see
     * {@code BlockStateRulesTest} for the nine affected combinations this guards against. It was
     * cross-checked against DataConverter's V2531, Yarn's {@code RedstoneConnectionsFix}, and
     * Chunker's {@code JavaLegacyRedstonePreTransformHandler} — three independently maintained
     * reimplementations of the same 1.16 change — all of which agree with the derivation above.
     * {@code power} is a plain multiplier and is neither read nor written by this rule.
     * </p>
     * <p>
     * A state this module does not otherwise recognize passes through
     * {@link #translate(BlockState, int)} unchanged; see
     * {@code BlockStateRulesTest.testAStateNoRuleKnowsAboutPassesThroughUnchanged}.
     * </p>
     */
    private static final List<BlockStateRule> RULES = List.of(
            // sign -> oak_sign. Oak was the only wood type a sign could be in 1.13, so this is a
            // plain rename with no ambiguity.
            // Source for the RENAME ITSELF: blockstate-property-research.md, "Widerspruch 2" (names
            // the snapshot "18w43a", not a DataVersion integer — the research document's own "V1802"
            // for this fact turned out to be wrong; see the NUMBER source below).
            // Source for the NUMBER 1901: not the research document. minecraft.wiki's own changelog
            // for snapshot 18w43a states the rename directly ("Renamed 'Sign' to 'Oak Sign'."), and
            // that snapshot's infobox lists DataVersion 1901 — checked via two independent fetches,
            // 2026-08-04. This replaces the research document's "V1802", which does not correspond to
            // any named public snapshot or release — 1631 (1.13.2) and 1901 (18w43a) are the nearest
            // named points, and 1802 sits in the unnamed gap between them. Unlike the grass/grass_path
            // corrections, this error made the rule fire too LATE rather than too early: a source
            // between 1802 and 1900 still stored the pre-rename name "sign" but the old, too-low
            // threshold would have left it untranslated, and Minestom throws a NullPointerException on
            // an unknown block name rather than tolerating it.
            renameRule("minecraft:sign", "minecraft:oak_sign", 1901),

            // wall_sign -> oak_wall_sign, same reasoning and same NUMBER correction as sign above
            // (1802 -> 1901). Unlike sign and stone_slab, no changelog text names "wall_sign"
            // explicitly in 18w43a's patch notes — searched directly and found nothing. The snapshot
            // attribution is inferred rather than directly quoted: wall_sign is the placement-derived
            // variant of sign, both are part of the same wood-type ID-prefixing pass that shipped in
            // one snapshot, and the research document groups the two facts under one citation even
            // after its number turned out to be wrong. This is weaker sourcing than sign's and
            // stone_slab's direct changelog quotes, and is flagged as such rather than presented as
            // equally solid.
            renameRule("minecraft:wall_sign", "minecraft:oak_wall_sign", 1901),

            // stone_slab -> smooth_stone_slab. The name is reused by a different block from 1.14 on
            // (1.13's stone_slab and 26.x's stone_slab both have 6 states, but they mean different
            // blocks), which is why this rule MUST be versioned: unversioned, it would rename a
            // 1.16+ world's already-correct stone_slab and corrupt it.
            // Source for the RENAME ITSELF: blockstate-property-research.md, section "A) Die Zahl",
            // two-case table (names the snapshot "18w43a"; verified 1.13 stone_slab = 6 states, 26.3
            // stone_slab AND smooth_stone_slab each = 6 states).
            // Source for the NUMBER 1901: same as sign above — minecraft.wiki's 18w43a changelog
            // states "Stone slabs have been renamed to smooth stone slabs." directly, and the
            // snapshot's infobox lists DataVersion 1901, replacing the research document's wrong
            // "V1802". This is the case the whole versioning mechanism exists for, so its own boundary
            // is pinned by a dedicated test rather than only the two far-apart sources 1519 and 2566;
            // see BlockStateRulesTest.testARuleAppliesExactlyBelowItsOwnVersionAndNotAtOrAboveIt.
            renameRule("minecraft:stone_slab", "minecraft:smooth_stone_slab", 1901),

            // cobblestone_wall / mossy_cobblestone_wall: north/south/east/west go from a boolean
            // (false/true) to none/low/tall, as a pure lookup (true -> low, false -> none). `tall`
            // is not reachable from a 1.13 state and Mojang's own fix does not attempt it either.
            // Source for the RENAME ITSELF: blockstate-property-research.md, "Widerspruch 3" (names
            // the snapshot family as "1.16"; confirmed no neighbor/chunk context is needed, contrary
            // to what the wiki route alone suggested).
            // Source for the NUMBER 2504: not the research document, whose "V2503" is off by one from
            // any named snapshot. minecraft.wiki's own changelog for snapshot 20w06a states the change
            // directly ("Block state now uses none, low, and tall for east, west, north, and south
            // directional values."), and that snapshot's infobox lists DataVersion 2504 — checked via
            // two independent fetches, 2026-08-04.
            wallRule(2504),

            // redstone_wire: north/south/east/west each flip from their raw connection value to
            // "side" exactly when two conditions both hold — the direction itself is unconnected
            // ("none"), and the axis PERPENDICULAR to it has no connection of its own. north/south
            // are decided by east/west; east/west are decided by north/south. Implementing this
            // gleichachsig (north/south checked against north/south) produces a rule that reads fine
            // and is wrong for every asymmetric case; see BlockStateRulesTest for the nine
            // combinations that catch exactly that mistake. "up" counts as connected for this check
            // but is itself never rewritten — it has no opposite in this scheme. "power" is a plain
            // multiplier, read by nothing here and written by nothing here.
            // Source for the RULE ITSELF: not the research document, whose measurement gave the size
            // of the change (9 of 81 direction combinations, times 16 power values) but not which
            // nine or what they become. The connection semantics above were derived directly and then
            // cross-checked against DataConverter's V2531, Yarn's RedstoneConnectionsFix, and
            // Chunker's JavaLegacyRedstonePreTransformHandler — three independently maintained
            // reimplementations of the same 1.16 change — all of which agree.
            // Source for the NUMBER 2532: not the research document, whose "V2531" names a fix
            // version that lands between two public snapshots rather than at either one, and whose
            // snapshot label "20w17a" (DataVersion 2529) turned out not to match: that snapshot's own
            // changelog has nothing about redstone wire. 20w18a's changelog and its dedicated
            // Redstone Dust history entry state the change directly — "Unconnected redstone dust now
            // has all direction block states set to 'side'" and direction states are "properly set to
            // 'side' at the end of a redstone wire on both ends, rather than only the one with other
            // redstone besides it" — word for word the derivation above. minecraft.wiki's data-version
            // table and PrismarineJS/minecraft-data's protocolVersions.json, two independent sources,
            // both give 20w18a DataVersion 2532 — checked via independent fetches, 2026-08-05. This
            // corrects the research document's guess by exactly one snapshot, the same
            // release-vs-actual-snapshot class of error as grass_path and grass below, just one
            // snapshot early rather than landing on the final release.
            redstoneWireRule(2532),

            // cauldron[level]: level=0 -> cauldron (properties emptied, the property is dropped
            // entirely); level=1..3 -> water_cauldron[level=n]. The block name is decided by a
            // property value, which is why this rule changes the name rather than only a value —
            // and exactly why the case was invisible to a plain name diff.
            // Source for the RENAME ITSELF: blockstate-property-research.md, section "A) Die Zahl",
            // row "(c) Property weggefallen" (names the snapshot family as "1.17").
            // Source for the NUMBER 2681: not the research document, whose "V2679" names neither the
            // right number nor (per a table lookup that turned out to be unreliable) the right
            // snapshot — 21w03a, DataVersion 2689, whose changelog has nothing about cauldrons beyond
            // a subtitle capitalization fix. minecraft.wiki's own changelog for snapshot 20w45a states
            // the split directly ("Have been split into normal, water and lava cauldrons."), and that
            // snapshot's infobox lists DataVersion 2681 — checked via three independent fetches,
            // 2026-08-04. This is the same snapshot as grass_path's rename below.
            cauldronRule(2681),

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
            renameRule("minecraft:grass", "minecraft:short_grass", 3693),

            // chain -> iron_chain, when copper chains arrived and the plain chain had to be
            // disambiguated.
            // Source for the RENAME ITSELF: the running Minestom registry, measured 2026-08-05.
            // "minecraft:chain" resolves to nothing on 26.1.2, while "minecraft:iron_chain" and
            // "minecraft:copper_chain" both resolve — so the old name is gone, and iron_chain is what
            // it became.
            // Source for the NUMBER 4556: not a document. Measured from a real world, because the
            // wiki numbers behind grass_path and grass above were each wrong on the first attempt in
            // the same release-vs-snapshot way. 1399 nether region files were scanned and every
            // chunk's block names correlated with that same chunk's stored DataVersion: "chain"
            // appears at DataVersion 3465, 3578, 3955 and 4435 (4930 chunks in total), "iron_chain"
            // only at 4556 (189 chunks), and no chunk carries both. The change therefore happened in
            // (4435, 4556]; that world holds no chunk from between those two versions, so the data
            // cannot resolve it any finer.
            // Why the UPPER bound of that interval is the safe end to pick: too HIGH only lets this
            // rule inspect chunks that no longer contain the old name, where its predicate does not
            // match and nothing happens. Too LOW would leave "chain" standing in every chunk between
            // the true version and the chosen one — and a name the server does not know is what the
            // loader silently turns into air, which is the entire failure this rule exists to stop.
            renameRule("minecraft:chain", "minecraft:iron_chain", 4556)
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
     * {@code since() == 1901} therefore applies to a 1.13 world ({@code 1519 < 1901}) and not to a
     * 1.16 world ({@code 2566 > 1901}).
     * </p>
     * <p>
     * A state no rule recognizes passes through unchanged.
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

    private static BlockStateRule redstoneWireRule(int since) {
        return new Rule(since,
                state -> state.name().equals("minecraft:redstone_wire"),
                BlockStateRules::rewriteRedstoneWireConnections);
    }

    /**
     * Recomputes {@code redstone_wire}'s four direction properties from each other, crossing the
     * axes on purpose: whether {@code north}/{@code south} collapse to {@code side} is decided by
     * {@code east}/{@code west}'s connection state, and whether {@code east}/{@code west} collapse is
     * decided by {@code north}/{@code south}'s. All four outputs are computed from the original,
     * unmodified values first and only written afterward, so an earlier write in this same call can
     * never leak into a later read — sequential writing gets the all-{@code none} case wrong.
     *
     * @param state a {@code redstone_wire} state as read from the source chunk
     * @return {@code state} with its four direction properties resolved to their later meaning
     */
    private static BlockState rewriteRedstoneWireConnections(BlockState state) {
        Map<String, String> properties = state.properties();
        String north = properties.get("north");
        String south = properties.get("south");
        String east = properties.get("east");
        String west = properties.get("west");

        boolean eastWestConnected = isRedstoneConnected(east) || isRedstoneConnected(west);
        boolean northSouthConnected = isRedstoneConnected(north) || isRedstoneConnected(south);

        Map<String, String> rewritten = new LinkedHashMap<>(properties);
        if (north != null) {
            rewritten.put("north", resolveRedstoneSide(north, eastWestConnected));
        }
        if (south != null) {
            rewritten.put("south", resolveRedstoneSide(south, eastWestConnected));
        }
        if (east != null) {
            rewritten.put("east", resolveRedstoneSide(east, northSouthConnected));
        }
        if (west != null) {
            rewritten.put("west", resolveRedstoneSide(west, northSouthConnected));
        }
        return new BlockState(state.name(), rewritten);
    }

    /**
     * Whether a {@code redstone_wire} direction counts as connected. {@code up} counts here — it is
     * a connection, just one this rule never overwrites — and only {@code none} does not.
     *
     * @param value a direction's raw property value, or {@code null} if the state omits it
     * @return {@code true} unless {@code value} is {@code null} or {@code "none"}
     */
    private static boolean isRedstoneConnected(String value) {
        return value != null && !"none".equals(value);
    }

    /**
     * Resolves one {@code redstone_wire} direction: it becomes {@code side} exactly when it is
     * itself unconnected and the perpendicular axis carries no connection either, otherwise it keeps
     * its original value — including {@code up}, which this never touches.
     *
     * @param value                          the direction's original value
     * @param perpendicularAxisHasConnection whether the OTHER axis (not this direction's own) has a
     *                                        connection anywhere on it
     * @return {@code "side"} or {@code value} unchanged
     */
    private static String resolveRedstoneSide(String value, boolean perpendicularAxisHasConnection) {
        return (!isRedstoneConnected(value) && !perpendicularAxisHasConnection) ? "side" : value;
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
