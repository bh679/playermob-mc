package games.brennan.playermob.menu;

import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.RelationPickerButtons;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The add-relation picker's <b>candidate snapshot</b>: which nearby PlayerMobs the editor
 * offers, and how that list rides the vanilla menu {@link ContainerData} channel.
 *
 * <p>{@code ContainerData} values are synced as <b>shorts</b> ({@code ClientboundContainerSetDataPacket}
 * on 1.20.1 and 1.21.1), so each entity id is split into a hi/lo 16-bit pair. Layout:
 * slot 0 = count, then {@code (hi, lo)} per candidate, {@link #SLOT_COUNT} slots in all. The
 * server writes the list it scanned; the client reads the same list back, so the row index a
 * pick button carries (see {@link RelationPickerButtons}) resolves to the same mob on both sides.
 * Loader-agnostic — no custom packets, and the per-loader menu-open payload is untouched.</p>
 */
public final class RelationCandidates {

    /** How far (blocks, from the edited mob's bounding box) the picker looks for PlayerMobs. */
    public static final double PICK_RANGE = 32.0;
    /** count + (hi, lo) per candidate. */
    public static final int SLOT_COUNT = 1 + 2 * RelationPickerButtons.MAX_CANDIDATES;

    private RelationCandidates() {
    }

    /**
     * Nearby PlayerMobs {@code mob} could be introduced to: alive, not itself, not already in
     * its ledger, nearest first, capped at {@link RelationPickerButtons#MAX_CANDIDATES}.
     * Server-side.
     */
    public static List<PlayerMobEntity> scan(PlayerMobEntity mob) {
        List<PlayerMobEntity> nearby = mob.level().getEntitiesOfClass(PlayerMobEntity.class,
            mob.getBoundingBox().inflate(PICK_RANGE),
            e -> e != mob && e.isAlive() && !mob.hasMet(e.getUUID()));
        List<PlayerMobEntity> sorted = new ArrayList<>(nearby);
        sorted.sort(Comparator.comparingDouble(mob::distanceToSqr));
        return sorted.size() > RelationPickerButtons.MAX_CANDIDATES
            ? List.copyOf(sorted.subList(0, RelationPickerButtons.MAX_CANDIDATES))
            : List.copyOf(sorted);
    }

    /** Encode {@code entityIds} (at most {@code MAX_CANDIDATES}; extras dropped) into {@code data}. */
    public static void write(SimpleContainerData data, List<Integer> entityIds) {
        int count = Math.min(entityIds.size(), RelationPickerButtons.MAX_CANDIDATES);
        data.set(0, count);
        for (int i = 0; i < RelationPickerButtons.MAX_CANDIDATES; i++) {
            int id = i < count ? entityIds.get(i) : 0;
            data.set(1 + i * 2, (id >>> 16) & 0xFFFF);
            data.set(2 + i * 2, id & 0xFFFF);
        }
    }

    /**
     * Decode the entity ids back out of {@code data}. Each half arrives sign-extended from a
     * short, so it is masked before reassembly. A count outside the valid range (a corrupt or
     * forward-version payload) yields an empty list rather than throwing.
     */
    public static List<Integer> read(ContainerData data) {
        int count = data.get(0);
        if (count < 0 || count > RelationPickerButtons.MAX_CANDIDATES) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int hi = data.get(1 + i * 2) & 0xFFFF;
            int lo = data.get(2 + i * 2) & 0xFFFF;
            ids.add((hi << 16) | lo);
        }
        return List.copyOf(ids);
    }
}
