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
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * A bookish PlayerMob — its Reading trait, which an echo inherits from how much its player read —
 * may stop at a shelf it walks past, take a book, stand reading it for a while, then drop it and
 * it burns.
 *
 * <p><b>Chance, not a timer.</b> Each shelf the mob passes (within
 * {@value BookGoalSupport#PASS_RADIUS} blocks, in sight) gets one roll per pass, at
 * {@link ReadingPolicy#passChance} — from 0% at Reading 0 up to 50% at Reading 10. Its Reading-focus
 * trait sets how long it reads ({@link ReadingPolicy#readTicks}), and its {@link ReactionChannel#ITEMS}
 * reaction speed how quickly it reaches for and drops the book.</p>
 *
 * <p><b>Shelves.</b> A plain bookshelf (the common case in Dungeon Train's library carriages), a
 * chiseled bookshelf with a book in it (a book &amp; quill there is for {@link WriteBookGoal}), or a
 * lectern holding a book. A lectern whose book hasn't been chosen yet (Dungeon Train's narrative
 * lectern before a player first opens it) is skipped, so a mob never advances a player's story.</p>
 *
 * <p><b>What it holds.</b> See {@link ReadingHands}: a plain shelf or a lectern lends a copy (the
 * lectern keeps its own); a chiseled shelf gives up its real book. A real book is only taken when the
 * {@code mobGriefing} rule allows and the backpack has room to stow it should the mob die or be
 * saved mid-read; otherwise the mob reads a copy.</p>
 *
 * <p><b>When it's done</b> it drops the book and the book burns away ({@link BookBurner}) — the way a
 * player's book does in Dungeon Train — so a chiseled shelf it read from is one book lighter. A read
 * cut short is different: a copy simply vanishes and a real book falls to the floor intact.</p>
 *
 * <p><b>Never mid-fight.</b> It won't start with a target, while hurt, fleeing, digging, crossing a
 * gap, at a door, or with anyone nearby it would attack or flee; any of those ends a read. Priority
 * 6: above the train march, so a reader stops to read, and below every real objective.</p>
 */
public final class ReadBookGoal extends Goal implements DescribableGoal {

    /** Squared distance from the shelf's centre at which the mob can take a book. */
    private static final double REACH_DISTANCE_SQR = 2.5 * 2.5;
    /** Give up walking to a shelf after this long. */
    private static final int PATH_TIMEOUT_TICKS = 200;
    /** Pause before taking the book and before dropping it, at neutral reaction speed. */
    private static final int HANDLE_PAUSE_TICKS = 10;
    /** How often to look for shelves being passed (the scan isn't free). */
    private static final int PASS_SCAN_TICKS = 10;
    /** After finding a threat nearby, wait this long before looking again. */
    private static final int THREAT_RETRY_TICKS = 100;

    enum Phase { WALK, TAKE, READ, RETURN, DONE }

    enum Kind { PLAIN, CHISELED, LECTERN }

    private final PlayerMobEntity mob;
    private final double speed;
    /** Shelves walked past recently — one roll each per pass. */
    private final PassMemory passed = new PassMemory();
    private int scanCooldown;

    private BlockPos shelf;
    private Kind kind;
    private Phase phase = Phase.DONE;
    private int phaseTicks;
    private int readTicks;
    /** The chiseled slot the real book came out of, or -1 (the book in hand is then a copy). */
    private int takenSlot = -1;

    public ReadBookGoal(PlayerMobEntity mob, double speed) {
        this.mob = mob;
        this.speed = speed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Reading";
    }

    @Override
    public String subObjective() {
        return switch (phase) {
            case WALK -> "finding a book";
            case TAKE -> "taking a book";
            case READ -> "reading";
            case RETURN -> "burning it";
            case DONE -> null;
        };
    }

    // ---- lifecycle --------------------------------------------------------

    @Override
    public boolean canUse() {
        int reading = mob.readingTrait();
        if (reading <= 0 || --scanCooldown > 0) return false;
        scanCooldown = PASS_SCAN_TICKS;
        if (!BookGoalSupport.isCalm(mob)) return false;
        List<BlockPos> shelves = BookGoalSupport.newlyPassed(mob, passed, (level, pos) -> kindOf(level, pos) != null);
        if (shelves.isEmpty()) return false;
        if (BookGoalSupport.isThreatened(mob)) {
            scanCooldown = THREAT_RETRY_TICKS;
            return false;
        }
        // One roll per shelf passed; the first that comes up is the one it reads.
        double chance = ReadingPolicy.passChance(reading);
        for (BlockPos pos : shelves) {
            if (mob.getRandom().nextDouble() < chance) {
                shelf = pos;
                kind = kindOf(mob.level(), pos);
                return kind != null;
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (phase == Phase.DONE || shelf == null || !BookGoalSupport.isCalm(mob)) return false;
        if (phase == Phase.WALK && (phaseTicks > PATH_TIMEOUT_TICKS || !TrainConfinement.allowsTarget(mob, shelf))) {
            return false;
        }
        // Once the book is in hand, anything taking it away ends the read.
        return phase.ordinal() < Phase.READ.ordinal() || mob.readingHands().stillHeld(mob);
    }

    @Override
    public void start() {
        phase = Phase.WALK;
        phaseTicks = 0;
        takenSlot = -1;
        mob.getNavigation().moveTo(shelf.getX() + 0.5, shelf.getY(), shelf.getZ() + 0.5, speed);
    }

    @Override
    public void stop() {
        mob.setReadingBook(false);
        // A book still in hand here means the read was cut short: a prop vanishes, a real book is
        // dropped where the mob stands (the shelf may be out of reach by now).
        ItemStack real = mob.readingHands().end(mob);
        if (!real.isEmpty()) {
            mob.dropAtLocation(real);
        }
        mob.getNavigation().stop();
        shelf = null;
        kind = null;
        phase = Phase.DONE;
        takenSlot = -1;
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
            case READ -> tickRead();
            case RETURN -> tickReturn();
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
        lookAtShelf();
        if (phaseTicks < mob.reactTicks(ReactionChannel.ITEMS, HANDLE_PAUSE_TICKS)) return;
        ItemStack book = takeBook();
        if (book.isEmpty()) {
            phase = Phase.DONE; // the shelf emptied or changed on the way — nothing to read
            return;
        }
        mob.swing(InteractionHand.OFF_HAND);
        mob.readingHands().begin(mob, book, takenSlot < 0);
        mob.setReadingBook(true);
        readTicks = ReadingPolicy.readTicks(mob.readingFocusTrait(), mob.getRandom().nextDouble());
        enter(Phase.READ);
    }

    private void tickRead() {
        BookGoalSupport.lookAtHeldBook(mob);
        if (phaseTicks >= readTicks) {
            mob.setReadingBook(false);
            enter(Phase.RETURN);
        }
    }

    /** Done with the book: drop it, and it burns away — the way a player's book does. */
    private void tickReturn() {
        if (phaseTicks < mob.reactTicks(ReactionChannel.ITEMS, HANDLE_PAUSE_TICKS)) return;
        mob.burnFinishedBook(mob.readingHands().finish(mob));
        mob.swing(InteractionHand.OFF_HAND);
        phase = Phase.DONE;
    }

    private void enter(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private void lookAtShelf() {
        mob.getLookControl().setLookAt(shelf.getX() + 0.5, shelf.getY() + 0.5, shelf.getZ() + 0.5);
    }

    // ---- the shelf --------------------------------------------------------

    /** Take (or borrow) a book from the shelf; {@link ItemStack#EMPTY} if it has none now. */
    private ItemStack takeBook() {
        Level level = mob.level();
        BlockEntity be = level.getBlockEntity(shelf);
        Kind now = kindOf(level, shelf);
        if (now == null) return ItemStack.EMPTY;
        switch (now) {
            case PLAIN:
                return new ItemStack(Items.BOOK);
            case LECTERN:
                return ((LecternBlockEntity) be).getBook().copyWithCount(1);
            case CHISELED:
            default:
                ChiseledBookShelfBlockEntity chiseled = (ChiseledBookShelfBlockEntity) be;
                List<Integer> filled = BookGoalSupport.slotsMatching(chiseled, ReadBookGoal::isReadingBook);
                if (filled.isEmpty()) return ItemStack.EMPTY;
                int slot = filled.get(mob.getRandom().nextInt(filled.size()));
                ItemStack inShelf = chiseled.getItem(slot);
                if (!GameRuleCompat.mobGriefing(level) || !mob.getInventory().canAddItem(inShelf)) {
                    return inShelf.copyWithCount(1); // read a copy, leave the shelf untouched
                }
                takenSlot = slot;
                return chiseled.removeItem(slot, 1);
        }
    }

    /** A book to read — a book &amp; quill on a shelf is one to write in ({@link WriteBookGoal}). */
    static boolean isReadingBook(ItemStack stack) {
        return !stack.is(Items.WRITABLE_BOOK);
    }

    /** What kind of shelf {@code pos} is, or {@code null} if it has no book to offer. */
    static Kind kindOf(Level level, BlockPos pos) {
        if (level.getBlockState(pos).is(Blocks.BOOKSHELF)) return Kind.PLAIN;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ChiseledBookShelfBlockEntity chiseled) {
            return BookGoalSupport.slotsMatching(chiseled, ReadBookGoal::isReadingBook).isEmpty()
                ? null : Kind.CHISELED;
        }
        if (be instanceof LecternBlockEntity lectern && !lectern.getBook().isEmpty()) {
            return Kind.LECTERN;
        }
        return null;
    }
}
