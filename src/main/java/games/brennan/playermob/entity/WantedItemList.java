package games.brennan.playermob.entity;

import games.brennan.playermob.compat.RegistryCompat;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Set;

/**
 * An admin-configured set of items a {@link PlayerMobEntity} should "always want" — the
 * config-driven companion to the hardcoded {@link ItemPickupPolicy} categories. Lets a pack
 * make modded loot the mob can't equip or eat (e.g. a mod's gun ammo) a first-class pickup,
 * hoarded in the backpack exactly like the curated {@code VALUABLES}.
 *
 * <p>Parsed once from the {@code extraPickupItems} config line (see {@code PlayerMobConfig}).
 * Each entry is either an <b>item id</b> (e.g. {@code scguns:assault_rifle}) or an <b>item
 * tag</b> prefixed with {@code #} (e.g. {@code #scguns:ammo}); a bare path with no namespace
 * defaults to {@code minecraft}. Entries are comma- and/or whitespace-separated. A malformed
 * entry is skipped rather than failing the whole list — config never breaks mod init.</p>
 *
 * <p>Immutable after {@link #parse}. Id membership is tested against the item's registry key
 * string ({@code namespace:path}); tag membership via {@link ItemStack#is(TagKey)}, which
 * resolves lazily at runtime against the loaded datapacks (so the list is built at startup,
 * before any world's tags exist, without needing them).</p>
 */
public final class WantedItemList {

    /** The do-nothing list — matches nothing. The default when {@code extraPickupItems} is blank. */
    public static final WantedItemList EMPTY = new WantedItemList(Set.of(), Set.of());

    private final Set<String> itemIds;
    private final Set<TagKey<Item>> tags;

    private WantedItemList(Set<String> itemIds, Set<TagKey<Item>> tags) {
        this.itemIds = itemIds;
        this.tags = tags;
    }

    /**
     * Parse a config line into a list. {@code null} / blank yields {@link #EMPTY}. Tokens are
     * split on commas and whitespace; a {@code #}-prefixed token is an item tag, anything else
     * an item id. Unparseable tokens are dropped (fail-safe — never throws).
     */
    public static WantedItemList parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return EMPTY;
        }
        Set<String> ids = new HashSet<>();
        Set<TagKey<Item>> tags = new HashSet<>();
        for (String token : raw.split("[,\\s]+")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            boolean isTag = trimmed.startsWith("#");
            String[] parts = splitId(isTag ? trimmed.substring(1) : trimmed);
            if (parts == null) {
                continue;
            }
            try {
                if (isTag) {
                    tags.add(TagKey.create(Registries.ITEM, RegistryCompat.id(parts[0], parts[1])));
                } else {
                    // RegistryCompat.id validates + canonicalises; its toString is "namespace:path",
                    // which is exactly what the runtime registry key compares against in matches().
                    ids.add(RegistryCompat.id(parts[0], parts[1]).toString());
                }
            } catch (RuntimeException e) {
                // Illegal id/tag characters — skip this entry, keep the rest.
            }
        }
        if (ids.isEmpty() && tags.isEmpty()) {
            return EMPTY;
        }
        return new WantedItemList(Set.copyOf(ids), Set.copyOf(tags));
    }

    /** Split {@code ns:path} (defaulting the namespace to {@code minecraft}), or null if either half is empty. */
    private static String[] splitId(String token) {
        int colon = token.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : token.substring(0, colon);
        String path = colon < 0 ? token : token.substring(colon + 1);
        if (namespace.isEmpty() || path.isEmpty()) {
            return null;
        }
        return new String[] {namespace, path};
    }

    /** True if {@code stack} matches any configured item id or tag. */
    public boolean matches(ItemStack stack) {
        if (stack.isEmpty() || isEmpty()) {
            return false;
        }
        if (!itemIds.isEmpty()
                && itemIds.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
            return true;
        }
        for (TagKey<Item> tag : tags) {
            if (stack.is(tag)) {
                return true;
            }
        }
        return false;
    }

    /** True when no id or tag is configured (i.e. {@link #EMPTY} or an all-junk line). */
    public boolean isEmpty() {
        return itemIds.isEmpty() && tags.isEmpty();
    }

    /** The parsed item-id strings ({@code namespace:path}) — exposed for unit tests. */
    Set<String> itemIds() {
        return itemIds;
    }

    /** The parsed item tags — exposed for unit tests (tag runtime-matching is verified in-game). */
    Set<TagKey<Item>> tags() {
        return tags;
    }
}
