package games.brennan.playermob.compat;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;

/**
 * Version-bridging detonation for {@link games.brennan.playermob.entity.goal.EndCrystalCombatGoal} — the
 * PlayerMob <em>melee-punches</em> the crystal it placed (a real attack, mirroring how it hits a zombie), so
 * the crystal takes damage and explodes (power 6). It is <b>hit</b>, never damaged remotely by code.
 *
 * <p>The mob's melee-attack method changed across MC versions:</p>
 * <ul>
 *   <li>1.20.1 / 1.21.1: {@code Mob.doHurtTarget(Entity)};</li>
 *   <li>26.x: {@code Mob.doHurtTarget(ServerLevel, Entity)} — a {@link net.minecraft.server.level.ServerLevel}
 *       parameter was added.</li>
 * </ul>
 *
 * <p>Routing the punch through {@code doHurtTarget} also side-steps the parallel {@code EndCrystal.hurt}
 * (≤1.21.1) → {@code EndCrystal.hurtServer} (26.x) rename: {@code doHurtTarget} applies the target's damage via
 * the version-correct entry point internally, so this one guard covers the whole detonation. The
 * {@code ServerLevel} type is referenced fully-qualified inside the {@code >=26} branch (mirroring
 * {@link GameRuleCompat}) so the pre-26 build carries no unused import.</p>
 */
public final class EndCrystalCompat {

    private EndCrystalCompat() {}

    /** Have {@code mob} melee-punch {@code crystal} to detonate it. No-op if the crystal is already gone. */
    public static void punch(PlayerMobEntity mob, EndCrystal crystal) {
        if (crystal == null || !crystal.isAlive()) {
            return;
        }
        //? if >=26 {
        /*if (mob.level() instanceof net.minecraft.server.level.ServerLevel server) {
            mob.doHurtTarget(server, crystal);
        }
        *///?} else {
        mob.doHurtTarget(crystal);
        //?}
    }
}
