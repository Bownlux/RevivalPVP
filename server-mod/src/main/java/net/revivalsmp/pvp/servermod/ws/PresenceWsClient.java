// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.servermod.ws;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.revivalsmp.pvp.servermod.RevivalPVPServerMod;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Long-lived WebSocket connection per online player. Authenticates with
 * tenant_auth (same flow as the queue WS at {@link BackendClient}), then
 * sits idle until the backend pushes a friend or duel-invite event. On
 * push, surfaces it as a chat-clickable line + Title banner so Bedrock and
 * non-mod Java players can act on it without opening any GUI.
 *
 * <p>Lifecycle: opened on PlayerJoinEvent, closed on PlayerQuitEvent. The
 * backend supports multiple WS connections per player_uuid, so a queueing
 * BackendClient can coexist without overwriting this connection's
 * registration in the connection map.
 */
@ClientEndpoint
public class PresenceWsClient {

    private static final Gson GSON = new Gson();

    private final RevivalPVPServerMod plugin;
    private final UUID    playerUuid;
    private final String  playerName;

    private volatile Session session;
    private volatile boolean active = true;
    /** True once we've received auth_ok. Used to avoid spamming reconnect logs. */
    private volatile boolean authed;

    // Friend-duel match_found state. PresenceWsClient handles this surface
    // when the player accepts a duel invite without going through /pvp queue
    // — there is no transient queue WS, so match_found arrives here.
    private volatile String pendingMatchHost;
    private volatile int    pendingMatchPort;
    private volatile String pendingMatchToken;
    private volatile BukkitTask pendingTransferTask;

    public PresenceWsClient(RevivalPVPServerMod plugin, Player player) {
        this.plugin     = plugin;
        this.playerUuid = player.getUniqueId();
        this.playerName = player.getName();
    }

    public void connect() {
        if (!active) return;
        Thread.ofVirtual().name("rpvp-presence-" + playerName).start(() -> {
            ClassLoader pluginCl = PresenceWsClient.class.getClassLoader();
            Thread.currentThread().setContextClassLoader(pluginCl);
            try {
                WebSocketContainer c = ContainerProvider.getWebSocketContainer();
                c.connectToServer(this, URI.create(plugin.backendWsUrl()));
            } catch (Exception e) {
                if (active) {
                    plugin.getLogger().warning(
                        "Presence WS connect failed for " + playerName + ": " + e.getMessage());
                }
            }
        });
    }

    public void close() {
        active = false;
        if (pendingTransferTask != null) {
            try { pendingTransferTask.cancel(); } catch (Exception ignored) {}
            pendingTransferTask = null;
        }
        try { if (session != null && session.isOpen()) session.close(); } catch (Exception ignored) {}
        session = null;
    }

    public boolean isActive() { return active; }

    @OnOpen
    public void onOpen(Session s) {
        this.session = s;
        // Same auth shape as BackendClient.onOpen — tenant_auth resolves
        // mc_uuid → internal player_uuid + registers this WS in the backend's
        // per-player connection list.
        JsonObject auth = new JsonObject();
        auth.addProperty("type",          "tenant_auth");
        auth.addProperty("api_key",       plugin.tenantKey());
        auth.addProperty("mc_uuid",       playerUuid.toString());
        auth.addProperty("username",      playerName);
        auth.addProperty("origin_server", plugin.originServer());
        send(auth);
    }

