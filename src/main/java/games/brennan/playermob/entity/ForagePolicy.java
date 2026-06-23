package games.brennan.playermob.entity;

//? if >=1.21.1 {
import net.minecraft.core.component.DataComponents;
//?}
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Decides <em>when</em> a {@link PlayerMobEntity} wants food and <em>what</em> in
 * the world counts as a food source. The acquisition counterpart to
 * {@link ItemPickupPolicy} — stateless, {@code Mob}-agnostic, every method static
 * and operating purely on {@link ItemStack}/{@link BlockState}/{@link Entity}.
 *
 * <p>The forage drive feeds the existing eat/pickup systems:
 * {@link games.brennan.playermob.entity.goal.HarvestCropsGoal} breaks ripe crops,
 * {@link games.brennan.playermob.entity.goal.HuntForFoodGoal} hunts animals, and the
 * already-present {@code RaidContainersGoal} loots chests — all gated on
 * {@link PlayerMobEntity#wantsFood()}, which delegates the decision to
 * {@link #wantsFood(boolean, int, float, float)} here.</p>
 *
 * <p>Unit-tested in {@code ForagePolicyTest}; the live-entity animal path is
 * covered by the in-game Gate 2 smoke test.</p>
 */
public final class ForagePolicy {

    private ForagePolicy() {}

    /**
     * Total carried nutrition (in food points) a <em>hurt</em> mob wants to keep
     * banked before it stops foraging — roughly two decent meals. A mob with no
     * food at all always forages regardless of this; this only governs the
     * "top up while injured" behaviour. Tunable.
     */
    public static final int HEAL_BUFFER_NUTRITION = 12;

    /**
     * Crops the mob harvests for food. Curated to blocks whose drop is
     * <em>directly edible</em> — carrots, potatoes, beetroot. Wheat is
     * intentionally absent: it isn't edible until crafted into bread, and
     * crafting is deferred (issue #5), so harvesting wheat would just clog the
     * backpack with something the mob can't eat. Membership is the whole
     * contract; extend freely (e.g. with melon/sweet-berry support).
     */
    private static final Set<Block> RIPE_FOOD_CROPS = Set.of(
        Blocks.CARROTS,
        Blocks.POTATOES,
        Blocks.BEETROOTS
    );

    /**
     * Core hunger decision (pure — no entity access, so it's trivially testable).
     *
     * <ul>
     *   <li><b>No food at all</b> → always wants food. This is the "seek food if
     *       they have none" core of the feature.</li>
     *   <li><b>Has food but is hurt</b> → keep foraging until {@code carriedNutrition}
     *       reaches {@link #HEAL_BUFFER_NUTRITION} — the "becomes higher priority
     *       when not full health" escalation.</li>
     *   <li><b>Full health with food</b> → content; don't forage.</li>
     * </ul>
     */
    public static boolean wantsFood(boolean hasFood, int carriedNutrition, float health, float maxHealth) {
        if (!hasFood) return true;
        if (health < maxHealth) return carriedNutrition < HEAL_BUFFER_NUTRITION;
        return false;
    }

    /** True if the stack is something the mob can eat — carries a FOOD component. */
    public static boolean isEdible(ItemStack stack) {
        //? if >=1.21.1 {
        return stack.has(DataComponents.FOOD);
        //?} else {
        /*return stack.getItem().getFoodProperties() != null;*/
        //?}
    }

    /**
     * True if {@code state} is a fully-grown crop in {@link #RIPE_FOOD_CROPS}.
     * The {@link CropBlock} cast both gates non-crop blocks and gives us
     * {@link CropBlock#isMaxAge} so the mob only harvests crops ready to pick.
     */
    public static boolean isRipeFoodCrop(BlockState state) {
        if (!(state.getBlock() instanceof CropBlock crop)) return false;
        if (!RIPE_FOOD_CROPS.contains(state.getBlock())) return false;
        return crop.isMaxAge(state);
    }

    /**
     * True if {@code entity} is a vanilla food animal worth hunting — a non-baby
     * cow (incl. mooshroom), pig, chicken, sheep, or rabbit. Wolves, cats,
     * horses, and villagers are deliberately excluded so the mob never slaughters
     * pets or owned mobs; babies are skipped (cruel, and they drop little).
     */
    public static boolean isHuntableFoodAnimal(Entity entity) {
        if (!(entity instanceof Animal animal)) return false;
        if (animal.isBaby()) return false;
        // Cow subsumes MushroomCow (mooshroom extends Cow).
        return animal instanceof Cow
            || animal instanceof Pig
            || animal instanceof Chicken
            || animal instanceof Sheep
            || animal instanceof Rabbit;
    }
}
