package games.brennan.playermob.entity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.Set;

/**
 * Decides <em>what</em> a {@link PlayerMobEntity} wants off the floor.
 *
 * <p>Stateless and {@code Mob}-agnostic — every method is static and operates
 * purely on {@link ItemStack}/{@link Container}. The <em>equip-upgrade</em>
 * decision is NOT here: it needs {@code Mob.canReplaceCurrentItem} (protected)
 * and so lives on {@link PlayerMobEntity#wouldEquipFloorItem}. This class
 * covers the categories a pure unit test can reach — see
 * {@code ItemPickupPolicyTest}.</p>
 *
 * <p>Pickup categories (mirrors the order {@link PlayerMobEntity#wantsToPickUp}
 * and {@link PlayerMobEntity#tryPickUpFloorItem} branch in):</p>
 * <ol>
 *   <li>gear upgrade — handled on the entity (equip-or-skip)</li>
 *   <li>{@link #isAmmo}, {@link #isValuable}, {@link #isConsumable} — hoarded in the backpack</li>
 *   <li>{@link #isBuildingBlock} — hoarded but capped at {@link #BUILDING_BLOCK_CAP}
 *       (one stack), trading up to bigger piles via {@link #wantsBuildingBlock}</li>
 * </ol>
 */
public final class ItemPickupPolicy {

    private ItemPickupPolicy() {}

    /**
     * Max building blocks the mob hoards across the whole backpack — one stack.
     * Past this it only "trades up" (swaps its smallest block stack for a
     * strictly larger found pile). Keeps a looter from hauling a quarry.
     */
    public static final int BUILDING_BLOCK_CAP = 64;

    /**
     * Curated "good loot" set. Items here get hoarded in the backpack even
     * though the mob can't equip or eat them. Block forms (e.g. diamond block)
     * are intentionally absent — they fall through to the building-block cap.
     * Extend freely; membership is the whole contract.
     */
    private static final Set<Item> VALUABLES = Set.of(
        Items.DIAMOND,
        Items.EMERALD,
        Items.NETHERITE_INGOT,
        Items.NETHERITE_SCRAP,
        Items.GOLD_INGOT,
        Items.IRON_INGOT,
        Items.COPPER_INGOT,
        Items.RAW_IRON,
        Items.RAW_GOLD,
        Items.RAW_COPPER,
        Items.LAPIS_LAZULI,
        Items.REDSTONE,
        Items.QUARTZ,
        Items.AMETHYST_SHARD,
        Items.ENCHANTED_BOOK,
        Items.NETHER_STAR,
        Items.ECHO_SHARD,
        Items.TOTEM_OF_UNDYING
    );

    /**
     * The four weapon/tool types the mob keeps a "best of each" of. The mob
     * holds the best of each across its main hand + backpack and switches the
     * active one for the situation; {@link WeaponCategory#PICKAXE} is kept but
     * never auto-wielded in combat (mining is a separate feature).
     */
    public enum WeaponCategory { SWORD, AXE, PICKAXE, RANGED }

