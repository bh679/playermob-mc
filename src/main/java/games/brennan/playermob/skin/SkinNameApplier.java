package games.brennan.playermob.skin;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.server.MinecraftServer;

/**
 * Applies the skin named by a player name (or local-skin-folder filename) to a {@link PlayerMobEntity} —
 * a local file wins synchronously, otherwise the name is resolved as a real player off-thread via
 * {@link PlayerSkinResolver}. Shared by {@code /playermob summon} and the {@code SkinPlayerName} NBT tag
 * ({@code PlayerMobEntity#onAddedToLevel}) so the two summon paths can't diverge.
 */
public final class SkinNameApplier {

    private SkinNameApplier() {}

    /** Convenience overload for callers with nothing to do once the skin lands. */
    public static void apply(MinecraftServer server, String name, PlayerMobEntity mob) {
        apply(server, name, mob, () -> { });
    }

    /**
     * Resolve {@code name} and apply it to {@code mob}, then run {@code onApplied}. A matching file in the
     * local skin folder is applied immediately (so {@code onApplied} runs synchronously); otherwise the
     * name is looked up as a player async, applying and running {@code onApplied} only if {@code mob} is
     * still present when the lookup completes (silently dropped otherwise).
     */
    public static void apply(MinecraftServer server, String name, PlayerMobEntity mob, Runnable onApplied) {
        if (LocalSkinFolder.resolve(name) != null) {
            mob.setSkinTextureUrl(LocalSkinRef.encode(name));
            onApplied.run();
            return;
        }
        PlayerSkinResolver.resolveAsync(server, name, opt -> {
            if (mob.isRemoved()) {
                return;
            }
            opt.ifPresent(resolved -> {
                mob.setSkinTextureUrl(resolved.url());
                mob.setSkinSlim(resolved.slim());
                onApplied.run();
            });
        });
    }
}
