package games.brennan.playermob.player;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * One player's live reading sessions — the clocked part of the Reading signal that can't live
 * in the saved {@link ReadingTally}. Each second of reading and each finished book is handed to
 * {@code sink} as a tally update; nothing here is persisted.
 *
 * <p><b>How the server knows someone is reading.</b> Book screens are client-side, so this works
 * from what the server can see, without a packet the client could forge:</p>
 * <ul>
 *   <li><b>Lectern</b> — a lectern opens a real server-side menu, so "the lectern menu is open"
 *       is authoritative. {@link ReadingHooks} samples it once a second.</li>
 *   <li><b>Held book</b> (written book, book &amp; quill) — the server sees the book being
 *       <em>used</em> but never sees the screen close. A session opens on use and stays open
 *       while the player holds perfectly still: the same book in the same hand, the same look
 *       direction (mouse look is frozen while a screen is open), and — off a train — the same
 *       position. Any counted input ({@link #onAction}) ends it, and a session can't outlast
 *       {@link #MAX_HELD_SECONDS}.</li>
 * </ul>
 *
 * <p>A session that lasts {@link #MIN_BOOK_SECONDS} counts its book once (the tally ignores a
 * book this life already read). Pure: every input is a primitive, so the rules unit-test
 * without a world — see {@code ReadingTrackerTest}.</p>
 */
public final class ReadingTracker {

    /** Seconds one session must last before its book counts as read. */
    static final int MIN_BOOK_SECONDS = 8;
    /** Longest a held-book session may run — bounds a player idling after closing the screen. */
    static final int MAX_HELD_SECONDS = 600;
    /** Look-direction change (degrees) that means the mouse moved, i.e. no screen is open. */
    static final float LOOK_EPSILON = 0.01F;
    /** Position change (blocks) that means the player moved. */
    static final double MOVE_EPSILON = 0.01;

    /** Where the player's book is, as seen in one sample. */
    public enum Hand { MAIN, OFF }

    private final Consumer<UnaryOperator<ReadingTally>> sink;

    // Held-book session.
    private boolean heldOpen;
    private int heldHash;
    private Hand heldHand;
    private float heldYaw, heldPitch;
    private double heldX, heldY, heldZ;
    private int heldSeconds;
    private boolean heldCredited;

    // Lectern session.
    private boolean lecternOpen;
    private int lecternHash;
    private int lecternSeconds;
    private boolean lecternCredited;

    public ReadingTracker(Consumer<UnaryOperator<ReadingTally>> sink) {
        this.sink = sink;
    }

    /** True while a held-book session is open (test/debug aid). */
    boolean isHeldOpen() {
        return heldOpen;
    }

    // ---- Held books -------------------------------------------------------

    /** The player just used the book with content hash {@code hash} from {@code hand}. */
    public void onOpenHeldBook(int hash, Hand hand, float yaw, float pitch, double x, double y, double z) {
        heldOpen = true;
        heldHash = hash;
        heldHand = hand;
        heldYaw = yaw;
        heldPitch = pitch;
        heldX = x;
        heldY = y;
        heldZ = z;
        heldSeconds = 0;
        heldCredited = false;
    }

    /** Any deliberate input: a held-book screen can't be open, so that session is over. */
    public void onAction() {
        heldOpen = false;
    }

    /**
     * One second of the held-book session. {@code bookHashInHand} is the hash of the readable book
     * now in the session's hand ({@code null} for none); {@code checkPosition} is false on a moving
     * train, where the player's world position drifts while they stand still.
     */
    public void sampleHeld(Integer bookHashInHand, Hand hand, boolean otherMenuOpen, float yaw, float pitch,
                           double x, double y, double z, boolean checkPosition) {
        if (!heldOpen) return;
        boolean still = hand == heldHand
            && bookHashInHand != null && bookHashInHand == heldHash
            && !otherMenuOpen
            && Math.abs(yaw - heldYaw) <= LOOK_EPSILON
            && Math.abs(pitch - heldPitch) <= LOOK_EPSILON
            && (!checkPosition || (Math.abs(x - heldX) <= MOVE_EPSILON
                && Math.abs(y - heldY) <= MOVE_EPSILON && Math.abs(z - heldZ) <= MOVE_EPSILON));
        if (!still || heldSeconds >= MAX_HELD_SECONDS) {
            heldOpen = false;
            return;
        }
        heldSeconds++;
        sink.accept(t -> t.withSeconds(1F));
        if (!heldCredited && heldSeconds >= MIN_BOOK_SECONDS) {
            heldCredited = true;
            int hash = heldHash;
            sink.accept(t -> t.withBook(hash));
        }
    }

    // ---- Lecterns ---------------------------------------------------------

    /**
     * One second sample of the lectern menu: {@code bookHash} is the open lectern's book, or
     * {@code null} when no lectern menu is open.
     */
    public void sampleLectern(Integer bookHash) {
        if (bookHash == null) {
            lecternOpen = false;
            return;
        }
        if (!lecternOpen || bookHash != lecternHash) {
            lecternOpen = true;
            lecternHash = bookHash;
            lecternSeconds = 0;
            lecternCredited = false;
        }
        lecternSeconds++;
        sink.accept(t -> t.withSeconds(1F));
        if (!lecternCredited && lecternSeconds >= MIN_BOOK_SECONDS) {
            lecternCredited = true;
            int hash = lecternHash;
            sink.accept(t -> t.withBook(hash));
        }
    }
}
