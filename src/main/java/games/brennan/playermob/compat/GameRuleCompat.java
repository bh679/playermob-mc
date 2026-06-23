package games.brennan.playermob.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Version-bridging accessors for the two boolean game rules PlayerMob reads —
 * {@code mobGriefing} (block-breaking / crop-harvest / container-raid gates) and
 * {@code showDeathMessages} (the reincarnation death broadcast).
 *
 * <p>The game-rule API was restructured in MC 26.x:</p>
 * <ul>
 *   <li>{@code net.minecraft.world.level.GameRules} moved to
 *       {@code net.minecraft.world.level.gamerules.GameRules};</li>
 *   <li>the rule keys were renamed ({@code RULE_MOBGRIEFING} → {@code MOB_GRIEFING},
 *       {@code RULE_SHOWDEATHMESSAGES} → {@code SHOW_DEATH_MESSAGES}) and are now typed
 *       {@code GameRule<Boolean>} rather than {@code GameRules.Key<BooleanValue>};</li>
 *   <li>the read accessor changed from {@code getBoolean(key)} to the generic
 *       {@code get(rule)};</li>
 *   <li>{@code getGameRules()} now lives only on {@link ServerLevel} (it was on
 *       {@code Level} before), so the level must be narrowed first.</li>
 * </ul>
 *
 * <p>On 1.20.1 / 1.21.1 the original {@code level.getGameRules().getBoolean(RULE_*)}
 * call is byte-identical to what shipped; the {@code >=26} branch narrows to
 * {@link ServerLevel} and reads via {@code get(...)}. A non-server {@link Level}
 * (client) reports the rule's default, matching how the pre-26 call behaved on the
 * integrated-server side that actually runs this mob logic.</p>
 */
public final class GameRuleCompat {

    private GameRuleCompat() {}

    /** Whether the {@code mobGriefing} rule is on for {@code level}. */
    public static boolean mobGriefing(Level level) {
        //? if >=26 {
        /*return level instanceof ServerLevel server
            && server.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.MOB_GRIEFING);
        *///?} else {
        return level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING);
        //?}
    }

    /** Whether the {@code showDeathMessages} rule is on for {@code level}. */
    public static boolean showDeathMessages(ServerLevel level) {
        //? if >=26 {
        /*return level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.SHOW_DEATH_MESSAGES);
        *///?} else {
        return level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_SHOWDEATHMESSAGES);
        //?}
    }
}
