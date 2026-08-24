package games.brennan.playermob.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
//? if >=26 {
/*import net.minecraft.world.entity.animal.equine.AbstractHorse;
*///?} else {
import net.minecraft.world.entity.animal.horse.AbstractHorse;
//?}

import java.util.UUID;

/**
 * Version bridge for the tamed-animal snapshots a life leaves behind — captured when the player
 * dies and replayed beside an echo of that life, tamed to it.
 *
 * <p>Ownership is rewritten purely in NBT ({@link #retame}). Both {@link TamableAnimal} and
 * {@link AbstractHorse} persist their owner under the {@code "Owner"} key and re-tame themselves
 * on read, and that key is unchanged from 1.20.1 through 26.x — so a snapshot handed a new owner
 * UUID comes back tamed to whoever it names, with no per-version setter to bridge. (26.x moved
 * ownership to an {@code EntityReference} in memory, but not in the save format.)</p>
 */
public final class PetSnapshots {

    /** The owner UUID key, identical on every supported version. */
    private static final String TAG_OWNER = "Owner";

    /**
     * {@link net.minecraft.world.entity.TamableAnimal}'s sit-order key, identical on every
     * supported version. Cleared on replay — see {@link #retame}.
     */
    private static final String TAG_SITTING = "Sitting";

    /**
     * Keys dropped from a captured snapshot. The identity and placement of the <em>original</em>
     * animal must not ride along: the original is usually still alive when the snapshot is taken,
     * so keeping its UUID would spawn a duplicate the level refuses to hold, and keeping its
     * position would drop the replay wherever the player last died.
     */
    private static final String[] STRIPPED = {"UUID", "Pos", "Motion", "Passengers", "leash"};

    private PetSnapshots() {}

    /** Whether {@code entity} is a rideable mount — a horse, donkey, mule, llama or camel. */
    public static boolean isMount(Entity entity) {
        return entity instanceof AbstractHorse;
    }

    /** The UUID this entity is tamed to, or {@code null} if it is untamed or not ownable. */
    public static UUID ownerUuid(Entity entity) {
        if (!(entity instanceof OwnableEntity ownable)) {
            return null;
        }
        //? if >=26 {
        /*net.minecraft.world.entity.EntityReference<net.minecraft.world.entity.LivingEntity> ref =
            ownable.getOwnerReference();
        return ref == null ? null : ref.getUUID();
        *///?} else {
        return ownable.getOwnerUUID();
        //?}
    }

    /**
     * Serialise {@code entity} into a replayable snapshot: its full save data, stamped with the
     * entity-type {@code "id"} {@link #spawn} needs, minus the {@link #STRIPPED} identity keys.
     */
    public static CompoundTag capture(Entity entity) {
        //? if >=26 {
        /*net.minecraft.world.level.storage.TagValueOutput out =
            net.minecraft.world.level.storage.TagValueOutput.createWithContext(
                net.minecraft.util.ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(out);
        CompoundTag tag = out.buildResult();
        *///?} else {
        CompoundTag tag = entity.saveWithoutId(new CompoundTag());
        //?}
        for (String key : STRIPPED) {
            tag.remove(key);
        }
        tag.putString("id", EntityType.getKey(entity.getType()).toString());
        return tag;
    }

    /**
     * Return a copy of {@code snapshot} tamed to {@code owner} instead of whoever owned it in life,
     * and standing.
     *
     * <p>The sit order is deliberately dropped. A player who told their wolf to sit and then died
     * would otherwise have it return frozen at the echo's feet, reading as scenery rather than as
     * something that came back with him — and nobody is left who could tell it to get up, since the
     * order can only be given by an owner the world can resolve.</p>
     */
    public static CompoundTag retame(CompoundTag snapshot, UUID owner) {
        CompoundTag tag = snapshot.copy();
        NbtCompat.putUUID(tag, TAG_OWNER, owner);
        tag.putBoolean(TAG_SITTING, false);
        return tag;
    }

    /**
     * Rebuild the entity a snapshot describes into {@code level} (not yet added to the world), or
     * {@code null} when its type no longer exists — a pet from a removed mod simply doesn't return.
     */
    public static Entity spawn(CompoundTag snapshot, ServerLevel level) {
        //? if >=26 {
        /*// 26.x wraps the spawn reason in an EntitySpawnRequest and takes an EntityProcessor
        // rather than a plain Function; EntityProcessor.NOP is the identity we want.
        return EntityType.loadEntityRecursive(snapshot.copy(), level,
            new net.minecraft.world.entity.EntitySpawnRequest(
                net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED, false),
            net.minecraft.world.entity.EntityProcessor.NOP);
        *///?} else {
        return EntityType.loadEntityRecursive(snapshot.copy(), level, entity -> entity);
        //?}
    }
}
