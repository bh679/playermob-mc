package games.brennan.playermob;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;

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

    public static final ResourceLocation PLAYER_MOB_ID =
        ResourceLocation.fromNamespaceAndPath(PlayerMob.MOD_ID, PLAYER_MOB_PATH);

    public static final ResourceLocation PLAYER_MOB_SPAWN_EGG_ID =
        ResourceLocation.fromNamespaceAndPath(PlayerMob.MOD_ID, PLAYER_MOB_SPAWN_EGG_PATH);

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
}
