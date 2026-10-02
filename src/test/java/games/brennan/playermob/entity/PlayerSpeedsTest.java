package games.brennan.playermob.entity;

import games.brennan.playermob.entity.PlayerSpeeds.Style;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link PlayerSpeeds} — gait selection, the player-stick input, the legacy
 * base-speed migration, and a physics check that the constants really do produce a player's speeds
 * under vanilla's movement maths. Primitives only, so no Minecraft bootstrap is needed (mirrors
 * {@link StayNearPolicyTest}).
 */
class PlayerSpeedsTest {

    private static final float EPS = 1.0e-6F;

    // Vanilla movement constants (LivingEntity.travel / jumpFromGround), for the physics oracle.
    private static final double INPUT_DAMPING = 0.98;
    private static final double GROUND_DRAG = 0.6 * 0.91;      // default block friction x air drag
    private static final double AIR_DRAG = 0.91;
    private static final double SPRINT_MODIFIER = 1.3;         // +30% multiply-total
    private static final double SPRINT_JUMP_BOOST = 0.2;
    private static final int JUMP_PERIOD_TICKS = 12;           // flat-ground jump, takeoff to landing

    // ---- The reaction-speed table ----

    private static final double NEVER = 0.999;   // a roll that only passes a 100% chance
    private static final double ALWAYS = 0.0;    // a roll that passes any non-zero chance

    @Test
    void goalsOnlyMarkMovementCasualOrUrgent() {
        assertFalse(PlayerSpeeds.isUrgent(PlayerSpeeds.CASUAL));
        assertTrue(PlayerSpeeds.isUrgent(PlayerSpeeds.URGENT));
        // Vanilla's strafe modifier is never urgent.
        assertFalse(PlayerSpeeds.isUrgent(0.25));
    }

    @Test
    void slowestReactorsAlwaysWalk() {
        for (int reaction = 0; reaction <= 1; reaction++) {
            assertEquals(Style.WALK, PlayerSpeeds.styleFor(reaction, false, ALWAYS), "reaction " + reaction);
            assertEquals(Style.WALK, PlayerSpeeds.styleFor(reaction, true, ALWAYS), "reaction " + reaction + ", urgent");
        }
    }

    @Test
    void slowReactorsWalkCasuallyAndSometimesSprintInCombat() {
        for (int reaction = 2; reaction <= 4; reaction++) {
            assertEquals(Style.WALK, PlayerSpeeds.styleFor(reaction, false, ALWAYS), "reaction " + reaction);
        }
        assertEquals(0.25, PlayerSpeeds.sprintChance(2, true), 1.0e-9);
        assertEquals(0.50, PlayerSpeeds.sprintChance(3, true), 1.0e-9);
        assertEquals(0.75, PlayerSpeeds.sprintChance(4, true), 1.0e-9);
        // The roll decides: under the chance sprints, at or over it walks.
        assertEquals(Style.SPRINT, PlayerSpeeds.styleFor(2, true, 0.24));
        assertEquals(Style.WALK, PlayerSpeeds.styleFor(2, true, 0.25));
        assertEquals(Style.SPRINT, PlayerSpeeds.styleFor(4, true, 0.74));
        assertEquals(Style.WALK, PlayerSpeeds.styleFor(4, true, 0.75));
    }

    @Test
    void neutralReactorMixesWalkAndSprintCasuallyAndSprintsInCombat() {
        assertEquals(Style.SPRINT, PlayerSpeeds.styleFor(5, false, 0.49));
        assertEquals(Style.WALK, PlayerSpeeds.styleFor(5, false, 0.50));
        assertEquals(Style.SPRINT, PlayerSpeeds.styleFor(5, true, NEVER));
    }

    @Test
    void quickReactorsSprintCasuallyAndSprintJumpInCombat() {
        for (int reaction = 6; reaction <= 7; reaction++) {
            assertEquals(Style.SPRINT, PlayerSpeeds.styleFor(reaction, false, NEVER), "reaction " + reaction);
            assertEquals(Style.SPRINT_JUMP, PlayerSpeeds.styleFor(reaction, true, NEVER), "reaction " + reaction + ", urgent");
        }
    }

