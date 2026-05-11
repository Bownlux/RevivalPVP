// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.servermod.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.revivalsmp.pvp.servermod.RevivalPVPServerMod;
import net.revivalsmp.pvp.servermod.gui.FriendsGui;
import net.revivalsmp.pvp.servermod.gui.HubGui;
import net.revivalsmp.pvp.servermod.gui.LeaderboardGui;
import net.revivalsmp.pvp.servermod.network.TenantHttpClient;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class PvpCommand implements CommandExecutor {

    private final RevivalPVPServerMod plugin;

    public PvpCommand(RevivalPVPServerMod plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("/pvp must be run by a player.");
            return true;
        }
        if (plugin.tenantKey().isBlank()) {
            // Op-aware fallback: ops actually CAN fix this, so route them to
            // the setup checklist that has a clickable dashboard URL. Regular
            // players just need a heads-up that this isn't on them — they
            // shouldn't be told to "edit config.yml" because they don't have
            // server-file access.
            if (player.isOp()) {
                player.sendMessage(Component.text(
                    "RevivalPVP isn't configured yet. Run /pvpadmin setup to finish.",
                    NamedTextColor.YELLOW));
            } else {
                player.sendMessage(Component.text(
                    "RevivalPVP isn't live on this server yet. Ask your admin to finish setting it up.",
                    NamedTextColor.GRAY));
            }
            return true;
        }
        // If RevivalPVP plugin is on the same JVM, refuse /pvp commands while
        // the player is mid-duel — they can't queue another fight while still
        // actively fighting one. Only blocks the queue/hub-open flow; cancel +
        // friends + leaderboard all still work mid-duel.
        if (isInActiveDuel(player) && !subAllowedDuringDuel(args)) {
            player.sendMessage(prefix().append(Component.text(
                "You're already in a duel. Use /spectatequit if spectating, or finish your match first.",
                NamedTextColor.YELLOW)));
            return true;
        }

        // Subcommand routing.
        if (args.length >= 1) {
            String sub = args[0].toLowerCase();
            switch (sub) {
                case "cancel" -> {
                    if (plugin.queueRegistry().isQueued(player.getUniqueId())) {
                        plugin.queueRegistry().cancel(player.getUniqueId());
                        player.sendMessage(Component.text("Queue cancelled.", NamedTextColor.YELLOW));
                    } else {
                        player.sendMessage(Component.text("You are not queued.", NamedTextColor.GRAY));
                    }
                    return true;
                }
                case "friends" -> {
                    if (args.length >= 2) return handleFriendsSub(player, args);
                    new FriendsGui(plugin, player).open();
                    return true;
                }
                case "leaderboard", "lb" -> {
                    String kit = args.length >= 2 ? args[1].toUpperCase() : "SWORD";
                    new LeaderboardGui(plugin, player, kit).open();
                    return true;
                }
                case "addfriend" -> {
                    if (args.length < 2) {
                        player.sendMessage(Component.text("Usage: /pvp addfriend <username>",
                            NamedTextColor.YELLOW));
                        return true;
                    }
                    String target = args[1].trim();
                    TenantHttpClient.sendFriendRequest(plugin, player.getUniqueId(), target)
                        .thenAccept(resp -> player.sendMessage(prefix().append(Component.text(
                            resp != null ? "Friend request sent to " + target + "."
                                          : "Could not send friend request (player not found or already pending).",
                            resp != null ? NamedTextColor.GREEN : NamedTextColor.YELLOW))));
                    return true;
                }
                case "invite-accept" -> {
                    if (args.length < 2) return true;
                    boolean wasQueued = plugin.queueRegistry().isQueued(player.getUniqueId());
                    String inviteId = args[1];
                    TenantHttpClient.acceptInviteWithError(plugin, player.getUniqueId(), inviteId)
                        .thenAccept(result -> {
                            if (result.errorMessage() != null) {
                                // Surface the real reason ('Both players must be
                                // online', 'Invite expired', etc.) instead of a
                                // misleading 'no longer valid' catch-all.
                                player.sendMessage(prefix().append(Component.text(
                                    result.errorMessage(), NamedTextColor.YELLOW)));
                                return;
                            }
                            player.sendMessage(prefix().append(Component.text(
                                "Accepted! Connecting to the duel...", NamedTextColor.AQUA)));
                            if (wasQueued) {
                                player.sendMessage(prefix().append(Component.text(
                                    "Your solo queue was dropped to start the friend duel.",
                                    NamedTextColor.GRAY)));
                            }
                        });
                    return true;
                }
                case "invite-decline" -> {
                    if (args.length < 2) return true;
                    TenantHttpClient.declineInvite(plugin, player.getUniqueId(), args[1])
                        .thenAccept(resp -> player.sendMessage(prefix().append(Component.text(
                            "Declined.", NamedTextColor.GRAY))));
                    return true;
                }
                case "vote-map" -> {
                    // /pvp vote-map <matchId> <mapId>
                    if (args.length < 3) {
                        player.sendMessage(prefix().append(Component.text(
                            "Usage: /pvp vote-map <matchId> <mapId>", NamedTextColor.YELLOW)));
                        return true;
                    }
                    String mid    = args[1];
                    String mapId  = args[2];
                    TenantHttpClient.voteMap(plugin, player.getUniqueId(), mid, mapId)
                        .thenAccept(resp -> {
                            if (resp == null) {
                                player.sendMessage(prefix().append(Component.text(
                                    "Map vote not accepted (already finalized?).",
                                    NamedTextColor.YELLOW)));
                            } else {
                                player.sendMessage(prefix().append(Component.text(
                                    "Voted: " + mapId, NamedTextColor.AQUA)));
                            }
                        });
                    return true;
                }
            }
        }

        new HubGui(plugin, player).open();
        return true;
    }

    private boolean handleFriendsSub(Player player, String[] args) {
        // /pvp friends accept <id> | reject <id> | invite <username>
        String op = args[1].toLowerCase();
        switch (op) {
            case "accept" -> {
                if (args.length < 3) return true;
                long id;
                try { id = Long.parseLong(args[2]); }
                catch (NumberFormatException e) { return true; }
                TenantHttpClient.acceptFriendRequest(plugin, player.getUniqueId(), id)
                    .thenAccept(resp -> player.sendMessage(prefix().append(Component.text(
                        resp != null ? "Friend request accepted." : "Could not accept.",
                        NamedTextColor.GREEN))));
            }
            case "reject" -> {
                if (args.length < 3) return true;
                long id;
                try { id = Long.parseLong(args[2]); }
                catch (NumberFormatException e) { return true; }
                TenantHttpClient.rejectFriendRequest(plugin, player.getUniqueId(), id)
                    .thenAccept(resp -> player.sendMessage(prefix().append(Component.text(
                        "Rejected.", NamedTextColor.GRAY))));
            }
            default -> player.sendMessage(prefix().append(Component.text(
                "Unknown subcommand. Use /pvp friends to open the menu.", NamedTextColor.GRAY)));
        }
        return true;
    }

    private static Component prefix() {
        return Component.text("[RevivalPVP] ", NamedTextColor.LIGHT_PURPLE);
    }

    /** Subcommands that are safe to use mid-duel (cancel queue, browse, etc). */
    private static boolean subAllowedDuringDuel(String[] args) {
        if (args.length < 1) return false;
        String sub = args[0].toLowerCase();
        return switch (sub) {
            case "cancel", "leaderboard", "lb", "friends", "addfriend",
                 "invite-accept", "invite-decline" -> true;
            default -> false;
        };
    }

    /** Same-JVM check: ask the local RevivalPVP plugin if the player is in a
     *  duel session. Reflective so we don't compile-time-depend on it. */
    private static boolean isInActiveDuel(Player p) {
        try {
            org.bukkit.plugin.Plugin pvp = org.bukkit.Bukkit.getPluginManager().getPlugin("RevivalPVP");
            if (pvp == null) return false;
            Object dm = pvp.getClass().getMethod("duelManager").invoke(pvp);
            if (dm == null) return false;
            Object inDuel = dm.getClass().getMethod("isInDuel", java.util.UUID.class)
                .invoke(dm, p.getUniqueId());
            return inDuel instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }
}