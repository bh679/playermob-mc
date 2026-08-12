package games.brennan.playermob.compat;

import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.UUID;

/**
 * One past life offered to the reincarnation pool — the symmetric data contract carried in
 * <em>both</em> directions across the {@link ReincarnationSource} seam:
 *
 * <ul>
 *   <li><b>Supply</b> (external mod → PlayerMob): a source returns these from
 *       {@link ReincarnationSource#candidates}, and a spawning PlayerMob may embody one.</li>
 *   <li><b>Read</b> (PlayerMob → external mod): {@link ReincarnationSources#recentDeaths} hands
 *       these back so an integrating mod (e.g. Discord Presence) can read PlayerMob's death log.</li>
 * </ul>
 *
 * <p>The {@code snapshot} is the PlayerMob entity's own {@code readAdditionalSaveData} NBT — the
 * complete life (skin, gear, traits, feelings, door personality). An external mod shuttles it
 * verbatim and never has to understand its layout; PlayerMob applies it when it spawns the echo.
 * The {@code skinUrl} and {@code name} are pulled out so a consumer can show a face and a title
 * without parsing the snapshot.</p>
 *
 * <p>{@code sourceId} names the mod that owns this record ({@code "playermob"} for the built-in
 * death log, or an integrating mod's id); {@code key} is that source's own stable id for the
 * record. Together ({@link #id()}) they give a pool-wide stable identity used to avoid showing a
 * live player the same past life twice in one session.</p>
 *
 * @param sourceId        the source mod's id ({@code "playermob"} for the built-in log)
 * @param key             the source's own stable id for this record (e.g. a death sequence number)
 * @param playerId        the original player this life belonged to
 * @param name            the player's display name at death (the echo is titled "Echo of &lt;name&gt;")
 * @param carriage        Dungeon-Train room index the death happened in, or
 *                        {@link TrainConfinement#NO_CARRIAGE} if it didn't happen on a train
 * @param skinUrl         captured Mojang skin texture URL, or {@code ""} if none was captured
 * @param difficulty      the vanilla difficulty this life was lived on (its serialized name), or
 *                        {@code ""} when unknown — see {@link ReincarnationDifficulty}
 * @param snapshot        the opaque PlayerMob entity NBT to embody this life
 * @param friendSnapshots snapshots of the PlayerMobs that loved this player at death (may be empty)
 */
public record ReincarnationRecord(String sourceId, String key, UUID playerId, String name,
                                  int carriage, String skinUrl, String difficulty, CompoundTag snapshot,
                                  List<CompoundTag> friendSnapshots) {

    /**
     * A record whose difficulty is unknown — it answers to {@link ReincarnationDifficulty#LEGACY}. Kept
     * so a source written before the difficulty partition still compiles and behaves as it did.
     */
    public ReincarnationRecord(String sourceId, String key, UUID playerId, String name,
                               int carriage, String skinUrl, CompoundTag snapshot,
                               List<CompoundTag> friendSnapshots) {
        this(sourceId, key, playerId, name, carriage, skinUrl, "", snapshot, friendSnapshots);
    }

    /** Pool-wide stable identity ({@code sourceId:key}) — keys the "already met this life" de-dup. */
    public String id() {
        return sourceId + ":" + key;
    }

    /** This life's effective partition — an unknown difficulty is {@link ReincarnationDifficulty#LEGACY}. */
    public String partition() {
        return ReincarnationDifficulty.partitionOf(difficulty);
    }
}
