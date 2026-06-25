package games.brennan.playermob.compat;

import games.brennan.playermob.entity.goal.CommandedFakePlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Supplies the per-level fake player that drives {@code /playermob order ... use} interactions
 * ({@link games.brennan.playermob.entity.goal.CommandedUse}).
 *
 * <p>Defaults to the common {@link CommandedFakePlayer} (a plain vanilla {@link ServerPlayer}
 * subclass — the only option on Fabric and on Forge 1.21.1, which no longer ships
 * {@code FakePlayerFactory}). A loader that <em>does</em> ship a real fake player overrides this at
 * boot with {@link #install} so modded items that check {@code instanceof FakePlayer} or read
 * capabilities behave — most notably NeoForge.</p>
 *
 * <p>Server-thread only.</p>
 */
public final class FakePlayerSource {

    /** Supplies a (typically cached) fake player bound to {@code level}. */
    public interface Provider {
        ServerPlayer get(ServerLevel level);
    }

    private static volatile Provider provider = CommandedFakePlayer::get;

    private FakePlayerSource() {}

    /** Install a loader-specific provider (e.g. NeoForge's {@code FakePlayerFactory}). Called once at boot. */
    public static void install(Provider newProvider) {
        provider = newProvider;
    }

    /** The fake player for {@code level} (loader-provided if installed, else the common one). */
    public static ServerPlayer get(ServerLevel level) {
        return provider.get(level);
    }
}
