// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.servermod.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.revivalsmp.pvp.servermod.RevivalPVPServerMod;
import net.revivalsmp.pvp.servermod.network.TenantHttpClient;
import net.revivalsmp.pvp.servermod.ws.BackendClient;
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
import java.util.List;
import java.util.UUID;

/**
 * /pvp spectate — live duels list. Paginated chest GUI of currently active
 * matches. Click a row to spectate (issues a spec- token + transfers the
 * player to the duel server in spectator gamemode).
 *
 * <p>Layout (54-slot chest):
 *   slot 4         header
 *   rows 1..5      up to 45 live duels (slots 9-53 minus controls)
 *   slot 49        [Refresh]
 *   slot 53        [Close]
 *
 * <p>Each row uses an iron sword icon coloured by mode (gold = ranked,
 * gray = unranked) and shows player_a vs player_b + map + kit. Voting-phase
 * duels (no map yet) render dimmer and aren't clickable since the duel
 * hasn't actually started yet.
 */
public class LiveMatchesGui implements Listener {

    private static final int SIZE = 54;

    private final RevivalPVPServerMod plugin;
    private final Player              player;
    private final Inventory           inv;

    /** Slot → match_id of the currently-rendered row. Cleared on every
     *  refresh so click handling never spectates a stale match_id. */
    private final java.util.Map<Integer, RowEntry> rows = new java.util.HashMap<>();

    public LiveMatchesGui(RevivalPVPServerMod plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inv    = Bukkit.createInventory(player, SIZE,
            Component.text("RevivalPVP — Live Duels", NamedTextColor.AQUA));
    }

    public void open() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        player.openInventory(inv);
        refresh();
    }

    private void refresh() {
        rows.clear();

        ItemStack title = new ItemStack(Material.SPYGLASS);
        ItemMeta tm = title.getItemMeta();
        tm.displayName(Component.text("Live Duels", NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        tm.lore(List.of(
            Component.text("Click any active duel to spectate.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Use /spectatequit on the duel server to leave.", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ));
        title.setItemMeta(tm);
        inv.setItem(4, title);

        inv.setItem(49, action(Material.PAPER,   "§e§lRefresh", "Re-fetch live duels"));
        inv.setItem(53, action(Material.BARRIER, "§c§lClose",   "Close this menu"));

        // Clear list area.
        for (int i = 9; i < 54; i++) {
            if (i == 49 || i == 53) continue;
            inv.setItem(i, null);
        }

        TenantHttpClient.liveDuels(plugin).thenAccept(resp -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (resp == null || !resp.has("live")) {
                inv.setItem(22, info("§cFailed to load live duels.",
                    "Check console for details."));
                return;
            }
            JsonArray live = resp.getAsJsonArray("live");
            if (live.isEmpty()) {
                inv.setItem(22, info("§7No live duels right now.",
                    "Click Refresh to check again."));
                return;
            }
            int slot = 9;
            for (int i = 0; i < live.size() && slot < 49; i++, slot++) {
                if (slot == 17 || slot == 18) continue;       // skip side gutters
                JsonObject m = live.get(i).getAsJsonObject();
                renderRow(slot, m);
            }
        }));
    }

    private void renderRow(int slot, JsonObject m) {
        String matchId = optStr(m, "match_id", "?");
        String kit     = optStr(m, "kit",      "?");
        String pa      = optStr(m, "player_a", "?");
        String pb      = optStr(m, "player_b", "?");
        String map     = optStr(m, "map_name", null);
        String phase   = optStr(m, "phase",    "active");
        boolean ranked = m.has("ranked") && m.get("ranked").getAsBoolean();
        boolean voting = "voting".equalsIgnoreCase(phase) || map == null;

        Material mat = ranked ? Material.GOLDEN_SWORD : Material.IRON_SWORD;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(pa + " vs " + pb,
            voting ? NamedTextColor.GRAY : NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Kit: §f" + kit + "§7 · §f"
                + (ranked ? "Ranked" : "Unranked"),
            NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Map: §f" + (map == null ? "(voting...)" : map),
            NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        if (voting) {
            lore.add(Component.text("§8Spectate available once the duel starts.")
                .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("§a§l▶ Click to Spectate")
                .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);

        inv.setItem(slot, item);
        rows.put(slot, new RowEntry(matchId, voting));
    }

    private ItemStack action(Material mat, String name, String desc) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("§7" + desc).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack info(String name, String desc) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        if (desc != null) {
            meta.lore(List.of(Component.text("§7" + desc).decoration(TextDecoration.ITALIC, false)));
        }
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!e.getInventory().equals(inv)) return;
        if (!(e.getWhoClicked() instanceof Player p) || !p.getUniqueId().equals(player.getUniqueId())) return;
        e.setCancelled(true);

        int slot = e.getRawSlot();
        if (slot == 49) { refresh(); return; }
        if (slot == 53) { p.closeInventory(); return; }

        RowEntry row = rows.get(slot);
        if (row == null) return;
        if (row.voting()) {
            p.sendMessage(Component.text("This duel is still in map vote — try again in a few seconds.",
                NamedTextColor.YELLOW));
            return;
        }
        spectate(p, row.matchId());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!e.getInventory().equals(inv)) return;
        UUID viewer = e.getPlayer().getUniqueId();
        if (!viewer.equals(player.getUniqueId())) return;
        HandlerList.unregisterAll(this);
    }

    private void spectate(Player p, String matchId) {
        // Server-mod tenant auth needs the player to be registered (queued at
        // least once). If they haven't, the friends/loadout flows hit the same
        // wall and we surface the same hint.
        p.sendMessage(Component.text("Joining as spectator…", NamedTextColor.AQUA));
        p.closeInventory();
        TenantHttpClient.spectate(plugin, p.getUniqueId(), matchId).thenAccept(resp ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                org.bukkit.entity.Player still = Bukkit.getPlayer(p.getUniqueId());
                if (still == null || !still.isOnline()) return;
                if (resp == null) {
                    still.sendMessage(Component.text(
                        "Couldn't start spectator session (the duel may have just ended).",
                        NamedTextColor.RED));
                    return;
                }
                String host  = resp.has("relay_host")    ? resp.get("relay_host").getAsString()    : null;
                int    port  = resp.has("relay_port")    ? resp.get("relay_port").getAsInt()       : 0;
                String token = resp.has("session_token") ? resp.get("session_token").getAsString() : null;
                if (host == null || port == 0 || token == null) {
                    still.sendMessage(Component.text(
                        "Backend returned an incomplete spectator session.",
                        NamedTextColor.RED));
                    return;
                }
                BackendClient.transferWithToken(plugin, still, host, port, token);
            }));
    }

    private static String optStr(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    private record RowEntry(String matchId, boolean voting) {}
}