package me.ladypaladra.thearmorymod.dice;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * When one player's held die fidgets. Kept apart from the server so its rules can be played through in a test.
 *
 * <p>The timing is the one games use for idle fidgets: once the player stands still with a die and nothing else going
 * on, a random wait of {@value #MIN_WAIT} to {@value #MAX_WAIT} seconds, and every time they stop moving the wait
 * starts again, so a fidget never fires the moment a player stops. A fidget is never the same as the one before.</p>
 *
 * <p>A fidget playing is stopped when the player moves or the hand changes item. It is never stopped when the hand
 * starts something else, such as a throw: that action plays its own animation in the same place and takes over, and a
 * stop sent then could cut that animation instead.</p>
 */
final class FidgetClock {

    static final double MIN_WAIT = 12.0;
    static final double MAX_WAIT = 24.0;

    /**
     * The wait between fidgets in the showcase a tester turns on ({@code /dicefidgets}): every fidget in turn, one
     * after another, so all of them can be judged in seconds.
     */
    static final double SHOWCASE_WAIT = 1.0;

    /**
     * One fidget of the held item's animation set, and how long it plays.
     */
    record Fidget(@Nonnull String name, double seconds) {
    }

    enum Act { NONE, PLAY, STOP }

    private boolean calm;
    private long due;
    private long playingUntil = Long.MIN_VALUE;
    @Nullable
    private String item;
    @Nullable
    private String last;
    private boolean showcase;

    /**
     * Turns the showcase on or off. A change starts the wait again, so the new pace applies at once.
     */
    void showcase(boolean on) {
        if (on != showcase) {
            showcase = on;
            calm = false;
        }
    }

    /**
     * Advances the clock to {@code now}.
     *
     * @param heldItem the item in the hand, or null
     * @param fidgets  the held item's fidgets, empty when it has none
     * @param still    standing still on the ground
     * @param free     nothing else running for the hand
     * @return what to do now. After {@link Act#PLAY}, {@link #chosen()} names the fidget
     */
    @Nonnull
    Act tick(long now, @Nullable String heldItem, @Nonnull List<Fidget> fidgets, boolean still, boolean free,
             @Nonnull RandomGenerator rng) {
        boolean playing = now < playingUntil;
        boolean switched = !Objects.equals(heldItem, item);
        item = heldItem;
        if (fidgets.isEmpty() || switched) {
            calm = false;
            return stop(playing);
        }
        if (!still) {
            calm = false;
            return stop(playing);
        }
        if (!free) {
            // The hand's new action has taken the animation over: the fidget is done, and no stop may follow.
            calm = false;
            playingUntil = Long.MIN_VALUE;
            return Act.NONE;
        }
        if (!calm) {
            calm = true;
            due = now + seconds(showcase ? SHOWCASE_WAIT : wait(rng));
        }
        if (playing || now < due) {
            return Act.NONE;
        }
        Fidget next = showcase ? following(fidgets) : pick(fidgets, rng);
        last = next.name();
        playingUntil = now + seconds(next.seconds());
        due = playingUntil + seconds(showcase ? SHOWCASE_WAIT : wait(rng));
        return Act.PLAY;
    }

    /**
     * The fidget the last {@link Act#PLAY} started.
     */
    @Nullable
    String chosen() {
        return last;
    }

    private Act stop(boolean playing) {
        if (!playing) {
            return Act.NONE;
        }
        playingUntil = Long.MIN_VALUE;
        return Act.STOP;
    }

    /**
     * Any fidget but the one before, evenly. A set with only one plays it every time.
     */
    @Nonnull
    private Fidget pick(@Nonnull List<Fidget> fidgets, @Nonnull RandomGenerator rng) {
        if (fidgets.size() == 1) {
            return fidgets.get(0);
        }
        Fidget next;
        do {
            next = fidgets.get(rng.nextInt(fidgets.size()));
        } while (next.name().equals(last));
        return next;
    }

    /**
     * The fidget after the one before, in the set's order, round and round: the showcase shows every one in turn.
     */
    @Nonnull
    private Fidget following(@Nonnull List<Fidget> fidgets) {
        for (int i = 0; i < fidgets.size(); i++) {
            if (fidgets.get(i).name().equals(last)) {
                return fidgets.get((i + 1) % fidgets.size());
            }
        }
        return fidgets.get(0);
    }

    static double wait(@Nonnull RandomGenerator rng) {
        return MIN_WAIT + (MAX_WAIT - MIN_WAIT) * rng.nextDouble();
    }

    private static long seconds(double s) {
        return (long) (s * 1.0E9);
    }
}
