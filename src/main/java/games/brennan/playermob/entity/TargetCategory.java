package games.brennan.playermob.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;

/**
 * The groups of entities a {@link PlayerMobEntity} reacts to. The mob's numeric
 * disposition ({@link DispositionResolver}) branches on the category:
 * players/PlayerMobs carry per-individual {@link FeelingLedger} feelings; hostile
 * mobs are driven by the fight/flight trait; animals and villagers are left alone
 * unless the mob is friendly.
 *
 * <p>{@link #classify(LivingEntity)} maps a live entity to its category (or
 * {@code null} when it belongs to none — including creative/spectator players,
 * which every behaviour path then ignores).</p>
 */
public enum TargetCategory {

    /** Human players — and other PlayerMobs, which are player-shaped. */
    PLAYERS,

    /** Vanilla hostile mobs ({@link Enemy} — zombies, skeletons, creepers, …). */
    HOSTILE_MOBS,

    /** Passive farm/wild animals ({@link Animal}). */
    ANIMALS,

    /** Villagers + wandering traders ({@link AbstractVillager}). */
    VILLAGERS;

    /**
     * Classify a candidate entity into a category, or {@code null} if it belongs
     * to none. Other {@link PlayerMobEntity}s classify as {@link #PLAYERS} —
     * they're player-shaped, so the mob treats its own kind like players (self is
     * excluded by the goal/target scans, not here). Real players in Creative or
     * Spectator mode return {@code null}, so the mob holds no disposition toward
     * them and every path (attack, flee, greet, watch) ignores them — mirroring
     * vanilla's {@code EntitySelector.NO_CREATIVE_OR_SPECTATOR}. Order matters:
     * the PlayerMob check is first (it's also an {@code Enemy}); villagers before
     * animals; the hostile check last so a future hostile animal still classifies
     * as an animal.
     */
    public static TargetCategory classify(LivingEntity entity) {
        if (entity instanceof PlayerMobEntity) return PLAYERS; // own kind — never creative/spectator
        if (entity instanceof Player player) {
            // Players in Creative or Spectator are treated as not present.
            if (player.isCreative() || player.isSpectator()) return null;
            return PLAYERS;
        }
        if (entity instanceof AbstractVillager) return VILLAGERS;
        if (entity instanceof Animal) return ANIMALS;
        if (entity instanceof Enemy) return HOSTILE_MOBS;
        return null;
    }
}
