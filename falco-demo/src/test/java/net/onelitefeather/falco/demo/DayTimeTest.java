package net.onelitefeather.falco.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the translation between the names a player types and the ticks a world counts.
 * <p>
 * The point of these tests is not arithmetic for its own sake. A demo server exists so someone can
 * look at the light, and looking at the light means switching between noon and midnight on demand.
 * If {@code /time night} put the sun somewhere else than vanilla does, every judgement made on that
 * server would be made under the wrong sky.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
// One test here switches the formatting locale of the whole JVM to prove that a description does
// not depend on it. Restoring the locale afterwards only holds against tests which run before or
// after this class, and gradle.properties asks for concurrent execution, so the class has to be
// the one thing running while that window is open.
@Isolated
class DayTimeTest {

    @Test
    void testTheNamesCarryTheTickValuesVanillaUses() {
        assertEquals(1000L, DayTime.DAY.ticks());
        assertEquals(6000L, DayTime.NOON.ticks());
        assertEquals(13000L, DayTime.NIGHT.ticks());
        assertEquals(18000L, DayTime.MIDNIGHT.ticks());
    }

    @Test
    void testANameIsAcceptedRegardlessOfCase() {
        assertEquals(DayTime.NIGHT, DayTime.byName("night"));
        assertEquals(DayTime.NIGHT, DayTime.byName("NIGHT"));
        assertEquals(DayTime.NIGHT, DayTime.byName("Night"));
    }

    @Test
    void testAnUnknownNameIsRejectedRatherThanGuessed() {
        assertNull(DayTime.byName("dusk"));
        assertNull(DayTime.byName(""));
        assertNull(DayTime.byName("1000"));
    }

    @Test
    void testTheNamesAreListedForAnErrorMessage() {
        String names = DayTime.names();

        for (DayTime time : DayTime.values()) {
            assertTrue(names.contains(time.name().toLowerCase(Locale.ROOT)),
                    "the list has to name " + time + " so a rejected input can show what is allowed");
        }
    }

    @Test
    void testTickZeroIsSixInTheMorning() {
        // Minecraft starts its day at sunrise, not at midnight. A description that got this wrong
        // would be off by six hours and nobody would notice until they compared it with the sky.
        assertTrue(DayTime.describe(0L, false).contains("06:00"), DayTime.describe(0L, false));
    }

    @Test
    void testTheKnownTimesDescribeTheHourTheyStandFor() {
        assertTrue(DayTime.describe(DayTime.NOON.ticks(), false).contains("12:00"));
        assertTrue(DayTime.describe(DayTime.MIDNIGHT.ticks(), false).contains("00:00"));
    }

    @Test
    void testTheDayCountsFromOneAndAdvancesWithTheClock() {
        assertTrue(DayTime.describe(0L, false).contains("day 1"), DayTime.describe(0L, false));
        assertTrue(DayTime.describe(24_000L, false).contains("day 2"), DayTime.describe(24_000L, false));
        assertTrue(DayTime.describe(47_999L, false).contains("day 2"), DayTime.describe(47_999L, false));
    }

    @Test
    void testADescriptionSaysWhetherTheClockIsRunning() {
        assertTrue(DayTime.describe(1000L, true).contains("paused"));
        assertTrue(DayTime.describe(1000L, false).contains("running"));
    }

    @Test
    void testATimeBeyondOneDayIsFoldedIntoItsHour() {
        // The clock keeps counting past 24000, so a description has to fold rather than overflow.
        assertTrue(DayTime.describe(24_000L + DayTime.NOON.ticks(), false).contains("12:00"));
    }

    @Test
    void testANegativeTimeIsDescribedWithoutBreaking() {
        // setTime takes a long and nothing stops a caller passing a negative one. The description
        // must not produce a negative hour, which is what a plain modulo would do.
        String described = DayTime.describe(-1000L, false);

        // An hour before tick zero, and tick zero is six in the morning.
        assertTrue(described.startsWith("05:00"), described);
        assertTrue(described.contains("tick -1000"), described);

        // Naming the tick is the only thing here allowed to print a minus. Asserting on its
        // absence alone would pass for a negative hour or a negative day as long as the tick was
        // quoted somewhere, which is what this test exists to rule out.
        assertEquals(1L, described.chars().filter(character -> character == '-').count(), described);
    }

    @Test
    void testADescriptionReadsTheSameNoMatterWhichLocaleTheServerStartsUnder() {
        // A clock reading goes to a player, and %d prints the digits the formatting locale
        // prescribes. Under a locale with its own numerals the description would come out as
        // "०६:०० on day १" — readable to nobody who typed /time, and invisible to every other
        // assertion here because the tests themselves run under a locale that uses ASCII digits.
        Locale format = Locale.getDefault(Locale.Category.FORMAT);

        // Only the formatting category, and only for the length of this test: changing the whole
        // default locale would follow the JVM into every other test in this module.
        Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("hi-IN-u-nu-deva"));
        try {
            assertEquals("06:00 on day 1, running (tick 0)", DayTime.describe(0L, false));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }
}
