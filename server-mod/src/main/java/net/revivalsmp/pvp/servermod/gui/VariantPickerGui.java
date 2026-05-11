// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.servermod.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.revivalsmp.pvp.servermod.RevivalPVPServerMod;
import net.revivalsmp.pvp.servermod.network.LoadoutHttpClient;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Chest-GUI variant picker for a single loadout slot. Shown when the player
 * clicks a slot in {@link LoadoutGui}. Each variant is rendered as a clickable
 * item; the currently-selected variant glows (via a hidden enchant glint) and
 * has a green name.
 *
 * <p>Click flow:
 * <ul>
 *   <li>Click variant → POST setLoadout async → on success, close + run
 *       {@code onClose} (typically reopens the parent {@link LoadoutGui})</li>
 *   <li>Click "Back" → close + run {@code onClose} without saving</li>
 * </ul>
 *
 * <p>State is per-instance, per-open. Listener auto-unregisters in onClose.
 */
public class VariantPickerGui implements Listener {

    private static final int SIZE = 54;
    /** Slots reserved for variant cards (45 cells across 5 rows). */
    private static final int FIRST_VARIANT_SLOT = 0;
    private static final int LAST_VARIANT_SLOT  = 44;
    private static final int BACK_SLOT          = 53;

    private final RevivalPVPServerMod plugin;
    private final Player              player;
    private final String              kit;
    private final String              slot;
    private final List<JsonObject>    variants;
    private final int                 currentlySelectedId;
    private final Runnable            onClose;
    private final Inventory           inv;

    /** Bukkit-inventory slot index → variant id, for click dispatch. */
    private final java.util.Map<Integer, Integer> slotToVariantId = new java.util.HashMap<>();

    /** True while a save is in flight, to ignore further clicks. */
    private volatile boolean saving = false;

    /** True once we've fired {@link #onClose} so we don't double-fire. */
    private volatile boolean closeFired = false;

    public VariantPickerGui(RevivalPVPServerMod plugin, Player player, String kit, String slot,
                            JsonArray variants, int currentlySelectedId, Runnable onClose) {
        this.plugin               = plugin;
        this.player               = player;
        this.kit                  = kit;
        this.slot                 = slot;
        this.variants             = new ArrayList<>();
        if (variants != null) {
            for (JsonElement el : variants) {
                if (el != null && el.isJsonObject()) this.variants.add(el.getAsJsonObject());
            }
        }
        this.currentlySelectedId  = currentlySelectedId;
        this.onClose              = onClose;
        this.inv                  = Bukkit.createInventory(player, SIZE,
            Component.text("Pick " + slot + " — " + prettyKit(kit), NamedTextColor.LIGHT_PURPLE));
    }

    public void open() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        render();
        player.openInventory(inv);
    }

    private void render() {
        inv.clear();
        slotToVariantId.clear();

        int idx = 0;
        for (JsonObject v : variants) {
            if (idx > LAST_VARIANT_SLOT) break;
            int variantId = v.has("id") ? v.get("id").getAsInt() : -1;
            inv.setItem(idx, variantIcon(v, variantId == currentlySelectedId));
            slotToVariantId.put(idx, variantId);
            idx++;
        }

        inv.setItem(BACK_SLOT, simple(Material.ARROW,
            Component.text("Back", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
            List.of(Component.text("Return without saving.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false))));
    }

    private ItemStack variantIcon(JsonObject variant, boolean selected) {
        Material mat = LoadoutGui.materialFromVariant(variant, LoadoutGui.fallbackForSlot(slot));
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        String name = variant.has("name") && !variant.get("name").isJsonNull()
            ? variant.get("name").getAsString()
            : "Variant #" + (variant.has("id") ? variant.get("id").getAsInt() : "?");
        meta.displayName(Component.text((selected ? "[Selected] " : "") + name,
            selected ? NamedTextColor.GREEN : NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        if (variant.has("description") && !variant.get("description").isJsonNull()) {
            for (String w : wrap(variant.get("description").getAsString(), 38)) {
                lore.add(Component.text(w, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
        }
        if (variant.has("balance_class") && !variant.get("balance_class").isJsonNull()) {
            lore.add(Component.empty());
            lore.add(Component.text("Style: " + variant.get("balance_class").getAsString(),
                NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false));
        }
        if (selected) {
            lore.add(Component.empty());
            lore.add(Component.text("Currently selected.", NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.empty());
            lore.add(Component.text("[Click to select]", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);

        if (selected) {
            // Hidden enchant glint to highlight the active variant. Any vanilla
            // enchant works for the visual; we hide the tooltip lines so the
            // lore we built above stays clean.
            try {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
            } catch (Throwable t) {
                // Some materials reject enchant ops — fall back to no glint.
            }
        }

        item.setItemMeta(meta);
        return item;
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!e.getInventory().equals(inv)) return;
        if (!(e.getWhoClicked() instanceof Player p) || !p.getUniqueId().equals(player.getUniqueId())) return;
        e.setCancelled(true);
        if (saving) return;

        int rawSlot = e.getRawSlot();
        if (rawSlot == BACK_SLOT) {
            HandlerList.unregisterAll(this);
            p.closeInventory();
            fireClose();
            return;
        }

        Integer variantId = slotToVariantId.get(rawSlot);
        if (variantId == null || variantId < 0) return;

        if (variantId == currentlySelectedId) {
            // Already selected — just close and reopen parent.
            HandlerList.unregisterAll(this);
            p.closeInventory();
            fireClose();
            return;
        }

        // POST setLoadout. Disable further clicks while in flight.
        saving = true;
        String internalUuid = plugin.getInternalUuid(player.getUniqueId());
        if (internalUuid == null) {
            p.sendMessage(Component.text("Loadout session expired. Open /pvp again.",
                NamedTextColor.RED));
            HandlerList.unregisterAll(this);
            p.closeInventory();
            fireClose();
            return;
        }

        JsonArray selections = new JsonArray();
        JsonObject sel = new JsonObject();
        sel.addProperty("slot", slot);
        sel.addProperty("variant_id", variantId);
        selections.add(sel);

        LoadoutHttpClient.setLoadout(plugin, kit, internalUuid, selections).thenAccept(resp ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player target = Bukkit.getPlayer(player.getUniqueId());
                if (target == null || !target.isOnline()) return;
                if (resp == null) {
                    target.sendMessage(Component.text(
                        "Failed to save loadout. Try again.", NamedTextColor.RED));
                    saving = false;
                    return;
                }
                HandlerList.unregisterAll(this);
                target.closeInventory();
                fireClose();
            })
        );
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!e.getInventory().equals(inv)) return;
        UUID viewer = e.getPlayer().getUniqueId();
        if (!viewer.equals(player.getUniqueId())) return;
        HandlerList.unregisterAll(this);
        // If the player closed via Esc rather than a click handler, still
        // reopen the parent so the editor flow is symmetric.
        if (!saving) fireClose();
    }

    private void fireClose() {
        if (closeFired) return;
        closeFired = true;
        if (onClose == null) return;
        Bukkit.getScheduler().runTask(plugin, onClose);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String prettyKit(String k) {
        if (k == null || k.isBlank()) return "?";
        return k.charAt(0) + k.substring(1).toLowerCase(Locale.ROOT);
    }

    private static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            if (line.length() + word.length() + 1 > width && !line.isEmpty()) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    private static ItemStack simple(Material mat, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (name != null) meta.displayName(name);
        if (lore != null && !lore.isEmpty()) meta.lore(new ArrayList<>(lore));
        item.setItemMeta(meta);
        return item;
    }

}