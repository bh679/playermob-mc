package games.brennan.playermob.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import games.brennan.playermob.compat.RegistryCompat;
import games.brennan.playermob.skin.LocalSkinFolder;
import games.brennan.playermob.skin.LocalSkinRef;
import games.brennan.playermob.skin.SkinModelDetector;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.slf4j.Logger;
//? if >=26 {
/*import net.minecraft.resources.Identifier;
*///?} else {
import net.minecraft.resources.ResourceLocation;
//?}

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side loader for local-folder skins — the PNGs a player drops in {@code config/playermob/skins/}
 * (see {@link LocalSkinFolder}). Reads a file off disk once, registers it as a {@link DynamicTexture}
 * the renderer can draw, and auto-detects its arm model from the pixels (via {@link SkinModelDetector},
 * over a {@link BufferedImage} so the heuristic is version-stable and unit-tested independently).
 *
 * <p>Mirrors {@link PlayerMobSkinTextures}: results are cached by skin name, so repeated render frames
 * are a map lookup. A missing or unreadable file caches a {@linkplain Holder#failed failure} sentinel —
 * the renderer then falls back to a bundled skin, and we don't re-touch the disk every frame. This is
 * the client-local model: on a dedicated server a client that lacks the file simply renders the
 * fallback (no skin bytes are sent over the network in this version).</p>
 *
 * <p>{@link Environment} {@code CLIENT} only — {@code NativeImage}/{@code TextureManager} are client
 * classes; the entity carries only the {@link LocalSkinRef} string, synced server→client.</p>
 */
@Environment(EnvType.CLIENT)
public final class LocalSkinTextures {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A resolved local skin: its registered texture id and detected arm model (or a failure sentinel). */
    //? if >=26 {
    /*private record Holder(Identifier id, boolean slim, boolean failed) {
        static Holder miss() { return new Holder(null, false, true); }
        static Holder ok(Identifier id, boolean slim) { return new Holder(id, slim, false); }
    }
    private static final ConcurrentHashMap<String, Holder> CACHE = new ConcurrentHashMap<>();
    *///?} else {
    private record Holder(ResourceLocation id, boolean slim, boolean failed) {
        static Holder miss() { return new Holder(null, false, true); }
        static Holder ok(ResourceLocation id, boolean slim) { return new Holder(id, slim, false); }
    }
    private static final ConcurrentHashMap<String, Holder> CACHE = new ConcurrentHashMap<>();
    //?}

    private LocalSkinTextures() {}

    /**
     * Resolve a local skin name to its registered texture id, loading and registering it on first use.
     * Returns {@code null} when the name is unsafe, the file is missing, or it can't be read — the
     * renderer then falls back to the bundled skin.
     */
    //? if >=26 {
    /*public static Identifier lookup(String name) {
    *///?} else {
    public static ResourceLocation lookup(String name) {
    //?}
        Holder holder = load(name);
        return holder.failed() ? null : holder.id();
    }

    /** Whether the named local skin is slim-armed (false when unknown/unreadable). */
    public static boolean isSlim(String name) {
        return load(name).slim();
    }

    /** Load-or-cache the holder for {@code name}. Detection + registration happen once per name. */
    private static Holder load(String name) {
        String safe = LocalSkinRef.sanitize(name);
        if (safe == null) {
            return Holder.miss();
        }
        return CACHE.computeIfAbsent(safe, LocalSkinTextures::read);
    }

    private static Holder read(String name) {
        Path path = LocalSkinFolder.resolve(name);
        if (path == null) {
            return Holder.miss();
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            boolean slim = detectSlim(bytes);
            return register(name, bytes, slim);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[playermob] failed to load local skin '{}' ({})", name, path, e);
            return Holder.miss();
        }
    }

    /** Detect the arm model from the PNG bytes via a {@link BufferedImage} (version-stable ARGB pixels). */
    private static boolean detectSlim(byte[] bytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) {
            return false;
        }
        return SkinModelDetector.isSlim(img.getWidth(), img.getHeight(), img::getRGB);
    }

    /** Register the PNG as a DynamicTexture under a derived id and return the resolved holder. */
    private static Holder register(String name, byte[] bytes, boolean slim) throws IOException {
        NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
        //? if >=26 {
        /*Identifier id = RegistryCompat.id("playermob", texturePath(name));
        DynamicTexture texture = new DynamicTexture(() -> "playermob/local/" + name, image);
        *///?} else {
        ResourceLocation id = RegistryCompat.id("playermob", texturePath(name));
        DynamicTexture texture = new DynamicTexture(image);
        //?}
        Minecraft.getInstance().getTextureManager().register(id, texture);
        return Holder.ok(id, slim);
    }

    /**
     * Map a skin name to a valid resource-location path ({@code local/<safe>_<hash>}). Resource paths
     * allow only {@code [a-z0-9/._-]}, so non-conforming characters are lowercased/replaced and a hash
     * of the original name is appended to keep two differently-cased/spelled names distinct.
     */
    private static String texturePath(String name) {
        StringBuilder sb = new StringBuilder("local/");
        String lower = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-'
                ? c : '_');
        }
        sb.append('_').append(Integer.toHexString(name.hashCode() & 0x7fffffff));
        return sb.toString();
    }
}
