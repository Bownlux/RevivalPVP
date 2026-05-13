// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.matchmaking;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.revivalsmp.pvp.RevivalPVPMod;
import net.revivalsmp.pvp.kit.Kit;
import net.revivalsmp.pvp.network.BackendWS;
import net.revivalsmp.pvp.network.DuelServerConnector;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side matchmaking state machine.
 *
 * States: IDLE → QUEUING → MATCH_FOUND → CONNECTING → IN_DUEL → RESULT
 *
 * Transitions:
 *   IDLE        → QUEUING       joinQueue()
 *   QUEUING     → MATCH_FOUND   backend WS "match_found"
 *   MATCH_FOUND → CONNECTING    acceptMatch(), triggers Minecraft server connect
 *   CONNECTING  → IN_DUEL       DuelServerListener plugin message "duel_start"
 *   IN_DUEL     → RESULT        DuelServerListener plugin message "duel_end"
 *   RESULT      → IDLE          clearResult()
 */
public class MatchmakingService {

    public enum State { IDLE, QUEUING, MATCH_FOUND, CONNECTING, IN_DUEL, RESULT }

    private static final long CLIENT_QUEUE_TIMEOUT_MS = 320_000;
    // Seconds to auto-accept after match_found before timing out
    private static final long ACCEPT_TIMEOUT_MS = 15_000;
    /** If we've been in CONNECTING state this long, the Player.transfer probably
     *  failed (Velocity rate-limit, kicked, server unreachable). Reset to IDLE
     *  so the user isn't stuck staring at "CONNECTING..." forever. */
    private static final long CONNECT_TIMEOUT_MS = 20_000;

    private final BackendWS ws;
    private volatile State state = State.IDLE;
    private Kit queuedKit;
    private boolean ranked;
    private ActiveMatch activeMatch;
    private MatchResult lastResult;
    private long queuedAt;
    private long matchFoundAt;
    private long connectingAt;

    // Map vote, populated from match_found, cleared on connect/idle.
    private List<MapOption> availableMaps = new ArrayList<>();
    private String          myVote;        // map_id we voted for; null if none yet
    private String          chosenMap;     // map_id finalized by backend
    private int             mapVoteSeconds;

    /** Last server-side error message, set by handleQueueError, consumed by the hub UI to display. */
    private String lastErrorMessage;
    private long   lastErrorAt;

    public MatchmakingService(BackendWS ws) {
        this.ws = ws;
        ws.on("match_found",   this::handleMatchFound);
        ws.on("map_selected",  this::handleMapSelected);
        ws.on("map_vote_ack",  this::handleMapVoteAck);
        ws.on("queue_timeout", this::handleQueueTimeout);
        ws.on("queue_error",   this::handleQueueError);
        ws.on("auth_error",    this::handleAuthError);
    }

    // ── Tick (runs every client tick on MC thread) ────────────────────────────

    public synchronized void tick() {
        switch (state) {
            case QUEUING -> {
                if (queueElapsedMs() > CLIENT_QUEUE_TIMEOUT_MS) {
                    state = State.IDLE;
                    RevivalPVPMod.LOGGER.info("Queue timed out client-side.");
                }
            }
            case MATCH_FOUND -> {
                // Auto-accept after timeout (don't leave player hanging)
                if (System.currentTimeMillis() - matchFoundAt > ACCEPT_TIMEOUT_MS) {
                    acceptMatch();
                }
            }
            case CONNECTING -> {
                // The transfer should land within a few seconds. If we're
                // still here past CONNECT_TIMEOUT_MS, the transfer failed
                // (Velocity rate-limit, server unreachable, etc.), reset
                // so the player isn't stranded on the "CONNECTING..." screen.
                if (System.currentTimeMillis() - connectingAt > CONNECT_TIMEOUT_MS) {
                    state = State.IDLE;
                    activeMatch = null;
                    lastErrorMessage = "Transfer failed (server unreachable or rate-limited). Try again.";
                    lastErrorAt = System.currentTimeMillis();
                    RevivalPVPMod.LOGGER.warn("Transfer timed out, resetting to IDLE.");
                }
            }
            default -> {}
        }
    }

    // ── Queue actions ─────────────────────────────────────────────────────────

    public synchronized boolean joinQueue(Kit kit, boolean ranked) {
        return joinQueue(kit, ranked, "global");
    }

    public synchronized boolean joinQueue(Kit kit, boolean ranked, String scope) {
        if (state != State.IDLE) return false;
        this.queuedKit = kit;
        this.ranked    = ranked;
        this.queuedAt  = System.currentTimeMillis();
        state          = State.QUEUING;

        JsonObject req = new JsonObject();
        req.addProperty("kit",    kit.name());
        req.addProperty("ranked", ranked);
        req.addProperty("scope",  scope);
        ws.send("queue_join", req);
        return true;
    }

