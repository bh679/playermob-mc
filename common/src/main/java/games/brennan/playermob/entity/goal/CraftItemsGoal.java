package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.CraftingLadder;
import games.brennan.playermob.entity.CraftingLadder.CraftAction;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Idle crafting goal: when the curated {@link CraftingLadder} has something to
 * make, the mob crafts it — 2×2 recipes in hand, but 3×3 tool recipes at a real
 * crafting table it walks to (and makes/places one first if there isn't one).
 *
 * <p>This is the "process materials" half of the gather-and-craft loop
 * ({@link MineBlocksGoal} gathers the raw materials). It runs at a higher
 * priority than mining so the mob works through what it already has, but
 * {@link #canUse} backs off while a block-break is in progress (the mob's
 * {@code breakingBlock} flag) so it never interrupts and wastes a partial mine.</p>
 *
 * <p><b>Phase machine:</b></p>
 * <ul>
 *   <li><b>CRAFTING</b> (in place) — for 2×2 recipes (planks, sticks, a crafting
 *       table). Short arm-swing pause, then apply.</li>
 *   <li><b>GOTO_TABLE → CRAFTING</b> — for tools (3×3): path to the nearest
 *       crafting table within scan range, then craft there.</li>
 *   <li><b>PLACING</b> — when a tool is wanted, the mob holds a crafting table
 *       item, and none is in reach: place it on valid adjacent ground
 *       ({@code mobGriefing}-gated), so the next cycle can craft tools at it.</li>
 * </ul>
 *
 * <p>Reuses any existing crafting table in range (including player-placed ones).
 * Crafted gear (tools/weapons) is equipped when it beats what's worn; materials
 * and the table item are stashed in the backpack — never forced into the hand.</p>
 */
public final class CraftItemsGoal extends Goal {

    private static final double MOVE_SPEED = 0.9;
    private static final int CRAFT_PAUSE_TICKS = 25;      // ~1.25s "crafting" pause
    private static final int PLACE_PAUSE_TICKS = 15;      // ~0.75s to place a table
    private static final int SWING_INTERVAL_TICKS = 8;    // arm-swing cadence
    private static final int POST_CRAFT_COOLDOWN = 12;    // brief pause between crafts
    private static final int EMPTY_COOLDOWN = 40;         // 2s between checks when nothing to do
    private static final int PATH_TIMEOUT_TICKS = 100;    // 5s to reach a table
    private static final int TABLE_SCAN_RADIUS = 6;       // search range for a usable table
    private static final double TABLE_REACH_SQR = 6.25;   // 2.5 blocks — "at the table"

    private enum Phase { IDLE, GOTO_TABLE, CRAFTING, PLACING }

    private final PlayerMobEntity mob;

    private Phase phase = Phase.IDLE;
    private Phase plannedPhase = Phase.IDLE;
    private int phaseTicks = 0;
    private int cooldown = 0;
    private CraftAction pending;
    private BlockPos tablePos; // nearest crafting table found in canUse (may be null)

    public CraftItemsGoal(PlayerMobEntity mob) {
        this.mob = mob;
        // Claim MOVE (to path to a table) + LOOK (don't get yanked mid-craft).
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (mob.getTarget() != null) return false;     // combat preempts
        if (mob.isBreakingBlock()) return false;        // don't interrupt a mine
        if (!mob.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) return false;

        tablePos = findNearbyCraftingTable();
        boolean tableInReach = tablePos != null;

        Optional<CraftAction> next =
            CraftingLadder.nextCraft(mob.getInventory(), mob.equippedToolItems(), tableInReach);
        if (next.isPresent()) {
            pending = next.get();
            plannedPhase = pending.needsTable() ? Phase.GOTO_TABLE : Phase.CRAFTING;
            return true;
        }

        // Nothing to craft right now — but if we want a tool, hold a table, and
        // none is in reach, place the table so next cycle can craft tools.
        if (!tableInReach
                && holdsCraftingTableItem()
                && CraftingLadder.wantsTable(mob.getInventory(), mob.equippedToolItems())) {
            pending = null;
            plannedPhase = Phase.PLACING;
            return true;
        }

        cooldown = EMPTY_COOLDOWN;
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return phase != Phase.IDLE && mob.isAlive() && mob.getTarget() == null;
    }

    @Override
    public void start() {
        phase = plannedPhase;
        phaseTicks = 0;
        if (phase == Phase.GOTO_TABLE && tablePos != null) {
            mob.getNavigation().moveTo(
                tablePos.getX() + 0.5, tablePos.getY(), tablePos.getZ() + 0.5, MOVE_SPEED);
        } else {
            mob.getNavigation().stop();
        }
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        phase = Phase.IDLE;
        phaseTicks = 0;
        pending = null;
        tablePos = null;
        cooldown = POST_CRAFT_COOLDOWN;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        phaseTicks++;
        switch (phase) {
            case GOTO_TABLE -> tickGotoTable();
            case CRAFTING -> tickCrafting();
            case PLACING -> tickPlacing();
            default -> { /* IDLE */ }
        }
    }

    private void tickGotoTable() {
        if (tablePos == null || !isCraftingTable(tablePos)) { // table gone
            stop();
            return;
        }
        if (distanceToSqr(tablePos) < TABLE_REACH_SQR) {
            mob.getNavigation().stop();
            lookAt(tablePos);
            phase = Phase.CRAFTING;
            phaseTicks = 0;
        } else if (phaseTicks > PATH_TIMEOUT_TICKS) {
            stop();
        }
    }

    private void tickCrafting() {
        if (phaseTicks % SWING_INTERVAL_TICKS == 0) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        if (phaseTicks >= CRAFT_PAUSE_TICKS) {
            performCraft();
            stop();
        }
    }

    private void tickPlacing() {
        if (phaseTicks % SWING_INTERVAL_TICKS == 0) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        if (phaseTicks >= PLACE_PAUSE_TICKS) {
            placeCraftingTable();
            stop();
        }
    }

    /**
     * Re-resolve the craft at apply-time — for a tool, only if we're genuinely at
     * a table — then consume inputs and place the output (equip gear, stash the
     * rest). The output is never forced into the mainhand for materials/tables.
     */
    private void performCraft() {
        boolean atTable = tablePos != null
                && isCraftingTable(tablePos)
                && distanceToSqr(tablePos) < TABLE_REACH_SQR;

        Optional<CraftAction> current =
            CraftingLadder.nextCraft(mob.getInventory(), mob.equippedToolItems(), atTable);
        if (current.isEmpty()) return;

        CraftAction action = current.get();
        CraftingLadder.apply(mob.getInventory(), action);

        if (action.needsTable()) {
            // A tool/weapon — equip if it beats the matching slot; stash any displaced piece.
            stashOrDrop(mob.equipIfBetter(action.output()));
        } else {
            // Planks / sticks / crafting table — into the backpack, not the hand.
            stashOrDrop(action.output());
        }

        mob.playSound(SoundEvents.WOOD_PLACE, 0.6F, 0.9F + mob.getRandom().nextFloat() * 0.2F);
    }

    /** Place a held crafting table on valid adjacent ground (mobGriefing-gated). */
    private void placeCraftingTable() {
        BlockPos spot = findTablePlacementSpot();
        if (spot == null) return;
        if (!consumeOneCraftingTable()) return;

        Level level = mob.level();
        level.setBlock(spot, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        mob.swing(InteractionHand.MAIN_HAND);
        mob.playSound(SoundEvents.WOOD_PLACE, 1.0F, 0.9F + mob.getRandom().nextFloat() * 0.2F);
    }

    // ---- Helpers ----------------------------------------------------------

    private void stashOrDrop(ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack leftover = mob.stashInInventory(stack);
        if (!leftover.isEmpty()) mob.spawnAtLocation(leftover);
    }

    private boolean holdsCraftingTableItem() {
        Container inv = mob.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(Items.CRAFTING_TABLE)) return true;
        }
        return false;
    }

    private boolean consumeOneCraftingTable() {
        Container inv = mob.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(Items.CRAFTING_TABLE)) {
                s.shrink(1);
                if (s.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }

    /** First valid horizontal-adjacent spot: replaceable block with a sturdy floor under it. */
    private BlockPos findTablePlacementSpot() {
        Level level = mob.level();
        BlockPos base = mob.blockPosition();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos p = base.relative(d);
            BlockState cur = level.getBlockState(p);
            if (!cur.canBeReplaced()) continue;
            BlockState below = level.getBlockState(p.below());
            if (below.isFaceSturdy(level, p.below(), Direction.UP)) {
                return p;
            }
        }
        return null;
    }

    private BlockPos findNearbyCraftingTable() {
        BlockPos mobPos = mob.blockPosition();
        Level level = mob.level();
        BlockPos closest = null;
        double closestDistSq = Double.MAX_VALUE;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -TABLE_SCAN_RADIUS; dx <= TABLE_SCAN_RADIUS; dx++) {
            for (int dy = -TABLE_SCAN_RADIUS; dy <= TABLE_SCAN_RADIUS; dy++) {
                for (int dz = -TABLE_SCAN_RADIUS; dz <= TABLE_SCAN_RADIUS; dz++) {
                    cursor.set(mobPos.getX() + dx, mobPos.getY() + dy, mobPos.getZ() + dz);
                    if (!level.getBlockState(cursor).is(Blocks.CRAFTING_TABLE)) continue;
                    double distSq = mobPos.distSqr(cursor);
                    if (distSq < closestDistSq) {
                        closestDistSq = distSq;
                        closest = cursor.immutable();
                    }
                }
            }
        }
        return closest;
    }

    private boolean isCraftingTable(BlockPos pos) {
        return mob.level().getBlockState(pos).is(Blocks.CRAFTING_TABLE);
    }

    private double distanceToSqr(BlockPos pos) {
        double dx = (pos.getX() + 0.5) - mob.getX();
        double dy = (pos.getY() + 0.5) - mob.getY();
        double dz = (pos.getZ() + 0.5) - mob.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private void lookAt(BlockPos pos) {
        mob.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }
}
