package games.brennan.playermob.fabric;

import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.entity.Personality;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
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

        PlayerMob.init();
    }
}