    @OnMessage
    public void onMessage(String text) {
        try {
            JsonObject msg = GSON.fromJson(text, JsonObject.class);
            String type = msg.has("type") ? msg.get("type").getAsString() : "";
            switch (type) {
                case "auth_ok"                 -> authed = true;
                case "friend_request_received" -> handleFriendRequest(msg);
                case "duel_invite_received"    -> handleDuelInvite(msg);
                case "duel_invite_resolved"    -> handleInviteResolved(msg);
                case "friend_added"            -> handleFriendAdded(msg);
                case "match_found"             -> handleFriendMatchFound(msg);
                case "map_selected"            -> {}  // logged by backend; transfer is timer-driven
                case "ping","pong"             -> {}
                default                        -> {} // ignore other queue-side messages
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Presence WS parse error: " + e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session s, CloseReason reason) {
        // Don't auto-reconnect — the player's quit handler owns the lifecycle.
        // If they're still online and we were dropped (network), the server
        // will surface the issue when push messages stop landing.
        session = null;
        authed = false;
    }

    @OnError
    public void onError(Session s, Throwable t) {
        plugin.getLogger().warning("Presence WS error for " + playerName + ": " + t.getMessage());
    }

    // ── Push handlers ────────────────────────────────────────────────────────

    private void handleFriendRequest(JsonObject msg) {
        long requestId = msg.has("request_id") ? msg.get("request_id").getAsLong() : 0L;
        String sender  = optStr(msg, "sender_username", "Someone");
        if (requestId == 0L) return;

        notifyOnMain(p -> {
            p.sendMessage(Component.empty());
            p.sendMessage(prefix()
                .append(Component.text(sender, NamedTextColor.WHITE))
                .append(Component.text(" sent you a friend request — ", NamedTextColor.GRAY))
                .append(actionPill("✓ ACCEPT", NamedTextColor.GREEN,
                    "/pvp friends accept " + requestId,
                    "Accept " + sender + "'s friend request"))
                .append(Component.space())
                .append(actionPill("✗ REJECT", NamedTextColor.RED,
                    "/pvp friends reject " + requestId,
                    "Reject and dismiss")));
            try { p.playSound(p.getLocation(), Sound.UI_TOAST_IN, 1f, 1f); } catch (Throwable ignored) {}
        });
    }

    private void handleDuelInvite(JsonObject msg) {
        String inviteId = optStr(msg, "invite_id", "");
        String sender   = optStr(msg, "sender_username", "Someone");
        String kit      = optStr(msg, "kit", "SWORD");
        boolean ranked  = msg.has("ranked") && msg.get("ranked").getAsBoolean();
        // Backend sends the actual TTL in expires_in (seconds). Default to 60s
        // if missing so an older payload doesn't render '0s'.
        int expiresIn   = msg.has("expires_in") && !msg.get("expires_in").isJsonNull()
            ? Math.max(10, msg.get("expires_in").getAsInt())
            : 60;
        if (inviteId.isEmpty()) return;

        // Format countdown: use minutes:seconds when >= 60s, else just Xs.
        String countdown = expiresIn >= 60
            ? String.format("%dm%02ds", expiresIn / 60, expiresIn % 60)
            : expiresIn + "s";

        // Title banner duration — matches expires_in but capped at 10s so we
        // don't pin a giant banner on screen for 5 minutes.
        int titleStaySecs = Math.min(10, expiresIn);

        notifyOnMain(p -> {
            p.sendMessage(Component.empty());
            p.sendMessage(prefix()
                .append(Component.text(sender, NamedTextColor.WHITE))
                .append(Component.text(" invited you to a ", NamedTextColor.GRAY))
                .append(Component.text(kit, NamedTextColor.AQUA)
                    .decoration(TextDecoration.BOLD, true))
                .append(Component.space())
                .append(Component.text(ranked ? "ranked" : "unranked", NamedTextColor.YELLOW))
                .append(Component.text(" duel ", NamedTextColor.GRAY))
                .append(Component.text("(" + countdown + ") ", NamedTextColor.DARK_GRAY))
                .append(Component.text("— ", NamedTextColor.GRAY))
                .append(actionPill("✓ ACCEPT", NamedTextColor.GREEN,
                    "/pvp invite-accept " + inviteId,
                    "Accept and enter matchmaking immediately"))
                .append(Component.space())
                .append(actionPill("✗ DECLINE", NamedTextColor.RED,
                    "/pvp invite-decline " + inviteId,
                    "Decline duel invite")));

            Component mainTitle = Component.text("Duel Invite!", NamedTextColor.AQUA)
                .decoration(TextDecoration.BOLD, true);
            Component subtitle = Component.text("from " + sender + " — see chat", NamedTextColor.GRAY);
            try {
                p.showTitle(Title.title(mainTitle, subtitle, Title.Times.times(
                    Duration.ofMillis(200), Duration.ofSeconds(titleStaySecs), Duration.ofMillis(500))));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            } catch (Throwable ignored) {}
        });
    }

    private void handleInviteResolved(JsonObject msg) {
        String state = optStr(msg, "state", "");
        // Don't spam chat; only surface on expiry / decline. Accept is followed
        // by the standard match_found flow which has its own messaging.
        if ("expired".equals(state) || "declined".equals(state)) {
            notifyOnMain(p -> p.sendMessage(prefix().append(Component.text(
                "Duel invite " + state + ".", NamedTextColor.GRAY))));
        }
    }

    /**
     * match_found from a friend-duel accept. There is no queue WS for this
     * player, so the transfer must originate from the presence WS. Wait
     * 14s before transferring so the backend's map-vote finalize (12s) has
     * time to write map_name into Redis before the duel server reads it.
     */
    private void handleFriendMatchFound(JsonObject msg) {
        if (pendingTransferTask != null) {
            try { pendingTransferTask.cancel(); } catch (Exception ignored) {}
        }
        pendingMatchHost  = optStr(msg, "relay_host", "revivalpvp.net");
        pendingMatchPort  = msg.has("relay_port") ? msg.get("relay_port").getAsInt() : 25565;
        pendingMatchToken = optStr(msg, "session_token", "");
        String opponent   = optStr(msg, "opponent_name", "Opponent");
        String matchId    = optStr(msg, "match_id", "");
        int voteSecs      = msg.has("map_vote_seconds") ? msg.get("map_vote_seconds").getAsInt() : 12;

        // Available maps come over with the match_found push for both queue
        // and friend duels. Build a chat-clickable pill row so non-mod (server-
        // mod-only) players have a map vote surface — they can't open the
        // client mod's hub picker.
        java.util.List<String[]> maps = new java.util.ArrayList<>();
        if (msg.has("available_maps") && msg.get("available_maps").isJsonArray()) {
            var arr = msg.getAsJsonArray("available_maps");
            for (var el : arr) {
                var o = el.getAsJsonObject();
                String id   = o.has("id")           ? o.get("id").getAsString()           : "";
                String name = o.has("display_name") ? o.get("display_name").getAsString() : id;
                if (!id.isEmpty()) maps.add(new String[]{id, name});
            }
        }

        notifyOnMain(p -> {
            p.sendMessage(Component.empty());
            p.sendMessage(prefix().append(Component.text(
                "Friend duel accepted! Connecting to " + opponent + " in 14s...",
                NamedTextColor.AQUA)));

            // Map vote chat row — mirrors the [✓ ACCEPT][✗ DECLINE] pattern
            // we use for invites. /pvp vote-map <matchId> <mapId> handles
            // the click; the backend's map-vote logic finalizes once both
            // sides have voted (or the timeout hits).
            if (!maps.isEmpty() && !matchId.isEmpty()) {
                Component row = prefix().append(Component.text(
                    "Pick map (" + voteSecs + "s) — ", NamedTextColor.GRAY));
                for (int i = 0; i < maps.size(); i++) {
                    if (i > 0) row = row.append(Component.space());
                    String mid  = maps.get(i)[0];
                    String name = maps.get(i)[1];
                    row = row.append(actionPill(name, NamedTextColor.AQUA,
                        "/pvp vote-map " + matchId + " " + mid,
                        "Vote " + name + " for this duel"));
                }
                p.sendMessage(row);
            }

            try {
                p.showTitle(Title.title(
                    Component.text("⚔ Duel Starting", NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true),
                    Component.text("vs " + opponent, NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofMillis(500))
                ));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            } catch (Throwable ignored) {}

            // Schedule transfer 14s out — covers the 12s map-vote window plus
            // a small buffer for backend write + Redis propagation.
            pendingTransferTask = Bukkit.getScheduler().runTaskLater(plugin, this::performTransfer, 20L * 14);
        });
    }

    private void performTransfer() {
        Player p = Bukkit.getPlayer(playerUuid);
        if (p == null || !p.isOnline()) return;
        if (pendingMatchHost == null || pendingMatchToken == null) return;
        try {
            p.storeCookie(
                new NamespacedKey("revivalpvp", "session"),
                pendingMatchToken.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            plugin.getLogger().warning("storeCookie failed (presence): " + t.getMessage());
        }
        p.sendMessage(prefix().append(Component.text(
            "Connecting to RevivalPVP duel server...", NamedTextColor.GREEN)));
        try { p.transfer(pendingMatchHost, pendingMatchPort); }
        catch (Throwable t) {
            p.sendMessage(prefix().append(Component.text(
                "Transfer failed: " + t.getMessage()
                    + ". Connect manually: " + pendingMatchHost + ":" + pendingMatchPort,
                NamedTextColor.RED)));
        }
        // Don't close the WS — this client lives for the player's session;
        // PresenceRegistry handles teardown on PlayerQuitEvent.
        pendingMatchHost = null;
        pendingMatchToken = null;
    }

    private void handleFriendAdded(JsonObject msg) {
        if (!msg.has("friend") || !msg.get("friend").isJsonObject()) return;
        String name = optStr(msg.getAsJsonObject("friend"), "username", "?");
        notifyOnMain(p -> p.sendMessage(prefix().append(Component.text(
            "You and " + name + " are now friends!", NamedTextColor.GREEN))));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void send(JsonObject obj) {
        if (session == null || !session.isOpen()) return;
        try { session.getAsyncRemote().sendText(GSON.toJson(obj)); }
        catch (Exception e) { plugin.getLogger().warning("Presence WS send failed: " + e.getMessage()); }
    }

    private void notifyOnMain(java.util.function.Consumer<Player> action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(playerUuid);
            if (p != null && p.isOnline()) action.accept(p);
        });
    }

    private static Component actionPill(String label, NamedTextColor color,
                                         String slashCommand, String hoverText) {
        return Component.text("[" + label + "]", color)
            .decoration(TextDecoration.BOLD, true)
            .clickEvent(ClickEvent.runCommand(slashCommand))
            .hoverEvent(HoverEvent.showText(Component.text(hoverText, NamedTextColor.GRAY)));
    }

    private static Component prefix() {
        return Component.text("[RevivalPVP] ", NamedTextColor.LIGHT_PURPLE);
    }

    private static String optStr(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }
}