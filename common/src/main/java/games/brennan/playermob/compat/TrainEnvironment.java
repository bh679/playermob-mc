package games.brennan.playermob.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Optional-mod integration seam describing the "train" a PlayerMob may be
 * standing on. The only mod that currently supplies one is
 * <a href="https://modrinth.com/mod/dungeon-train">Dungeon Train</a>
 * ({@code dungeontrain}), whose carriages are moving Sable physics sub-levels.
 *
 * <p>This interface lives in {@code common} and references only vanilla types,
 * so it compiles on every loader. The real, Dungeon-Train-backed implementation
 * lives in the NeoForge module (the only loader DT can ever load on) and is
 * installed at boot via {@link TrainConfinement#install} — exactly mirroring the
 * {@code PlayerMobRegistry.MENU_OPENER} loader-backfill pattern.</p>
 *
 * <p>When no train mod is present (always on Fabric/Forge, and on NeoForge when
 * Dungeon Train isn't installed) the active environment stays {@link #ABSENT},
 * whose every method reports "not on a train" — so all
 * {@link TrainConfinement} predicates allow everything and behaviour is
 * identical to a build without this seam.</p>
 *
 * <p>All queries are server-authoritative: implementations resolve the train via
 * server-only physics state and must return {@code false} when handed a
 * client-side entity.</p>
 */
public interface TrainEnvironment {

    /** True if {@code self} is currently standing on a train carriage. */
    boolean isOnTrain(Entity self);

    /**
     * True if {@code candidate} is on the <em>same</em> train as {@code self}.
     * Any carriage of the same train counts — the comparison is train-wide, not
     * per-carriage. Returns {@code false} if {@code self} is not on a train, if
     * {@code candidate} is on a different (or no) train, or if the two are in
     * different levels.
     */
    boolean sameTrain(Entity self, Entity candidate);

    /**
     * Block-position variant of {@link #sameTrain(Entity, Entity)} for
     * block-based targets (chests, crops, dropped items, armor stands). The
     * position is interpreted in {@code self}'s level.
     */
    boolean sameTrain(Entity self, BlockPos candidatePos);

    /**
     * The carriage nearest to {@code self} within {@code radius} blocks as a
     * {@link ReboardTarget} (its current world AABB + world velocity), or
     * {@code null} if no carriage is loaded in range (always {@code null} without
     * a train mod). Server-authoritative; unlike {@link #isOnTrain}/{@link #sameTrain}
     * this resolves a carriage the entity is <em>not</em> on — the "where do I
     * re-board?" query the recovery AI ({@code TrainRecoveryGoal}) uses after
     * falling onto the track bed. The carriage moves, so the result is a one-tick
     * snapshot; re-query each tick while chasing it.
     */
    ReboardTarget nearestCarriage(Entity self, double radius);

    /**
     * Where a fallen PlayerMob should head to climb back aboard: the nearest
     * carriage's current world-space bounding box and its world velocity
     * (blocks/tick). Vanilla types only, so this seam compiles on every loader;
     * the Dungeon-Train impl fills it from {@code ManagedShip.worldAABB()} and
     * {@code TrainTransformProvider.getTargetVelocity()}.
     */
    record ReboardTarget(AABB worldBox, Vec3 velocity) {}

    /**
     * Sentinel returned by {@link #carriageIndex(Entity)} when {@code self} is
     * not on a train (or its carriage can't be resolved). Distinct from any
     * real signed carriage index, which is small and centred on 0.
     */
    int NO_CARRIAGE = Integer.MIN_VALUE;

    /**
     * The signed carriage (room) index — Dungeon Train's {@code pIdx} — of the
     * carriage {@code self} is currently standing in, or {@link #NO_CARRIAGE} if
     * {@code self} is not on a train. The train runs along world-X with carriage
     * {@code 0} at the origin, so the sign tells you which side of centre the mob
     * is on. Used once, on boarding, to fix the mob's march direction (see
     * {@link TrainConfinement#boardingDirection(int)}).
     */
    int carriageIndex(Entity self);

    /**
     * The carriage (room) {@code pIdx} of a mob that may have just been spawned at a
     * carriage's <em>sub-level</em> coordinates rather than its world position — the case
     * for a Dungeon-Train-spawned mob inside {@code finalizeSpawn}, before it is grounded
     * and tracked at world coords. Implementations try the world-space {@link #carriageIndex}
     * first and fall back to a sub-level lookup; {@link #NO_CARRIAGE} if neither resolves.
     * Used to choose a depth-appropriate reincarnation echo. A no-op default off-train.
     */
    default int spawnCarriageIndex(Entity self) { return NO_CARRIAGE; }

    /**
     * A current world-space waypoint at the centre of the next carriage room in
     * step direction {@code dir} ({@code -1} toward decreasing index, {@code +1}
     * toward increasing) <em>within the same carriage group</em>, or {@code null}
     * if {@code self} is not on a train or the step would leave the group (a
     * physical gap to the next group — crossing it is behaviour #2, deferred).
     *
     * <p>Carriages move every tick, so the returned point is only valid for the
     * tick it was queried — callers re-query each tick while pathing to it.</p>
     */
    Vec3 nextCarriageTarget(Entity self, int dir);

    /**
     * A current world-space waypoint at the centre of the <em>nearest room of the
     * adjacent carriage group</em> in march direction {@code dir} — the next group
     * of the <em>same</em> train beyond this group's boundary — or {@code null} if
     * {@code self} is not on a train or there is no further group that way (the
     * genuine end of the train).
     *
     * <p>This is the cross-group analogue of {@link #nextCarriageTarget}: it is
     * resolved once {@code nextCarriageTarget} has run out of rooms within the
     * current group (a physical gap to the next group). The returned room is the one
     * facing the gap, so a mob that crosses lands at the near edge of the next group
     * and resumes within-group marching in the same direction. Crossing the gap
     * itself is {@code CrossGroupGapGoal}'s job.</p>
     *
     * <p>Like {@link #nextCarriageTarget}, the carriages move, so the point is only
     * valid for the tick it was queried.</p>
     */
    Vec3 nextGroupTarget(Entity self, int dir);

    /**
     * If {@code self} is on a train and standing against a closed door, open it —
     * wooden doors directly, power-operated (iron/copper) doors by operating their
     * adjacent control. A no-op default (off-train environments do nothing); the
     * Dungeon-Train impl resolves the carriage's block-coordinate space, which is a
     * moving Sable sub-level whose blocks don't sit at the mob's world position.
     *
     * <p>Run as a per-tick reflex rather than a goal, because vanilla
     * {@code DoorInteractGoal}'s path-node + collision mechanism doesn't fire on a
     * moving sub-level — so doors on a carriage otherwise never open.</p>
     */
    default void openBlockingDoor(Entity self) {}

    /**
     * A world-space deck point on the carriage the mob can actually board <em>right
     * now</em> — an opening it can step/hop into (a flatbed section, or a gap in the
     * wall at least the mob's height) with solid floor beneath — or {@code null} when
     * the carriage is walled at the mob's position and no opening is currently
     * alongside. In that null case the recovery goal holds beside the moving train and
     * waits for an open carriage (flatbed / hole) to slide into reach rather than
     * bonking the wall.
     *
     * <p>Resolves the carriage's real blocks in its sub-level coordinate space (the
     * far-offset shipyard region, not the mob's apparent world position), so only the
     * Dungeon-Train impl returns non-null; every other environment keeps the
     * {@code null} default and the goal just hops straight on (no walls to clear).</p>
     *
     * @param self        the recovering mob, positioned beside the carriage at ~deck height
     * @param carriageBox the carriage's current world AABB (from {@link ReboardTarget#worldBox()})
     */
    default Vec3 boardingSpot(Entity self, AABB carriageBox) { return null; }

    /**
     * No-op environment used whenever no train mod is active. Reports every
     * entity as not on a train, so confinement never engages.
     */
    TrainEnvironment ABSENT = new TrainEnvironment() {
        @Override public boolean isOnTrain(Entity self) { return false; }
        @Override public boolean sameTrain(Entity self, Entity candidate) { return false; }
        @Override public boolean sameTrain(Entity self, BlockPos candidatePos) { return false; }
        @Override public int carriageIndex(Entity self) { return NO_CARRIAGE; }
        @Override public Vec3 nextCarriageTarget(Entity self, int dir) { return null; }
        @Override public Vec3 nextGroupTarget(Entity self, int dir) { return null; }
        @Override public ReboardTarget nearestCarriage(Entity self, double radius) { return null; }
    };
}
