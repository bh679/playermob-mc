package games.brennan.playermob.player;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
            store.completeLife(player.getUUID(), player.getGameProfile().getName(), snapshot);
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] failed to snapshot reincarnation for {}", player.getGameProfile().getName(), e);
        }
    }

    /**
     * Build a spawn egg stamped with the player's last-life snapshot, or
     * {@link ItemStack#EMPTY} if that player has no recorded past life.
     */
    public static ItemStack reincarnationEgg(ServerLevel level, UUID playerId) {
        PlayerLifeStore store = PlayerLifeStore.get(level);
        CompoundTag snapshot = store.lastLife(playerId);
        if (snapshot == null) {
            return ItemStack.EMPTY;
        }
        ItemStack egg = new ItemStack(PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG);
        egg.set(DataComponents.ENTITY_DATA, CustomData.of(snapshot));
        String name = store.lastName(playerId);
        egg.set(DataComponents.CUSTOM_NAME,
            Component.literal("Echo of " + (name != null ? name : "a Lost Soul")));
        return egg;
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

        applySkin(ghost, player, level);
        applyGear(ghost, player);

        CompoundTag snapshot = new CompoundTag();
        ghost.addAdditionalSaveData(snapshot);
        // Overwrite the ghost's default 5/5 traits with the life-derived ones. The
        // entity already wrote the trait keys, so this is the single authoritative
        // write of FightFlight/Friendliness.
        record.toTraits().save(snapshot);

        ghost.discard();
        return snapshot;
    }

    /** The dead player's own Minecraft skin, or a random bundled mob skin if unavailable. */
    private static void applySkin(PlayerMobEntity ghost, ServerPlayer player, ServerLevel level) {
        Optional<ProfileSkins.Skin> skin = ProfileSkins.extract(player.getGameProfile());
        if (skin.isPresent()) {
            ghost.setSkinTextureUrl(skin.get().url());
            ghost.setSkinSlim(skin.get().slim());
        } else {
            ghost.setSkinIndex(level.getRandom().nextInt(PlayerMobEntity.SKIN_COUNT));
        }
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
