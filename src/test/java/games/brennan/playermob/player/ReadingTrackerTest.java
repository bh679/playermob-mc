package games.brennan.playermob.player;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static games.brennan.playermob.player.ReadingTracker.Hand.MAIN;
import static games.brennan.playermob.player.ReadingTracker.Hand.OFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingTrackerTest {

    private ReadingTally tally;
    private ReadingTracker tracker;

    @BeforeEach
    void setUp() {
        tally = ReadingTally.EMPTY;
        tracker = new ReadingTracker(update -> tally = update.apply(tally));
    }

    private void open(int hash) {
        tracker.onOpenHeldBook(hash, MAIN, 10F, 20F, 1, 64, 1);
    }

    private void still(int hash, int seconds) {
        for (int i = 0; i < seconds; i++) {
            tracker.sampleHeld(hash, MAIN, false, 10F, 20F, 1, 64, 1, true);
        }
    }

    @Test
    void heldBookAccruesWhileThePlayerHoldsStill() {
        open(5);
        still(5, 3);
        assertEquals(3F, tally.seconds(), 1e-6);
        assertEquals(0, tally.books(), "too short to count the book");
    }

    @Test
    void bookCountsOnceAfterTheMinimum() {
        open(5);
        still(5, ReadingTracker.MIN_BOOK_SECONDS + 5);
        assertEquals(1, tally.books());
        assertEquals(ReadingTracker.MIN_BOOK_SECONDS + 5, tally.seconds(), 1e-6);
    }

    @Test
    void lookingAroundEndsTheSession() {
        open(5);
        still(5, 2);
        tracker.sampleHeld(5, MAIN, false, 11F, 20F, 1, 64, 1, true); // mouse moved
        assertFalse(tracker.isHeldOpen());
        still(5, 10);
        assertEquals(2F, tally.seconds(), 1e-6);
    }

    @Test
    void movingEndsTheSessionOffTrainButNotOnOne() {
        open(5);
        tracker.sampleHeld(5, MAIN, false, 10F, 20F, 2, 64, 1, true);
        assertFalse(tracker.isHeldOpen());

        open(5);
        tracker.sampleHeld(5, MAIN, false, 10F, 20F, 9, 64, 1, false); // carried by the train
        assertTrue(tracker.isHeldOpen());
    }

    @Test
    void anyActionEndsTheSession() {
        open(5);
        still(5, 1);
        tracker.onAction();
        still(5, 5);
        assertEquals(1F, tally.seconds(), 1e-6);
    }

    @Test
    void swappingTheBookOrHandOrOpeningAMenuEndsTheSession() {
        open(5);
        tracker.sampleHeld(6, MAIN, false, 10F, 20F, 1, 64, 1, true);
        assertFalse(tracker.isHeldOpen());
        open(5);
        tracker.sampleHeld(5, OFF, false, 10F, 20F, 1, 64, 1, true);
        assertFalse(tracker.isHeldOpen());
        open(5);
        tracker.sampleHeld(null, null, false, 10F, 20F, 1, 64, 1, true);
        assertFalse(tracker.isHeldOpen());
        open(5);
        tracker.sampleHeld(5, MAIN, true, 10F, 20F, 1, 64, 1, true);
        assertFalse(tracker.isHeldOpen());
        assertEquals(0F, tally.seconds(), 1e-6);
    }

    @Test
    void heldSessionIsCapped() {
        open(5);
        still(5, ReadingTracker.MAX_HELD_SECONDS + 100);
        assertEquals(ReadingTracker.MAX_HELD_SECONDS, tally.seconds(), 1e-6);
    }

    @Test
    void noSessionNoCredit() {
        still(5, 20);
        assertTrue(tally.isEmpty());
    }

    @Test
    void lecternAccruesAndCountsTheBookOnce() {
        for (int i = 0; i < ReadingTracker.MIN_BOOK_SECONDS * 2; i++) tracker.sampleLectern(77);
        assertEquals(1, tally.books());
        tracker.sampleLectern(null);
        for (int i = 0; i < ReadingTracker.MIN_BOOK_SECONDS; i++) tracker.sampleLectern(77);
        assertEquals(1, tally.books(), "re-reading the same book doesn't add one");
        assertEquals(ReadingTracker.MIN_BOOK_SECONDS * 3, tally.seconds(), 1e-6);
    }

    @Test
    void lecternShortVisitsDoNotCountABook() {
        for (int r = 0; r < 5; r++) {
            for (int i = 0; i < ReadingTracker.MIN_BOOK_SECONDS - 1; i++) tracker.sampleLectern(88);
            tracker.sampleLectern(null);
        }
        assertEquals(0, tally.books());
    }
}
