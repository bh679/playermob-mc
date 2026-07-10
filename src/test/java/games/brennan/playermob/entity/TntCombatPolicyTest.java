package games.brennan.playermob.entity;

import games.brennan.playermob.entity.TntCombatPolicy.IgnitionStrategy;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link TntCombatPolicy} — igniter recognition, preference ordering, and TNT
 * placement-candidate ordering. Uses the same {@link Bootstrap} dance as {@code ItemPickupPolicyTest}
 * (vanilla item/block registries must exist before any {@link Items} reference). Classification is by
 * item identity / block {@code instanceof}, not datapack tags, so these run on every MC version.
 */
class TntCombatPolicyTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void classifiesEachIgniterFamily() {
        assertSame(IgnitionStrategy.RIGHT_CLICK, TntCombatPolicy.classify(new ItemStack(Items.FLINT_AND_STEEL)), "flint & steel");
        assertSame(IgnitionStrategy.RIGHT_CLICK, TntCombatPolicy.classify(new ItemStack(Items.FIRE_CHARGE)), "fire charge");
        assertSame(IgnitionStrategy.PLACE_POWER, TntCombatPolicy.classify(new ItemStack(Items.REDSTONE_BLOCK)), "redstone block");
        assertSame(IgnitionStrategy.PLACE_POWER, TntCombatPolicy.classify(new ItemStack(Items.LEVER)), "lever");
        assertSame(IgnitionStrategy.PLACE_POWER, TntCombatPolicy.classify(new ItemStack(Items.STONE_BUTTON)), "stone button");
        assertSame(IgnitionStrategy.PLACE_POWER, TntCombatPolicy.classify(new ItemStack(Items.OAK_BUTTON)), "wooden button");
        assertSame(IgnitionStrategy.TRAP, TntCombatPolicy.classify(new ItemStack(Items.STONE_PRESSURE_PLATE)), "stone plate");
        assertSame(IgnitionStrategy.TRAP, TntCombatPolicy.classify(new ItemStack(Items.OAK_PRESSURE_PLATE)), "wooden plate");
        // A weighted plate is a WeightedPressurePlateBlock — still a BasePressurePlateBlock, still a TRAP.
        assertSame(IgnitionStrategy.TRAP, TntCombatPolicy.classify(new ItemStack(Items.HEAVY_WEIGHTED_PRESSURE_PLATE)), "weighted plate");
    }

    @Test
    void rejectsNonIgniters() {
        assertNull(TntCombatPolicy.classify(ItemStack.EMPTY), "empty");
        assertNull(TntCombatPolicy.classify(new ItemStack(Items.TNT)), "TNT itself is not an igniter");
        assertNull(TntCombatPolicy.classify(new ItemStack(Items.REDSTONE)), "redstone dust doesn't prime on placement");
        assertNull(TntCombatPolicy.classify(new ItemStack(Items.COBBLESTONE)), "plain block");
        assertNull(TntCombatPolicy.classify(new ItemStack(Items.DIAMOND_SWORD)), "weapon");
        assertFalse(TntCombatPolicy.isIgniter(new ItemStack(Items.COBBLESTONE)), "isIgniter(block)");
        assertTrue(TntCombatPolicy.isIgniter(new ItemStack(Items.FLINT_AND_STEEL)), "isIgniter(flint & steel)");
    }

    @Test
    void ranksIgnitersDeterministicLightersFirst() {
        int fs = TntCombatPolicy.igniterRank(new ItemStack(Items.FLINT_AND_STEEL));
        int fc = TntCombatPolicy.igniterRank(new ItemStack(Items.FIRE_CHARGE));
        int rb = TntCombatPolicy.igniterRank(new ItemStack(Items.REDSTONE_BLOCK));
        int lv = TntCombatPolicy.igniterRank(new ItemStack(Items.LEVER));
        int bt = TntCombatPolicy.igniterRank(new ItemStack(Items.STONE_BUTTON));
        int pp = TntCombatPolicy.igniterRank(new ItemStack(Items.STONE_PRESSURE_PLATE));
        assertTrue(fs < fc && fc < rb && rb < lv && lv < bt && bt < pp,
            "priority order: flint&steel < fire charge < redstone block < lever < button < plate");
        assertEquals(-1, TntCombatPolicy.igniterRank(new ItemStack(Items.COBBLESTONE)), "non-igniter rank");
    }

    @Test
    void selectsBestIgniterSlotByPriority() {
        SimpleContainer pack = new SimpleContainer(6);
        pack.setItem(0, new ItemStack(Items.COBBLESTONE));
        pack.setItem(1, new ItemStack(Items.STONE_PRESSURE_PLATE)); // rank 5
        pack.setItem(2, new ItemStack(Items.LEVER));                // rank 3
        pack.setItem(3, new ItemStack(Items.FIRE_CHARGE));          // rank 1 — best present
        pack.setItem(4, new ItemStack(Items.DIAMOND_SWORD));
        assertEquals(3, TntCombatPolicy.selectIgniterSlot(pack), "picks the fire charge (lowest rank)");
        assertTrue(TntCombatPolicy.hasIgniter(pack), "hasIgniter true when any present");
    }

    @Test
    void reportsNoIgniterWhenNonePresent() {
        SimpleContainer pack = new SimpleContainer(4);
        pack.setItem(0, new ItemStack(Items.COBBLESTONE));
        pack.setItem(1, new ItemStack(Items.TNT));
        pack.setItem(2, new ItemStack(Items.REDSTONE)); // dust, not an igniter
        assertEquals(-1, TntCombatPolicy.selectIgniterSlot(pack), "no igniter → -1");
        assertFalse(TntCombatPolicy.hasIgniter(pack), "hasIgniter false");
    }

    @Test
    void findsFirstTntSlot() {
        SimpleContainer pack = new SimpleContainer(4);
        pack.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
        pack.setItem(1, new ItemStack(Items.TNT, 3));
        assertEquals(1, TntCombatPolicy.firstTntSlot(pack), "finds the TNT slot");

        SimpleContainer noTnt = new SimpleContainer(2);
        noTnt.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
        assertEquals(-1, TntCombatPolicy.firstTntSlot(noTnt), "no TNT → -1");
    }

    @Test
    void ordersPlacementCandidatesTowardTarget() {
        BlockPos mob = new BlockPos(0, 64, 0);
        BlockPos target = new BlockPos(3, 64, 0); // due east
        List<BlockPos> candidates = TntCombatPolicy.placementCandidates(mob, target);

        assertEquals(new BlockPos(1, 64, 0), candidates.get(0), "first candidate steps from the mob toward the target");
        assertTrue(candidates.contains(mob), "includes the mob's own footprint");
        assertTrue(candidates.contains(target), "includes the target's feet");
        assertEquals(new BlockPos(2, 64, 0), candidates.get(candidates.size() - 2), "then just short of the target");
        // No duplicates.
        assertEquals(candidates.size(), candidates.stream().distinct().count(), "candidates are de-duplicated");
        assertEquals(4, candidates.size(), "four distinct candidates for a 3-block separation");
    }

    @Test
    void placementCandidatesCollapseWhenMobOnTarget() {
        BlockPos same = new BlockPos(5, 70, 5);
        List<BlockPos> candidates = TntCombatPolicy.placementCandidates(same, same);
        assertEquals(List.of(same), candidates, "no direction → just the shared block, de-duplicated to one entry");
    }
}
