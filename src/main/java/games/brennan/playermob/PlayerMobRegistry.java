package games.brennan.playermob;

import games.brennan.playermob.entity.Archetype;
import games.brennan.playermob.entity.DispositionTraits;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.compat.RegistryCompat;
import games.brennan.playermob.menu.PlayerMobMenu;
import games.brennan.playermob.menu.PlayerMobMenuOpener;
import net.minecraft.nbt.CompoundTag;
//? if >=26 {
/*import net.minecraft.resources.Identifier;
*///?} else {
import net.minecraft.resources.ResourceLocation;
//?}
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

import java.util.Locale;

/**
 * Common registration holder.
 *
 * <p>Architectury API 13.x (the 1.21 line) ships Fabric + NeoForge only — Forge
 * was dropped upstream. To keep all three loaders working we follow
 * {@code AdventureItemNames}' pattern: registration is performed in each
 * loader's entry class using that loader's native API, and the resulting
 * {@link EntityType} / {@link Item} references are stored here for shared
 * code to use (goal predicates, renderer registration, etc.).</p>
 *
 * <p>The fields are <em>set once</em> by the loader entry on boot and never
 * mutated thereafter. They are nullable until that boot moment — anything in
 * common that reads them must run after {@link PlayerMob#init()} completes.</p>
 */
public final class PlayerMobRegistry {

    public static final String PLAYER_MOB_PATH = "player_mob";
    public static final String PLAYER_MOB_SPAWN_EGG_PATH = "player_mob_spawn_egg";
    public static final String PLAYER_MOB_MENU_PATH = "player_mob_menu";

    //? if >=26 {
    /*public static final net.minecraft.resources.Identifier PLAYER_MOB_ID =
        RegistryCompat.id(PlayerMob.MOD_ID, PLAYER_MOB_PATH);

    public static final net.minecraft.resources.Identifier PLAYER_MOB_SPAWN_EGG_ID =
        RegistryCompat.id(PlayerMob.MOD_ID, PLAYER_MOB_SPAWN_EGG_PATH);

    public static final net.minecraft.resources.Identifier PLAYER_MOB_MENU_ID =
        RegistryCompat.id(PlayerMob.MOD_ID, PLAYER_MOB_MENU_PATH);
    *///?} else {
    public static final ResourceLocation PLAYER_MOB_ID =
        RegistryCompat.id(PlayerMob.MOD_ID, PLAYER_MOB_PATH);

    public static final ResourceLocation PLAYER_MOB_SPAWN_EGG_ID =
        RegistryCompat.id(PlayerMob.MOD_ID, PLAYER_MOB_SPAWN_EGG_PATH);

    public static final ResourceLocation PLAYER_MOB_MENU_ID =
        RegistryCompat.id(PlayerMob.MOD_ID, PLAYER_MOB_MENU_PATH);
    //?}

    /**
     * The registered {@link EntityType}. Populated by each loader entry class
     * during its bootstrap. Never null after {@link PlayerMob#init()} returns
     * on a properly-configured loader.
     */
    public static EntityType<PlayerMobEntity> PLAYER_MOB;

    /**
     * The registered spawn-egg item. Populated by each loader entry class.
     */
    public static Item PLAYER_MOB_SPAWN_EGG;

    /**
     * The registered {@link MenuType} for the Creative inventory screen.
     * Populated by each loader entry class (Fabric uses an extended screen
     * handler type; Forge/NeoForge use {@code IForgeMenuType}/
     * {@code IMenuTypeExtension}). Never null after that loader's boot.
     */
    public static MenuType<PlayerMobMenu> PLAYER_MOB_MENU;

    /**
     * Loader-supplied hook that opens {@link PlayerMobMenu} server-side. Set
     * by each loader entry class during boot; read at runtime by
     * {@code PlayerMobEntity.mobInteract}. See {@link PlayerMobMenuOpener}.
     */
    public static PlayerMobMenuOpener MENU_OPENER;

    private PlayerMobRegistry() {}