    /**
     * Classify a stack into a {@link WeaponCategory}, or {@code null} if it
     * isn't one of the four toolkit types. This is the gate that keeps the mob
     * from "equipping" arbitrary items: vanilla {@code canReplaceCurrentItem}
     * returns true for <em>any</em> item over an empty hand, so weapons are
     * routed through category logic instead.
     */
    public static WeaponCategory weaponCategory(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof SwordItem) return WeaponCategory.SWORD;
        if (item instanceof PickaxeItem) return WeaponCategory.PICKAXE;
        if (item instanceof AxeItem) return WeaponCategory.AXE;
        if (item instanceof BowItem || item instanceof CrossbowItem) return WeaponCategory.RANGED;
        return null;
    }

    /** Wearable gear handled by the equip-upgrade path: armor + shields, but not weapons. */
    public static boolean isArmorOrShield(ItemStack stack) {
        return weaponCategory(stack) == null && Equipable.get(stack) != null;
    }

    /**
     * Main-hand attack damage contributed by this stack — the sum of its
     * {@code ATTACK_DAMAGE} attribute modifiers. Ranks tiers (wood &lt; iron
     * &lt; diamond) and sword-vs-axe (axes hit harder) correctly; bows/crossbows
     * carry no such modifier and return 0.
     */
    public static double meleeAttackDamage(ItemStack stack) {
        ItemAttributeModifiers mods =
            stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double[] damage = {0.0};
        mods.forEach(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.is(Attributes.ATTACK_DAMAGE)) {
                damage[0] += modifier.amount();
            }
        });
        return damage[0];
    }

    /** Count of distinct enchantments — the tie-breaker when base quality is equal. */
    public static int enchantmentScore(ItemStack stack) {
        return stack.getEnchantments().size();
    }

    /**
     * Compare two stacks of the <em>same</em> {@link WeaponCategory}. Positive
     * if {@code a} is better, negative if {@code b} is, 0 if equal. Melee/tools
     * compare by attack damage (a tier proxy that also ranks pickaxes); ranged
     * prefers crossbow &gt; bow. Enchantment count breaks ties.
     */
    public static int compareQuality(ItemStack a, ItemStack b) {
        if (weaponCategory(a) == WeaponCategory.RANGED) {
            int rank = Integer.compare(rangedRank(a), rangedRank(b));
            if (rank != 0) return rank;
        } else {
            int dmg = Double.compare(meleeAttackDamage(a), meleeAttackDamage(b));
            if (dmg != 0) return dmg;
        }
        return Integer.compare(enchantmentScore(a), enchantmentScore(b));
    }

    /** Crossbows out-rank bows; anything else is 0. */
    private static int rangedRank(ItemStack stack) {
        if (stack.getItem() instanceof CrossbowItem) return 2;
        if (stack.getItem() instanceof BowItem) return 1;
        return 0;
    }

    /** Arrows of any kind — {@link ArrowItem} covers spectral + tipped subclasses. */
    public static boolean isAmmo(ItemStack stack) {
        return stack.getItem() instanceof ArrowItem;
    }

    /** Any placeable block (anything backed by a {@link BlockItem}). */
    public static boolean isBuildingBlock(ItemStack stack) {
        return stack.getItem() instanceof BlockItem;
    }

    /** Food (carries a FOOD component) or a potion of any flavour. */
    public static boolean isConsumable(ItemStack stack) {
        if (stack.has(DataComponents.FOOD)) return true;
        return stack.is(Items.POTION)
            || stack.is(Items.SPLASH_POTION)
            || stack.is(Items.LINGERING_POTION);
    }

    /** Membership in the curated {@link #VALUABLES} set. */
    public static boolean isValuable(ItemStack stack) {
        return VALUABLES.contains(stack.getItem());
    }

    /**
     * Sum of every building-block stack's count across {@code container}.
     * Used to enforce {@link #BUILDING_BLOCK_CAP}.
     */
    public static int countBuildingBlocks(Container container) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (isBuildingBlock(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Slot index of the building-block stack holding the <em>fewest</em>
     * blocks, or {@code -1} if the container has none. The swap target when
     * the mob is at the cap and finds a bigger pile.
     */
    public static int smallestBuildingBlockSlot(Container container) {
        int slot = -1;
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (isBuildingBlock(stack) && stack.getCount() < min) {
                min = stack.getCount();
                slot = i;
            }
        }
        return slot;
    }

    /**
     * Whether the mob wants {@code found} (already known to be a building
     * block). True while under the cap, or — once at the cap — only if
     * {@code found} is a strictly larger pile than the smallest stack the mob
     * is already carrying (the "trade up to bigger piles" rule).
     */
    public static boolean wantsBuildingBlock(Container backpack, ItemStack found) {
        int carried = countBuildingBlocks(backpack);
        if (carried < BUILDING_BLOCK_CAP) return true;
        int smallest = smallestBuildingBlockSlot(backpack);
        if (smallest < 0) return false;
        return found.getCount() > backpack.getItem(smallest).getCount();
    }
}
