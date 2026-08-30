package games.brennan.playermob.entity.goal;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.compat.GameRuleCompat;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.compat.WitherSpawnCompat;
import games.brennan.playermob.entity.WitherSummonPolicy;
import games.brennan.playermob.entity.WitherSummonPolicy.Rig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WitherSkullBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.EnumSet;

/**
 * The last resort: a PlayerMob that is losing a fight it cares about <em>builds a wither</em>.
 *
 * <p>This is deliberately the rarest thing a PlayerMob does. Every one of these must hold before it even
 * considers it (see {@link #armed()}): the mob carries the kit (4 soul sand/soil + 3 wither skeleton skulls),
 * it is <b>below half hearts</b>, it is <b>fully aggressive</b> by nature ({@code fightFlight} ≥
 * {@link WitherSummonPolicy#FF_WITHER_MIN}), and it <b>hates</b> the enemy it is fighting. A timid mob flees, a
 * merely-annoyed mob keeps swinging; only a furious, cornered mob that loathes its foe raises a wither.</p>
 *
 * <pre>BUILD (four soul blocks, then the three skulls, one at a time) → FLEE</pre>
 *
 * <p>The rig is the vanilla pattern ({@code ^^^} / {@code ###} / {@code ~#~}) laid a few blocks ahead of the mob
 * toward its target — geometry from {@link WitherSummonPolicy#placementCandidates}. Every block of it must be
 * <b>placeable by hand</b>: inside the mob's block reach and in its line of sight, checked for the whole footprint
 * before building starts and again for each block as it goes down, so a mob knocked out of position walks back
 * into place instead of reaching through a wall. Blocks go in one at a time
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
 * <p>Runs at <b>priority 1</b>, above the priority-2 attack goal — the same slot, and for the same reason, as
 * {@link FlintAndSteelIgniteGoal}: the trigger fires <em>mid-fight</em>, when the attack goal is already running
 * and holding MOVE, and vanilla's {@code GoalSelector} only lets a <em>strictly</em> higher-priority goal preempt
 * a running one. At priority 2 this would never get the slot in the one situation it exists for. The gates below
 * are narrow enough that ordinary combat keeps the slot the rest of the time. No JUMP flag, so the priority-0 {@code FloatGoal} still owns JUMP in water (see the goal-JUMP gotcha). Gated on
 * {@link PlayerMobConfig#witherSummon()} and the {@code mobGriefing} gamerule (it places blocks and spawns a boss
 * that wrecks terrain), and on {@link TrainConfinement} so a train-bound mob doesn't raise a wither in a carriage.</p>
 */
public final class WitherSummonGoal extends Goal implements DescribableGoal {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How close (squared) the target must already be — ~5 blocks, so the rig lands between the mob and it. */
    private static final double SETUP_REACH_SQR = 25.0;
    private static final int SITE_RECHECK_TICKS = 10;       // don't re-scan for a site every single tick
    private static final int BUILD_GAP_MIN = 5;             // min ticks between placing successive rig blocks
    private static final int BUILD_GAP_MAX = 20;            // a 5-20 tick gap, so the mob visibly builds
    private static final int BUILD_STEPS = 7;               // 4 soul blocks + 3 skulls
    /** Vanilla player block reach (4.5 blocks), squared — the mob places what a player could place. */
    private static final double REACH_SQR = 20.25;
    private static final int BUILD_STALL_LIMIT = 60;        // 3s of failing to get back in reach before giving up
    private static final int SPAWN_RETRY_TICKS = 20;        // keep re-offering a finished rig to vanilla for 1s
    private static final double WITHER_SEARCH = 16.0;       // how far to look for "did a wither actually appear?"
    private static final int FLEE_TICKS = 240;              // 12s of running — past the wither's ~11s charge-up
    private static final int FLEE_REPATH_INTERVAL = 20;     // re-pick a retreat spot this often
    private static final double FLEE_SPEED = 1.35;          // faster than a fight walk — this is a real panic
    private static final double FLEE_RADIUS = 16.0;         // retreat-spot search radius
    private static final int FLEE_VERTICAL = 7;             // retreat-spot vertical search range
    private static final double FLEE_SAFE_SQR = 256.0;      // 16 blocks clear of the rig is far enough
    private static final int POST_SUMMON_COOLDOWN = 600;    // 30s before the mob would even consider another
    private static final int FAIL_COOLDOWN = 200;           // 10s pause after a failed rig, so we don't thrash

