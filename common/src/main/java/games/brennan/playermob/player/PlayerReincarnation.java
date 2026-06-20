package games.brennan.playermob.player;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.logging.LogUtils;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.compat.ReincarnationQuery;
import games.brennan.playermob.compat.ReincarnationRecord;
import games.brennan.playermob.compat.ReincarnationSources;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.DispositionResolver;
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
import java.util.Arrays;
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
     * Separate per-spawn chances that a Dungeon-Train ({@link net.minecraft.world.entity.MobSpawnType#EVENT})
     * PlayerMob embodies a stored past life instead of a fresh mob: one slice for a <em>local</em>
     * life (the player's own death log) and an equal, independent slice for a <em>remote</em> life
     * supplied by an integrating mod. They partition the spawn roll ({@code SELF} + {@code REMOTE}
     * + fresh = 1), so a remote pool never erodes the local self-reincarnation chance. Named
     * constants in the mod's convention (cf. {@code PlayerMobEntity.URL_SKIN_CHANCE}); compose on
     * top of Dungeon Train's own "1-in-N" decision to spawn a group at all.
     */
    public static final float SELF_REINCARNATION_CHANCE = 0.25F;   // 1-in-4 EVENT spawns try a LOCAL past life (self allowed)
    public static final float REMOTE_REINCARNATION_CHANCE = 0.25F; // a further 1-in-4 try a REMOTE past life (never the live player)

    /** Slots copied verbatim from the dead player onto the reincarnated mob. */
    private static final EquipmentSlot[] WORN_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    };

    /**
     * Radius, in blocks, scanned around a dying player for the loved-one PlayerMobs whose snapshots
     * become friend-echoes (see {@link #captureFriendSnapshots}). Generous enough to span a few
     * Dungeon-Train carriages, since loving mobs travel with the player; only loaded mobs are seen.
     */
    private static final double FRIEND_SCAN_RADIUS = 64.0;

    /** Most loved-one snapshots kept per death — bounds {@code lives.dat} growth (most-loved first). */
    private static final int FRIEND_SNAPSHOT_CAP = 4;

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
            // Snapshot the PlayerMobs that loved this player so an echo of them can later return
            // alongside a friend-echo of one of those loved ones (with its last-seen gear).
            List<CompoundTag> friends = captureFriendSnapshots(level, player);
            GlobalLifeStore global = GlobalLifeStore.get(level.getServer());
            global.append(player.getUUID(), player.getGameProfile().getName(), carriage, snapshot, friends);
            // A new life begins on respawn — clear which past lives this player has already met,
            // so every life across the whole reincarnation pool is available to meet again.
            ReincarnationSources.resetSession(player.getUUID());
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
     * Turn a freshly-created Dungeon-Train PlayerMob into a stored past life; returns the embodied
     * {@link ReincarnationRecord} (whose {@code friendSnapshots} the caller may replay as a
     * friend-echo), or {@code null} if it stayed a fresh mob.
     * Called from {@link PlayerMobEntity#finalizeSpawn} for {@code EVENT} spawns,
     * <em>before</em> the default skin/trait rolls — applying the snapshot via
     * {@code readAdditionalSaveData} pins the skin and traits explicit so those rolls
     * become no-ops (the same mechanism the reincarnation egg relies on).
     *
     * <p>The spawn roll is partitioned into two independent slices so the pools never compete: with
     * probability {@link #SELF_REINCARNATION_CHANCE} the mob embodies a <em>local</em> life (the
     * player's own death log, self included), else with probability {@link #REMOTE_REINCARNATION_CHANCE}
     * a <em>remote</em> life supplied by an integrating mod (never the live player themselves), else
     * it stays a fresh mob. Within the chosen pool the life is drawn for this spawn's carriage depth —
     * deaths within {@link GlobalLifeStore#CARRIAGE_RADIUS} carriages that the nearest player hasn't
     * met this life, weighted toward newer deaths and nearer carriages. An empty chosen pool returns
     * {@code null} (no cross-pool borrow) so the spawn falls back to a fresh PlayerMob. Never throws
     * into the spawn flow.</p>
     */
    public static ReincarnationRecord maybeReincarnateOnSpawn(PlayerMobEntity mob, ServerLevelAccessor world) {
        try {
            ServerLevel level = world.getLevel();
            int spawnCarriage = TrainConfinement.spawnCarriageIndex(mob);
            // The nearest player owns the "met this life" set that gates reuse (singleplayer = the player).
            Player nearest = level.getNearestPlayer(mob, -1.0);
            UUID owner = nearest == null ? null : nearest.getUUID();
            ReincarnationQuery query = ReincarnationQuery.byCarriage(spawnCarriage, owner);
            // Two separate slices — [0,SELF) a local life, [SELF,SELF+REMOTE) a remote one — so a
            // remote pool never eats into the local self-reincarnation chance; the rest stays fresh.
            float roll = world.getRandom().nextFloat();
            ReincarnationRecord echo;
            if (roll < SELF_REINCARNATION_CHANCE) {
                echo = ReincarnationSources.pick(level.getServer(), query, world.getRandom(), false).orElse(null);
            } else if (roll < SELF_REINCARNATION_CHANCE + REMOTE_REINCARNATION_CHANCE) {
                echo = ReincarnationSources.pick(level.getServer(), query, world.getRandom(), true).orElse(null);
            } else {
                return null;
            }
            if (echo == null) {
                return null; // the chosen pool had no eligible life — stay a fresh mob
            }
            mob.readAdditionalSaveData(echo.snapshot().copy());
            // Name the echo after the past life so it reads as a returning soul — and,
            // because AdventureItemNames skips mobs that already carry a CustomName, so AIN
            // doesn't overwrite it with a random PlayerMob name. AIN's finalizeSpawn naming
            // runs when super.finalizeSpawn returns (after this), so setting the name here
            // wins. Mirrors the reincarnation egg's "Echo of X" label.
            mob.setCustomName(Component.literal("Echo of " + echo.name()));
            mob.setCustomNameVisible(true);
            return echo;
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] failed to apply a reincarnation on spawn", e);
            return null;
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
     * Tag the mob as a reincarnation of this player and capture the player's current skin.
     *
     * <p>We read the skin texture URL + arm model already present on the dying player's
     * authenticated {@code GameProfile} (no network download — the server populated it at login)
     * and store the URL in the reincarnation ref plus the model in the synched slim flag. The
     * renderer then draws it through the normal Mojang-URL skin path. We capture rather than
     * resolve live because vanilla {@code SkinManager} can't turn a bare uuid into a skin (see
     * {@link SourceProfileSkin}). When no textures are available (offline/dev, or a pure-offline
     * server) the ref carries no URL and the mob falls back to the default skin — and capture
     * never throws into the death snapshot.</p>
     */
    private static void applySkin(PlayerMobEntity ghost, ServerPlayer player) {
        String url = "";
        try {
            MinecraftProfileTexture skin = player.getServer().getSessionService()
                .getTextures(player.getGameProfile()).skin();
            if (skin != null) {
                url = skin.getUrl();
                ghost.setSkinSlim("slim".equals(skin.getMetadata("model")));
            }
        } catch (RuntimeException e) {
            // Skin capture must never break snapshot building — fall back to a url-less ref
            // (renders the default skin, exactly as before capture existed).
            LOGGER.warn("[playermob] could not capture skin for reincarnation of {}",
                player.getGameProfile().getName(), e);
        }
        ghost.setSkinTextureUrl(
            SourceProfileSkin.encode(player.getUUID(), player.getGameProfile().getName(), url));
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

    // ---- friend capture (loved-one snapshots for friend-echoes) -----------

    /**
     * Snapshot the PlayerMobs that loved {@code player} at death — those within
     * {@link #FRIEND_SCAN_RADIUS} whose feeling is {@link DispositionResolver#FEELING_LOVE} or more —
     * keeping the {@link #FRIEND_SNAPSHOT_CAP} most-loved. Each snapshot is the mob's own
     * {@code addAdditionalSaveData} (skin/identity, gear, traits, feelings), the same form
     * {@link #buildSnapshot} captures, plus a {@code FriendLabel} for its friend-echo's title. Only
     * loaded mobs are visible; a loved one elsewhere on the train simply isn't logged this death.
     */
    private static List<CompoundTag> captureFriendSnapshots(ServerLevel level, ServerPlayer player) {
        List<PlayerMobEntity> lovers = new ArrayList<>();
        List<Float> feelings = new ArrayList<>();
        for (PlayerMobEntity mob : level.getEntitiesOfClass(
                PlayerMobEntity.class, player.getBoundingBox().inflate(FRIEND_SCAN_RADIUS))) {
            float feeling = mob.feelingToward(player);
            if (feeling >= DispositionResolver.FEELING_LOVE) {
                lovers.add(mob);
                feelings.add(feeling);
            }
        }
        if (lovers.isEmpty()) {
            return List.of();
        }
        float[] scores = new float[feelings.size()];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = feelings.get(i);
        }
        List<CompoundTag> out = new ArrayList<>();
        for (int idx : topIndices(scores, FRIEND_SNAPSHOT_CAP)) {
            out.add(snapshotFriend(lovers.get(idx)));
        }
        return out;
    }

    /** Serialise one loved-one mob into a friend snapshot: its full state plus its friend-echo label. */
    private static CompoundTag snapshotFriend(PlayerMobEntity mob) {
        CompoundTag tag = new CompoundTag();
        mob.addAdditionalSaveData(tag);
        // Egg/ItemStack encoding needs an "id"; harmless for the direct create()+readAdditionalSaveData path.
        tag.putString("id", PlayerMobRegistry.PLAYER_MOB_ID.toString());
        tag.putString(GlobalLifeStore.FRIEND_LABEL_KEY, friendLabel(mob));
        return tag;
    }

    /**
     * The name a loved one's friend-echo is titled with. A friend that is itself an echo carries its
     * source player's name in its skin ref — use that, so the result reads "Echo of &lt;player&gt;"
     * rather than "Echo of Echo of …"; otherwise fall back to the mob's display name.
     */
    private static String friendLabel(PlayerMobEntity mob) {
        return SourceProfileSkin.decode(mob.getSkinTextureUrl())
            .map(SourceProfileSkin.Ref::name)
            .filter(name -> !name.isBlank())
            .orElse(mob.getName().getString());
    }

    /**
     * Indices of the (up to) {@code cap} highest values in {@code scores}, highest first; ties keep
     * ascending index order. Pure (no world) so the most-loved selection is unit-tested directly.
     */
    static int[] topIndices(float[] scores, int cap) {
        Integer[] order = new Integer[scores.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Float.compare(scores[b], scores[a])); // highest first, stable on ties
        int k = Math.min(cap, scores.length);
        int[] out = new int[k];
        for (int i = 0; i < k; i++) {
            out[i] = order[i];
        }
        return out;
    }
}
