package games.brennan.playermob.entity;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.PlayerMobConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * Optional, off-by-default debug readout for Dungeon-Train PlayerMob auto-spawns. When
 * {@link PlayerMobConfig#debugSpawnLog()} is on, each {@code MobSpawnType.EVENT} spawn logs a line
 * and broadcasts a colour-coded chat message so spawn outcomes are visible while testing:
 * gray = a plain mob, yellow = it brought a random friend, purple = it's an echo, green = an echo
 * that also brought a friend-echo. Reuses the death-message broadcast pattern.
 */
final class DtSpawnDebug {

    private static final Logger LOGGER = LogUtils.getLogger();

    private DtSpawnDebug() {}

    /** The four DT-spawn outcomes, keyed by whether the mob is an echo and whether a companion spawned. */
    enum Kind {
        PLAIN(ChatFormatting.GRAY, "boarded the train"),
        FRIEND(ChatFormatting.YELLOW, "boarded the train with a friend"),
        ECHO(ChatFormatting.LIGHT_PURPLE, "boarded the train"),
        ECHO_FRIEND(ChatFormatting.GREEN, "boarded the train with a friend-echo");

        final ChatFormatting colour;
        final String suffix;

        Kind(ChatFormatting colour, String suffix) {
            this.colour = colour;
            this.suffix = suffix;
        }
    }

    /** Pure mapping of (isEcho, companion) to a {@link Kind} — unit-tested. */
    static Kind classify(boolean isEcho, boolean companion) {
        if (isEcho) {
            return companion ? Kind.ECHO_FRIEND : Kind.ECHO;
        }
        return companion ? Kind.FRIEND : Kind.PLAIN;
    }

    /**
     * If debug spawn logging is enabled, log + broadcast a colour-coded line describing this DT
     * auto-spawn. {@code mob} is the just-spawned PlayerMob; an echo carries its "Echo of X" name.
     */
    static void report(ServerLevel level, PlayerMobEntity mob, boolean isEcho, boolean companion) {
        if (!PlayerMobConfig.debugSpawnLog()) {
            return;
        }
        Kind kind = classify(isEcho, companion);
        String who = mob.getCustomName() != null
            ? mob.getCustomName().getString()
            : (isEcho ? "An echo" : "A PlayerMob");
        LOGGER.info("[{}] DT spawn — {} ({}) at {}", PlayerMob.MOD_ID, kind, who, mob.blockPosition());
        level.getServer().getPlayerList()
            .broadcastSystemMessage(Component.literal(who + " " + kind.suffix).withStyle(kind.colour), false);
    }
}
