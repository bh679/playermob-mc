package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link HazardBlockPolicy} — which blocks count as hurting, whether fire
 * immunity excuses them, and the reaction window's declared bounds. The live scan for somewhere safe
 * to step needs a real level and is covered by the in-game Gate 2 test, the same split
 * {@link PathFirePolicy} uses.
 *
 * <p>Same {@link Bootstrap} dance as {@code BlockSourcePolicyTest} — vanilla registries must boot
 * before any {@link Blocks} reference. The branches NOT asserted here are the tag-driven ones —
 * {@link PathFirePolicy#isFire} ({@code BlockTags.FIRE}) and the {@code FluidTags.LAVA} arm — which
 * need a bound datapack and are covered by the in-game Gate 2 test. The block-identity arms beside
 * them ({@link Blocks#LAVA}, {@link Blocks#MAGMA_BLOCK}) reach the same {@code FIRE} classification
 * without a tag, which is exactly why they're there.</p>
 */
class HazardBlockPolicyTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void ordinaryGroundIsNotAHazard() {
        assertEquals(HazardBlockPolicy.Hazard.NONE,
            HazardBlockPolicy.hazardKind(Blocks.STONE.defaultBlockState()));
        assertEquals(HazardBlockPolicy.Hazard.NONE,
            HazardBlockPolicy.hazardKind(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertEquals(HazardBlockPolicy.Hazard.NONE,
            HazardBlockPolicy.hazardKind(Blocks.AIR.defaultBlockState()));
        assertEquals(HazardBlockPolicy.Hazard.NONE, HazardBlockPolicy.hazardKind(null),
            "a missing state must not read as a hazard");
    }

    @Test
    void burningBlocksAreFireHazards() {
        assertEquals(HazardBlockPolicy.Hazard.FIRE,
            HazardBlockPolicy.hazardKind(Blocks.MAGMA_BLOCK.defaultBlockState()));
        assertEquals(HazardBlockPolicy.Hazard.FIRE,
            HazardBlockPolicy.hazardKind(Blocks.LAVA.defaultBlockState()));
    }

    @Test
    void aCampfireOnlyCountsWhileItIsLit() {
        assertEquals(HazardBlockPolicy.Hazard.FIRE, HazardBlockPolicy.hazardKind(
            Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT, true)));
        assertEquals(HazardBlockPolicy.Hazard.FIRE, HazardBlockPolicy.hazardKind(
            Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT, true)));
        assertEquals(HazardBlockPolicy.Hazard.NONE, HazardBlockPolicy.hazardKind(
            Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT, false)),
            "an unlit campfire is just furniture");
    }

    @Test
    void thingsThatHurtOnContactAreTheirOwnKind() {
        assertEquals(HazardBlockPolicy.Hazard.CONTACT,
            HazardBlockPolicy.hazardKind(Blocks.CACTUS.defaultBlockState()));
        assertEquals(HazardBlockPolicy.Hazard.CONTACT,
            HazardBlockPolicy.hazardKind(Blocks.SWEET_BERRY_BUSH.defaultBlockState()));
        assertEquals(HazardBlockPolicy.Hazard.CONTACT,
            HazardBlockPolicy.hazardKind(Blocks.WITHER_ROSE.defaultBlockState()));
        assertEquals(HazardBlockPolicy.Hazard.CONTACT,
            HazardBlockPolicy.hazardKind(Blocks.POWDER_SNOW.defaultBlockState()));
    }

    @Test
    void fireImmunityExcusesBurningBlocksButNothingElse() {
        assertTrue(HazardBlockPolicy.isHarmful(HazardBlockPolicy.Hazard.FIRE, /* fireImmune */ false));
        assertFalse(HazardBlockPolicy.isHarmful(HazardBlockPolicy.Hazard.FIRE, /* fireImmune */ true),
            "a mob that can't burn has no reason to step off magma");
        assertTrue(HazardBlockPolicy.isHarmful(HazardBlockPolicy.Hazard.CONTACT, /* fireImmune */ true),
            "cactus doesn't care how fireproof you are");
        assertFalse(HazardBlockPolicy.isHarmful(HazardBlockPolicy.Hazard.NONE, false));
        assertFalse(HazardBlockPolicy.isHarmful(HazardBlockPolicy.Hazard.NONE, true));
    }

    @Test
    void theStateOverloadAgreesWithTheKindOverload() {
        assertTrue(HazardBlockPolicy.isHarmful(Blocks.MAGMA_BLOCK.defaultBlockState(), false));
        assertFalse(HazardBlockPolicy.isHarmful(Blocks.MAGMA_BLOCK.defaultBlockState(), true));
        assertFalse(HazardBlockPolicy.isHarmful(Blocks.STONE.defaultBlockState(), false));
    }

    @Test
    void reactionWindowMatchesTheSpecifiedBounds() {
        // The window is the neutral (reaction 5) baseline; StepOffHazardGoal rolls it through
        // PlayerMobEntity.reactRoll, whose bounds and skew are ReactionSpeedTest's business.
        assertEquals(5, HazardBlockPolicy.REACTION_MIN_TICKS);
        assertEquals(20, HazardBlockPolicy.REACTION_MAX_TICKS);
        assertTrue(HazardBlockPolicy.REACTION_MIN_TICKS < HazardBlockPolicy.REACTION_MAX_TICKS);
    }

    @Test
    void scanAndCooldownBudgetsAreSane() {
        assertTrue(HazardBlockPolicy.SEARCH_RADIUS > 0);
        assertTrue(HazardBlockPolicy.SEARCH_VERTICAL > 0);
        // A boxed-in mob must not re-scan every tick, but must retry well within a life's worth of damage.
        assertTrue(HazardBlockPolicy.RECHECK_TICKS >= 20 && HazardBlockPolicy.RECHECK_TICKS <= 100);
        assertTrue(HazardBlockPolicy.MOVE_TIMEOUT_TICKS > HazardBlockPolicy.REACTION_MAX_TICKS);
    }
}