    private enum Phase { BUILD, FLEE, DONE }

    private final PlayerMobEntity mob;
    private final double speed;

    private Phase phase = Phase.DONE;
    private int cooldown;
    /** Throttles the site scan in {@link #canUse()} so it isn't run every tick while a kitted mob fights. */
    private int siteRecheck;
    /** The site {@link #canUse()} found and {@link #start()} will build on. */
    private Rig pendingRig;
    /** Staged-build cursor: 0-3 soul blocks, 4-5 outer skulls, 6 the centre skull, {@code >=BUILD_STEPS}=done. */
    private int buildStep;
    private int buildDelay;
    private Rig rig;
    /** Ticks spent unable to place the current block (out of reach or sight) — bails at {@link #BUILD_STALL_LIMIT}. */
    private int buildStall;
    /** Ticks left to re-offer a finished rig to vanilla's spawn check (see {@link #tickFlee}). */
    private int spawnRetry;
    private int fleeTicks;
    private int fleeRepathTicks;

    public WitherSummonGoal(PlayerMobEntity mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        // MOVE + LOOK only — no JUMP, so FloatGoal can still own JUMP in water.
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /**
     * Only takes the combat slot when it can act <em>right now</em>: every gate in {@link #armed()}, a target
     * already in build range, and a validated rig site from where the mob stands. That last clause matters as much
     * as the rest — this goal outranks the attack goal, so a version that took the slot and then went looking for
     * somewhere to build left the mob wandering around its enemy without swinging. A mob that can't build never
     * takes the slot at all, and since it's in melee range during a fight anyway, the trigger still lands in
     * exactly the situation the behaviour exists for.
     */
    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (siteRecheck > 0) {
            siteRecheck--;
            return false;
        }
        if (!armed()) {
            return false;
        }
        LivingEntity target = mob.getTarget();
        if (target == null || mob.distanceToSqr(target) > SETUP_REACH_SQR) {
            return false;
        }
        pendingRig = firstBuildableSite(mob.level(), target);
        if (pendingRig == null) {
            siteRecheck = SITE_RECHECK_TICKS; // nowhere to build from here — let the fight carry on
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == Phase.DONE || !mob.isAlive()) {
            return false;
        }
        // Once building starts, finish the rig and the run — bailing would strand a half-built wither.
        return phase == Phase.BUILD || phase == Phase.FLEE;
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
        rig = pendingRig;
        pendingRig = null;
        phase = Phase.BUILD;
        buildStep = 0;
        buildDelay = 0;   // lay the first block on the next build tick
        buildStall = 0;
        spawnRetry = 0;
        debug("raising a wither at {} (target {})", rig.bottom(), nameOfTarget());
    }

    @Override
    public void stop() {
        mob.setSprinting(false);
        mob.getNavigation().stop();
        phase = Phase.DONE;
        spawnRetry = 0;
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
            case BUILD -> tickBuild();
            case FLEE -> tickFlee();
            default -> { /* DONE — canContinueToUse ends the goal next evaluation */ }
        }
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

