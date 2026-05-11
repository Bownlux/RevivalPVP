// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.kit;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a Bukkit-style material name ("NETHERITE_AXE") to a vanilla
 * {@link Item} at runtime. Cached per-material so repeated lookups in the
 * GUI render loop are cheap.
 *
 * <p>This is what lets us drop the {@code Items.NETHERITE_AXE} compile-time
 * references in the Kit enum — the backend's {@code /kits/catalog} response
 * names icons by material string, and we resolve them once on demand.
 *
 * <p>If a material is not in the vanilla registry (e.g. a mod-only item the
 * local client doesn't ship), falls back to {@link Items#BARRIER} so the
 * UI keeps rendering. The Kit's {@code modOnly} flag tells the picker to
 * grey it out and show a "needs latest mod" tooltip.
 */
public final class IconResolver {

    private IconResolver() {}

    /** Cache: uppercase material name → resolved Item. Hits the GUI render
     *  loop on every frame; cache keeps it O(1) after first lookup. */
    private static final ConcurrentHashMap<String, Item> CACHE = new ConcurrentHashMap<>();

    /** Item to return when the material is unknown / mod-only and not present
     *  in the local registry. UI callers can pair this with a "needs latest
     *  mod" tooltip via Kit#modOnly(). */
    public static final Item FALLBACK = Items.BARRIER;

    /**
     * Resolve a material name (Bukkit-style upper snake_case, e.g. "NETHERITE_AXE")
     * to a vanilla Item. Always returns non-null — {@link #FALLBACK} on miss.
     */
    public static Item resolve(String materialName) {
        if (materialName == null || materialName.isBlank()) return FALLBACK;
        String key = materialName.toUpperCase(Locale.ROOT);
        Item cached = CACHE.get(key);
        if (cached != null) return cached;
        Item resolved = lookup(materialName);
        CACHE.put(key, resolved);
        return resolved;
    }

    private static Item lookup(String materialName) {
        try {
            Identifier id = Identifier.fromNamespaceAndPath("minecraft",
                materialName.toLowerCase(Locale.ROOT));
            Item item = BuiltInRegistries.ITEM.getValue(id);
            // BuiltInRegistries.ITEM is a DefaultedRegistry returning AIR for misses.
            if (item == null || item == Items.AIR) return FALLBACK;
            return item;
        } catch (Throwable t) {
            return FALLBACK;
        }
    }

    /** Test/debug only — drops the cache so subsequent resolves re-run the registry lookup. */
    public static void clearCache() {
        CACHE.clear();
    }
}
