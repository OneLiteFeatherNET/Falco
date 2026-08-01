package net.onelitefeather.falco.demo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.arguments.ArgumentWord;
import net.minestom.server.command.builder.arguments.number.ArgumentLong;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;
import net.minestom.server.instance.Clock;
import net.minestom.server.instance.Instance;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@link TimeCommand} class is the command which puts the sun where the person looking at the
 * demo wants it.
 * <p>
 * The half of this module which is judged by eye stands or falls with the light, and the light of a
 * world is a different thing at noon than it is at midnight. Waiting twenty minutes for nightfall is
 * not a way to compare two stacks, so the sky is made a knob rather than a schedule. The tick values
 * come from {@link DayTime}, which uses the ones vanilla uses, so a world looked at here is lit the
 * way it is lit anywhere else.
 * </p>
 * <p>
 * <b>Holding the clock is part of the point.</b> A moving sun changes the sky light under the
 * observer while they are still forming an opinion of it, and two servers compared a minute apart
 * are then compared under two different skies. {@code /time pause} freezes the condition; the
 * reading always says whether it is frozen, because a held clock which does not admit it looks
 * exactly like a server that stopped ticking.
 * </p>
 * <p>
 * <b>The instance is the one the demo runs, not the one the sender stands in.</b> This server has
 * exactly one instance and hands it to the command at registration, which is why the console can
 * work this command as well as a player: the operator watching the log is usually the one who
 * started the session for somebody else to fly through, and making them join first to move the sun
 * would buy nothing. Nothing here reads the sender beyond answering it.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
final class TimeCommand extends Command {

    /**
     * The name the command is registered under.
     */
    static final String NAME = "time";

    /**
     * The word which holds the clock where it stands.
     */
    static final String HOLD = "pause";

    /**
     * The word which lets a held clock run again.
     */
    static final String RELEASE = "resume";

    /**
     * The word which introduces a raw tick value.
     */
    static final String SET = "set";

    /**
     * The answer for an instance whose dimension brings no clock.
     * <p>
     * A dimension names the clock it runs on and nothing guarantees that the server knows it, so
     * {@link Instance#defaultClock()} may hand back nothing. Saying so is better than silently
     * accepting a command which then changes no sky at all.
     * </p>
     */
    static final String NO_CLOCK = "this dimension runs no clock, so there is no time of day to read or to set";

    /**
     * The answer for a {@code set} which arrives without the tick it needs.
     * <p>
     * The word is accepted through the free argument alongside the names, so a {@code set} with no
     * number reaches the same place an unknown word does. Answering it with "there is no time of
     * day called 'set'" would be false — the word is right, only its argument is missing.
     * </p>
     */
    static final String MISSING_TICKS = "'" + SET + "' needs the tick to put the clock at, as in '/"
            + NAME + " " + SET + " 6000'. The names " + DayTime.NAMES + " need no number";

    /**
     * Every word the command accepts after its name.
     * <p>
     * Held rather than rebuilt because the suggestion callback asks for it on every keystroke a
     * client sends while the command is being typed, and the answer is the same every time.
     * </p>
     */
    private static final List<String> WORDS = buildWords();

    /**
     * Builds the command for the instance the demo server runs.
     *
     * @param instance the instance whose clock is read and set
     */
    TimeCommand(Instance instance) {
        super(NAME);

        // A free word rather than ArgumentWord#from, on purpose. A restricted argument rejects an
        // unknown name inside Minestom's parser, and the answer a player gets is then a syntax
        // error rather than the list of the names which would have worked. The suggestions below
        // give the completion a restriction would have given, without taking the error message
        // away.
        ArgumentWord name = ArgumentType.Word("name");
        name.setSuggestionCallback((sender, context, suggestion) -> {
            for (String word : words()) {
                suggestion.addEntry(new SuggestionEntry(word));
            }
        });

        ArgumentLong ticks = ArgumentType.Long("ticks");

        setDefaultExecutor((sender, context) -> read(sender, instance));
        addSyntax((sender, context) -> apply(sender, instance, context.get(name)), name);
        addSyntax((sender, context) -> set(sender, instance, context.get(ticks)), ArgumentType.Literal(SET), ticks);
    }

    /**
     * Answers with the reading of the clock.
     *
     * @param sender   the sender who asked
     * @param instance the instance whose clock is read
     */
    private static void read(CommandSender sender, Instance instance) {
        @Nullable Clock clock = clockOf(sender, instance);

        if (clock == null) {
            return;
        }

        sender.sendMessage(Component.text(reading(clock.time(), clock.paused()), NamedTextColor.GRAY));
    }

    /**
     * Returns the clock of the instance, and tells the sender if there is none.
     * <p>
     * A dimension names the clock it runs on and nothing guarantees the server knows it, so every
     * one of the three entry points has to answer the same absence the same way. Doing it here
     * keeps that answer in one place rather than in three.
     * </p>
     *
     * @param sender   the sender to tell if the dimension runs no clock
     * @param instance the instance whose clock is wanted
     * @return the clock, or null if the dimension runs none
     */
    private static @Nullable Clock clockOf(CommandSender sender, Instance instance) {
        @Nullable Clock clock = instance.defaultClock();

        if (clock == null) {
            sender.sendMessage(Component.text(NO_CLOCK, NamedTextColor.RED));
        }

        return clock;
    }

