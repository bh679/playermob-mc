package games.brennan.playermob.entity.goal;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.compat.EndCrystalCompat;
import games.brennan.playermob.compat.GameRuleCompat;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.EndCrystalCombatPolicy;
import games.brennan.playermob.entity.EndCrystalCombatPolicy.Layout;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/**
 * End-crystal bombardment: a PlayerMob that carries an end crystal, an obsidian block for its base, and solid
 * blocks to hide behind prefers to bomb the enemy it is fighting rather than trade bow/melee blows. It runs at the
 * priority-2 combat tier <em>before</em> {@link WeaponAwareAttackGoal} (right after {@link TntCombatGoal}), so while
 * armed it owns the combat slot; when it runs out of the kit the goal stands down and the normal fight goal takes over.
 *
 * <p>Unlike TNT, a crystal has <b>no fuse</b> — it is an inert bomb until something <em>damages</em> it, and a naive
 * point-blank melee hit (power-6 blast, radius ≈12) is suicide. So the mob uses the survival technique a player uses:
 * it builds a little bunker and <em>punches the crystal from behind cover</em>:</p>
 *
 * <pre>APPROACH → BUILD (obsidian base → crystal → a 2-tall cover, one block at a time) → BRACE (crouch, raise shield, punch) → loop</pre>
 *
 * <ol>
 *   <li>an <b>obsidian base</b> two blocks toward the target, with the crystal seated on top of it;</li>
 *   <li>a <b>2-block-tall cover</b> — any solid, full-cube blocks from the kit — one block toward the target, i.e.
 *       between the mob and the crystal, stacked two high so it fully hides the crouched mob from the elevated blast;</li>
 *   <li><b>crouch</b> behind it ({@code setShiftKeyDown} + {@link Pose#CROUCHING}) to shrink the mob's blast exposure;</li>
 *   <li>raise a <b>shield</b> in the off-hand if the mob has one (through the ~5-tick warm-up, so it actually blocks);</li>
 *   <li><b>punch</b> the crystal — a real melee hit via {@link EndCrystalCompat#punch} ({@code Mob.doHurtTarget}) — which
 *       detonates it while the cover + crouch + shield absorb the blast.</li>
 * </ol>
 *
 * <p>The crystal stays inert through the whole setup (steps 1–4 place blocks / raise a shield but never damage it), so
 * rigging the bunker is safe — the boom only happens on the punch, when the mob is already covered. No JUMP flag (so the
 * priority-0 {@code FloatGoal} keeps the mob afloat — see the goal-JUMP gotcha). Gated on
 * {@link PlayerMobConfig#endCrystalCombat()} and the {@code mobGriefing} gamerule (a world-modifying behaviour), and on
 * {@link TrainConfinement} so a train-bound mob doesn't bomb outside its carriage.</p>
 */
public final class EndCrystalCombatGoal extends Goal implements DescribableGoal {

    /** How close (squared) the mob gets before it rigs — ~4.5 blocks, so the crystal (placed 2 ahead) lands near the enemy. */
    private static final double SETUP_REACH_SQR = 20.25;
    private static final int WALK_TIMEOUT_TICKS = 200;      // 10s to reach the target before giving up
    private static final int BRACE_TICKS = 8;               // hold behind cover before punching (≥ the 5-tick shield warm-up)
    private static final int BUILD_GAP_MIN = 5;             // min ticks between placing successive rig blocks
    private static final int BUILD_GAP_MAX = 20;            // a 5-20 tick gap, so the mob visibly builds
    private static final int BUILD_STEPS = 4;               // base, crystal, cover, cover-top
    private static final int POST_CYCLE_COOLDOWN = 10;      // brief settle after a completed detonation before re-arming
    private static final int FAIL_COOLDOWN = 40;            // 2s pause after a failed rig, so we don't thrash

    private enum Phase { APPROACH, BUILD, BRACE, DONE }

    private final PlayerMobEntity mob;
    private final double speed;

    private Phase phase = Phase.DONE;
    private int walkTicks;
    private int braceTicks;
    private int cooldown;
    /** Staged-build cursor: 0=base, 1=crystal, 2=cover, 3=cover-top, {@code >=BUILD_STEPS}=done. */
    private int buildStep;
    private int buildDelay;
    private BlockPos buildBase;
    private BlockPos buildWall;
    private EndCrystal placedCrystal;
    /** The off-hand stack displaced when a shield was swapped in for the brace (restored afterwards). */
    private ItemStack stashedOffhand = ItemStack.EMPTY;
    /** A shield is in the off-hand and should be raised while bracing. */
    private boolean shieldEngaged;
    /** The shield was moved in from the pack (must be returned + the off-hand restored on un-brace). */
    private boolean swappedShield;

