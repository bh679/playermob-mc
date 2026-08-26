package games.brennan.playermob.entity.goal;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.compat.GameRuleCompat;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.FlintAndSteelPolicy;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/**
 * Flint and steel, used the way a player uses it: to <b>cook a kill</b> and to <b>torch the ground
 * under an enemy</b>.
 *
 * <ul>
 *   <li><b>Cook the kill.</b> When the animal it's fighting is one swing from death
 *       ({@link FlintAndSteelPolicy#wantsCookFinisher}), the mob lights the block the animal is
 *       standing in first. The animal dies burning, so vanilla's smelting loot condition fires and
 *       the meat drops <em>cooked</em>. A short while later ({@link FlintAndSteelPolicy#BURN_MIN_TICKS}–
 *       {@link FlintAndSteelPolicy#BURN_MAX_TICKS} ticks) it stamps that fire back out — the animal
 *       keeps its ~8 s entity burn either way, so the cook still lands.</li>
 *   <li><b>Occasional combat ignite.</b> Against anything else — a hostile, a player, another
 *       PlayerMob — that isn't <em>already</em> on fire, it occasionally does the same thing and
 *       leaves the fire standing as an area hazard. "Occasionally" is a randomized
 *       {@link FlintAndSteelPolicy#COMBAT_GAP_MIN_TICKS}–{@link FlintAndSteelPolicy#COMBAT_GAP_MAX_TICKS}
 *       tick gap, re-seeded whenever the target changes so it never fires the instant a fight starts,
 *       under a hard shared cap of {@link FlintAndSteelPolicy#RATE_MAX_IGNITES} lights per
 *       {@link FlintAndSteelPolicy#RATE_WINDOW_TICKS} ticks.</li>
 * </ul>
 *
 * <p><b>Always the ground, never the mob.</b> There is no "set entity on fire" shortcut here: the mob
 * walks into reach, <em>draws</em> the flint and steel (a {@link FlintAndSteelPolicy#SWAP_MIN_TICKS}–
 * {@link FlintAndSteelPolicy#SWAP_MAX_TICKS} tick wind-up, not a snap), right-clicks the <em>up face
 * of the block below its target</em> through the real interaction pipeline
 * ({@link CommandedUse#performOnFace} — durability, sound and loader use-events all fire), and the
 * target catches alight by standing in the resulting fire. If vanilla refuses the light (rain, a
 * surface fire can't sit on) the attempt simply fails and the mob puts the tool away, exactly as a
 * player's would — there is deliberately no force-place fallback.</p>
 *
 * <p>Runs at priority 1 — <em>above</em> {@link WeaponAwareAttackGoal}, alongside
 * {@link DoorOperationGoal}, and for the same reason. Both of this goal's triggers fire in the middle of
 * a fight the attack goal is already running, and a same-priority goal can never interrupt one that's
 * already running (vanilla's GoalSelector only lets a strictly higher-priority goal preempt) — at the
 * priority-2 combat tier this would simply never get the MOVE/LOOK slot. From priority 1 it takes that
 * slot for the length of the ritual, so the attack goal can't land a bare-fisted (or flint-and-steel) hit
 * mid-swap, and hands it straight back when it's done. No JUMP flag, so the priority-0 {@code FloatGoal}
 * keeps the mob afloat — see the goal-JUMP gotcha in {@link TntCombatGoal}. Gated on
 * {@link PlayerMobConfig#flintAndSteelCombat()}, on the {@code mobGriefing} gamerule (it places a real
 * fire block), and on {@link TrainConfinement}.</p>
 */
public final class FlintAndSteelIgniteGoal extends Goal implements DescribableGoal {

    /** How close (squared) the mob gets before it lights the ground — ~3.5 blocks, matching {@link TntCombatGoal}. */
    private static final double REACH_SQR = 12.25;
    /**
     * Give up walking in after ~5s and let the normal fight goal take back over. Deliberately
     * <em>not</em> reaction-scaled: it's a give-up guard, not a reaction — scaling it would make a
     * quick-reacting mob abandon the approach sooner, which is backwards.
     */
    private static final int WALK_TIMEOUT_TICKS = 100;
    /**
     * Pause before this goal re-arms after a failed attempt, so it doesn't thrash the combat slot.
     * The neutral (reaction 5) baseline — {@link #fail()} scales it.
     */
    private static final int FAIL_COOLDOWN = 40;

    /** Which drive triggered this run — only {@link Mode#COOK} puts its fire back out. */
    private enum Mode { COOK, COMBAT }

    private enum Phase { APPROACH, SWAP_IN, IGNITE, WAIT_BURN, EXTINGUISH, SWAP_OUT, DONE }

    private final PlayerMobEntity mob;
    private final double speed;

    private Phase phase = Phase.DONE;
    private Mode mode = Mode.COMBAT;
    private BlockPos firePos;
    private int waitTicks;
    private int walkTicks;
    private int cooldown;

