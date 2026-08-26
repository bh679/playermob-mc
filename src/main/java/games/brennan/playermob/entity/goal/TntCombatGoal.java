package games.brennan.playermob.entity.goal;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.compat.GameRuleCompat;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.TntCombatPolicy;
import games.brennan.playermob.entity.TntCombatPolicy.IgnitionStrategy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * TNT bombardment: a PlayerMob that carries TNT <em>and</em> a way to light it prefers to bomb the
 * enemy it is fighting rather than trade bow/melee blows. It runs at the priority-2 combat tier
 * <em>before</em> {@link WeaponAwareAttackGoal}, so while armed it owns the combat slot; when it runs
 * out of TNT or igniters the goal stands down and the normal fight goal takes back over.
 *
 * <p>One outer Goal owns MOVE+LOOK for the whole bomb cycle (like {@link WeaponAwareAttackGoal} owns
 * the slot across weapon switches), running:</p>
 *
 * <pre>APPROACH → place TNT + light it → back off clear of the blast → (loop while armed, no idling for the boom)</pre>
 *
 * <p>It does <em>not</em> wait for a charge to explode before laying the next — as soon as it's a safe distance
 * from every live {@link PrimedTnt} it lit, it re-engages (and it won't lay a fresh charge while standing in the
 * blast of one still cooking). Against a moving target that reads as a rapid string of bombing runs.</p>
 *
 * <p><b>Lighting</b> is delegated per igniter by {@link TntCombatPolicy}:
 * flint &amp; steel / fire charge right-click the TNT block through the proven, modded-safe
 * {@link CommandedUse} pipeline (vanilla {@code TntBlock.useItemOn} primes it and consumes the igniter);
 * a redstone block / lever / button / pressure plate is physically <em>placed and used</em> — the actual
 * component is seated on the ground beside the TNT in its powered/pressed state so a real redstone signal
 * primes it (a fused {@link PrimedTnt}, just like a player rigging it), and it stays put through the fuse.
 * A direct-prime safety net backs that up so the charge never duds mid-loop. TNT is only consumed on a
 * successful light — a failed ignition rolls the placed block back so the mob never litters unlit TNT.</p>
 *
 * <p>No JUMP flag (so the priority-0 {@code FloatGoal} keeps the mob afloat — see the goal-JUMP gotcha).
 * Gated on {@link PlayerMobConfig#tntCombat()} and the {@code mobGriefing} gamerule (a world-modifying
 * behaviour), and on {@link TrainConfinement} so a train-bound mob doesn't bomb outside its carriage.</p>
 */
public final class TntCombatGoal extends Goal implements DescribableGoal {

    /** How close (squared) the mob gets before it drops the TNT — ~3.5 blocks, so the charge lands on the enemy. */
    private static final double APPROACH_REACH_SQR = 12.25;
    private static final int WALK_TIMEOUT_TICKS = 200;       // 10s to reach the target before giving up
    private static final int FLEE_TICKS = 90;                // safety cap: give up backing off after ~4.5s if boxed in
    private static final double FLEE_SPEED = 1.4;            // sprint clear — the blast is lethal
    private static final int FLEE_REPATH_INTERVAL = 8;       // re-pick a retreat point every 0.4s
    private static final int RETREAT_RADIUS = 10;
    private static final int RETREAT_VERTICAL = 6;
    /** Clear-of-the-blast distance (squared) — ~11 blocks, comfortably past a TNT's (power 4) ~7-8-block hurt
     *  radius. The mob only backs off until no live charge is within this range, then re-engages immediately —
     *  it doesn't idle waiting for the fuse, so it keeps bombing/fighting as long as it's clear of its own TNT. */
    private static final double BLAST_SAFE_SQR = 121.0;
    private static final int POST_CYCLE_COOLDOWN = 10;       // brief settle after a completed bomb before re-arming
    private static final int FAIL_COOLDOWN = 40;             // 2s pause after a failed placement, so we don't thrash

    /** Outcome of an ignition attempt. */
    private enum Outcome { PRIMED, FAILED }

