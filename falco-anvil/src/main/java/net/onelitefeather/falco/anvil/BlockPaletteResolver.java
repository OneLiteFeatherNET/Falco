package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.minestom.server.instance.block.Block;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * The {@link BlockPaletteResolver} class translates between the block entries of the Anvil format
 * and the block state ids of Minestom.
 * <p>
 * An entry the server does not know is handed to an {@link UnknownEntryPolicy} instead of failing
 * outright: the default substitutes air, which keeps a world holding blocks of a mod or of a newer
 * game version loadable instead of losing a whole chunk over a single unknown block, but a caller
 * that converts or checks a world can configure a policy which refuses it instead. Every unknown
 * name is reported once through the diagnostics so the problem stays visible without flooding the
 * log, regardless of what the policy does with it.
 * </p>
 *
 * <p>
 * This type is experimental. The Anvil loader is new and its API may still change while it is
 * being validated against real worlds.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.1.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public final class BlockPaletteResolver implements PaletteEntryResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockPaletteResolver.class);

    private static final String NAME_KEY = "Name";
    private static final String PROPERTIES_KEY = "Properties";

    private final AnvilDiagnostics diagnostics;
    private final UnknownEntryPolicy policy;

    /**
     * Creates a new resolver which reports unknown blocks to the given diagnostics and replaces
     * them following {@link DefaultUnknownEntryPolicy}.
     *
     * @param diagnostics the diagnostics which throttle the reports
     */
    public BlockPaletteResolver(AnvilDiagnostics diagnostics) {
        this(diagnostics, new DefaultUnknownEntryPolicy());
    }

    /**
     * Creates a new resolver which reports unknown blocks to the given diagnostics and decides what
     * they become through the given policy.
     *
     * @param diagnostics the diagnostics which throttle the reports
     * @param policy      the policy consulted for a block the server does not know
     * @since 2.1.0
     */
    public BlockPaletteResolver(AnvilDiagnostics diagnostics, UnknownEntryPolicy policy) {
        this.diagnostics = diagnostics;
        this.policy = policy;
    }

    /**
     * {@inheritDoc}
     *
     * @throws AnvilChunkException if the configured policy refuses an unknown block
     */
    @Override
    public int toId(String name, @Nullable CompoundBinaryTag properties) {
        Block block = Block.fromKey(name);

        if (block == null) {
            if (this.diagnostics.reportUnknownBlock(name)) {
                LOGGER.warn("The block '{}' is unknown, further chunks with it are not reported", name);
            }
            return this.policy.onUnknownBlock(name, properties);
        }
        if (properties == null || properties.size() == 0) {
            return block.stateId();
        }
        return block.withProperties(readProperties(name, properties)).stateId();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompoundBinaryTag toEntry(int id) {
        Block block = Block.fromStateId(id);

        if (block == null) {
            return CompoundBinaryTag.builder().putString(NAME_KEY, Block.AIR.key().asString()).build();
        }

        CompoundBinaryTag.Builder entry = CompoundBinaryTag.builder().putString(NAME_KEY, block.key().asString());
        Map<String, String> properties = block.properties();

        if (!properties.isEmpty()) {
            CompoundBinaryTag.Builder values = CompoundBinaryTag.builder();
            properties.forEach(values::putString);
            entry.put(PROPERTIES_KEY, values.build());
        }
        return entry.build();
    }

    /**
     * Reads the properties of a palette entry.
     * A property which does not hold a string is skipped because the format only defines string
     * values for them.
     *
     * @param name       the name of the block the properties belong to
     * @param properties the properties of the palette entry
     * @return the properties of the block
     */
    private Map<String, String> readProperties(String name, CompoundBinaryTag properties) {
        Map<String, String> values = HashMap.newHashMap(properties.size());

        for (Map.Entry<String, ? extends BinaryTag> property : properties) {
            if (property.getValue() instanceof StringBinaryTag value) {
                values.put(property.getKey(), value.value());
                continue;
            }
            if (this.diagnostics.reportUnknownBlock(name + "#" + property.getKey())) {
                LOGGER.warn(
                        "The property '{}' of the block '{}' is a {} instead of a string and is skipped",
                        property.getKey(), name, property.getValue().type()
                );
            }
        }
        return values;
    }
}
