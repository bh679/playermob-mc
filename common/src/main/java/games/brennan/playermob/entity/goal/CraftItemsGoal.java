package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.CraftingLadder;
import games.brennan.playermob.entity.CraftingLadder.CraftAction;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Idle crafting goal: when the mob has the ingredients for the next rung of the
 * curated {@link CraftingLadder}, it stops, "crafts" for a short pause (with
 * arm-swings), then consumes the inputs and produces the output — equipping it
 * if it beats what's worn, otherwise stashing it in the backpack.
 *
 * <p>This is the "process materials" half of the gather-and-craft loop
 * ({@link MineBlocksGoal} gathers the raw materials). It runs at a higher
 * priority than mining so the mob works through what it already has before
 * gathering more, but {@link #canUse} backs off while a block-break is in
 * progress (the mob's {@code breakingBlock} flag) so it never interrupts and
 * wastes a partial mine.</p>
 *
 * <p>One craft per activation, then a short cooldown — so a full ladder climb
 * reads as a series of deliberate crafting pauses. Gated on the
 * {@code mobGriefing} gamerule, matching the rest of the idle gather/craft loop,
 * so a single switch disables the whole behaviour.</p>
 */
public final class CraftItemsGoal extends Goal {

    private static final int CRAFT_PAUSE_TICKS = 25;     // ~1.25s "crafting" pause
    private static final int SWING_INTERVAL_TICKS = 8;   // arm-swing cadence
    private static final int POST_CRAFT_COOLDOWN = 12;   // brief pause between crafts
    private static final int EMPTY_COOLDOWN = 40;        // 2s between checks when nothing to craft

    private enum Phase { IDLE, CRAFTING }

    private final PlayerMobEntity mob;

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private int cooldown = 0;
    private CraftAction pending;

    public CraftItemsGoal(PlayerMobEntity mob) {
        this.mob = mob;
        // Claim MOVE so the mob halts to craft; LOOK so it isn't yanked around
        // by idle look goals mid-craft.
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (mob.getTarget() != null) return false;           // combat preempts
        if (mob.isBreakingBlock()) return false;             // don't interrupt a mine in progress
        if (!mob.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) return false;

        Optional<CraftAction> next = CraftingLadder.nextCraft(mob.getInventory(), mob.equippedToolItems());
        if (next.isEmpty()) {
            cooldown = EMPTY_COOLDOWN;
            return false;
        }
        pending = next.get();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return phase != Phase.IDLE
                && pending != null
                && mob.isAlive()
                && mob.getTarget() == null;
    }

    @Override
    public void start() {
        phase = Phase.CRAFTING;
        phaseTicks = 0;
        mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        phase = Phase.IDLE;
        phaseTicks = 0;
        pending = null;
        cooldown = POST_CRAFT_COOLDOWN;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        phaseTicks++;
        if (phaseTicks % SWING_INTERVAL_TICKS == 0) {
            mob.swing(InteractionHand.MAIN_HAND);
        }
        if (phaseTicks >= CRAFT_PAUSE_TICKS) {
            performCraft();
            stop();
        }
    }

    /**
     * Re-resolve the next craft at apply-time (the backpack could have changed
     * during the pause), consume its inputs, and place the output — equipping it
     * if it's an upgrade, else stashing it (overflow spills to the ground).
     */
    private void performCraft() {
        Optional<CraftAction> current = CraftingLadder.nextCraft(mob.getInventory(), mob.equippedToolItems());
        if (current.isEmpty()) return;

        CraftAction action = current.get();
        CraftingLadder.apply(mob.getInventory(), action);

        ItemStack leftover = mob.equipIfBetter(action.output());
        if (!leftover.isEmpty()) {
            ItemStack overflow = mob.stashInInventory(leftover);
            if (!overflow.isEmpty()) mob.spawnAtLocation(overflow);
        }

        mob.playSound(SoundEvents.WOOD_PLACE, 0.6F, 0.9F + mob.getRandom().nextFloat() * 0.2F);
    }
}
