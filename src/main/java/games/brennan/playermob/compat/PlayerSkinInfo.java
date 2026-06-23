package games.brennan.playermob.compat;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;

/**
 * Version-neutral stand-in for vanilla {@code net.minecraft.client.resources.PlayerSkin},
 * which only exists in MC 1.21.1. Carries the two fields PlayerMob actually reads — the
 * resolved {@link #texture() texture} and whether the skin uses the {@link #slim() slim}
 * (3-pixel) arm model — so the renderer and menu stay identical across versions.
 *
 * <p>Produced by {@link SkinCompat#insecureSkin}; client-only.</p>
 *
 * @param texture the renderable skin texture (may be a default while a fetch is in flight)
 * @param slim    true if the skin uses the slim arm model
 */
@Environment(EnvType.CLIENT)
public record PlayerSkinInfo(ResourceLocation texture, boolean slim) {
}
