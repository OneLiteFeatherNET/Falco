package net.onelitefeather.falco.demo;

import org.junit.jupiter.api.Test;

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
 * @since 0.3.0
 */
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
            assertTrue(names.contains(time.name().toLowerCase(java.util.Locale.ROOT)),
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

        assertTrue(described.contains(":"), described);
        assertTrue(described.indexOf('-') < 0 || described.contains("tick -1000"), described);
    }
}
