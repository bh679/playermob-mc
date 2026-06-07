package games.brennan.playermob.neoforge.compat;

import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.playermob.compat.TrainEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

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
}
