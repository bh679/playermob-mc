package games.brennan.playermob.fabric.client;

import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.client.PlayerMobRenderer;
import games.brennan.playermob.client.VersionHudRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

/**
 * Fabric client-side entrypoint. Registers {@link PlayerMobRenderer} against
 * the entity type populated by {@link games.brennan.playermob.fabric.PlayerMobFabric#onInitialize()},
 * plus the dev-only build-info HUD ({@link VersionHudRenderer}).
 *
 * <p>Registered via {@code fabric.mod.json} under {@code entrypoints.client}.</p>
 */
@Environment(EnvType.CLIENT)
public final class PlayerMobFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(
            PlayerMobRegistry.PLAYER_MOB,
            PlayerMobRenderer::new);

        // Dev-only build-info HUD — hidden on the `main`/release branch.
        HudRenderCallback.EVENT.register(
            (graphics, tickCounter) -> VersionHudRenderer.render(graphics));
    }
}
