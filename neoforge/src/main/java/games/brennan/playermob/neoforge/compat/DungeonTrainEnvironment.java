package games.brennan.playermob.neoforge.compat;

import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.playermob.compat.TrainEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Dungeon Train-backed {@link TrainEnvironment}. This is the <em>only</em> class
 * in PlayerMob that references Dungeon Train ({@code dungeontrain}) symbols, and
 * it is instantiated solely from inside the {@code ModList.isLoaded("dungeontrain")}
 * guard in {@code PlayerMobNeoForge} — so the JVM never classloads it (or the DT
 * types it imports) when Dungeon Train is absent. Compiled against DT's public
 * API via {@code modCompileOnly}; never bundled or hard-required at runtime.
 *
 * <p>A Dungeon Train carriage is a moving Sable sub-level driven by a {@link
 * TrainTransformProvider}, which carries the train-wide {@code trainId}. We
 * resolve "which train owns this block" through DT's public façade:
 * {@code Shipyards.of(level).findAt(pos)} → {@link ManagedShip} →
 * {@link ManagedShip#getKinematicDriver()}; a driver that is a {@link
 * TrainTransformProvider} yields the train's {@link UUID} via
 * {@link TrainTransformProvider#getTrainId()}. Any null/absent link in that chain
 * means "not on a train".</p>
 *
 * <p>{@code Shipyards.of} requires a {@link ServerLevel}, so every query first
 * checks the entity is on the server and returns {@code false} otherwise — these
 * predicates are only ever consulted from server-side AI in any case.</p>
 */
public final class DungeonTrainEnvironment implements TrainEnvironment {

    /**
     * Temporary geometry probe — launch with {@code -Dplayermob.trainDebug=true}
     * to log the world↔model mapping (mob world pos, {@code worldToShip} result,
     * shipyard origin, carriage length, derived room index, group pIdx range) so
     * the coordinate convention can be confirmed in-game. Throttled; left dormant
     * (flag off by default) and removed once the mapping is verified.
     */
    private static final boolean DEBUG = Boolean.getBoolean("playermob.trainDebug");
    private static final Logger LOGGER = LoggerFactory.getLogger("playermob/dungeontrain");

    @Override
    public boolean isOnTrain(Entity self) {
        return trainIdAt(self, self.blockPosition()) != null;
    }

    @Override
    public boolean sameTrain(Entity self, Entity candidate) {
        if (self.level() != candidate.level()) {
            return false;
        }
        UUID mine = trainIdAt(self, self.blockPosition());
        if (mine == null) {
            return false;
        }
        return mine.equals(trainIdAt(candidate, candidate.blockPosition()));
    }

    @Override
    public boolean sameTrain(Entity self, BlockPos candidatePos) {
        UUID mine = trainIdAt(self, self.blockPosition());
        if (mine == null) {
            return false;
        }
        return mine.equals(trainIdAt(self, candidatePos));
    }

    /**
     * The {@code trainId} of the carriage occupying {@code pos} in the (server)
     * level of {@code ctx}, or {@code null} if {@code pos} is not on any train
     * (or {@code ctx} is client-side).
     */
    private static UUID trainIdAt(Entity ctx, BlockPos pos) {
        if (!(ctx.level() instanceof ServerLevel level)) {
            return null;
        }
        ManagedShip ship = Shipyards.of(level).findAt(pos);
        if (ship == null) {
            return null;
        }
        if (ship.getKinematicDriver() instanceof TrainTransformProvider provider) {
            return provider.getTrainId();
        }
        return null;
    }

    // ---- Carriage exploration (behaviour #3) -----------------------------

    @Override
    public int carriageIndex(Entity self) {
        Carriage c = carriageAt(self);
        if (c == null) {
            return NO_CARRIAGE;
        }
        Vector3d local = toShip(c, self);
        int room = roomIndex(c.provider, local.x);
        if (DEBUG && (self.tickCount & 31) == 0) {
            logProbe(self, c, local, room);
        }
        // Fail-safe: the mob is standing inside its own group, so its derived
        // room index MUST fall within [getPIdx(), getGroupHighestPIdx()]. If it
        // doesn't, the world↔model mapping is wrong for this build of DT/Sable —
        // report "no carriage" so the explore behaviour cleanly no-ops instead of
        // sending the mob the wrong way or into a wall.
        return c.provider.containsPIdx(room) ? room : NO_CARRIAGE;
    }

    @Override
    public Vec3 nextCarriageTarget(Entity self, int dir) {
        Carriage c = carriageAt(self);
        if (c == null) {
            return null;
        }
        Vector3d local = toShip(c, self);
        int room = roomIndex(c.provider, local.x);
        if (!c.provider.containsPIdx(room)) {
            return null; // geometry not trustworthy (see carriageIndex) — no-op
        }
        int target = room + dir;
        if (!c.provider.containsPIdx(target)) {
            return null; // next room is in another group — physical gap, behaviour #2
        }
        // Ship-fixed centre of the target room in model space, transformed to the
        // carriage's *current* world position. The point is fixed to the moving
        // ship, so a mob pathing to it closes distance as it walks; callers
        // re-query each tick to follow the carriage.
        int length = c.provider.dims().length();
        double localX = c.provider.getShipyardOrigin().getX() + (double) target * length + length / 2.0;
        // shipToWorld owns (mutates and returns) its argument — hand it a fresh
        // vector and never one we still need to read.
        Vector3d world = c.ship.shipToWorld(new Vector3d(localX, local.y, local.z));
        return new Vec3(world.x, world.y, world.z);
    }

    /** The signed room index for a model-space X within {@code provider}'s group. */
    private static int roomIndex(TrainTransformProvider provider, double localX) {
        int length = provider.dims().length();
        return (int) Math.floor((localX - provider.getShipyardOrigin().getX()) / length);
    }

    /** {@code self}'s position in {@code c}'s model space. The returned vector is owned by the caller. */
    private static Vector3d toShip(Carriage c, Entity self) {
        // worldToShip owns (mutates and returns) its argument — pass it fresh.
        return c.ship.worldToShip(new Vector3d(self.getX(), self.getY(), self.getZ()));
    }

    /** Resolve the carriage group {@code self} stands in, or {@code null} if it's not on a train. */
    private static Carriage carriageAt(Entity self) {
        if (!(self.level() instanceof ServerLevel level)) {
            return null;
        }
        ManagedShip ship = Shipyards.of(level).findAt(self.blockPosition());
        if (ship == null) {
            return null;
        }
        if (ship.getKinematicDriver() instanceof TrainTransformProvider provider) {
            return new Carriage(ship, provider);
        }
        return null;
    }

    private static void logProbe(Entity self, Carriage c, Vector3d local, int room) {
        LOGGER.info(
            "probe world=({},{},{}) local=({},{},{}) origin={} len={} room={} pIdx={} high={} size={} contains={}",
            f(self.getX()), f(self.getY()), f(self.getZ()),
            f(local.x), f(local.y), f(local.z),
            c.provider.getShipyardOrigin(), c.provider.dims().length(),
            room, c.provider.getPIdx(), c.provider.getGroupHighestPIdx(),
            c.provider.getGroupSize(), c.provider.containsPIdx(room));
    }

    private static String f(double d) {
        return String.format("%.2f", d);
    }

    /** A resolved carriage group: the Sable ship plus its train transform driver. */
    private record Carriage(ManagedShip ship, TrainTransformProvider provider) {}
}
