package net.onelitefeather.falco.anvil;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagType;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.LongArrayBinaryTag;
import net.kyori.adventure.nbt.NumberBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * The {@link NbtReads} class provides strict read access to Adventure NBT structures.
 * <p>
 * The getters of {@link CompoundBinaryTag} return a default value when a key is missing or holds
 * an unexpected type. For chunk data that behaviour is dangerous because a broken region file
 * would silently turn into an empty chunk which overwrites the real data on the next save.
 * Every method of this class therefore reports a missing or mistyped value as an error.
 * </p>
 * <p>
 * The class also avoids the iterators of the array tags. In Adventure 5.1.1 those iterators stop
 * one entry early which would drop the last entry of every packed block or biome array.
 * </p>
 *
 * <p>
 * This type is experimental. The Anvil loader is new and its API may still change while it is
 * being validated against real worlds.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.1.0
 */
@ApiStatus.Experimental
public final class NbtReads {

    private NbtReads() {
    }

    /**
     * Reads a long array and copies every entry of it.
     *
     * @param compound the compound which holds the array
     * @param key      the key of the array
     * @return the entries of the array
     * @throws IOException if the key is missing or does not hold a long array
     */
    public static long[] longArray(CompoundBinaryTag compound, String key) throws IOException {
        if (!(compound.get(key) instanceof LongArrayBinaryTag tag)) {
            throw missing(compound, key, "a long array");
        }

        long[] values = new long[tag.size()];

        for (int index = 0; index < values.length; index++) {
            values[index] = tag.get(index);
        }
        return values;
    }

    /**
     * Reads an int array and copies every entry of it.
     *
     * @param compound the compound which holds the array
     * @param key      the key of the array
     * @return the entries of the array
     * @throws IOException if the key is missing or does not hold an int array
     */
    public static int[] intArray(CompoundBinaryTag compound, String key) throws IOException {
        if (!(compound.get(key) instanceof IntArrayBinaryTag tag)) {
            throw missing(compound, key, "an int array");
        }

        int[] values = new int[tag.size()];

        for (int index = 0; index < values.length; index++) {
            values[index] = tag.get(index);
        }
        return values;
    }

    /**
     * Reads a nested compound.
     *
     * @param compound the compound which holds the nested compound
     * @param key      the key of the nested compound
     * @return the nested compound
     * @throws IOException if the key is missing or does not hold a compound
     */
    public static CompoundBinaryTag compound(CompoundBinaryTag compound, String key) throws IOException {
        if (!(compound.get(key) instanceof CompoundBinaryTag tag)) {
            throw missing(compound, key, "a compound");
        }
        return tag;
    }

    /**
     * Reads a nested compound which is allowed to be absent.
     *
     * @param compound the compound which holds the nested compound
     * @param key      the key of the nested compound
     * @return the nested compound or null if the key is absent or holds another type
     */
    @Contract(pure = true)
    public static @Nullable CompoundBinaryTag optionalCompound(CompoundBinaryTag compound, String key) {
        return compound.get(key) instanceof CompoundBinaryTag tag ? tag : null;
    }

    /**
     * Reads a list and verifies the type of its elements.
     * An empty list always reports {@link BinaryTagTypes#END} as its element type, so it is
     * accepted for every requested type.
     *
     * @param compound    the compound which holds the list
     * @param key         the key of the list
     * @param elementType the type every element of the list has to use
     * @return the list
     * @throws IOException if the key is missing, does not hold a list or holds other elements
     */
    public static ListBinaryTag list(CompoundBinaryTag compound, String key, BinaryTagType<? extends BinaryTag> elementType) throws IOException {
        if (!(compound.get(key) instanceof ListBinaryTag tag)) {
            throw missing(compound, key, "a list");
        }
        if (tag.size() > 0 && tag.elementType() != elementType) {
            throw new IOException("The key '" + key + "' holds a list of another element type than the expected one");
        }
        return tag;
    }

    /**
     * Reads a list which is allowed to be absent.
     *
     * @param compound    the compound which holds the list
     * @param key         the key of the list
     * @param elementType the type every element of the list has to use
     * @return the list or an empty list if the key is absent or holds another type
     */
    @Contract(pure = true)
    public static ListBinaryTag optionalList(CompoundBinaryTag compound, String key, BinaryTagType<? extends BinaryTag> elementType) {
        if (compound.get(key) instanceof ListBinaryTag tag && (tag.size() == 0 || tag.elementType() == elementType)) {
            return tag;
        }
        return ListBinaryTag.empty();
    }

    /**
     * Reads a string value.
     *
     * @param compound the compound which holds the value
     * @param key      the key of the value
     * @return the value
     * @throws IOException if the key is missing or does not hold a string
     */
    public static String string(CompoundBinaryTag compound, String key) throws IOException {
        if (!(compound.get(key) instanceof StringBinaryTag tag)) {
            throw missing(compound, key, "a string");
        }
        return tag.value();
    }

    /**
     * Reads a string value which is allowed to be absent.
     *
     * @param compound the compound which holds the value
     * @param key      the key of the value
     * @return the value or null if the key is absent or holds another type
     */
    @Contract(pure = true)
    public static @Nullable String optionalString(CompoundBinaryTag compound, String key) {
        return compound.get(key) instanceof StringBinaryTag tag ? tag.value() : null;
    }

    /**
     * Reads a numeric value as an int. Every numeric tag is accepted because the format stores
     * some values with a narrower type than an int.
     *
     * @param compound the compound which holds the value
     * @param key      the key of the value
     * @return the value
     * @throws IOException if the key is missing or does not hold a number
     */
    public static int integer(CompoundBinaryTag compound, String key) throws IOException {
        if (!(compound.get(key) instanceof NumberBinaryTag tag)) {
            throw missing(compound, key, "a number");
        }
        return tag.intValue();
    }

    /**
     * Reads a numeric value as an int which is allowed to be absent.
     *
     * @param compound     the compound which holds the value
     * @param key          the key of the value
     * @param defaultValue the value to use if the key is absent
     * @return the value or the given default value
     */
    @Contract(pure = true)
    public static int optionalInteger(CompoundBinaryTag compound, String key, int defaultValue) {
        return compound.get(key) instanceof NumberBinaryTag tag ? tag.intValue() : defaultValue;
    }

    /**
     * Builds the error for a key which is missing or holds an unexpected type.
     *
     * @param compound the compound which was read
     * @param key      the key which was requested
     * @param expected the description of the expected type
     * @return the error to report
     */
    @Contract(pure = true, value = "_, _, _ -> new")
    private static IOException missing(CompoundBinaryTag compound, String key, String expected) {
        BinaryTag actual = compound.get(key);
        String description = actual == null ? "is absent" : "holds " + actual.type();
        return new IOException("The key '" + key + "' " + description + " but " + expected + " was expected");
    }
}
