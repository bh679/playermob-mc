package games.brennan.playermob.compat;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure NBT half of the pet replay: handing a stored pet to the echo that is bringing it back.
 * No Minecraft world — {@code capture}/{@code spawn} need one and are exercised in-game.
 */
class PetSnapshotsTest {

    private static final UUID ECHO = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID PREVIOUS_OWNER = UUID.fromString("00000000-0000-4000-8000-0000000000ff");

    @Test
    void retameHandsThePetToTheEcho() {
        CompoundTag stored = new CompoundTag();
        stored.putString("id", "minecraft:wolf");
        NbtCompat.putUUID(stored, "Owner", PREVIOUS_OWNER);

        CompoundTag replayed = PetSnapshots.retame(stored, ECHO);

        assertEquals(ECHO, NbtCompat.getUUID(replayed, "Owner"));
        assertEquals("minecraft:wolf", NbtCompat.getStringOr(replayed, "id", ""));
        // The stored record is untouched — a record may be replayed for more than one echo.
        assertEquals(PREVIOUS_OWNER, NbtCompat.getUUID(stored, "Owner"));
    }

    @Test
    void aPetToldToSitComesBackStanding() {
        // Sit down, then die: the snapshot carries the sit order. Replaying it verbatim would
        // leave the pet frozen at the echo's feet, and no one could tell it to get up again.
        CompoundTag stored = new CompoundTag();
        stored.putString("id", "minecraft:wolf");
        stored.putBoolean("Sitting", true);

        assertFalse(NbtCompat.getBooleanOr(PetSnapshots.retame(stored, ECHO), "Sitting", false));
        assertTrue(NbtCompat.getBooleanOr(stored, "Sitting", false)); // …without rewriting the record
    }
}
