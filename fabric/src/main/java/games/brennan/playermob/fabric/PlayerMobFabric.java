package games.brennan.playermob.fabric;

import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.entity.Personality;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.menu.PlayerMobMenu;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.CreativeModeTabs;

/**
 * Fabric loader entrypoint. Registers the entity type, the random spawn egg
 * plus the five player-facing personality archetype eggs, default attributes,
 * and creative-tab placement using Fabric-native APIs, then defers to
 * {@link PlayerMob#init()} for shared post-registration logic.
 *
 * <p>Egg colours, the {@code entity_data} payload, and the {@code SpawnEggItem}
 * construction all live in {@link PlayerMobRegistry} so the three loaders share
 * one source of truth — see {@link PlayerMobRegistry#createPersonalitySpawnEgg}.</p>
 */
public final class PlayerMobFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // Entity type — common builder, fabric registration.
        EntityType<PlayerMobEntity> entityType =
            PlayerMobRegistry.entityTypeBuilder()
                .build(PlayerMobRegistry.PLAYER_MOB_ID.toString());
        PlayerMobRegistry.PLAYER_MOB = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            PlayerMobRegistry.PLAYER_MOB_ID,
            entityType);

        // Default attribute supplier — must be registered before the first
        // entity of this type is constructed.
        FabricDefaultAttributeRegistry.register(
            PlayerMobRegistry.PLAYER_MOB,
            PlayerMobEntity.createAttributes());

        // Random (default) spawn egg — rolls every category at spawn.
        PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG = Registry.register(
            BuiltInRegistries.ITEM,
            PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG_ID,
            PlayerMobRegistry.createRandomSpawnEgg(entityType));

        // Player-facing archetype eggs — one per personality.
        for (Personality personality : Personality.values()) {
            Registry.register(
                BuiltInRegistries.ITEM,
                PlayerMobRegistry.personalitySpawnEggId(personality),
                PlayerMobRegistry.createPersonalitySpawnEgg(entityType, personality));
        }

        // Drop every egg into the Spawn Eggs creative tab.
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS).register(entries -> {
            entries.accept(PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG);
            for (Personality personality : Personality.values()) {
                entries.accept(BuiltInRegistries.ITEM.get(
                    PlayerMobRegistry.personalitySpawnEggId(personality)));
            }
        });

        // Inventory menu — the extended type carries the target mob's entity id
        // to the client via the VarInt StreamCodec.
        PlayerMobRegistry.PLAYER_MOB_MENU = Registry.register(
            BuiltInRegistries.MENU,
            PlayerMobRegistry.PLAYER_MOB_MENU_ID,
            new ExtendedScreenHandlerType<>(
                (syncId, inv, entityId) -> PlayerMobMenu.fromEntityId(syncId, inv, entityId),
                ByteBufCodecs.VAR_INT));

        // Opener — Fabric serialises getScreenOpeningData() through the type's
        // StreamCodec, so a plain openMenu(provider) is enough.
        PlayerMobRegistry.MENU_OPENER = (serverPlayer, mob) ->
            serverPlayer.openMenu(new PlayerMobMenuProvider(mob));

        // Datapack-extensible skin pack — loads data/<ns>/playermob_skins/*.json
        // into PlayerMobSkinRegistry. Fabric's ResourceManagerHelper is the loader-
        // native equivalent of vanilla's addReloadListener on the server data type.
        ResourceLocation skinListenerId =
            ResourceLocation.fromNamespaceAndPath(PlayerMob.MOD_ID, "skins");
        ResourceManagerHelper.get(PackType.SERVER_DATA)
            .registerReloadListener(
                new games.brennan.playermob.fabric.SkinReloadListenerWrapper(skinListenerId));

        PlayerMob.init();
    }
}
