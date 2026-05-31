package games.brennan.playermob.fabric;

import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.skin.PlayerMobSkinReloadListener;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

/**
 * Fabric loader entrypoint. Registers the entity type, spawn egg item, default
 * attributes, and creative-tab placement using Fabric-native APIs, then defers
 * to {@link PlayerMob#init()} for shared post-registration logic.
 *
 * <p>Architectury API runtime is not used (its 1.21 line is Fabric+NeoForge
 * only — see {@code PlayerMobRegistry} javadoc) so each loader carries its
 * own short registration block.</p>
 */
public final class PlayerMobFabric implements ModInitializer {

    /**
     * Spawn-egg primary (background) colour: warm skin tone.
     */
    private static final int SPAWN_EGG_PRIMARY = 0xDDB897;
    /**
     * Spawn-egg secondary (spots) colour: dark hair brown.
     */
    private static final int SPAWN_EGG_SECONDARY = 0x4F3A2A;

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

        // Spawn-egg item.
        Item spawnEgg = new SpawnEggItem(
            PlayerMobRegistry.PLAYER_MOB,
            SPAWN_EGG_PRIMARY, SPAWN_EGG_SECONDARY,
            new Item.Properties());
        PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG = Registry.register(
            BuiltInRegistries.ITEM,
            PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG_ID,
            spawnEgg);

        // Drop the spawn egg into the Spawn Eggs creative tab.
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS)
            .register(entries -> entries.accept(PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG));

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
