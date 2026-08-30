package games.brennan.playermob.entity.goal;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.compat.GameRuleCompat;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.WitherSummonPolicy;
import games.brennan.playermob.entity.WitherSummonPolicy.Rig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WitherSkullBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * The last resort: a PlayerMob that is losing a fight it cares about <em>builds a wither</em>.
 *
 * <p>This is deliberately the rarest thing a PlayerMob does. Every one of these must hold before it even
 * considers it (see {@link #armed()}): the mob carries the kit (4 soul sand/soil + 3 wither skeleton skulls),
 * it is <b>below half hearts</b>, it is <b>fully aggressive</b> by nature ({@code fightFlight} ≥
 * {@link WitherSummonPolicy#FF_WITHER_MIN}), and it <b>hates</b> the enemy it is fighting. A timid mob flees, a
 * merely-annoyed mob keeps swinging; only a furious, cornered mob that loathes its foe raises a wither.</p>
 *
 * <pre>APPROACH → BUILD (four soul blocks, then the three skulls, one at a time) → FLEE</pre>
 *
 * <p>The rig is the vanilla pattern ({@code ^^^} / {@code ###} / {@code ~#~}) laid a few blocks ahead of the mob
 * toward its target — geometry from {@link WitherSummonPolicy#placementCandidates}. Blocks go in one at a time
 * with a visible gap, and the <b>centre skull is placed last</b>: right after it lands the goal calls vanilla's
 * own {@link WitherSkullBlock#checkSpawn} on that position, which is exactly what a real player's placement
 * triggers — so the boss arrives through the vanilla path (pattern check, blocks cleared, invulnerable charge-up,
 * boss bar, the summon criterion) with no spawn code of our own. That three-argument overload is the one signature
 * present on every MC version this mod builds for, so no version guard is needed.</p>
 *
 * <p>Then the mob <b>runs</b> — the wither is nobody's ally, and the mob has no illusions about that. It sprints
 * away from the rig (the retreat picker {@link TntCombatGoal} uses) for a few seconds before standing down. It
 * frequently dies anyway; that is the nature of a panic button.</p>
 *
 * <p>Runs at the priority-2 combat tier after the TNT and end-crystal bombers, so those keep first dibs. No JUMP
 * flag, so the priority-0 {@code FloatGoal} still owns JUMP in water (see the goal-JUMP gotcha). Gated on
 * {@link PlayerMobConfig#witherSummon()} and the {@code mobGriefing} gamerule (it places blocks and spawns a boss
 * that wrecks terrain), and on {@link TrainConfinement} so a train-bound mob doesn't raise a wither in a carriage.</p>
 */
public final class WitherSummonGoal extends Goal implements DescribableGoal {

    /** How close (squared) the mob gets before it builds — ~5 blocks, so the rig lands between it and the enemy. */
    private static final double SETUP_REACH_SQR = 25.0;
    private static final int WALK_TIMEOUT_TICKS = 200;      // 10s to reach the target before giving up
    private static final int BUILD_GAP_MIN = 5;             // min ticks between placing successive rig blocks
    private static final int BUILD_GAP_MAX = 20;            // a 5-20 tick gap, so the mob visibly builds
    private static final int BUILD_STEPS = 7;               // 4 soul blocks + 3 skulls
    private static final int FLEE_TICKS = 120;              // 6s of running before standing down
    private static final int FLEE_REPATH_INTERVAL = 20;     // re-pick a retreat spot this often
    private static final double FLEE_SPEED = 1.35;          // faster than a fight walk — this is a real panic
    private static final double FLEE_RADIUS = 16.0;         // retreat-spot search radius
    private static final int FLEE_VERTICAL = 7;             // retreat-spot vertical search range
    private static final double FLEE_SAFE_SQR = 256.0;      // 16 blocks clear of the rig is far enough
    private static final int POST_SUMMON_COOLDOWN = 600;    // 30s before the mob would even consider another
    private static final int FAIL_COOLDOWN = 100;           // 5s pause after a failed site, so we don't thrash

    private enum Phase { APPROACH, BUILD, FLEE, DONE }

    private final PlayerMobEntity mob;
    private final double speed;

    private Phase phase = Phase.DONE;
    private int walkTicks;
    private int cooldown;
    /** Staged-build cursor: 0-3 soul blocks, 4-5 outer skulls, 6 the centre skull, {@code >=BUILD_STEPS}=done. */
    private int buildStep;
    private int buildDelay;
    private Rig rig;
    private int fleeTicks;
    private int fleeRepathTicks;

    public WitherSummonGoal(PlayerMobEntity mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        // MOVE + LOOK only — no JUMP, so FloatGoal can still own JUMP in water.
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        return armed();
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == Phase.DONE || !mob.isAlive()) {
            return false;
        }
        if (phase == Phase.BUILD || phase == Phase.FLEE) {
            return true; // finish the rig and the run — bailing would strand a half-built wither
        }
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && !target.isRemoved();
    }

    /** All the gates: config on, mobGriefing on, not train-confined, a hated live target, cornered, furious, kitted. */
    private boolean armed() {
        if (!PlayerMobConfig.witherSummon()) {
            return false;
        }
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive() || target.isRemoved()) {
            return false;
        }
        if (!GameRuleCompat.mobGriefing(mob.level())) {
            return false;
        }
        if (!TrainConfinement.allowsTarget(mob, target)) {
            return false;
        }
        if (!WitherSummonPolicy.isDesperate(mob.getHealth(), mob.getMaxHealth())) {
            return false; // still healthy — fight it out the normal way
        }
        if (!WitherSummonPolicy.isFurious(mob.fightFlight())) {
            return false; // not the kind of mob that escalates this far
        }
        if (!WitherSummonPolicy.hatesTarget(mob.feelingToward(target))) {
            return false; // it has to actually loathe this enemy
        }
        return WitherSummonPolicy.hasKit(mob.getInventory());
    }

    @Override
    public void start() {
        phase = Phase.APPROACH;
        walkTicks = 0;
        rig = null;
    }

    @Override
    public void stop() {
        mob.setSprinting(false);
        mob.getNavigation().stop();
        phase = Phase.DONE;
        rig = null;
        // Hand the mob back a proper weapon; WeaponAwareAttackGoal re-selects ranged/melee on its next tick.
        mob.equipBestMeleeInHand();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        switch (phase) {
            case APPROACH -> tickApproach();
            case BUILD -> tickBuild();
            case FLEE -> tickFlee();
            default -> { /* DONE — canContinueToUse ends the goal next evaluation */ }
        }
    }

    private void tickApproach() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) {
            phase = Phase.DONE;
            return;
        }
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (mob.distanceToSqr(target) <= SETUP_REACH_SQR) {
            mob.getNavigation().stop();
            if (!startBuild(target)) {
                // Nowhere to raise it here — stand down and let the normal fight goal take over for a beat.
                phase = Phase.DONE;
                cooldown = mob.reactTicks(FAIL_COOLDOWN);
            }
            return;
        }
        if (++walkTicks > WALK_TIMEOUT_TICKS) {
            phase = Phase.DONE;
            cooldown = mob.reactTicks(FAIL_COOLDOWN);
            return;
        }
        mob.getNavigation().moveTo(target, speed);
    }

    /**
     * Pick a buildable site and begin. The whole seven-block footprint is validated up front so a doomed rig never
     * starts (a half-built wither is just wasted skulls), then the blocks go up one per {@link #tickBuild() build
     * tick}. Returns whether building started (else the caller stands the goal down).
     */
    private boolean startBuild(LivingEntity target) {
        if (!WitherSummonPolicy.hasKit(mob.getInventory())) {
            return false;
        }
        Level level = mob.level();
        Rig site = firstBuildableSite(level, target);
        if (site == null) {
            return false;
        }
        rig = site;
        buildStep = 0;
        buildDelay = 0; // lay the first block on the next build tick
        phase = Phase.BUILD;
        return true;
    }

    /** The first candidate rig (best-aligned facing first) whose footprint is clear and whose floor holds it up. */
    private Rig firstBuildableSite(Level level, LivingEntity target) {
        for (Rig candidate : WitherSummonPolicy.placementCandidates(mob.blockPosition(), target.blockPosition())) {
            if (canBuild(level, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Every block of the rig must be replaceable, and the bottom soul block needs a sturdy floor under it. */
    private boolean canBuild(Level level, Rig candidate) {
        BlockPos floor = candidate.bottom().below();
        if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
            return false;
        }
        for (BlockPos pos : candidate.allPositions()) {
            if (!level.getBlockState(pos).canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Lay the rig one block at a time, {@link #BUILD_GAP_MIN}-{@link #BUILD_GAP_MAX} ticks apart, so the mob is seen
     * assembling it rather than conjuring it: the four soul blocks, then the two outer skulls, then the centre skull
     * that sets the whole thing off. {@link #canContinueToUse} keeps the goal alive so a started rig always finishes.
     */
    private void tickBuild() {
        mob.getLookControl().setLookAt(rig.midCenter().getX() + 0.5, rig.midCenter().getY() + 0.5,
            rig.midCenter().getZ() + 0.5);
        if (buildDelay > 0) {
            buildDelay--;
            return;
        }
        performBuildStep(buildStep);
        mob.swing(InteractionHand.MAIN_HAND);
        if (++buildStep >= BUILD_STEPS) {
            enterFlee();
            return;
        }
        buildDelay = mob.reactRoll(BUILD_GAP_MIN, BUILD_GAP_MAX); // 5-20 ticks, skewed by reaction speed
    }

    /** Perform one placement of the staged build — soul blocks first (steps 0-3), then the three skulls (4-6). */
    private void performBuildStep(int step) {
        Level level = mob.level();
        SimpleContainer pack = mob.getInventory();
        if (step < WitherSummonPolicy.SOUL_BLOCKS_NEEDED) {
            placeSoulBlock(level, pack, rig.soulPositions().get(step));
            return;
        }
        List<BlockPos> skulls = rig.skullPositions();
        BlockPos pos = skulls.get(step - WitherSummonPolicy.SOUL_BLOCKS_NEEDED);
        placeSkull(level, pack, pos);
        if (step == BUILD_STEPS - 1) {
            summon(level, pos);
        }
    }

    /** Set one soul block from the pack — soul soil if that's what the mob carries, else soul sand. */
    private void placeSoulBlock(Level level, SimpleContainer pack, BlockPos pos) {
        int slot = WitherSummonPolicy.firstSoulSlot(pack);
        if (slot < 0) {
            return;
        }
        ItemStack stack = pack.getItem(slot);
        level.setBlock(pos, (stack.is(Items.SOUL_SOIL) ? Blocks.SOUL_SOIL : Blocks.SOUL_SAND).defaultBlockState(), 3);
        pack.removeItem(slot, 1);
    }

    /** Set one wither skeleton skull from the pack. */
    private void placeSkull(Level level, SimpleContainer pack, BlockPos pos) {
        int slot = WitherSummonPolicy.firstSkullSlot(pack);
        if (slot < 0) {
            return;
        }
        level.setBlock(pos, Blocks.WITHER_SKELETON_SKULL.defaultBlockState(), 3);
        pack.removeItem(slot, 1);
    }

    /**
     * Ask vanilla to do what it does when a player completes the pattern: {@link WitherSkullBlock#checkSpawn} on the
     * skull just placed re-checks the whole rig, clears the blocks and brings the boss up itself. A no-op (leaving the
     * rig standing for someone else to finish) if the placement didn't take or the pattern doesn't hold.
     */
    private void summon(Level level, BlockPos skullPos) {
        if (!level.getBlockState(skullPos).is(Blocks.WITHER_SKELETON_SKULL)) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(skullPos);
        if (blockEntity instanceof SkullBlockEntity skull) {
            WitherSkullBlock.checkSpawn(level, skullPos, skull);
        }
    }

    private void enterFlee() {
        phase = Phase.FLEE;
        fleeTicks = FLEE_TICKS;
        fleeRepathTicks = 0;
        mob.getNavigation().stop();
        mob.setSprinting(true);
    }

    /**
     * Get away from what it just made. The mob runs until it is {@link #FLEE_SAFE_SQR} clear of the rig or
     * {@link #FLEE_TICKS} run out (it may well be boxed in, or already dying), then stands down for a long
     * {@link #POST_SUMMON_COOLDOWN} — one wither per crisis.
     */
    private void tickFlee() {
        Vec3 danger = Vec3.atCenterOf(rig.midCenter());
        if (--fleeTicks <= 0 || mob.distanceToSqr(danger) >= FLEE_SAFE_SQR) {
            phase = Phase.DONE;
            cooldown = mob.reactTicks(POST_SUMMON_COOLDOWN);
            mob.setSprinting(false);
            return;
        }
        if (++fleeRepathTicks >= mob.reactTicks(FLEE_REPATH_INTERVAL) || mob.getNavigation().isDone()) {
            fleeRepathTicks = 0;
            Vec3 away = DefaultRandomPos.getPosAway(mob, (int) FLEE_RADIUS, FLEE_VERTICAL, danger);
            if (away != null) {
                mob.getNavigation().moveTo(away.x, away.y, away.z, FLEE_SPEED);
            }
        }
    }

    @Override
    public String objective() {
        return "Summoning";
    }

    @Override
    public String subObjective() {
        LivingEntity target = mob.getTarget();
        String name = target != null ? target.getName().getString() : null;
        return switch (phase) {
            case APPROACH -> name != null ? "closing on " + name : "closing in";
            case BUILD -> "raising a wither";
            case FLEE -> "running from the wither";
            default -> null;
        };
    }
}
