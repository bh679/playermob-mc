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
 * <p>Datapack authors can ship additional skin entries by dropping JSON files
 * matching {@link PlayerMobSkin#CODEC} into their datapack's
 * {@code data/<modid-or-pack-id>/playermob_skins/} directory.</p>
 *
 * <p>MC 26.x reworked {@link SimpleJsonResourceReloadListener} from a raw-Gson base
 * ({@code apply(Map<ResourceLocation, JsonElement>)} — the subclass parses each element
 * itself) into a codec-typed base ({@code SimpleJsonResourceReloadListener<T>}, constructed
 * with the {@code Codec<T>} so {@code apply(Map<Identifier, T>)} hands back already-decoded
 * objects). The {@code >=26} branch wires {@link PlayerMobSkin#CODEC} into the base and just
 * collects the decoded values; pre-26 keeps the hand-parse loop.</p>
 */
//? if >=26 {
/*public class PlayerMobSkinReloadListener extends SimpleJsonResourceReloadListener<PlayerMobSkin> {
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
        /*super(PlayerMobSkin.CODEC, FileToIdConverter.json(FOLDER));
        *///?} else {
        super(GSON, FOLDER);
        //?}
    }

    //? if >=26 {
    /*@Override
    protected void apply(Map<Identifier, PlayerMobSkin> resources,
                         ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        // The base already decoded each entry through PlayerMobSkin.CODEC and dropped any
        // that failed, so the surviving values are the valid skins.
        List<PlayerMobSkin> parsed = new ArrayList<>(resources.values());
        PlayerMobSkinRegistry.replaceAll(parsed);
        LOGGER.info("[playermob] loaded {} skin(s)", parsed.size());
    }
    *///?} else {
    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources,
                         ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        List<PlayerMobSkin> parsed = new ArrayList<>(resources.size());
        int rejected = 0;
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation id = entry.getKey();
            var result = PlayerMobSkin.CODEC.parse(JsonOps.INSTANCE, entry.getValue());
            if (result.result().isPresent()) {
                parsed.add(result.result().get());
            } else {
                rejected++;
                LOGGER.warn("[playermob] skipping invalid skin {}: {}", id,
                    result.error().map(err -> err.message()).orElse("unknown error"));
            }
        }
        PlayerMobSkinRegistry.replaceAll(parsed);
        LOGGER.info("[playermob] loaded {} skin(s) ({} rejected)", parsed.size(), rejected);
    }
    //?}
}
