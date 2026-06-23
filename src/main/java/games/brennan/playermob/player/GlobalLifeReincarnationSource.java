package games.brennan.playermob.player;

import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.compat.NbtCompat;
import games.brennan.playermob.compat.ReincarnationQuery;
import games.brennan.playermob.compat.ReincarnationRecord;
import games.brennan.playermob.compat.ReincarnationSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Exposes PlayerMob's own death log ({@link GlobalLifeStore} → {@code lives.dat}) as the built-in
 * {@link ReincarnationSource}. Always registered (from {@code PlayerMob.init}), so even with no
 * integrating mod the reincarnation pool is the local death log — exactly the behaviour that
 * shipped before the seam existed.
 *
 * <p>Translates a {@link ReincarnationQuery} into the matching {@link GlobalLifeStore} candidate
 * set and maps each {@link GlobalLifeStore.DeathRecord} into the public {@link ReincarnationRecord},
 * decoding the captured player skin URL out of the snapshot and defensively copying the (mutable)
 * snapshot NBT so a consumer can't disturb the stored log.</p>
 */
public final class GlobalLifeReincarnationSource implements ReincarnationSource {

    /** The on-disk death log is the <em>local</em> pool — the player's own past lives, self included. */
    @Override
    public boolean remote() {
        return false;
    }

    /**
     * NBT key the snapshot stores the skin ref under — mirrors {@code PlayerMobEntity.TAG_SKIN_TEXTURE_URL}
     * (a stable {@code lives.dat} field). Used only to surface the captured player skin URL as a
     * convenience; the snapshot is otherwise opaque.
     */
    private static final String SKIN_TEXTURE_URL_KEY = "SkinTextureUrl";

    @Override
    public List<ReincarnationRecord> candidates(MinecraftServer server, ReincarnationQuery query) {
        GlobalLifeStore store = GlobalLifeStore.get(server);
        List<GlobalLifeStore.DeathRecord> records = switch (query.mode()) {
            case CARRIAGE -> store.recordsInBand(query.carriage());
            case PLAYER -> query.player() == null ? List.of() : store.recordsForPlayer(query.player());
            case ANY -> store.allRecords();
        };
        return toRecords(records);
    }

    @Override
    public List<ReincarnationRecord> recent(MinecraftServer server, int limit) {
        return toRecords(GlobalLifeStore.get(server).recent(limit));
    }

    private static List<ReincarnationRecord> toRecords(List<GlobalLifeStore.DeathRecord> records) {
        List<ReincarnationRecord> out = new ArrayList<>(records.size());
        for (GlobalLifeStore.DeathRecord r : records) {
            out.add(toRecord(r));
        }
        return out;
    }

    private static ReincarnationRecord toRecord(GlobalLifeStore.DeathRecord r) {
        CompoundTag snapshot = r.snapshot().copy();
        String skinUrl = SourceProfileSkin.decode(NbtCompat.getStringOr(snapshot, SKIN_TEXTURE_URL_KEY, ""))
            .map(SourceProfileSkin.Ref::url)
            .orElse("");
        return new ReincarnationRecord(
            PlayerMob.MOD_ID,
            Long.toString(r.id()),
            r.uuid(),
            r.name() == null ? "" : r.name(),
            r.carriage(),
            skinUrl,
            snapshot,
            copyAll(r.friendSnapshots()));
    }

    private static List<CompoundTag> copyAll(List<CompoundTag> tags) {
        if (tags.isEmpty()) {
            return List.of();
        }
        List<CompoundTag> out = new ArrayList<>(tags.size());
        for (CompoundTag t : tags) {
            out.add(t.copy());
        }
        return out;
    }
}
