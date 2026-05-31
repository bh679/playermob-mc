package games.brennan.playermob.forge;

import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.entity.Personality;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Forge loader entrypoint. Mirrors {@code PlayerMobNeoForge} with Forge-flavour
 * imports. The Architectury API doesn't ship a Forge build for 1.21.x, so
 * registration lives per-loader using Forge's DeferredRegister.
 *
 * <p>Registers the entity type, the random spawn egg, and the five player-facing
 * personality archetype eggs (in a static block); egg colours / {@code entity_data}
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

    public static final RegistryObject<EntityType<PlayerMobEntity>> PLAYER_MOB =
        ENTITY_TYPES.register(PlayerMobRegistry.PLAYER_MOB_PATH, () ->
            PlayerMobRegistry.entityTypeBuilder().build(PlayerMobRegistry.PLAYER_MOB_PATH));

    public static final RegistryObject<Item> PLAYER_MOB_SPAWN_EGG =
        ITEMS.register(PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG_PATH, () ->
            PlayerMobRegistry.createRandomSpawnEgg(PLAYER_MOB.get()));

    // Player-facing archetype eggs — one per personality. Registered for their
    // side effect; resolved from BuiltInRegistries at tab-build time.
    static {
        for (Personality personality : Personality.values()) {
            ITEMS.register(PlayerMobRegistry.personalitySpawnEggPath(personality), () ->
                PlayerMobRegistry.createPersonalitySpawnEgg(PLAYER_MOB.get(), personality));
        }
    }

    public PlayerMobForge(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
        ITEMS.register(modBus);

        modBus.addListener(PlayerMobForge::onEntityAttributeCreation);
        modBus.addListener(PlayerMobForge::onBuildCreativeTab);
        modBus.addListener(PlayerMobForge::onCommonSetup);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            games.brennan.playermob.forge.client.PlayerMobForgeClient.register(modBus);
        }
    }

    private static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(PLAYER_MOB.get(), PlayerMobEntity.createAttributes().build());
    }

    private static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(PLAYER_MOB_SPAWN_EGG.get());
            for (Personality personality : Personality.values()) {
                event.accept(BuiltInRegistries.ITEM.get(
                    PlayerMobRegistry.personalitySpawnEggId(personality)));
            }
        }
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        PlayerMobRegistry.PLAYER_MOB = PLAYER_MOB.get();
        PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG = PLAYER_MOB_SPAWN_EGG.get();
        PlayerMob.init();
    }
}
