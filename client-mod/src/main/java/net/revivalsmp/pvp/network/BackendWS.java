// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.revivalsmp.pvp.RevivalPVPMod;

import jakarta.websocket.*;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * WebSocket connection to the RevivalPVP backend.
 * Handles reconnect with exponential backoff.
 * Thread-safe, callbacks fire on the Minecraft client thread via tick().
 */
@ClientEndpoint
public class BackendWS {

    private static final Gson GSON = new Gson();
    /** Initial reconnect delay after a disconnect. Grows up to RECONNECT_DELAY_MAX_MS. */
    private static final long RECONNECT_DELAY_INITIAL_MS = 2_000;
    private static final long RECONNECT_DELAY_MAX_MS     = 30_000;
    /** Send a {@code ping} this often so NAT/firewall middleboxes don't reap
     *  an idle WS. Backend sends a pong back; receiving any frame counts as
     *  liveness for the watchdog below. */
    private static final long PING_INTERVAL_MS = 25_000;
    /** If we've gone this long without ANY frame from the server, treat the
     *  socket as silently dead (TCP RST swallowed by a middlebox) and force
     *  a reconnect. Set well above PING_INTERVAL so a single missed pong
     *  doesn't trigger a stampede. */
    private static final long DEAD_CONNECTION_TIMEOUT_MS = 90_000;

    private Session session;
    private final Map<String, CopyOnWriteArrayList<Consumer<JsonObject>>> handlers = new ConcurrentHashMap<>();
    private final java.util.concurrent.LinkedBlockingQueue<JsonObject> inbound = new java.util.concurrent.LinkedBlockingQueue<>();

    private long nextReconnectMs = 0;
    private long currentReconnectDelayMs = RECONNECT_DELAY_INITIAL_MS;
    private long lastPingSentMs = 0;
    private long lastFrameReceivedMs = 0;
    private boolean connecting = false;
    /** Set by {@link #shutdown()} so tick() and onClose() stop spawning
     *  new reconnect attempts after the client begins quitting. */
    private volatile boolean shutdown = false;

    public void connect() {
        if (shutdown) return;
        if (connecting || (session != null && session.isOpen())) return;
        connecting = true;
        Thread.ofVirtual().name("revival-pvp-ws").start(() -> {
            try {
                String url = RevivalPVPMod.get().config().backendWsUrl();
                WebSocketContainer container = ContainerProvider.getWebSocketContainer();
                container.connectToServer(this, URI.create(url));
            } catch (Exception e) {
                RevivalPVPMod.LOGGER.warn("WS connect failed: {}", e.getMessage());
                connecting = false;
                scheduleNextReconnect();
            }
        });
    }

    private void scheduleNextReconnect() {
        nextReconnectMs = System.currentTimeMillis() + currentReconnectDelayMs;
        // Exponential backoff capped at 30s. Reset to initial delay on the
        // next successful onOpen so transient network blips heal fast.
        currentReconnectDelayMs = Math.min(RECONNECT_DELAY_MAX_MS, currentReconnectDelayMs * 2);
    }

