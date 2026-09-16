package games.brennan.playermob.client;

import games.brennan.playermob.compat.SkinCompat;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.PlayerInfo;
//? if >=26 {
/*import net.minecraft.resources.Identifier;
*///?} else {
import net.minecraft.resources.ResourceLocation;
//?}
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side identity lookups for the relationship rows of {@link PlayerMobScreen}: who a
 * ledger UUID is (tab-list player or loaded PlayerMob), what face to draw for them, what they
 * feel back, and name trimming. Split out of the screen to keep it under the file-size cap.
 * Names are stable for a session, so they're cached per instance; faces are re-resolved each
 * frame so async-loading player skins flip in once cached.
 */
@Environment(EnvType.CLIENT)
final class RelationIdentity {

    private final Map<UUID, String> nameCache = new HashMap<>();

    /** The display name for {@code id}, resolved once and cached. */
    String name(UUID id) {
        return nameCache.computeIfAbsent(id, RelationIdentity::computeName);
    }

    private static String computeName(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(id);
            if (info != null) {
                //? if >=26 {
                /*return info.getProfile().name(); // authlib 9: GameProfile.getName() → name()
                *///?} else {
                return info.getProfile().getName();
                //?}
            }
        }
        Entity e = loadedEntity(id);
        return e != null ? e.getName().getString() : id.toString().substring(0, 8);
    }

    /** True if {@code id} is a connected player (tab list) rather than a PlayerMob. */
    static boolean isPlayer(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        return mc.getConnection() != null && mc.getConnection().getPlayerInfo(id) != null;
    }

    /**
     * Resolve a face texture for {@code id}: a tab-list player's skin, else a loaded
     * PlayerMob's skin, else a generic Steve/Alex default.
     */
    //? if >=26 {
    /*static Identifier faceTexture(UUID id) {
    *///?} else {
    static ResourceLocation faceTexture(UUID id) {
    //?}
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(id);
            if (info != null) {
                // var: SkinCompat.playerInfoTexture returns Identifier on 26, ResourceLocation pre-26.
                var texture = SkinCompat.playerInfoTexture(info);
                if (texture != null) {
                    return texture;
                }
            }
        }
        if (loadedEntity(id) instanceof PlayerMobEntity pm) {
            return PlayerMobRenderer.resolveSkin(pm);
        }
        return SkinCompat.defaultTextureFor(id);
    }

    /**
     * The feeling {@code other} holds back toward {@code mob}, read from {@code other}'s own
     * synced ledger if it is a loaded PlayerMob; null for a player, an unloaded mob, or one that
     * hasn't met {@code mob}.
     */
    static Float backFeeling(PlayerMobEntity mob, UUID other) {
        return loadedEntity(other) instanceof PlayerMobEntity pm
            ? pm.getSyncedFeelings().get(mob.getUUID()) : null;
    }

    /** The client-loaded entity with {@code id}, or null. */
    private static Entity loadedEntity(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getUUID().equals(id)) {
                return e;
            }
        }
        return null;
    }

    /** Truncate {@code name} with an ellipsis so it fits within {@code maxWidth} px of {@code font}. */
    static String trimTo(Font font, String name, int maxWidth) {
        if (font.width(name) <= maxWidth) {
            return name;
        }
        String ellipsis = "…";
        String trimmed = name;
        while (!trimmed.isEmpty() && font.width(trimmed + ellipsis) > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + ellipsis;
    }
}
