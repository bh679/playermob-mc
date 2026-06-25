package games.brennan.playermob.entity.goal;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * A minimal, cached fake player used to drive {@code /playermob order ... use} item
 * interactions ({@link CommandedUse}) — a PlayerMob has no player of its own, yet
 * item/entity interactions are written against {@link net.minecraft.world.entity.player.Player}.
 *
 * <p>Kept in common (a plain vanilla {@link ServerPlayer} subclass) rather than behind a
 * per-loader seam, because Forge 1.21.1 no longer ships {@code FakePlayerFactory}; one
 * implementation then works identically on Fabric, Forge and NeoForge. One instance is cached
 * per level; it is never added to the world or ticked, only positioned and used transiently.</p>
 *
 * <p>{@link #awardStat} is stubbed: a freshly constructed, never-joined ServerPlayer has no
 * stats manager, so the {@code awardStat} call inside vanilla item placement/use would NPE.</p>
 */
public final class CommandedFakePlayer extends ServerPlayer {

    private static final GameProfile PROFILE =
        new GameProfile(UUID.fromString("a9f8e7d6-0000-4000-8000-706c61796d62"), "[PlayerMob]");
    private static final Map<ServerLevel, CommandedFakePlayer> CACHE = new WeakHashMap<>();

    private CommandedFakePlayer(ServerLevel level) {
        //? if >=1.21.1 {
        super(level.getServer(), level, PROFILE, net.minecraft.server.level.ClientInformation.createDefault());
        //?} else {
        /*super(level.getServer(), level, PROFILE);
        *///?}
    }

    /** The cached fake player bound to {@code level}, created on first use. */
    public static ServerPlayer get(ServerLevel level) {
        return CACHE.computeIfAbsent(level, CommandedFakePlayer::new);
    }

    @Override
    public void awardStat(Stat<?> stat, int amount) {
        // no-op: a fake player has no stats manager
    }
}
