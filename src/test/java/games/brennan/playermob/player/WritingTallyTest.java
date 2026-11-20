package games.brennan.playermob.player;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WritingTallyTest {

    private static WritingTally written(int n) {
        WritingTally t = WritingTally.EMPTY;
        for (int i = 0; i < n; i++) t = t.withWritten();
        return t;
    }

    @Test
    void neverWritingScoresZero() {
        assertEquals(0, WritingTally.EMPTY.score());
        assertTrue(WritingTally.EMPTY.isEmpty());
    }

    @Test
    void writingRaisesIt() {
        assertEquals(3, written(1).score());          // 10·1/3
        assertEquals(6, written(3).score());          // 10·3/5
        assertTrue(written(30).score() >= 9);
    }

    @Test
    void signingCountsALittleExtra() {
        WritingTally signedOne = WritingTally.EMPTY.withSigned();
        assertEquals(1, signedOne.written());
        assertTrue(signedOne.raw() > written(1).raw());
        WritingTally author = WritingTally.EMPTY;
        for (int i = 0; i < 5; i++) author = author.withSigned();
        assertEquals(8, author.score());              // 7.5/9.5
    }

    @Test
    void blankQuillsLeftBehindBarelyMatter() {
        WritingTally writer = written(3);
        WritingTally careless = writer.withBlankLeft(20);
        assertTrue(careless.score() < writer.score(), "it does pull it down");
        assertEquals(4, careless.score());           // 30/7 — still a writer
        assertEquals(writer.score(), writer.withBlankLeft(1).score(), "one stray quill changes nothing");
        assertEquals(0, WritingTally.EMPTY.withBlankLeft(50).score());
    }

    @Test
    void nonPositiveBlankCountsAreIgnored() {
        assertSame(WritingTally.EMPTY, WritingTally.EMPTY.withBlankLeft(0));
        assertSame(WritingTally.EMPTY, WritingTally.EMPTY.withBlankLeft(-3));
    }

    @Test
    void roundTripsThroughNbtAndTheLifeRecord() {
        WritingTally t = written(2).withSigned().withBlankLeft(4);
        WritingTally back = WritingTally.load(t.save());
        assertEquals(t.written(), back.written());
        assertEquals(t.signed(), back.signed());
        assertEquals(t.blankLeft(), back.blankLeft());
        assertTrue(WritingTally.load(new CompoundTag()).isEmpty());

        PlayerLifeRecord r = PlayerLifeRecord.EMPTY.withWriting(w -> w.withWritten());
        assertFalse(r.isEmpty(), "a writing-only life must still be saved");
        CompoundTag tag = new CompoundTag();
        r.save(tag);
        assertEquals(1, PlayerLifeRecord.load(tag).writing().written());
        CompoundTag empty = new CompoundTag();
        PlayerLifeRecord.EMPTY.save(empty);
        assertFalse(empty.contains(WritingTally.TAG));
    }
}
