package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link EquipmentEvaluator#addToContainer}.
 *
 * <p>The swap methods live on {@link PlayerMobEntity} (need protected
 * {@code Mob.canReplaceCurrentItem} access) — covered by the in-game Gate 2
 * smoke test. This class covers what's purely container-shaped.</p>
 *
 * <p>Vanilla classes like {@link ItemStack} and {@link SimpleContainer}
 * trigger {@link net.minecraft.core.registries.BuiltInRegistries} static
 * initialisation, which requires {@link Bootstrap#bootStrap()} first.
 * Without it, the first reference throws {@code Not bootstrapped (called
 * from registry ResourceKey[minecraft:root / minecraft:game_event])}.</p>
 */
class EquipmentEvaluatorTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        // Idempotent — safe to call multiple times across test classes.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void addToContainerHandlesEmptyInputStack() {
        // Empty input — addToContainer returns the empty stack unchanged.
        // Exercises the early-exit path (both loops bail because
        // remaining.isEmpty()).
        SimpleContainer container = new SimpleContainer(2);
        ItemStack remaining = EquipmentEvaluator.addToContainer(container, ItemStack.EMPTY);
        assertTrue(remaining.isEmpty(), "Empty input → empty result");
    }

    @Test
    void addToContainerHandlesZeroSizeContainer() {
        // Pathological: container with 0 slots. addToContainer must not
        // throw — returns the input as leftover.
        SimpleContainer container = new SimpleContainer(0);
        ItemStack remaining = EquipmentEvaluator.addToContainer(container, ItemStack.EMPTY);
        assertTrue(remaining.isEmpty(), "Zero-size container with empty input → empty");
    }
}