    public EndCrystalCombatGoal(PlayerMobEntity mob, double speed) {
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
        if (phase == Phase.BUILD || phase == Phase.BRACE) {
            return true; // once building starts, finish the rig + punch — bailing would strand a half-built bomb
        }
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && !target.isRemoved();
    }

    /** All the gates: config on, a live target we may fight, mobGriefing on, not train-confined, and a full kit on hand. */
    private boolean armed() {
        if (!PlayerMobConfig.endCrystalCombat()) {
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
        return EndCrystalCombatPolicy.hasKit(mob.getInventory());
    }

    @Override
    public void start() {
        phase = Phase.APPROACH;
        walkTicks = 0;
        placedCrystal = null;
    }

    @Override
    public void stop() {
        endBrace();
        mob.getNavigation().stop();
        phase = Phase.DONE;
        placedCrystal = null;
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
            case BRACE -> tickBrace();
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
                // Couldn't build a bunker here — stand down and let the normal fight goal take over for a beat.
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
     * Validate a bunker site and begin building it. The layout (obsidian base + crystal on top + a 2-block-tall cover
     * between the mob and the crystal) is checked up front so a doomed rig never starts, then the blocks go up one per
     * {@link #tickBuild() build tick} with a short gap between each — so the mob is seen deliberately assembling its
     * bunker rather than snapping it into place. Returns whether building started (else the caller stands the goal down).
     */
    private boolean startBuild(LivingEntity target) {
        SimpleContainer pack = mob.getInventory();
        if (!EndCrystalCombatPolicy.hasKit(pack)) {
            return false;
        }
        Level level = mob.level();
        Layout site = firstBuildableSite(level, target);
        if (site == null) {
            return false; // no buildable bunker here in any facing — fall back to normal combat for a beat
        }
        buildBase = site.base();
        buildWall = site.wall();
        buildStep = 0;
        buildDelay = 0;               // lay the first block on the next build tick
        placedCrystal = null;
        mob.equipBestMeleeInHand();   // punch with a weapon, not a bow
        equipShieldIfAvailable(pack); // shield to the off-hand now; raised once the cover is up (brace)
        phase = Phase.BUILD;
        return true;
    }

    /**
     * The first candidate rig site (best-aligned facing first) whose obsidian base is buildable and whose 2-tall cover
     * column is clear, or {@code null} if none of the mob's facings work here. Trying several facings — like the TNT
     * bomber's placement candidates — is what lets a kitted mob rig on real terrain instead of only open flats.
     */
    private Layout firstBuildableSite(Level level, LivingEntity target) {
        for (Layout candidate : EndCrystalCombatPolicy.placementCandidates(mob.blockPosition(), target.blockPosition())) {
            if (canPlaceBase(level, candidate.base()) && canPlaceCover(level, candidate.wall())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Lay the bunker one block at a time, {@link #BUILD_GAP_MIN}–{@link #BUILD_GAP_MAX} ticks apart, so the mob
     * visibly builds rather than
     * conjuring it: obsidian base → crystal on top → the two cover blocks between it and the crystal. When the last
     * block is down it braces. {@link #canContinueToUse} keeps the goal alive so a started rig always finishes.
     */
    private void tickBuild() {
        mob.getLookControl().setLookAt(buildBase.getX() + 0.5, buildBase.getY() + 1.0, buildBase.getZ() + 0.5);
        if (buildDelay > 0) {
            buildDelay--;
            return;
        }
        performBuildStep(buildStep);
        if (++buildStep >= BUILD_STEPS) {
            enterBrace();
            return;
        }
        buildDelay = mob.reactRoll(BUILD_GAP_MIN, BUILD_GAP_MAX); // 5-20 ticks, skewed by reaction speed
    }

    /** Perform one placement of the staged build (see {@link #tickBuild()}), swinging as the mob sets each block. */
    private void performBuildStep(int step) {
        Level level = mob.level();
        SimpleContainer pack = mob.getInventory();
        switch (step) {
            case 0 -> { // obsidian base
                int obsidianSlot = EndCrystalCombatPolicy.firstObsidianSlot(pack);
                if (obsidianSlot >= 0) {
                    level.setBlock(buildBase, Blocks.OBSIDIAN.defaultBlockState(), 3);
                    pack.removeItem(obsidianSlot, 1);
                }
            }
            case 1 -> { // seat the crystal on the base
                EndCrystal crystal = new EndCrystal(level,
                    buildBase.getX() + 0.5, buildBase.getY() + 1, buildBase.getZ() + 0.5);
                crystal.setShowBottom(false);
                level.addFreshEntity(crystal);
                placedCrystal = crystal;
            }
            case 2 -> placeCover(level, pack, buildWall);          // ground-level cover
            default -> placeCover(level, pack, buildWall.above()); // and one on top → a 2-tall wall
        }
        mob.swing(InteractionHand.MAIN_HAND);
    }

    /** A replaceable, floor-supported spot for the obsidian base with a clear block above it for the crystal to seat. */
    private boolean canPlaceBase(Level level, BlockPos base) {
        if (!level.getBlockState(base).canBeReplaced()) {
            return false;
        }
        BlockPos below = base.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return false;
        }
        return level.getBlockState(base.above()).canBeReplaced(); // the crystal's own space
    }

    /** Both blocks of the 2-tall cover column must be replaceable (no support needed — they only occlude the blast). */
    private boolean canPlaceCover(Level level, BlockPos wall) {
        return level.getBlockState(wall).canBeReplaced()
            && level.getBlockState(wall.above()).canBeReplaced();
    }

    /** Place one cover block at {@code pos} from the best remaining cover slot (non-obsidian preferred), if any. */
    private void placeCover(Level level, SimpleContainer pack, BlockPos pos) {
        int slot = EndCrystalCombatPolicy.selectCoverSlot(pack);
        if (slot < 0) {
            return;
        }
        BlockState state = ((BlockItem) pack.getItem(slot).getItem()).getBlock().defaultBlockState();
        level.setBlock(pos, state, 3);
        pack.removeItem(slot, 1);
    }

    private void enterBrace() {
        phase = Phase.BRACE;
        braceTicks = 0;
    }

    /**
     * Crouch behind the cover, raise the shield through its warm-up, then punch the crystal to set it off. The mob does
     * not move here — it holds its little bunker; {@link #canContinueToUse} keeps the goal alive so the brace always
     * finishes.
     */
    private void tickBrace() {
        // Crouch (re-asserted each tick — pose can be recomputed elsewhere).
        mob.setShiftKeyDown(true);
        mob.setPose(Pose.CROUCHING);
        if (placedCrystal != null && placedCrystal.isAlive()) {
            mob.getLookControl().setLookAt(placedCrystal, 30.0F, 30.0F);
        }
        // Raise the shield (if one is in the off-hand) and let it warm up before the blast.
        if (shieldEngaged && !mob.isUsingItem() && mob.getOffhandItem().is(Items.SHIELD)) {
            mob.startUsingItem(InteractionHand.OFF_HAND);
        }
        if (++braceTicks < mob.reactTicks(BRACE_TICKS)) {
            return;
        }
        // Braced — punch the crystal (a real melee hit) to detonate it, covered by the block + crouch + shield.
        if (placedCrystal != null && placedCrystal.isAlive()) {
            mob.swing(InteractionHand.MAIN_HAND);
            EndCrystalCompat.punch(mob, placedCrystal);
        }
        placedCrystal = null;
        endBrace();
        // Re-engage immediately if still armed (next bombing run), else stand down for the normal fight goal.
        if (armed()) {
            phase = Phase.APPROACH;
            walkTicks = 0;
        } else {
            phase = Phase.DONE;
            cooldown = mob.reactTicks(POST_CYCLE_COOLDOWN);
        }
    }

    /**
     * If the mob carries a vanilla shield (already in the off-hand, or drawn from the pack), make sure it's in the
     * off-hand for the brace. A swapped-in shield stashes whatever the off-hand held, restored on {@link #endBrace}.
     */
    private void equipShieldIfAvailable(SimpleContainer pack) {
        if (mob.getOffhandItem().is(Items.SHIELD)) {
            shieldEngaged = true;
            return;
        }
        int shieldSlot = EndCrystalCombatPolicy.firstShieldSlot(pack);
        if (shieldSlot < 0) {
            return; // no shield — rely on the cover + crouch
        }
        ItemStack shield = pack.removeItem(shieldSlot, 1);
        stashedOffhand = mob.getOffhandItem();
        mob.setItemSlot(EquipmentSlot.OFFHAND, shield);
        shieldEngaged = true;
        swappedShield = true;
    }

    /** Lower the shield, stand up, and return a borrowed shield + restore the off-hand. Safe to call any time. */
    private void endBrace() {
        if (mob.isUsingItem()) {
            mob.stopUsingItem();
        }
        mob.setShiftKeyDown(false);
        mob.setPose(Pose.STANDING);
        if (swappedShield) {
            ItemStack shield = mob.getOffhandItem();
            mob.setItemSlot(EquipmentSlot.OFFHAND, stashedOffhand);
            stashedOffhand = ItemStack.EMPTY;
            if (!shield.isEmpty()) {
                ItemStack leftover = mob.getInventory().addItem(shield);
                if (!leftover.isEmpty()) {
                    mob.dropAtLocation(leftover);
                }
            }
            swappedShield = false;
        }
        shieldEngaged = false;
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
            case BUILD -> "rigging a crystal";
            case BRACE -> "bracing behind cover";
            default -> null;
        };
    }
}
