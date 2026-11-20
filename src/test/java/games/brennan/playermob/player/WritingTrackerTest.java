package games.brennan.playermob.player;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WritingTrackerTest {

    private WritingTally tally;
    private WritingTracker tracker;

    @BeforeEach
    void setUp() {
        tally = WritingTally.EMPTY;
        tracker = new WritingTracker(u -> tally = u.apply(tally));
    }

    @Test
    void savingNewTextCountsAsWriting() {
        tracker.onBookSaved(List.of(), List.of("Dear diary"));
        tracker.onBookSaved(List.of("Dear diary"), List.of("Dear diary", "today I"));
        assertEquals(2, tally.written());
    }

    @Test
    void savingUnchangedOrBlankTextDoesNot() {
        tracker.onBookSaved(List.of("same"), List.of("same"));
        tracker.onBookSaved(List.of(), List.of("", "  "));
        assertTrue(tally.isEmpty());
    }

    @Test
    void signingCountsAsWritingAndASignature() {
        tracker.onSigned(false);
        assertEquals(1, tally.written());
        assertEquals(1, tally.signed());
    }

    @Test
    void blankQuillsThatLeaveAreCounted() {
        tracker.sampleBlank(3, false);
        tracker.sampleBlank(1, false); // dropped two
        assertEquals(2, tally.blankLeft());
    }

    @Test
    void puttingBlankQuillsAwayIsNeutral() {
        tracker.sampleBlank(3, false);
        tracker.sampleBlank(0, true); // into a chest
        assertEquals(0, tally.blankLeft());
    }

    @Test
    void aQuillWrittenInHasNotLeft() {
        tracker.sampleBlank(2, false);
        tracker.onBookSaved(List.of(), List.of("first words")); // one blank becomes a draft
        tracker.sampleBlank(1, false);
        assertEquals(0, tally.blankLeft());
        assertEquals(1, tally.written());
    }

    @Test
    void aBlankBookSignedHasNotLeftEither() {
        tracker.sampleBlank(1, false);
        tracker.onSigned(true);
        tracker.sampleBlank(0, false);
        assertEquals(0, tally.blankLeft());
    }

    @Test
    void theFirstSampleOnlyRemembers() {
        tracker.sampleBlank(5, false);
        assertTrue(tally.isEmpty());
    }
}
