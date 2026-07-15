package games.brennan.playermob.entity;

import games.brennan.playermob.compat.NbtCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * The tether a {@link PlayerMobEntity} is kept near by {@link games.brennan.playermob.entity.goal.StayNearGoal}
 * — a fixed <b>position</b> or a live <b>entity</b>, plus a radius. Immutable value object (create a new
 * one to change it, never mutate — see the project immutability rule); serialised into the mob's
 * {@code PlayerMobData/StayNear} sub-compound so an anchor round-trips through save/load and can be
 * authored via {@code /summon …{PlayerMobData:{StayNear:{…}}}}, {@code /data merge}, or a spawn egg.
 *
 * <p>Exactly one of {@link #entityId} / {@link #pos} is non-null. The radius is clamped to
 * {@link StayNearPolicy#clampRadius} at every construction point, so a stored value is always valid.</p>
 */
public final class StayAnchor {

    /** NBT key for the radius (int, blocks). */
    private static final String TAG_RADIUS = "Radius";
    /** NBT key for a position anchor ({@link BlockPos#asLong()}). */
    private static final String TAG_POS = "AnchorPos";
    /** NBT key for an entity anchor (UUID, {@code int[4]} encoding via {@link NbtCompat#putUUID}). */
    private static final String TAG_ENTITY = "AnchorEntity";

    private final UUID entityId; // non-null for an entity anchor
    private final BlockPos pos;  // non-null for a position anchor
    private final int radius;

    private StayAnchor(UUID entityId, BlockPos pos, int radius) {
        this.entityId = entityId;
        this.pos = pos;
        this.radius = StayNearPolicy.clampRadius(radius);
    }

    /** A tether to a fixed block position. */
    public static StayAnchor ofPosition(BlockPos pos, int radius) {
        return new StayAnchor(null, pos.immutable(), radius);
    }

    /** A tether to a live entity (player or another mob), by UUID. */
    public static StayAnchor ofEntity(UUID entityId, int radius) {
        return new StayAnchor(entityId, null, radius);
    }

    /** The (clamped) tether radius in blocks. */
    public int radius() {
        return radius;
    }

    /** Whether this anchor tracks a live entity (vs a fixed position). */
    public boolean isEntity() {
        return entityId != null;
    }

    /**
     * The anchor's live world position, or {@code null} when an entity anchor's target is gone or
     * unloaded — in which case the goal simply idles this tick, keeping the anchor for when the
     * entity comes back. A position anchor always resolves.
     */
    public Vec3 resolvePos(ServerLevel level) {
        if (entityId != null) {
            Entity target = level.getEntity(entityId);
            return target != null && target.isAlive() ? target.position() : null;
        }
        return Vec3.atCenterOf(pos);
    }

    /** Short human-readable label for command feedback and the objective readout. */
    public String label(ServerLevel level) {
        if (entityId != null) {
            Entity target = level.getEntity(entityId);
            return target != null ? target.getName().getString() : "its anchor";
        }
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    /** Write this anchor's fields into {@code out} (the {@code StayNear} sub-compound). */
    public void save(CompoundTag out) {
        out.putInt(TAG_RADIUS, radius);
        if (entityId != null) {
            NbtCompat.putUUID(out, TAG_ENTITY, entityId);
        } else {
            out.putLong(TAG_POS, pos.asLong());
        }
    }

    /**
     * Rebuild an anchor from a {@code StayNear} sub-compound, or {@code null} when neither an entity
     * nor a position key is present (a pre-feature save, or a mob that was never tethered). An entity
     * key wins if somehow both are present. The radius defaults to {@link StayNearPolicy#DEFAULT_RADIUS}
     * and is clamped by the constructor.
     */
    public static StayAnchor load(CompoundTag tag) {
        int radius = NbtCompat.getIntOr(tag, TAG_RADIUS, StayNearPolicy.DEFAULT_RADIUS);
        if (NbtCompat.hasUUID(tag, TAG_ENTITY)) {
            return ofEntity(NbtCompat.getUUID(tag, TAG_ENTITY), radius);
        }
        if (NbtCompat.containsOfType(tag, TAG_POS, NbtCompat.ANY_NUMERIC)) {
            return ofPosition(BlockPos.of(NbtCompat.getLongOr(tag, TAG_POS, 0L)), radius);
        }
        return null;
    }
}
