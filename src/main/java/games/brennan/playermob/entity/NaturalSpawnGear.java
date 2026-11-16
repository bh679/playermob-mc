package games.brennan.playermob.entity;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.PlayerMobConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * A wild PlayerMob arrives carrying a random handful of items no better than what the nearest real player
 * has: the player's tier is the best {@link GearTier} anywhere on them (armour, hands, whole inventory), and
 * each item is drawn from that tier or any tier below it.
 *
 * <p>Only wild spawns ({@link #isWildSpawn}: natural, chunk-generation, structure). Rolled on the mob's
 * first server tick rather than in {@code finalizeSpawn}, because the village-companion path finalizes on a
 * worldgen worker thread where live player inventories must not be read; a chunk-generated mob is geared
 * for whoever is nearest when it first ticks.</p>
 *
 * <p>Two switches: the {@code naturalSpawnGear} config key (players), and {@link #setEnabled} (a consuming
 * mod, called once at boot). Dungeon Train turns it off: it gears its own PlayerMobs by difficulty.</p>
 */
public final class NaturalSpawnGear {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Fewest / most items one wild PlayerMob is handed. */
    static final int MIN_ITEMS = 2;
    static final int MAX_ITEMS = 6;

    private static volatile boolean enabledByHost = true;
    private static volatile Map<String, Item> itemsById;

    /** One rolled item: id + count. */
    public record Pick(String itemId, int count) {}

    private NaturalSpawnGear() {}

    /**
     * Turn the feature off (or back on) for this game. Called once at boot by a consuming mod that gears
     * PlayerMobs itself; never called otherwise, so it stays on.
     */
    public static void setEnabled(boolean enabled) {
        enabledByHost = enabled;
    }

    /** Whether wild PlayerMobs get spawn items right now: the host mod allows it and the config is on. */
    public static boolean active() {
        return enabledByHost && PlayerMobConfig.naturalSpawnGear();
    }

    /** Whether a spawn-reason name (e.g. {@code "NATURAL"}) is a wild spawn that gets items. */
    public static boolean isWildSpawn(String reasonName) {
        return "NATURAL".equals(reasonName) || "CHUNK_GENERATION".equals(reasonName)
            || "STRUCTURE".equals(reasonName);
    }

    /**
     * Roll the items for a mob whose nearest player is at {@code playerTier}. Each item picks a tier
     * uniformly from {@link GearTier#NONE} up to {@code playerTier}, then an entry uniformly from that
     * tier's pool, then a count from 1 to the entry's max. {@code nextInt} returns a value in {@code [0, n)}.
     */
    public static List<Pick> roll(GearTier playerTier, IntUnaryOperator nextInt) {
        int items = MIN_ITEMS + nextInt.applyAsInt(MAX_ITEMS - MIN_ITEMS + 1);
        List<Pick> picks = new ArrayList<>(items);
        GearTier[] tiers = GearTier.values();
        for (int i = 0; i < items; i++) {
            GearTier tier = tiers[nextInt.applyAsInt(playerTier.ordinal() + 1)];
            List<GearTier.Entry> pool = tier.pool();
            GearTier.Entry entry = pool.get(nextInt.applyAsInt(pool.size()));
            int count = entry.maxCount() <= 1 ? 1 : 1 + nextInt.applyAsInt(entry.maxCount());
            picks.add(new Pick(entry.itemId(), count));
        }
        return Collections.unmodifiableList(picks);
    }

    /** Whether {@code itemId} is a weapon or tool a mob would hold in its main hand. */
    static boolean isHandheld(String itemId) {
        return itemId.endsWith("_sword") || itemId.endsWith("_axe") || itemId.endsWith("_pickaxe")
            || itemId.endsWith("_shovel") || itemId.endsWith("_hoe")
            || itemId.equals("minecraft:bow") || itemId.equals("minecraft:crossbow");
    }

    /**
     * Hand {@code mob} its spawn items, matched to the nearest real player in its level. No player there:
     * nothing (the mob stays empty-handed, as before this feature). Never throws.
     */
    static void apply(PlayerMobEntity mob, ServerLevel level) {
        try {
            ServerPlayer player = nearestRealPlayer(mob, level);
            if (player == null) {
                return;
            }
            GearTier tier = tierOf(player);
            List<Pick> picks = roll(tier, bound -> mob.getRandom().nextInt(bound));
            List<ItemStack> stacks = new ArrayList<>(picks.size());
            for (Pick pick : picks) {
                Item item = itemFor(pick.itemId());
                if (item != null && item != Items.AIR) {
                    stacks.add(new ItemStack(item, pick.count()));
                }
            }
            mob.receiveSpawnGear(stacks);
            if (PlayerMobConfig.debugSpawnLog()) {
                LOGGER.info("[NaturalGear] {} near {} (tier {}) got {}", mob.getUUID(),
                    player.getName().getString(), tier, picks);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[playermob] natural-spawn items failed for {}; it spawns empty-handed", mob, e);
        }
    }

    /** The nearest living, non-spectator player in {@code level}; fake players (any subclass) never count. */
    private static ServerPlayer nearestRealPlayer(PlayerMobEntity mob, ServerLevel level) {
        ServerPlayer nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (player.getClass() != ServerPlayer.class || player.isSpectator() || !player.isAlive()) {
                continue;
            }
            double dist = player.distanceToSqr(mob);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = player;
            }
        }
        return nearest;
    }

    /** The best tier anywhere on {@code player}: every inventory slot (hotbar, main, armour, offhand) plus worn gear. */
    private static GearTier tierOf(ServerPlayer player) {
        List<String> ids = new ArrayList<>();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            addId(ids, inventory.getItem(i));
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            addId(ids, player.getItemBySlot(slot));
        }
        return GearTier.best(ids);
    }

    private static void addId(List<String> ids, ItemStack stack) {
        if (!stack.isEmpty()) {
            ids.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
    }

    /** Id to item, built once from the item registry (the same call on every Minecraft version). */
    private static Item itemFor(String itemId) {
        Map<String, Item> map = itemsById;
        if (map == null) {
            map = new HashMap<>();
            for (Item item : BuiltInRegistries.ITEM) {
                map.put(BuiltInRegistries.ITEM.getKey(item).toString(), item);
            }
            itemsById = map;
        }
        return map.get(itemId);
    }
}
