package net.onelitefeather.falco.instance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import space.vectrix.flare.fastutil.Long2ObjectSyncMap;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the one sentence the javadoc of {@code ChunkRegistry#chunks} is not allowed to get wrong: a
 * lookup in {@code Long2ObjectSyncMap} is lock free when it hits the read map and takes a monitor
 * when it does not.
 * <p>
 * That field javadoc used to say "lookups take no lock", full stop, and that reading of the library
 * is false. {@code Long2ObjectSyncMapImpl#getEntry} reads the read map without a lock, and when that
 * returns null while the map is amended it enters {@code synchronized(this.lock)} to consult the
 * dirty map. All of that is a property of a dependency rather than of this repository, which is
 * exactly why prose about it rots in silence: nothing here breaks when flare changes or when a
 * Minestom bump drags in a different version of it. This test is what breaks.
 * </p>
 *
 * <h2>Why the monitor is held through a mapping function rather than taken from the field</h2>
 * <p>
 * The monitor is a private field of the implementation and this project does not use reflection, so
 * the lock is taken the way the library itself hands it out. {@code computeIfAbsent} on a key that is
 * in neither map runs its mapping function <em>inside</em> {@code synchronized(this.lock)}, so a
 * function which parks there holds the map's monitor for as long as it is parked, through public API
 * and nothing else. {@code CountDownLatch#await} parks on a queue rather than on the monitor, so
 * unlike {@code Object#wait} it does not hand the monitor back while it waits.
 * </p>
 *
 * <h2>Why the setup is three lines and why none of them may be dropped</h2>
 * <p>
 * The arrangement is the fragile part of a test like this, because the two internal maps are not
 * observable from outside and a setup which looks like it arranges something can be arranging
 * nothing. Two things have to be true at the moment the probes run: the hit key has to be in the read
 * map, and the map has to be amended so that a miss reaches the slow path.
 * </p>
 * <p>
 * The first needs the {@code put} and then the {@code size()}, because a {@code put} of a new key
 * writes the dirty map and only a promotion moves it across; {@code size()} calls the library's
 * {@code promote()} and is the shortest public way to force one. The second needs nothing at all,
 * and that is worth stating rather than papering over with a second {@code put}: the holder's own
 * {@code computeIfAbsent} runs {@code dirtyLocked()} and sets {@code amended} before it calls the
 * mapping function, so by the time the monitor is held the miss path is armed by construction.
 * </p>
 * <p>
 * There is deliberately no probing of the map before the holder starts. A lookup which misses while
 * the map is amended counts a miss and promotes once the misses reach the size of the dirty map, so
 * a "check the arrangement first" assertion would repair a broken arrangement on its way past and
 * leave a test that passes no matter which setup line is removed. That is not hypothetical: it is
 * what the first version of this class did, and two of the three mutations below survived it. What
 * the probes return is therefore asserted after they return, where reading it costs nothing.
 * </p>
 *
 * <h2>The three mutations this has to fail against</h2>
 * <p>
 * Point the miss probe at the promoted key, and the blocked assertion has to fail. Drop the
 * {@code size()}, so the promoted key stays in the dirty map, and the hit probe has to block. Let the
 * mapping function return without parking, so no monitor is held, and the blocked assertion has to
 * fail again. All three were run.
 * </p>
 *
 * @author TheMeinerLP
 * @version 1.0.0
 * @since 0.4.0
 */
@DisplayName("What a lookup in the chunk map locks")
class ChunkMapLockOnMissTest {

    /**
     * A key which is promoted into the read map before the measurement, so its lookup is a hit.
     */
    private static final long PROMOTED = 40L;

    /**
     * A key which is never written, so a lookup of it finds neither map and is the miss under test.
     */
    private static final long ABSENT = 42L;

    /**
     * A key the holder thread computes, so its mapping function runs while the monitor is held.
     */
    private static final long HOLDER = 43L;

    /**
     * How long a lookup is given to prove that it is blocked. A lookup which takes no lock returns in
     * microseconds, so this window only has to be wide enough that a scheduling hiccup cannot be
     * mistaken for a monitor.
     */
    private static final long BLOCKED_WINDOW_MILLIS = 300L;

    /**
     * How long a lookup is given to prove that it completed.
     */
    private static final long COMPLETION_TIMEOUT_SECONDS = 5L;

    /**
     * Starts a thread which looks the given key up, records what it got and reports when it returned.
     *
     * @param map    the map to look up in
     * @param key    the key to look up
     * @param result where the returned value is recorded
     * @param done   counted down once the lookup has returned
     */
    private static void probe(Long2ObjectSyncMap<Object> map, long key,
                              AtomicReference<Object> result, CountDownLatch done) {
        final Thread thread = new Thread(() -> {
            result.set(map.get(key));
            done.countDown();
        }, "probe-" + key);
        thread.setDaemon(true);
        thread.start();
    }

    @Test
    @Timeout(30)
    @DisplayName("a hit walks past a held monitor and a miss waits for it")
    void testAMissTakesTheMonitorAndAHitDoesNot() throws InterruptedException {
        final Long2ObjectSyncMap<Object> map = Long2ObjectSyncMap.hashmap();
        final Object value = new Object();

        map.put(PROMOTED, value);
        map.size();

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final LongFunction<Object> holdTheMonitor = key -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return value;
        };

        final Thread holder = new Thread(() -> map.computeIfAbsent(HOLDER, holdTheMonitor), "monitor-holder");
        holder.setDaemon(true);
        holder.start();

        final AtomicReference<Object> hitResult = new AtomicReference<>();
        final AtomicReference<Object> missResult = new AtomicReference<>();
        final CountDownLatch hitDone = new CountDownLatch(1);
        final CountDownLatch missDone = new CountDownLatch(1);
        try {
            assertTrue(entered.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the mapping function never ran, so no monitor was ever held and nothing below is a measurement");

            probe(map, PROMOTED, hitResult, hitDone);
            assertTrue(hitDone.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "a lookup of the promoted key blocked behind the monitor, so that key was not in the read map"
                            + " and the arrangement of this test is broken");

            probe(map, ABSENT, missResult, missDone);
            assertFalse(missDone.await(BLOCKED_WINDOW_MILLIS, TimeUnit.MILLISECONDS),
                    "a lookup of an absent key returned while the map's monitor was held, so it took no lock:"
                            + " the field javadoc of ChunkRegistry#chunks may say lookups are lock free again");
        } finally {
            release.countDown();
        }

        holder.join(TimeUnit.SECONDS.toMillis(COMPLETION_TIMEOUT_SECONDS));
        assertTrue(missDone.await(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the blocked lookup never returned after the monitor was released, so a monitor is not what it waited for");

        assertNotNull(hitResult.get(), "the unblocked lookup returned null, so it was a miss which took no lock"
                + " rather than the read map hit this arm claims to be");
        assertSame(value, hitResult.get(), "the unblocked lookup returned a foreign value");
        assertNull(missResult.get(), "the blocked lookup found a value, so the key it probed was not absent");
    }
}
