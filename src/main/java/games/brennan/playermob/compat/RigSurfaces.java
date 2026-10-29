package games.brennan.playermob.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Optional-mod integration seam deciding <em>where</em> a PlayerMob builds a multi-block rig (the
 * {@link games.brennan.playermob.entity.goal.EndCrystalCombatGoal} bunker): in the world, or on some
 * other block grid the mob is standing on.
 *
 * <p>Mirrors {@link ReincarnationCaptureGate}: the provider defaults to "no surface" (build in the
 * world, exactly as before this seam existed) and is replaced exactly once, at boot, by a consuming
 * mod from inside its {@code ModList.isLoaded("playermob")} guard.</p>
 *
 * <p>Used by Dungeon Train, whose carriages keep their blocks in a separate physics plot: a world
 * read of a carriage cell is air, so without a surface a mob riding the train finds no floor to rig
 * on, and any block it did place would float in world space while the train drives off. DT's surface
 * maps the rig into the carriage under the mob, so the rig rides with it.</p>
 *
 * <p>A surface is resolved once per rig, so every block of it lands in the same frame however far
 * the surface moves while the mob builds. Server-thread only. Fail-open: a provider that throws is
 * logged and treated as "no surface".</p>
 */
public final class RigSurfaces {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * A block grid a rig can be built on. Positions handed to the read/write methods are
     * <b>local</b> — produced by {@link #toLocal} — and are stable for the life of the rig.
     */
    public interface RigSurface {
        /** The local cell that world cell {@code worldPos} currently overlaps. */
        BlockPos toLocal(BlockPos worldPos);

        /** The block at local cell {@code local}. */
        BlockState getBlockState(BlockPos local);

        /** True when local cell {@code local}'s top face can carry a block (vanilla's sturdy-face test). */
        boolean isSturdyFloor(BlockPos local);

        /** Place {@code state} at local cell {@code local}. */
        void setBlock(BlockPos local, BlockState state);

        /** Where local point ({@code x},{@code y},{@code z}) is in the world right now. */
        Vec3 toWorld(double x, double y, double z);
    }

    /** A consumer's choice of surface for a mob about to rig. */
    public interface Provider {
        /** The surface {@code mob} should build on, or {@code null} to build in the world. */
        default RigSurface surfaceFor(Mob mob) {
            return null;
        }
    }

    private static volatile Provider provider = new Provider() {};

    private RigSurfaces() {}

    /**
     * Install the active provider. Called once during loader boot by a consuming mod; never called
     * on runs without such a mod, so the holder stays the build-in-the-world default.
     */
    public static void install(Provider newProvider) {
        provider = newProvider;
    }

    /**
     * The consumer's surface for {@code mob}, or {@code null} for the world. Best-effort: any
     * exception the provider throws is logged and treated as {@code null}.
     */
    public static RigSurface surfaceFor(Mob mob) {
        try {
            return provider.surfaceFor(mob);
        } catch (Throwable t) {
            LOGGER.warn("[playermob] rig-surface provider threw; building in the world", t);
            return null;
        }
    }

    /** {@link #surfaceFor}, falling back to the plain world {@code mob} stands in. */
    public static RigSurface resolve(Mob mob) {
        RigSurface surface = surfaceFor(mob);
        return surface != null ? surface : new WorldSurface(mob.level());
    }

    /** The world itself: local cells are world cells. */
    public record WorldSurface(Level level) implements RigSurface {
        @Override
        public BlockPos toLocal(BlockPos worldPos) {
            return worldPos;
        }

        @Override
        public BlockState getBlockState(BlockPos local) {
            return level.getBlockState(local);
        }

        @Override
        public boolean isSturdyFloor(BlockPos local) {
            return level.getBlockState(local).isFaceSturdy(level, local, Direction.UP);
        }

        @Override
        public void setBlock(BlockPos local, BlockState state) {
            level.setBlock(local, state, 3);
        }

        @Override
        public Vec3 toWorld(double x, double y, double z) {
            return new Vec3(x, y, z);
        }
    }
}
