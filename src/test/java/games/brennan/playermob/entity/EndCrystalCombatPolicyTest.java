package games.brennan.playermob.entity;

import games.brennan.playermob.entity.EndCrystalCombatPolicy.Layout;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link EndCrystalCombatPolicy} — cover-block recognition (full-cube collision), kit slot
 * finding, the {@code hasKit} gate, non-obsidian cover preference, and the rig geometry. Uses the same
 * {@link Bootstrap} dance as {@link TntCombatPolicyTest} (vanilla item/block registries — and their cached block
 * shapes — must exist before any {@link Items} reference). Classification is by item identity and a cache-backed
 * collision-shape query, not datapack tags, so these run on every MC version.
 */
class EndCrystalCombatPolicyTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void classifiesFullCubesAsCover() {
        assertTrue(EndCrystalCombatPolicy.isCoverBlock(new ItemStack(Items.COBBLESTONE)), "cobblestone");
        assertTrue(EndCrystalCombatPolicy.isCoverBlock(new ItemStack(Items.DIRT)), "dirt");
        assertTrue(EndCrystalCombatPolicy.isCoverBlock(new ItemStack(Items.STONE)), "stone");
        assertTrue(EndCrystalCombatPolicy.isCoverBlock(new ItemStack(Items.OBSIDIAN)), "obsidian is a cover block too");
    }

    @Test
    void rejectsPartialAndNonBlocksAsCover() {
        assertFalse(EndCrystalCombatPolicy.isCoverBlock(ItemStack.EMPTY), "empty");
        assertFalse(EndCrystalCombatPolicy.isCoverBlock(new ItemStack(Items.OAK_SLAB)), "slab (half collision)");
        assertFalse(EndCrystalCombatPolicy.isCoverBlock(new ItemStack(Items.OAK_STAIRS)), "stairs (partial collision)");
        assertFalse(EndCrystalCombatPolicy.isCoverBlock(new ItemStack(Items.OAK_FENCE)), "fence (partial collision)");
        assertFalse(EndCrystalCombatPolicy.isCoverBlock(new ItemStack(Items.TORCH)), "torch (no collision)");
        assertFalse(EndCrystalCombatPolicy.isCoverBlock(new ItemStack(Items.END_CRYSTAL)), "end crystal is not a block");
        assertFalse(EndCrystalCombatPolicy.isCoverBlock(new ItemStack(Items.DIAMOND_SWORD)), "weapon");
        assertFalse(EndCrystalCombatPolicy.isCoverBlock(new ItemStack(Items.SHIELD)), "shield is not a block");
    }

    @Test
    void countsSolidCoverAcrossSlots() {
        SimpleContainer pack = new SimpleContainer(6);
        pack.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
        pack.setItem(1, new ItemStack(Items.OBSIDIAN, 2));   // obsidian counts as cover
        pack.setItem(2, new ItemStack(Items.OAK_SLAB, 5));   // excluded — partial
        pack.setItem(3, new ItemStack(Items.DIAMOND_SWORD)); // excluded — not a block
        assertEquals(5, EndCrystalCombatPolicy.solidCoverCount(pack), "3 cobblestone + 2 obsidian");
    }

    @Test
    void findsCrystalObsidianAndShieldSlots() {
        SimpleContainer pack = new SimpleContainer(5);
        pack.setItem(0, new ItemStack(Items.DIRT));
        pack.setItem(1, new ItemStack(Items.OBSIDIAN, 4));
        pack.setItem(2, new ItemStack(Items.END_CRYSTAL, 3));
        pack.setItem(3, new ItemStack(Items.SHIELD));
        assertEquals(2, EndCrystalCombatPolicy.firstCrystalSlot(pack), "crystal slot");
        assertEquals(1, EndCrystalCombatPolicy.firstObsidianSlot(pack), "obsidian slot");
        assertEquals(3, EndCrystalCombatPolicy.firstShieldSlot(pack), "shield slot");

        SimpleContainer bare = new SimpleContainer(2);
        bare.setItem(0, new ItemStack(Items.COBBLESTONE));
        assertEquals(-1, EndCrystalCombatPolicy.firstCrystalSlot(bare), "no crystal → -1");
        assertEquals(-1, EndCrystalCombatPolicy.firstObsidianSlot(bare), "no obsidian → -1");
        assertEquals(-1, EndCrystalCombatPolicy.firstShieldSlot(bare), "no shield → -1");
    }

    @Test
    void selectCoverPrefersNonObsidian() {
        SimpleContainer mixed = new SimpleContainer(3);
        mixed.setItem(0, new ItemStack(Items.OBSIDIAN, 2));
        mixed.setItem(1, new ItemStack(Items.COBBLESTONE, 4));
        assertEquals(1, EndCrystalCombatPolicy.selectCoverSlot(mixed), "picks cobblestone over obsidian");

        SimpleContainer onlyObsidian = new SimpleContainer(2);
        onlyObsidian.setItem(0, new ItemStack(Items.END_CRYSTAL));
        onlyObsidian.setItem(1, new ItemStack(Items.OBSIDIAN, 2));
        assertEquals(1, EndCrystalCombatPolicy.selectCoverSlot(onlyObsidian), "falls back to obsidian when it's the only cover");

        SimpleContainer noCover = new SimpleContainer(2);
        noCover.setItem(0, new ItemStack(Items.END_CRYSTAL));
        noCover.setItem(1, new ItemStack(Items.OAK_SLAB));
        assertEquals(-1, EndCrystalCombatPolicy.selectCoverSlot(noCover), "no full-cube cover → -1");
    }

    @Test
    void hasKitRequiresCrystalBaseAndTwoTallCover() {
        // crystal + 1 obsidian (base) + 2 cobblestone (the 2-tall cover) → armed (3 solid blocks)
        assertTrue(EndCrystalCombatPolicy.hasKit(kit(1, 1, 2)), "crystal + obsidian + 2 cobblestone");
        // crystal + 1 obsidian + 1 cobblestone: only 2 solid blocks → not enough for base + a 2-tall cover
        assertFalse(EndCrystalCombatPolicy.hasKit(kit(1, 1, 1)), "crystal + obsidian + 1 cobblestone (2 solid)");
        // crystal + 3 obsidian: obsidian doubles as the two cover blocks → armed (3 solid blocks)
        assertTrue(EndCrystalCombatPolicy.hasKit(kit(1, 3, 0)), "crystal + 3 obsidian (obsidian doubles as cover)");
        // crystal + 2 obsidian: only 2 solid blocks → not armed
        assertFalse(EndCrystalCombatPolicy.hasKit(kit(1, 2, 0)), "crystal + 2 obsidian (2 solid)");
        // no obsidian for the base, even with plenty of cover → not armed
        assertFalse(EndCrystalCombatPolicy.hasKit(kit(1, 0, 4)), "crystal + cobblestone but no obsidian base");
        // no crystal → not armed
        assertFalse(EndCrystalCombatPolicy.hasKit(kit(0, 3, 3)), "obsidian + cobblestone but no crystal");
    }

    @Test
    void candidatesLeadTargetwardWithCoverBetween() {
        BlockPos mob = new BlockPos(0, 64, 0);

        List<Layout> east = EndCrystalCombatPolicy.placementCandidates(mob, new BlockPos(3, 64, 0)); // due east
        assertEquals(new BlockPos(1, 64, 0), east.get(0).wall(), "best candidate: cover one step east (toward target)");
        assertEquals(new BlockPos(2, 64, 0), east.get(0).base(), "best candidate: base one step beyond the cover");
        assertTrue(east.size() >= 3, "several fallback facings offered, not just one");

        // every candidate keeps the cover halfway between the mob and the base, and never points away from the target
        for (Layout c : east) {
            int wdx = c.wall().getX() - mob.getX();
            int wdz = c.wall().getZ() - mob.getZ();
            assertEquals(new BlockPos(mob.getX() + 2 * wdx, mob.getY(), mob.getZ() + 2 * wdz), c.base(),
                "cover sits halfway between mob and base");
            assertTrue(wdx >= 0, "no rear-facing candidate for an eastward target");
        }

        List<Layout> ne = EndCrystalCombatPolicy.placementCandidates(mob, new BlockPos(5, 64, 5)); // north-east
        assertEquals(new BlockPos(1, 64, 1), ne.get(0).wall(), "best candidate faces diagonally toward the target");
        assertEquals(new BlockPos(2, 64, 2), ne.get(0).base(), "diagonal base");
    }

    @Test
    void candidatesDefaultNorthWhenMobOnTarget() {
        BlockPos same = new BlockPos(5, 70, 5);
        List<Layout> c = EndCrystalCombatPolicy.placementCandidates(same, same);
        assertEquals(8, c.size(), "no direction → all eight facings offered");
        assertEquals(new BlockPos(5, 70, 4), c.get(0).wall(), "north cover first");
        assertEquals(new BlockPos(5, 70, 3), c.get(0).base(), "north base two out");
    }

    /** A backpack with {@code crystals} end crystals, {@code obsidian} obsidian, and {@code cobble} cobblestone. */
    private static SimpleContainer kit(int crystals, int obsidian, int cobble) {
        SimpleContainer pack = new SimpleContainer(3);
        if (crystals > 0) pack.setItem(0, new ItemStack(Items.END_CRYSTAL, crystals));
        if (obsidian > 0) pack.setItem(1, new ItemStack(Items.OBSIDIAN, obsidian));
        if (cobble > 0) pack.setItem(2, new ItemStack(Items.COBBLESTONE, cobble));
        return pack;
    }
}
