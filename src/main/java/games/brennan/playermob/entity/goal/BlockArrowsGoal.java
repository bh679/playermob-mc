package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.CrossbowCompat;
import games.brennan.playermob.entity.AimCone;
import games.brennan.playermob.entity.ArrowThreat;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Defensive reflex: raise a held shield against ranged fire and square the body up
 * so vanilla's directional block actually deflects the hit, then lower it once the
 * threat passes. This is what lets a sword-and-board PlayerMob survive a skeleton or
 * pillager instead of eating every shot.
 *
 * <p><b>Why it triggers on the enemy's <i>charge</i>, not just the arrow.</b>
 * Vanilla shields don't block until they've been raised for ~5 ticks
 * ({@code LivingEntity.isBlocking()} checks {@code useDuration - remaining >= 5}).
 * An arrow fired from close range arrives in fewer ticks than that, so reacting only
 * once it's airborne raises the shield too late to count. The primary trigger is
 * therefore "someone is <i>drawing/charging</i> a ranged weapon aimed at me" — that
 * gives the shield its warm-up during the enemy's draw, so it's genuinely blocking by
 * the time the shot looses. An in-flight arrow on a collision course is kept as a
 * secondary trigger (covers shots from an un-classified source / already loosed).</p>
 *
 * <p>Detection:</p>
 * <ul>
 *   <li><b>Charging shooter</b> ({@link #findAimingShooter}) — a nearby
 *       {@link LivingEntity} drawing a bow / charging or holding a charged crossbow,
 *       that is aimed at this mob (a {@link Mob} whose {@code getTarget()} is us, or —
 *       for players, who have no target — one whose view cone covers us via
 *       {@link AimCone}), with line of sight.</li>
 *   <li><b>Incoming arrow</b> ({@link #findIncomingArrow}) — any {@link AbstractArrow}
 *       (arrows + thrown tridents) on a near-collision course per the pure
 *       {@link ArrowThreat} test.</li>
 * </ul>
 *
 * <p>Declares <b>no flags</b> (like {@code PlayerMobDoorGoal}), so it layers on top of
 * whatever owns movement — a sword-and-board mob keeps approaching/meleeing via
 * {@code WeaponAwareAttackGoal} (priority 2) and only blocks while it isn't striking.
 * The mob lowers the guard whenever it's committed to its own attack
 * ({@link #isCommittedToAttack}): shooting back, or within melee reach of its target —
 * it takes the hit to land one. It still blocks while <em>closing</em> on a ranged
 * attacker (not yet in reach). Charging its own bow suppresses the raise too
 * ({@link #isBusyUsingNonShield}). No shield in hand ⇒ no-op.</p>
 */
public final class BlockArrowsGoal extends Goal {

    /** Block radius to scan for in-flight arrows. */
    private static final double ARROW_SCAN_RADIUS = 16.0;

    /** Block radius to scan for a shooter charging a ranged weapon at the mob. */
    private static final double SHOOTER_SCAN_RANGE = 20.0;

    /** Added to the mob's half-width to size the "this arrow will hit me" disc. */
    private static final double HIT_MARGIN = 0.5;

    /** Don't start blocking arrows whose closest approach is more than this many ticks out (~1s). */
    private static final double MAX_LEAD_TICKS = 20.0;

    /** Cosine of the aim-cone half-angle for player shooters (~53°). Higher = tighter. */
    private static final double AIM_COS_THRESHOLD = 0.6;

    /** Keep the shield up this many ticks after the last threat, so quick volleys / re-draws don't flicker it. */
    private static final int GRACE_TICKS = 8;

    private final PlayerMobEntity mob;

    /** Last server tick a ranged threat was seen — drives the {@link #GRACE_TICKS} hold. */
    private int lastThreatTick = Integer.MIN_VALUE;

    public BlockArrowsGoal(PlayerMobEntity mob) {
        this.mob = mob;
        // Intentionally no setFlags(...) — see class javadoc.
    }

    @Override
    public boolean canUse() {
        if (!mob.hasShieldReady()) return false;
        if (mob.isFleeing()) return false;        // running away -> don't stop to face & block
        if (isBusyUsingNonShield()) return false; // don't interrupt charging its own bow
        if (isCommittedToAttack()) return false;  // striking its own target -> drop guard to land the hit
        return hasRangedThreat();
    }

    @Override
    public boolean canContinueToUse() {
        if (!mob.hasShieldReady()) return false;
        if (mob.isFleeing()) return false;
        if (isCommittedToAttack()) return false;
        if (hasRangedThreat()) return true;
        // Brief grace after the last threat so a stream of shots / re-draws holds the block.
        return mob.tickCount - lastThreatTick <= GRACE_TICKS;
    }

    @Override
    public void tick() {
        // An airborne arrow is the most imminent thing to face; otherwise face the
        // shooter we're anticipating so the warm-up shield is already squared up.
        AbstractArrow arrow = findIncomingArrow();
        double faceX;
        double faceZ;
        if (arrow != null) {
            faceX = arrow.getX();
            faceZ = arrow.getZ();
        } else {
            LivingEntity shooter = findAimingShooter();
            if (shooter == null) return;
            faceX = shooter.getX();
            faceZ = shooter.getZ();
        }
        mob.raiseShieldIfHeld();
        mob.faceBodyToward(faceX, faceZ);
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

    private boolean hasRangedThreat() {
        return findIncomingArrow() != null || findAimingShooter() != null;
    }

    /** True while the mob is mid-use of a non-shield item (e.g. charging its own bow). */
    private boolean isBusyUsingNonShield() {
        return mob.isUsingItem() && !mob.getUseItem().is(Items.SHIELD);
    }

    /**
     * The mob declines to block while committed to its own attack — it drops the guard
     * to strike (shooting back, or in melee reach of its target), accepting it may take
     * the hit. A mob still <em>closing</em> on a ranged attacker is not yet in reach, so
     * it keeps blocking as it approaches and only lowers once adjacent.
     */
    private boolean isCommittedToAttack() {
        return isActivelyShootingBack() || isAttackingInMelee();
    }

    /** True when the mob is holding a ranged weapon and has a target — it should shoot, not block. */
    private boolean isActivelyShootingBack() {
        if (mob.getTarget() == null) return false;
        return mob.getMainHandItem().getItem() instanceof BowItem
            || mob.getMainHandItem().getItem() instanceof CrossbowItem;
    }

    /** True when the mob has a target within melee reach — it's striking, so it lowers the shield. */
    private boolean isAttackingInMelee() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;
        // Vanilla MeleeAttackGoal.getAttackReachSqr.
        double reachSqr = mob.getBbWidth() * 2.0F * mob.getBbWidth() * 2.0F + target.getBbWidth();
        return mob.distanceToSqr(target) <= reachSqr;
    }

    /**
     * Nearest entity drawing/charging a ranged weapon aimed at the mob, or {@code null}.
     * This is the primary, anticipatory trigger (see class javadoc on the shield warm-up).
     */
    private LivingEntity findAimingShooter() {
        double mobCx = mob.getX();
        double mobCy = mob.getY() + mob.getBbHeight() * 0.5;
        double mobCz = mob.getZ();

        AABB scan = mob.getBoundingBox().inflate(SHOOTER_SCAN_RANGE);
        List<LivingEntity> candidates = mob.level().getEntitiesOfClass(
            LivingEntity.class, scan,
            e -> e != mob && e.isAlive() && isPreparingRangedAttack(e));

        LivingEntity closest = null;
        double closestSqr = Double.MAX_VALUE;
        for (LivingEntity shooter : candidates) {
            if (!isAimedAtMob(shooter, mobCx, mobCy, mobCz)) continue;
            double distSqr = shooter.distanceToSqr(mobCx, mobCy, mobCz);
            if (distSqr < closestSqr) {
                closestSqr = distSqr;
                closest = shooter;
            }
        }
        return closest;
    }

    /**
     * Whether {@code shooter} is drawing a bow, or charging / holding a charged crossbow.
     * The "actively drawing/charging" case is read off {@code isUsingItem()} — vanilla's
     * bow and crossbow attack goals both call {@code startUsingItem} during the draw, so
     * this covers players, skeletons, and charging pillagers alike. {@link #holdsChargedCrossbow}
     * then covers the post-charge hold (loaded crossbow, not yet loosed).
     */
    private static boolean isPreparingRangedAttack(LivingEntity shooter) {
        if (shooter.isUsingItem()) {
            ItemStack using = shooter.getUseItem();
            if (using.getItem() instanceof BowItem || using.getItem() instanceof CrossbowItem) {
                return true;
            }
        }
        return holdsChargedCrossbow(shooter);
    }

    private static boolean holdsChargedCrossbow(LivingEntity shooter) {
        return isChargedCrossbow(shooter.getMainHandItem())
            || isChargedCrossbow(shooter.getOffhandItem());
    }

    private static boolean isChargedCrossbow(ItemStack stack) {
        if (!(stack.getItem() instanceof CrossbowItem)) return false;
        // Charged state: CHARGED_PROJECTILES component on 1.21.1, the NBT charged
        // flag on 1.20.1 (see CrossbowCompat / PlayerMobCrossbowAttackGoal).
        return CrossbowCompat.isCharged(stack);
    }

    /**
     * Whether {@code shooter} is pointing at this mob: definitive for a {@link Mob} that
     * has locked onto us as its target; otherwise (players, untargeted mobs) a view-cone
     * test via {@link AimCone}. Both require line of sight.
     */
    private boolean isAimedAtMob(LivingEntity shooter, double mobCx, double mobCy, double mobCz) {
        if (shooter instanceof Mob other && other.getTarget() == mob) {
            return shooter.hasLineOfSight(mob);
        }
        Vec3 look = shooter.getViewVector(1.0F);
        double dx = mobCx - shooter.getX();
        double dy = mobCy - shooter.getEyeY();
        double dz = mobCz - shooter.getZ();
        return AimCone.facesWithin(look.x, look.y, look.z, dx, dy, dz, AIM_COS_THRESHOLD)
            && shooter.hasLineOfSight(mob);
    }

    /**
     * Nearest in-flight arrow on a collision course with the mob, or {@code null}.
     * Skips the mob's own shots and (via {@link ArrowThreat}'s velocity gate) arrows
     * already stuck in the ground. Secondary trigger — see class javadoc.
     */
    private AbstractArrow findIncomingArrow() {
        double mobX = mob.getX();
        double mobY = mob.getY() + mob.getBbHeight() * 0.5;
        double mobZ = mob.getZ();
        double hitRadius = mob.getBbWidth() * 0.5 + HIT_MARGIN;

        AABB scan = mob.getBoundingBox().inflate(ARROW_SCAN_RADIUS);
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
