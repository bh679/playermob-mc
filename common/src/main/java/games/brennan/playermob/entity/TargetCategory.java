package games.brennan.playermob.entity;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * The groups of entities a {@link PlayerMobEntity} can hold a distinct
 * {@link Personality} toward. Each category owns:
 * <ul>
 *   <li>the <b>allow-matrix</b> — which personalities are valid for it
 *       ({@link #allowed()} / {@link #allows(Personality)}); some combinations
 *       are nonsensical (you can't be "skeptical of a cow") and are excluded;</li>
 *   <li>a <b>default</b> personality ({@link #defaultPersonality()}) used for
 *       legacy saves, bare {@code /summon}, and as the clamp target for a
 *       disallowed value — chosen so a default mob reproduces v1 behaviour
 *       (fights hostile mobs, ignores everything else);</li>
 *   <li>a uniform <b>random</b> pick over the allowed set
 *       ({@link #randomAllowed(RandomSource)}), used at spawn.</li>
 * </ul>
 *
 * <p>{@link #classify(LivingEntity)} maps a live entity to its category (or
 * {@code null} when it belongs to none — including other PlayerMobs, so the
 * mob ignores its own kind).</p>
 */
public enum TargetCategory {

    /** Human players. All five personalities are valid. */
    PLAYERS(List.of(Personality.AGGRESSIVE, Personality.FRIENDLY, Personality.PASSIVE,
                    Personality.SKEPTICAL, Personality.SHY),
            Personality.PASSIVE),

    /**
     * Vanilla hostile mobs ({@link Enemy} — zombies, skeletons, creepers, …).
     * No Friendly (you don't gift a creeper). Defaults to {@link Personality#AGGRESSIVE}
     * — this reproduces the v1 single-stance behaviour with no migration code.
     */
    HOSTILE_MOBS(List.of(Personality.AGGRESSIVE, Personality.PASSIVE,
                         Personality.SKEPTICAL, Personality.SHY),
                 Personality.AGGRESSIVE),

    /** Passive farm/wild animals ({@link Animal}). No Skeptical/Shy. */
    ANIMALS(List.of(Personality.AGGRESSIVE, Personality.FRIENDLY, Personality.PASSIVE),
            Personality.PASSIVE),

    /** Villagers + wandering traders ({@link AbstractVillager}). No Skeptical/Shy. */
    VILLAGERS(List.of(Personality.AGGRESSIVE, Personality.FRIENDLY, Personality.PASSIVE),
              Personality.PASSIVE);

    private final List<Personality> allowed;
    private final Personality defaultPersonality;

    TargetCategory(List<Personality> allowed, Personality defaultPersonality) {
        this.allowed = allowed; // List.of(...) is already immutable
        this.defaultPersonality = defaultPersonality;
    }

    /** Immutable list of personalities valid for this category (never empty). */
    public List<Personality> allowed() {
        return allowed;
    }

    public boolean allows(Personality personality) {
        return allowed.contains(personality);
    }

    /** Safe fallback personality; always a member of {@link #allowed()}. */
    public Personality defaultPersonality() {
        return defaultPersonality;
    }

    /** Uniform random pick from {@link #allowed()}. Used at spawn. */
    public Personality randomAllowed(RandomSource random) {
        return allowed.get(random.nextInt(allowed.size()));
    }

    /**
     * Classify a candidate entity into a category, or {@code null} if it belongs
     * to none. Other {@link PlayerMobEntity}s classify as {@link #PLAYERS} —
     * they're player-shaped, so the mob treats its own kind like players (self
     * is excluded by the goal/target scans, not here). Real players in Creative
     * or Spectator mode return {@code null}, so the mob holds no disposition
     * toward them and every path (attack, flee, greet, watch, provoke) ignores
     * them — mirroring vanilla's {@code EntitySelector.NO_CREATIVE_OR_SPECTATOR}.
     * Order matters: the PlayerMob check is first (it's also an {@code Enemy});
     * villagers before animals; the hostile check last so a future hostile
     * animal still classifies as an animal.
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
