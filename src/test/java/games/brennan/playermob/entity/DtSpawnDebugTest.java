package games.brennan.playermob.entity;

import games.brennan.playermob.entity.DtSpawnDebug.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic oracle for {@link DtSpawnDebug#classify} — the (isEcho × companion) → {@link Kind}
 * mapping that picks each DT-spawn message's colour. No world.
 */
class DtSpawnDebugTest {

    @Test
    void classifyMapsAllFourOutcomes() {
        assertEquals(Kind.PLAIN, DtSpawnDebug.classify(false, false));
        assertEquals(Kind.FRIEND, DtSpawnDebug.classify(false, true));
        assertEquals(Kind.ECHO, DtSpawnDebug.classify(true, false));
        assertEquals(Kind.ECHO_FRIEND, DtSpawnDebug.classify(true, true));
    }
}
