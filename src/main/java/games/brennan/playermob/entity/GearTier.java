package games.brennan.playermob.entity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How far along the game a set of items is, by vanilla material: nothing, wood/leather, stone/chain/gold,
 * iron, diamond, netherite. Each tier carries the pool of vanilla items a naturally spawning PlayerMob may
 * be handed at that tier ({@link NaturalSpawnGear}); the same table classifies a player's own items, so
 * "the player's tier" is simply the highest tier any item they carry appears in.
 *
 * <p>Keyed by item id (not {@code TieredItem}/{@code ArmorMaterial}) so it reads the same on every
 * Minecraft version this mod builds for. Pure: no Minecraft types, unit-tested in {@code GearTierTest}.
 * Ids not in any pool (modded items, blocks, anything unlisted) classify as {@link #NONE}.</p>
 */
public enum GearTier {

    NONE(List.of(
        stack("minecraft:stick", 6), stack("minecraft:dirt", 8), stack("minecraft:apple", 3),
        stack("minecraft:wheat_seeds", 6), stack("minecraft:rotten_flesh", 4), stack("minecraft:bone", 3))),

    WOOD(List.of(
        gear("minecraft:wooden_sword"), gear("minecraft:wooden_pickaxe"), gear("minecraft:wooden_axe"),
        gear("minecraft:wooden_shovel"), gear("minecraft:wooden_hoe"),
        gear("minecraft:leather_helmet"), gear("minecraft:leather_chestplate"),
        gear("minecraft:leather_leggings"), gear("minecraft:leather_boots"),
        gear("minecraft:bow"), stack("minecraft:arrow", 8),
        stack("minecraft:oak_log", 8), stack("minecraft:oak_planks", 8), gear("minecraft:crafting_table"),
        stack("minecraft:bread", 4), stack("minecraft:string", 4), stack("minecraft:leather", 4))),

    STONE(List.of(
        gear("minecraft:stone_sword"), gear("minecraft:stone_pickaxe"), gear("minecraft:stone_axe"),
        gear("minecraft:stone_shovel"), gear("minecraft:stone_hoe"),
        gear("minecraft:chainmail_helmet"), gear("minecraft:chainmail_chestplate"),
        gear("minecraft:chainmail_leggings"), gear("minecraft:chainmail_boots"),
        gear("minecraft:golden_sword"), gear("minecraft:golden_pickaxe"), gear("minecraft:golden_axe"),
        gear("minecraft:golden_helmet"), gear("minecraft:golden_chestplate"),
        gear("minecraft:golden_leggings"), gear("minecraft:golden_boots"),
        stack("minecraft:cobblestone", 8), stack("minecraft:coal", 6), stack("minecraft:torch", 8),
        gear("minecraft:furnace"), stack("minecraft:gold_ingot", 3), stack("minecraft:cooked_porkchop", 4))),

    IRON(List.of(
        gear("minecraft:iron_sword"), gear("minecraft:iron_pickaxe"), gear("minecraft:iron_axe"),
        gear("minecraft:iron_shovel"), gear("minecraft:iron_hoe"),
        gear("minecraft:iron_helmet"), gear("minecraft:iron_chestplate"),
        gear("minecraft:iron_leggings"), gear("minecraft:iron_boots"),
        gear("minecraft:shield"), gear("minecraft:crossbow"), gear("minecraft:bucket"),
        gear("minecraft:flint_and_steel"),
        stack("minecraft:iron_ingot", 4), stack("minecraft:raw_iron", 4), stack("minecraft:cooked_beef", 4))),

    DIAMOND(List.of(
        gear("minecraft:diamond_sword"), gear("minecraft:diamond_pickaxe"), gear("minecraft:diamond_axe"),
        gear("minecraft:diamond_shovel"), gear("minecraft:diamond_hoe"),
        gear("minecraft:diamond_helmet"), gear("minecraft:diamond_chestplate"),
        gear("minecraft:diamond_leggings"), gear("minecraft:diamond_boots"),
        stack("minecraft:diamond", 2), stack("minecraft:obsidian", 4), stack("minecraft:golden_apple", 1),
        stack("minecraft:ender_pearl", 2))),

    NETHERITE(List.of(
        gear("minecraft:netherite_sword"), gear("minecraft:netherite_pickaxe"), gear("minecraft:netherite_axe"),
        gear("minecraft:netherite_shovel"), gear("minecraft:netherite_hoe"),
        gear("minecraft:netherite_helmet"), gear("minecraft:netherite_chestplate"),
        gear("minecraft:netherite_leggings"), gear("minecraft:netherite_boots"),
        stack("minecraft:netherite_ingot", 1), stack("minecraft:netherite_scrap", 2),
        stack("minecraft:ancient_debris", 1)));

    /** One pool entry: an item id and the most of it one pick may hand out (1 for gear). */
    public record Entry(String itemId, int maxCount) {}

    private static final Map<String, GearTier> BY_ID = new HashMap<>();

    static {
        for (GearTier tier : values()) {
            for (Entry entry : tier.pool) {
                GearTier previous = BY_ID.put(entry.itemId(), tier);
                if (previous != null) {
                    throw new IllegalStateException(entry.itemId() + " is in both " + previous + " and " + tier);
                }
            }
        }
    }

    private final List<Entry> pool;

    GearTier(List<Entry> pool) {
        this.pool = pool;
    }

    /** The items a natural spawn may be handed at exactly this tier. */
    public List<Entry> pool() {
        return pool;
    }

    /** The tier {@code itemId} (e.g. {@code "minecraft:iron_pickaxe"}) belongs to; {@link #NONE} if unlisted. */
    public static GearTier classify(String itemId) {
        if (itemId == null) {
            return NONE;
        }
        return BY_ID.getOrDefault(itemId, NONE);
    }

    /** The highest tier among {@code itemIds}; {@link #NONE} for an empty list. */
    public static GearTier best(Iterable<String> itemIds) {
        GearTier best = NONE;
        for (String id : itemIds) {
            GearTier tier = classify(id);
            if (tier.ordinal() > best.ordinal()) {
                best = tier;
            }
        }
        return best;
    }

    private static Entry gear(String id) {
        return new Entry(id, 1);
    }

    private static Entry stack(String id, int maxCount) {
        return new Entry(id, maxCount);
    }
}
