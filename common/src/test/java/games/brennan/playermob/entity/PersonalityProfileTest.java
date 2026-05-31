package games.brennan.playermob.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link PersonalityProfile}: defaults, clamping,
 * spawn-roll explicit-set semantics, provocation flips, and NBT round-trip.
 * Uses only {@link CompoundTag} and {@link RandomSource} (no registry
 * bootstrap) and never calls {@code personalityToward}/{@code classify}
 * (which need live entities — covered at Gate 2).
 */
class PersonalityProfileTest {

    @Test
    void constructorAppliesCategoryDefaults() {
        PersonalityProfile p = new PersonalityProfile();
        assertEquals(Personality.AGGRESSIVE, p.get(TargetCategory.HOSTILE_MOBS));
        assertEquals(Personality.PASSIVE, p.get(TargetCategory.PLAYERS));
        assertEquals(Personality.PASSIVE, p.get(TargetCategory.ANIMALS));
        assertEquals(Personality.PASSIVE, p.get(TargetCategory.VILLAGERS));
    }

    @Test
    void setClampsDisallowedToCategoryDefault() {
        PersonalityProfile p = new PersonalityProfile();
        // Shy is disallowed toward animals → clamps to ANIMALS default (Passive).
        p.set(TargetCategory.ANIMALS, Personality.SHY);
        assertEquals(Personality.PASSIVE, p.get(TargetCategory.ANIMALS));
        // Friendly is disallowed toward hostiles → clamps to Aggressive.
        p.set(TargetCategory.HOSTILE_MOBS, Personality.FRIENDLY);
        assertEquals(Personality.AGGRESSIVE, p.get(TargetCategory.HOSTILE_MOBS));
        // An allowed value sticks.
        p.set(TargetCategory.PLAYERS, Personality.SKEPTICAL);
        assertEquals(Personality.SKEPTICAL, p.get(TargetCategory.PLAYERS));
    }

    @Test
    void rollUnsetRandomLeavesExplicitCategoriesUntouched() {
        PersonalityProfile p = new PersonalityProfile();
        p.set(TargetCategory.PLAYERS, Personality.FRIENDLY); // explicit (like a player-facing egg)
        p.rollUnsetRandom(RandomSource.create(42L));

        // PLAYERS preserved; others rolled within their allowed sets.
        assertEquals(Personality.FRIENDLY, p.get(TargetCategory.PLAYERS));
        assertTrue(TargetCategory.HOSTILE_MOBS.allows(p.get(TargetCategory.HOSTILE_MOBS)));
        assertTrue(TargetCategory.ANIMALS.allows(p.get(TargetCategory.ANIMALS)));
        assertTrue(TargetCategory.VILLAGERS.allows(p.get(TargetCategory.VILLAGERS)));
    }

    @Test
    void provokeArmedTurnsAggressiveUnarmedTurnsShy() {
        PersonalityProfile p = new PersonalityProfile();
        p.set(TargetCategory.PLAYERS, Personality.PASSIVE);
        assertEquals(Personality.AGGRESSIVE, p.provoke(TargetCategory.PLAYERS, /*armed*/ true));

        PersonalityProfile q = new PersonalityProfile();
        q.set(TargetCategory.PLAYERS, Personality.PASSIVE);
        assertEquals(Personality.SHY, q.provoke(TargetCategory.PLAYERS, /*armed*/ false));
    }

    @Test
    void provokeUnarmedClampsToAggressiveWhereShyDisallowed() {
        PersonalityProfile p = new PersonalityProfile();
        p.set(TargetCategory.ANIMALS, Personality.FRIENDLY);
        // Unarmed would be Shy, but animals disallow Shy → Aggressive.
        assertEquals(Personality.AGGRESSIVE, p.provoke(TargetCategory.ANIMALS, /*armed*/ false));
    }

    @Test
    void provokeIsNoOpWhenAlreadyReacting() {
        PersonalityProfile p = new PersonalityProfile();
        p.set(TargetCategory.PLAYERS, Personality.AGGRESSIVE);
        assertEquals(Personality.AGGRESSIVE, p.provoke(TargetCategory.PLAYERS, false));
        p.set(TargetCategory.HOSTILE_MOBS, Personality.SHY);
        assertEquals(Personality.SHY, p.provoke(TargetCategory.HOSTILE_MOBS, true));
    }

    @Test
    void saveLoadRoundTripsAndMarksLoadedExplicit() {
        PersonalityProfile original = new PersonalityProfile();
        original.set(TargetCategory.PLAYERS, Personality.SKEPTICAL);
        original.set(TargetCategory.HOSTILE_MOBS, Personality.SHY);
        original.set(TargetCategory.ANIMALS, Personality.FRIENDLY);
        original.set(TargetCategory.VILLAGERS, Personality.AGGRESSIVE);

        CompoundTag tag = new CompoundTag();
        original.save(tag);

        PersonalityProfile loaded = new PersonalityProfile();
        loaded.load(tag);
        assertEquals(Personality.SKEPTICAL, loaded.get(TargetCategory.PLAYERS));
        assertEquals(Personality.SHY, loaded.get(TargetCategory.HOSTILE_MOBS));
        assertEquals(Personality.FRIENDLY, loaded.get(TargetCategory.ANIMALS));
        assertEquals(Personality.AGGRESSIVE, loaded.get(TargetCategory.VILLAGERS));

        // Loaded categories are explicit → a subsequent spawn-roll must not change them.
        loaded.rollUnsetRandom(RandomSource.create(7L));
        assertEquals(Personality.SKEPTICAL, loaded.get(TargetCategory.PLAYERS));
        assertEquals(Personality.SHY, loaded.get(TargetCategory.HOSTILE_MOBS));
        assertEquals(Personality.FRIENDLY, loaded.get(TargetCategory.ANIMALS));
        assertEquals(Personality.AGGRESSIVE, loaded.get(TargetCategory.VILLAGERS));
    }

    @Test
    void legacySaveWithNoTagsKeepsV1Defaults() {
        // A v1 mob's NBT has none of the personality keys → defaults persist.
        PersonalityProfile loaded = new PersonalityProfile();
        loaded.load(new CompoundTag());
        assertEquals(Personality.AGGRESSIVE, loaded.get(TargetCategory.HOSTILE_MOBS));
        assertEquals(Personality.PASSIVE, loaded.get(TargetCategory.PLAYERS));
    }
}
