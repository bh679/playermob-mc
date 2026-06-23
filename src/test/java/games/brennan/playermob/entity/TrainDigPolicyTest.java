package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link TrainDigPolicy} — which fill blocks a PlayerMob may mine to pass
 * through a packed Dungeon-Train carriage.
 *
 * <p>Same {@link Bootstrap} dance as {@link BlockSourcePolicyTest} (vanilla registries must boot
 * before any {@link Blocks} reference). The {@link net.minecraft.tags.BlockTags#LOGS} branch needs
 * a bound datapack and is covered by the in-game Gate 2 smoke test, not here — this suite exercises
 * the explicit-{@code Blocks} fill set and the negatives.</p>
 */
class TrainDigPolicyTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void iceVariantsAreDiggable() {
        assertTrue(TrainDigPolicy.isDiggableObstruction(Blocks.ICE.defaultBlockState()));
        assertTrue(TrainDigPolicy.isDiggableObstruction(Blocks.PACKED_ICE.defaultBlockState()));
        assertTrue(TrainDigPolicy.isDiggableObstruction(Blocks.BLUE_ICE.defaultBlockState()));
    }

    @Test
    void dirtFamilyMudAndMossAreDiggable() {
        assertTrue(TrainDigPolicy.isDiggableObstruction(Blocks.DIRT.defaultBlockState()));
        assertTrue(TrainDigPolicy.isDiggableObstruction(Blocks.COARSE_DIRT.defaultBlockState()));
        assertTrue(TrainDigPolicy.isDiggableObstruction(Blocks.GRASS_BLOCK.defaultBlockState()));
        assertTrue(TrainDigPolicy.isDiggableObstruction(Blocks.MUD.defaultBlockState()));
        assertTrue(TrainDigPolicy.isDiggableObstruction(Blocks.MOSS_BLOCK.defaultBlockState()));
    }

    @Test
    void structuralAndUnrelatedBlocksAreNotDiggable() {
        // The stone-brick bed / rails and any structural wall must never be mined.
        assertFalse(TrainDigPolicy.isDiggableObstruction(Blocks.STONE_BRICKS.defaultBlockState()),
            "the carriage bed is stone brick — never dig it");
        assertFalse(TrainDigPolicy.isDiggableObstruction(Blocks.STONE.defaultBlockState()));
        assertFalse(TrainDigPolicy.isDiggableObstruction(Blocks.COBBLESTONE.defaultBlockState()));
        assertFalse(TrainDigPolicy.isDiggableObstruction(Blocks.GLASS.defaultBlockState()));
        assertFalse(TrainDigPolicy.isDiggableObstruction(Blocks.OAK_PLANKS.defaultBlockState()),
            "planks are a build block, not raw fill");
    }
}
