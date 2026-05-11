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
import java.util.Map;
import java.util.UUID;

/**
 * /pvp profile — chest GUI showing the caller's own per-kit rank/LP plus a
 * "Generate Web Login Code" action that lets them sign into revivalpvp.net
 * without ever leaving the game (hands them a 6-char code valid for 5 min).
 *
 * <p>Useful primarily for non-mod users (e.g. Bedrock) who can't see the
 * client-mod's hub but still want to check progression and link the website.
 *
 * <p>Layout (54-slot chest):
 *   slot 4         player head + summary
 *   slots 9-17     per-kit rank tiles (one per kit, in playstyle order)
 *   slot 31        [Generate Web Login Code] (paper)
 *   slot 53        [Close]
 */
public class ProfileGui implements Listener {

    private static final int SIZE = 54;

    /** Same kit-icon mapping the leaderboard uses; keeps the UI consistent.
     *  Map.ofEntries because we exceed Map.of's 10-entry overload limit. */
    private static final Map<String, Material> KIT_ICONS = Map.ofEntries(
        Map.entry("FIST",     Material.PLAYER_HEAD),
        Map.entry("SWORD",    Material.IRON_SWORD),
        Map.entry("ARCHER",   Material.BOW),
        Map.entry("CROSSBOW", Material.CROSSBOW),
        Map.entry("MACE",     Material.MACE),
        Map.entry("CRYSTAL",  Material.END_CRYSTAL),
        Map.entry("SPEAR",    Material.IRON_SPEAR),
        Map.entry("TRIDENT",  Material.TRIDENT),
        Map.entry("TNT",      Material.TNT),
        Map.entry("POTIONS",  Material.SPLASH_POTION),
        Map.entry("AXE",      Material.NETHERITE_AXE)
    );
    /** Render order — same as HubGui kit-row layout (row 1 frontline, row 2 polearm/explosive/utility). */
    private static final List<String> KIT_ORDER = List.of(
        "FIST", "SWORD", "ARCHER", "CROSSBOW", "MACE", "CRYSTAL",
        "SPEAR", "TRIDENT", "TNT", "POTIONS", "AXE");

    private final RevivalPVPServerMod plugin;
    private final Player              player;
    private final Inventory           inv;

    /** Most recent web-login code shown so a follow-up Close → reopen still
     *  shows the same code instead of double-issuing. Null when no code is
     *  outstanding for this GUI session. */
    private String  loginCode;
    private long    loginCodeIssuedAt;
    private int     loginCodeTtl;
    private boolean codeRequesting;

    public ProfileGui(RevivalPVPServerMod plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inv    = Bukkit.createInventory(player, SIZE,
            Component.text("RevivalPVP — " + player.getName(), NamedTextColor.LIGHT_PURPLE));
    }

