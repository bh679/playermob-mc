package games.brennan.playermob.compat;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.skin.PlayerMobSkin;
import games.brennan.playermob.skin.PlayerMobSkinRegistry;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The seam an integrating mod uses to add skins to PlayerMob's spawn pool programmatically — the
 * skin analogue of {@link ReincarnationSources}. Register a {@link SkinSource} from your loader
 * entry point (guarded by {@code ModList.isLoaded("playermob")} / Fabric's equivalent):
 *
 * <pre>{@code
 * SkinSources.register(() -> List.of(
 *     new PlayerMobSkin("MyHero", "http://textures.minecraft.net/texture/abc...", SkinModel.WIDE)));
 * }</pre>
 *
 * <p>Registered sources' skins are pushed into {@link PlayerMobSkinRegistry}, which unions them with
 * the datapack pool (de-duplicated by texture URL). If a source's contents change after
 * registration, call {@link #refresh()} to re-pull and re-publish. A source that throws is logged
 * and skipped — it never breaks the spawn pool.</p>
 *
 * <p>With no source registered the pool is exactly the datapack pool, i.e. the prior behaviour.</p>
 */
public final class SkinSources {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile List<SkinSource> sources = List.of();

    private SkinSources() {}

    /** Add a skin source and immediately publish the combined external pool to the registry. */
    public static synchronized void register(SkinSource source) {
        if (source == null) {
            return;
        }
        List<SkinSource> next = new ArrayList<>(sources);
        next.add(source);
        sources = List.copyOf(next);
        PlayerMobSkinRegistry.setExternalSkins(collect());
    }

    /** Re-pull every registered source and re-publish — call when a source's skins have changed. */
    public static void refresh() {
        PlayerMobSkinRegistry.setExternalSkins(collect());
    }

    /** Test seam: drop all registered sources and clear the registry's external pool. */
    static synchronized void resetForTest() {
        sources = List.of();
        PlayerMobSkinRegistry.setExternalSkins(List.of());
    }

    /** Flatten every source's current skins, skipping (and logging) any that throw. */
    static List<PlayerMobSkin> collect() {
        List<PlayerMobSkin> out = new ArrayList<>();
        for (SkinSource source : sources) {
            try {
                List<PlayerMobSkin> supplied = source.skins();
                if (supplied != null) {
                    out.addAll(supplied);
                }
            } catch (RuntimeException e) {
                LOGGER.error("[playermob] skin source {} failed to supply skins",
                    source.getClass().getName(), e);
            }
        }
        return out;
    }
}
