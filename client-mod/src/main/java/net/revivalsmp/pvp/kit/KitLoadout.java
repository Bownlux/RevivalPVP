// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.kit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-kit equipped-item registry, used by the queue-tab 3D viewer and the
 * variant tooltip system.
 *
 * <p>Previously populated from a static {@code populate()} method full of
 * hardcoded constants. As of the dynamic-catalog refactor, the entries come
 * from the kit's {@code rawDefaultLoadout} JSON (see {@link Kit} and
 * {@link KitRegistry}), which originates from the backend's
 * {@code /kits/catalog} response.
 *
 * <p>Parsing happens lazily on first access per kit and is cached
 * thereafter. Synchronous: callers don't need to handle async loading
 * because the catalog JSON is already in memory by the time they ask.
 */
public final class KitLoadout {

    private KitLoadout() {}

    /** Equipment slot identifier. Stable for hover-region mapping. */
    public enum Slot {
        HEAD, CHEST, LEGS, FEET,
        MAINHAND, OFFHAND,
        HOTBAR1, HOTBAR2, HOTBAR3, HOTBAR4, HOTBAR5
    }

    /** A single equipped item in a kit's preview. */
    public static final class LoadoutSlot {
        public final Slot slot;
        public final ItemStack item;
        public final String displayName;
        public final String description;

        public LoadoutSlot(Slot slot, ItemStack item, String displayName, String description) {
            this.slot = slot;
            this.item = item;
            this.displayName = displayName;
            this.description = description;
        }
    }

    /** Cache: kit name → parsed loadout. Invalidated whenever
     *  {@link KitRegistry#refresh()} replaces the catalog. */
    private static final ConcurrentHashMap<String, List<LoadoutSlot>> CACHE = new ConcurrentHashMap<>();

    /** Returns the default loadout preview for this kit. Never null. Empty list
     *  when the kit has no preview items (e.g. FIST) or when the catalog
     *  doesn't yet include this kit. */
    public static List<LoadoutSlot> get(Kit kit) {
        if (kit == null) return Collections.emptyList();
        return CACHE.computeIfAbsent(kit.name(), k -> parse(kit.rawDefaultLoadout()));
    }

    /** Find the slot entry for a given equipment slot, or null. */
    public static LoadoutSlot find(Kit kit, Slot slot) {
        for (LoadoutSlot ls : get(kit)) {
            if (ls.slot == slot) return ls;
        }
        return null;
    }

    /** Clear the parsed-loadout cache. Called by {@link KitRegistry} when the
     *  catalog is refreshed so the next {@link #get(Kit)} reparses. */
    public static void invalidateCache() {
        CACHE.clear();
    }

    // ── Parsing ────────────────────────────────────────────────────────────

    /** Parse a kit's default_loadout JSON array (one variant row per slot) into
     *  the LoadoutSlot list. Variant rows are the same shape served by
     *  {@code /kits/variants} — each has an {@code item} JSON consumed by
     *  {@link VariantItemFactory}. */
    private static List<LoadoutSlot> parse(JsonArray raw) {
        if (raw == null || raw.size() == 0) return Collections.emptyList();
        List<LoadoutSlot> out = new ArrayList<>();
        for (JsonElement el : raw) {
            if (!el.isJsonObject()) continue;
            JsonObject variant = el.getAsJsonObject();
            Slot slot = parseSlot(optStr(variant, "slot"));
            if (slot == null) continue;
            JsonObject item = (variant.has("item") && variant.get("item").isJsonObject())
                ? variant.getAsJsonObject("item") : null;
            ItemStack stack = item == null ? ItemStack.EMPTY : VariantItemFactory.fromJson(item);
            String displayName = item != null && item.has("name") && !item.get("name").isJsonNull()
                ? stripColorCodes(item.get("name").getAsString())
                : optStr(variant, "name");
            String description = optStr(variant, "description");
            out.add(new LoadoutSlot(slot, stack, displayName, description));
        }
        return out;
    }

    private static Slot parseSlot(String name) {
        if (name == null) return null;
        try {
            return Slot.valueOf(name.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String optStr(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : "";
    }

    /** Strip the §x legacy color codes the backend prefixes display names with. */
    private static String stripColorCodes(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) { i++; continue; }
            sb.append(c);
        }
        return sb.toString();
    }
}
