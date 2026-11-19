package games.brennan.playermob.player;

import games.brennan.playermob.entity.DispositionTraits;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic oracle for {@link TamingTally} — the Taming hobby score of one life. */
class TamingTallyTest {

    private static TamingTally of(int tames, int breeds, int feeds) {
        TamingTally t = TamingTally.EMPTY;
        for (int i = 0; i < tames; i++) t = t.withTame();
        for (int i = 0; i < breeds; i++) t = t.withBreed();
        for (int i = 0; i < feeds; i++) t = t.withFeed();
        return t;
    }

    @Test
    void noEvidenceScoresZero() {
        assertTrue(TamingTally.EMPTY.isEmpty());
        assertEquals(0, TamingTally.EMPTY.score());
    }

    @Test
    void twoTamesScoreFive() {
        assertEquals(5, of(2, 0, 0).score());
    }

    @Test
    void oneBreedingPairScoresTwo() {
        assertEquals(2, of(0, 1, 2).score()); // 1.5 points
    }

    @Test
    void tamingWeighsMoreThanFeeding() {
        assertTrue(of(1, 0, 0).points() > of(0, 1, 0).points());
        assertTrue(of(0, 1, 0).points() > of(0, 0, 1).points());
        assertEquals(12.0, TamingTally.TAME_POINTS / TamingTally.FEED_POINTS, 1e-9);
    }

    @Test
    void scoreSaturatesBelowOrAtTen() {
        assertEquals(8, of(4, 6, 20).score());
        int huge = of(100, 100, 1000).score();
        assertEquals(DispositionTraits.MAX, huge);
        assertTrue(of(0, 0, 4).score() < of(0, 0, 40).score());
    }

    @Test
    void creditsAreImmutable() {
        TamingTally a = TamingTally.EMPTY;
        TamingTally b = a.withTame();
        assertTrue(a.isEmpty());
        assertEquals(1, b.tames());
    }

    @Test
    void nbtRoundTrip() {
        TamingTally t = of(3, 2, 7);
        TamingTally back = TamingTally.load(t.save());
        assertEquals(3, back.tames());
        assertEquals(2, back.breeds());
        assertEquals(7, back.feeds());
    }

    @Test
    void missingTagLoadsEmpty() {
        assertSame(TamingTally.EMPTY, TamingTally.load(new CompoundTag()));
    }

    @Test
    void lifeRecordCarriesTamingIntoTraitsAndNbt() {
        PlayerLifeRecord r = PlayerLifeRecord.EMPTY.withTaming(t -> of(2, 0, 0));
        assertFalse(r.isEmpty());
        assertEquals(5, r.toTraits().taming());
        CompoundTag tag = new CompoundTag();
        r.save(tag);
        assertEquals(2, PlayerLifeRecord.load(tag).taming().tames());
        // A pre-taming save has no compound ⇒ Taming 0.
        CompoundTag old = new CompoundTag();
        PlayerLifeRecord.EMPTY.save(old);
        assertFalse(old.contains(TamingTally.TAG));
        assertEquals(0, PlayerLifeRecord.load(old).toTraits().taming());
    }

    @Test
    void tamingLeavesTheOtherTraitsAlone() {
        DispositionTraits plain = PlayerLifeRecord.EMPTY.toTraits();
        DispositionTraits tamer = PlayerLifeRecord.EMPTY.withTaming(t -> of(5, 5, 5)).toTraits();
        assertEquals(plain.fightFlight(), tamer.fightFlight());
        assertEquals(plain.friendliness(), tamer.friendliness());
        assertEquals(plain.reactionSpeed(), tamer.reactionSpeed());
    }
}
