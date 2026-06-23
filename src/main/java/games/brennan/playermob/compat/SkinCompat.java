package games.brennan.playermob.compat;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

//? if >=1.21.1 {
import net.minecraft.client.resources.PlayerSkin;
//?}

import java.util.UUID;

/**
 * Version-bridging accessors for the client skin pipeline, which was rewritten
 * between MC 1.20.1 and 1.21.1. In 1.21.1 {@code SkinManager.getInsecureSkin}
 * returns a {@link PlayerSkin} record ({@code .texture()} + {@code .model()}); in
 * 1.20.1 the equivalent is {@code SkinManager.getInsecureSkinLocation} (a bare
 * {@link ResourceLocation}) with the model name resolved separately as a
 * {@code String}. {@link PlayerInfo} likewise exposed {@code getSkin()} in 1.21.1
 * vs {@code getSkinLocation()} / {@code getModelName()} in 1.20.1, and
 * {@code DefaultPlayerSkin} changed from {@code getDefaultTexture()} /
 * {@code get(uuid).texture()} to {@code getDefaultSkin()} / {@code getDefaultSkin(uuid)}.
 *
 * <p>All callers go through {@link PlayerSkinInfo}, a tiny version-neutral
 * {@code (texture, slim)} pair, so the renderer/menu code is uniform.</p>
 *
 * <p>Client-only — the whole skin stack is client-side.</p>
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
        //? if >=1.21.1 {
        PlayerSkin skin = Minecraft.getInstance().getSkinManager().getInsecureSkin(profile);
        return new PlayerSkinInfo(skin.texture(), skin.model() == PlayerSkin.Model.SLIM);
        //?} else {
        /*ResourceLocation texture = Minecraft.getInstance().getSkinManager().getInsecureSkinLocation(profile);
        boolean slim = "slim".equals(DefaultPlayerSkin.getSkinModelName(profile.getId()));
        return new PlayerSkinInfo(texture, slim);*/
        //?}
    }

    /** The generic Steve/Alex default texture (no UUID context). */
    public static ResourceLocation defaultTexture() {
        //? if >=1.21.1 {
        return DefaultPlayerSkin.getDefaultTexture();
        //?} else {
        /*return DefaultPlayerSkin.getDefaultSkin();*/
        //?}
    }

    /** The default texture for a specific UUID (its deterministic Steve/Alex). */
    public static ResourceLocation defaultTextureFor(UUID id) {
        //? if >=1.21.1 {
        return DefaultPlayerSkin.get(id).texture();
        //?} else {
        /*return DefaultPlayerSkin.getDefaultSkin(id);*/
        //?}
    }

    /** The texture a tab-list {@link PlayerInfo} resolves to, or {@code null}. */
    public static ResourceLocation playerInfoTexture(PlayerInfo info) {
        //? if >=1.21.1 {
        return info.getSkin().texture();
        //?} else {
        /*return info.getSkinLocation();*/
        //?}
    }
}
