// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
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
 * One WebSocket session per queued player.
 *
 * Lifecycle:
 *   onOpen → send tenant_auth (key + mc_uuid + username + origin_server)
 *   recv auth_ok → send queue_join (kit + ranked + scope)
 *   recv queue_joined → notify player (status bar / chat)
 *   recv match_found → Player.transfer(host, port) → close()
 *   recv queue_timeout / queue_error / auth_error → notify + close()
 */
@ClientEndpoint
public class BackendClient {

    private static final Gson GSON = new Gson();

    /** Cookie key that travels with the player across {@code Player.transfer()}.
     *  The PvP duel-server plugin reads it on PlayerJoinEvent and treats it as
     *  an auth token, replacing the {@code revivalpvp:auth} plugin-message
     *  flow that the client-mod uses. Same protocol, different transport. */
    public static final NamespacedKey SESSION_COOKIE =
        new NamespacedKey("revivalpvp", "session");

    private final RevivalPVPServerMod plugin;
    private final UUID    playerUuid;
    private final String  playerName;
    private final String  kit;
    private final boolean ranked;
    private final String  scope;

    private volatile Session session;
    private volatile boolean active = true;
    private final net.revivalsmp.pvp.servermod.queue.QueueDisplay queueDisplay;

    // Pending match state — populated when match_found arrives, consumed
    // when the player accepts (manually via /pvpaccept or chat-click) or
    // the 15-second auto-accept timer fires.
    private static final long ACCEPT_TIMEOUT_TICKS = 20L * 15;
    private volatile boolean pendingAccept = false;
    private volatile String  pendingHost;
    private volatile int     pendingPort;
    private volatile String  pendingToken;
    private volatile String  pendingOpponent;
    private volatile String  pendingMatchId;
    private volatile String  myMapVote;       // null if not yet voted
    private volatile BukkitTask autoAcceptTask;

    public BackendClient(RevivalPVPServerMod plugin, Player player,
                         String kit, boolean ranked, String scope) {
        this.plugin     = plugin;
        this.playerUuid = player.getUniqueId();
        this.playerName = player.getName();
        this.kit        = kit;
        this.ranked     = ranked;
        this.scope      = scope;
        this.queueDisplay = new net.revivalsmp.pvp.servermod.queue.QueueDisplay(plugin, playerUuid);
    }

    public boolean isActive() { return active && session != null && session.isOpen(); }

    public void connect() {
        Thread.ofVirtual().name("rpvp-mod-ws-" + playerName).start(() -> {
            // ContainerProvider.getWebSocketContainer() uses ServiceLoader,
            // which picks up META-INF/services via the THREAD CONTEXT classloader.
            // Virtual threads don't inherit our plugin classloader from the main
            // thread, so without this swap, ServiceLoader looks in Paper's
            // classloader and finds nothing — "Could not find an implementation class".
            ClassLoader pluginCl = BackendClient.class.getClassLoader();
            Thread.currentThread().setContextClassLoader(pluginCl);
            try {
                WebSocketContainer c = ContainerProvider.getWebSocketContainer();
                c.connectToServer(this, URI.create(plugin.backendWsUrl()));
            } catch (Exception e) {
                plugin.getLogger().warning("WS connect failed for " + playerName + ": " + e.getMessage());
                notifyOnMain(playerUuid, p -> p.sendMessage(prefix().append(Component.text(
                    "Could not reach RevivalPVP backend. Try again later.", NamedTextColor.RED))));
                active = false;
            }
        });
    }

    public void close() {
        active = false;
        if (autoAcceptTask != null) {
            try { autoAcceptTask.cancel(); } catch (Exception ignored) {}
            autoAcceptTask = null;
        }
        // Catch-all: any path that closes the WS (socket drop, /pvp cancel,
        // server shutdown) must also drop the BossBar so it doesn't orphan.
        if (queueDisplay != null) {
            Bukkit.getScheduler().runTask(plugin, queueDisplay::stop);
        }
        // Tell the backend explicitly to leave the queue before we tear
        // down the socket. The backend's disconnect handler will eventually
        // do the same on socket-close, but the explicit signal avoids races
        // where the player's next /pvp queue lands before the disconnect
        // cleanup runs and gets rejected as "already in queue".
        try {
            if (session != null && session.isOpen()) {
                JsonObject leave = new JsonObject();
                leave.addProperty("type", "queue_leave");
                session.getBasicRemote().sendText(GSON.toJson(leave));
            }
        } catch (Exception ignored) {}
        try { if (session != null && session.isOpen()) session.close(); } catch (Exception ignored) {}
    }

