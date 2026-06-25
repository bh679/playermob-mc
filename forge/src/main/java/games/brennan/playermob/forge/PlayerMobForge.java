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
import net.minecraft.world.item.SpawnEggItem;
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
        ITEMS.register(PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG_PATH, PlayerMobForge::randomSpawnEgg);

    // Player-facing archetype eggs — one per preset. Registered for their
    // side effect; resolved from BuiltInRegistries at tab-build time.
    static {
        for (Archetype archetype : Archetype.values()) {
            ITEMS.register(PlayerMobRegistry.archetypeSpawnEggPath(archetype),
                () -> archetypeSpawnEgg(archetype));
        }
    }

    public static final RegistryObject<MenuType<PlayerMobMenu>> PLAYER_MOB_MENU =
        MENUS.register(PlayerMobRegistry.PLAYER_MOB_MENU_PATH, () ->
            IForgeMenuType.create((windowId, inv, buf) ->
                PlayerMobMenu.fromEntityId(windowId, inv, buf.readVarInt())));

    //? if >=1.21.1 {
    public PlayerMobForge(IEventBus modBus) {
        wire(modBus);
    }
    //?} else {
    /*// Forge 47.x (1.20.1) constructs @Mod classes via the no-arg constructor and
    // requires it to exist — the mod event bus is fetched from the loading context
    // rather than injected. Without this, FMLModContainer.constructMod throws
    // NoSuchMethodException: PlayerMobForge.<init>() and mod loading aborts (issue #106).
    public PlayerMobForge() {
        wire(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
    }
    *///?}

    private void wire(IEventBus modBus) {
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
     * Builds the random spawn egg. On Forge 52.x (1.21.1) the entity type is already
     * registered when the ITEM {@code RegisterEvent} fires, so the resolved type goes
     * straight to the shared factory. On Forge 47.x (1.20.1) the ITEM event fires
     * <em>before</em> ENTITY_TYPE, so {@code PLAYER_MOB.get()} would NPE during item
     * registration — instead the egg is a {@code ForgeSpawnEggItem} backed by the
     * {@code PLAYER_MOB} supplier, which resolves the type lazily at spawn time (issue #106).
     */
    private static SpawnEggItem randomSpawnEgg() {
        //? if >=1.21.1 {
        return PlayerMobRegistry.createRandomSpawnEgg(PLAYER_MOB.get());
        //?} else {
        /*return new net.minecraftforge.common.ForgeSpawnEggItem(PLAYER_MOB,
            PlayerMobRegistry.SPAWN_EGG_PRIMARY, PlayerMobRegistry.SPAWN_EGG_SECONDARY,
            new Item.Properties());*/
        //?}
    }

    /**
     * Builds an archetype spawn egg, pinning its two trait values via {@code entity_data}.
     * Same 47.x-vs-52.x split as {@link #randomSpawnEgg()}: on 1.20.1 the lazy
     * {@code ForgeSpawnEggItem} stamps the trait NBT under the vanilla {@code "EntityTag"}
     * key in {@code getDefaultInstance}, mirroring the 1.20.1 path in {@code RegistryCompat}.
     */
    private static SpawnEggItem archetypeSpawnEgg(Archetype archetype) {
        //? if >=1.21.1 {
        return PlayerMobRegistry.createArchetypeSpawnEgg(PLAYER_MOB.get(), archetype);
        //?} else {
        /*net.minecraft.nbt.CompoundTag entityData = PlayerMobRegistry.archetypeEggData(archetype);
        return new net.minecraftforge.common.ForgeSpawnEggItem(PLAYER_MOB,
                PlayerMobRegistry.SPAWN_EGG_PRIMARY, archetype.eggColor, new Item.Properties()) {
            @Override
            public net.minecraft.world.item.ItemStack getDefaultInstance() {
                net.minecraft.world.item.ItemStack stack = super.getDefaultInstance();
                games.brennan.playermob.compat.RegistryCompat.ensureArchetypeEntityTag(stack, entityData);
                return stack;
            }
            // new ItemStack(item) (creative tab / bare /give / pick-block) skips
            // getDefaultInstance, so the stack carries no EntityTag — inject it (only when
            // absent) before vanilla reads the stack tag at spawn, so the archetype pins.
            @Override
            public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
                games.brennan.playermob.compat.RegistryCompat.ensureArchetypeEntityTag(context.getItemInHand(), entityData);
                return super.useOn(context);
            }
            @Override
            public net.minecraft.world.InteractionResultHolder<net.minecraft.world.item.ItemStack> use(
                    net.minecraft.world.level.Level level,
                    net.minecraft.world.entity.player.Player player,
                    net.minecraft.world.InteractionHand hand) {
                games.brennan.playermob.compat.RegistryCompat.ensureArchetypeEntityTag(player.getItemInHand(hand), entityData);
                return super.use(level, player, hand);
            }
        };*/
        //?}
    }

    /**
     * Wire the skin-pack reload listener — datapack hook for adding skins via
     * {@code data/<ns>/playermob_skins/*.json}.
     */
    private static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new PlayerMobSkinReloadListener());
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        ReincarnateCommand.register(event.getDispatcher(), event.getBuildContext());
    }

    private static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(PLAYER_MOB.get(), PlayerMobEntity.createAttributes().build());
    }

    private static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            // Add via getDefaultInstance() so the archetype eggs carry their entity_data /
            // EntityTag (on 1.20.1 a bare item built by new ItemStack would drop it).
            event.accept(PLAYER_MOB_SPAWN_EGG.get().getDefaultInstance());
            for (Archetype archetype : Archetype.values()) {
                event.accept(BuiltInRegistries.ITEM.get(
                    PlayerMobRegistry.archetypeSpawnEggId(archetype)).getDefaultInstance());
            }
        }
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        PlayerMobRegistry.PLAYER_MOB = PLAYER_MOB.get();
        PlayerMobRegistry.PLAYER_MOB_SPAWN_EGG = PLAYER_MOB_SPAWN_EGG.get();
        PlayerMobRegistry.PLAYER_MOB_MENU = PLAYER_MOB_MENU.get();
        PlayerMobRegistry.MENU_OPENER = (serverPlayer, mob) -> {
            SimpleMenuProvider provider = new SimpleMenuProvider(
                (id, inv, player) -> new PlayerMobMenu(id, inv, mob),
                Component.translatable("container.playermob.player_mob"));
            //? if >=1.21.1 {
            serverPlayer.openMenu(provider, buf -> buf.writeVarInt(mob.getId()));
            //?} else {
            /*// Forge 47.x (1.20.1) ServerPlayer.openMenu takes no extra-data writer;
            // the buffer-carrying open goes through NetworkHooks.openScreen.
            net.minecraftforge.network.NetworkHooks.openScreen(
                serverPlayer, provider, buf -> buf.writeVarInt(mob.getId()));*/
            //?}
        };
        // Forge 1.20.1 ships FakePlayerFactory (used for `/playermob order ... use`); Forge 1.21.1
        // (52.x) dropped it, so that node falls back to the common fake player.
        //? if <1.21.1 {
        /*games.brennan.playermob.compat.FakePlayerSource.install(
            net.minecraftforge.common.util.FakePlayerFactory::getMinecraft);
        *///?}

        PlayerMob.init(FMLPaths.CONFIGDIR.get());
    }
}
