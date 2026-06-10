package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static games.brennan.playermob.entity.DispositionResolver.innerRadius;
import static games.brennan.playermob.entity.DispositionResolver.onHurt;
import static games.brennan.playermob.entity.DispositionResolver.outerRadius;
import static games.brennan.playermob.entity.DispositionResolver.resolve;
import static games.brennan.playermob.entity.DispositionResolver.witnessedAttackDelta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link DispositionResolver} — the numeric decision
 * function. No Minecraft world needed (it operates on primitives + our own
 * enums), so it stays free of a registry bootstrap.
 */
class DispositionResolverTest {

    private static final double EPS = 1e-6;

    // ---- personal-space radii ----

    @Test
    void outerRadiiAtNeutralFeeling() {
        assertEquals(15.0, outerRadius(0, 5), EPS);
        assertEquals(8.5, outerRadius(1, 5), EPS);
        assertEquals(7.0, outerRadius(2, 5), EPS);
        assertEquals(6.0, outerRadius(3, 5), EPS);
        assertEquals(5.5, outerRadius(4, 5), EPS);
        assertEquals(0.0, outerRadius(5, 5), EPS);   // friendliness 5+ = no bubble
        assertEquals(0.0, outerRadius(10, 5), EPS);
    }

    @Test
    void innerRadiiAtNeutralFeeling() {
        assertEquals(10.0, innerRadius(0, 5), EPS);
        assertEquals(5.0, innerRadius(1, 5), EPS);
        assertEquals(1.5, innerRadius(2, 5), EPS);
        assertEquals(0.0, innerRadius(3, 5), EPS);   // skeptical-only
        assertEquals(0.0, innerRadius(4, 5), EPS);
    }

    @Test
    void feelingWidensForDislikedShrinksForLikedCappedAtMax() {
        assertEquals(15.5, outerRadius(0, 4), EPS);                       // disliked → wider
        assertEquals(DispositionResolver.MAX_RANGE, outerRadius(0, 0), EPS); // capped
        assertEquals(4.5, outerRadius(2, 10), EPS);                       // liked → narrower
        assertEquals(0.0, innerRadius(2, 10), EPS);                       // inner clamps to >= 0
    }

    // ---- hostiles: fight/flight, no bubble ----

    @Test
    void hostilesFightOrFleeByFightFlight() {
        assertEquals(Reaction.FIGHT, resolve(5, 0, 5, TargetCategory.HOSTILE_MOBS, 9));
        assertEquals(Reaction.FLEE, resolve(4, 9, 5, TargetCategory.HOSTILE_MOBS, 9));
    }

    // ---- animals / villagers: greet-if-friendly else ignore, never attacked ----

    @Test
    void animalsAndVillagersGreetOrIgnore() {
        assertEquals(Reaction.GREET, resolve(9, 5, 5, TargetCategory.ANIMALS, 2));
        assertEquals(Reaction.IGNORE, resolve(9, 4, 5, TargetCategory.ANIMALS, 2));
        assertEquals(Reaction.GREET, resolve(0, 5, 5, TargetCategory.VILLAGERS, 2));
        assertEquals(Reaction.IGNORE, resolve(10, 4, 0, TargetCategory.VILLAGERS, 1));
    }

    // ---- players: low-friendliness two-ring ----

    @Test
    void lowFriendlinessTwoRing() {
        // fr1 @ neutral: inner 5, outer 8.5
        assertEquals(Reaction.FIGHT, resolve(5, 1, 5, TargetCategory.PLAYERS, 3));   // inner, fighter
        assertEquals(Reaction.FLEE, resolve(4, 1, 5, TargetCategory.PLAYERS, 3));    // inner, flighty
        assertEquals(Reaction.WATCH, resolve(9, 1, 5, TargetCategory.PLAYERS, 6));   // between rings
        assertEquals(Reaction.IGNORE, resolve(9, 1, 5, TargetCategory.PLAYERS, 10)); // beyond outer
    }

    @Test
    void midFriendlinessIsSkepticalOnly() {
        // fr3 @ neutral: outer 6, inner 0 → never FIGHT/FLEE from distance alone
        assertEquals(Reaction.WATCH, resolve(9, 3, 5, TargetCategory.PLAYERS, 3));
        assertEquals(Reaction.WATCH, resolve(0, 3, 5, TargetCategory.PLAYERS, 5));
        assertEquals(Reaction.IGNORE, resolve(9, 3, 5, TargetCategory.PLAYERS, 7));
    }

    @Test
    void friendlyGreetsRegardlessOfDistance() {
        assertEquals(Reaction.GREET, resolve(0, 5, 5, TargetCategory.PLAYERS, 1));
        assertEquals(Reaction.GREET, resolve(10, 9, 6, TargetCategory.PLAYERS, 50));
    }

    // ---- players: hate overrides nature for any friendliness ----

    @Test
    void hateOverridesNature() {
        // A friendly-natured mob (fr8) that has been driven to hate (feeling <= 3)
        // fights/flees within HATE_RANGE regardless of its friendliness.
        assertEquals(Reaction.FLEE, resolve(4, 8, 2, TargetCategory.PLAYERS, 5));
        assertEquals(Reaction.FIGHT, resolve(6, 8, 2, TargetCategory.PLAYERS, 5));
        assertEquals(Reaction.FIGHT, resolve(5, 8, 3, TargetCategory.PLAYERS, 10)); // feeling==3 is hate
        assertEquals(Reaction.IGNORE, resolve(9, 8, 0, TargetCategory.PLAYERS,
            DispositionResolver.MAX_RANGE + 1));                                     // beyond hate range
    }