    @Test
    void sharpReactorsSprintJumpCasuallyAndBoostInCombat() {
        for (int reaction = 8; reaction <= 9; reaction++) {
            assertEquals(Style.SPRINT_JUMP, PlayerSpeeds.styleFor(reaction, false, NEVER), "reaction " + reaction);
            assertEquals(Style.BOOST, PlayerSpeeds.styleFor(reaction, true, NEVER), "reaction " + reaction + ", urgent");
        }
    }

    @Test
    void theSharpestBoostEverywhere() {
        assertEquals(Style.BOOST, PlayerSpeeds.styleFor(10, false, NEVER));
        assertEquals(Style.BOOST, PlayerSpeeds.styleFor(10, true, NEVER));
    }

    @Test
    void urgencyNeverMakesAMobSlower() {
        for (int reaction = 0; reaction <= 10; reaction++) {
            for (double roll : new double[] {0.0, 0.3, 0.6, 0.9}) {
                assertTrue(PlayerSpeeds.styleFor(reaction, true, roll).ordinal()
                        >= PlayerSpeeds.styleFor(reaction, false, roll).ordinal(), "reaction " + reaction);
            }
        }
    }

    @Test
    void eachStyleIncludesTheOnesBelowIt() {
        assertFalse(Style.WALK.sprints());
        assertTrue(Style.SPRINT.sprints());
        assertFalse(Style.SPRINT.sprintJumps());
        assertTrue(Style.SPRINT_JUMP.sprints());
        assertTrue(Style.SPRINT_JUMP.sprintJumps());
        assertFalse(Style.SPRINT_JUMP.boosts());
        assertTrue(Style.BOOST.sprintJumps());
        assertTrue(Style.BOOST.boosts());
    }

    @Test
    void outOfRangeReactionSpeedsAreClamped() {
        assertEquals(Style.WALK, PlayerSpeeds.styleFor(-3, true, ALWAYS));
        assertEquals(Style.BOOST, PlayerSpeeds.styleFor(99, false, NEVER));
    }

    @Test
    void movingForwardIsFullStick() {
        float[] input = PlayerSpeeds.stickInput(1.0F, 0.0F, 1.0F, false);
        assertEquals(1.0F, input[0], EPS);
        assertEquals(0.0F, input[1], EPS);
    }

    @Test
    void vanillaSpeedScaledInputIsReplacedNotKept() {
        // Mob.setSpeed leaves forward = modifier x attribute (here 1.3 x 0.10); the magnitude must not survive.
        float[] input = PlayerSpeeds.stickInput(0.13F, 0.0F, 1.0F, false);
        assertEquals(1.0F, input[0], EPS);
    }

    @Test
    void strafeIsRescaledToUnitLength() {
        // Bow goals strafe with (0.5, 0.5) — a player holding W+D moves at full walk speed.
        float[] input = PlayerSpeeds.stickInput(0.5F, 0.5F, 1.0F, false);
        assertEquals(1.0, Math.hypot(input[0], input[1]), 1.0e-6);
        assertEquals(input[0], input[1], EPS);

        float[] back = PlayerSpeeds.stickInput(-0.5F, 0.5F, 1.0F, false);
        assertEquals(-input[0], back[0], EPS, "direction is preserved");
    }

    @Test
    void usingAnItemCutsInputToAFifth() {
        float[] forward = PlayerSpeeds.stickInput(1.0F, 0.0F, 1.0F, true);
        assertEquals(0.2F, forward[0], EPS);

        float[] strafe = PlayerSpeeds.stickInput(0.5F, -0.5F, 1.0F, true);
        assertEquals(0.2, Math.hypot(strafe[0], strafe[1]), 1.0e-6);
    }

    @Test
    void fullStickMagnitudeIsVersionSupplied() {
        // 26+ applies the 0.98 input damping before the AI step, so the control writes it itself.
        float[] input = PlayerSpeeds.stickInput(1.0F, 0.0F, 0.98F, false);
        assertEquals(0.98F, input[0], EPS);
    }

    @Test
    void noInputStaysNoInput() {
        float[] input = PlayerSpeeds.stickInput(0.0F, 0.0F, 1.0F, false);
        assertEquals(0.0F, input[0], EPS);
        assertEquals(0.0F, input[1], EPS);
    }

