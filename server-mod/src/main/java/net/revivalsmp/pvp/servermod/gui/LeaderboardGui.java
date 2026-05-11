// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.servermod.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.revivalsmp.pvp.servermod.RevivalPVPServerMod;
import net.revivalsmp.pvp.servermod.network.TenantHttpClient;
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
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /pvp leaderboard — 6-row chest. Top row is the filter row: 9 kit picker
 * buttons. Slot 49 (bottom-row centre) is the Sponsors toggle, which
 * switches entries to season sponsor totals (coins received) instead of
 * kit LP. Slots 9..44 hold up to 36 player heads. Click a head → opens
 * PlayerDetailGui for that player.
 */
public class LeaderboardGui implements Listener {

    private static final int SIZE = 54;
    /** Bottom-row centre slot. Reserved for the Sponsors-mode toggle so the
     *  kit filter row stays intact and the toggle has its own visual home. */
    private static final int SPONSORS_SLOT = 49;

    /** Row 0 = kit picker. Slot → kit. Click any of these to switch the
     *  leaderboard to that kit without leaving the GUI. */
    private static final java.util.Map<Integer, KitOpt> KIT_SLOTS = new java.util.LinkedHashMap<>();
    static {
        // Kept in same playstyle order as HubGui: fist→sword→archer→...
        KIT_SLOTS.put(0, new KitOpt("FIST",     Material.PLAYER_HEAD));
        KIT_SLOTS.put(1, new KitOpt("SWORD",    Material.IRON_SWORD));
        KIT_SLOTS.put(2, new KitOpt("ARCHER",   Material.BOW));
        KIT_SLOTS.put(3, new KitOpt("CROSSBOW", Material.CROSSBOW));
        KIT_SLOTS.put(4, new KitOpt("MACE",     Material.MACE));
        KIT_SLOTS.put(5, new KitOpt("CRYSTAL",  Material.END_CRYSTAL));
        KIT_SLOTS.put(6, new KitOpt("SPEAR",    Material.IRON_SPEAR));
        KIT_SLOTS.put(7, new KitOpt("TRIDENT",  Material.TRIDENT));
        KIT_SLOTS.put(8, new KitOpt("TNT",      Material.TNT));
        // POTIONS + AXE intentionally not in the chest leaderboard filter:
        // row 0 has only 9 slots and rows 1-4 are leaderboard entries.
        // Per-player rankings for these kits are visible in /pvp profile and
        // on the website; expanding the chest filter is a follow-up.
    }
    private record KitOpt(String name, Material icon) {}

    private final RevivalPVPServerMod plugin;
    private final Player              player;
    private final Inventory           inv;
    private String                    kit;          // mutable — switching kits stays in this GUI
    private boolean                   sponsorMode;  // when true, ignore kit and show sponsor coins

    private final List<Entry> entries = new ArrayList<>();

    public LeaderboardGui(RevivalPVPServerMod plugin, Player player, String kit) {
        this.plugin = plugin;
        this.player = player;
        this.kit    = kit.toUpperCase();
        // Generic title — the row-0 kit picker shows the current selection
        // visually. Bukkit chest titles are fixed at create time, so anchoring
        // the title to a specific kit would force a recreate-on-switch.
        this.inv    = Bukkit.createInventory(player, SIZE,
            Component.text("Leaderboard", NamedTextColor.LIGHT_PURPLE));
    }

