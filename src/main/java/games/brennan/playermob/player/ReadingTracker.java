package games.brennan.playermob.player;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * One player's live reading bookkeeping — the clocked part of the Reading signal that can't live
 * in the saved {@link ReadingTally}. It reports three things to {@code sink} as tally updates:
 * each book <b>opened</b>, each reading <b>session's length</b> when it ends, and each book that
 * <b>left the inventory</b> (to the floor, the fire, the void — anywhere but a container).
 * Nothing here is persisted.
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
 * <p>Pure: every input is a primitive, so the rules unit-test without a world — see
 * {@code ReadingTrackerTest}.</p>
 */
public final class ReadingTracker {

    /** Longest a held-book session may run — bounds a player idling after closing the screen. */
    static final int MAX_HELD_SECONDS = 600;
    /** Look-direction change (degrees) that means the mouse moved, i.e. no screen is open. */
    static final float LOOK_EPSILON = 0.01F;
    /** Position change (blocks) that means the player moved. */
    static final double MOVE_EPSILON = 0.01;
    /**
     * A swing this soon after opening a book is the open's own: a successful book use makes the
     * client swing its arm, so a swing packet follows the use packet. Only that one is excused.
     */
    static final int OPEN_SWING_TICKS = 2;

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
    private int heldOpenTick;
    private boolean openSwingSeen;

    // Lectern session.
    private boolean lecternOpen;
    private int lecternHash;
    private int lecternSeconds;

    /** Readable books carried at the last inventory sample; {@code null} before the first. */
    private Set<Integer> carried;

    public ReadingTracker(Consumer<UnaryOperator<ReadingTally>> sink) {
        this.sink = sink;
    }

    /** True while a held-book session is open (test/debug aid). */
    boolean isHeldOpen() {
        return heldOpen;
    }

    // ---- Held books -------------------------------------------------------

    /** The player just used the book with content hash {@code hash} from {@code hand} at {@code tick}. */
    public void onOpenHeldBook(int hash, Hand hand, float yaw, float pitch, double x, double y, double z,
                               int tick) {
        endHeld();
        heldOpen = true;
        heldOpenTick = tick;
        openSwingSeen = false;
        heldHash = hash;
        heldHand = hand;
        heldYaw = yaw;
        heldPitch = pitch;
        heldX = x;
        heldY = y;
        heldZ = z;
        heldSeconds = 0;
        sink.accept(t -> t.withOpened(hash));
    }

    /**
     * A deliberate input at {@code tick}: a held-book screen can't be open, so that session is
     * over — except for the single arm swing that accompanies the book's own opening.
     */
    public void onAction(boolean swing, int tick) {
        if (heldOpen && swing && !openSwingSeen && tick - heldOpenTick <= OPEN_SWING_TICKS) {
            openSwingSeen = true;
            return;
        }
        endHeld();
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
            endHeld();
            return;
        }
        heldSeconds++;
    }

    /** Close the held-book session, reporting how long it lasted. */
    private void endHeld() {
        if (!heldOpen) return;
        heldOpen = false;
        int seconds = heldSeconds;
        sink.accept(t -> t.withSession(seconds));
    }

    // ---- Lecterns ---------------------------------------------------------

    /**
     * One second sample of the lectern menu: {@code bookHash} is the open lectern's book, or
     * {@code null} when no lectern menu is open.
     */
    public void sampleLectern(Integer bookHash) {
        if (lecternOpen && (bookHash == null || bookHash != lecternHash)) {
            endLectern();
        }
        if (bookHash == null) return;
        if (!lecternOpen) {
            lecternOpen = true;
            lecternHash = bookHash;
            lecternSeconds = 0;
            int hash = bookHash;
            sink.accept(t -> t.withOpened(hash));
        }
        lecternSeconds++;
    }

    private void endLectern() {
        lecternOpen = false;
        int seconds = lecternSeconds;
        sink.accept(t -> t.withSession(seconds));
    }

    // ---- Inventory ----------------------------------------------------------

    /**
     * One second sample of the readable books the player carries. A book carried last second and
     * gone now left them — unless a container was open, in which case it was put away, which says
     * nothing about the player. Whether it left <em>unread</em> is judged by the tally at scoring
     * time, so reading it later (say, on a lectern) still clears it.
     */
    public void sampleInventory(Set<Integer> nowCarried, boolean containerOpen) {
        if (carried != null && !containerOpen) {
            for (int hash : carried) {
                if (!nowCarried.contains(hash)) {
                    sink.accept(t -> t.withLeft(hash));
                }
            }
        }
        carried = new HashSet<>(nowCarried);
    }

    /** Close every open session — the player died; report what they were in the middle of. */
    public void endAll() {
        endHeld();
        if (lecternOpen) {
            endLectern();
        }
    }
}