    public synchronized void leaveQueue() {
        if (state != State.QUEUING) return;
        ws.send("queue_leave", new JsonObject());
        state = State.IDLE;
    }

    /**
     * Accept the matched duel, connects to the duel server.
     * Auto-called after ACCEPT_TIMEOUT_MS so players don't need to click Accept.
     */
    public synchronized void acceptMatch() {
        if (state != State.MATCH_FOUND || activeMatch == null) return;
        state = State.CONNECTING;
        connectingAt = System.currentTimeMillis();
        RevivalPVPMod.LOGGER.info("Accepting match {}, connecting to {}:{}",
            activeMatch.matchId(), activeMatch.duelHost(), activeMatch.duelPort());

        // Must run on MC main thread
        Minecraft.getInstance().execute(() ->
            DuelServerConnector.connectToDuelServer(
                activeMatch.duelHost(),
                activeMatch.duelPort(),
                activeMatch.sessionToken()
            )
        );
    }

    /**
     * Send a map vote for the active match. No-op outside MATCH_FOUND or if
     * the map id isn't in the available pool. The backend acks via map_vote_ack
     * and broadcasts map_selected once both players have voted (or after the
     * server-side timeout).
     */
    public synchronized void voteMap(String mapId) {
        if (state != State.MATCH_FOUND || activeMatch == null || mapId == null) return;
        boolean valid = false;
        for (MapOption m : availableMaps) {
            if (m.id().equalsIgnoreCase(mapId)) { valid = true; break; }
        }
        if (!valid) return;
        myVote = mapId;
        JsonObject req = new JsonObject();
        req.addProperty("match_id", activeMatch.matchId());
        req.addProperty("map_id",   mapId);
        ws.send("map_vote", req);
    }

    // ── Plugin message callbacks (called from DuelServerListener) ─────────────

    public synchronized void onDuelStart() {
        state = State.IN_DUEL;
    }

    public synchronized void onDuelEnd(boolean won, int lpChange, String newRank, boolean promoted) {
        lastResult  = new MatchResult(won, lpChange, newRank, promoted);
        activeMatch = null;
        state       = State.RESULT;
    }

    // ── WS message handlers ───────────────────────────────────────────────────

