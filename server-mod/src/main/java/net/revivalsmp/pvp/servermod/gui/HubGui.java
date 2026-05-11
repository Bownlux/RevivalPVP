// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.servermod.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.revivalsmp.pvp.servermod.RevivalPVPServerMod;
import net.revivalsmp.pvp.servermod.kits.KitCatalogCache;
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
 * One-shot chest GUI shown by /pvp. Player picks kit + scope + ranked,
 * clicks "Queue" to start. State is per-instance (per-player, per-open).
 *
 * Bare-bones for v1: no kit previews / leaderboards / pretty borders.
 * Just the controls needed to send a queue_join.
 */
public class HubGui implements Listener {

    private static final int SIZE = 36;  // 4-row chest (top bar / kits row 1 / kits row 2 / controls)

    private final RevivalPVPServerMod plugin;
    private final Player              player;
    private final Inventory           inv;

    private String  kit;
    private String  scope;

    /** Slot indexes the chest GUI assigns kits to, in catalog sort order.
     *  14 slots across rows 2 (10-16) and 3 (19-25). If the catalog has
     *  more kits than this we truncate with a log warning rather than
     *  silently dropping — that's a signal the layout needs paging. */
    private static final int[] KIT_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
    };

    /** Cache of slot → kit name resolved at layout time so the click
     *  handler can map an InventoryClickEvent's slot back to the kit
     *  without iterating the catalog again on every click. */
    private final java.util.Map<Integer, String> slotToKit = new java.util.HashMap<>();
    // Default to Unranked. New players were accidentally queueing into ranked
    // (and burning placement matches) before they understood the system.
    // Players who actually want ranked can toggle it in one click.
    private boolean ranked = false;

    public HubGui(RevivalPVPServerMod plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.scope  = plugin.defaultScope();
        // Default kit picks the first kit in the catalog so a player who
        // never touched their selection has something valid pre-selected.
        List<KitCatalogCache.Kit> catalog = plugin.kitCatalog().get();
        this.kit    = catalog.isEmpty() ? "SWORD" : catalog.get(0).name();
        this.inv    = Bukkit.createInventory(player, SIZE,
            Component.text("RevivalPVP — Queue", NamedTextColor.LIGHT_PURPLE));
        layout();
    }

    public void open() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        player.openInventory(inv);
    }

    private void layout() {
        // Row 1 (slots 0-8): top toolbar — profile + spectate + friends + leaderboard (right-anchored).
        inv.setItem(5, profileIcon());
        inv.setItem(6, spectateIcon());
        inv.setItem(7, friendsIcon());
        inv.setItem(8, leaderboardIcon());

        // Rows 2-3 (slots 10-16, 19-25): kit picker — fully data-driven via
        // KitCatalogCache → /kits/catalog so adding/renaming a kit on the
        // backend doesn't require a re-jar. mod_only kits are filtered out
        // by the cache since the chest GUI is the path for non-mod players.
        slotToKit.clear();
        List<KitCatalogCache.Kit> kits = plugin.kitCatalog().get();
        int placed = 0;
        for (KitCatalogCache.Kit k : kits) {
            if (placed >= KIT_SLOTS.length) {
                plugin.getLogger().warning("Kit catalog has " + kits.size()
                    + " entries but the chest GUI only has " + KIT_SLOTS.length
                    + " slots — truncating. Consider widening the layout.");
                break;
            }
            int slot = KIT_SLOTS[placed++];
            inv.setItem(slot, kitIcon(k.icon(), k.name(), k.display()));
            slotToKit.put(slot, k.name());
        }

        // Row 4 (slots 27-35): scope, ranked, customize, queue.
        inv.setItem(28, scopeIcon());
        inv.setItem(31, rankedIcon());
        inv.setItem(32, customizeIcon());
        inv.setItem(34, queueIcon());
    }

    private ItemStack profileIcon() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.SkullMeta sm) {
            try { sm.setOwningPlayer(player); } catch (Throwable ignored) {}
        }
        meta.displayName(Component.text("My Profile", NamedTextColor.GOLD)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Your rank + LP per kit", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Generate a web login code for revivalpvp.net.",
                NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack spectateIcon() {
        ItemStack item = new ItemStack(Material.SPYGLASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Spectate Live Duels", NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Watch any duel that's currently live.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Click to browse the live match list.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack friendsIcon() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Friends", NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Open your friend list", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Send duel invites · Accept requests", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack leaderboardIcon() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Leaderboard", NamedTextColor.GOLD)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Top 100 SWORD players", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Click to view profiles · ♥ Sponsor", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack customizeIcon() {
        ItemStack item = new ItemStack(Material.CRAFTING_TABLE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Customize Loadout", NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Pick a variant for each slot of the", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("currently-selected kit (" + kit + ").", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.empty(),
            Component.text("Requires a queue session — if you", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("haven't queued yet this login, do that", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("once first to authenticate.", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack kitIcon(Material mat, String kitName, String label) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        boolean selected = kitName.equals(this.kit);
        Component name = Component.text(label,
            selected ? NamedTextColor.GREEN : NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false);
        meta.displayName(name);
        meta.lore(List.of(
            Component.text(selected ? "Selected" : "Click to select",
                selected ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack scopeIcon() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Match Scope: " + scope, NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("local",  "Only players on this server"));
        lore.add(line("region", "Only players in your region"));
        lore.add(line("global", "Anyone (fastest queues)"));
        lore.add(Component.empty());
        lore.add(Component.text("Click to cycle.", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private Component line(String s, String desc) {
        boolean sel = s.equals(scope);
        return Component.text((sel ? "▶ " : "  ") + s + " — " + desc,
            sel ? NamedTextColor.GREEN : NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack rankedIcon() {
        ItemStack item = new ItemStack(ranked ? Material.NETHER_STAR : Material.GLOWSTONE_DUST);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(
            ranked ? "Ranked" : "Unranked",
            ranked ? NamedTextColor.GOLD : NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text(ranked
                ? "LP/rank changes apply"
                : "No LP/rank changes",
                NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
            Component.text("Click to toggle.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack queueIcon() {
        ItemStack item = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("▶ Queue", NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text("Kit: "   + kit,    NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
            Component.text("Scope: " + scope,  NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
            Component.text("Mode: "  + (ranked ? "Ranked" : "Unranked"),
                NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!e.getInventory().equals(inv)) return;
        if (!(e.getWhoClicked() instanceof Player p) || !p.getUniqueId().equals(player.getUniqueId())) return;
        e.setCancelled(true);

        int slot = e.getRawSlot();
        // Kit picker: any slot the layout assigned to a kit. Built from the
        // backend's /kits/catalog so we don't have to keep a hardcoded
        // slot↔kit switch in sync.
        String clickedKit = slotToKit.get(slot);
        if (clickedKit != null) {
            kit = clickedKit;
            layout();
            return;
        }
        switch (slot) {
            // Row 1 — top toolbar
            case 5  -> openProfile(p);
            case 6  -> openSpectate(p);
            case 7  -> openFriends(p);
            case 8  -> openLeaderboard(p);
            // Row 4 — controls
            case 28 -> { scope = nextScope(scope); layout(); }
            case 31 -> { ranked = !ranked; layout(); }
            case 32 -> openLoadout(p);
            case 34 -> queue(p);
            default -> {}
        }
    }

    private void openProfile(Player p) {
        HandlerList.unregisterAll(this);
        p.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> new ProfileGui(plugin, p).open());
    }

    private void openSpectate(Player p) {
        HandlerList.unregisterAll(this);
        p.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> new LiveMatchesGui(plugin, p).open());
    }

    private void openFriends(Player p) {
        HandlerList.unregisterAll(this);
        p.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> new FriendsGui(plugin, p).open());
    }

    private void openLeaderboard(Player p) {
        HandlerList.unregisterAll(this);
        p.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> new LeaderboardGui(plugin, p, kit).open());
    }

    private void openLoadout(Player p) {
        // Loadout endpoints are keyed by the backend's internal player_uuid,
        // which we cache from the WS auth_ok payload. If the player hasn't
        // queued yet this session, the cache is empty — bounce them back
        // with a hint instead of opening a broken GUI.
        if (plugin.getInternalUuid(p.getUniqueId()) == null) {
            p.closeInventory();
            p.sendMessage(Component.text(
                "Join a queue once first to enable loadout editing.",
                NamedTextColor.YELLOW));
            return;
        }
        HandlerList.unregisterAll(this);
        p.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> new LoadoutGui(plugin, p, kit).open());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!e.getInventory().equals(inv)) return;
        UUID viewer = e.getPlayer().getUniqueId();
        if (!viewer.equals(player.getUniqueId())) return;
        HandlerList.unregisterAll(this);
    }

    private void queue(Player p) {
        p.closeInventory();
        if (plugin.queueRegistry().isQueued(p.getUniqueId())) {
            p.sendMessage(Component.text("Already in queue. Use /pvp cancel to leave.", NamedTextColor.YELLOW));
            return;
        }
        plugin.queueRegistry().startQueue(p, kit, ranked, scope);
        p.sendMessage(Component.text("Connecting to RevivalPVP matchmaking...", NamedTextColor.GRAY));
    }

    private static String nextScope(String s) {
        return switch (s) {
            case "local"  -> "region";
            case "region" -> "global";
            default       -> "local";
        };
    }
}