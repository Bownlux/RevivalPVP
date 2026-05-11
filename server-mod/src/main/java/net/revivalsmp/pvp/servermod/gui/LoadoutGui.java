// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Chest-GUI loadout editor. Shows the player's currently-selected variant for
 * each kit slot; clicking a slot opens a {@link VariantPickerGui} for that slot.
 *
 * <p>Layout (54-slot, 6-row chest):
 * <pre>
 *   row 0: header strip (kit info)
 *   row 1: equipment slots: HEAD CHEST LEGS FEET MAINHAND OFFHAND HOTBAR1 (slots 10..16)
 *   row 2: hotbar continuation: HOTBAR2 HOTBAR3 HOTBAR4 (slots 19..21)
 *   row 5: back button (slot 49)
 * </pre>
 *
 * <p>State is loaded async on open (with "Loading..." placeholders) and
 * re-loaded after each save so the row reflects the freshly-selected variant.
 *
 * <p>Per-instance, per-open. Listener auto-unregisters in {@link #onClose}.
 */
public class LoadoutGui implements Listener {

    private static final int SIZE = 54;

    /** Fixed slot ordering, matching the client mod's {@code KitLoadout.Slot}. */
    private static final List<String> SLOTS = List.of(
        "HEAD", "CHEST", "LEGS", "FEET",
        "MAINHAND", "OFFHAND",
        "HOTBAR1", "HOTBAR2", "HOTBAR3", "HOTBAR4"
    );

    /** Bukkit-inventory slot index for each loadout slot. */
    private static final Map<String, Integer> SLOT_POSITIONS = new HashMap<>();
    static {
        // Row 1 (slots 9..17): main armor + main/offhand + first hotbar (positions 10..16)
        SLOT_POSITIONS.put("HEAD",     10);
        SLOT_POSITIONS.put("CHEST",    11);
        SLOT_POSITIONS.put("LEGS",     12);
        SLOT_POSITIONS.put("FEET",     13);
        SLOT_POSITIONS.put("MAINHAND", 14);
        SLOT_POSITIONS.put("OFFHAND",  15);
        SLOT_POSITIONS.put("HOTBAR1",  16);
        // Row 2 (slots 18..26)
        SLOT_POSITIONS.put("HOTBAR2",  19);
        SLOT_POSITIONS.put("HOTBAR3",  20);
        SLOT_POSITIONS.put("HOTBAR4",  21);
    }

    private static final int BACK_SLOT = 49;
    private static final int HEADER_SLOT = 4;

    private final RevivalPVPServerMod plugin;
    private final Player              player;
    private final String              kit;
    private final Inventory           inv;

    /** Most recent loadout response from the backend. Null while loading or on failure. */
    private JsonObject loadout;

    /**
     * Per-slot variant list (slot name → JsonArray). Built from {@link #loadout}'s
     * {@code by_slot} field for fast access in click handlers.
     */
    private final Map<String, JsonArray> variantsBySlot = new LinkedHashMap<>();

    /** Per-slot currently-selected variant id; -1 if none. */
    private final Map<String, Integer> selectedIdBySlot = new HashMap<>();

    /** Set true while a save is in flight, to ignore further clicks. */
    private volatile boolean saving = false;

    public LoadoutGui(RevivalPVPServerMod plugin, Player player, String kit) {
        this.plugin = plugin;
        this.player = player;
        this.kit    = kit;
        this.inv    = Bukkit.createInventory(player, SIZE,
            Component.text("Loadout — " + prettyKit(kit), NamedTextColor.LIGHT_PURPLE));
    }

    public void open() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        renderLoading();
        player.openInventory(inv);
        fetch();
    }

    /** Fetch loadout from backend and re-render once it arrives. */
    private void fetch() {
        String internalUuid = plugin.getInternalUuid(player.getUniqueId());
        if (internalUuid == null) {
            // Should have been guarded at the open call site, but double-check.
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.closeInventory();
                player.sendMessage(Component.text(
                    "Join a queue once first to enable loadout editing.", NamedTextColor.YELLOW));
            });
            return;
        }
        LoadoutHttpClient.getLoadout(plugin, kit, internalUuid).thenAccept(resp ->
            Bukkit.getScheduler().runTask(plugin, () -> applyLoadout(resp))
        );
    }

    /** Update local state + re-render after a fetch (or after a successful save). */
    private void applyLoadout(JsonObject resp) {
        if (player.getOpenInventory() == null || !inv.equals(player.getOpenInventory().getTopInventory())) {
            // Player closed the GUI before the response arrived — drop the result.
            return;
        }
        if (resp == null) {
            renderError("Could not load your loadout. Try again later.");
            return;
        }
        this.loadout = resp;
        variantsBySlot.clear();
        selectedIdBySlot.clear();

        if (resp.has("by_slot") && resp.get("by_slot").isJsonObject()) {
            JsonObject bySlot = resp.getAsJsonObject("by_slot");
            for (Map.Entry<String, JsonElement> e : bySlot.entrySet()) {
                if (e.getValue().isJsonArray()) {
                    variantsBySlot.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue().getAsJsonArray());
                }
            }
        }
        if (resp.has("selected") && resp.get("selected").isJsonObject()) {
            JsonObject selected = resp.getAsJsonObject("selected");
            for (Map.Entry<String, JsonElement> e : selected.entrySet()) {
                JsonObject v = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
                if (v != null && v.has("id")) {
                    try { selectedIdBySlot.put(e.getKey().toUpperCase(Locale.ROOT), v.get("id").getAsInt()); }
                    catch (Exception ignored) {}
                }
            }
        }
        render();
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void renderLoading() {
        inv.clear();
        inv.setItem(HEADER_SLOT, simple(Material.PAPER,
            Component.text("Loading loadout...", NamedTextColor.GRAY),
            List.of()));
        for (String slot : SLOTS) {
            Integer pos = SLOT_POSITIONS.get(slot);
            if (pos != null) {
                inv.setItem(pos, simple(Material.GRAY_STAINED_GLASS_PANE,
                    Component.text(slot, NamedTextColor.GRAY),
                    List.of(Component.text("Loading...", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false))));
            }
        }
        inv.setItem(BACK_SLOT, backButton());
    }

    private void renderError(String msg) {
        inv.clear();
        inv.setItem(HEADER_SLOT, simple(Material.BARRIER,
            Component.text("Error", NamedTextColor.RED),
            List.of(Component.text(msg, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false))));
        inv.setItem(BACK_SLOT, backButton());
    }

    private void render() {
        inv.clear();
        // Header
        int totalSlots = variantsBySlot.size();
        int selected = selectedIdBySlot.size();
        inv.setItem(HEADER_SLOT, simple(Material.NETHER_STAR,
            Component.text(prettyKit(kit) + " Loadout", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false),
            List.of(
                Component.text(selected + "/" + totalSlots + " slots configured",
                    NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Click a slot below to change its variant.",
                    NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
            )));

        // Equipment / hotbar slots
        for (String slotName : SLOTS) {
            Integer pos = SLOT_POSITIONS.get(slotName);
            if (pos == null) continue;
            JsonArray variants = variantsBySlot.get(slotName);
            if (variants == null || variants.isEmpty()) {
                // Slot not part of this kit — leave the inventory cell empty.
                continue;
            }
            JsonObject selectedVariant = currentVariantFor(slotName);
            inv.setItem(pos, slotIcon(slotName, selectedVariant, variants.size()));
        }

        inv.setItem(BACK_SLOT, backButton());
    }

    private JsonObject currentVariantFor(String slotName) {
        Integer id = selectedIdBySlot.get(slotName);
        if (id == null) return null;
        JsonArray variants = variantsBySlot.get(slotName);
        if (variants == null) return null;
        for (JsonElement el : variants) {
            if (!el.isJsonObject()) continue;
            JsonObject v = el.getAsJsonObject();
            if (v.has("id") && v.get("id").getAsInt() == id) return v;
        }
        return null;
    }

    private ItemStack slotIcon(String slotName, JsonObject variant, int totalVariants) {
        Material mat = variant != null
            ? materialFromVariant(variant, fallbackForSlot(slotName))
            : fallbackForSlot(slotName);
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        String variantName = variant != null && variant.has("name") && !variant.get("name").isJsonNull()
            ? variant.get("name").getAsString()
            : "(no selection)";
        meta.displayName(Component.text(slotName + ": " + variantName, NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        if (variant != null && variant.has("description") && !variant.get("description").isJsonNull()) {
            String desc = variant.get("description").getAsString();
            for (String wrapped : wrap(desc, 38)) {
                lore.add(Component.text(wrapped, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
        }
        if (!lore.isEmpty()) lore.add(Component.empty());
        lore.add(Component.text(totalVariants + " variants available", NamedTextColor.DARK_GRAY)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("[Click to change]", NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack backButton() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Back", NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Return to the queue menu.",
            NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
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

        int slot = e.getRawSlot();
        if (slot == BACK_SLOT) {
            // Reopen hub. Close first, then schedule the open one tick later
            // so Bukkit doesn't reject the open inside the click handler.
            HandlerList.unregisterAll(this);
            p.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> new HubGui(plugin, p).open());
            return;
        }

        // Find which loadout slot this corresponds to.
        for (Map.Entry<String, Integer> entry : SLOT_POSITIONS.entrySet()) {
            if (entry.getValue() == slot) {
                String slotName = entry.getKey();
                JsonArray variants = variantsBySlot.get(slotName);
                if (variants == null || variants.isEmpty()) return;
                int currentId = selectedIdBySlot.getOrDefault(slotName, -1);

                // Capture local refs for the callback, since we tear down listeners.
                final UUID viewerId = player.getUniqueId();
                final RevivalPVPServerMod pluginRef = plugin;
                final String kitRef = kit;

                HandlerList.unregisterAll(this);
                p.closeInventory();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player target = Bukkit.getPlayer(viewerId);
                    if (target == null || !target.isOnline()) return;
                    new VariantPickerGui(pluginRef, target, kitRef, slotName,
                        variants, currentId,
                        () -> new LoadoutGui(pluginRef, target, kitRef).open()
                    ).open();
                });
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!e.getInventory().equals(inv)) return;
        UUID viewer = e.getPlayer().getUniqueId();
        if (!viewer.equals(player.getUniqueId())) return;
        HandlerList.unregisterAll(this);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Resolve a Bukkit {@link Material} from a variant's item.material; fall back if invalid. */
    static Material materialFromVariant(JsonObject variant, Material fallback) {
        if (variant == null) return fallback;
        if (!variant.has("item") || !variant.get("item").isJsonObject()) return fallback;
        JsonObject item = variant.getAsJsonObject("item");
        if (!item.has("material") || item.get("material").isJsonNull()) return fallback;
        try {
            String name = item.get("material").getAsString().toUpperCase(Locale.ROOT);
            Material m = Material.matchMaterial(name);
            return m != null ? m : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Per-slot fallback icon when the variant's material is missing or unknown. */
    static Material fallbackForSlot(String slotName) {
        return switch (slotName) {
            case "HEAD"     -> Material.IRON_HELMET;
            case "CHEST"    -> Material.IRON_CHESTPLATE;
            case "LEGS"     -> Material.IRON_LEGGINGS;
            case "FEET"     -> Material.IRON_BOOTS;
            case "MAINHAND" -> Material.IRON_SWORD;
            case "OFFHAND"  -> Material.SHIELD;
            default          -> Material.PAPER; // hotbars
        };
    }

    /** Cheap lore wrap so we don't blow past the 32-ish-char visual width. */
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

    private static String prettyKit(String k) {
        if (k == null || k.isBlank()) return "?";
        return k.charAt(0) + k.substring(1).toLowerCase(Locale.ROOT);
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