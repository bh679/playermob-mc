package games.brennan.playermob.testutil;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Boots the vanilla Minecraft registries <em>once, before any test class loads</em>.
 *
 * <p>Many tests reference registry-backed classes ({@link net.minecraft.world.item.ItemStack},
 * {@code Registries}, {@code BuiltInRegistries}). Most do the {@code SharedConstants.tryDetectVersion()}
 * + {@code Bootstrap.bootStrap()} dance in a per-class {@code @BeforeAll}, but a few that only touch
 * registries transitively (e.g. {@code PlayerMobConfigTest} → {@code WantedItemList.parse} →
 * {@code Registries}) did not. In the shared Gradle test worker, if such a test runs <em>before</em>
 * any bootstrapping test, the first reference triggers {@code Registries.<clinit>} on an un-booted
 * registry, which throws and permanently poisons the class — every later test in that JVM then
 * cascades into {@code NoClassDefFoundError: Could not initialize class net.minecraft...}. Gradle's
 * default test-class order differs per Stonecutter version node, so this surfaced on 1.20.1 / 26.2
 * but not on 1.21.1.</p>
 *
 * <p>Registered via {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}
 * so the JUnit Platform discovers it through {@link java.util.ServiceLoader} and invokes
 * {@link #launcherSessionOpened} before the test plan runs. The per-class {@code @BeforeAll} blocks
 * remain in place; {@code Bootstrap.bootStrap()} is internally idempotent, so this is purely
 * additive — it just removes the ordering dependency.</p>
 */
public final class MinecraftBootstrapListener implements LauncherSessionListener {

    private static boolean booted = false;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (booted) {
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        //? if >=26 {
        /*// MC 26.x moved item data-component binding out of Bootstrap and into the datapack
        // reload pipeline (DataComponentInitializers, applied by ReloadableServerResources).
        // Without it, `new ItemStack(Items.X)` throws "Components not bound yet" because the
        // item's registry Holder never had its components bound. Replicate just that bind pass
        // against the vanilla lookup so registry-backed tests can build ItemStacks. (1.20.1 /
        // 1.21.1 bind components during Bootstrap, so this block is stripped there.)
        net.minecraft.core.HolderLookup.Provider lookup =
            net.minecraft.data.registries.VanillaRegistries.createLookup();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup)
            .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        *///?}
        booted = true;
    }
}