    private enum Phase { APPROACH, FLEE, DONE }

    private final PlayerMobEntity mob;
    private final double speed;

    private Phase phase = Phase.DONE;
    private int walkTicks;
    private int fleeTicks;
    private int fleeRepathTicks;
    private int cooldown;

    public TntCombatGoal(PlayerMobEntity mob, double speed) {
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
        if (phase == Phase.FLEE) {
            return true; // always finish the getaway — the blast is lethal
        }
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && !target.isRemoved();
    }

    /** All the gates: config on, mobGriefing on, a live target we may fight, and TNT + an igniter on hand. */
    private boolean armed() {
        if (!PlayerMobConfig.tntCombat()) {
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
        return TntCombatPolicy.firstTntSlot(mob.getInventory()) >= 0 && hasUsableIgniter();
    }

    /** An igniter in the backpack or already in the main hand (operator-placed). */
    private boolean hasUsableIgniter() {
        return TntCombatPolicy.hasIgniter(mob.getInventory())
            || TntCombatPolicy.isIgniter(mob.getMainHandItem());
    }

    @Override
    public void start() {
        phase = Phase.APPROACH;
        walkTicks = 0;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        phase = Phase.DONE;
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
        if (mob.distanceToSqr(target) <= APPROACH_REACH_SQR) {
            mob.getNavigation().stop();
            if (nearLiveTnt()) {
                // A charge it lit is still cooking right here — clear the blast before laying another.
                enterFlee();
            } else if (placeAndIgnite(target)) {
                enterFlee();
            } else {
                // Couldn't get a charge down — stand down and let the normal fight goal take over for a beat.
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
     * Place a TNT block at a validated spot near {@code target}, then light it with the best igniter.
     * Consumes one TNT only on a successful light; a failed light rolls the block back. Returns whether
     * the mob armed a charge (and should now flee).
     */
    private boolean placeAndIgnite(LivingEntity target) {
        Level level = mob.level();
        Container pack = mob.getInventory();
        int tntSlot = TntCombatPolicy.firstTntSlot(pack);
        if (tntSlot < 0) {
            return false;
        }
        BlockPos pos = findPlacement(target);
        if (pos == null) {
            return false;
        }
        BlockState previous = level.getBlockState(pos);
        level.setBlock(pos, Blocks.TNT.defaultBlockState(), 3);

        Outcome outcome = ignite(pos);
        if (outcome == Outcome.FAILED) {
            // Roll back the TNT block if it's still standing (a successful light already turned it to air/primed).
            if (level.getBlockState(pos).is(Blocks.TNT)) {
                level.setBlock(pos, previous, 3);
            }
            return false;
        }
        pack.removeItem(tntSlot, 1);
        mob.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** Light the TNT at {@code pos} with the mob's best igniter, dispatched by strategy. */
    private Outcome ignite(BlockPos pos) {
        // An igniter already in hand (flint & steel / fire charge) — use it in place, no pack juggling.
        if (TntCombatPolicy.classify(mob.getMainHandItem()) == IgnitionStrategy.RIGHT_CLICK) {
            return igniteRightClick(pos, -1);
        }
        Container pack = mob.getInventory();
        int slot = TntCombatPolicy.selectIgniterSlot(pack);
        if (slot < 0) {
            return Outcome.FAILED;
        }
        ItemStack igniter = pack.getItem(slot);
        IgnitionStrategy strategy = TntCombatPolicy.classify(igniter);
        if (strategy == null) {
            return Outcome.FAILED;
        }
        return switch (strategy) {
            case RIGHT_CLICK -> igniteRightClick(pos, slot);
            case PLACE_PRIME -> placeActivateIgniter(pos, slot, igniter);
        };
    }

    /**
     * Right-click the TNT block with flint &amp; steel / fire charge through {@link CommandedUse}. The igniter
     * must be in the main hand for the interaction to consume it and write the result back; if it lives in the
     * pack ({@code packSlot >= 0}) it's swapped in transiently and the reduced stack returned afterwards, so the
     * mob's held weapon and pack bookkeeping are preserved between cycles.
     */
    private Outcome igniteRightClick(BlockPos pos, int packSlot) {
        SimpleContainer pack = mob.getInventory();
        ItemStack stashed = ItemStack.EMPTY;
        boolean swapped = false;
        if (packSlot >= 0) {
            ItemStack igniter = pack.getItem(packSlot);
            pack.setItem(packSlot, ItemStack.EMPTY);
            stashed = mob.getMainHandItem();
            mob.setItemSlot(EquipmentSlot.MAINHAND, igniter);
            swapped = true;
        }

        CommandedUse.perform(mob, mob.getMainHandItem(), null, pos);
        // Vanilla TntBlock.useItemOn primes the TNT (block → air) and consumes the igniter.
        boolean primed = !mob.level().getBlockState(pos).is(Blocks.TNT);

        if (swapped) {
            ItemStack remainder = mob.getMainHandItem(); // reduced igniter, or empty if it broke / was spent
            mob.setItemSlot(EquipmentSlot.MAINHAND, stashed);
            if (!remainder.isEmpty()) {
                ItemStack leftover = pack.addItem(remainder);
                if (!leftover.isEmpty()) {
                    mob.dropAtLocation(leftover);
                }
            }
        }
        return primed ? Outcome.PRIMED : Outcome.FAILED;
    }

    /**
     * A redstone-family igniter (redstone block / lever / button / pressure plate): seat the actual component
     * on the ground right beside the TNT in its powered/pressed state, so real redstone feeds the TNT and primes
     * it — the mob visibly places and uses it, and (unlike an on-top component) it stays put through the whole
     * fuse instead of popping the instant the TNT loses its support. Consumes one from the pack. As a safety net
     * (the charge must never dud mid-loop) it also primes directly if the block somehow survived the update.
     */
    private Outcome placeActivateIgniter(BlockPos tnt, int packSlot, ItemStack igniter) {
        BlockState state = activatedState(igniter);
        if (state == null) {
            return Outcome.FAILED;
        }
        Level level = mob.level();
        BlockPos spot = componentSpot(tnt);
        if (spot == null) {
            return Outcome.FAILED; // nowhere to seat the component
        }
        level.setBlock(spot, state, 3); // flag 3 → neighbour-notify: the powered component primes the adjacent TNT
        mob.getInventory().removeItem(packSlot, 1);
        if (level.getBlockState(tnt).is(Blocks.TNT)) {
            primeTnt(tnt); // redstone didn't take (shouldn't happen) — light it outright so the cycle continues
        }
        return Outcome.PRIMED;
    }

    /**
     * A floor-supported spot to seat the redstone component right beside the TNT, so it stays visible through the
     * whole fuse (a component ON the TNT would pop the instant the TNT primes and loses its support). Prefers a
     * ground-level horizontal neighbour; falls back to on-top only if the mob is boxed in on all sides.
     */
    private BlockPos componentSpot(BlockPos tnt) {
        Level level = mob.level();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = tnt.relative(dir);
            if (level.getBlockState(side).canBeReplaced()
                    && level.getBlockState(side.below()).isFaceSturdy(level, side.below(), Direction.UP)) {
                return side;
            }
        }
        BlockPos on = tnt.above();
        return level.getBlockState(on).canBeReplaced() ? on : null;
    }

    /**
     * The placed block for a redstone-family igniter, made to emit a redstone signal into the TNT below:
     * FACE=FLOOR (so levers/buttons mount flat on top) and POWERED / POWER=15 (so it's already "on"/"pressed").
     * A redstone block has no such properties — it powers its neighbours inherently.
     */
    private static BlockState activatedState(ItemStack igniter) {
        if (!(igniter.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        if (state.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            state = state.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR);
        }
        if (state.hasProperty(BlockStateProperties.POWERED)) {
            state = state.setValue(BlockStateProperties.POWERED, true);
        }
        if (state.hasProperty(BlockStateProperties.POWER)) {
            state = state.setValue(BlockStateProperties.POWER, 15); // weighted pressure plates
        }
        return state;
    }

    /**
     * Replace the TNT block at {@code pos} with a fused {@link PrimedTnt} owned by the mob, with the prime
     * sound — mirroring vanilla {@code TntBlock.explode}. Used as the redstone-ignition safety net.
     */
    private boolean primeTnt(BlockPos pos) {
        Level level = mob.level();
        if (!level.getBlockState(pos).is(Blocks.TNT)) {
            return false;
        }
        double x = pos.getX() + 0.5;
        double y = pos.getY();
        double z = pos.getZ() + 0.5;
        PrimedTnt primed = new PrimedTnt(level, x, y, z, mob);
        level.addFreshEntity(primed);
        level.removeBlock(pos, false);
        level.playSound(null, x, pos.getY() + 0.5, z, SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, 1.0F);
        return true;
    }

    /** First replaceable, floor-supported candidate spot to drop the TNT, or null if none is reachable. */
    private BlockPos findPlacement(LivingEntity target) {
        Level level = mob.level();
        for (BlockPos cand : TntCombatPolicy.placementCandidates(mob.blockPosition(), target.blockPosition())) {
            BlockState here = level.getBlockState(cand);
            if (!here.canBeReplaced()) {
                continue;
            }
            BlockPos below = cand.below();
            if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                return cand.immutable();
            }
        }
        return null;
    }

    private void enterFlee() {
        phase = Phase.FLEE;
        fleeTicks = FLEE_TICKS;
        fleeRepathTicks = 0;
        mob.getNavigation().stop();
    }

    /**
     * Back off only until the mob is clear of every live charge it lit, then re-engage <em>immediately</em> —
     * no idling to watch the fuse burn down. The mob keeps bombing/fighting as long as it isn't standing in its
     * own blast; a {@link #FLEE_TICKS} cap covers the rare case where it can't get clear (boxed in).
     */
    private void tickFlee() {
        PrimedTnt danger = nearestLiveCharge();
        if (danger == null || --fleeTicks <= 0) {
            if (armed()) {
                phase = Phase.APPROACH;
                walkTicks = 0;
            } else {
                phase = Phase.DONE;
                cooldown = mob.reactTicks(POST_CYCLE_COOLDOWN);
            }
            return;
        }
        if (++fleeRepathTicks >= mob.reactTicks(FLEE_REPATH_INTERVAL) || mob.getNavigation().isDone()) {
            fleeRepathTicks = 0;
            Vec3 away = DefaultRandomPos.getPosAway(mob, RETREAT_RADIUS, RETREAT_VERTICAL, danger.position());
            if (away != null) {
                mob.getNavigation().moveTo(away.x, away.y, away.z, FLEE_SPEED);
            }
        }
    }

    /** True while a live primed TNT sits within blast range of the mob (so it shouldn't lay another there). */
    private boolean nearLiveTnt() {
        return nearestLiveCharge() != null;
    }

    /** The nearest live {@link PrimedTnt} within {@link #BLAST_SAFE_SQR} of the mob, or {@code null} if it's clear. */
    private PrimedTnt nearestLiveCharge() {
        double range = Math.sqrt(BLAST_SAFE_SQR);
        PrimedTnt nearest = null;
        double best = BLAST_SAFE_SQR;
        for (PrimedTnt t : mob.level().getEntitiesOfClass(
                PrimedTnt.class, mob.getBoundingBox().inflate(range), PrimedTnt::isAlive)) {
            double d = mob.distanceToSqr(t);
            if (d < best) {
                best = d;
                nearest = t;
            }
        }
        return nearest;
    }

    @Override
    public String objective() {
        return "Bombing";
    }

    @Override
    public String subObjective() {
        LivingEntity target = mob.getTarget();
        String name = target != null ? target.getName().getString() : null;
        return switch (phase) {
            case APPROACH -> name != null ? "closing on " + name : "closing in";
            case FLEE -> "fleeing the blast";
            default -> null;
        };
    }
}
