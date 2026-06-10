package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.Personality;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The <b>Shy</b> behaviour, as a flee → hide loop toward any entity the mob is
 * {@link Personality#SHY} toward (players and/or hostile mobs — Shy is
 * disallowed for animals/villagers):
 *
 * <ol>
 *   <li><b>Flee</b> — sprint to a position away from the threat (vanilla's
 *       {@link DefaultRandomPos#getPosAway} retreat picker, same as
 *       {@code AvoidEntityGoal}).</li>
 *   <li><b>Hide</b> — once it's opened up some distance, stop, crouch, and keep
 *       watching the threat for a random 2–15 seconds (peeking from cover)
 *       before returning to its tasks. If the threat closes back in during the
 *       watch, it bolts again.</li>
 * </ol>
 *
 * <p>On a Dungeon Train, a mob cornered at a carriage-group boundary — vanilla pathing
 * can't cross the inter-group gap (separate Sable sub-levels), so the retreat stalls at
 * the edge — instead bolts across the gap to the group on the side <em>away from the
 * threat</em> using the shared ballistic {@link GapLeap} (the same hop {@link CrossGroupGapGoal}
 * uses to explore forward). Off a train this never triggers
 * ({@link TrainConfinement#isConfined} is false), so behaviour is unchanged. See issue #64.</p>
 *
 * <p>Registered at priority 1 and deliberately does <em>not</em> gate on
 * {@code target == null}, so a provoked-shy mob flees instead of fighting.
 * Crouching is cosmetic (see {@link PlayerMobEntity#setCrouching}); the mob
 * keeps full flee speed.</p>
 */
public final class FleeFromCategoryGoal extends Goal implements DescribableGoal {

    private static final double DETECT_RANGE_BONUS = 6.0;     // notice the threat a bit beyond flee range
    private static final double RETURN_TO_FLEE_MARGIN = 2.0;  // while hiding, re-flee if threat gets within (fleeDistance - margin)
    private static final double PANIC_RANGE = 5.0;            // this close → always flee, never brave a chest
    private static final int BRAVE_RADIUS = 12;               // sneak-raid a chest within this instead of fleeing
    private static final int HIDE_MIN_TICKS = 40;            // 2s
    private static final int HIDE_MAX_TICKS = 300;           // 15s
    private static final int RESUME_COOLDOWN_TICKS = 60;     // ~3s back-to-tasks before re-triggering
    private static final int RETREAT_RADIUS = 16;
    private static final int RETREAT_VERTICAL = 7;
    // Cross-gap escape approach (mirrors CrossGroupGapGoal): walk to the gap edge, settle for a
    // clean carry read, then hop. Vanilla nav can't cross the gap, so it stops at the near edge and
    // reports done — that "done at the edge" is the launch cue, NOT wherever the flee retreat ended.
    private static final int LEAP_SETTLE_TICKS = 3;            // consecutive ticks "nav done at the edge" → launch
    private static final int LEAP_REPATH_INTERVAL = 10;        // re-issue the approach moveTo every 0.5s
    private static final int LEAP_APPROACH_TIMEOUT_TICKS = 100; // give up the hop after 5s → flee normally

    private enum Phase { FLEE, HIDE, LEAP }

    private final PlayerMobEntity mob;
    private final double fleeDistance;
    private final double detectRange;
    private final double walkSpeed;
    private final double sprintSpeed;
    private final GapLeap leap = new GapLeap(); // shared cross-gap hop, used when cornered on a train

    private Phase phase = Phase.FLEE;
    private LivingEntity threat;
    private int hideTicksLeft;
    private int cooldownTicks;
    private int leapDir;             // latched away-from-threat step direction for the current leap
    private int leapTicks;           // ticks since the hop began — approach timeout backstop
    private int leapSettleTicks;     // consecutive ticks "nav done at the edge"
    private int leapRepathCooldown;  // re-issue the approach moveTo when this reaches 0

    public FleeFromCategoryGoal(PlayerMobEntity mob, float fleeDistance,
                                double walkSpeed, double sprintSpeed) {
        this.mob = mob;
        this.fleeDistance = fleeDistance;
        this.detectRange = fleeDistance + DETECT_RANGE_BONUS;
        this.walkSpeed = walkSpeed;
        this.sprintSpeed = sprintSpeed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Fleeing";
    }

    @Override
    public String subObjective() {
        return switch (phase) {
            case FLEE -> "running";
            case HIDE -> "hiding";
            case LEAP -> "leaping the gap";
        };
    }

    @Override
    public boolean canUse() {
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return false;
        }
        LivingEntity candidate = mob.nearestWithPersonality(Personality.SHY, detectRange);
        if (candidate == null) return false;
        // Unless the threat is right on top of us, if there's a chest worth braving
        // yield so the raid goal can sneak-grab it (crouching). Once it's looted the
        // chest is no longer raidable, so this goal fires again and the mob flees.
        if (mob.distanceTo(candidate) > PANIC_RANGE
                && mob.canRaid()
                && mob.hasRaidableContainerNearby(BRAVE_RADIUS)) {
            return false;
        }
        this.threat = candidate;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == Phase.LEAP) {
            // Committed to the cross-gap escape: see it through even as the mob opens distance from
            // the threat or a target appears. Airborne → never abandon it mid-air (flight timeout);
            // still approaching the edge → keep going (the approach timeout in tickLeap falls back
            // to normal fleeing).
            if (!mob.isAlive() || mob.isDeadOrDying()) return false;
            return !leap.isLaunched() || leap.flightTicks() <= GapLeap.FLIGHT_TIMEOUT_TICKS;
        }
        if (threat == null || !threat.isAlive()) return false;
        if (mob.personalityToward(threat) != Personality.SHY) return false;
        if (mob.distanceTo(threat) > detectRange + 4.0) return false;
        return phase == Phase.FLEE || hideTicksLeft > 0;
    }

    @Override
    public void start() {
        this.phase = Phase.FLEE;
        this.hideTicksLeft = 0;
        mob.setFleeing(true); // suppress the shield-block reflex while running away
    }

    @Override
    public void tick() {
        if (phase == Phase.LEAP) {
            tickLeap(); // mid-hop: no threat-relative steering, GapLeap drives the arc
            return;
        }
        if (threat == null) return;
        mob.getLookControl().setLookAt(threat, 30.0F, 30.0F); // keep eyes on the threat
        double dist = mob.distanceTo(threat);

        switch (phase) {
            case FLEE -> {
                mob.setCrouching(false);
                if (dist >= fleeDistance) {
                    // Opened up enough → hide and peek.
                    mob.getNavigation().stop();
                    phase = Phase.HIDE;
                    hideTicksLeft = HIDE_MIN_TICKS
                        + mob.getRandom().nextInt(HIDE_MAX_TICKS - HIDE_MIN_TICKS + 1);
                    mob.setCrouching(true);
                } else if (mob.getNavigation().isDone()) {
                    // Reached as far as vanilla nav can retreat. On a train, if that's a
                    // group boundary with a group on the away side, leap the gap; otherwise
                    // pick another retreat point as usual.
                    if (!beginLeapIfCornered()) {
                        fleeAway();
                    }
                }
            }
            case HIDE -> {
                mob.getNavigation().stop();
                mob.setCrouching(true);
                hideTicksLeft--;
                if (dist <= fleeDistance - RETURN_TO_FLEE_MARGIN) {
                    phase = Phase.FLEE; // threat closing back in — bolt further away
                    mob.setCrouching(false);
                }
            }
            case LEAP -> { } // handled above (early return)
        }
    }

    private void fleeAway() {
        Vec3 away = DefaultRandomPos.getPosAway(mob, RETREAT_RADIUS, RETREAT_VERTICAL, threat.position());
        if (away != null) {
            // Panic-sprint when the threat is close; settle to a walk once farther off.
            double speed = mob.distanceTo(threat) < fleeDistance * 0.5 ? sprintSpeed : walkSpeed;
            mob.getNavigation().moveTo(away.x, away.y, away.z, speed);
        }
    }

    /**
     * On a Dungeon Train, if the mob is cornered at a carriage-group boundary with a group on the
     * side away from the threat, begin a cross-gap leap toward it and return {@code true}. Off a
     * train ({@link TrainConfinement#isConfined} false) or not cornered at an away-side boundary,
     * returns {@code false} so the caller retreats the normal way — keeping off-train behaviour
     * unchanged.
     */
    private boolean beginLeapIfCornered() {
        if (threat == null || !TrainConfinement.isConfined(mob)) {
            return false;
        }
        List<BoundaryLeap> candidates = new ArrayList<>(2);
        for (int d = -1; d <= 1; d += 2) {
            // A real gap this way (no room left to retreat within the group)...
            if (TrainConfinement.nextCarriageTarget(mob, d) != null) {
                continue;
            }
            // ...with a group on the far side to land on.
            Vec3 group = TrainConfinement.nextGroupTarget(mob, d);
            if (group != null) {
                candidates.add(new BoundaryLeap(d, group.x));
            }
        }
        BoundaryLeap chosen = chooseAwayLeap(mob.getX(), threat.getX(), candidates);
        if (chosen == null) {
            return false; // no boundary leap leads away from the threat
        }
        phase = Phase.LEAP;
        leapDir = chosen.dir();
        leapTicks = 0;
        leapSettleTicks = 0;
        leapRepathCooldown = LEAP_REPATH_INTERVAL;
        leap.reset();
        mob.setCrouching(false);
        // Start walking to the gap edge immediately. Vanilla nav can't cross the gap, so it stops
        // at the near edge — tickLeap settles there and launches the hop from the edge (not from
        // wherever the flee retreat happened to end).
        Vec3 target = TrainConfinement.nextGroupTarget(mob, leapDir);
        if (target != null) {
            mob.getNavigation().moveTo(target.x, target.y, target.z, sprintSpeed);
        }
        return true;
    }

    /**
     * Walk to the gap edge toward the away group, settle for a clean carry read, launch the shared
     * ballistic hop, then fly until landed. Mirrors {@link CrossGroupGapGoal}'s approach so the
     * escape uses the exact same tuned crossing as the forward-explore leap — crucially, it launches
     * from the gap edge (nav done) rather than from wherever the flee retreat stopped.
     */
    private void tickLeap() {
        if (leap.isLaunched()) {
            if (leap.tickFlight(mob)) {
                endLeap(); // landed (or timed out) on the far group — resume fleeing/hiding here
            }
            return;
        }
        if (++leapTicks > LEAP_APPROACH_TIMEOUT_TICKS) {
            endLeap(); // couldn't reach the edge in time — fall back to normal fleeing
            return;
        }
        Vec3 target = TrainConfinement.nextGroupTarget(mob, leapDir);
        if (target == null) {
            endLeap(); // lost the adjacent group — flee the normal way
            return;
        }
        leap.trackCarry(mob);
        mob.getLookControl().setLookAt(target.x, target.y, target.z);
        if (mob.getNavigation().isDone()) {
            // As close to the gap as vanilla can walk (and the carry reading is clean) → hop.
            if (++leapSettleTicks >= LEAP_SETTLE_TICKS) {
                leap.launch(mob, target);
            }
            return;
        }
        leapSettleTicks = 0;
        if (--leapRepathCooldown <= 0) {
            leapRepathCooldown = LEAP_REPATH_INTERVAL;
            mob.getNavigation().moveTo(target.x, target.y, target.z, sprintSpeed);
        }
    }

    /** Clear the hop and return to the FLEE phase (which re-evaluates: hide on the far side, or
     *  bolt again if the threat is still close). Also clears the crossing-gap latch. */
    private void endLeap() {
        mob.setCrossingGap(false);
        leap.reset();
        leapDir = 0;
        leapTicks = 0;
        leapSettleTicks = 0;
        leapRepathCooldown = 0;
        phase = Phase.FLEE;
    }

    @Override
    public void stop() {
        mob.setCrouching(false);
        mob.getNavigation().stop();
        mob.setFleeing(false);
        mob.setCrossingGap(false); // clear in case we stopped mid-leap
        leap.reset();
        this.threat = null;
        this.phase = Phase.FLEE;
        this.hideTicksLeft = 0;
        this.leapDir = 0;
        this.leapTicks = 0;
        this.leapSettleTicks = 0;
        this.leapRepathCooldown = 0;
        this.cooldownTicks = RESUME_COOLDOWN_TICKS;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /** A candidate boundary leap: the step direction and the world-X of the group it would land on. */
    record BoundaryLeap(int dir, double landingX) {}

    /**
     * Of the candidate boundary leaps, the one that carries the mob <em>away</em> from the threat
     * along the train's world-X axis: the group whose landing is farthest from the threat, and
     * never nearer than the mob already is (so a leap toward the threat is rejected). Returns
     * {@code null} if no candidate leads away. Pure function of its inputs — unit-tested.
     *
     * <p>Dungeon Train runs along world-X (its carriage index is monotonic in world-X), so
     * "away from the threat" reduces to maximising {@code |landingX − threatX|}. A perpendicular
     * threat ({@code threatX ≈ mobX}) makes any boundary leap count as "away".</p>
     */
    static BoundaryLeap chooseAwayLeap(double mobX, double threatX, List<BoundaryLeap> candidates) {
        BoundaryLeap best = null;
        double bestDist = Math.abs(mobX - threatX); // must beat standing still — never leap toward the threat
        for (BoundaryLeap c : candidates) {
            double dist = Math.abs(c.landingX() - threatX);
            if (dist > bestDist) {
                best = c;
                bestDist = dist;
            }
        }
        return best;
    }
}
