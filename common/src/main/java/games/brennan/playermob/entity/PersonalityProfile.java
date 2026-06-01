package games.brennan.playermob.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.EnumMap;
import java.util.EnumSet;

/**
 * Holds one {@link Personality} per {@link TargetCategory} for a single
 * {@link PlayerMobEntity}, plus the save/load, spawn-roll, and provocation
 * logic. Extracted from the entity so the entity stays lean and this slice of
 * behaviour is unit-testable without a live world.
 *
 * <p><b>Explicit-set tracking.</b> The profile remembers which categories were
 * set deliberately (via NBT load, the spawn-egg {@code entity_data} component,
 * or {@code /summon}). {@link #rollUnsetRandom(RandomSource)} — called from
 * {@code finalizeSpawn} — then randomises only the categories left at their
 * construction default. This is what makes the "player-facing" spawn eggs work:
 * the egg pins {@code PLAYERS} (marked explicit), and the roll fills the other
 * three. It's robust to whether {@code finalizeSpawn} runs before or after the
 * egg's NBT is merged (see {@link PlayerMobEntity#finalizeSpawn}).</p>
 *
 * <p>All categories start at {@link TargetCategory#defaultPersonality()}, which
 * reproduces v1 behaviour (aggressive to hostile mobs, passive to everything
 * else) — so legacy saves and bare {@code /summon} need no migration.</p>
 */
public final class PersonalityProfile {

    // NBT keys — one int (Personality ordinal) per category. The PLAYERS key
    // is public because it doubles as the spawn-egg entity_data key and the
    // documented {@code /summon ... {PlayerPersonality:N}} key (see PlayerMobRegistry).
    public static final String TAG_PLAYERS = "PlayerPersonality";
    static final String TAG_HOSTILE = "HostilePersonality";
    static final String TAG_ANIMALS = "AnimalPersonality";
    static final String TAG_VILLAGERS = "VillagerPersonality";

    private final EnumMap<TargetCategory, Personality> byCategory =
        new EnumMap<>(TargetCategory.class);

    /** Categories set deliberately (load / egg / summon) — never re-rolled at spawn. */
    private final EnumSet<TargetCategory> explicit = EnumSet.noneOf(TargetCategory.class);

    public PersonalityProfile() {
        for (TargetCategory category : TargetCategory.values()) {
            byCategory.put(category, category.defaultPersonality());
        }
    }

    /** The mob's personality toward this category. */
    public Personality get(TargetCategory category) {
        return byCategory.get(category);
    }

    /**
     * Set the personality toward a category and mark it explicit. A value not
     * in the category's allow-matrix is clamped to that category's default
     * (defensive against {@code /summon SkepticalPersonality-toward-a-cow}).
     */
    public void set(TargetCategory category, Personality personality) {
        if (category == null || personality == null) return;
        Personality clamped = category.allows(personality) ? personality : category.defaultPersonality();
        byCategory.put(category, clamped);
        explicit.add(category);
    }

    /** The mob's personality toward a live entity, or {@code null} if uncategorised. */
    public Personality personalityToward(LivingEntity entity) {
        TargetCategory category = TargetCategory.classify(entity);
        return category == null ? null : byCategory.get(category);
    }

    /**
     * Randomise every category NOT explicitly set, picking uniformly from each
     * category's allowed set. Called once from {@code finalizeSpawn}.
     */
    public void rollUnsetRandom(RandomSource random) {
        for (TargetCategory category : TargetCategory.values()) {
            if (!explicit.contains(category)) {
                byCategory.put(category, category.randomAllowed(random));
            }
        }
    }

    /**
     * React to being attacked by an entity of {@code category}: a Friendly,
     * Passive, or Skeptical disposition flips to {@link Personality#AGGRESSIVE}
     * if the mob is armed, else {@link Personality#SHY}. Shy is clamped back to
     * Aggressive for categories that disallow it (animals, villagers). An
     * already-Aggressive or already-Shy disposition is left unchanged.
     *
     * @return the resulting personality toward that category (or {@code null}
     *         if {@code category} is null)
     */
    public Personality provoke(TargetCategory category, boolean armed) {
        if (category == null) return null;
        Personality current = byCategory.get(category);
        if (current == Personality.AGGRESSIVE || current == Personality.SHY) {
            return current; // already reacting
        }
        Personality next = armed ? Personality.AGGRESSIVE : Personality.SHY;
        if (!category.allows(next)) {
            next = Personality.AGGRESSIVE; // Shy disallowed → stand and fight
        }
        byCategory.put(category, next);
        explicit.add(category);
        return next;
    }

    /** Write one int per category. */
    public void save(CompoundTag tag) {
        tag.putInt(TAG_PLAYERS, byCategory.get(TargetCategory.PLAYERS).ordinal());
        tag.putInt(TAG_HOSTILE, byCategory.get(TargetCategory.HOSTILE_MOBS).ordinal());
        tag.putInt(TAG_ANIMALS, byCategory.get(TargetCategory.ANIMALS).ordinal());
        tag.putInt(TAG_VILLAGERS, byCategory.get(TargetCategory.VILLAGERS).ordinal());
    }

    /**
     * Read whatever category tags are present (each marks that category
     * explicit and is clamped to the allow-matrix). Missing tags keep the
     * constructor default — so a legacy save with none of these tags loads as a
     * v1-behaviour mob.
     */
    public void load(CompoundTag tag) {
        loadOne(tag, TAG_PLAYERS, TargetCategory.PLAYERS);
        loadOne(tag, TAG_HOSTILE, TargetCategory.HOSTILE_MOBS);
        loadOne(tag, TAG_ANIMALS, TargetCategory.ANIMALS);
        loadOne(tag, TAG_VILLAGERS, TargetCategory.VILLAGERS);
    }

    private void loadOne(CompoundTag tag, String key, TargetCategory category) {
        if (tag.contains(key)) {
            set(category, Personality.byOrdinal(tag.getInt(key)));
        }
    }
}
