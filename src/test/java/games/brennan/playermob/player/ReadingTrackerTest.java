package games.brennan.playermob.player;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

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
        tracker.onOpenHeldBook(hash, MAIN, 10F, 20F, 1, 64, 1, 100);
    }

    private void still(int hash, int seconds) {
        for (int i = 0; i < seconds; i++) {
            tracker.sampleHeld(hash, MAIN, false, 10F, 20F, 1, 64, 1, true);
        }
    }

    // ---- held books -------------------------------------------------------

    @Test
    void openingABookCountsItAsOpenedAtOnce() {
        open(5);
        assertEquals(1, tally.opened());
        assertEquals(0, tally.sessions(), "the session only reports when it ends");
    }

    @Test
    void aSessionReportsItsLengthWhenItEnds() {
        open(5);
        still(5, 30);
        tracker.onAction(false, 900);
        assertEquals(1, tally.sessions());
        assertEquals(30.0, tally.averageSessionSeconds(), 1e-6);
    }

    @Test
    void aFlickShutIsASkim() {
        open(5);
        tracker.onAction(false, 140); // closed before the first one-second sample
        assertEquals(1, tally.sessions());
        assertEquals(0.0, tally.averageSessionSeconds(), 1e-6);
        assertEquals(0, tally.focusScore());
    }

    @Test
    void lookingAroundEndsTheSession() {
        open(5);
        still(5, 2);
        tracker.sampleHeld(5, MAIN, false, 11F, 20F, 1, 64, 1, true); // mouse moved
        assertFalse(tracker.isHeldOpen());
        assertEquals(2.0, tally.averageSessionSeconds(), 1e-6);
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
        assertEquals(4, tally.sessions());
    }

    @Test
    void reopeningEndsThePreviousSessionFirst() {
        open(5);
        still(5, 10);
        open(6);
        assertEquals(1, tally.sessions());
        assertEquals(2, tally.opened());
    }

    @Test
    void heldSessionIsCapped() {
        open(5);
        still(5, ReadingTracker.MAX_HELD_SECONDS + 100);
        assertFalse(tracker.isHeldOpen());
        assertEquals(1, tally.sessions());
        assertEquals(ReadingTally.MAX_SESSION_SECONDS, tally.averageSessionSeconds(), 1e-6);
    }

    @Test
    void theSwingThatOpensTheBookDoesNotCloseIt() {
        open(5);
        tracker.onAction(true, 100);
        assertTrue(tracker.isHeldOpen());
        still(5, 3);
        assertTrue(tracker.isHeldOpen());
    }

    @Test
    void onlyOneOpeningSwingIsExcused() {
        open(5);
        tracker.onAction(true, 100);
        tracker.onAction(true, 101);
        assertFalse(tracker.isHeldOpen());
    }

    @Test
    void aLaterSwingClosesTheBook() {
        open(5);
        still(5, 2);
        tracker.onAction(true, 100 + ReadingTracker.OPEN_SWING_TICKS + 1);
        assertFalse(tracker.isHeldOpen());
    }

    // ---- lecterns ---------------------------------------------------------

    @Test
    void lecternOpensOnceAndReportsTheSessionOnClose() {
        for (int i = 0; i < 25; i++) tracker.sampleLectern(77);
        assertEquals(1, tally.opened());
        assertEquals(0, tally.sessions());
        tracker.sampleLectern(null);
        assertEquals(1, tally.sessions());
        assertEquals(25.0, tally.averageSessionSeconds(), 1e-6);
        for (int i = 0; i < 5; i++) tracker.sampleLectern(77);
        tracker.sampleLectern(null);
        assertEquals(1, tally.opened(), "the same book again isn't a new book");
        assertEquals(2, tally.sessions());
    }

    @Test
    void switchingLecternBooksSplitsTheSession() {
        for (int i = 0; i < 4; i++) tracker.sampleLectern(1);
        for (int i = 0; i < 4; i++) tracker.sampleLectern(2);
        assertEquals(2, tally.opened());
        assertEquals(1, tally.sessions());
    }

    // ---- inventory ----------------------------------------------------------

    @Test
    void aBookThatLeavesUnreadIsIgnored() {
        tracker.sampleInventory(Set.of(1, 2), false);
        tracker.sampleInventory(Set.of(2), false); // dropped / burned
        assertEquals(1, tally.ignored());
    }

    @Test
    void puttingABookAwayIsNeutral() {
        tracker.sampleInventory(Set.of(1), false);
        tracker.sampleInventory(Set.of(), true); // into a chest
        assertEquals(0, tally.ignored());
        assertTrue(tally.isEmpty());
    }

    @Test
    void aBookReadThenDroppedIsNotIgnored() {
        tracker.sampleInventory(Set.of(3), false);
        open(3);
        tracker.onAction(false, 200);
        tracker.sampleInventory(Set.of(), false);
        assertEquals(0, tally.ignored());
    }

    @Test
    void theFirstSampleOnlyRemembers() {
        tracker.sampleInventory(Set.of(1, 2, 3), false);
        assertTrue(tally.isEmpty());
    }

    @Test
    void deathClosesOpenSessions() {
        open(5);
        still(5, 12);
        for (int i = 0; i < 3; i++) tracker.sampleLectern(9);
        tracker.endAll();
        assertEquals(2, tally.sessions());
    }
}