    @Test
    void legacyBaseSpeedMigratesOnce() {
        assertEquals(PlayerSpeeds.PLAYER_BASE_SPEED,
            PlayerSpeeds.migratedBaseSpeed(PlayerSpeeds.LEGACY_BASE_SPEED, false), 1.0e-9);
    }

    @Test
    void migratedOrCustomBaseSpeedsAreLeftAlone() {
        // Already saved under the new model: someone set 0.30 on purpose with /attribute.
        assertEquals(0.30, PlayerSpeeds.migratedBaseSpeed(0.30, true), 1.0e-9);
        // An unmarked save with a deliberate custom base.
        assertEquals(0.25, PlayerSpeeds.migratedBaseSpeed(0.25, false), 1.0e-9);
        // A fresh spawn (unmarked, already at the player base).
        assertEquals(0.10, PlayerSpeeds.migratedBaseSpeed(0.10, false), 1.0e-9);
    }

    // ---- Physics oracle: these constants under vanilla's maths give a player's speeds ----

    @Test
    void walkIsPlayerWalkSpeed() {
        assertEquals(4.317, groundSpeedMetresPerSecond(PlayerSpeeds.PLAYER_BASE_SPEED), 0.001);
    }

    @Test
    void sprintIsPlayerSprintSpeed() {
        assertEquals(5.612, groundSpeedMetresPerSecond(PlayerSpeeds.PLAYER_BASE_SPEED * SPRINT_MODIFIER), 0.001);
    }

    @Test
    void usingAnItemIsAFifthOfWalk() {
        double slowed = groundSpeedMetresPerSecond(PlayerSpeeds.PLAYER_BASE_SPEED) * PlayerSpeeds.USE_ITEM_INPUT;
        assertEquals(0.863, slowed, 0.001);
    }

    @Test
    void sprintJumpAveragesPlayerSprintJumpSpeed() {
        assertEquals(7.127, sprintJumpMetresPerSecond(PlayerSpeeds.SPRINT_FLYING_SPEED), 0.01);
    }

    @Test
    void withoutThePlayerFlyingSpeedASprintJumpFallsShort() {
        // The mob default (0.02 airborne, sprinting or not) is why getFlyingSpeed is overridden.
        assertEquals(6.25, sprintJumpMetresPerSecond(PlayerSpeeds.FLYING_SPEED), 0.05);
    }

    @Test
    void legacyBaseUnderFullStickWouldBeFarTooFast() {
        // Why the save migration matters: an unmigrated 0.30 base is three times a player's walk.
        assertEquals(12.95, groundSpeedMetresPerSecond(PlayerSpeeds.LEGACY_BASE_SPEED), 0.01);
    }

    /** Terminal ground speed for a full-stick input at the given speed attribute, in m/s. */
    private static double groundSpeedMetresPerSecond(double speedAttribute) {
        double accel = speedAttribute * INPUT_DAMPING;   // friction factor is 1.0 on default blocks
        return accel / (1.0 - GROUND_DRAG) * 20.0;
    }

    /** Steady-state average speed of held-jump sprint-jumping on flat ground, in m/s. */
    private static double sprintJumpMetresPerSecond(float flyingSpeed) {
        double groundAccel = PlayerSpeeds.PLAYER_BASE_SPEED * SPRINT_MODIFIER * INPUT_DAMPING;
        double airAccel = flyingSpeed * INPUT_DAMPING;
        double velocity = 0.0;
        double cycleDistance = 0.0;
        for (int cycle = 0; cycle < 50; cycle++) {              // long enough to converge
            cycleDistance = 0.0;
            for (int tick = 0; tick < JUMP_PERIOD_TICKS; tick++) {
                boolean takeoff = tick == 0;
                // The takeoff tick starts on the ground: sprint boost, ground accel, ground drag.
                velocity += takeoff ? SPRINT_JUMP_BOOST + groundAccel : airAccel;
                cycleDistance += velocity;
                velocity *= takeoff ? GROUND_DRAG : AIR_DRAG;
            }
        }
        return cycleDistance / JUMP_PERIOD_TICKS * 20.0;
    }
}
