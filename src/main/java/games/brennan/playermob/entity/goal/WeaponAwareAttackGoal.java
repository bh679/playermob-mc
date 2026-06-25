package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumSet;

/**
 * Composite attack goal that chooses between crossbow / bow / melee behaviour
 * based on whatever the mob is holding in its main hand. One outer Goal owns
 * the MOVE+LOOK+JUMP flags so the goal selector sees a single occupant for
 * combat — switching mid-combat won't lose the slot.
 *
 * <p>Before each delegate (re)selection it asks the entity to
 * {@link PlayerMobEntity#equipBestWeaponForTarget draw the best weapon for the
 * range} — ranged when the target is far, melee when close — so the mob uses
 * its whole weapon toolkit, not just whatever it picked up last. On
 * {@link #stop} it reverts to holding the best melee.</p>
 *
 * <p>Internally delegates to three goals — {@link PlayerMobCrossbowAttackGoal},
 * {@link PlayerMobBowAttackGoal}, {@link MeleeAttackGoal} — chosen per tick by
 * inspecting the main-hand stack. When the held weapon changes mid-combat
 * (e.g. the mob's crossbow runs out of ammo and the operator swaps via
 * {@code /item replace}), this goal stops the previous delegate and starts
 * the new one so behaviour swaps cleanly rather than glitching between
 * inconsistent path/charge state.</p>
 *
 * <p>Why not run the three vanilla goals separately at different priorities?
 * Because their {@link Goal#canUse()} predicates don't know about the
 * holding-the-right-weapon condition. Two of them would constantly thrash
 * the selector trying to run. Composing them inside one Goal keeps selector
 * state stable.</p>
 */
public final class WeaponAwareAttackGoal extends Goal implements DescribableGoal {

    private final PlayerMobEntity mob;
    private final PlayerMobCrossbowAttackGoal crossbow;
    private final PlayerMobBowAttackGoal bow;
    private final MeleeAttackGoal melee;

    /** The currently-running delegate, or {@code null} when nothing has started. */
    private Goal active;

    /**
     * @param mob the entity this goal drives
     * @param speed pathfinder movement multiplier (typical: 1.0)
     * @param rangedAttackRange max attack range in blocks for the ranged delegates
     *                          (the bow/crossbow firing distance, not the
     *                          targeting distance)
     */
    public WeaponAwareAttackGoal(PlayerMobEntity mob, double speed, float rangedAttackRange) {
        this.mob = mob;
        // 20-tick attack interval mirrors vanilla skeleton; the crossbow goal
        // manages its own charge timing so doesn't take an interval arg.
        this.crossbow = new PlayerMobCrossbowAttackGoal(mob, speed, rangedAttackRange);
        this.bow = new PlayerMobBowAttackGoal(mob, speed, 20, rangedAttackRange);
        this.melee = new MeleeAttackGoal(mob, speed, true);
        // We claim every flag any delegate might need so the selector reserves
        // them for us. Inner-goal flags are irrelevant — they're not selector-managed.
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public String objective() {
        return "Fighting";
    }

    @Override
    public String subObjective() {
        String mode = active == crossbow ? "crossbow" : active == bow ? "bow" : "melee";
        LivingEntity target = mob.getTarget();
        return target != null ? mode + " → " + target.getName().getString() : mode;
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;
        Goal desired = pickGoalForMainhand();
        return desired.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;
        if (active == null) return false;
        // Draw the best weapon for the current range before (re)selecting the delegate.
        mob.equipBestWeaponForTarget(target);
        // If the weapon changed (auto-switch or operator swap), hand off cleanly.
        Goal desired = pickGoalForMainhand();
        if (desired != active) {
            active.stop();
            if (!desired.canUse()) {
                active = null;
                return false;
            }
            active = desired;
            active.start();
            return true;
        }
        return active.canContinueToUse();
    }

    @Override
    public void start() {
        // Equip the situational weapon first so the chosen delegate matches it.
        mob.equipBestWeaponForTarget(mob.getTarget());
        active = pickGoalForMainhand();
        active.start();
    }

    @Override
    public void stop() {
        if (active != null) {
            active.stop();
            active = null;
        }
        // Combat over — revert to holding the best melee weapon.
        mob.equipBestMeleeInHand();
    }

    @Override
    public void tick() {
        if (active != null) {
            active.tick();
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        // We need every tick to spot mainhand changes promptly.
        return true;
    }

    /**
     * Inspect the mob's mainhand and return the delegate goal whose weapon
     * class matches. Defaults to melee for empty hands and any item that
     * isn't a recognised ranged weapon (sword, axe, fists, mismatched mod
     * items — all are melee-mode).
     *
     * <p>A ranged weapon only routes to its ranged delegate while the mob has
     * ammo ({@link PlayerMobEntity#hasRangedAmmo()}). With no arrows a mob still
     * holding a bow/crossbow falls to the melee delegate — so it bashes in
     * rather than dry-firing or standing idle when it owns no melee weapon to
     * swap to. (When it does own one, {@code equipBestWeaponForTarget} has
     * already drawn it.)</p>
     */
    private Goal pickGoalForMainhand() {
        ItemStack mainhand = mob.getMainHandItem();
        if (mob.hasRangedAmmo()) {
            if (mainhand.is(Items.CROSSBOW)) return crossbow;
            if (mainhand.is(Items.BOW)) return bow;
        }
        return melee;
    }
}
