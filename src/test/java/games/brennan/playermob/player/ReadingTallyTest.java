package games.brennan.playermob.player;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingTallyTest {

    private static ReadingTally minutes(double m) {
        return ReadingTally.EMPTY.withSeconds((float) (m * 60.0));
    }

    private static ReadingTally books(int n) {
        ReadingTally t = ReadingTally.EMPTY;
        for (int i = 0; i < n; i++) {
            t = t.withBook(1000 + i);
        }
        return t;
    }

    @Test
    void noReadingScoresZero() {
        assertEquals(0, ReadingTally.EMPTY.score());
        assertTrue(ReadingTally.EMPTY.isEmpty());
    }

    @Test
    void oneShortReadIsALowScoreNotNeutral() {
        ReadingTally t = minutes(0.5).withBook(1);
        assertTrue(t.score() >= 0 && t.score() <= 2, "got " + t.score());
    }

    @Test
    void scoreRisesWithTimeAndBooksAndSaturates() {
        int prev = -1;
        for (double m : new double[] {0, 5, 15, 30, 60, 120, 600}) {
            int s = minutes(m).score();
            assertTrue(s >= prev, "monotone in time");
            prev = s;
        }
        assertTrue(minutes(10_000).withSeconds(1F).score() <= 10);
        // A very bookish life reaches the top of the scale.
        ReadingTally avid = minutes(300);
        for (int i = 0; i < 40; i++) avid = avid.withBook(i);
        assertEquals(10, avid.score());
    }

    @Test
    void curvesHitTheirDocumentedPoints() {
        assertEquals(10.0 * (1 - Math.exp(-1)), minutes(ReadingTally.MINUTES_SCALE).timeScore(), 1e-6);
        assertEquals(10.0 * (1 - Math.exp(-1)), books((int) ReadingTally.BOOKS_SCALE).bookScore(), 1e-6);
        // Half an hour and six books: both curves at ~6.3 ⇒ 6.
        ReadingTally t = minutes(30);
        for (int i = 0; i < 6; i++) t = t.withBook(i);
        assertEquals(6, t.score());
    }

    @Test
    void theSameBookCountsOnce() {
        ReadingTally once = ReadingTally.EMPTY.withBook(42);
        assertSame(once, once.withBook(42));
        assertEquals(1, once.books());
        assertTrue(once.hasRead(42));
        assertFalse(once.hasRead(43));
    }

    @Test
    void bookCountStopsAtTheMemoryCap() {
        ReadingTally t = books(ReadingTally.MAX_REMEMBERED_BOOKS);
        assertEquals(ReadingTally.MAX_REMEMBERED_BOOKS, t.books());
        assertSame(t, t.withBook(-1));
    }

    @Test
    void nonPositiveSecondsAreIgnored() {
        assertSame(ReadingTally.EMPTY, ReadingTally.EMPTY.withSeconds(0F));
        assertSame(ReadingTally.EMPTY, ReadingTally.EMPTY.withSeconds(-5F));
    }

    @Test
    void roundTripsThroughNbt() {
        ReadingTally t = minutes(12.5).withBook(7).withBook(9);
        ReadingTally back = ReadingTally.load(t.save());
        assertEquals(t.seconds(), back.seconds(), 1e-6);
        assertEquals(2, back.books());
        assertTrue(back.hasRead(7) && back.hasRead(9));
        assertEquals(t.score(), back.score());
    }

    @Test
    void missingTagLoadsEmpty() {
        ReadingTally back = ReadingTally.load(new CompoundTag());
        assertTrue(back.isEmpty());
        assertEquals(0, back.score());
    }

    @Test
    void lifeRecordCarriesReadingIntoSaveAndEmptiness() {
        PlayerLifeRecord r = PlayerLifeRecord.EMPTY.withReading(t -> t.withSeconds(60F).withBook(3));
        assertFalse(r.isEmpty(), "a reading-only life must still be saved");
        CompoundTag tag = new CompoundTag();
        r.save(tag);
        PlayerLifeRecord back = PlayerLifeRecord.load(tag);
        assertEquals(1, back.reading().books());
        assertEquals(60F, back.reading().seconds(), 1e-6);
        // Reading never leaks into the disposition traits.
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
