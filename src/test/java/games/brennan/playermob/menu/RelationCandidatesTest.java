package games.brennan.playermob.menu;

import games.brennan.playermob.entity.RelationPickerButtons;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for {@link RelationCandidates}' hi/lo data-slot encoding. The wire
 * truncates each slot to a signed short, so the decode is exercised through a view that
 * sign-extends every value the way {@code ClientboundContainerSetDataPacket} does.
 */
class RelationCandidatesTest {

    /** What the client sees: each slot narrowed to a short and widened back. */
    private static ContainerData asSyncedShorts(SimpleContainerData server) {
        SimpleContainerData client = new SimpleContainerData(server.getCount());
        for (int i = 0; i < server.getCount(); i++) {
            client.set(i, (short) server.get(i));
        }
        return client;
    }

    private static List<Integer> roundTrip(List<Integer> ids) {
        SimpleContainerData data = new SimpleContainerData(RelationCandidates.SLOT_COUNT);
        RelationCandidates.write(data, ids);
        return RelationCandidates.read(asSyncedShorts(data));
    }

    @Test
    void emptyListRoundTrips() {
        assertEquals(List.of(), roundTrip(List.of()));
    }

    @Test
    void smallIdsRoundTrip() {
        assertEquals(List.of(1, 2, 3), roundTrip(List.of(1, 2, 3)));
    }

    /** Ids beyond a short, and ids whose low half is negative as a short, survive the split. */
    @Test
    void largeAndSignBitIdsRoundTrip() {
        List<Integer> ids = List.of(32767, 32768, 65535, 65536, 0xFFFF_8000, 0x7FFF_FFFF, 123_456_789);
        assertEquals(ids, roundTrip(ids));
    }

    @Test
    void capsAtMaxCandidates() {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < RelationPickerButtons.MAX_CANDIDATES + 5; i++) {
            ids.add(100_000 + i);
        }
        List<Integer> back = roundTrip(ids);
        assertEquals(RelationPickerButtons.MAX_CANDIDATES, back.size());
        assertEquals(ids.subList(0, RelationPickerButtons.MAX_CANDIDATES), back);
    }

    @Test
    void corruptCountReadsAsEmpty() {
        SimpleContainerData data = new SimpleContainerData(RelationCandidates.SLOT_COUNT);
        data.set(0, RelationPickerButtons.MAX_CANDIDATES + 1);
        assertTrue(RelationCandidates.read(data).isEmpty());
        data.set(0, -1);
        assertTrue(RelationCandidates.read(data).isEmpty());
    }

    @Test
    void slotCountCoversEveryCandidate() {
        assertEquals(1 + 2 * RelationPickerButtons.MAX_CANDIDATES, RelationCandidates.SLOT_COUNT);
    }
}
