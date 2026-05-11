// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.servermod.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
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
 * 3-row chest "Player Detail" view.
 *   slot 11 — skull (player's head, lore = ranks)
 *   slot 13 — book (sponsor totals + top sponsors)
 *   slot 15 — HEART_OF_THE_SEA "Sponsor" — left-click=1, shift-click=5
 *   slot 22 — back arrow → re-opens leaderboard
 *
 * Title: "Player Detail · ♥ N" where N = caller's key balance, fetched on open.
 */
public class PlayerDetailGui implements Listener {

    private static final int SIZE = 27;

    private final RevivalPVPServerMod plugin;
    private final Player              player;
    private final LeaderboardGui.Entry target;
    private final Inventory           inv;

    private int    callerKeyBalance = 0;
    private JsonObject sponsorProfile;

    public PlayerDetailGui(RevivalPVPServerMod plugin, Player player, LeaderboardGui.Entry target) {
        this.plugin = plugin;
        this.player = player;
        this.target = target;
        this.inv    = Bukkit.createInventory(player, SIZE,
            Component.text("Player Detail · ♥ ?", NamedTextColor.LIGHT_PURPLE));
    }

    public void open() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        player.openInventory(inv);
        repaint();
        // Fetch caller coin balance + target's sponsor profile in parallel.
        TenantHttpClient.myCoins(plugin, player.getUniqueId()).thenAccept(resp -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (resp != null && resp.has("balance") && !resp.get("balance").isJsonNull()) {
                    callerKeyBalance = resp.get("balance").getAsInt();
                }
                repaint();
            });
        });
        TenantHttpClient.sponsorProfile(plugin, target.username()).thenAccept(resp -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                sponsorProfile = resp;
                repaint();
            });
        });
    }

    private void repaint() {
        // Title is immutable on the open inventory in Bukkit, so the caller has
        // to reopen if they want a refreshed `♥ N`. For v1 we set it once on the
        // first repaint by re-creating the inventory if balance just landed.

        for (int i = 0; i < SIZE; i++) inv.setItem(i, null);

        // Slot 11: head with ranks.
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        if (target.username() != null) {
            try { sm.setOwningPlayer(Bukkit.getOfflinePlayer(target.username())); }
            catch (Throwable ignored) {}
        }
        sm.displayName(Component.text(target.username(), NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false));
        List<Component> hl = new ArrayList<>();
        hl.add(Component.text("Global rank #" + target.position(), NamedTextColor.GOLD)
            .decoration(TextDecoration.ITALIC, false));
        hl.add(Component.text(
            (target.rank() == null ? "Unranked" : target.rank()
                + (target.division() == null || target.division().isBlank() ? "" : " " + target.division()))
                + " — " + target.lp() + " LP",
            NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        if (target.games() > 0) {
            int wr = (int) (target.wins() * 100.0 / target.games());
            hl.add(Component.text("W/L: " + target.wins() + "/" + (target.games() - target.wins())
                + "  (" + wr + "%)", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        sm.lore(hl);
        head.setItemMeta(sm);
        inv.setItem(11, head);

        // Slot 13: sponsor profile book.
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        ItemMeta bm = book.getItemMeta();
        bm.displayName(Component.text("Sponsorship", NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.ITALIC, false));
        List<Component> bl = new ArrayList<>();
        if (sponsorProfile == null) {
            bl.add(Component.text("Loading...", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        } else {
            int total  = sponsorProfile.has("total_coins")  ? sponsorProfile.get("total_coins").getAsInt()  : 0;
            int season = sponsorProfile.has("season_coins") ? sponsorProfile.get("season_coins").getAsInt() : 0;
            bl.add(Component.text("Total received: ♥ " + total, NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
            bl.add(Component.text("This season: ♥ " + season, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
            if (sponsorProfile.has("top_sponsors")) {
                JsonArray ts = sponsorProfile.getAsJsonArray("top_sponsors");
                if (ts.size() > 0) {
                    bl.add(Component.empty());
                    bl.add(Component.text("Top sponsors:", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
                    for (int i = 0; i < Math.min(5, ts.size()); i++) {
                        JsonObject s = ts.get(i).getAsJsonObject();
                        String name = optStr(s, "sponsor_name");
                        int coins = s.has("coins_spent") ? s.get("coins_spent").getAsInt() : 0;
                        bl.add(Component.text(" " + (i + 1) + ". " + name + "  ♥" + coins,
                            NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
                    }
                }
            }
        }
        bm.lore(bl);
        book.setItemMeta(bm);
        inv.setItem(13, book);

        // Slot 15: HEART_OF_THE_SEA — sponsor 10 (left) / 100 (shift) / 500 (right).
        ItemStack heart = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta hm = heart.getItemMeta();
        hm.displayName(Component.text("♥ Sponsor " + target.username(), NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.ITALIC, false));
        List<Component> sl = new ArrayList<>();
        sl.add(Component.text("Your balance: ♥ " + callerKeyBalance + " coins", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        sl.add(Component.empty());
        sl.add(Component.text("Left-click → spend 10 coins",   NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        sl.add(Component.text("Shift-click → spend 100 coins", NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        sl.add(Component.text("Right-click → spend 500 coins", NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        if (callerKeyBalance == 0) {
            sl.add(Component.empty());
            sl.add(Component.text("Buy Sponsor Coins at revivalpvp.net", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        }
        hm.lore(sl);
        heart.setItemMeta(hm);
        inv.setItem(15, heart);

        // Slot 22: back arrow.
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta am = back.getItemMeta();
        am.displayName(Component.text("← Back to Leaderboard", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        back.setItemMeta(am);
        inv.setItem(22, back);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!e.getInventory().equals(inv)) return;
        if (!(e.getWhoClicked() instanceof Player p) || !p.getUniqueId().equals(player.getUniqueId())) return;
        e.setCancelled(true);

        int slot = e.getRawSlot();
        if (slot == 22) {
            HandlerList.unregisterAll(this);
            p.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () ->
                new LeaderboardGui(plugin, p, "SWORD").open());
            return;
        }
        if (slot == 15) {
            int coins;
            // Tenant-keyed sponsor spend was removed for security: with
            // only X-Tenant-Key + X-MC-UUID, an attacker holding any
            // leaked tenant key could drain any player's sponsor balance
            // (audit finding #2). Sponsor spend now requires a real
            // player JWT, surfaced through the website only.
            p.closeInventory();
            p.sendMessage(prefix().append(Component.text("Sponsor at ", NamedTextColor.GRAY))
                .append(Component.text("https://revivalpvp.net/", NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.openUrl("https://revivalpvp.net/"))
                    .hoverEvent(HoverEvent.showText(Component.text("Open in browser"))))
                .append(Component.text(" — sign in to support " + target.username() + ".",
                    NamedTextColor.GRAY)));
        }
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
}