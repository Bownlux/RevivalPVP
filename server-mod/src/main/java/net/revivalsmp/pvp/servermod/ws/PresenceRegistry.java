// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.servermod.ws;

import net.revivalsmp.pvp.servermod.RevivalPVPServerMod;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one {@link PresenceWsClient} per online player.
 *
 * <p>PlayerJoinEvent → connect; PlayerQuitEvent → close. Connect is delayed
 * by 2 seconds so the auth_ok lands after the player's main-thread join
 * pipeline has completed (avoids flickering chat-clickable lines during the
 * "joining the game" handshake).
 */
public final class PresenceRegistry implements Listener {

    private final RevivalPVPServerMod plugin;
    private final Map<UUID, PresenceWsClient> clients = new ConcurrentHashMap<>();

    public PresenceRegistry(RevivalPVPServerMod plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Open clients for any players already online at enable-time (reload).
        for (Player p : Bukkit.getOnlinePlayers()) startFor(p);
    }

    public void shutdown() {
        for (PresenceWsClient c : clients.values()) c.close();
        clients.clear();
    }

    private void startFor(Player p) {
        if (plugin.tenantKey().isBlank()) return;  // misconfigured — skip silently.
        UUID uuid = p.getUniqueId();
        PresenceWsClient prev = clients.put(uuid, new PresenceWsClient(plugin, p));
        if (prev != null) prev.close();
        // Delay slightly so the join pipeline finishes before chat lines
        // can land. 40 ticks = 2s.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PresenceWsClient c = clients.get(uuid);
            if (c != null) c.connect();
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        startFor(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        PresenceWsClient c = clients.remove(e.getPlayer().getUniqueId());
        if (c != null) c.close();
    }
}