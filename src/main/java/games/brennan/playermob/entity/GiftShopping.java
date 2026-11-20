package games.brennan.playermob.entity;

import games.brennan.playermob.compat.ItemKindCompat;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether gear a villager sells would be an upgrade for a <em>player</em> — the test an echo uses
 * before buying a gift for the player it loves (see {@code ShopWithVillagerGoal}). It is the echo's
 * own pickup test, applied to everything the player owns:
 *
 * <ul>
 *   <li><b>Armour or a shield</b> must beat every piece for the same slot the player has, worn or
 *       carried ({@link EquipmentEvaluator#shouldReplace}).</li>
 *   <li><b>A weapon or tool</b> must be strictly better than the best of its kind (sword, axe,
 *       pickaxe, ranged) anywhere the player has one ({@link ItemPickupPolicy#compareQuality}).</li>
 * </ul>
 *
 * <p>The pure core ({@link #upgrades}) works on a plain list of stacks so it is unit-testable;
 * {@link #ownedBy} gathers that list from a live player.</p>
 */
public final class GiftShopping {

    private GiftShopping() {}

    /** Blocks within which the loved player has to be for the echo to shop for them. */
    public static final double GIFT_RANGE = 32.0;

    /**
     * True when {@code candidate} is gear and beats everything of its kind in {@code owned} —
     * a real upgrade for whoever owns those stacks.
     */
    public static boolean upgrades(ItemStack candidate, List<ItemStack> owned) {
        if (candidate.isEmpty()) return false;
        if (ItemPickupPolicy.isArmorOrShield(candidate)) {
            EquipmentSlot slot = ItemKindCompat.armorSlotOrMainhand(candidate);
            for (ItemStack mine : owned) {
                if (mine.isEmpty() || !ItemPickupPolicy.isArmorOrShield(mine)
                        || ItemKindCompat.armorSlotOrMainhand(mine) != slot) {
                    continue;
                }
                if (!EquipmentEvaluator.shouldReplace(candidate, mine)) return false;
            }
            return EquipmentEvaluator.shouldReplace(candidate, ItemStack.EMPTY);
        }
        ItemPickupPolicy.WeaponCategory category = ItemPickupPolicy.weaponCategory(candidate);
        if (category == null) return false;
        for (ItemStack mine : owned) {
            if (!mine.isEmpty() && ItemPickupPolicy.weaponCategory(mine) == category
                    && ItemPickupPolicy.compareQuality(candidate, mine) <= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Everything {@code player} owns that could hold gear — worn, held and carried — plus
     * {@code alsoOwned} (a gift already bought for them and not yet delivered).
     */
    public static List<ItemStack> ownedBy(Player player, ItemStack alsoOwned) {
        List<ItemStack> owned = new ArrayList<>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            owned.add(inventory.getItem(i));
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            owned.add(player.getItemBySlot(slot));
        }
        if (alsoOwned != null && !alsoOwned.isEmpty()) {
            owned.add(alsoOwned);
        }
        return owned;
    }
}
