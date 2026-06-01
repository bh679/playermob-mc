package games.brennan.playermob.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

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
     * No-op environment used whenever no train mod is active. Reports every
     * entity as not on a train, so confinement never engages.
     */
    TrainEnvironment ABSENT = new TrainEnvironment() {
        @Override public boolean isOnTrain(Entity self) { return false; }
        @Override public boolean sameTrain(Entity self, Entity candidate) { return false; }
        @Override public boolean sameTrain(Entity self, BlockPos candidatePos) { return false; }
    };
}
