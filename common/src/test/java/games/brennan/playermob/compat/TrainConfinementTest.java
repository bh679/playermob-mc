package games.brennan.playermob.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the {@link TrainConfinement} gate. The interesting
 * behaviour is the confinement rule — "unconfined unless actually on a train,
 * then same-train only" — which is independent of any real {@link Entity}, so we
 * drive it with a {@link FakeTrain} and pass {@code null} entities (the fake
 * never dereferences them). The live Dungeon-Train resolution in {@code
 * DungeonTrainEnvironment} needs a running server and is covered by the in-game
 * Gate 2 smoke test instead.
 */
class TrainConfinementTest {

    private static final BlockPos POS = new BlockPos(0, 0, 0);

    /** Reset the global holder so one test's install never leaks into the next. */
    @AfterEach
    void resetEnvironment() {
        TrainConfinement.install(TrainEnvironment.ABSENT);
    }

    @Test
    void absentEnvironmentAllowsEverything() {
        TrainConfinement.install(TrainEnvironment.ABSENT);
        assertFalse(TrainConfinement.isConfined(null), "ABSENT means never confined");
        assertTrue(TrainConfinement.allowsTarget(null, (Entity) null), "ABSENT allows any entity target");
        assertTrue(TrainConfinement.allowsTarget(null, POS), "ABSENT allows any block target");
    }

    @Test
    void notOnTrainIsUnconfinedEvenWhenCandidateIsOffTrain() {
        // selfOnTrain=false: the mob isn't on a train, so confinement must not
        // engage — every candidate is allowed regardless of sameTrain.
        TrainConfinement.install(new FakeTrain(false, false));
        assertFalse(TrainConfinement.isConfined(null));
        assertTrue(TrainConfinement.allowsTarget(null, (Entity) null));
        assertTrue(TrainConfinement.allowsTarget(null, POS));
    }

    @Test
    void onTrainBlocksOffTrainTargets() {
        // selfOnTrain=true, candidate not on the same train → blocked.
        TrainConfinement.install(new FakeTrain(true, false));
        assertTrue(TrainConfinement.isConfined(null));
        assertFalse(TrainConfinement.allowsTarget(null, (Entity) null), "off-train entity is blocked");
        assertFalse(TrainConfinement.allowsTarget(null, POS), "off-train block is blocked");
    }

    @Test
    void onTrainAllowsSameTrainTargets() {
        // selfOnTrain=true, candidate on the same train → allowed.
        TrainConfinement.install(new FakeTrain(true, true));
        assertTrue(TrainConfinement.isConfined(null));
        assertTrue(TrainConfinement.allowsTarget(null, (Entity) null), "same-train entity is allowed");
        assertTrue(TrainConfinement.allowsTarget(null, POS), "same-train block is allowed");
    }

    @Test
    void absentEnvironmentHasNoCarriage() {
        TrainConfinement.install(TrainEnvironment.ABSENT);
        assertEquals(TrainConfinement.NO_CARRIAGE, TrainConfinement.carriageIndex(null),
            "ABSENT reports no carriage");
        assertNull(TrainConfinement.nextCarriageTarget(null, -1),
            "ABSENT has no next-carriage waypoint");
    }

    @Test
    void boardingDirectionMarchesTowardZero() {
        // Negative side marches up (+1) toward 0; the centre and the positive side
        // march down (-1) through 0. The direction is latched once and kept, so a
        // mob that boarded negative keeps +1 even after its index turns positive.
        assertEquals(+1, TrainConfinement.boardingDirection(-5), "deep negative → up");
        assertEquals(+1, TrainConfinement.boardingDirection(-1), "just below 0 → up");
        assertEquals(-1, TrainConfinement.boardingDirection(0), "at 0 → down (default)");
        assertEquals(-1, TrainConfinement.boardingDirection(1), "just above 0 → down");
        assertEquals(-1, TrainConfinement.boardingDirection(5), "deep positive → down");
    }

    /**
     * Configurable stand-in for a train mod's environment. The carriage-geometry
     * methods are stubbed (NO_CARRIAGE / null) — their real behaviour is DT-backed
     * geometry covered by the in-game Gate 2 test, not unit-testable here.
     */
    private record FakeTrain(boolean selfOnTrain, boolean sameTrainResult) implements TrainEnvironment {
        @Override public boolean isOnTrain(Entity self) { return selfOnTrain; }
        @Override public boolean sameTrain(Entity self, Entity candidate) { return sameTrainResult; }
        @Override public boolean sameTrain(Entity self, BlockPos candidatePos) { return sameTrainResult; }
        @Override public int carriageIndex(Entity self) { return NO_CARRIAGE; }
        @Override public Vec3 nextCarriageTarget(Entity self, int dir) { return null; }
    }
}