    /** Rolling stamps of recent lights — the shared 5-per-10s cap. Replaced wholesale, never mutated. */
    private long[] rateWindow = FlintAndSteelPolicy.newRateWindow();
    /** Earliest game tick at which a <em>combat</em> ignite may be considered. */
    private long nextCombatTick = Long.MIN_VALUE;
    /** Target the combat gap was last seeded for, so a new opponent re-rolls the wait. */
    private int gapSeededFor = -1;

    public FlintAndSteelIgniteGoal(PlayerMobEntity mob, double speed) {
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
        if (!PlayerMobConfig.flintAndSteelCombat() || !GameRuleCompat.mobGriefing(mob.level())) {
            return false;
        }
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive() || target.isRemoved()) {
            return false;
        }
        if (!TrainConfinement.allowsTarget(mob, target)) {
            return false;
        }
        if (!FlintAndSteelPolicy.hasFlintAndSteel(mob.getInventory(), mob.getMainHandItem())) {
            return false;
        }
        long now = mob.level().getGameTime();
        if (!FlintAndSteelPolicy.withinRate(rateWindow, now)) {
            return false;
        }
        // A fresh opponent re-rolls the "occasionally" wait, so the mob never opens a fight with fire.
        if (gapSeededFor != target.getId()) {
            gapSeededFor = target.getId();
            nextCombatTick = now + FlintAndSteelPolicy.combatGapTicks(mob.getRandom());
        }
        if (FlintAndSteelPolicy.wantsCookFinisher(target, mob.getAttributeValue(Attributes.ATTACK_DAMAGE))) {
            mode = Mode.COOK;
            return true;
        }
        if (now >= nextCombatTick && FlintAndSteelPolicy.wantsCombatIgnite(target)) {
            mode = Mode.COMBAT;
            return true;
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == Phase.DONE || !mob.isAlive()) {
            return false;
        }
        if (phase == Phase.APPROACH || phase == Phase.SWAP_IN) {
            // Nothing committed yet — drop it if the target is gone.
            LivingEntity target = mob.getTarget();
            return target != null && target.isAlive() && !target.isRemoved();
        }
        // Past the point of no return: always finish the burn / put-out / put-the-tool-away, even if the
        // target dies mid-ritual (which, for the cooking finisher, is exactly the expected outcome).
        return true;
    }

    @Override
    public void start() {
        phase = Phase.APPROACH;
        walkTicks = 0;
        firePos = null;
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        phase = Phase.DONE;
        firePos = null;
        // Never leave the mob standing in a fight holding a flint and steel.
        restoreWeapon();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        switch (phase) {
            case APPROACH -> tickApproach();
            case SWAP_IN -> tickSwapIn();
            case IGNITE -> tickIgnite();
            case WAIT_BURN -> tickWaitBurn();
            case EXTINGUISH -> tickExtinguish();
            case SWAP_OUT -> tickSwapOut();
            default -> { /* DONE — canContinueToUse ends the goal next evaluation */ }
        }
    }

    /** Walk into arm's reach of the target, then start drawing the tool. */
    private void tickApproach() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) {
            phase = Phase.DONE;
            return;
        }
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (mob.distanceToSqr(target) <= REACH_SQR && mob.hasLineOfSight(target)) {
            mob.getNavigation().stop();
            if (ignitionSpot(target) == null) {
                // Nowhere sane to put a fire under this one (in water, mid-air, standing on us) — don't
                // even bother drawing the tool.
                fail();
                return;
            }
            phase = Phase.SWAP_IN;
            waitTicks = swapDelayTicks();
            return;
        }
        if (++walkTicks > WALK_TIMEOUT_TICKS) {
            fail();
            return;
        }
        mob.getNavigation().moveTo(target, speed);
    }

    /** Deliberate wind-up, then the flint and steel actually comes into the hand. */
    private void tickSwapIn() {
        LivingEntity target = mob.getTarget();
        if (target != null) {
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
        if (--waitTicks > 0) {
            return;
        }
        if (!mob.equipWeapon(Items.FLINT_AND_STEEL)) {
            // Shouldn't happen — canUse() just confirmed one — but bail cleanly if it did.
            fail();
            return;
        }
        phase = Phase.IGNITE;
    }

    /** Right-click the up face of the block below the target, exactly as a player would. */
    private void tickIgnite() {
        LivingEntity target = mob.getTarget();
        BlockPos spot = target != null && target.isAlive() ? ignitionSpot(target) : null;
        if (spot == null) {
            // Died or moved somewhere unlightable while we were drawing — just put the tool away.
            enterSwapOut();
            return;
        }
        mob.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        // The block the target stands ON is the one that gets clicked; the fire lands on its up face,
        // i.e. inside the target's own block.
        CommandedUse.performOnFace(mob, spot.below(), Direction.UP);
        mob.swing(InteractionHand.MAIN_HAND);

        if (!isFire(mob.level().getBlockState(spot))) {
            // Vanilla refused the light (rain, an unsuitable surface). A player's wouldn't have worked
            // either — no force-place fallback; stow the tool and try again later.
            enterSwapOut();
            return;
        }

        firePos = spot;
        long now = mob.level().getGameTime();
        rateWindow = FlintAndSteelPolicy.recordIgnite(rateWindow, now);
        if (mode == Mode.COOK) {
            phase = Phase.WAIT_BURN;
            // Not reaction-scaled: how long a fire takes to cook meat is a property of the world, not of
            // how quickly this mob notices things.
            waitTicks = FlintAndSteelPolicy.burnTicks(mob.getRandom());
        } else {
            // Combat fire is left standing as an area hazard; just re-roll the "occasionally" gap.
            nextCombatTick = now + FlintAndSteelPolicy.combatGapTicks(mob.getRandom());
            enterSwapOut();
        }
    }

    /** Let it burn — long enough for the animal to catch, short enough not to start a forest fire. */
    private void tickWaitBurn() {
        if (firePos != null) {
            mob.getLookControl().setLookAt(firePos.getX() + 0.5, firePos.getY(), firePos.getZ() + 0.5);
        }
        if (--waitTicks <= 0) {
            phase = Phase.EXTINGUISH;
        }
    }

    /** Stamp the cooking fire back out. The animal keeps burning — that's what cooks the drop. */
    private void tickExtinguish() {
        Level level = mob.level();
        if (firePos != null && isFire(level.getBlockState(firePos))) {
            level.removeBlock(firePos, false);
            level.playSound(null, firePos.getX() + 0.5, firePos.getY() + 0.5, firePos.getZ() + 0.5,
                SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0F, 1.0F);
            mob.swing(InteractionHand.MAIN_HAND);
        }
        firePos = null;
        enterSwapOut();
    }

    /** Same deliberate wind-up on the way back to a real weapon. */
    private void tickSwapOut() {
        if (--waitTicks > 0) {
            return;
        }
        restoreWeapon();
        phase = Phase.DONE;
    }

    private void enterSwapOut() {
        phase = Phase.SWAP_OUT;
        waitTicks = swapDelayTicks();
    }

    /** Hand the mob back a proper weapon; {@link WeaponAwareAttackGoal} re-selects on its next tick. */
    private void restoreWeapon() {
        if (!FlintAndSteelPolicy.isFlintAndSteel(mob.getMainHandItem())) {
            return;
        }
        LivingEntity target = mob.getTarget();
        if (target != null && target.isAlive()) {
            mob.equipBestWeaponForTarget(target);
        } else {
            mob.equipBestMeleeInHand();
        }
        // Both of those are no-ops for a mob that owns no weapon at all — it would be left punching with
        // the flint and steel. Stow it by hand so the tool always goes back in the pack.
        if (FlintAndSteelPolicy.isFlintAndSteel(mob.getMainHandItem())) {
            ItemStack tool = mob.getMainHandItem();
            mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            ItemStack leftover = mob.getInventory().addItem(tool);
            if (!leftover.isEmpty()) {
                mob.dropAtLocation(leftover);
            }
        }
    }

    /** Stand down for a beat and let the normal fight goal take the slot back. */
    private void fail() {
        phase = Phase.DONE;
        cooldown = mob.reactTicks(FAIL_COOLDOWN);
    }

    /**
     * Wind-up for drawing the flint and steel or putting it away, skewed low for a quick-reacting mob
     * and high for a slow one. Both swap sites run through here, so this one call is where reaction
     * speed reaches the whole ritual; at the neutral reaction speed it is the uniform
     * {@link FlintAndSteelPolicy#SWAP_MIN_TICKS}–{@link FlintAndSteelPolicy#SWAP_MAX_TICKS} roll it replaces.
     */
    private int swapDelayTicks() {
        return mob.reactRoll(FlintAndSteelPolicy.SWAP_MIN_TICKS, FlintAndSteelPolicy.SWAP_MAX_TICKS);
    }

    /**
     * The block position the fire would occupy — the target's own feet block — or {@code null} if it
     * isn't a sane place for one: not replaceable, waterlogged/flooded, no floor to sit on, or the mob
     * is standing there itself (no self-immolation).
     */
    private BlockPos ignitionSpot(LivingEntity target) {
        Level level = mob.level();
        BlockPos spot = target.blockPosition();
        if (spot.equals(mob.blockPosition())) {
            return null;
        }
        BlockState here = level.getBlockState(spot);
        if (!here.canBeReplaced() || !level.getFluidState(spot).isEmpty()) {
            return null;
        }
        BlockPos below = spot.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return null;
        }
        return spot.immutable();
    }

    /** Fire or soul fire — whichever the surface produced. */
    private static boolean isFire(BlockState state) {
        return state.is(BlockTags.FIRE);
    }

    @Override
    public String objective() {
        return "Burning";
    }

    @Override
    public String subObjective() {
        LivingEntity target = mob.getTarget();
        String name = target != null ? target.getName().getString() : null;
        return switch (phase) {
            case APPROACH -> name != null ? "closing on " + name : "closing in";
            case SWAP_IN -> "drawing flint and steel";
            case IGNITE -> name != null ? "lighting the ground under " + name : "lighting the ground";
            case WAIT_BURN -> "letting it cook";
            case EXTINGUISH -> "putting the fire out";
            case SWAP_OUT -> "stowing flint and steel";
            default -> null;
        };
    }
}
