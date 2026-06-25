package games.brennan.playermob.neoforge;

import games.brennan.playermob.PlayerMob;
import games.brennan.playermob.PlayerMobRegistry;
import games.brennan.playermob.compat.TrainConfinement;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
//? if >=26 {
/*import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
*///?} else {
import net.neoforged.neoforge.event.AddReloadListenerEvent;
//?}
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * NeoForge loader entrypoint. Registers the entity type, the random spawn egg,
 * the five player-facing archetype eggs (in a static block),
 * attributes, and creative-tab placement via DeferredRegister + the matching
 * event-bus listeners, then hands off to {@link PlayerMob#init} once
 * registration completes (FMLCommonSetupEvent).
 *
 * <p>Egg colours / {@code entity_data} are built by {@link PlayerMobRegistry};
 * the archetype eggs are resolved from the item registry at creative-tab time
 * to avoid threading their {@link DeferredItem} generics through a collection.</p>
 */
@Mod(PlayerMob.MOD_ID)
public final class PlayerMobNeoForge {

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, PlayerMob.MOD_ID);
    private static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(PlayerMob.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS =
        DeferredRegister.create(Registries.MENU, PlayerMob.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<PlayerMobEntity>> PLAYER_MOB =
        ENTITY_TYPES.register(PlayerMobRegistry.PLAYER_MOB_PATH, () ->
            //? if >=26 {
            /*PlayerMobRegistry.entityTypeBuilder().build(
                ResourceKey.create(Registries.ENTITY_TYPE, PlayerMobRegistry.PLAYER_MOB_ID)));
            *///?} else {
            PlayerMobRegistry.entityTypeBuilder().build(PlayerMobRegistry.PLAYER_MOB_PATH));
            //?}

    public static final DeferredItem<Item> PLAYER_MOB_SPAWN_EGG =
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

    public static final DeferredHolder<MenuType<?>, MenuType<PlayerMobMenu>> PLAYER_MOB_MENU =
        MENUS.register(PlayerMobRegistry.PLAYER_MOB_MENU_PATH, () ->
            IMenuTypeExtension.create((syncId, inv, buf) ->
                PlayerMobMenu.fromEntityId(syncId, inv, buf.readVarInt())));

    public PlayerMobNeoForge(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
        ITEMS.register(modBus);
        MENUS.register(modBus);

        modBus.addListener(PlayerMobNeoForge::onEntityAttributeCreation);
        modBus.addListener(PlayerMobNeoForge::onBuildCreativeTab);
        modBus.addListener(PlayerMobNeoForge::onCommonSetup);

        // Reload listeners + commands live on the game bus, not the mod bus.
        NeoForge.EVENT_BUS.addListener(PlayerMobNeoForge::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(PlayerMobNeoForge::onRegisterCommands);

        //? if >=26 {
        /*// 26.x replaced the FMLEnvironment.dist public field with the FMLEnvironment.getDist() accessor.
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
        *///?} else {
        if (FMLEnvironment.dist == Dist.CLIENT) {
        //?}
            games.brennan.playermob.neoforge.client.PlayerMobNeoForgeClient.register(modBus);
        }
    }

    /**
     * Wire the skin-pack reload listener — datapack hook for adding skins via
     * {@code data/<ns>/playermob_skins/*.json}.
     */
    //? if >=26 {
    /*// 26.x renamed AddReloadListenerEvent → AddServerReloadListenersEvent, and addListener now
    // takes an explicit Identifier id alongside the listener (it's a dependency-sortable registry,
    // like Fabric's IdentifiableResourceReloadListener). Reuse the same `playermob:skins` id the
    // Fabric wrapper registers under, via the loader-agnostic RegistryCompat.id (Identifier on 26).
    private static void onAddReloadListeners(AddServerReloadListenersEvent event) {
        event.addListener(
            games.brennan.playermob.compat.RegistryCompat.id(PlayerMob.MOD_ID, "skins"),
            new PlayerMobSkinReloadListener());
    }
    *///?} else {
    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new PlayerMobSkinReloadListener());
    }
    //?}

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        ReincarnateCommand.register(event.getDispatcher(), event.getBuildContext());
    }

    private static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(PLAYER_MOB.get(), PlayerMobEntity.createAttributes().build());
    }

    private static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(PLAYER_MOB_SPAWN_EGG.get());
            for (Archetype archetype : Archetype.values()) {
                //? if >=26 {
                /*// 26.x: Registry.get(Identifier) now returns Optional<Holder.Reference<Item>>;
                // getValue(Identifier) is the direct "give me the Item" accessor the tab's
                // accept(ItemLike) wants (the archetype egg is always registered, so it's non-null).
                event.accept(BuiltInRegistries.ITEM.getValue(
                    PlayerMobRegistry.archetypeSpawnEggId(archetype)));
                *///?} else {
                event.accept(BuiltInRegistries.ITEM.get(
                    PlayerMobRegistry.archetypeSpawnEggId(archetype)));
                //?}
            }
        }
    }

    /**
     * Fires after all DeferredRegisters have run, so {@code .get()} is safe.
     * Backfills the common-side static refs and triggers shared init.
     */
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

        // Optional Dungeon Train integration. DT is NeoForge-only and optional;
        // gate on its presence so DungeonTrainEnvironment (and the Dungeon Train
        // symbols it imports) is only ever classloaded when DT is installed.
        if (ModList.get().isLoaded("dungeontrain")) {
            installDungeonTrain();
        }
        PlayerMob.init(FMLPaths.CONFIGDIR.get());
    }

    /**
     * Install the Dungeon Train-backed train environment. Isolated in its own
     * method and referenced only from behind the {@code isLoaded("dungeontrain")}
     * guard, so the NeoForge compat class and its Dungeon Train imports are never
     * classloaded when DT is absent.
     */
    private static void installDungeonTrain() {
        //? if <26 {
        // Dungeon Train is a NeoForge-1.21.1-only optional dependency (see build.gradle.kts) and
        // can never load on MC 26.x — so on >=26 this method is an empty no-op and never installs a
        // train environment. The runtime ModList.isLoaded("dungeontrain") guard at the call site
        // already keeps it from running, but the compile reference to DungeonTrainEnvironment is
        // dropped on 26 too. With no environment installed, TrainConfinement stays on its ABSENT
        // default — the same path every non-DT world already runs, so the mod still functions.
        TrainConfinement.install(new games.brennan.playermob.neoforge.compat.DungeonTrainEnvironment());
        //?}
    }
}
