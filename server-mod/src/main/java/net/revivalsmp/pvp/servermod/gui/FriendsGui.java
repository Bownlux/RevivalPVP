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
 * /pvp friends — paginated chest GUI of the player's friends. v1: read-only
 * for incoming requests (accept via row click on the request rows shown
 * before friends), invite-on-click for friends rows. Bedrock-friendly.
 *
 * Layout (54-slot chest):
 *   row 0       header (slot 4 = title)
 *   rows 1..5   up to 45 entries per page; first paint: pending requests
 *               (yellow heads), then friends (skin heads)
 *   slot 49     [Refresh] (paper)
 *   slot 50     [Add Friend] (writable_book — opens chat input prompt)
 *   slot 53     [Close] (barrier)
 */
public class FriendsGui implements Listener {

    private static final int SIZE = 54;

    private final RevivalPVPServerMod plugin;
    private final Player              player;
    private final Inventory           inv;

    private List<RowEntry> entries = new ArrayList<>();

    public FriendsGui(RevivalPVPServerMod plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inv    = Bukkit.createInventory(player, SIZE,
            Component.text("RevivalPVP — Friends", NamedTextColor.LIGHT_PURPLE));
    }

    public void open() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        player.openInventory(inv);
        refresh();
    }

    private void refresh() {
        // Header.
        ItemStack title = new ItemStack(Material.PAPER);
        ItemMeta tm = title.getItemMeta();
        tm.displayName(Component.text("Your Friends", NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.ITALIC, false));
        tm.lore(List.of(
            Component.text("Click [+ Add Friend] to send a request,", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("or click a friend row to invite to a duel.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ));
        title.setItemMeta(tm);
        inv.setItem(4, title);

        inv.setItem(49, action(Material.PAPER,           "§e§lRefresh",      "Reload your friend data"));
        inv.setItem(50, action(Material.WRITABLE_BOOK,    "§a§l+ Add Friend", "Type a username in chat after closing"));
        inv.setItem(53, action(Material.BARRIER,          "§c§lClose",        "Close this menu"));

        // Clear list area.
        for (int i = 9; i < 54; i++) {
            if (i == 49 || i == 50 || i == 53) continue;
            inv.setItem(i, null);
        }

        // Fetch incoming requests + friends.
        TenantHttpClient.listFriendRequests(plugin, player.getUniqueId()).thenAccept(reqResp -> {
            TenantHttpClient.listFriends(plugin, player.getUniqueId()).thenAccept(friendsResp -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    entries.clear();
                    if (reqResp != null && reqResp.has("incoming")) {
                        for (var el : reqResp.getAsJsonArray("incoming")) {
                            JsonObject o = el.getAsJsonObject();
                            entries.add(new RowEntry(true,
                                o.get("id").getAsLong(),
                                null,
                                o.get("sender_username").getAsString(),
                                "request"));
                        }
                    }
                    if (friendsResp != null && friendsResp.has("friends")) {
                        for (var el : friendsResp.getAsJsonArray("friends")) {
                            JsonObject o = el.getAsJsonObject();
                            entries.add(new RowEntry(false, 0L,
                                o.get("uuid").getAsString(),
                                o.get("username").getAsString(),
                                optStr(o, "online_state")));
                        }
                    }
                    paint();
                });
            });
        });
    }

    private void paint() {
        int slot = 9;
        for (RowEntry e : entries) {
            if (slot >= 49) break;
            if (slot == 17 || slot == 26 || slot == 35 || slot == 44) {
                // skip rightmost column to keep visual gutter
                slot++;
            }
            inv.setItem(slot++, rowItem(e));
        }
    }

    private ItemStack rowItem(RowEntry e) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        if (e.username != null) {
            try {
                sm.setOwningPlayer(Bukkit.getOfflinePlayer(e.username));
            } catch (Throwable ignored) {}
        }
        if (e.isRequest) {
            sm.displayName(Component.text("⚠ " + e.username, NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
            sm.lore(List.of(
                Component.text("Sent you a friend request", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("Left-click → ACCEPT", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("Right-click → REJECT", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)
            ));
        } else {
            NamedTextColor stateColor = switch (e.state == null ? "offline" : e.state) {
                case "idle"     -> NamedTextColor.GREEN;
                case "in_queue" -> NamedTextColor.YELLOW;
                case "in_duel"  -> NamedTextColor.GOLD;
                default         -> NamedTextColor.DARK_GRAY;
            };
            String stateLabel = switch (e.state == null ? "offline" : e.state) {
                case "idle"     -> "● online";
                case "in_queue" -> "● in queue";
                case "in_duel"  -> "● in duel";
                default         -> "● offline";
            };
            sm.displayName(Component.text(e.username, NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
            sm.lore(List.of(
                Component.text(stateLabel, stateColor)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Left-click → invite to duel", NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("Right-click → unfriend", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)
            ));
        }
        head.setItemMeta(sm);
        return head;
    }

    private ItemStack action(Material mat, String name, String lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta m = item.getItemMeta();
        m.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text(lore, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(m);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!e.getInventory().equals(inv)) return;
        if (!(e.getWhoClicked() instanceof Player p) || !p.getUniqueId().equals(player.getUniqueId())) return;
        e.setCancelled(true);

        int slot = e.getRawSlot();
        boolean right = e.isRightClick();

        if (slot == 49) { refresh(); return; }
        if (slot == 50) {
            p.closeInventory();
            p.sendMessage(prefix().append(Component.text(
                "Type the username in chat: ", NamedTextColor.GRAY))
                .append(Component.text("/pvp addfriend <username>", NamedTextColor.AQUA)));
            return;
        }
        if (slot == 53) { p.closeInventory(); return; }

        // Map slot back into entries[].
        int idx = slotToIndex(slot);
        if (idx < 0 || idx >= entries.size()) return;
        RowEntry r = entries.get(idx);

        if (r.isRequest) {
            if (right) {
                TenantHttpClient.rejectFriendRequest(plugin, p.getUniqueId(), r.requestId)
                    .thenAccept(v -> Bukkit.getScheduler().runTask(plugin, this::refresh));
                p.sendMessage(prefix().append(Component.text("Rejected.", NamedTextColor.GRAY)));
            } else {
                TenantHttpClient.acceptFriendRequest(plugin, p.getUniqueId(), r.requestId)
                    .thenAccept(v -> Bukkit.getScheduler().runTask(plugin, this::refresh));
                p.sendMessage(prefix().append(Component.text("Accepted!", NamedTextColor.GREEN)));
            }
        } else {
            if (right) {
                TenantHttpClient.sendDuelInvite(plugin, p.getUniqueId(), r.uuid, "SWORD", false);
                // unfriend on right-click. Doesn't refresh until success.
                // Send DELETE via tenant — tenant API doesn't have it, so for v1 right-click is no-op.
                p.sendMessage(prefix().append(Component.text(
                    "Unfriend not yet supported from chest GUI — use the website.",
                    NamedTextColor.YELLOW)));
            } else {
                // Left-click: send a SWORD unranked duel invite as a sane default.
                TenantHttpClient.sendDuelInvite(plugin, p.getUniqueId(), r.uuid, "SWORD", false);
                p.sendMessage(prefix().append(Component.text(
                    "Sent SWORD unranked duel invite to " + r.username + ".",
                    NamedTextColor.AQUA)));
                p.closeInventory();
            }
        }
    }

    private int slotToIndex(int slot) {
        if (slot < 9 || slot >= 49) return -1;
        // skip rightmost column gutter (slots 17, 26, 35, 44).
        // Mapping: visible slots are 9..16, 18..25, 27..34, 36..43, 45..48.
        int row = (slot - 9) / 9;
        int col = (slot - 9) % 9;
        if (col == 8) return -1;
        // Each row has 8 visible cells (cols 0..7).
        return row * 8 + col;
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

    private static Component prefix() {
        return Component.text("[RevivalPVP] ", NamedTextColor.LIGHT_PURPLE);
    }

    private record RowEntry(boolean isRequest, long requestId, String uuid, String username, String state) {}
}