    public void open() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        player.openInventory(inv);
        loadStats();
    }

    private void loadStats() {
        // Header placeholder while loading.
        inv.setItem(4, infoIcon(Material.PAPER, "§7Loading profile...", null));

        TenantHttpClient.playerStats(plugin, player.getName())
            .thenAccept(resp -> Bukkit.getScheduler().runTask(plugin, () -> render(resp)));
    }

    private void render(JsonObject resp) {
        // Player head + summary at top.
        inv.setItem(4, headSummary(resp));

        if (resp == null) {
            inv.setItem(22, infoIcon(Material.BARRIER,
                "§cFailed to load profile.",
                "Check console for details."));
        } else {
            JsonArray kitStats = resp.has("kit_stats") && resp.get("kit_stats").isJsonArray()
                ? resp.getAsJsonArray("kit_stats")
                : new JsonArray();
            // Two-row layout matches HubGui: 6 frontline kits in row 2 (10-15),
            // 5 polearm/explosive/utility kits in row 3 (19-23).
            int[] slots = {10, 11, 12, 13, 14, 15, 19, 20, 21, 22, 23};
            for (int i = 0; i < KIT_ORDER.size() && i < slots.length; i++) {
                String kit = KIT_ORDER.get(i);
                inv.setItem(slots[i], kitTile(kit, findKitStat(kitStats, kit)));
            }
        }

        // Web login code action.
        inv.setItem(31, webCodeIcon());
        inv.setItem(53, action(Material.BARRIER, "§c§lClose", "Close this menu"));
    }

    private ItemStack headSummary(JsonObject resp) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) head.getItemMeta();
        try { sm.setOwningPlayer(player); } catch (Throwable ignored) {}
        sm.displayName(Component.text(player.getName(), NamedTextColor.AQUA)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (resp != null) {
            int totalGames = 0, totalWins = 0;
            JsonArray ks = resp.has("kit_stats") ? resp.getAsJsonArray("kit_stats") : new JsonArray();
            for (var el : ks) {
                JsonObject o = el.getAsJsonObject();
                totalGames += o.has("games") ? o.get("games").getAsInt() : 0;
                totalWins  += o.has("wins")  ? o.get("wins").getAsInt()  : 0;
            }
            lore.add(Component.text("Lifetime: §f" + totalWins + "W / "
                    + (totalGames - totalWins) + "L  (" + totalGames + " games)",
                NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Per-kit ranks below ↓", NamedTextColor.DARK_GRAY)
            .decoration(TextDecoration.ITALIC, false));
        sm.lore(lore);
        head.setItemMeta(sm);
        return head;
    }

    private JsonObject findKitStat(JsonArray arr, String kit) {
        for (var el : arr) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("kit") && kit.equalsIgnoreCase(o.get("kit").getAsString())) return o;
        }
        return null;
    }

    private ItemStack kitTile(String kit, JsonObject stat) {
        Material mat = KIT_ICONS.getOrDefault(kit, Material.PAPER);
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(kit, NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        if (stat == null) {
            lore.add(Component.text("§7Unranked").decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("§8No matches played").decoration(TextDecoration.ITALIC, false));
        } else {
            String rank = stat.has("rank") && !stat.get("rank").isJsonNull()
                ? stat.get("rank").getAsString() : "UNRANKED";
            String div  = stat.has("division") && !stat.get("division").isJsonNull()
                ? stat.get("division").getAsString() : "";
            int lp      = stat.has("lp") ? stat.get("lp").getAsInt() : 0;
            int games   = stat.has("games") ? stat.get("games").getAsInt() : 0;
            int wins    = stat.has("wins") ? stat.get("wins").getAsInt() : 0;
            boolean placementDone = stat.has("placement_done") && stat.get("placement_done").getAsBoolean();
            int placementGames    = stat.has("placement_games") ? stat.get("placement_games").getAsInt() : 0;

            lore.add(Component.text("§b" + capitalize(rank)
                    + (div.isBlank() ? "" : " " + div)
                    + " §7— §f" + lp + " LP",
                NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            if (games > 0) {
                int wr = wins * 100 / games;
                lore.add(Component.text("W/L: " + wins + "/" + (games - wins) + " (" + wr + "%)",
                    NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            if (!placementDone) {
                lore.add(Component.text("§ePlacement: " + placementGames + "/10 games",
                    NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack webCodeIcon() {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        if (loginCode == null) {
            meta.displayName(Component.text(
                codeRequesting ? "§7Requesting code..." : "§a§l▶ Get Web Login Code",
                codeRequesting ? NamedTextColor.GRAY : NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text("Click to generate a 6-char code.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("Visit revivalpvp.net/login and enter", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("the code to sign in. Valid for 5 minutes.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ));
        } else {
            int secsLeft = Math.max(0,
                loginCodeTtl - (int)((System.currentTimeMillis() - loginCodeIssuedAt) / 1000));
            meta.displayName(Component.text("§a§lCode: §f§l" + loginCode, NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text("Visit §brevivalpvp.net/login", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("and enter this code.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Expires in " + secsLeft / 60 + ":"
                    + String.format("%02d", secsLeft % 60), NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("Click to request a new code.", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ));
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack action(Material mat, String name, String desc) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        if (desc != null) {
            meta.lore(List.of(Component.text("§7" + desc).decoration(TextDecoration.ITALIC, false)));
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoIcon(Material mat, String name, String desc) {
        return action(mat, name, desc);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!e.getInventory().equals(inv)) return;
        if (!(e.getWhoClicked() instanceof Player p) || !p.getUniqueId().equals(player.getUniqueId())) return;
        e.setCancelled(true);

        int slot = e.getRawSlot();
        if (slot == 53) { p.closeInventory(); return; }
        if (slot == 31) { requestWebCode(p); return; }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!e.getInventory().equals(inv)) return;
        UUID viewer = e.getPlayer().getUniqueId();
        if (!viewer.equals(player.getUniqueId())) return;
        HandlerList.unregisterAll(this);
    }

    private void requestWebCode(Player p) {
        // Tenant-keyed web-code minting was removed for security: anyone
        // with a leaked tenant key could mint a code for any player who
        // had ever queued, redeem it on the site, and take over the
        // account. Replaced with an in-chat link to revivalpvp.net/login
        // where the player runs through the normal mod-issued JWT flow.
        codeRequesting = false;
        p.closeInventory();
        p.sendMessage(Component.text("Sign in at ", NamedTextColor.GRAY)
            .append(Component.text("https://revivalpvp.net/login", NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl("https://revivalpvp.net/login"))
                .hoverEvent(HoverEvent.showText(Component.text("Open in browser"))))
            .append(Component.text(" to access your account.", NamedTextColor.GRAY)));
    }
}