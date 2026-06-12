package games.brennan.playermob.forge;

import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.entity.Archetype;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.menu.PlayerMobMenu;
import games.brennan.playermob.player.ReincarnateCommand;
import games.brennan.playermob.skin.PlayerMobSkinReloadListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Forge loader entrypoint. Mirrors {@code PlayerMobNeoForge} with Forge-flavour
 * imports. The Architectury API doesn't ship a Forge build for 1.21.x, so
 * registration lives per-loader using Forge's DeferredRegister.
 *
 * <p>Registers the entity type, the random spawn egg, and the five player-facing
 * archetype eggs (in a static block); egg colour / {@code entity_data}
 * are built by {@link PlayerMobRegistry}. The archetype eggs are looked back up
 * from the item registry at creative-tab time, which avoids threading their
 * {@link RegistryObject} generics through a collection.</p>
 */
@Mod(PlayerMob.MOD_ID)
public final class PlayerMobForge {

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, PlayerMob.MOD_ID);
    private static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(Registries.ITEM, PlayerMob.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(Registries.MENU, PlayerMob.MOD_ID);

    public static final RegistryObject<EntityType<PlayerMobEntity>> PLAYER_MOB =
        ENTITY_TYPES.register(PlayerMobRegistry.PLAYER_MOB_PATH, () ->
            PlayerMobRegistry.entityTypeBuilder().build(PlayerMobRegistry.PLAYER_MOB_PATH));

    public static final RegistryObject<Item> PLAYER_MOB_SPAWN_EGG =
        ITEMS.register(PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG_PATH, () ->
            PlayerMobRegistry.createRandomSpawnEgg(PLAYER_MOB.get()));

    // Player-facing archetype eggs — one per preset. Registered for their
    // side effect; resolved from BuiltInRegistries at tab-build time.
    static {
        for (Archetype archetype : Archetype.values()) {
            ITEMS.register(PlayerMobRegistry.archetypeSpawnEggPath(archetype), () ->
                PlayerMobRegistry.createArchetypeSpawnEgg(PLAYER_MOB.get(), archetype));
        }
    }

    public static final RegistryObject<MenuType<PlayerMobMenu>> PLAYER_MOB_MENU =
        MENUS.register(PlayerMobRegistry.PLAYER_MOB_MENU_PATH, () ->
            IForgeMenuType.create((windowId, inv, buf) ->
                PlayerMobMenu.fromEntityId(windowId, inv, buf.readVarInt())));

    public PlayerMobForge(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
        ITEMS.register(modBus);
        MENUS.register(modBus);

        modBus.addListener(PlayerMobForge::onEntityAttributeCreation);
        modBus.addListener(PlayerMobForge::onBuildCreativeTab);
        modBus.addListener(PlayerMobForge::onCommonSetup);

        // Reload listeners + commands live on the game bus, not the mod bus.
        MinecraftForge.EVENT_BUS.addListener(PlayerMobForge::onAddReloadListeners);
        MinecraftForge.EVENT_BUS.addListener(PlayerMobForge::onRegisterCommands);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            games.brennan.playermob.forge.client.PlayerMobForgeClient.register(modBus);
        }
    }

    /**
     * Wire the skin-pack reload listener — datapack hook for adding skins via
     * {@code data/<ns>/playermob_skins/*.json}.
     */
    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new PlayerMobSkinReloadListener());
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        ReincarnateCommand.register(event.getDispatcher());
    }

    private static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(PLAYER_MOB.get(), PlayerMobEntity.createAttributes().build());
    }

    private static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(PLAYER_MOB_SPAWN_EGG.get());
            for (Archetype archetype : Archetype.values()) {
                event.accept(BuiltInRegistries.ITEM.get(
                    PlayerMobRegistry.archetypeSpawnEggId(archetype)));
            }
        }
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        PlayerMobRegistry.PLAYER_MOB = PLAYER_MOB.get();
        PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG = PLAYER_MOB_SPAWN_EGG.get();
        PlayerMobRegistry.PLAYER_MOB_MENU = PLAYER_MOB_MENU.get();
        PlayerMobRegistry.MENU_OPENER = (serverPlayer, mob) ->
            serverPlayer.openMenu(
                new SimpleMenuProvider(
                    (id, inv, player) -> new PlayerMobMenu(id, inv, mob),
                    Component.translatable("container.playermob.player_mob")),
                buf -> buf.writeVarInt(mob.getId()));
        PlayerMob.init(FMLPaths.CONFIGDIR.get());
    }
}