    // ---- players: love overrides nature for any friendliness ----

    @Test
    void loveOverridesNature() {
        // A territorial/aggressive mob (low friendliness) GREETS a player it loves (feeling >= 7)
        // at any distance, instead of attacking it in its personal-space bubble — mirrors hate.
        assertEquals(Reaction.GREET, resolve(9, 1, 7, TargetCategory.PLAYERS, 1));   // 9/1, loved, point-blank
        assertEquals(Reaction.GREET, resolve(9, 1, 10, TargetCategory.PLAYERS, 1));
        assertEquals(Reaction.GREET, resolve(10, 0, 8, TargetCategory.PLAYERS, 2));
        // feeling 6 is below love → still the territorial bubble (a fighter attacks up close).
        assertEquals(Reaction.FIGHT, resolve(9, 1, 6, TargetCategory.PLAYERS, 1));
    }

    // ---- onHurt ----

    @Test
    void onHurtThresholdAtFive() {
        assertEquals(DispositionResolver.HurtResponse.RETALIATE, onHurt(5));
        assertEquals(DispositionResolver.HurtResponse.FLEE, onHurt(4));
    }

    // ---- witnessed-attack reaction (signed continuous delta) ----

    @Test
    void witnessApproveBandScalesWithAggressionAndDislike() {
        // Positive admiration, reproducing cells of the design table (Like 0.4, cap 3).
        assertEquals(3.0f, witnessedAttackDelta(10, 0), EPS);   // hate + max aggression → the cap
        assertEquals(1.0f, witnessedAttackDelta(10, 5), EPS);   // neutral victim, max aggression
        assertEquals(0.9f, witnessedAttackDelta(3, 0), EPS);    // just over the approve threshold
        assertEquals(0.6f, witnessedAttackDelta(6, 3), EPS);
        assertEquals(0.6f, witnessedAttackDelta(10, 6), EPS);
        assertEquals(0.3f, witnessedAttackDelta(9, 6), EPS);    // threshold cell — now a small positive
    }

    @Test
    void witnessNeutralBandIsNoReaction() {
        // Below the approve threshold and below the love line → exactly 0.
        assertEquals(0.0f, witnessedAttackDelta(0, 0), EPS);
        assertEquals(0.0f, witnessedAttackDelta(2, 0), EPS);    // fightFlight − 3 < 0
        assertEquals(0.0f, witnessedAttackDelta(8, 6), EPS);    // feeling 6 needs fight/flight >= 9
        assertEquals(0.0f, witnessedAttackDelta(5, 5), EPS);
    }

    @Test
    void witnessResentBandDeepensWithLoveAndCalm() {
        // feeling >= 7 (love band): harming a loved one turns the mob against the attacker,
        // deeper the more it loves V and the calmer it is.
        assertEquals(-4.0f, witnessedAttackDelta(0, 10), EPS);  // beloved victim, calm witness
        assertEquals(-1.0f, witnessedAttackDelta(10, 10), EPS); // aggressive witness minds less
        assertEquals(-2.5f, witnessedAttackDelta(5, 10), EPS);
        assertEquals(-2.8f, witnessedAttackDelta(0, 7), EPS);
        assertEquals(-0.2f, witnessedAttackDelta(10, 8), EPS);
        // Edge: at the very bottom of the love band (feeling 7) a max-aggression witness tips
        // slightly positive (+0.2) — so pro-violence it mildly approves even here.
        assertEquals(0.2f, witnessedAttackDelta(10, 7), EPS);
    }

    @Test
    void witnessNeverExceedsTheCap() {
        for (int ff = 0; ff <= 10; ff++) {
            for (float feeling = 0; feeling <= 10; feeling++) {
                assertTrue(witnessedAttackDelta(ff, feeling) <= 3.0f + EPS,
                    "delta exceeded the cap at ff=" + ff + " feeling=" + feeling);
            }
        }
    }

    // ---- totality ----

    @Test
    void nullCategoryIsIgnored() {
        assertEquals(Reaction.IGNORE, resolve(9, 9, 5, null, 1));
    }

    @Test
    void resolveIsTotalAndCategoryLegal() {
        float[] feelings = {0f, 3f, 5f, 7f, 10f};
        double[] distances = {0, 1.5, 5, 8, 16, 30};
        for (int ff = 0; ff <= 10; ff++) {
            for (int fr = 0; fr <= 10; fr++) {
                for (float feeling : feelings) {
                    for (double d : distances) {
                        for (TargetCategory c : TargetCategory.values()) {
                            Reaction r = resolve(ff, fr, feeling, c, d);
                            assertNotNull(r, "resolve must never return null");
                            if (c == TargetCategory.ANIMALS || c == TargetCategory.VILLAGERS) {
                                assertTrue(r == Reaction.GREET || r == Reaction.IGNORE,
                                    c + " must greet or ignore, got " + r);
                            }
                        }
                    }
                }
            }
        }
    }
}
