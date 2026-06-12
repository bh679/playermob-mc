package games.brennan.playermob.player;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ServerLevelAccessor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns a dying player into a reincarnation snapshot, and a stored snapshot into
 * a spawn egg. The bridge between {@link PlayerLifeStore} (what the player did and
 * carried) and the {@link PlayerMobEntity} that is later summoned to embody it.
 *
 * <p>The snapshot is built by populating a throwaway "ghost" PlayerMobEntity and
 * serialising it with the entity's own {@code addAdditionalSaveData} — so the NBT
 * keys (traits, equipment, backpack, skin) are guaranteed to match what the entity
 * reads back, with no hand-assembled tags. The egg carries that snapshot in its
 * {@code ENTITY_DATA} component, which vanilla merges onto the spawned mob.</p>
 */
public final class PlayerReincarnation {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Chance that a Dungeon-Train ({@link net.minecraft.world.entity.MobSpawnType#EVENT})
     * PlayerMob spawn embodies a stored past life instead of a fresh random mob. The
     * single tunable for that behaviour — the mod has no config system and expresses
     * every probability as a named constant (cf. {@code PlayerMobEntity.URL_SKIN_CHANCE}).
     * Composes on top of Dungeon Train's own "1-in-N" decision to spawn a group at all.
     */
    public static final float REINCARNATION_SPAWN_CHANCE = 0.25F; // 1-in-4 EVENT PlayerMobs attempt to embody a stored past life (echo when an eligible one exists)

    /** Slots copied verbatim from the dead player onto the reincarnated mob. */
    private static final EquipmentSlot[] WORN_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    };

    private PlayerReincarnation() {}

    /**
     * Called from the player-death mixin at the moment of death (inventory still
     * intact). Snapshots this life and resets the player's live tally. Never throws
     * into the death flow — any failure is logged and swallowed.
     */
    public static void onDeath(ServerPlayer player) {
        try {
            ServerLevel level = player.serverLevel();
            PlayerLifeStore store = PlayerLifeStore.get(level);
            PlayerLifeRecord record = store.current(player.getUUID());
            CompoundTag snapshot = buildSnapshot(level, player, record);
            // The completed snapshot is appended to the GLOBAL death log (cross-world); only
            // the in-progress tally was world-scoped, so reset it on the world store.
            store.resetCurrent(player.getUUID());
            // Record the carriage they died in so Dungeon-Train echoes can match depth.
            int carriage = TrainConfinement.carriageIndex(player);
            GlobalLifeStore global = GlobalLifeStore.get(level.getServer());
            global.append(player.getUUID(), player.getGameProfile().getName(), carriage, snapshot);
            // A new life begins on respawn — all past lives are available to meet again.
            global.resetSession(player.getUUID());
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] failed to snapshot reincarnation for {}", player.getGameProfile().getName(), e);
        }
    }

    /**
     * Build a spawn egg stamped with the player's last-life snapshot, or
     * {@link ItemStack#EMPTY} if that player has no recorded past life.
     */
    public static ItemStack reincarnationEgg(ServerLevel level, UUID playerId) {
        GlobalLifeStore store = GlobalLifeStore.get(level.getServer());
        CompoundTag snapshot = store.mostRecentForPlayer(playerId);
        if (snapshot == null) {
            return ItemStack.EMPTY;
        }
        ItemStack egg = new ItemStack(PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG);
        egg.set(DataComponents.ENTITY_DATA, CustomData.of(snapshot));
        String name = store.mostRecentNameForPlayer(playerId);
        egg.set(DataComponents.CUSTOM_NAME,
            Component.literal("Echo of " + (name != null ? name : "a Lost Soul")));
        return egg;
    }

