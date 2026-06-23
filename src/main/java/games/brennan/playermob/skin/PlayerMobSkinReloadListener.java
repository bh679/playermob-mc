package games.brennan.playermob.skin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
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
 * {@code data/<modid-or-pack-id>/playermob_skins/} directory. The {@code id}
 * of each entry is derived from the file path (vanilla
 * {@link SimpleJsonResourceReloadListener} convention) and is currently used
 * only for log lines — no lookup-by-id today.</p>
 */
public class PlayerMobSkinReloadListener extends SimpleJsonResourceReloadListener {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Folder under {@code data/&lt;namespace&gt;/} the listener scans. */
    private static final String FOLDER = "playermob_skins";

    /** Vanilla Gson instance is fine — we go straight through the codec from there. */
    private static final Gson GSON = new Gson();

    public PlayerMobSkinReloadListener() {
        super(GSON, FOLDER);
    }

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
}
