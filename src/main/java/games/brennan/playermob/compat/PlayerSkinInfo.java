package games.brennan.playermob.compat;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if >=26 {
/*import net.minecraft.resources.Identifier;
*///?} else {
import net.minecraft.resources.ResourceLocation;
//?}

/**
 * Version-neutral stand-in for vanilla's per-version "resolved player skin" types
 * ({@code net.minecraft.client.resources.PlayerSkin} on 1.21.1,
 * {@code net.minecraft.world.entity.player.PlayerSkin} on 26.x). Carries the two fields
 * PlayerMob actually reads — the resolved {@link #texture() texture} and whether the skin
 * uses the {@link #slim() slim} (3-pixel) arm model — so the renderer and menu stay identical
 * across versions.
 *
 * <p>The texture handle is the version's namespaced-id type ({@code ResourceLocation} pre-26,
 * {@code Identifier} on 26.x — the class was renamed); render/blit calls accept it directly.</p>
 *
 * <p>Produced by {@link SkinCompat#insecureSkin}; client-only.</p>
 */
@Environment(EnvType.CLIENT)
//? if >=26 {
/*public record PlayerSkinInfo(Identifier texture, boolean slim) {
}
*///?} else {
public record PlayerSkinInfo(ResourceLocation texture, boolean slim) {
}
//?}