    /**
     * Every block of the rig must be replaceable and <b>placeable by hand</b> — within the mob's block reach and
     * in its line of sight — and the bottom soul block needs a sturdy floor under it. The reach/sight rule is what
     * keeps the mob honest: it builds what a player standing there could build, never through a wall or at range.
     */
    private boolean canBuild(Level level, Rig candidate) {
        BlockPos floor = candidate.bottom().below();
        if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
            return false;
        }
        for (BlockPos pos : candidate.allPositions()) {
            if (!level.getBlockState(pos).canBeReplaced() || !canReach(level, pos)) {
                return false;
            }
        }
        return true;
    }

    /** True when {@code pos} is within the mob's block reach and it can see the spot it's placing into. */
    private boolean canReach(Level level, BlockPos pos) {
        Vec3 eye = mob.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        if (eye.distanceToSqr(center) > REACH_SQR) {
            return false;
        }
        return hasClearLineOfSight(level, eye, pos, center);
    }

    /**
     * True when a straight line from {@code eye} reaches {@code pos} without passing through another block's
     * collision shape. The same three-clause test {@code RaidContainersGoal#hasClearLineOfSight} uses for chests
     * (a hit on the target block itself, or at/beyond its centre, counts as visible), and {@code COLLIDER} for the
     * same reason: glass occludes, as it does for every other mob.
     */
    private boolean hasClearLineOfSight(Level level, Vec3 eye, BlockPos pos, Vec3 center) {
        BlockHitResult hit = level.clip(new ClipContext(
            eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mob));
        return hit.getType() == HitResult.Type.MISS
            || hit.getBlockPos().equals(pos)
            || hit.getLocation().distanceToSqr(eye) + 1.0e-4 >= center.distanceToSqr(eye);
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
        // Knocked out of position mid-rig? Walk back into reach rather than placing the next block through a
        // wall or from across the clearing — the same rule the site validation applies up front.
        if (!canReach(mob.level(), stepPos(buildStep))) {
            if (++buildStall > BUILD_STALL_LIMIT) {
                phase = Phase.DONE;
                cooldown = mob.reactTicks(FAIL_COOLDOWN);
                return;
            }
            mob.getNavigation().moveTo(rig.midCenter().getX() + 0.5, rig.midCenter().getY(),
                rig.midCenter().getZ() + 0.5, speed);
            return;
        }
        buildStall = 0;
        mob.getNavigation().stop();
        performBuildStep(buildStep);
        mob.swing(InteractionHand.MAIN_HAND);
        if (++buildStep >= BUILD_STEPS) {
            enterFlee();
            return;
        }
        buildDelay = mob.reactRoll(BUILD_GAP_MIN, BUILD_GAP_MAX); // 5-20 ticks, skewed by reaction speed
    }

    /** Where the block for {@code step} goes — soul blocks first (steps 0-3), then the three skulls (4-6). */
    private BlockPos stepPos(int step) {
        return step < WitherSummonPolicy.SOUL_BLOCKS_NEEDED
            ? rig.soulPositions().get(step)
            : rig.skullPositions().get(step - WitherSummonPolicy.SOUL_BLOCKS_NEEDED);
    }

    /** Perform one placement of the staged build — soul blocks first (steps 0-3), then the three skulls (4-6). */
    private void performBuildStep(int step) {
        Level level = mob.level();
        SimpleContainer pack = mob.getInventory();
        BlockPos pos = stepPos(step);
        if (step < WitherSummonPolicy.SOUL_BLOCKS_NEEDED) {
            placeSoulBlock(level, pack, pos);
            return;
        }
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

    /** True while every block of the rig is still the block the mob put there — i.e. the pattern is whole. */
    private boolean patternStands(Level level) {
        for (BlockPos pos : rig.soulPositions()) {
            BlockState state = level.getBlockState(pos);
            if (!state.is(Blocks.SOUL_SAND) && !state.is(Blocks.SOUL_SOIL)) {
                return false;
            }
        }
        for (BlockPos pos : rig.skullPositions()) {
            if (!level.getBlockState(pos).is(Blocks.WITHER_SKELETON_SKULL)) {
                return false;
            }
        }
        return true;
    }

    /** True when a wither is already up near the rig — vanilla's check fired, and there's nothing left to do. */
    private boolean witherNearby(Level level) {
        AABB around = new AABB(rig.bottom()).inflate(WITHER_SEARCH);
        return !level.getEntitiesOfClass(WitherBoss.class, around).isEmpty();
    }

    /**
     * Finish the summon ourselves when vanilla's check has had its chance and declined.
     *
     * <p>{@link WitherSkullBlock#checkSpawn} has to <em>search</em> for the pattern around the skull it was given,
     * and in play it has been seen to leave a complete, correct rig standing with no boss on it — a rig a player
     * could finish by breaking and replacing one skull. The goal doesn't need that search: it knows all seven
     * positions, because it placed them. So it does exactly what vanilla does at coordinates it already has —
     * break the pattern blocks (with their break particles), seat an invulnerable wither on the bottom block, and
     * award the summon criterion to everyone nearby, so <i>Withering Heights</i> unlocks either way.</p>
     */
    private void fallbackSummon(Level level) {
        if (witherNearby(level)) {
            debug("vanilla spawn check raised the wither at {}", rig.bottom());
            return;
        }
        if (!patternStands(level)) {
            debug("rig at {} no longer whole — abandoning it", rig.bottom());
            return;
        }
        for (BlockPos pos : rig.allPositions()) {
            level.levelEvent(2001, pos, Block.getId(level.getBlockState(pos))); // the block-break effect
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
        WitherBoss wither = WitherSpawnCompat.create(level);
        if (wither == null) {
            debug("could not create a wither for the rig at {}", rig.bottom());
            return;
        }
        BlockPos seat = rig.bottom();
        WitherSpawnCompat.seat(wither, seat.getX() + 0.5, seat.getY() + 0.55, seat.getZ() + 0.5, mob.getYRot());
        wither.makeInvulnerable();
        level.addFreshEntity(wither);
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class,
                wither.getBoundingBox().inflate(50.0))) {
            WitherSpawnCompat.awardSummonCriterion(player, wither);
        }
        debug("vanilla check declined the rig at {} — summoned the wither directly", seat);
    }

    /** Opt-in tracing (the existing {@code debugSpawnLog} config), so a misfire says which branch ran. */
    private void debug(String message, Object... args) {
        if (PlayerMobConfig.debugSpawnLog()) {
            LOGGER.info("[playermob] wither: " + message, args);
        }
    }

    /** The current target's name for the trace lines, or {@code "nothing"} if it has none. */
    private String nameOfTarget() {
        LivingEntity target = mob.getTarget();
        return target != null ? target.getName().getString() : "nothing";
    }

    private void enterFlee() {
        phase = Phase.FLEE;
        spawnRetry = SPAWN_RETRY_TICKS;
        fleeTicks = FLEE_TICKS;
        fleeRepathTicks = 0;
        mob.getNavigation().stop();
        mob.setSprinting(true);
    }

    /**
     * Get away from what it just made. The mob runs until it is {@link #FLEE_SAFE_SQR} clear of the rig or
     * {@link #FLEE_TICKS} run out (it may well be boxed in, or already dying), then stands down for a long
     * {@link #POST_SUMMON_COOLDOWN} — one wither per crisis. The window outlasts the boss's invulnerable
     * charge-up on purpose: the blast that ends it is what kills a mob that stopped running too early.
     */
    private void tickFlee() {
        // Keep offering a finished rig to vanilla for a moment. The spawn check is idempotent — it only fires
        // when the whole pattern stands, and consumes the blocks when it does — so re-running it costs nothing
        // and rescues a rig whose last skull landed while some other block was momentarily missing (a complete
        // pattern standing in the world with no boss on it is the one failure this behaviour can leave behind).
        if (spawnRetry > 0) {
            spawnRetry--;
            if (spawnRetry == 0) {
                fallbackSummon(mob.level()); // window closed — finish it ourselves if vanilla didn't
            } else {
                summon(mob.level(), rig.skullCenter());
            }
        }
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
            case BUILD -> name != null ? "raising a wither on " + name : "raising a wither";
            case FLEE -> "running from the wither";
            default -> null;
        };
    }
}