    /**
     * With probability {@link #REINCARNATION_SPAWN_CHANCE}, turn a freshly-created
     * Dungeon-Train PlayerMob into a stored past life; returns {@code true} if it did.
     * Called from {@link PlayerMobEntity#finalizeSpawn} for {@code EVENT} spawns,
     * <em>before</em> the default skin/trait rolls — applying the snapshot via
     * {@code readAdditionalSaveData} pins the skin and traits explicit so those rolls
     * become no-ops (the same mechanism the reincarnation egg relies on).
     *
     * <p>The life is drawn from the global death log by {@link GlobalLifeStore#pickEchoFor}:
     * deaths within {@link GlobalLifeStore#CARRIAGE_RADIUS} carriages of the spawn that the
     * nearest player hasn't met this life, weighted toward newer deaths. No eligible life
     * (empty band / all met / unresolved carriage) returns {@code false} so the spawn falls
     * back to a normal random PlayerMob. Never throws into the spawn flow.</p>
     */
    public static boolean maybeReincarnateOnSpawn(PlayerMobEntity mob, ServerLevelAccessor world) {
        try {
            if (world.getRandom().nextFloat() >= REINCARNATION_SPAWN_CHANCE) {
                return false;
            }
            ServerLevel level = world.getLevel();
            int spawnCarriage = TrainConfinement.spawnCarriageIndex(mob);
            // The nearest player owns the "met this life" set that gates reuse (singleplayer = the player).
            Player nearest = level.getNearestPlayer(mob, -1.0);
            UUID owner = nearest == null ? null : nearest.getUUID();
            GlobalLifeStore store = GlobalLifeStore.get(level.getServer());
            GlobalLifeStore.DeathRecord echo = store.pickEchoFor(owner, spawnCarriage, world.getRandom());
            if (echo == null) {
                return false;
            }
            mob.readAdditionalSaveData(echo.snapshot().copy());
            // Name the echo after the past life so it reads as a returning soul — and,
            // because AdventureItemNames skips mobs that already carry a CustomName, so AIN
            // doesn't overwrite it with a random PlayerMob name. AIN's finalizeSpawn naming
            // runs when super.finalizeSpawn returns (after this), so setting the name here
            // wins. Mirrors the reincarnation egg's "Echo of X" label.
            mob.setCustomName(Component.literal("Echo of " + echo.name()));
            mob.setCustomNameVisible(true);
            return true;
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] failed to apply a reincarnation on spawn", e);
            return false;
        }
    }

    // ---- snapshot building ------------------------------------------------

    private static CompoundTag buildSnapshot(ServerLevel level, ServerPlayer player, PlayerLifeRecord record) {
        PlayerMobEntity ghost = PlayerMobRegistry.PLAYER_MOB.create(level);
        if (ghost == null) {
            // Entity type is always registered by the time a player can die; this is
            // pure defensiveness. Fall back to a traits-only snapshot.
            CompoundTag tag = new CompoundTag();
            record.toTraits().save(tag);
            return tag;
        }

        applySkin(ghost, player);
        applyGear(ghost, player);

        CompoundTag snapshot = new CompoundTag();
        ghost.addAdditionalSaveData(snapshot);
        // Overwrite the ghost's default 5/5 traits with the life-derived ones. The
        // entity already wrote the trait keys, so this is the single authoritative
        // write of FightFlight/Friendliness.
        record.toTraits().save(snapshot);
        // A spawn egg's ENTITY_DATA must name its entity type, or ItemStack encoding
        // throws "Missing id for entity" the moment the egg is saved in an inventory.
        snapshot.putString("id", PlayerMobRegistry.PLAYER_MOB_ID.toString());

        ghost.discard();
        return snapshot;
    }

    /**
     * Tag the mob as a reincarnation of this player. The renderer resolves the skin
     * from this identity through vanilla {@code SkinManager} — the player's real skin
     * in production, their UUID-derived default skin offline/in dev — so the mob always
     * matches how the player is rendered, and stays current if they reskin.
     */
    private static void applySkin(PlayerMobEntity ghost, ServerPlayer player) {
        ghost.setSkinTextureUrl(
            SourceProfileSkin.encode(player.getUUID(), player.getGameProfile().getName()));
    }

    /**
     * Copy the gear the player died with onto the mob: worn armor + held weapon +
     * offhand into the matching slots, then the rest of their pack into the mob's
     * backpack until it's full. Food goes in first (so the mob can eat); the rest
     * follows in inventory order — hotbar first, which naturally favours the tools
     * a player keeps to hand over deep-storage clutter.
     */
    private static void applyGear(PlayerMobEntity ghost, ServerPlayer player) {
        for (EquipmentSlot slot : WORN_SLOTS) {
            ItemStack worn = player.getItemBySlot(slot);
            if (!worn.isEmpty()) {
                ghost.setItemSlot(slot, worn.copy());
            }
        }

        Inventory inv = player.getInventory();
        List<ItemStack> foods = new ArrayList<>();
        List<ItemStack> other = new ArrayList<>();
        for (int i = 0; i < inv.items.size(); i++) {
            if (i == inv.selected) {
                continue; // the held item already went to MAINHAND
            }
            ItemStack stack = inv.items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            (stack.has(DataComponents.FOOD) ? foods : other).add(stack.copy());
        }

        SimpleContainer backpack = ghost.getInventory();
        fill(backpack, other, fill(backpack, foods, 0));
    }

    /** Drop {@code stacks} into the backpack from {@code startSlot}; returns the next free slot. */
    private static int fill(SimpleContainer backpack, List<ItemStack> stacks, int startSlot) {
        int slot = startSlot;
        for (ItemStack stack : stacks) {
            if (slot >= backpack.getContainerSize()) {
                break;
            }
            backpack.setItem(slot++, stack);
        }
        return slot;
    }
}
