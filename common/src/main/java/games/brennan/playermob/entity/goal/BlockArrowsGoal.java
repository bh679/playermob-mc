package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.ArrowThreat;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Defensive reflex: when an arrow is in flight and heading at the mob, and the mob
 * is holding a shield, raise it and square the body up so vanilla's directional
 * block actually deflects the hit — then lower it once the volley passes. This is
 * what lets a sword-and-board PlayerMob survive a skeleton instead of eating every
 * arrow.
 *
 * <p>Declares <b>no flags</b> (like {@code PlayerMobDoorGoal}), so it layers on top
 * of whatever owns movement — the mob keeps meleeing via {@code WeaponAwareAttackGoal}
 * (priority 2) and raises its off-hand shield between swings, rather than this reflex
 * freezing combat by stealing MOVE/LOOK.</p>
 *
 * <p>Threat detection is delegated to the pure, unit-tested {@link ArrowThreat};
 * this class only does the {@link AbstractArrow} scan and the shield/face plumbing
 * (all reused from {@link PlayerMobEntity}: {@code raiseShieldIfHeld},
 * {@code lowerShield}, {@code faceBodyToward}). "Arrow" means any
 * {@link AbstractArrow} — normal/tipped/spectral arrows and thrown tridents.</p>
 *
 * <p>An archer mob actively shooting back is left to shoot (see
 * {@link #isActivelyShootingBack}) rather than freezing to block; a melee mob, or an
 * archer with no target, blocks freely. Charging a bow already suppresses the raise
 * (a mob can only use one item at a time — {@code raiseShieldIfHeld} bails while
 * {@code isUsingItem()}), and {@link #isBusyUsingNonShield} keeps {@code canUse}
 * honest about it.</p>
 */
public final class BlockArrowsGoal extends Goal {

    /** Block radius around the mob to scan for in-flight arrows. */
    private static final double SCAN_RADIUS = 16.0;

    /** Added to the mob's half-width to size the "this arrow will hit me" disc. */
    private static final double HIT_MARGIN = 0.5;

    /** Don't start blocking arrows whose closest approach is more than this many ticks out (~1s). */
    private static final double MAX_LEAD_TICKS = 20.0;

    /** Keep the shield up this many ticks after the last threat, so quick volleys don't flicker it. */
    private static final int GRACE_TICKS = 5;

    private final PlayerMobEntity mob;

    /** Last server tick an incoming arrow was seen — drives the {@link #GRACE_TICKS} hold. */
    private int lastThreatTick = Integer.MIN_VALUE;

    public BlockArrowsGoal(PlayerMobEntity mob) {
        this.mob = mob;
        // Intentionally no setFlags(...) — see class javadoc.
    }

    @Override
    public boolean canUse() {
        if (!mob.hasShieldReady()) return false;
        if (isBusyUsingNonShield()) return false;   // don't interrupt a bow charge
        if (isActivelyShootingBack()) return false; // archer engaging a target shoots, doesn't freeze
        return findIncomingArrow() != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!mob.hasShieldReady()) return false;
        if (findIncomingArrow() != null) return true;
        // Brief grace after the last arrow so a stream of shots keeps the shield up.
        return mob.tickCount - lastThreatTick <= GRACE_TICKS;
    }

    @Override
    public void tick() {
        AbstractArrow arrow = findIncomingArrow();
        if (arrow == null) return;
        mob.raiseShieldIfHeld();
        // Vanilla only blocks hits from the facing arc — square up to the arrow.
        mob.faceBodyToward(arrow.getX(), arrow.getZ());
        lastThreatTick = mob.tickCount;
    }

    @Override
    public void stop() {
        mob.lowerShield();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /** True while the mob is mid-use of a non-shield item (e.g. charging a bow). */
    private boolean isBusyUsingNonShield() {
        return mob.isUsingItem() && !mob.getUseItem().is(Items.SHIELD);
    }

    /** True when the mob is holding a ranged weapon and has a target — it should shoot, not block. */
    private boolean isActivelyShootingBack() {
        if (mob.getTarget() == null) return false;
        return mob.getMainHandItem().getItem() instanceof BowItem
            || mob.getMainHandItem().getItem() instanceof CrossbowItem;
    }

    /**
     * Nearest in-flight arrow on a collision course with the mob, or {@code null}.
     * Skips the mob's own shots and (via {@link ArrowThreat}'s velocity gate)
     * arrows already stuck in the ground.
     */
    private AbstractArrow findIncomingArrow() {
        double mobX = mob.getX();
        double mobY = mob.getY() + mob.getBbHeight() * 0.5;
        double mobZ = mob.getZ();
        double hitRadius = mob.getBbWidth() * 0.5 + HIT_MARGIN;

        AABB scan = mob.getBoundingBox().inflate(SCAN_RADIUS);
        List<AbstractArrow> arrows = mob.level().getEntitiesOfClass(
            AbstractArrow.class, scan,
            a -> a.isAlive() && a.getOwner() != mob);

        AbstractArrow closest = null;
        double closestSqr = Double.MAX_VALUE;
        for (AbstractArrow arrow : arrows) {
            Vec3 v = arrow.getDeltaMovement();
            if (!ArrowThreat.isIncoming(mobX, mobY, mobZ,
                    arrow.getX(), arrow.getY(), arrow.getZ(),
                    v.x, v.y, v.z, hitRadius, MAX_LEAD_TICKS)) {
                continue;
            }
            double distSqr = arrow.distanceToSqr(mobX, mobY, mobZ);
            if (distSqr < closestSqr) {
                closestSqr = distSqr;
                closest = arrow;
            }
        }
        return closest;
    }
}
