package games.brennan.playermob.player;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingTallyTest {

    private static ReadingTally opened(int n) {
        ReadingTally t = ReadingTally.EMPTY;
        for (int i = 0; i < n; i++) t = t.withOpened(1000 + i);
        return t;
    }

    private static ReadingTally sessions(ReadingTally t, int count, int seconds) {
        for (int i = 0; i < count; i++) t = t.withSession(seconds);
        return t;
    }

    // ---- chance -----------------------------------------------------------

    @Test
    void noBooksScoresZeroOnBoth() {
        assertEquals(0, ReadingTally.EMPTY.chanceScore());
        assertEquals(0, ReadingTally.EMPTY.focusScore());
        assertTrue(ReadingTally.EMPTY.isEmpty());
    }

    @Test
    void chanceRisesWithEveryBookOpened() {
        assertEquals(3, opened(1).chanceScore());   // 10·1/3
        assertEquals(6, opened(3).chanceScore());   // 10·3/5
        assertEquals(8, opened(10).chanceScore());  // 10·10/12
        int prev = -1;
        for (int n = 0; n <= 40; n++) {
            int s = opened(n).chanceScore();
            assertTrue(s >= prev && s <= 10);
            prev = s;
        }
    }

    @Test
    void booksLeftUnreadPullChanceDown() {
        ReadingTally reader = opened(3);
        ReadingTally ditcher = reader;
        for (int i = 0; i < 8; i++) ditcher = ditcher.withLeft(5000 + i);
        assertEquals(8, ditcher.ignored());
        assertTrue(ditcher.chanceScore() < reader.chanceScore(), "skipping books lowers the chance");
        assertEquals(2, ditcher.chanceScore()); // 10·3/13
    }

    @Test
    void chanceRecoversByReadingAgain() {
        ReadingTally t = ReadingTally.EMPTY;
        for (int i = 0; i < 6; i++) t = t.withLeft(5000 + i);
        int low = t.chanceScore();
        for (int i = 0; i < 20; i++) t = t.withOpened(1000 + i);
        assertTrue(t.chanceScore() > low + 3);
    }

    @Test
    void aBookReadLaterIsNotCountedAsIgnored() {
        // Put on a lectern (it left the inventory unread), then read there.
        ReadingTally t = ReadingTally.EMPTY.withLeft(42).withOpened(42);
        assertEquals(0, t.ignored());
        assertEquals(1, t.opened());
    }

    @Test
    void eachBookCountsOnce() {
        ReadingTally once = ReadingTally.EMPTY.withOpened(7);
        assertSame(once, once.withOpened(7));
        ReadingTally left = ReadingTally.EMPTY.withLeft(9);
        assertSame(left, left.withLeft(9));
        assertEquals(1, left.ignored());
    }

    @Test
    void rememberedBooksAreCapped() {
        ReadingTally t = opened(ReadingTally.MAX_REMEMBERED_BOOKS);
        assertSame(t, t.withOpened(-1));
        assertEquals(ReadingTally.MAX_REMEMBERED_BOOKS, t.opened());
    }

    // ---- focus ------------------------------------------------------------

    @Test
    void sessionLengthScoreHitsItsAnchors() {
        assertEquals(0.0, ReadingTally.sessionLengthScore(0), 1e-9);
        assertEquals(0.0, ReadingTally.sessionLengthScore(2), 1e-9);
        assertEquals(5.0, ReadingTally.sessionLengthScore(20), 1e-9);
        assertEquals(10.0, ReadingTally.sessionLengthScore(60), 1e-9);
        assertEquals(10.0, ReadingTally.sessionLengthScore(500), 1e-9);
        assertEquals(7.5, ReadingTally.sessionLengthScore(40), 1e-9);
    }

    @Test
    void focusRisesWithLongReads() {
        assertEquals(3, sessions(ReadingTally.EMPTY, 1, 60).focusScore()); // 10/3
        assertEquals(8, sessions(ReadingTally.EMPTY, 10, 60).focusScore()); // 100/12
        assertTrue(sessions(ReadingTally.EMPTY, 40, 90).focusScore() >= 9);
    }

    @Test
    void skimmingDragsFocusDown() {
        ReadingTally deep = sessions(ReadingTally.EMPTY, 6, 60);
        ReadingTally skimmed = sessions(deep, 12, 1);
        assertTrue(skimmed.focusScore() < deep.focusScore(), "flicking books open and shut lowers focus");
        assertEquals(3, skimmed.focusScore()); // 60/20
        assertEquals(0, sessions(ReadingTally.EMPTY, 30, 2).focusScore());
    }

    @Test
    void anIdleSessionCountsNoMoreThanTheCap() {
        ReadingTally idle = ReadingTally.EMPTY.withSession(10_000);
        assertEquals(ReadingTally.MAX_SESSION_SECONDS, idle.averageSessionSeconds(), 1e-6);
    }

    @Test
    void theTwoScoresAreIndependent() {
        // Opens everything, reads nothing for long: eager but shallow.
        ReadingTally skimmer = sessions(opened(12), 12, 1);
        assertTrue(skimmer.chanceScore() >= 8);
        assertEquals(0, skimmer.focusScore());
        // Rarely opens a book (ditches most), but settles in when they do.
        ReadingTally rare = sessions(opened(2), 6, 90);
        for (int i = 0; i < 10; i++) rare = rare.withLeft(7000 + i);
        assertTrue(rare.chanceScore() <= 2);
        assertTrue(rare.focusScore() >= 7);
    }

    // ---- persistence ------------------------------------------------------

    @Test
    void roundTripsThroughNbt() {
        ReadingTally t = sessions(opened(3).withLeft(9).withLeft(1000), 4, 30);
        ReadingTally back = ReadingTally.load(t.save());
        assertEquals(t.opened(), back.opened());
        assertEquals(t.ignored(), back.ignored());
        assertEquals(t.sessions(), back.sessions());
        assertEquals(t.chanceScore(), back.chanceScore());
        assertEquals(t.focusScore(), back.focusScore());
    }

    @Test
    void missingTagLoadsEmpty() {
        assertTrue(ReadingTally.load(new CompoundTag()).isEmpty());
    }

    @Test
    void lifeRecordCarriesReadingIntoSaveAndEmptiness() {
        PlayerLifeRecord r = PlayerLifeRecord.EMPTY.withReading(t -> t.withOpened(3).withSession(30));
        assertFalse(r.isEmpty(), "a reading-only life must still be saved");
        CompoundTag tag = new CompoundTag();
        r.save(tag);
        PlayerLifeRecord back = PlayerLifeRecord.load(tag);
        assertEquals(1, back.reading().opened());
        assertEquals(1, back.reading().sessions());
        assertEquals(PlayerLifeRecord.EMPTY.toTraits().fightFlight(), back.toTraits().fightFlight());
        assertEquals(PlayerLifeRecord.EMPTY.toTraits().friendliness(), back.toTraits().friendliness());
    }

    @Test
    void oldRecordWithoutReadingLoadsEmpty() {
        CompoundTag tag = new CompoundTag();
        PlayerLifeRecord.EMPTY.save(tag);
        assertFalse(tag.contains(ReadingTally.TAG));
        assertTrue(PlayerLifeRecord.load(tag).reading().isEmpty());
    }
}
