package games.brennan.playermob.entity.goal;

import games.brennan.playermob.compat.ItemDataCompat;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

/**
 * Eat food from the mob's inventory when HP drops below
 * {@link #HUNGER_THRESHOLD}. Holds the food in offhand for the eating
 * duration so the player-shaped renderer shows it raised to the mob's face,
 * then heals the mob by the food's nutrition value and applies any food
 * effects (Regeneration on golden apple, etc.).
 *
 * <p><b>Slot priority:</b> combat (priority 2) preempts eating; eating
 * (priority 3, registered before the raid goals) preempts raiding. If a hostile
 * appears mid-bite the mob drops the food back into its inventory, restores
 * the previous offhand item, and engages.</p>
 *
 * <p><b>No path / movement:</b> the mob eats standing still — only claims
 * the LOOK flag from the selector. It still drifts via the lower-priority
 * stroll goal during the brief eating window.</p>
 */
public final class EatFoodGoal extends Goal implements DescribableGoal {

    /**
     * Eat when HP drops below this fraction of max HP. Also the threshold below
     * which {@link PlayerMobEntity#hasImmediateFoodSource} treats banked food as
     * "should eat this now" — so the hunt goal stands down and lets the mob eat
     * instead of chasing another animal. Public so the entity can share it.
     */
    public static final float HUNGER_THRESHOLD = 0.75f;

    /** Total ticks from "bite starts" to "heal applied". Matches vanilla 1.6s eat duration. */
    private static final int EAT_TICKS = 32;

    /** Spawn an item-puff every N ticks while eating. 8 ticks = 4 bursts across the cycle. */
    private static final int PARTICLE_INTERVAL_TICKS = 8;

    private final PlayerMobEntity mob;

    private int eatTicks = 0;
    private boolean eating = false;
    /** Whatever was in the offhand before we hijacked it for the food. Restored on stop. */
    private ItemStack previousOffhand = ItemStack.EMPTY;

    public EatFoodGoal(PlayerMobEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.LOOK));
    }

    @Override
    public String objective() {
        return "Eating";
    }

    @Override
    public boolean canUse() {
        if (mob.getTarget() != null) return false;
        if (mob.getHealth() >= mob.getMaxHealth() * HUNGER_THRESHOLD) return false;
        return mob.findBestFoodSlot() >= 0;
    }

    @Override
    public boolean canContinueToUse() {
        // Combat preempts; otherwise keep going until finishEating sets eating=false.
        return eating && mob.getTarget() == null;
    }

    @Override
    public void start() {
        int slot = mob.findBestFoodSlot();
        if (slot < 0) return; // race — food gone between canUse and start

        SimpleContainer inv = mob.getInventory();
        ItemStack source = inv.getItem(slot);
        // Take a single-item portion so the offhand never shows a stack of 64
        // visually; the rest stays in inventory.
        ItemStack portion = source.split(1);
        if (source.isEmpty()) {
            inv.setItem(slot, ItemStack.EMPTY);
        }

        previousOffhand = mob.getItemBySlot(EquipmentSlot.OFFHAND).copy();
        mob.setItemSlot(EquipmentSlot.OFFHAND, portion);

        // Opening "om" — vanilla plays the same sound on first bite for players.
        //? if >=26 {
        /*// 26.x removed LivingEntity.getEatingSound; the eating sound rides the CONSUMABLE
        // component now (falling back to the generic eat sound for non-component food).
        // SoundEvents.GENERIC_EAT is a Holder<SoundEvent> in 26.x, so unwrap with value().
        net.minecraft.sounds.SoundEvent eatSound = games.brennan.playermob.compat.ItemDataCompat
            .eatingSound(portion, net.minecraft.sounds.SoundEvents.GENERIC_EAT.value());
        *///?} else {
        net.minecraft.sounds.SoundEvent eatSound = mob.getEatingSound(portion);
        //?}
        mob.playSound(
            eatSound,
            0.5F,
            1.0F + (mob.getRandom().nextFloat() - mob.getRandom().nextFloat()) * 0.4F);

        eatTicks = 0;
        eating = true;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (!eating) return;
        eatTicks++;

        ItemStack food = mob.getItemBySlot(EquipmentSlot.OFFHAND);
        if (food.isEmpty()) {
            // Something cleared the offhand mid-bite — abort cleanly.
            stop();
            return;
        }

        if (eatTicks % PARTICLE_INTERVAL_TICKS == 0) {
            mob.spawnEatingParticles(food);
        }

        if (eatTicks >= EAT_TICKS) {
            finishEating(food);
        }
    }

    /**
     * Apply the food's heal + effects, shrink the offhand stack, and restore
     * the original offhand item. Idempotent — once it runs, {@code eating}
     * flips false so {@code stop()} won't try to push the food back into
     * inventory.
     */
    private void finishEating(ItemStack food) {
        FoodProperties props = ItemDataCompat.foodProperties(food);
        if (props != null) {
            mob.heal((float) ItemDataCompat.nutrition(props));
            // Defensive copies of each effect happen inside the shim — the
            // supplier returns the same instance across calls.
            ItemDataCompat.applyFoodEffects(food, props, mob, mob.getRandom());
        }

        food.shrink(1);
        // We started with a 1-count portion, so shrink leaves it empty.
        // Restore whatever was in offhand before eating started.
        mob.setItemSlot(EquipmentSlot.OFFHAND, previousOffhand);

        previousOffhand = ItemStack.EMPTY;
        eating = false;
        eatTicks = 0;
    }

    @Override
    public void stop() {
        if (eating) {
            // Mid-bite interruption (combat preempt, mob killed, etc.). Put
            // the food back in inventory so we don't lose it, and restore
            // whatever was in offhand. If inventory's full, drop on ground.
            ItemStack inProgress = mob.getItemBySlot(EquipmentSlot.OFFHAND);
            if (!inProgress.isEmpty()) {
                ItemStack leftover = mob.getInventory().addItem(inProgress);
                if (!leftover.isEmpty()) {
                    mob.dropAtLocation(leftover);
                }
            }
            mob.setItemSlot(EquipmentSlot.OFFHAND, previousOffhand);
        }
        previousOffhand = ItemStack.EMPTY;
        eating = false;
        eatTicks = 0;
    }
}
