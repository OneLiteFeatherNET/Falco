package net.onelitefeather.falco.demo;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The named times of day a player can ask for, and the description of a clock reading.
 * <p>
 * This exists because the demo server is there to be looked at. Judging whether the light is right
 * means putting the sun where you want it, and waiting twenty minutes for nightfall is not a
 * workflow. The tick values are the ones the vanilla {@code /time set} command uses, so a world
 * lit here looks like the same world lit anywhere else — a demo that invented its own noon would
 * quietly invalidate every comparison made on it.
 * </p>
 * <p>
 * A Minecraft day starts at sunrise rather than at midnight, which is why tick zero is six in the
 * morning and not zero o'clock. That offset is the one thing about this class worth remembering.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
enum DayTime {

    /**
     * Sunrise has passed and the sky is bright. The moment vanilla calls {@code day}.
     */
    DAY(1_000L),

    /**
     * The sun stands at its highest, so sky light is at its strongest.
     */
    NOON(6_000L),

    /**
     * The sun has set. Block light sources are the only thing left to see by, which makes this the
     * interesting one when judging a light engine.
     */
    NIGHT(13_000L),

    /**
     * The darkest point of the night.
     */
    MIDNIGHT(18_000L);

    /**
     * The amount of ticks a full day and night lasts.
     */
    static final long TICKS_PER_DAY = 24_000L;

    /**
     * The amount of ticks one in-game hour lasts.
     */
    private static final long TICKS_PER_HOUR = 1_000L;

    /**
     * The hour of the day tick zero stands for. A Minecraft day begins at sunrise.
     */
    private static final long SUNRISE_HOUR = 6L;

    /**
     * The amount of minutes an hour holds, used to turn the remainder of an hour into minutes.
     */
    private static final long MINUTES_PER_HOUR = 60L;

    /**
     * The tick this time of day stands for.
     */
    private final long ticks;

    /**
     * Creates a time of day.
     *
     * @param ticks the tick this time of day stands for
     */
    DayTime(long ticks) {
        this.ticks = ticks;
    }

    /**
     * Returns the tick this time of day stands for.
     *
     * @return the tick of this time of day
     */
    @Contract(pure = true)
    long ticks() {
        return this.ticks;
    }

    /**
     * Looks up a time of day by the name a player typed.
     *
     * @param name the name to look up, in any capitalisation
     * @return the time of day, or null if no time of day carries that name
     */
    @Contract(pure = true)
    static @Nullable DayTime byName(String name) {
        for (DayTime time : values()) {
            if (time.name().equalsIgnoreCase(name)) {
                return time;
            }
        }
        return null;
    }

    /**
     * Every name this type accepts, separated by a comma.
     * <p>
     * Held rather than rebuilt because it is joined into a message on every player who arrives and
     * on every word the command turns down, and the names do not change while the server runs.
     * </p>
     */
    static final String NAMES = Arrays.stream(values())
            .map(time -> time.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.joining(", "));

    /**
     * Lists every name this type accepts, so a rejected input can be answered with the alternatives
     * instead of with a bare refusal.
     *
     * @return the accepted names, separated by a comma
     */
    @Contract(pure = true)
    static String names() {
        return NAMES;
    }

    /**
     * Describes a clock reading the way a person reads a clock.
     * <p>
     * The tick itself is kept in the description rather than replaced by it: the hour is what a
     * reader wants, the tick is what they have to type to get back here.
     * </p>
     *
     * @param ticks  the tick the clock stands at, which may be negative or beyond a single day
     * @param paused whether the clock is currently held
     * @return the reading as a sentence
     */
    @Contract(pure = true)
    static String describe(long ticks, boolean paused) {
        // floorMod rather than %, because the clock accepts a negative tick and a plain remainder
        // would answer a negative hour for it.
        long withinDay = Math.floorMod(ticks, TICKS_PER_DAY);
        long hour = Math.floorMod(withinDay / TICKS_PER_HOUR + SUNRISE_HOUR, 24L);
        long minute = withinDay % TICKS_PER_HOUR * MINUTES_PER_HOUR / TICKS_PER_HOUR;
        long day = Math.floorDiv(ticks, TICKS_PER_DAY) + 1L;

        return "%02d:%02d on day %d, %s (tick %d)".formatted(
                hour, minute, day, paused ? "paused" : "running", ticks
        );
    }
}
