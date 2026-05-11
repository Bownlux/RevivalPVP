// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.servermod.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.revivalsmp.pvp.servermod.RevivalPVPServerMod;
import net.revivalsmp.pvp.servermod.ws.BackendClient;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /pvpaccept [map] — accepts a pending RevivalPVP match, optionally voting for
 * a map at the same time. Triggered from chat-click ([Red] / [SKIP / RANDOM])
 * or typed manually. No-op if the player has no pending match.
 */
public final class PvpAcceptCommand implements CommandExecutor {

    private final RevivalPVPServerMod plugin;

    public PvpAcceptCommand(RevivalPVPServerMod plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("/pvpaccept must be run by a player.");
            return true;
        }
        BackendClient bc = plugin.queueRegistry().get(p.getUniqueId());
        if (bc != null && bc.hasPendingAccept()) {
            if (args.length >= 1 && !args[0].isBlank()) {
                bc.submitMapVote(args[0]);
            }
            bc.acceptPendingMatch(p);
            return true;
        }
        p.sendMessage(Component.text("[RevivalPVP] ", NamedTextColor.LIGHT_PURPLE)
            .append(Component.text("No pending match to accept.", NamedTextColor.GRAY)));
        return true;
    }
}