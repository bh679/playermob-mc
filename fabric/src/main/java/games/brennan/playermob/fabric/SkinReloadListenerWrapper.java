package games.brennan.playermob.fabric;

import games.brennan.playermob.skin.PlayerMobSkinReloadListener;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
//? if >=26 {
/*import net.minecraft.resources.Identifier;
*///?} else {
import net.minecraft.resources.ResourceLocation;
//?}

/**
 * Fabric-side wrapper around {@link PlayerMobSkinReloadListener}. Fabric's
 * {@code ResourceManagerHelper.registerReloadListener} requires an
 * {@link IdentifiableResourceReloadListener} — an ID is needed for dependency
 * sorting and for the reload progress UI. The common-side listener stays
 * loader-agnostic; this thin wrapper just adds the Fabric-specific ID
 * contract.
 */
public final class SkinReloadListenerWrapper
        extends PlayerMobSkinReloadListener
        implements IdentifiableResourceReloadListener {

    //? if >=26 {
    /*private final Identifier id;

    public SkinReloadListenerWrapper(Identifier id) {
        this.id = id;
    }

    @Override
    public Identifier getFabricId() {
        return id;
    }
    *///?} else {
    private final ResourceLocation id;

    public SkinReloadListenerWrapper(ResourceLocation id) {
        this.id = id;
    }

    @Override
    public ResourceLocation getFabricId() {
        return id;
    }
    //?}
}