    /**
     * Carries out the word a sender typed after the command.
     *
     * @param sender   the sender who typed it
     * @param instance the instance whose clock is set
     * @param word     the word, which may be a time of day or one of the two clock words
     */
    private static void apply(CommandSender sender, Instance instance, String word) {
        @Nullable Clock clock = clockOf(sender, instance);

        if (clock == null) {
            return;
        }

        // Before the name lookup, because `set` is a word this command knows and the lookup would
        // report it as one it does not. It arrives here only when its tick is missing; with the
        // tick, the syntax below takes it and this branch is never reached.
        if (sets(word)) {
            sender.sendMessage(Component.text(MISSING_TICKS, NamedTextColor.RED));
            return;
        }

        if (holds(word)) {
            clock.pause();
            confirm(sender, clock);
            return;
        }

        if (releases(word)) {
            clock.resume();
            confirm(sender, clock);
            return;
        }

        @Nullable DayTime time = DayTime.byName(word);

        if (time == null) {
            sender.sendMessage(Component.text(rejected(word), NamedTextColor.RED));
            return;
        }

        clock.time(time.ticks());
        confirm(sender, clock);
    }

    /**
     * Puts the clock at a raw tick.
     * <p>
     * The named times are what somebody wants nine times out of ten, and this is the tenth: the
     * light of a specific moment of dusk is not one of four constants, and a tick read out of a
     * reading is the only way back to exactly it.
     * </p>
     *
     * @param sender   the sender who typed the value
     * @param instance the instance whose clock is set
     * @param ticks    the tick to put the clock at
     */
    private static void set(CommandSender sender, Instance instance, long ticks) {
        @Nullable Clock clock = clockOf(sender, instance);

        if (clock == null) {
            return;
        }

        // No range check. A tick beyond a day is a later day and a negative tick is an earlier one;
        // both are folded into an hour by the reading rather than refused here.
        clock.time(ticks);
        confirm(sender, clock);
    }

    /**
     * Answers a change with the reading the clock now carries.
     * <p>
     * The reading is taken from the clock instead of from the value which was just written, so a
     * confirmation cannot claim a state the clock does not hold.
     * </p>
     *
     * @param sender the sender who made the change
     * @param clock  the clock which was changed
     */
    private static void confirm(CommandSender sender, Clock clock) {
        sender.sendMessage(Component.text(changed(clock.time(), clock.paused()), NamedTextColor.YELLOW));
    }

    /**
     * Builds the answer to a plain {@code /time}.
     *
     * @param ticks  the tick the clock stands at
     * @param paused whether the clock is currently held
     * @return the sentence the sender receives
     */
    @Contract(pure = true)
    static String reading(long ticks, boolean paused) {
        return "the clock reads " + DayTime.describe(ticks, paused);
    }

    /**
     * Builds the answer to a change of the clock.
     *
     * @param ticks  the tick the clock now stands at
     * @param paused whether the clock is currently held
     * @return the sentence the sender receives
     */
    @Contract(pure = true)
    static String changed(long ticks, boolean paused) {
        return "the clock now reads " + DayTime.describe(ticks, paused);
    }

    /**
     * Builds the answer to a word this command does not know.
     * <p>
     * A refusal which does not say what would have worked makes the reader guess, and the set of
     * accepted words is four names and three keywords — small enough to print in full every time.
     * </p>
     *
     * @param word the word which was not understood
     * @return the sentence the sender receives
     */
    @Contract(pure = true)
    static String rejected(String word) {
        return "there is no time of day called '" + word + "'. The names are " + DayTime.names()
                + "; '" + SET + " <ticks>' puts the clock at a raw tick, and '" + HOLD + "' and '"
                + RELEASE + "' hold the clock and let it run again";
    }

    /**
     * Returns whether a word asks for the clock to be held.
     *
     * @param word the word a sender typed, in any capitalisation
     * @return true if the clock should be held
     */
    @Contract(pure = true)
    static boolean holds(String word) {
        return HOLD.equalsIgnoreCase(word);
    }

    /**
     * Returns whether a word asks for a held clock to run again.
     *
     * @param word the word a sender typed, in any capitalisation
     * @return true if the clock should run again
     */
    @Contract(pure = true)
    static boolean releases(String word) {
        return RELEASE.equalsIgnoreCase(word);
    }

    /**
     * Returns whether a word introduces a raw tick.
     * <p>
     * Needed because the word reaches the free argument on its own when the tick behind it is
     * missing, and a sender who typed a word this command knows should not be told it does not.
     * </p>
     *
     * @param word the word a sender typed, in any capitalisation
     * @return true if the word asks for a raw tick to follow
     */
    @Contract(pure = true)
    static boolean sets(String word) {
        return SET.equalsIgnoreCase(word);
    }

    /**
     * Lists every word the command accepts after its name, for the completion a client asks for.
     *
     * @return the accepted words, in the order they are offered
     */
    @Contract(pure = true)
    static List<String> words() {
        return WORDS;
    }

    /**
     * Builds the list {@link #WORDS} holds, once.
     *
     * @return the accepted words, in the order they are offered
     */
    private static List<String> buildWords() {
        List<String> words = new ArrayList<>();

        for (DayTime time : DayTime.values()) {
            words.add(time.name().toLowerCase(Locale.ROOT));
        }

        words.add(HOLD);
        words.add(RELEASE);
        words.add(SET);

        return List.copyOf(words);
    }
}
