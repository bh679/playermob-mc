package games.brennan.playermob.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

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
}
