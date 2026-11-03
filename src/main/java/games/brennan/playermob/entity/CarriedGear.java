package games.brennan.playermob.entity;

import games.brennan.playermob.compat.ItemKindCompat;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
//? if >=1.21.1 {
import net.minecraft.world.item.MaceItem;
//?}
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SpectralArrowItem;
import net.minecraft.world.item.TridentItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Picks which of a PlayerMob's backpack items show on its body: up to two weapons crossed on the
 * back, up to two digging tools hanging at the thighs, a shield and a banner on the back, and a
 * quiver when it carries arrows. What it holds in its hands is drawn by the held-item layer, so
 * only the backpack is considered here.
 *
 * <p>Pure and stateless: the server runs it over the backpack and syncs the result (see
 * {@code PlayerMobEntity#refreshCarriedGear}); {@code CarriedGearLayer} draws it.</p>
 */
public final class CarriedGear {

    private CarriedGear() {}

    /** A place on the body an item is drawn. Wire order: the synced accessors follow it. */
    public enum Spot {
        /** Back, slung from the right shoulder. */
        BACK_RIGHT,
        /** Back, slung from the left shoulder — crossed over {@link #BACK_RIGHT}. */
        BACK_LEFT,
        /** Hanging at the outside of the right thigh. */
        HIP_RIGHT,
        /** Hanging at the outside of the left thigh. */
        HIP_LEFT,
        /** Flat on the back, over the slung weapons. */
        SHIELD,
        /** Standing up behind the shoulders. */
        BANNER
    }

    /** What sort of arrows show in the quiver. */
    public enum Quiver {
        NONE, ARROWS, SPECTRAL;

        public static Quiver fromOrdinal(int ordinal) {
            Quiver[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NONE;
        }
    }

    /**
     * The picked loadout. {@code spots} has one entry per {@link Spot} (in its order), each a
     * single-item copy or {@link ItemStack#EMPTY}; it is unmodifiable.
     */
    public record Loadout(List<ItemStack> spots, Quiver quiver) {

        public ItemStack at(Spot spot) {
            return spots.get(spot.ordinal());
        }
    }

    /** Nothing shown. */
    public static final Loadout NONE = new Loadout(
        Collections.nCopies(Spot.values().length, ItemStack.EMPTY), Quiver.NONE);

    private enum Kind { BACK_WEAPON, TOOL, SHIELD, BANNER, ARROWS, SPECTRAL_ARROWS, OTHER }

    /** {@link #pick(List, int, long)} limit meaning "show every gear item". */
    public static final int NO_LIMIT = -1;

    /**
     * Fills the body spots from {@code bag}, first come first served in slot order. Items past a
     * full spot are not shown; spectral arrows win the quiver over plain ones.
     */
    public static Loadout pick(List<ItemStack> bag) {
        return pick(bag, NO_LIMIT, 0L);
    }

    /**
     * As {@link #pick(List)}, but shows at most {@code limit} of the bag's gear items (each arrow
     * stack counts as one), chosen by a shuffle seeded with {@code seed} — the same bag and seed
     * always choose the same items. {@link #NO_LIMIT} shows them all; 0 shows nothing.
     */
    public static Loadout pick(List<ItemStack> bag, int limit, long seed) {
        if (limit == 0) {
            return NONE;
        }
        List<ItemStack> gear = new ArrayList<>();
        for (ItemStack stack : bag) {
            if (stack != null && !stack.isEmpty() && kindOf(stack) != Kind.OTHER) {
                gear.add(stack);
            }
        }
        if (limit > 0 && limit < gear.size()) {
            // Choose which by position, then keep the chosen ones in bag order so spots still fill
            // first come first served.
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < gear.size(); i++) {
                order.add(i);
            }
            Collections.shuffle(order, new Random(seed));
            List<Integer> chosen = new ArrayList<>(order.subList(0, limit));
            Collections.sort(chosen);
            List<ItemStack> kept = new ArrayList<>(limit);
            for (int index : chosen) {
                kept.add(gear.get(index));
            }
            gear = kept;
        }
        return fill(gear);
    }

    private static Loadout fill(List<ItemStack> gear) {
        List<ItemStack> spots = new ArrayList<>(NONE.spots());
        Quiver quiver = Quiver.NONE;
        for (ItemStack stack : gear) {
            switch (kindOf(stack)) {
                case BACK_WEAPON -> fillFirst(spots, stack, Spot.BACK_RIGHT, Spot.BACK_LEFT);
                case TOOL -> fillFirst(spots, stack, Spot.HIP_RIGHT, Spot.HIP_LEFT);
                case SHIELD -> fillFirst(spots, stack, Spot.SHIELD);
                case BANNER -> fillFirst(spots, stack, Spot.BANNER);
                case SPECTRAL_ARROWS -> quiver = Quiver.SPECTRAL;
                case ARROWS -> {
                    if (quiver == Quiver.NONE) {
                        quiver = Quiver.ARROWS;
                    }
                }
                case OTHER -> { }
            }
        }
        return new Loadout(Collections.unmodifiableList(spots), quiver);
    }

    private static void fillFirst(List<ItemStack> spots, ItemStack stack, Spot... candidates) {
        for (Spot spot : candidates) {
            if (spots.get(spot.ordinal()).isEmpty()) {
                spots.set(spot.ordinal(), stack.copyWithCount(1));
                return;
            }
        }
    }

    private static Kind kindOf(ItemStack stack) {
        if (stack.getItem() instanceof ShieldItem) {
            return Kind.SHIELD;
        }
        if (stack.getItem() instanceof BannerItem) {
            return Kind.BANNER;
        }
        if (stack.getItem() instanceof SpectralArrowItem) {
            return Kind.SPECTRAL_ARROWS;
        }
        if (stack.getItem() instanceof ArrowItem) {
            return Kind.ARROWS;
        }
        if (isBackWeapon(stack)) {
            return Kind.BACK_WEAPON;
        }
        // After the weapons: an axe is a digging tool, and hangs at the hip.
        if (ItemKindCompat.isDiggingTool(stack)) {
            return Kind.TOOL;
        }
        return Kind.OTHER;
    }

    private static boolean isBackWeapon(ItemStack stack) {
        return ItemKindCompat.isSword(stack)
            || ItemKindCompat.isSpear(stack)
            || stack.getItem() instanceof TridentItem
            || stack.getItem() instanceof BowItem
            || stack.getItem() instanceof CrossbowItem
            //? if >=1.21.1 {
            || stack.getItem() instanceof MaceItem;
            //?} else {
            /*;*///?}
    }
}