    /** Called on each client tick, drains inbound queue on the MC thread,
     *  reconnects if needed, and runs the keepalive watchdog. */
    public void tick() {
        if (shutdown) return;
        long now = System.currentTimeMillis();

        // Reconnect path, only when not currently mid-connect attempt.
        if (!isConnected() && now > nextReconnectMs && !connecting) {
            connect();
        }

        // Keepalive: ping every PING_INTERVAL_MS while connected.
        if (isConnected() && now - lastPingSentMs > PING_INTERVAL_MS) {
            lastPingSentMs = now;
            try {
                JsonObject ping = new JsonObject();
                ping.addProperty("type", "ping");
                session.getAsyncRemote().sendText(GSON.toJson(ping));
            } catch (Exception e) {
                RevivalPVPMod.LOGGER.warn("WS ping send failed: {}", e.getMessage());
            }
        }

        // Watchdog: if we haven't seen ANY frame in a long time, the socket
        // is probably silently dead. Force-close so onClose runs and the
        // reconnect path takes over.
        if (isConnected() && lastFrameReceivedMs > 0
            && now - lastFrameReceivedMs > DEAD_CONNECTION_TIMEOUT_MS) {
            RevivalPVPMod.LOGGER.warn("WS appears stale ({}ms since last frame), forcing reconnect.",
                now - lastFrameReceivedMs);
            close();
            // close() leaves nextReconnectMs at 0, so next tick reconnects immediately.
        }

        JsonObject msg;
        while ((msg = inbound.poll()) != null) {
            final JsonObject finalMsg = msg;
            String type = finalMsg.has("type") ? finalMsg.get("type").getAsString() : "unknown";
            // Pongs are pure liveness, don't dispatch to handlers.
            if ("pong".equals(type)) continue;
            var list = handlers.get(type);
            if (list != null) list.forEach(h -> h.accept(finalMsg));
        }
    }

    public void on(String type, Consumer<JsonObject> handler) {
        handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    public void send(String type, JsonObject payload) {
        if (!isConnected()) return;
        payload.addProperty("type", type);
        try {
            session.getAsyncRemote().sendText(GSON.toJson(payload));
        } catch (Exception e) {
            RevivalPVPMod.LOGGER.warn("WS send failed: {}", e.getMessage());
        }
    }

    public boolean isConnected() { return session != null && session.isOpen(); }

    /** Close the current session (if any). Lets caller force a reconnect with
     *  a freshly-saved auth token, or recover from a stale socket. */
    public void close() {
        try { if (session != null && session.isOpen()) session.close(); }
        catch (Exception ignored) {}
        session = null;
        connecting = false;
        // Force the next tick to reconnect immediately, not wait out the backoff.
        nextReconnectMs = 0;
        lastFrameReceivedMs = 0;
    }

    @OnOpen
    public void onOpen(Session s) {
        session = s;
        connecting = false;
        // Successful open, reset backoff so the NEXT drop reconnects fast.
        currentReconnectDelayMs = RECONNECT_DELAY_INITIAL_MS;
        long now = System.currentTimeMillis();
        lastFrameReceivedMs = now;
        lastPingSentMs = now;  // don't immediately re-ping right after open
        RevivalPVPMod.LOGGER.info("RevivalPVP backend connected.");
        // Send auth token
        String token = RevivalPVPMod.get().config().authToken();
        if (token != null) {
            JsonObject auth = new JsonObject();
            auth.addProperty("token", token);
            send("auth", auth);
        }
    }

    @OnMessage
    public void onMessage(String text) {
        // Liveness, any frame from the server proves the socket is alive.
        lastFrameReceivedMs = System.currentTimeMillis();
        try {
            inbound.put(GSON.fromJson(text, JsonObject.class));
        } catch (Exception ignored) {}
    }

    @OnClose
    public void onClose(Session s, CloseReason reason) {
        session = null;
        connecting = false;
        if (shutdown) {
            RevivalPVPMod.LOGGER.info("WS closed during shutdown.");
            return;
        }
        scheduleNextReconnect();
        RevivalPVPMod.LOGGER.info("WS closed: {}, reconnecting in {}ms",
            reason.getReasonPhrase(), currentReconnectDelayMs);
    }

    /** Permanently stops this client. Closes the session if open, blocks
     *  further reconnect attempts, and marks the watchdog inert. Called
     *  from ClientLifecycleEvents.CLIENT_STOPPING. */
    public void shutdown() {
        shutdown = true;
        try { if (session != null && session.isOpen()) session.close(); }
        catch (Exception ignored) {}
        session = null;
        connecting = false;
        handlers.clear();
        inbound.clear();
    }

    @OnError
    public void onError(Session s, Throwable t) {
        RevivalPVPMod.LOGGER.warn("WS error: {}", t.getMessage());
    }
}
