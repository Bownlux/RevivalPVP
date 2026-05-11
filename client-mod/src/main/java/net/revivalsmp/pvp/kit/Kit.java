// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.kit;

import com.google.gson.JsonArray;
import net.minecraft.world.item.Item;

import java.util.List;

/**
 * Immutable metadata for a single kit. Replaces the old hardcoded enum so
 * adding a kit is a backend DB row, not a mod release.
 *
 * <p>Instances are built by {@link KitRegistry} from the
 * {@code /kits/catalog} backend response (or its disk cache fallback).
 *
 * <p>Source-compat with the old enum is preserved via:
 * <ul>
 *   <li>{@link #get(String)} — replaces {@code Kit.SWORD} style literals.</li>
 *   <li>{@link #values()} — returns the current catalog, replacing the
 *       enum's auto-generated {@code values()}.</li>
 *   <li>{@link #icon()}, {@link #display()}, {@link #description()},
 *       {@link #name()}, {@link #isRankable()} — same method names the
 *       enum exposed, so callsites only need {@code .icon → .icon()}
 *       style edits.</li>
 * </ul>
 */
public record Kit(
    String name,
    String display,
    String description,
    String iconMaterial,
    boolean modOnly,
    int sortOrder,
    JsonArray rawDefaultLoadout
) {

    /** Runtime-resolved icon. {@link IconResolver} caches the registry lookup. */
    public Item icon() {
        return IconResolver.resolve(iconMaterial);
    }

    /** False only for the MIXED meta-kit (custom duels — no ranked ladder). */
    public boolean isRankable() {
        return !"MIXED".equals(name);
    }

    // ── Static delegators to KitRegistry (source-compat with old enum) ──────

    /** Replaces {@code Kit.SWORD}-style literals. Case-insensitive name lookup.
     *  Returns null if the kit isn't in the registry yet — callers can null-check
     *  or rely on {@link KitRegistry#getOrPlaceholder(String)} for fail-soft UI. */
    public static Kit get(String name) {
        return KitRegistry.get(name);
    }

    /** Replaces the enum's {@code Kit.values()}. Returns the current catalog
     *  in the order the backend's sort_order column dictates (mirrors the
     *  in-game HubGui chest layout). */
    public static List<Kit> values() {
        return KitRegistry.all();
    }

    // Identity by name only: catalog refreshes replace Kit instances, but
    // a kit "is" still the same kit if its name matches. Records' auto-
    // generated equals/hashCode would compare every field (including the
    // mutable-by-reference JsonArray), so refresh would break == and equals
    // for any held references. Override to fix.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Kit other)) return false;
        return name != null && name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return name == null ? 0 : name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
