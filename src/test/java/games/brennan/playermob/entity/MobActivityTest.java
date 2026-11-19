package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static games.brennan.playermob.entity.MobActivity.CONTAINER;
import static games.brennan.playermob.entity.MobActivity.INVENTORY;
import static games.brennan.playermob.entity.MobActivity.NONE;
import static games.brennan.playermob.entity.MobActivity.READING;
import static games.brennan.playermob.entity.MobActivity.resolve;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic oracle for {@link MobActivity} — which look wins each tick, and the total decode of
 * the synced wire value. Primitives only, so no Minecraft bootstrap is needed.
 */
class MobActivityTest {

    @Test
    void nothingToShowAtRest() {
        assertEquals(NONE, resolve(true, true, false, false, 0, false, false));
    }

    @Test
    void openContainerWinsOverEverything() {
        assertEquals(CONTAINER, resolve(true, true, true, false, 0, false, false));
        assertEquals(CONTAINER, resolve(true, true, true, true, 20, true, false)); // even mid-fight
    }

    @Test
    void gearChangePulseAndIdleCheckShowInventory() {
        assertEquals(INVENTORY, resolve(true, true, false, false, 1, false, false));
        assertEquals(INVENTORY, resolve(true, true, false, false, 0, true, false));
    }

    @Test
    void combatSuppressesTheInventoryLook() {
        assertEquals(NONE, resolve(true, true, false, true, 30, false, false));
        assertEquals(NONE, resolve(true, true, false, true, 0, true, false));
    }

    @Test
    void disabledOrDeadShowsNothing() {
        assertEquals(NONE, resolve(false, true, true, false, 30, true, false));
        assertEquals(NONE, resolve(true, false, true, false, 30, true, false));
    }

    @Test
    void wireValueDecodesTotally() {
        for (MobActivity activity : MobActivity.values()) {
            assertEquals(activity, MobActivity.fromOrdinal(activity.ordinal()));
        }
        assertEquals(NONE, MobActivity.fromOrdinal(-1));
        assertEquals(NONE, MobActivity.fromOrdinal(99));
    }

    @Test
    void wireOrderIsStable() {
        // The ordinal is the synced byte — reordering would desync client and server.
        assertEquals(0, NONE.ordinal());
        assertEquals(1, CONTAINER.ordinal());
        assertEquals(2, INVENTORY.ordinal());
    }

    @Test
    void readingShowsOnlyWhenNothingElseIsGoingOn() {
        assertEquals(READING, resolve(true, true, false, false, 0, false, true));
        assertEquals(INVENTORY, resolve(true, true, false, false, 5, false, true));
        assertEquals(CONTAINER, resolve(true, true, true, false, 0, false, true));
        assertEquals(NONE, resolve(true, true, false, true, 0, false, true)); // fighting
        assertEquals(NONE, resolve(false, true, false, false, 0, false, true)); // setting off
    }

    @Test
    void readingIsAppendedSoOldOrdinalsKeepTheirMeaning() {
        assertEquals(3, READING.ordinal());
        assertEquals(READING, MobActivity.fromOrdinal(3));
    }
}
