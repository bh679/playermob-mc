package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.GameRuleCompat;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.BookBurner;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.ReactionChannel;
import games.brennan.playermob.entity.ReadingHands;
import games.brennan.playermob.entity.ReadingPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * A writer — a PlayerMob whose Writing trait, which an echo inherits from how much its player wrote,
 * is above 0 — keeps a book &amp; quill and writes in it now and then.
 *
 * <ol>
 *   <li><b>Pick one up.</b> Walking past a chiseled bookshelf holding a book &amp; quill (within
 *       {@value BookGoalSupport#PASS_RADIUS} blocks, in sight), it rolls once per pass at
 *       {@link ReadingPolicy#passChance} — 0% at Writing 0, 50% at 10 — and on a hit walks over and
 *       takes the real book &amp; quill into its backpack. Needs the {@code mobGriefing} rule and room
 *       in the backpack.</li>
 *   <li><b>Write.</b> While it carries one and stands at rest, it rolls the same chance every
 *       {@link ReadingPolicy#WRITE_CHECK_TICKS}; on a hit it stops, holds the book &amp; quill and
 *       writes ({@link games.brennan.playermob.entity.MobActivity#WRITING}) for as long as its
 *       Reading focus lasts ({@link ReadingPolicy#readTicks}).</li>
 *   <li><b>Maybe sign.</b> Each session ends with a {@link ReadingPolicy#SIGN_CHANCE} chance it signs
 *       the book: then, done with it, it drops it and it burns ({@link BookBurner}). Otherwise the
 *       book &amp; quill goes back in the backpack for another day.</li>
 * </ol>
 *
 * <p>No text is written into the item — the writing is behaviour, not content. The book &amp; quill
 * is a real backpack item, so it drops with everything else on death; a session cut short puts it
 * back in the backpack. Same calm and threat gates as {@link ReadBookGoal}, same priority (6).</p>
 */
public final class WriteBookGoal extends Goal implements DescribableGoal {

    private static final double REACH_DISTANCE_SQR = 2.5 * 2.5;
    private static final int PATH_TIMEOUT_TICKS = 200;
    private static final int HANDLE_PAUSE_TICKS = 10;
    private static final int PASS_SCAN_TICKS = 10;
    private static final int THREAT_RETRY_TICKS = 100;

    enum Phase { WALK, TAKE, WRITE, FINISH, DONE }

    private final PlayerMobEntity mob;
    private final double speed;
    private final PassMemory passed = new PassMemory();
    private int scanCooldown;
    /** {@code mob.tickCount} of the next sit-down-and-write roll. */
    private int nextWriteCheck;

    /** The chiseled shelf to take a book &amp; quill from, or {@code null} for a writing session. */
    private BlockPos shelf;
    private Phase phase = Phase.DONE;
    private int phaseTicks;
    private int writeTicks;

    public WriteBookGoal(PlayerMobEntity mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Writing";
    }

    @Override
    public String subObjective() {
        return switch (phase) {
            case WALK -> "fetching a book & quill";
            case TAKE -> "taking a book & quill";
            case WRITE -> "writing";
            case FINISH -> "finishing";
            case DONE -> null;
        };
    }

    // ---- lifecycle --------------------------------------------------------

    @Override
    public boolean canUse() {
        int writing = mob.writingTrait();
        if (writing <= 0 || --scanCooldown > 0) return false;
        scanCooldown = PASS_SCAN_TICKS;
        if (!BookGoalSupport.isCalm(mob)) return false;
        double chance = ReadingPolicy.passChance(writing);

        if (quillSlot() >= 0) {
            // Carrying one: now and then, at rest, sit down and write.
            if (mob.tickCount < nextWriteCheck || !isAtRest()) return false;
            nextWriteCheck = mob.tickCount + ReadingPolicy.WRITE_CHECK_TICKS;
            if (BookGoalSupport.isThreatened(mob) || mob.getRandom().nextDouble() >= chance) return false;
            shelf = null;
            return true;
        }

        // Not carrying one: roll for any book & quill on a chiseled shelf being walked past.
        if (!GameRuleCompat.mobGriefing(mob.level())
                || !mob.getInventory().canAddItem(new ItemStack(Items.WRITABLE_BOOK))) {
            return false;
        }
        // Threats first: a shelf seen while threatened would be spent without a roll.
        if (BookGoalSupport.isThreatened(mob)) {
            scanCooldown = THREAT_RETRY_TICKS;
            return false;
        }
        List<BlockPos> shelves = BookGoalSupport.newlyPassed(mob, passed, WriteBookGoal::holdsQuill);
        if (shelves.isEmpty()) return false;
        for (BlockPos pos : shelves) {
            if (mob.getRandom().nextDouble() < chance) {
                shelf = pos;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == Phase.DONE || !BookGoalSupport.isCalm(mob)) return false;
        if (phase == Phase.WALK && (phaseTicks > PATH_TIMEOUT_TICKS || !TrainConfinement.allowsTarget(mob, shelf))) {
            return false;
        }
        return phase.ordinal() < Phase.WRITE.ordinal() || mob.readingHands().stillHeld(mob);
    }

    @Override
    public void start() {
        phaseTicks = 0;
        if (shelf != null) {
            phase = Phase.WALK;
            mob.getNavigation().moveTo(shelf.getX() + 0.5, shelf.getY(), shelf.getZ() + 0.5, speed);
            return;
        }
        // A writing session: the book & quill comes out of the backpack into the off hand.
        int slot = quillSlot();
        if (slot < 0) {
            phase = Phase.DONE;
            return;
        }
        mob.getNavigation().stop();
        ItemStack quill = mob.getInventory().removeItem(slot, 1);
        mob.readingHands().begin(mob, quill, false);
        mob.setWritingBook(true);
        writeTicks = ReadingPolicy.readTicks(mob.readingFocusTrait(), mob.getRandom().nextDouble());
        phase = Phase.WRITE;
    }

    @Override
    public void stop() {
        mob.setWritingBook(false);
        // Cut short mid-session: the book & quill is the mob's own — back in the backpack it goes.
        mob.readingHands().settle(mob);
        mob.getNavigation().stop();
        shelf = null;
        phase = Phase.DONE;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        phaseTicks++;
        switch (phase) {
            case WALK -> tickWalk();
            case TAKE -> tickTake();
            case WRITE -> tickWrite();
            case FINISH -> tickFinish();
            case DONE -> { }
        }
    }

    // ---- phases -----------------------------------------------------------

    private void tickWalk() {
        if (Vec3.atCenterOf(shelf).distanceToSqr(mob.position()) < REACH_DISTANCE_SQR) {
            mob.getNavigation().stop();
            enter(Phase.TAKE);
        } else if (mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(shelf.getX() + 0.5, shelf.getY(), shelf.getZ() + 0.5, speed);
        }
    }

    private void tickTake() {
        mob.getLookControl().setLookAt(shelf.getX() + 0.5, shelf.getY() + 0.5, shelf.getZ() + 0.5);
        if (phaseTicks < mob.reactTicks(ReactionChannel.ITEMS, HANDLE_PAUSE_TICKS)) return;
        if (mob.level().getBlockEntity(shelf) instanceof ChiseledBookShelfBlockEntity chiseled) {
            List<Integer> slots = BookGoalSupport.slotsMatching(chiseled, s -> s.is(Items.WRITABLE_BOOK));
            if (!slots.isEmpty()) {
                int slot = slots.get(mob.getRandom().nextInt(slots.size()));
                ItemStack quill = chiseled.removeItem(slot, 1);
                ItemStack left = mob.getInventory().addItem(quill);
                if (!left.isEmpty()) {
                    chiseled.setItem(slot, left); // no room after all — leave it on the shelf
                } else {
                    mob.swing(InteractionHand.MAIN_HAND);
                    // Don't sit straight down to write; it's a keeper, for later.
                    nextWriteCheck = mob.tickCount + ReadingPolicy.WRITE_CHECK_TICKS;
                }
            }
        }
        phase = Phase.DONE;
    }

    private void tickWrite() {
        BookGoalSupport.lookAtHeldBook(mob);
        if (phaseTicks >= writeTicks) {
            mob.setWritingBook(false);
            enter(Phase.FINISH);
        }
    }

    /** Session over: sign it (drop it, it burns) — or put it away to write in another day. */
    private void tickFinish() {
        if (phaseTicks < mob.reactTicks(ReactionChannel.ITEMS, HANDLE_PAUSE_TICKS)) return;
        if (mob.getRandom().nextDouble() < ReadingPolicy.SIGN_CHANCE) {
            mob.burnFinishedBook(mob.readingHands().finish(mob));
            mob.swing(InteractionHand.OFF_HAND);
        } else {
            mob.readingHands().settle(mob);
        }
        phase = Phase.DONE;
    }

    private void enter(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    // ---- helpers ----------------------------------------------------------

    /** Backpack slot of a book &amp; quill the mob carries, or -1. */
    private int quillSlot() {
        SimpleContainer backpack = mob.getInventory();
        for (int i = 0; i < backpack.getContainerSize(); i++) {
            if (backpack.getItem(i).is(Items.WRITABLE_BOOK)) return i;
        }
        return -1;
    }

    /** Standing still on the ground with nothing else going on. */
    private boolean isAtRest() {
        return mob.getNavigation().isDone() && mob.onGround() && !mob.isUsingItem();
    }

    private static boolean holdsQuill(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ChiseledBookShelfBlockEntity chiseled
            && !BookGoalSupport.slotsMatching(chiseled, s -> s.is(Items.WRITABLE_BOOK)).isEmpty();
    }
}
