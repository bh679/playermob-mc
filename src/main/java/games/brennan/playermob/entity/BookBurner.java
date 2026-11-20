package games.brennan.playermob.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A finished book goes the way a player's does in Dungeon Train: dropped, it catches fire, burns for
 * a moment and is gone. Owned by the mob that read it, which ticks the burn so it plays out like
 * Dungeon Train's own — flame and smoke around the book for {@link #BURN_TICKS}, then an extinguish
 * hiss and a puff of smoke as it's consumed.
 *
 * <p>The dropped book can never be picked up, and it is genuinely on fire, so if the mob dies or its
 * chunk unloads mid-burn, vanilla fire damage still destroys it a few seconds later: a read can
 * never leave a book behind for anyone to take (a copy read in place of a shelf's book is conjured).</p>
 */
public final class BookBurner {

    /** How long a dropped book burns before it's consumed (3 s, matching Dungeon Train). */
    static final int BURN_TICKS = 60;
    /** Fire on the item itself — the visible flames, and the fallback that destroys it untended. */
    static final int FIRE_TICKS = 160;

    private record Burning(ItemEntity item, int[] ticksLeft) {}

    private final List<Burning> burning = new ArrayList<>();

    /** Drop {@code book} at {@code mob}'s feet, alight. A no-op for an empty stack. */
    public void dropBurning(PlayerMobEntity mob, ItemStack book) {
        if (book.isEmpty() || !(mob.level() instanceof ServerLevel level)) {
            return;
        }
        Vec3 look = mob.getLookAngle();
        ItemEntity item = new ItemEntity(level, mob.getX() + look.x * 0.4, mob.getEyeY() - 0.5,
            mob.getZ() + look.z * 0.4, book);
        item.setDeltaMovement(look.x * 0.08, 0.1, look.z * 0.08);
        item.setNeverPickUp();
        item.setRemainingFireTicks(FIRE_TICKS);
        level.addFreshEntity(item);
        level.playSound(null, item.getX(), item.getY(), item.getZ(),
            SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 0.5F, 1.4F);
        burning.add(new Burning(item, new int[] {BURN_TICKS}));
    }

    /** Advance every burn this mob started. Server side, once per tick. */
    public void tick() {
        if (burning.isEmpty()) {
            return;
        }
        Iterator<Burning> it = burning.iterator();
        while (it.hasNext()) {
            Burning b = it.next();
            ItemEntity item = b.item();
            if (item.isRemoved() || !(item.level() instanceof ServerLevel level)) {
                it.remove();
                continue;
            }
            if (item.tickCount % 2 == 0) {
                level.sendParticles(ParticleTypes.FLAME, item.getX(), item.getY() + 0.2, item.getZ(),
                    3, 0.12, 0.08, 0.12, 0.02);
                level.sendParticles(ParticleTypes.SMOKE, item.getX(), item.getY() + 0.3, item.getZ(),
                    1, 0.08, 0.05, 0.08, 0.01);
            }
            if (--b.ticksLeft()[0] <= 0) {
                level.playSound(null, item.getX(), item.getY(), item.getZ(),
                    SoundEvents.FIRE_EXTINGUISH, SoundSource.NEUTRAL, 0.6F, 1.2F);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, item.getX(), item.getY() + 0.2, item.getZ(),
                    8, 0.2, 0.1, 0.2, 0.02);
                item.discard();
                it.remove();
            }
        }
    }
}
