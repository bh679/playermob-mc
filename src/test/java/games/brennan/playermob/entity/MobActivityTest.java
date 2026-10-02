package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static games.brennan.playermob.entity.MobActivity.CONTAINER;
import static games.brennan.playermob.entity.MobActivity.INVENTORY;
import static games.brennan.playermob.entity.MobActivity.NONE;
import static games.brennan.playermob.entity.MobActivity.resolve;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic oracle for {@link MobActivity} — which look wins each tick, and the total decode of
 * the synced wire value. Primitives only, so no Minecraft bootstrap is needed.
 */
class MobActivityTest {

    @Test
    void nothingToShowAtRest() {
        assertEquals(NONE, resolve(true, true, false, false, 0, false));
    }

    @Test
    void openContainerWinsOverEverything() {
        assertEquals(CONTAINER, resolve(true, true, true, false, 0, false));
        assertEquals(CONTAINER, resolve(true, true, true, true, 20, true)); // even mid-fight
    }

    @Test
    void gearChangePulseAndIdleCheckShowInventory() {
        assertEquals(INVENTORY, resolve(true, true, false, false, 1, false));
        assertEquals(INVENTORY, resolve(true, true, false, false, 0, true));
    }

    @Test
    void combatSuppressesTheInventoryLook() {
        assertEquals(NONE, resolve(true, true, false, true, 30, false));
        assertEquals(NONE, resolve(true, true, false, true, 0, true));
    }

    @Test
    void disabledOrDeadShowsNothing() {
        assertEquals(NONE, resolve(false, true, true, false, 30, true));
        assertEquals(NONE, resolve(true, false, true, false, 30, true));
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
}
