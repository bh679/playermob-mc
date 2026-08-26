package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * The water-bucket half of {@link FireBucketGoal}, shared with {@link DouseFireInPathGoal} — both
 * goals empty a bucket at a chosen spot and then scoop the water straight back up, and the two
 * should stay identical rather than drift apart as two private copies.
 *
 * <p>The source block is placed and removed directly rather than through a fake-player raytrace, so
 * the water lands exactly where the caller chose; a raytrace would put it wherever the mob happens to
 * be looking. Placing water and picking it back up is ordinary bucket use, not world-griefing, so
 * callers don't gate it on {@code mobGriefing}.</p>
 */
public final class WaterBucketUse {

    private WaterBucketUse() {}

    /** A water bucket in either hand or anywhere in the pack. */
    public static boolean hasBucketAnywhere(PlayerMobEntity mob) {
        return mob.getMainHandItem().is(Items.WATER_BUCKET)
            || mob.getOffhandItem().is(Items.WATER_BUCKET)
            || packHasBucket(mob.getInventory());
    }

    /** A water bucket somewhere in {@code pack}. */
    public static boolean packHasBucket(Container pack) {
        for (int i = 0; i < pack.getContainerSize(); i++) {
            if (pack.getItem(i).is(Items.WATER_BUCKET)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get a water bucket into the main hand — a straight swap from the off-hand, else pulled from the
     * pack. Returns whether the mob is now holding one.
     */
    public static boolean drawIntoMainHand(PlayerMobEntity mob) {
        if (mob.getMainHandItem().is(Items.WATER_BUCKET)) {
            return true;
        }
        if (mob.getOffhandItem().is(Items.WATER_BUCKET)) {
            ItemStack off = mob.getOffhandItem();
            ItemStack main = mob.getMainHandItem();
            mob.setItemSlot(EquipmentSlot.OFFHAND, main);
            mob.setItemSlot(EquipmentSlot.MAINHAND, off);
            return true;
        }
        return mob.equipWeapon(Items.WATER_BUCKET);
    }

    /** Place a water source at {@code pos} and turn the held water bucket into an empty one. */
    public static void empty(PlayerMobEntity mob, BlockPos pos) {
        Level level = mob.level();
        level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BUCKET));
        level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            SoundEvents.BUCKET_EMPTY, SoundSource.NEUTRAL, 1.0F, 1.0F);
    }

    /** Remove the water source at {@code pos} and turn the held empty bucket back into a water one. */
    public static void fill(PlayerMobEntity mob, BlockPos pos) {
        Level level = mob.level();
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WATER_BUCKET));
        level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            SoundEvents.BUCKET_FILL, SoundSource.NEUTRAL, 1.0F, 1.0F);
    }
}
