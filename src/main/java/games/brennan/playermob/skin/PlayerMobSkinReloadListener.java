package games.brennan.playermob.skin;

//? if >=26 {
/*import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
*///?} else {
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
//?}
import com.mojang.logging.LogUtils;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code data/&lt;namespace&gt;/playermob_skins/*.json} files into
 * {@link PlayerMobSkinRegistry}. Vanilla pattern, mirrors how loot tables,
 * advancements, and recipes are loaded.
 *
 * <p>Each file is a {@link PlayerMobSkinEntry}. Entries that ship a {@code textureUrl} are usable
 * immediately; entries that instead name a player ({@code playerName}) are handed to the registry as
 * pending specs and resolved asynchronously through {@link PlayerSkinResolver} (see
 * {@link PlayerMobSkinRegistry#ensureResolved}).</p>
 *
 * <p>MC 26.x reworked {@link SimpleJsonResourceReloadListener} from a raw-Gson base
 * ({@code apply(Map<ResourceLocation, JsonElement>)} — the subclass parses each element
 * itself) into a codec-typed base ({@code SimpleJsonResourceReloadListener<T>}, constructed
 * with the {@code Codec<T>} so {@code apply(Map<Identifier, T>)} hands back already-decoded
 * objects). The {@code >=26} branch wires {@link PlayerMobSkinEntry#CODEC} into the base and just
 * collects the decoded values; pre-26 keeps the hand-parse loop.</p>
 */
//? if >=26 {
/*public class PlayerMobSkinReloadListener extends SimpleJsonResourceReloadListener<PlayerMobSkinEntry> {
*///?} else {
public class PlayerMobSkinReloadListener extends SimpleJsonResourceReloadListener {
//?}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Folder under {@code data/&lt;namespace&gt;/} the listener scans. */
    private static final String FOLDER = "playermob_skins";

    //? if <26 {
    /** Vanilla Gson instance is fine — we go straight through the codec from there. */
    private static final Gson GSON = new Gson();
    //?}

    public PlayerMobSkinReloadListener() {
        //? if >=26 {
        /*super(PlayerMobSkinEntry.CODEC, FileToIdConverter.json(FOLDER));
        *///?} else {
        super(GSON, FOLDER);
        //?}
    }

    //? if >=26 {
    /*@Override
    protected void apply(Map<Identifier, PlayerMobSkinEntry> resources,
                         ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        // The base already decoded each entry through PlayerMobSkinEntry.CODEC and dropped any
        // that failed, so the surviving values are the valid entries.
        publish(new ArrayList<>(resources.values()));
    }
    *///?} else {
    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources,
                         ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        List<PlayerMobSkinEntry> parsed = new ArrayList<>(resources.size());
        int rejected = 0;
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation id = entry.getKey();
            var result = PlayerMobSkinEntry.CODEC.parse(JsonOps.INSTANCE, entry.getValue());
            if (result.result().isPresent()) {
                parsed.add(result.result().get());
            } else {
                rejected++;
                LOGGER.warn("[playermob] skipping invalid skin {}: {}", id,
                    result.error().map(err -> err.message()).orElse("unknown error"));
            }
        }
        publish(parsed);
        if (rejected > 0) {
            LOGGER.warn("[playermob] {} skin entr(y/ies) rejected", rejected);
        }
    }
    //?}

    /**
     * Split decoded entries into ready URL skins and pending name specs, then swap them into the
     * registry. Resolution of the name specs is kicked off lazily (server-side, off-thread) by the
     * registry — this method never blocks.
     */
    private static void publish(List<PlayerMobSkinEntry> entries) {
        List<PlayerMobSkin> urlSkins = new ArrayList<>();
        List<PlayerMobSkinEntry> nameSpecs = new ArrayList<>();
        for (PlayerMobSkinEntry entry : entries) {
            if (entry.hasUrl()) {
                urlSkins.add(entry.toUrlSkin());
            } else {
                nameSpecs.add(entry);
            }
        }
        PlayerMobSkinRegistry.replaceAll(urlSkins, nameSpecs);
        LOGGER.info("[playermob] loaded {} skin URL(s), {} name(s) pending resolution",
            urlSkins.size(), nameSpecs.size());
    }
}
