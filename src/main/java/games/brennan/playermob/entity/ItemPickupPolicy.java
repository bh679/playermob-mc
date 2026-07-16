package games.brennan.playermob.entity;

import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.compat.ItemDataCompat;
import games.brennan.playermob.compat.ItemKindCompat;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
     * Bulk materials a mob stocks up on from <em>chests</em> (distinct from the
     * pooled floor-pickup {@link #BUILDING_BLOCK_CAP}): a stack of each, capped
     * independently. See {@link #wantsResource}.
     */
    public enum BlockResource { WOOD, STONE }

    /** Per-type chest-loot cap — up to one stack of wood AND one stack of stone. */
    public static final int WOOD_CAP = 64;
    public static final int STONE_CAP = 64;

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
     * The broad "stone" family the mob loots from chests: plain stone, cobblestone,
     * deepslate, granite/diorite/andesite/tuff, blackstone, and their cut / polished /
     * brick / chiseled variants. No single vanilla item tag covers this set, so it's
     * curated here (mirrors {@link #VALUABLES}). Tool-craftable members
     * (cobblestone/blackstone/cobbled-deepslate) are detected separately via
     * {@link #isToolCraftableStone} and valued higher. Extend freely; membership is
     * the whole contract.
     */
    private static final Set<Item> STONE_FAMILY = Set.of(
        Items.STONE, Items.COBBLESTONE, Items.MOSSY_COBBLESTONE, Items.SMOOTH_STONE,
        Items.STONE_BRICKS, Items.MOSSY_STONE_BRICKS, Items.CRACKED_STONE_BRICKS,
        Items.CHISELED_STONE_BRICKS,
        Items.GRANITE, Items.POLISHED_GRANITE,
        Items.DIORITE, Items.POLISHED_DIORITE,
        Items.ANDESITE, Items.POLISHED_ANDESITE,
        Items.DEEPSLATE, Items.COBBLED_DEEPSLATE, Items.POLISHED_DEEPSLATE,
        Items.DEEPSLATE_BRICKS, Items.CRACKED_DEEPSLATE_BRICKS,
        Items.DEEPSLATE_TILES, Items.CRACKED_DEEPSLATE_TILES, Items.CHISELED_DEEPSLATE,
        //? if >=1.21.1 {
        Items.TUFF, Items.POLISHED_TUFF, Items.TUFF_BRICKS,
        Items.CHISELED_TUFF, Items.CHISELED_TUFF_BRICKS,
        //?} else {
        /*Items.TUFF, // polished/brick/chiseled tuff variants don't exist in 1.20.1
        *///?}
        Items.BLACKSTONE, Items.POLISHED_BLACKSTONE, Items.POLISHED_BLACKSTONE_BRICKS,
        Items.CRACKED_POLISHED_BLACKSTONE_BRICKS, Items.CHISELED_POLISHED_BLACKSTONE,
        Items.GILDED_BLACKSTONE
    );

    /**
     * The {@code #minecraft:stone_tool_materials} members — the complete vanilla set of stone
     * that crafts stone tools. Authoritative for vanilla; {@link #isToolCraftableStone} also
     * consults the tag for modded additions. All three are members of {@link #STONE_FAMILY}.
     */
    private static final Set<Item> TOOL_CRAFTABLE_STONE = Set.of(
        Items.COBBLESTONE, Items.BLACKSTONE, Items.COBBLED_DEEPSLATE
    );

    /**
     * Common vanilla logs/stems + planks — a fallback for {@link #isWood} so the classifier
     * works in unit tests (which run without datapack-loaded tags). At runtime the {@code #logs}
     * / {@code #planks} tags are authoritative and also cover stripped logs, wood/hyphae blocks,
     * and modded woods not listed here.
     */
    private static final Set<Item> COMMON_WOODS = Set.of(
        Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG,
        Items.ACACIA_LOG, Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG,
        Items.CRIMSON_STEM, Items.WARPED_STEM,
        Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS,
        Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS,
        Items.BAMBOO_PLANKS, Items.CRIMSON_PLANKS, Items.WARPED_PLANKS
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
        if (ItemKindCompat.isSword(stack)) return WeaponCategory.SWORD;
        if (ItemKindCompat.isPickaxe(stack)) return WeaponCategory.PICKAXE;
        if (item instanceof AxeItem) return WeaponCategory.AXE;
        if (item instanceof BowItem || item instanceof CrossbowItem) return WeaponCategory.RANGED;
        // Admin-configured modded firearms (MusketMod muskets, gun-mod rifles, …) are ranged too.
        if (PlayerMobConfig.moddedRanged().isRangedWeapon(stack)) return WeaponCategory.RANGED;
        return null;
    }

    /** Wearable gear handled by the equip-upgrade path: armor + shields, but not weapons. */
    public static boolean isArmorOrShield(ItemStack stack) {
        return weaponCategory(stack) == null && ItemKindCompat.isEquippable(stack);
    }

    /**
     * Main-hand attack damage contributed by this stack — the sum of its
     * {@code ATTACK_DAMAGE} attribute modifiers. Ranks tiers (wood &lt; iron
     * &lt; diamond) and sword-vs-axe (axes hit harder) correctly; bows/crossbows
     * carry no such modifier and return 0.
     */
    public static double meleeAttackDamage(ItemStack stack) {
        return ItemDataCompat.mainhandAttackDamage(stack);
    }

    /** Count of distinct enchantments — the tie-breaker when base quality is equal. */
    public static int enchantmentScore(ItemStack stack) {
        return ItemDataCompat.enchantmentCount(stack);
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

    /** Modded firearms out-rank crossbows, which out-rank bows; anything else is 0. */
    private static int rangedRank(ItemStack stack) {
        if (PlayerMobConfig.moddedRanged().isRangedWeapon(stack)) return 3;
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
        if (ItemDataCompat.isFood(stack)) return true;
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

    // ---- Wood / stone chest looting (per-resource caps) -------------------

    /**
     * True if {@code stack} is any log/stem or any planks. The {@code #logs} / {@code #planks}
     * tags drive this at runtime — covering every vanilla variant (stripped, wood/hyphae) plus
     * modded woods. The {@link #COMMON_WOODS} fallback set covers the common vanilla logs/planks
     * so the classifier still works in unit tests, which run without datapack-loaded tags.
     */
    public static boolean isWood(ItemStack stack) {
        return stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS)
            || COMMON_WOODS.contains(stack.getItem());
    }

    /** True if {@code stack} is in the broad {@link #STONE_FAMILY}. */
    public static boolean isStone(ItemStack stack) {
        return STONE_FAMILY.contains(stack.getItem());
    }

    /**
     * True for stone that can craft stone tools — the {@code #minecraft:stone_tool_materials}
     * members (cobblestone, blackstone, cobbled-deepslate). Valued above decorative stone so the
     * mob's stone pool converges toward the useful kind. The {@link #TOOL_CRAFTABLE_STONE} set is
     * the complete vanilla truth (and keeps unit tests working without datapack tags); the tag
     * adds any modded tool-stone at runtime. Implies {@link #isStone}.
     */
    public static boolean isToolCraftableStone(ItemStack stack) {
        return TOOL_CRAFTABLE_STONE.contains(stack.getItem())
            || stack.is(ItemTags.STONE_TOOL_MATERIALS);
    }

    /**
     * Classify {@code stack} as a capped chest resource, or {@code null} if it's
     * neither wood nor stone. The two families are disjoint, so check order is cosmetic.
     */
    public static BlockResource blockResource(ItemStack stack) {
        if (isWood(stack)) return BlockResource.WOOD;
        if (isStone(stack)) return BlockResource.STONE;
        return null;
    }

    private static int capFor(BlockResource resource) {
        return resource == BlockResource.WOOD ? WOOD_CAP : STONE_CAP;
    }

    private static boolean matches(BlockResource resource, ItemStack stack) {
        return resource == BlockResource.WOOD ? isWood(stack) : isStone(stack);
    }

    /** Total count of {@code resource} stacks across {@code container}. Enforces the per-type cap. */
    public static int countResource(Container container, BlockResource resource) {
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (matches(resource, stack)) total += stack.getCount();
        }
        return total;
    }

    /**
     * Trade-up rank of a stack within its resource: higher beats lower. Lexicographic
     * <strong>(tool-craftable tier, then pile count)</strong> — for STONE a tool-craftable
     * stack outranks a decorative one regardless of size; ties and all WOOD compare by count.
     * This is where the "prioritize tool-craftable stone" rule lives.
     */
    static long tradeUpValue(BlockResource resource, ItemStack stack) {
        long tier = (resource == BlockResource.STONE && isToolCraftableStone(stack)) ? 1L : 0L;
        return (tier << 32) | (stack.getCount() & 0xffffffffL);
    }

    /**
     * Slot index of the <em>lowest</em>-{@link #tradeUpValue} stack of {@code resource}
     * in {@code container} (the displacement target when trading up), or {@code -1} if none.
     */
    public static int lowestValueResourceSlot(Container container, BlockResource resource) {
        int slot = -1;
        long min = Long.MAX_VALUE;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (matches(resource, stack)) {
                long value = tradeUpValue(resource, stack);
                if (value < min) {
                    min = value;
                    slot = i;
                }
            }
        }
        return slot;
    }

    /**
     * Whether the mob wants {@code found} (already known to be {@code resource}). True while
     * under the per-type cap; once at the cap, only if {@code found} strictly out-ranks the
     * lowest-value stack already carried (bigger pile, or — for stone — tool-craftable over
     * decorative). Mirrors {@link #wantsBuildingBlock} with the resource/priority dimension added.
     */
    public static boolean wantsResource(Container backpack, ItemStack found, BlockResource resource) {
        if (countResource(backpack, resource) < capFor(resource)) return true;
        int lowSlot = lowestValueResourceSlot(backpack, resource);
        if (lowSlot < 0) return false;
        return tradeUpValue(resource, found) > tradeUpValue(resource, backpack.getItem(lowSlot));
    }
}
