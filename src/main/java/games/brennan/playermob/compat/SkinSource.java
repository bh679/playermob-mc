package games.brennan.playermob.compat;

import games.brennan.playermob.skin.PlayerMobSkin;

import java.util.List;

/**
 * A programmatic supplier of PlayerMob skins, for an integrating mod that wants to add to the pool
 * a spawning PlayerMob draws from — beyond what a datapack ships. Register one with
 * {@link SkinSources#register}.
 *
 * <p>Return <b>fully-resolved</b> entries: each {@link PlayerMobSkin} carries a real Mojang CDN
 * texture URL (and arm model). To build entries from player <em>names</em> instead, resolve them
 * first with {@link games.brennan.playermob.skin.PlayerSkinResolver#resolveAsync} (off-thread,
 * cached) and cache the resulting {@code PlayerMobSkin}s in your source; never block in
 * {@link #skins()}.</p>
 *
 * <p>{@link #skins()} is polled when sources change (and may be polled again later), so it should
 * return quickly from local state. The registry de-duplicates by texture URL across all sources and
 * the datapack pool, so overlapping entries are harmless.</p>
 */
@FunctionalInterface
public interface SkinSource {

    /** This source's current skins. Never block; return from local state. May be empty, never null. */
    List<PlayerMobSkin> skins();
}
