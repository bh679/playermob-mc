package games.brennan.playermob.entity;

import org.junit.jupiter.api.Test;

import static games.brennan.playermob.entity.DispositionResolver.admireBonus;
import static games.brennan.playermob.entity.DispositionResolver.approvesWitnessedAttack;
import static games.brennan.playermob.entity.DispositionResolver.innerRadius;
import static games.brennan.playermob.entity.DispositionResolver.onHurt;
import static games.brennan.playermob.entity.DispositionResolver.outerRadius;
import static games.brennan.playermob.entity.DispositionResolver.resolve;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ---- onHurt ----

    @Test
    void onHurtThresholdAtFive() {
        assertEquals(DispositionResolver.HurtResponse.RETALIATE, onHurt(5));
        assertEquals(DispositionResolver.HurtResponse.FLEE, onHurt(4));
    }

    // ---- witnessed-attack admiration ----

    @Test
    void maxAggressionAdmiresUnlessVictimIsAFavourite() {
        // Fight/flight 10: likes the attacker unless it really likes the victim (feeling 7+).
        assertTrue(approvesWitnessedAttack(10, 0));
        assertTrue(approvesWitnessedAttack(10, 5));
        assertTrue(approvesWitnessedAttack(10, 6));
        assertFalse(approvesWitnessedAttack(10, 7), "feeling 7 is the favourite (harm) band");
        assertFalse(approvesWitnessedAttack(10, 8));
        assertFalse(approvesWitnessedAttack(10, 10));
    }

    @Test
    void lowAggressionAdmiresOnlyForDislikedVictims() {
        // Fight/flight 6: likes it only if it dislikes the victim (feeling 1–3).
        assertTrue(approvesWitnessedAttack(6, 1));
        assertTrue(approvesWitnessedAttack(6, 3));
        assertFalse(approvesWitnessedAttack(6, 4));
        assertFalse(approvesWitnessedAttack(6, 5));
    }

    @Test
    void timidMobsNeverAdmireViolence() {
        // Threshold = fightFlight − 3. Fight/flight ≤ 2 can never approve (feeling floors at 0);
        // fight/flight 3 approves only for an utterly-hated victim (feeling 0), nothing higher.
        for (float feeling = 0; feeling <= 10; feeling++) {
            assertFalse(approvesWitnessedAttack(2, feeling), "ff=2 never approves");
            assertFalse(approvesWitnessedAttack(0, feeling), "ff=0 never approves");
        }
        assertTrue(approvesWitnessedAttack(3, 0), "ff=3 admires only its most-hated victim");
        assertFalse(approvesWitnessedAttack(3, 1), "ff=3 won't approve above feeling 0");
    }

    @Test
    void approvalNeverOverlapsTheHarmBand() {
        // The harm-a-loved-one event owns feeling >= FEELING_LOVE; approval must stay out of it
        // at every fight/flight, so a single witnessed attack is only ever harm OR admire.
        for (int ff = 0; ff <= 10; ff++) {
            for (float feeling = DispositionResolver.FEELING_LOVE; feeling <= 10; feeling++) {
                assertFalse(approvesWitnessedAttack(ff, feeling),
                    "approval leaked into the harm band: ff=" + ff + " feeling=" + feeling);
            }
        }
    }

    @Test
    void admireBonusFloorsAtBaseAndScalesWithDislike() {
        assertEquals(0.5f, admireBonus(6), EPS); // mildly likes victim → floor
        assertEquals(0.5f, admireBonus(5), EPS); // neutral → floor
        assertEquals(0.6f, admireBonus(4), EPS);
        assertEquals(0.7f, admireBonus(3), EPS); // base 0.5 + 0.2 reaches the cap
        assertEquals(0.7f, admireBonus(2), EPS); // capped
        assertEquals(0.7f, admireBonus(0), EPS); // capped (hates victim)
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
