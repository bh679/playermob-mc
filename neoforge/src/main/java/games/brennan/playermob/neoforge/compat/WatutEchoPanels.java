package games.brennan.playermob.neoforge.compat;

//? if >=26 {
/*// What Are They Up To (watut) is wired as a compile-only dependency on the NeoForge 1.21.1 node only
// (see neoforge/build.gradle.kts). On >=26 this is a never-called stub: PlayerMobNeoForgeClient
// installs the real hook only on <26. The class must still compile in the source set.
// Line comments only inside this >=26 block (no nested block comments).
public final class WatutEchoPanels {
    private WatutEchoPanels() {}
    public static void install() {}
}
*///?} else {
import com.corosus.watut.ParticleRegistry;
import com.corosus.watut.PlayerStatusManagerClient;
import com.corosus.watut.SpriteInfo;
import com.corosus.watut.particle.ParticleStatic;
import com.mojang.logging.LogUtils;
import games.brennan.playermob.client.EchoPanelPlacement;
import games.brennan.playermob.entity.MobActivity;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Optional What Are They Up To (WATUT) integration: a PlayerMob that is reading or writing a book
 * shows <em>WATUT's own</em> book panel floating in front of it — the same panel WATUT shows over a
 * player with a book &amp; quill open — instead of a panel of PlayerMob's.
 *
 * <p>WATUT drives its panels from player statuses keyed by tab-list UUID, so a mob can't be given a
 * status. Instead, each client tick this keeps one of WATUT's {@link ParticleStatic} panels per
 * reading/writing echo in WATUT's own particle engine — WATUT's {@code book} sprite, size and
 * placement ({@link EchoPanelPlacement}), turned to the echo's body — and drops it when the echo
 * stops. WATUT has no reading state, so the book panel serves reading and writing alike.</p>
 *
 * <p>Imports WATUT symbols, so it is only classloaded from behind {@code ModList.isLoaded("watut")}.
 * WATUT documents no API for this; if a WATUT update changes these classes, the first failure
 * disables the hook (logged once) rather than crashing the game.</p>
 */
public final class WatutEchoPanels {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** WATUT's own panel size for its book and sign panels. */
    private static final float PANEL_SIZE = 0.7F;
    /** WATUT's own backward tilt for a screen panel, in degrees. */
    private static final float PANEL_PITCH = 20.0F;

    /** Live panel per echo, keyed by entity id. */
    private static final Map<Integer, ParticleStatic> PANELS = new HashMap<>();
    private static boolean disabled;

    private WatutEchoPanels() {}

    /** Start the per-tick hook. Called once at client setup, only when {@code watut} is present. */
    public static void install() {
        NeoForge.EVENT_BUS.addListener(WatutEchoPanels::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (disabled) return;
        try {
            tick(Minecraft.getInstance().level);
        } catch (LinkageError | RuntimeException e) {
            disabled = true;
            PANELS.clear();
            LOGGER.warn("[playermob] WATUT book panels disabled — this WATUT version doesn't match", e);
        }
    }

    private static void tick(ClientLevel level) {
        if (level == null) {
            removeAll();
            return;
        }
        Set<Integer> seen = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof PlayerMobEntity mob) || !showsBook(mob)) continue;
            seen.add(mob.getId());
            ParticleStatic panel = PANELS.get(mob.getId());
            if (panel == null || !panel.isAlive()) {
                panel = spawn(level, mob);
                if (panel == null) continue; // WATUT's sprites aren't ready yet
                PANELS.put(mob.getId(), panel);
            }
            follow(panel, mob);
        }
        Iterator<Map.Entry<Integer, ParticleStatic>> it = PANELS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ParticleStatic> e = it.next();
            if (!seen.contains(e.getKey())) {
                e.getValue().remove();
                it.remove();
            }
        }
    }

    private static boolean showsBook(PlayerMobEntity mob) {
        MobActivity activity = mob.getActivity();
        return mob.isAlive() && !mob.isInvisible()
            && (activity == MobActivity.READING || activity == MobActivity.WRITING);
    }

    private static ParticleStatic spawn(ClientLevel level, PlayerMobEntity mob) {
        SpriteInfo book = ParticleRegistry.book;
        TextureAtlasSprite sprite = book == null ? null : book.getSprite();
        if (sprite == null) return null;
        double[] p = EchoPanelPlacement.position(mob.getX(), mob.getY(), mob.getZ(), mob.getXRot(), mob.yBodyRot);
        ParticleStatic panel = new ParticleStatic(level, p[0], p[1], p[2], sprite, PANEL_SIZE);
        PlayerStatusManagerClient.getParticleEngine().add(panel);
        return panel;
    }

    /** Keep the panel alive, in front of the echo and turned with it — as WATUT does for players. */
    private static void follow(ParticleStatic panel, PlayerMobEntity mob) {
        double[] p = EchoPanelPlacement.position(mob.getX(), mob.getY(), mob.getZ(), mob.getXRot(), mob.yBodyRot);
        // Last tick's spot, from the echo's own previous position, so WATUT interpolates smoothly.
        double[] prev = EchoPanelPlacement.position(mob.xo, mob.yo, mob.zo, mob.xRotO, mob.yBodyRotO);
        panel.keepAlive();
        panel.setPos(p[0], p[1], p[2]);
        panel.setPosPrev(prev[0], prev[1], prev[2]);
        // WATUT's own convention for a player's screen panel: yaw is the body yaw negated, tilted
        // back 20 degrees (from its tickOtherPlayerClient).
        panel.prevRotationYaw = -mob.yBodyRotO;
        panel.rotationYaw = -mob.yBodyRot;
        panel.prevRotationPitch = PANEL_PITCH;
        panel.rotationPitch = PANEL_PITCH;
    }

    private static void removeAll() {
        for (ParticleStatic panel : PANELS.values()) {
            panel.remove();
        }
        PANELS.clear();
    }
}
//?}
