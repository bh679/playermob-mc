package games.brennan.playermob.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Stateless gate the PlayerMob AI consults to decide whether a candidate
 * target (mob or block) is allowed, plus the set-once holder for the active
 * {@link TrainEnvironment}.
 *
 * <p><b>Confinement rule:</b> when a PlayerMob is <em>not</em> on a train, every
 * predicate returns {@code true} — the mob behaves exactly as it does in a build
 * without any train integration. When the mob <em>is</em> on a train, a candidate
 * is only allowed if it sits on the <em>same</em> train. This is what keeps a
 * PlayerMob from chasing a mob, or pathing to a chest, that has left the train.</p>
 *
 * <p>The active environment defaults to {@link TrainEnvironment#ABSENT} and is
 * replaced exactly once, at boot, by the loader that detects a train mod — see
 * {@link #install}. On Fabric/Forge, and on NeoForge without Dungeon Train, it is
 * never replaced, so {@link #isConfined} is always {@code false} and every
 * {@code allowsTarget} call short-circuits to {@code true} at zero cost.</p>
 */
public final class TrainConfinement {

    private static volatile TrainEnvironment environment = TrainEnvironment.ABSENT;

    private TrainConfinement() {}

    /**
     * Install the active train environment. Called once during loader boot by the
     * NeoForge entrypoint when {@code dungeontrain} is present; never called on
     * loaders or runs without a train mod (the holder stays {@link
     * TrainEnvironment#ABSENT}).
     */
    public static void install(TrainEnvironment env) {
        environment = env;
    }

    /** True only while {@code self} is actually standing on a train. */
    public static boolean isConfined(Entity self) {
        return environment.isOnTrain(self);
    }

    /**
     * Whether {@code self} may target/pursue the entity {@code candidate}.
     * Allowed unless {@code self} is confined to a train and {@code candidate}
     * is not on that same train.
     */
    public static boolean allowsTarget(Entity self, Entity candidate) {
        TrainEnvironment env = environment;
        return !env.isOnTrain(self) || env.sameTrain(self, candidate);
    }

    /**
     * Whether {@code self} may target/path to the block at {@code pos}.
     * Allowed unless {@code self} is confined to a train and {@code pos} is not
     * part of that same train.
     */
    public static boolean allowsTarget(Entity self, BlockPos pos) {
        TrainEnvironment env = environment;
        return !env.isOnTrain(self) || env.sameTrain(self, pos);
    }

    /**
     * The carriage nearest {@code self} within {@code radius}, or {@code null} if
     * none is loaded in range (always {@code null} without a train mod). Used by
     * the recovery AI to find where to re-board after a fall; see
     * {@link TrainEnvironment#nearestCarriage}.
     */
    public static TrainEnvironment.ReboardTarget nearestCarriage(Entity self, double radius) {
        return environment.nearestCarriage(self, radius);
    }

    /**
     * A world-space deck point the mob can board right now (an opening with floor under
     * it), or {@code null} if the carriage is walled where the mob is — in which case
     * recovery waits beside the moving train for an open carriage. Always {@code null}
     * without a train mod; see {@link TrainEnvironment#boardingSpot}.
     */
    public static Vec3 boardingSpot(Entity self, AABB carriageBox) {
        return environment.boardingSpot(self, carriageBox);
    }

    // ---- Carriage exploration (behaviour #3) -----------------------------

    /** Re-export of {@link TrainEnvironment#NO_CARRIAGE} for AI-side callers. */
    public static final int NO_CARRIAGE = TrainEnvironment.NO_CARRIAGE;

    /**
     * The signed carriage index {@code self} is standing in, or {@link #NO_CARRIAGE}
     * if it isn't on a train. Always {@code NO_CARRIAGE} without a train mod.
     */
    public static int carriageIndex(Entity self) {
        return environment.carriageIndex(self);
    }

    /**
     * The carriage {@code pIdx} for a just-spawned mob that may still be at a carriage's
     * sub-level coordinates (e.g. inside {@code finalizeSpawn}); {@link #NO_CARRIAGE} without
     * a train mod or if it can't be resolved. See {@link TrainEnvironment#spawnCarriageIndex}.
     */
    public static int spawnCarriageIndex(Entity self) {
        return environment.spawnCarriageIndex(self);
    }

    /**
     * World-space waypoint at the centre of the next carriage room in step
     * direction {@code dir} within the same group, or {@code null} if not on a
     * train or at the group boundary. Always {@code null} without a train mod.
     */
    public static Vec3 nextCarriageTarget(Entity self, int dir) {
        return environment.nextCarriageTarget(self, dir);
    }

    /**
     * World-space waypoint at the centre of the nearest room of the adjacent
     * same-train group in step direction {@code dir}, or {@code null} if not on a
     * train or there is no further group that way. Always {@code null} without a
     * train mod. Used by {@code CrossGroupGapGoal} to aim the cross-gap leap once
     * {@link #nextCarriageTarget} runs out of rooms in the current group.
     */
    public static Vec3 nextGroupTarget(Entity self, int dir) {
        return environment.nextGroupTarget(self, dir);
    }

    /**
     * Open a closed door {@code self} is standing against on a train (wooden
     * directly, iron/copper via its control). No-op off a train / without a train
     * mod — see {@link TrainEnvironment#openBlockingDoor}.
     */
    public static void openBlockingDoor(Entity self) {
        environment.openBlockingDoor(self);
    }

    /**
     * If {@code self} is on a train and stuck against soft fill (ice/dirt/mud/moss/log) blocking
     * its march, mine that block to pass — returning {@code true} while doing so. No-op (returns
     * {@code false}) off a train / without a train mod; see
     * {@link TrainEnvironment#digObstructingBlock}.
     */
    public static boolean digObstructingBlock(Entity self) {
        return environment.digObstructingBlock(self);
    }

    /**
     * The fixed march direction for a mob that boarded at {@code carriageIndex}:
     * march toward carriage 0 and continue past it. Negative side marches up
     * ({@code +1}); the centre and positive side march down ({@code -1}). Chosen
     * once on boarding and kept thereafter, so the mob keeps going after crossing
     * 0. Pure function of the index — the unit-tested core of the boarding rule.
     */
    public static int boardingDirection(int carriageIndex) {
        return carriageIndex < 0 ? +1 : -1;
    }
}