    /** True if a match has been found and is awaiting accept. */
    public boolean hasPendingAccept() { return pendingAccept; }

    /**
     * Player accepted a pending match (via /pvpaccept, chat-click, or auto).
     * Stores the cookie and transfers them. Idempotent — safe to call twice.
     * Caller must be on the Bukkit main thread.
     */
    public void acceptPendingMatch(Player p) {
        if (!pendingAccept) return;
        pendingAccept = false;
        if (autoAcceptTask != null) {
            try { autoAcceptTask.cancel(); } catch (Exception ignored) {}
            autoAcceptTask = null;
        }
        if (pendingToken != null && !pendingToken.isEmpty()) {
            try {
                p.storeCookie(SESSION_COOKIE, pendingToken.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable t) {
                plugin.getLogger().warning("storeCookie failed: " + t.getMessage());
            }
        }
        p.sendMessage(prefix().append(Component.text(
            "Connecting to RevivalPVP duel server...", NamedTextColor.GREEN)));

        // Bedrock fast-path: Player.transfer doesn't translate cleanly via
        // Geyser to Bedrock clients (the Bedrock client tries port 19132,
        // not the Java port). Since the duel-server plugin runs on the same
        // JVM, we direct-auth the player without disconnecting them.
        if (isBedrockPlayer(p) && tryDirectAuthOnSameJvm(p, pendingToken)) {
            close();
            return;
        }

        // Java fast-path (default): cookie + transfer dance.
        // Delay the transfer a few ticks so the cookie write packet flushes
        // to the client before transfer disconnects them. Without this, the
        // duel-side plugin auth via retrieveCookie returns empty on the new
        // connection, the player drops out, and the duel never starts.
        final java.util.UUID uid = p.getUniqueId();
        final String host = pendingHost;
        final int    port = pendingPort;
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            org.bukkit.entity.Player still = org.bukkit.Bukkit.getPlayer(uid);
            if (still == null || !still.isOnline()) {
                close();
                return;
            }
            try {
                still.transfer(host, port);
            } catch (Throwable t) {
                still.sendMessage(prefix().append(Component.text(
                    "Transfer failed: " + t.getMessage()
                        + ". Connect manually: " + host + ":" + port,
                    NamedTextColor.RED)));
            }
            close();
        }, 6L);   // 6 ticks (300ms) — enough for the cookie packet to flush.
    }

    /** Floodgate convention: Bedrock player names start with a dot. We don't
     *  hard-depend on Floodgate API to keep the server-mod portable to other
     *  Geyser/Floodgate setups; the dot prefix is reliable in our deployment. */
    private static boolean isBedrockPlayer(org.bukkit.entity.Player p) {
        String name = p.getName();
        return name != null && name.startsWith(".");
    }

    /**
     * Hand a player off to the duel-server with a pre-issued session token.
     * Used by both match accept (queue → duel) and spectator launch (live
     * duels → spectator slot). Same dance both ways: store cookie, then
     * Player.transfer() — with the Bedrock direct-auth fallback for Geyser.
     *
     * <p>Caller must be on the Bukkit main thread.
     */
    public static void transferWithToken(
            RevivalPVPServerMod plugin, Player p,
            String host, int port, String token) {
        if (token == null || token.isEmpty()) {
            plugin.getLogger().warning("transferWithToken: empty token, aborting.");
            return;
        }
        try {
            p.storeCookie(SESSION_COOKIE, token.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            plugin.getLogger().warning("storeCookie failed: " + t.getMessage());
        }

        // Bedrock fast-path: direct-auth on the same JVM. Same path as queue accept.
        if (isBedrockPlayer(p)) {
            try {
                org.bukkit.plugin.Plugin pvp = org.bukkit.Bukkit.getPluginManager().getPlugin("RevivalPVP");
                if (pvp != null) {
                    var method = pvp.getClass().getMethod("directAuthPlayer",
                        org.bukkit.entity.Player.class, String.class);
                    method.invoke(pvp, p, token);
                    p.sendMessage(Component.text("Joining as spectator...", NamedTextColor.AQUA));
                    return;
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("Bedrock spectator direct-auth failed: " + t.getMessage());
                // Fall through to the Java transfer path as a last resort.
            }
        }

        // Java path: same 6-tick delay so the cookie flushes before transfer.
        final java.util.UUID uid = p.getUniqueId();
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            org.bukkit.entity.Player still = org.bukkit.Bukkit.getPlayer(uid);
            if (still == null || !still.isOnline()) return;
            try {
                still.transfer(host, port);
            } catch (Throwable t) {
                still.sendMessage(Component.text(
                    "Transfer failed: " + t.getMessage()
                        + ". Connect manually: " + host + ":" + port,
                    NamedTextColor.RED));
            }
        }, 6L);
    }

    /** Look up the locally-installed RevivalPVP plugin via Bukkit's plugin
     *  manager and call its public directAuthPlayer entry point. Returns
     *  true on success. Bedrock players never disconnect — they get teleported
     *  to the arena once both sides have auth'd. */
    private boolean tryDirectAuthOnSameJvm(org.bukkit.entity.Player p, String token) {
        try {
            org.bukkit.plugin.Plugin pvp = org.bukkit.Bukkit.getPluginManager().getPlugin("RevivalPVP");
            if (pvp == null) {
                plugin.getLogger().warning("RevivalPVP plugin not found on this server — Bedrock direct-auth unavailable.");
                return false;
            }
            // Use reflection so we don't need a compile-time dep on the plugin.
            var method = pvp.getClass().getMethod("directAuthPlayer",
                org.bukkit.entity.Player.class, String.class);
            method.invoke(pvp, p, token);
            p.sendMessage(prefix().append(Component.text(
                "Joining duel...", NamedTextColor.GREEN)));
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Bedrock direct-auth failed for " + p.getName()
                + ": " + t.getMessage());
            return false;
        }
    }

    @OnOpen
    public void onOpen(Session s) {
        this.session = s;
        // Send tenant_auth — this both authenticates the server and creates/
        // resolves the player record by MC UUID on the backend.
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
                case "auth_ok"        -> handleAuthOk(msg);
                case "auth_error"     -> handleAuthError(msg);
                case "queue_joined"   -> handleQueueJoined(msg);
                case "queue_error"    -> handleQueueError(msg);
                case "match_found"    -> handleMatchFound(msg);
                case "map_selected"   -> handleMapSelected(msg);
                case "map_vote_ack"   -> {} // ignored on server-mod
                case "queue_timeout"  -> handleQueueTimeout();
                case "ping","pong"    -> {}
                default               -> plugin.getLogger().fine("Unhandled WS msg: " + type);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("WS parse error: " + e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session s, CloseReason reason) {
        active = false;
        plugin.queueRegistry().cancel(playerUuid);
    }

    @OnError
    public void onError(Session s, Throwable t) {
        plugin.getLogger().warning("WS error for " + playerName + ": " + t.getMessage());
    }

    // ── Server messages ────────────────────────────────────────────────────────

    private void handleAuthOk(JsonObject msg) {
        // Cache the backend's internal player_uuid so HTTP-key endpoints
        // (e.g. /kits/loadouts/{kit}/by-uuid/{internal}) can target it.
        // The backend resolves mc_uuid → internal uuid in tenant_auth, and
        // returns it as `uuid` in auth_ok. Different from the Mojang UUID.
        if (msg != null && msg.has("uuid") && !msg.get("uuid").isJsonNull()) {
            try {
                plugin.setInternalUuid(playerUuid, msg.get("uuid").getAsString());
            } catch (Exception e) {
                plugin.getLogger().warning("Could not parse auth_ok uuid: " + e.getMessage());
            }
        }

        // Server-mod player is authenticated; immediately join the queue.
        JsonObject q = new JsonObject();
        q.addProperty("type",   "queue_join");
        q.addProperty("kit",    kit);
        q.addProperty("ranked", ranked);
        q.addProperty("scope",  scope);
        send(q);
    }

    private void handleAuthError(JsonObject msg) {
        String reason = msg.has("reason") ? msg.get("reason").getAsString() : "unknown";
        notifyOnMain(playerUuid, p -> {
            queueDisplay.stop();
            p.sendMessage(prefix().append(Component.text(
                "Auth failed: " + reason, NamedTextColor.RED)));
        });
        close();
    }

    private void handleQueueJoined(JsonObject msg) {
        int pos = msg.has("position") ? msg.get("position").getAsInt() : 0;
        notifyOnMain(playerUuid, p -> {
            p.sendMessage(prefix().append(Component.text(
                "Queued for " + kit + " (" + scope + (ranked ? ", ranked" : ", unranked")
                    + ") — position " + pos + ". /pvp cancel to leave.",
                NamedTextColor.AQUA)));
            // Show the persistent BossBar indicator so players without the
            // client mod can see they're still in queue + how long it's been.
            queueDisplay.start(kit, ranked, scope);
        });
    }

    private void handleQueueError(JsonObject msg) {
        String reason = msg.has("reason") ? msg.get("reason").getAsString() : "unknown";
        notifyOnMain(playerUuid, p -> {
            queueDisplay.stop();
            p.sendMessage(prefix().append(Component.text(
                "Queue error: " + reason, NamedTextColor.RED)));
        });
        close();
    }

    private void handleMatchFound(JsonObject msg) {
        // Queue ended successfully. Drop the BossBar so the player's focus
        // shifts to the match-acceptance UI without competing visuals.
        notifyOnMain(playerUuid, p -> queueDisplay.stop());

        // Save pending state; do NOT transfer yet. Player must accept manually
        // (via /pvpaccept, chat-click, or the 15s auto-accept fallback below).
        pendingHost     = msg.has("relay_host")    ? msg.get("relay_host").getAsString()    : "revivalpvp.net";
        pendingPort     = msg.has("relay_port")    ? msg.get("relay_port").getAsInt()       : 25565;
        pendingToken    = msg.has("session_token") ? msg.get("session_token").getAsString() : "";
        pendingOpponent = msg.has("opponent_name") ? msg.get("opponent_name").getAsString() : "Opponent";
        pendingMatchId  = msg.has("match_id")      ? msg.get("match_id").getAsString()      : "";
        myMapVote       = null;
        pendingAccept   = true;

        // Build map options for chat-click vote. Each option fires
        // /pvpaccept <id> which both registers the vote and arms accept.
        final java.util.List<String[]> maps = new java.util.ArrayList<>();
        if (msg.has("available_maps") && msg.get("available_maps").isJsonArray()) {
            var arr = msg.getAsJsonArray("available_maps");
            for (int i = 0; i < arr.size(); i++) {
                var m = arr.get(i).getAsJsonObject();
                maps.add(new String[]{
                    m.get("id").getAsString(),
                    m.has("display_name") ? m.get("display_name").getAsString() : m.get("id").getAsString(),
                });
            }
        }

        notifyOnMain(playerUuid, p -> {
            // Big title in the middle of the screen.
            Component mainTitle = Component.text("⚔ MATCH FOUND", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true);
            Component subtitle = Component.text("vs " + pendingOpponent + " — pick a map", NamedTextColor.YELLOW);
            p.showTitle(Title.title(mainTitle, subtitle,
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(15), Duration.ofMillis(500))));

            // Sound effect so it grabs attention.
            try {
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            } catch (Throwable ignored) {}

            p.sendMessage(Component.empty());
            p.sendMessage(prefix().append(Component.text(
                "Match found vs " + pendingOpponent + "!", NamedTextColor.GOLD)));

            if (!maps.isEmpty()) {
                Component line = Component.text("  Vote for a map: ", NamedTextColor.GRAY);
                for (String[] m : maps) {
                    Component pill = Component.text(" [" + m[1] + "] ", NamedTextColor.AQUA)
                        .decoration(TextDecoration.BOLD, true)
                        .clickEvent(ClickEvent.runCommand("/pvpaccept " + m[0]))
                        .hoverEvent(HoverEvent.showText(Component.text(
                            "Vote for " + m[1] + " — accepts the duel.", NamedTextColor.GRAY)));
                    line = line.append(pill);
                }
                p.sendMessage(line);
            }
            Component skipPill = Component.text(" [SKIP / RANDOM] ", NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true)
                .clickEvent(ClickEvent.runCommand("/pvpaccept"))
                .hoverEvent(HoverEvent.showText(Component.text(
                    "Accept without voting — backend picks the map.", NamedTextColor.GRAY)));
            p.sendMessage(Component.text("  Or ", NamedTextColor.GRAY).append(skipPill).append(
                Component.text("(auto-accept in 15s)", NamedTextColor.GRAY)));
            p.sendMessage(Component.empty());

            // Auto-accept fallback so a stuck player isn't punished.
            autoAcceptTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!pendingAccept) return;
                Player still = Bukkit.getPlayer(playerUuid);
                if (still != null && still.isOnline()) {
                    still.sendMessage(prefix().append(Component.text(
                        "Auto-accepting...", NamedTextColor.GRAY)));
                    acceptPendingMatch(still);
                }
            }, ACCEPT_TIMEOUT_TICKS);
        });
    }

    /** Send a map vote for the pending match. The backend resolves the map
     *  shortly after both players vote (or after its server-side timeout) and
     *  the duel-plugin reads the chosen map from /duels/active when both
     *  players authenticate on the duel server.
     *
     *  Uses the synchronous send path so the vote is on the wire before
     *  acceptPendingMatch() closes the WebSocket. */
    public void submitMapVote(String mapId) {
        if (!pendingAccept || pendingMatchId == null || mapId == null) return;
        myMapVote = mapId;
        JsonObject v = new JsonObject();
        v.addProperty("type",     "map_vote");
        v.addProperty("match_id", pendingMatchId);
        v.addProperty("map_id",   mapId);
        sendSync(v);
    }

    private void sendSync(JsonObject obj) {
        if (session == null || !session.isOpen()) return;
        try { session.getBasicRemote().sendText(GSON.toJson(obj)); }
        catch (Exception e) { plugin.getLogger().warning("WS sync send failed: " + e.getMessage()); }
    }

    /** Map vote was finalized by the backend. We log it for debugging — the
     *  actual transfer is driven by the player accepting (chat-click or
     *  /pvpaccept). The duel-plugin reads map_name from /duels/active. */
    private void handleMapSelected(JsonObject msg) {
        String chosen = msg.has("map_id") ? msg.get("map_id").getAsString() : "?";
        plugin.getLogger().info("Map selected for " + playerName + ": " + chosen);
    }

    private void handleQueueTimeout() {
        String waited = queueDisplay.elapsedDisplay();
        notifyOnMain(playerUuid, p -> {
            queueDisplay.stop();
            p.sendMessage(prefix().append(Component.text(
                "No opponent found after " + waited + ". Try /pvp again or pick a different scope.",
                NamedTextColor.YELLOW)));
        });
        close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void send(JsonObject obj) {
        if (session == null || !session.isOpen()) return;
        try { session.getAsyncRemote().sendText(GSON.toJson(obj)); }
        catch (Exception e) { plugin.getLogger().warning("WS send failed: " + e.getMessage()); }
    }

    private void notifyOnMain(UUID uuid, java.util.function.Consumer<Player> action) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) action.accept(p);
        });
    }

    private static Component prefix() {
        return Component.text("[RevivalPVP] ", NamedTextColor.LIGHT_PURPLE);
    }
}