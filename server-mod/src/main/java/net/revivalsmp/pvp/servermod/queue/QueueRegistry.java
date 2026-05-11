// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.servermod.queue;

import net.revivalsmp.pvp.servermod.RevivalPVPServerMod;
import net.revivalsmp.pvp.servermod.ws.BackendClient;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks one BackendClient per queued player. The client opens when the
 * player clicks "Queue" and closes when the match is found / queue
 * timeouts / player cancels. We never multiplex players over a single
 * WebSocket — keeps the protocol simple and scopes per-player state.
 */
public final class QueueRegistry {

    private final RevivalPVPServerMod plugin;
    private final Map<UUID, BackendClient> clients = new ConcurrentHashMap<>();

    public QueueRegistry(RevivalPVPServerMod plugin) {
        this.plugin = plugin;
    }

    /** Open a fresh BackendClient for this player and start the queue flow. */
    public BackendClient startQueue(Player player, String kit, boolean ranked, String scope) {
        BackendClient prev = clients.remove(player.getUniqueId());
        if (prev != null) prev.close();
        BackendClient bc = new BackendClient(plugin, player, kit, ranked, scope);
        clients.put(player.getUniqueId(), bc);
        bc.connect();
        return bc;
    }

    public BackendClient get(UUID uuid) { return clients.get(uuid); }

    public void cancel(UUID uuid) {
        BackendClient bc = clients.remove(uuid);
        if (bc != null) bc.close();
    }

    public boolean isQueued(UUID uuid) {
        BackendClient bc = clients.get(uuid);
        return bc != null && bc.isActive();
    }

    public void shutdown() {
        for (BackendClient bc : clients.values()) bc.close();
        clients.clear();
    }
}