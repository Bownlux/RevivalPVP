// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.kit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.revivalsmp.pvp.RevivalPVPMod;

import java.util.Locale;
import java.util.Map;

/**
 * Converts a backend variant's "item" JSON object into a client-side ItemStack
 * suitable for icon rendering and equipping on the fake render-target player.
 *
 * Mirrors the Bukkit-side logic in {@code KitDelivery.jsonToItem} but uses the
 * vanilla Mojang API (BuiltInRegistries, ItemEnchantments) since the mod is
 * not running under Bukkit.
 *
 * Item JSON shape:
 *   { "material": "NETHERITE_HELMET", "count": 1,
 *     "enchants": {"PROTECTION_ENVIRONMENTAL":4}, "name":"§7…" }
 */
public final class VariantItemFactory {

    private VariantItemFactory() {}

    /** Build an ItemStack from a backend variant's "item" JSON. Returns EMPTY on failure. */
    public static ItemStack fromJson(JsonObject item) {
        if (item == null) return ItemStack.EMPTY;
        try {
            Item mcItem = lookupItem(item.get("material").getAsString());
            if (mcItem == null) return ItemStack.EMPTY;
            int count = item.has("count") ? item.get("count").getAsInt() : 1;
            ItemStack stack = new ItemStack(mcItem, Math.max(1, count));

            if (item.has("enchants") && !item.get("enchants").isJsonNull()) {
                applyEnchants(stack, item.getAsJsonObject("enchants"));
            }
            return stack;
        } catch (Exception e) {
            // 'Components are not bound yet' fires when the hub renders
            // before MC's component registry has finished initializing
            // (typically opening the hub from the title screen). The screen
            // re-paints fine once registries bind, so this is benign noise.
            // Demote to debug; surface real errors at warn.
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("Components are not bound") || msg.contains("Components not bound")) {
                RevivalPVPMod.LOGGER.debug("VariantItemFactory.fromJson deferred (registries not yet bound)");
            } else {
                RevivalPVPMod.LOGGER.warn("VariantItemFactory.fromJson failed: {}", msg);
            }
            return ItemStack.EMPTY;
        }
    }

    /** Translate a Bukkit-style material name (e.g. NETHERITE_HELMET) to the vanilla item registry id. */
    private static Item lookupItem(String materialName) {
        if (materialName == null || materialName.isBlank()) return null;
        String key = materialName.toLowerCase(Locale.ROOT);
        ResourceLocation id;
        try {
            id = ResourceLocation.fromNamespaceAndPath("minecraft", key);
        } catch (Exception e) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        // BuiltInRegistries.ITEM is a DefaultedRegistry → returns AIR if not found.
        if (item == null || item == net.minecraft.world.item.Items.AIR) return null;
        return item;
    }

    /** Apply enchantments via {@link ItemStack#enchant} which transparently writes the component. */
    private static void applyEnchants(ItemStack stack, JsonObject enchants) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            // No level → no registry access. Skip enchants on icons (still valid render).
            return;
        }
        HolderLookup.Provider lookup = mc.level.registryAccess();
        HolderLookup.RegistryLookup<Enchantment> enchLookup;
        try {
            enchLookup = lookup.lookupOrThrow(Registries.ENCHANTMENT);
        } catch (Exception e) {
            return;
        }

        for (Map.Entry<String, JsonElement> e : enchants.entrySet()) {
            Holder<Enchantment> ench = lookupEnchantment(enchLookup, e.getKey());
            if (ench == null) continue;
            int level;
            try { level = e.getValue().getAsInt(); } catch (Exception ignored) { continue; }
            if (level <= 0) continue;
            stack.enchant(ench, level);
        }
    }

    /** Map a Bukkit-style enchant name to a vanilla Holder via the registry. */
    private static Holder<Enchantment> lookupEnchantment(HolderLookup.RegistryLookup<Enchantment> reg, String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.toUpperCase(Locale.ROOT);
        String key = switch (n) {
            case "PROTECTION_ENVIRONMENTAL" -> "protection";
            case "PROTECTION_EXPLOSIONS"    -> "blast_protection";
            case "PROTECTION_PROJECTILE"    -> "projectile_protection";
            case "PROTECTION_FIRE"          -> "fire_protection";
            case "PROTECTION_FALL"          -> "feather_falling";
            case "DAMAGE_ALL"               -> "sharpness";
            case "DAMAGE_UNDEAD"            -> "smite";
            case "DAMAGE_ARTHROPODS"        -> "bane_of_arthropods";
            case "ARROW_DAMAGE"             -> "power";
            case "ARROW_KNOCKBACK"          -> "punch";
            case "ARROW_FIRE"               -> "flame";
            case "ARROW_INFINITE"           -> "infinity";
            default                         -> n.toLowerCase(Locale.ROOT);
        };
        ResourceLocation id;
        try {
            id = ResourceLocation.fromNamespaceAndPath("minecraft", key);
        } catch (Exception e) {
            return null;
        }
        ResourceKey<Enchantment> rk = ResourceKey.create(Registries.ENCHANTMENT, id);
        return reg.get(rk).orElse(null);
    }
}