    public void open() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        player.openInventory(inv);
        refresh();
    }

    private void refresh() {
        // Row 0 — kit filter. Selected kit shows enchanted (glowing) when
        // we're in kit mode. Greyed (no enchant) while sponsor mode is active.
        for (var entry : KIT_SLOTS.entrySet()) {
            int slot     = entry.getKey();
            KitOpt opt   = entry.getValue();
            boolean sel  = !sponsorMode && opt.name().equals(kit);
            ItemStack item = new ItemStack(opt.icon());
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text(opt.name(),
                sel ? NamedTextColor.GOLD : NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text(sel
                    ? "Showing this leaderboard"
                    : "Click to switch leaderboard",
                    sel ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("Top players for kit: " + opt.name(), NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ));
            if (sel) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            item.setItemMeta(meta);
            inv.setItem(slot, item);
        }

        // Sponsors toggle in slot 49.
        ItemStack sponsorBtn = new ItemStack(Material.PINK_DYE);
        ItemMeta sponsorMeta = sponsorBtn.getItemMeta();
        sponsorMeta.displayName(Component.text("Sponsors Leaderboard",
            sponsorMode ? NamedTextColor.GOLD : NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.ITALIC, false));
        sponsorMeta.lore(List.of(
            Component.text(sponsorMode
                ? "Showing top sponsored players this season"
                : "Click to view top sponsored players this season",
                sponsorMode ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Sorted by sponsor coins received.", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ));
        if (sponsorMode) {
            sponsorMeta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            sponsorMeta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        }
        sponsorBtn.setItemMeta(sponsorMeta);
        inv.setItem(SPONSORS_SLOT, sponsorBtn);

        // Clear all body slots before refilling with new entries.
        for (int i = 9; i < SIZE; i++) {
            if (i == SPONSORS_SLOT) continue;
            inv.setItem(i, null);
        }

        var future = sponsorMode
            ? TenantHttpClient.sponsorLeaderboard(plugin, 36)
            : TenantHttpClient.kitLeaderboard(plugin, kit, 36);

        future.thenAccept(resp -> Bukkit.getScheduler().runTask(plugin, () -> {
            entries.clear();
            if (resp == null || !resp.has("entries")) return;
            JsonArray arr = resp.getAsJsonArray("entries");
            int slot = 9;
            for (var el : arr) {
                if (slot >= SPONSORS_SLOT) break; // stop before bottom toggle row
                JsonObject o = el.getAsJsonObject();
                Entry e;
                if (sponsorMode) {
                    e = new Entry(
                        o.has("rank") ? o.get("rank").getAsInt() : 0,
                        optStr(o, "uuid"),
                        optStr(o, "username"),
                        null, null, 0, 0, 0,
                        o.has("coins") ? o.get("coins").getAsInt() : 0,
                        true
                    );
                } else {
                    e = new Entry(
                        o.has("rank_position") ? o.get("rank_position").getAsInt() : 0,
                        optStr(o, "uuid"),
                        optStr(o, "username"),
                        optStr(o, "rank"),
                        optStr(o, "division"),
                        o.has("lp") ? o.get("lp").getAsInt() : 0,
                        o.has("wins") ? o.get("wins").getAsInt() : 0,
                        o.has("games") ? o.get("games").getAsInt() : 0,
                        0,
                        false
                    );
                }
                entries.add(e);
                inv.setItem(slot++, headFor(e));
            }
        }));
    }

    private ItemStack headFor(Entry e) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        if (e.username != null) {
            try { sm.setOwningPlayer(Bukkit.getOfflinePlayer(e.username)); }
            catch (Throwable ignored) {}
        }
        sm.displayName(Component.text("#" + e.position + "  " + e.username,
            NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (e.sponsor) {
            lore.add(Component.text("Sponsor coins received this season",
                NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("♥ " + e.coins + " coins",
                NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Kit: " + kit, NamedTextColor.DARK_AQUA)
                .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text(
                (e.rank == null ? "Unranked" : e.rank
                    + (e.division == null || e.division.isBlank() ? "" : " " + e.division))
                    + " - " + e.lp + " LP",
                NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            if (e.games > 0) {
                int wr = (int) (e.wins * 100.0 / e.games);
                lore.add(Component.text(
                    "W/L: " + e.wins + "/" + (e.games - e.wins) + "  (" + wr + "%)",
                    NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
        }
        lore.add(Component.empty());
        lore.add(Component.text("Click to view detail / sponsor", NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.ITALIC, false));
        sm.lore(lore);
        head.setItemMeta(sm);
        return head;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!e.getInventory().equals(inv)) return;
        if (!(e.getWhoClicked() instanceof Player p) || !p.getUniqueId().equals(player.getUniqueId())) return;
        e.setCancelled(true);

        int slot = e.getRawSlot();

        // Sponsors toggle. Switches view to sponsor coins and refreshes.
        if (slot == SPONSORS_SLOT) {
            if (!sponsorMode) {
                sponsorMode = true;
                refresh();
            }
            return;
        }

        // Row 0 — kit filter. Click switches the leaderboard kit (and disables
        // sponsor mode if it was on) and refreshes in place.
        KitOpt chosenKit = KIT_SLOTS.get(slot);
        if (chosenKit != null) {
            boolean wasSponsor = sponsorMode;
            if (!chosenKit.name().equals(kit) || wasSponsor) {
                kit = chosenKit.name();
                sponsorMode = false;
                refresh();
            }
            return;
        }

        if (slot < 9 || slot >= SIZE) return;
        int idx = slot - 9;
        if (idx < 0 || idx >= entries.size()) return;
        Entry chosen = entries.get(idx);
        if (chosen.uuid == null) return;

        HandlerList.unregisterAll(this);
        p.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> new PlayerDetailGui(plugin, p, chosen).open());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!e.getInventory().equals(inv)) return;
        UUID viewer = e.getPlayer().getUniqueId();
        if (!viewer.equals(player.getUniqueId())) return;
        HandlerList.unregisterAll(this);
    }

    private static String optStr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    record Entry(int position, String uuid, String username, String rank,
                 String division, int lp, int wins, int games,
                 int coins, boolean sponsor) {
        // Convenience for kit-mode call sites that don't carry sponsor data.
        Entry(int position, String uuid, String username, String rank,
              String division, int lp, int wins, int games) {
            this(position, uuid, username, rank, division, lp, wins, games, 0, false);
        }
    }
}