package net.onelitefeather.falco.demo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests everything the time command decides before it touches a clock.
 * <p>
 * The wiring itself — an argument, a syntax, a registered command — is Minestom's and is exercised by
 * starting a server. What is this module's own is the part in between: which word means which time,
 * which two words mean the clock rather than the time, and what a sender is told when a word means
 * nothing at all. A refusal that does not name the alternatives leaves somebody guessing at four
 * words, which is the whole reason those sentences are built here rather than left to a parser.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.3.0
 */
class TimeCommandTest {

    @Test
    void testTheReadingCarriesTheHourAndTheTickItCameFrom() {
        String reading = TimeCommand.reading(DayTime.NOON.ticks(), false);

        assertTrue(reading.contains("12:00"), reading);
        assertTrue(reading.contains("tick 6000"), reading);
    }

    @Test
    void testAChangeIsAnsweredWithTheNewReadingRatherThanWithAnEcho() {
        String changed = TimeCommand.changed(DayTime.MIDNIGHT.ticks(), false);

        assertTrue(changed.contains("00:00"), changed);
        assertTrue(changed.contains("tick 18000"), changed);
    }

    @Test
    void testAReadingSaysWhetherTheClockIsHeld() {
        assertTrue(TimeCommand.reading(0L, true).contains("paused"));
        assertTrue(TimeCommand.reading(0L, false).contains("running"));
        assertTrue(TimeCommand.changed(0L, true).contains("paused"));
    }

    @Test
    void testARejectedWordNamesEveryTimeOfDayThatWouldHaveWorked() {
        String rejected = TimeCommand.rejected("dusk");

        assertTrue(rejected.contains("dusk"), rejected);

        for (DayTime time : DayTime.values()) {
            assertTrue(rejected.contains(time.name().toLowerCase(Locale.ROOT)),
                    "a refusal has to name " + time + " so nobody has to guess: " + rejected);
        }
    }

    @Test
    void testARejectedWordAlsoNamesTheWordsWhichAreNotTimesOfDay() {
        // The names alone would be a half answer: three of the seven accepted words are keywords,
        // and a reader told only about the names would never find them.
        String rejected = TimeCommand.rejected("dusk");

        assertTrue(rejected.contains(TimeCommand.HOLD), rejected);
        assertTrue(rejected.contains(TimeCommand.RELEASE), rejected);
        assertTrue(rejected.contains(TimeCommand.SET), rejected);
    }

    @Test
    void testTheClockWordsAreAcceptedRegardlessOfCase() {
        assertTrue(TimeCommand.holds("pause"));
        assertTrue(TimeCommand.holds("PAUSE"));
        assertTrue(TimeCommand.holds("Pause"));
        assertTrue(TimeCommand.releases("resume"));
        assertTrue(TimeCommand.releases("RESUME"));
    }

    @Test
    void testTheClockWordsDoNotSwallowATimeOfDay() {
        // Both branches run before the name lookup does, so a word which means a time of day must
        // not answer to either of them.
        for (DayTime time : DayTime.values()) {
            String name = time.name().toLowerCase(Locale.ROOT);

            assertFalse(TimeCommand.holds(name), name + " is a time of day, not the hold word");
            assertFalse(TimeCommand.releases(name), name + " is a time of day, not the release word");
        }

        assertFalse(TimeCommand.holds("resume"));
        assertFalse(TimeCommand.releases("pause"));
        assertFalse(TimeCommand.holds(""));
    }

    @Test
    void testTheSuggestionsOfferEveryWordTheCommandAccepts() {
        List<String> words = TimeCommand.words();

        for (DayTime time : DayTime.values()) {
            assertTrue(words.contains(time.name().toLowerCase(Locale.ROOT)),
                    "a completion which drops " + time + " hides a word the command takes: " + words);
        }

        assertTrue(words.contains(TimeCommand.HOLD), words.toString());
        assertTrue(words.contains(TimeCommand.RELEASE), words.toString());
        assertTrue(words.contains(TimeCommand.SET), words.toString());
    }

    @Test
    void testTheSuggestionsAreOfferedInTheCapitalisationTheCommandIsTypedIn() {
        for (String word : TimeCommand.words()) {
            assertTrue(word.equals(word.toLowerCase(Locale.ROOT)),
                    "a suggestion the client completes to has to be typeable as it stands: " + word);
        }
    }
}
