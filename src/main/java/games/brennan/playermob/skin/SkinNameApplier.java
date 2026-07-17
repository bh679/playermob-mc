package games.brennan.playermob.skin;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;

/**
 * Applies the skin named by a player name (or local-skin-folder filename) to a {@link PlayerMobEntity} —
 * a local file wins synchronously, otherwise the name is resolved as a real player off-thread via
 * {@link PlayerSkinResolver}. Shared by {@code /playermob summon} and the {@code SkinPlayerName} NBT tag
 * ({@code PlayerMobEntity#onAddedToLevel}) so the two summon paths can't diverge.
 */
public final class SkinNameApplier {

    private SkinNameApplier() {}

    /**
     * Apply the skin synchronously <b>only if it's immediately available</b> — a local-folder file, or a
     * player already resolved in {@link PlayerSkinResolver}'s in-process cache — returning {@code true}.
     * Returns {@code false} without touching the mob when the name still needs an off-thread network
     * resolve; the caller then keeps it pending for the async path.
     *
     * <p>Called from {@code finalizeSpawn} <em>before the entity is tracked to clients</em>, so a
     * repeat spawn of an already-seen skin bakes the texture URL into the spawn packet itself — no
     * default-skin frame, and no race between the async first-tick apply and the entity's spawn packet
     * (the cause of an intermittent default skin on egg-spawned mobs).</p>
     */
    public static boolean applyIfImmediate(String name, PlayerMobEntity mob) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (LocalSkinFolder.resolve(name) != null) {
            mob.setSkinTextureUrl(LocalSkinRef.encode(name));
            return true;
        }
        Optional<PlayerSkinResolver.Resolved> hit = PlayerSkinResolver.cached(name);
        if (hit.isPresent()) {
            mob.setSkinTextureUrl(hit.get().url());
            mob.setSkinSlim(hit.get().slim());
            return true;
        }
        return false;
    }

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
