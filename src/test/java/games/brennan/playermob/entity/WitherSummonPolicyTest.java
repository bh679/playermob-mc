package games.brennan.playermob.entity;

import games.brennan.playermob.entity.WitherSummonPolicy.Rig;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link WitherSummonPolicy} — kit recognition (soul sand <em>and</em> soul soil), the four
 * gates a mob has to clear before it reaches for a wither, and the vanilla rig geometry. Uses the same
 * {@link Bootstrap} dance as {@link EndCrystalCombatPolicyTest} (vanilla item registries must exist before any
 * {@link Items} reference). Everything here is item identity + arithmetic, no datapack tags, so it runs on every
 * MC version.
 */
class WitherSummonPolicyTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static SimpleContainer kit(int soulSand, int soulSoil, int skulls) {
        SimpleContainer pack = new SimpleContainer(9);
        int slot = 0;
        if (soulSand > 0) {
            pack.setItem(slot++, new ItemStack(Items.SOUL_SAND, soulSand));
        }
        if (soulSoil > 0) {
            pack.setItem(slot++, new ItemStack(Items.SOUL_SOIL, soulSoil));
        }
        if (skulls > 0) {
            pack.setItem(slot, new ItemStack(Items.WITHER_SKELETON_SKULL, skulls));
        }
        return pack;
    }

    @Test
    void countsBothSoulBlocks() {
        assertTrue(WitherSummonPolicy.isSoulBlock(new ItemStack(Items.SOUL_SAND)), "soul sand");
        assertTrue(WitherSummonPolicy.isSoulBlock(new ItemStack(Items.SOUL_SOIL)), "soul soil");
        assertFalse(WitherSummonPolicy.isSoulBlock(new ItemStack(Items.SAND)), "plain sand is not soul sand");
        assertFalse(WitherSummonPolicy.isSoulBlock(ItemStack.EMPTY), "empty");
        assertEquals(5, WitherSummonPolicy.soulBlockCount(kit(2, 3, 0)), "sand + soil both count");
    }

    @Test
    void countsOnlyWitherSkulls() {
        SimpleContainer pack = new SimpleContainer(3);
        pack.setItem(0, new ItemStack(Items.WITHER_SKELETON_SKULL, 2));
        pack.setItem(1, new ItemStack(Items.SKELETON_SKULL, 4));
        pack.setItem(2, new ItemStack(Items.CREEPER_HEAD, 4));
        assertEquals(2, WitherSummonPolicy.skullCount(pack), "only wither skeleton skulls count");
    }

    @Test
    void findsFirstKitSlots() {
        SimpleContainer pack = new SimpleContainer(4);
        pack.setItem(1, new ItemStack(Items.SOUL_SOIL, 4));
        pack.setItem(3, new ItemStack(Items.WITHER_SKELETON_SKULL, 3));
        assertEquals(1, WitherSummonPolicy.firstSoulSlot(pack));
        assertEquals(3, WitherSummonPolicy.firstSkullSlot(pack));
        assertEquals(-1, WitherSummonPolicy.firstSoulSlot(new SimpleContainer(2)), "empty pack has no soul block");
        assertEquals(-1, WitherSummonPolicy.firstSkullSlot(new SimpleContainer(2)), "empty pack has no skull");
    }

    @Test
    void kitNeedsFourSoulBlocksAndThreeSkulls() {
        assertTrue(WitherSummonPolicy.hasKit(kit(4, 0, 3)), "exactly the pattern");
        assertTrue(WitherSummonPolicy.hasKit(kit(2, 2, 5)), "mixed soul blocks, spare skulls");
        assertFalse(WitherSummonPolicy.hasKit(kit(3, 0, 3)), "three soul blocks is one short");
        assertFalse(WitherSummonPolicy.hasKit(kit(4, 0, 2)), "two skulls is one short");
        assertFalse(WitherSummonPolicy.hasKit(new SimpleContainer(9)), "empty pack");
    }

    @Test
    void onlyADesperateMobEscalates() {
        assertTrue(WitherSummonPolicy.isDesperate(9.0F, 20.0F), "below half hearts");
        assertFalse(WitherSummonPolicy.isDesperate(10.0F, 20.0F), "exactly half is not yet desperate");
        assertFalse(WitherSummonPolicy.isDesperate(20.0F, 20.0F), "full health");
        assertFalse(WitherSummonPolicy.isDesperate(0.0F, 0.0F), "degenerate max health");
    }

    @Test
    void onlyAFuriousMobEscalates() {
        assertTrue(WitherSummonPolicy.isFurious(WitherSummonPolicy.FF_WITHER_MIN), "at the threshold");
        assertTrue(WitherSummonPolicy.isFurious(10), "maximally aggressive");
        assertFalse(WitherSummonPolicy.isFurious(WitherSummonPolicy.FF_WITHER_MIN - 1), "one below the threshold");
        assertFalse(WitherSummonPolicy.isFurious(5), "a middling fighter doesn't reach this far");
    }

    @Test
    void onlyAHatedTargetEarnsAWither() {
        assertTrue(WitherSummonPolicy.hatesTarget(0.0F), "loathing");
        assertTrue(WitherSummonPolicy.hatesTarget(DispositionResolver.FEELING_HATE), "at the hate threshold");
        assertFalse(WitherSummonPolicy.hatesTarget(DispositionResolver.FEELING_HATE + 0.1F), "merely disliked");
        assertFalse(WitherSummonPolicy.hatesTarget(FeelingLedger.DEFAULT), "a stranger");
    }

    @Test
    void rigIsTheVanillaPattern() {
        Rig rig = WitherSummonPolicy.placementCandidates(BlockPos.ZERO, new BlockPos(10, 0, 0)).get(0);
        assertEquals(4, rig.soulPositions().size(), "four soul blocks");
        assertEquals(3, rig.skullPositions().size(), "three skulls");
        assertEquals(7, new HashSet<>(rig.allPositions()).size(), "seven distinct blocks");

        // bottom soul block, the row of three one above it, the skulls one above those
        assertEquals(rig.bottom().above(), rig.midCenter(), "mid row sits on the bottom block");
        assertEquals(rig.midCenter().above(), rig.skullCenter(), "skulls sit on the mid row");
        for (BlockPos mid : List.of(rig.midLeft(), rig.midRight())) {
            assertEquals(rig.midCenter().getY(), mid.getY(), "the mid row is level");
        }
        assertEquals(rig.midLeft().above(), rig.skullLeft(), "left skull tops the left soul block");
        assertEquals(rig.midRight().above(), rig.skullRight(), "right skull tops the right soul block");
        assertNotEquals(rig.midLeft(), rig.midRight(), "the row's arms go opposite ways");
        // The arms run across the mob's facing: an east-facing rig spreads north/south.
        assertEquals(rig.midCenter().getX(), rig.midLeft().getX(), "arms run across an east-facing rig");
        assertEquals(rig.midCenter().getX(), rig.midRight().getX(), "arms run across an east-facing rig");
    }

    @Test
    void centreSkullIsPlacedLast() {
        Rig rig = WitherSummonPolicy.placementCandidates(BlockPos.ZERO, new BlockPos(0, 0, 10)).get(0);
        List<BlockPos> skulls = rig.skullPositions();
        assertEquals(rig.skullCenter(), skulls.get(skulls.size() - 1),
            "the centre skull goes last — it's the one that triggers the spawn check");
    }

    @Test
    void candidatesFaceTheTargetFirstAndDropTheRear() {
        BlockPos mob = BlockPos.ZERO;
        List<Rig> east = WitherSummonPolicy.placementCandidates(mob, new BlockPos(10, 0, 0));
        assertEquals(3, east.size(), "the rear-facing direction is dropped");
        assertTrue(east.get(0).bottom().getX() > 0, "the best candidate is laid toward the target");
        assertEquals(0, east.get(0).bottom().getZ(), "and squarely so");
        for (Rig rig : east) {
            assertTrue(rig.bottom().getX() >= 0, "no candidate is laid behind the mob");
        }

        // Sharing a column: no direction to prefer, so all four are offered, north first.
        List<Rig> above = WitherSummonPolicy.placementCandidates(mob, new BlockPos(0, 5, 0));
        assertEquals(4, above.size(), "every facing is offered when there's no direction");
        assertTrue(above.get(0).bottom().getZ() < 0, "north first");
    }
}