    private synchronized void handleMatchFound(JsonObject msg) {
        // Defensive: if any required field is missing or null, ignore the
        // frame instead of crashing with NPE. A backend version mismatch
        // (e.g. renamed field after a server-side deploy while a client
        // is still running) shouldn't kill the player's Minecraft instance.
        if (!hasNonNull(msg, "match_id") || !hasNonNull(msg, "opponent_name")
            || !hasNonNull(msg, "relay_host") || !hasNonNull(msg, "relay_port")
            || !hasNonNull(msg, "session_token")) {
            RevivalPVPMod.LOGGER.warn("match_found frame missing required fields; dropping");
            return;
        }
        activeMatch  = new ActiveMatch(
            msg.get("match_id").getAsString(),
            msg.get("opponent_name").getAsString(),
            hasNonNull(msg, "opponent_rank") ? msg.get("opponent_rank").getAsString() : "",
            msg.get("relay_host").getAsString(),    // duel server host
            msg.get("relay_port").getAsInt(),        // duel server port
            msg.get("session_token").getAsString()
        );
        availableMaps.clear();
        myVote    = null;
        chosenMap = null;
        if (msg.has("available_maps") && msg.get("available_maps").isJsonArray()) {
            JsonArray arr = msg.getAsJsonArray("available_maps");
            for (int i = 0; i < arr.size(); i++) {
                JsonObject m = arr.get(i).getAsJsonObject();
                availableMaps.add(new MapOption(
                    m.get("id").getAsString(),
                    m.has("display_name") ? m.get("display_name").getAsString() : m.get("id").getAsString(),
                    m.has("description")  ? m.get("description").getAsString()  : ""
                ));
            }
        }
        mapVoteSeconds = msg.has("map_vote_seconds") ? msg.get("map_vote_seconds").getAsInt() : 12;
        matchFoundAt = System.currentTimeMillis();
        state        = State.MATCH_FOUND;
        RevivalPVPMod.LOGGER.info("Match found: {} vs {} ({}); maps={}",
            "you", activeMatch.opponentName(), activeMatch.duelHost(),
            availableMaps.stream().map(MapOption::id).toList());

        // Auto-open the hub (or force-switch to the queue tab if it's already
        // open on a different one) so the map picker is visible. Both queue
        // and friend-invite paths arrive here; the friend-invite path
        // typically has no UI up at all.
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof net.revivalsmp.pvp.client.screen.MatchResultScreen) return;
            if (mc.screen instanceof net.revivalsmp.pvp.client.screen.PVPHubScreen hub) {
                hub.switchToQueueTab();
                return;
            }
            mc.setScreen(new net.revivalsmp.pvp.client.screen.PVPHubScreen(this));
        });
    }

    private synchronized void handleMapSelected(JsonObject msg) {
        chosenMap = msg.get("map_id").getAsString();
        RevivalPVPMod.LOGGER.info("Map selected: {} (votes a={}, b={})",
            chosenMap,
            msg.has("vote_a") && !msg.get("vote_a").isJsonNull() ? msg.get("vote_a").getAsString() : "-",
            msg.has("vote_b") && !msg.get("vote_b").isJsonNull() ? msg.get("vote_b").getAsString() : "-");
        // Auto-accept once the map is locked in. If we're somehow no longer in
        // MATCH_FOUND (player left, etc.) acceptMatch is a no-op.
        acceptMatch();
    }

    private synchronized void handleMapVoteAck(JsonObject msg) {
        // For now, just log. The UI reads myVote() to highlight selection.
        boolean ok = msg.has("accepted") && msg.get("accepted").getAsBoolean();
        if (!ok) {
            RevivalPVPMod.LOGGER.warn("Map vote rejected: {}", msg);
            myVote = null;
        }
    }

    private synchronized void handleQueueTimeout(JsonObject msg) {
        state = State.IDLE;
        lastErrorMessage = "Queue timed out, no opponent found.";
        lastErrorAt = System.currentTimeMillis();
    }

    /** Backend rejected the queue request (e.g. ranked w/o placement done). */
    private synchronized void handleQueueError(JsonObject msg) {
        String reason = msg.has("reason") && !msg.get("reason").isJsonNull()
            ? msg.get("reason").getAsString()
            : "Queue rejected.";
        state = State.IDLE;
        lastErrorMessage = reason;
        lastErrorAt = System.currentTimeMillis();
        RevivalPVPMod.LOGGER.info("Queue rejected: {}", reason);
    }

    /** Backend rejected auth (expired JWT, invalid token, etc.). */
    private synchronized void handleAuthError(JsonObject msg) {
        String reason = msg.has("reason") && !msg.get("reason").isJsonNull()
            ? msg.get("reason").getAsString()
            : "Authentication failed.";
        // Clear the saved token so the next openHub triggers a fresh Mojang auth.
        net.revivalsmp.pvp.RevivalPVPMod.get().config().setAuthToken(null);
        state = State.IDLE;
        lastErrorMessage = reason;
        lastErrorAt = System.currentTimeMillis();
        RevivalPVPMod.LOGGER.warn("Auth rejected: {}", reason);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public State       state()        { return state; }
    public Kit         queuedKit()    { return queuedKit; }
    public boolean     isRanked()     { return ranked; }
    public ActiveMatch activeMatch()  { return activeMatch; }
    public MatchResult lastResult()   { return lastResult; }
    public long        queueElapsedMs() { return state == State.QUEUING ? System.currentTimeMillis() - queuedAt : 0; }
    public long        matchFoundElapsedMs() { return state == State.MATCH_FOUND ? System.currentTimeMillis() - matchFoundAt : 0; }
    public List<MapOption> availableMaps() { return availableMaps; }
    public String      myVote()       { return myVote; }
    public String      chosenMap()    { return chosenMap; }
    public int         mapVoteSeconds() { return mapVoteSeconds; }

    public synchronized void clearResult() {
        if (state == State.RESULT) state = State.IDLE;
    }

    /** Most recent server-side error (queue rejection, auth fail, timeout). Null if none. */
    public synchronized String lastErrorMessage() { return lastErrorMessage; }
    public synchronized long   lastErrorAt()      { return lastErrorAt; }
    public synchronized void   clearError()       { lastErrorMessage = null; }

    /** Defensive helper: a JsonObject "has" the key only when it's present
     *  AND not JsonNull. Saves every WS handler from re-doing the dance. */
    private static boolean hasNonNull(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull();
    }

    // ── Records ───────────────────────────────────────────────────────────────

    /** relay_host/relay_port fields now carry the duel server address (not a UDP relay). */
    public record ActiveMatch(String matchId, String opponentName, String opponentRank,
                              String duelHost, int duelPort, String sessionToken) {}
    public record MatchResult(boolean won, int lpChange, String newRank, boolean promoted) {}
    public record MapOption(String id, String displayName, String description) {}
}