    /**
     * The shared {@link EntityType.Builder} every loader uses. Centralised so
     * size, mob category, and tracking range stay consistent across loaders.
     *
     * <p>Hitbox 0.6 × 1.95 matches the vanilla player hitbox so the
     * player-shaped renderer fits cleanly inside the entity bounds.</p>
     */
    public static EntityType.Builder<PlayerMobEntity> entityTypeBuilder() {
        return EntityType.Builder
            .<PlayerMobEntity>of(PlayerMobEntity::new, MobCategory.MONSTER)
            .sized(0.6F, 1.95F)
            .clientTrackingRange(8);
    }

    // ---- Spawn eggs -------------------------------------------------------

    /** Spawn-egg background colour (warm skin tone) — shared by every variant. */
    public static final int SPAWN_EGG_PRIMARY = 0xDDB897;
    /** Default/random egg spot colour (dark hair brown). */
    public static final int SPAWN_EGG_SECONDARY = 0x4F3A2A;

    /** Registry path for an archetype egg, e.g. {@code player_mob_shy_spawn_egg}. */
    public static String archetypeSpawnEggPath(Archetype archetype) {
        return "player_mob_" + archetype.name().toLowerCase(Locale.ROOT) + "_spawn_egg";
    }

    //? if >=26 {
    /*public static net.minecraft.resources.Identifier archetypeSpawnEggId(Archetype archetype) {
        return RegistryCompat.id(PlayerMob.MOD_ID, archetypeSpawnEggPath(archetype));
    }
    *///?} else {
    public static ResourceLocation archetypeSpawnEggId(Archetype archetype) {
        return RegistryCompat.id(PlayerMob.MOD_ID, archetypeSpawnEggPath(archetype));
    }
    //?}

    /**
     * The {@code entity_data} an archetype egg stamps onto the mob it spawns —
     * the preset's two trait values. Both load as explicit, so
     * {@code finalizeSpawn}'s roll leaves them untouched (see {@link DispositionTraits}).
     */
    public static CompoundTag archetypeEggData(Archetype archetype) {
        CompoundTag tag = new CompoundTag();
        // entity_data must name its entity type, or encoding the egg ItemStack throws
        // "Missing id for entity" the moment it is saved in a player inventory.
        tag.putString("id", PLAYER_MOB_ID.toString());
        tag.putInt(DispositionTraits.TAG_FIGHT_FLIGHT, archetype.fightFlight);
        tag.putInt(DispositionTraits.TAG_FRIENDLINESS, archetype.friendliness);
        return tag;
    }

    /** The fully-random spawn egg (the original default egg). */
    public static SpawnEggItem createRandomSpawnEgg(EntityType<? extends Mob> type) {
        //? if >=26 {
        /*// 26.x: the spawn egg's entity type rides the ENTITY_DATA component; egg colours are
        // data-driven from the item model rather than constructor args. The data tag is empty
        // (a fully-random egg pins nothing) but still names the type via TypedEntityData.
        // 26.x: Item.Properties must carry its registry id before the Item ctor runs
        // (the ctor calls itemIdOrThrow()), so set it here from the egg's known id.
        return new SpawnEggItem(new Item.Properties()
            .setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ITEM, PLAYER_MOB_SPAWN_EGG_ID))
            .component(
            net.minecraft.core.component.DataComponents.ENTITY_DATA,
            net.minecraft.world.item.component.TypedEntityData.of(type, new CompoundTag())));
        *///?} else {
        return new SpawnEggItem(type, SPAWN_EGG_PRIMARY, SPAWN_EGG_SECONDARY, new Item.Properties());
        //?}
    }

    /**
     * An archetype egg that pins its two trait values via the {@code entity_data}
     * component; the entity's {@code finalizeSpawn} keeps the pinned traits and
     * rolls nothing further.
     */
    public static SpawnEggItem createArchetypeSpawnEgg(EntityType<? extends Mob> type, Archetype archetype) {
        return RegistryCompat.archetypeSpawnEgg(
            type, SPAWN_EGG_PRIMARY, archetype.eggColor, archetypeEggData(archetype),
            archetypeSpawnEggId(archetype));
    }
}
