package games.brennan.playermob.compat;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
//? if >=26 {
/*import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
*///?} else {
import net.minecraft.resources.ResourceLocation;
//? if >=1.21.1 {
import net.minecraft.client.resources.PlayerSkin;
//?}
//?}

import java.util.UUID;

/**
 * Version-bridging accessors for the client skin pipeline, rewritten twice across the
 * supported MC range.
 *
 * <ul>
 *   <li><b>1.20.1</b> — {@code SkinManager.getInsecureSkinLocation} (a bare
 *       {@code ResourceLocation}), model resolved separately as a {@code String};
 *       {@code PlayerInfo.getSkinLocation()}; {@code DefaultPlayerSkin.getDefaultSkin()}.</li>
 *   <li><b>1.21.1</b> — {@code SkinManager.getInsecureSkin} → {@code client.resources.PlayerSkin}
 *       record ({@code .texture()} + {@code .model()}); {@code PlayerInfo.getSkin()};
 *       {@code DefaultPlayerSkin.getDefaultTexture()} / {@code get(uuid).texture()}.</li>
 *   <li><b>26.x</b> — {@code ResourceLocation} renamed {@code Identifier}; the resolved skin is
 *       now {@code world.entity.player.PlayerSkin} whose {@code body()} is a
 *       {@code ClientAsset.Texture} ({@code .texturePath()} → {@code Identifier}) and whose
 *       {@code model()} is a {@code PlayerModelType} ({@code SLIM}/{@code WIDE}). The synchronous
 *       insecure lookup moved from {@code getInsecureSkin(profile)} to
 *       {@code createLookup(profile, false).get()} (returns a default while the async fetch is in
 *       flight). {@code DefaultPlayerSkin.getDefaultTexture()} / {@code get(uuid).body().texturePath()}.</li>
 * </ul>
 *
 * <p>All callers go through {@link PlayerSkinInfo}, a tiny version-neutral
 * {@code (texture, slim)} pair, so the renderer/menu code is uniform. Client-only.</p>
 */
@Environment(EnvType.CLIENT)
public final class SkinCompat {

    private SkinCompat() {}

    /**
     * Resolve {@code profile}'s skin through vanilla {@code SkinManager} without
     * signature validation — the texture the client would render that profile with,
     * falling back internally to a UUID-derived default while any async fetch is in
     * flight. Never returns {@code null}.
     */
    public static PlayerSkinInfo insecureSkin(GameProfile profile) {
        //? if >=26 {
        /*PlayerSkin skin = Minecraft.getInstance().getSkinManager().createLookup(profile, false).get();
        return new PlayerSkinInfo(skin.body().texturePath(), skin.model() == PlayerModelType.SLIM);
        *///?} else {
        //? if >=1.21.1 {
        PlayerSkin skin = Minecraft.getInstance().getSkinManager().getInsecureSkin(profile);
        return new PlayerSkinInfo(skin.texture(), skin.model() == PlayerSkin.Model.SLIM);
        //?} else {
        /*ResourceLocation texture = Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(profile);
        boolean slim = "slim".equals(DefaultPlayerSkin.getSkinModelName(profile.getId()));
        return new PlayerSkinInfo(texture, slim);*/
        //?}
        //?}
    }

    /** The generic Steve/Alex default texture (no UUID context). */
    //? if >=26 {
    /*public static Identifier defaultTexture() {
        return DefaultPlayerSkin.getDefaultTexture();
    }
    *///?} else {
    public static ResourceLocation defaultTexture() {
        //? if >=1.21.1 {
        return DefaultPlayerSkin.getDefaultTexture();
        //?} else {
        /*return DefaultPlayerSkin.getDefaultSkin();*/
        //?}
    }
    //?}

    /** The default texture for a specific UUID (its deterministic Steve/Alex). */
    //? if >=26 {
    /*public static Identifier defaultTextureFor(UUID id) {
        return DefaultPlayerSkin.get(id).body().texturePath();
    }
    *///?} else {
    public static ResourceLocation defaultTextureFor(UUID id) {
        //? if >=1.21.1 {
        return DefaultPlayerSkin.get(id).texture();
        //?} else {
        /*return DefaultPlayerSkin.getDefaultSkin(id);*/
        //?}
    }
    //?}

    /** The texture a tab-list {@link PlayerInfo} resolves to, or {@code null}. */
    //? if >=26 {
    /*public static Identifier playerInfoTexture(PlayerInfo info) {
        return info.getSkin().body().texturePath();
    }
    *///?} else {
    public static ResourceLocation playerInfoTexture(PlayerInfo info) {
        //? if >=1.21.1 {
        return info.getSkin().texture();
        //?} else {
        /*return info.getSkinLocation();*/
        //?}
    }
    //?}
}
