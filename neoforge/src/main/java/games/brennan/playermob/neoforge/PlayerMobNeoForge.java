package games.brennan.playermob.neoforge;

import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * NeoForge loader entrypoint. Registers entity type, spawn egg, attributes,
 * and creative-tab placement via DeferredRegister + the matching event-bus
 * listeners, then hands off to {@link PlayerMob#init()} once registration
 * completes (FMLCommonSetupEvent).
 */
@Mod(PlayerMob.MOD_ID)
public final class PlayerMobNeoForge {

    private static final int SPAWN_EGG_PRIMARY = 0xDDB897;
    private static final int SPAWN_EGG_SECONDARY = 0x4F3A2A;

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, PlayerMob.MOD_ID);
    private static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(PlayerMob.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<PlayerMobEntity>> PLAYER_MOB =
        ENTITY_TYPES.register(PlayerMobRegistry.PLAYER_MOB_PATH, () ->
            PlayerMobRegistry.entityTypeBuilder().build(PlayerMobRegistry.PLAYER_MOB_PATH));

    public static final DeferredItem<Item> PLAYER_MOB_SPAWN_EGG =
        ITEMS.register(PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG_PATH, () ->
            new SpawnEggItem(PLAYER_MOB.get(),
                             SPAWN_EGG_PRIMARY, SPAWN_EGG_SECONDARY,
                             new Item.Properties()));

    public PlayerMobNeoForge(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
        ITEMS.register(modBus);

        modBus.addListener(PlayerMobNeoForge::onEntityAttributeCreation);
        modBus.addListener(PlayerMobNeoForge::onBuildCreativeTab);
        modBus.addListener(PlayerMobNeoForge::onCommonSetup);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            games.brennan.playermob.neoforge.client.PlayerMobNeoForgeClient.register(modBus);
        }
    }

    private static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(PLAYER_MOB.get(), PlayerMobEntity.createAttributes().build());
    }

    private static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(PLAYER_MOB_SPAWN_EGG.get());
        }
    }

    /**
     * Fires after all DeferredRegisters have run, so {@code .get()} is safe.
     * Backfills the common-side static refs and triggers shared init.
     */
    private static void onCommonSetup(FMLCommonSetupEvent event) {
        PlayerMobRegistry.PLAYER_MOB = PLAYER_MOB.get();
        PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG = PLAYER_MOB_SPAWN_EGG.get();
        PlayerMob.init();
    }
}
