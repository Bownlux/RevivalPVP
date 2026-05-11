// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.revivalsmp.pvp.RevivalPVPMod;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Plain HTTP calls to the backend for read-only data (leaderboards,
 * history, player profiles). Auth via the JWT we obtained from MojangAuth.
 *
 * Each call returns a CompletableFuture so callers can show "loading…" UI
 * and update on completion. Failures resolve to null (logged).
 */
public final class BackendHttpClient {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        // Java's default HTTP/2 upgrade headers (h2c) are rejected by uvicorn
        // ('Invalid HTTP request received' -> 400) and corrupt the body parse.
        // Same fix applied to the heartbeat task and server-mod HTTP clients.
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    private BackendHttpClient() {}

    /** GET /rankings/{kit}?limit=N, kit is "global" or one of FIST/SWORD/ARCHER/MACE/CRYSTAL. */
    public static CompletableFuture<JsonObject> leaderboard(String kit, int limit) {
        String url = base() + "/rankings/" + URLEncoder.encode(kit, StandardCharsets.UTF_8)
            + "?limit=" + limit;
        return get(url);
    }

    /** GET /sponsor/leaderboard?season=current&limit=N, top players by sponsor coins received. */
    public static CompletableFuture<JsonObject> sponsorLeaderboard(int limit) {
        return get(base() + "/sponsor/leaderboard?season=current&limit=" + limit);
    }

    /** GET /duels/history?player_uuid=X&limit=N */
    public static CompletableFuture<JsonObject> history(String playerUuid, int limit) {
        String url = base() + "/duels/history?player_uuid="
            + URLEncoder.encode(playerUuid, StandardCharsets.UTF_8)
            + "&limit=" + limit;
        return get(url);
    }

    /** GET /duels/history?limit=N, server-wide latest matches across all players. */
    public static CompletableFuture<JsonObject> historyAll(int limit) {
        return get(base() + "/duels/history?limit=" + limit);
    }

    /** GET /duels/live?limit=N, currently in-progress or vote-pending matches. */
    public static CompletableFuture<JsonObject> liveDuels(int limit) {
        return get(base() + "/duels/live?limit=" + limit);
    }

    /** GET /queue/status, returns {queues: {KIT: int, ...}, total_queued, ...}.
     *  Used by the hub kit cards to show live "X queued" counts. Public; no
     *  auth header required. */
    public static CompletableFuture<JsonObject> queueStatus() {
        return get(base() + "/queue/status");
    }

    /** POST /duels/{match_id}/spectate, issue a spectator session + relay
     *  address so the caller can transfer the player to the duel server. */
    public static CompletableFuture<JsonObject> spectate(String matchId) {
        return post(base() + "/duels/" + URLEncoder.encode(matchId, StandardCharsets.UTF_8)
            + "/spectate", new JsonObject());
    }

    /** GET /rankings/player/{uuid}, player's per-kit ratings. */
    public static CompletableFuture<JsonObject> playerRatings(String playerUuid) {
        return get(base() + "/rankings/player/" + URLEncoder.encode(playerUuid, StandardCharsets.UTF_8));
    }

    /** GET /kits/catalog — public kit catalog (metadata + default loadouts) for
     *  the runtime KitRegistry. No auth needed; KitRegistry caches the result
     *  to disk so the hub still opens when the backend is unreachable. */
    public static CompletableFuture<JsonObject> kitCatalog() {
        return get(base() + "/kits/catalog");
    }

    /** GET /kits/loadouts/{kit}, fetches available variants and current selection. */
    public static CompletableFuture<JsonObject> getLoadout(String kit) {
        return get(base() + "/kits/loadouts/" + URLEncoder.encode(kit, StandardCharsets.UTF_8));
    }

    /**
     * POST /kits/loadouts/{kit}, persists a list of {slot, variant_id} selections.
     * Body shape: {"selections":[{"slot":"HEAD","variant_id":1}, ...]}
     */
    public static CompletableFuture<JsonObject> setLoadout(String kit, JsonArray selections) {
        JsonObject body = new JsonObject();
        body.add("selections", selections);
        return post(base() + "/kits/loadouts/" + URLEncoder.encode(kit, StandardCharsets.UTF_8), body);
    }

    // ── Friends ───────────────────────────────────────────────────────────────

    /** GET /players/search?q=..., username autocomplete. */
    public static CompletableFuture<JsonObject> searchPlayers(String prefix, int limit) {
        return get(base() + "/players/search?q="
            + URLEncoder.encode(prefix, StandardCharsets.UTF_8)
            + "&limit=" + limit);
    }

    public static CompletableFuture<JsonObject> listFriends() {
        return get(base() + "/friends");
    }

    public static CompletableFuture<JsonObject> listFriendRequests() {
        return get(base() + "/friends/requests");
    }

    public static CompletableFuture<JsonObject> sendFriendRequest(String username) {
        JsonObject body = new JsonObject();
        body.addProperty("target_username", username);
        // Use postWithError so callers can surface the backend's "detail" string
        // (e.g., "vloxnux hasn't joined RevivalPVP yet") instead of a generic
        // "request failed" message.
        return postWithError(base() + "/friends/request", body);
    }

    public static CompletableFuture<JsonObject> acceptFriendRequest(long requestId) {
        JsonObject body = new JsonObject();
        body.addProperty("request_id", requestId);
        return post(base() + "/friends/accept", body);
    }

    public static CompletableFuture<JsonObject> rejectFriendRequest(long requestId) {
        JsonObject body = new JsonObject();
        body.addProperty("request_id", requestId);
        return post(base() + "/friends/reject", body);
    }

    public static CompletableFuture<JsonObject> unfriend(String friendUuid) {
        return delete(base() + "/friends/" + URLEncoder.encode(friendUuid, StandardCharsets.UTF_8));
    }

    public static CompletableFuture<JsonObject> sendDuelInvite(String friendUuid, String kit, boolean ranked) {
        JsonObject body = new JsonObject();
        body.addProperty("friend_uuid", friendUuid);
        body.addProperty("kit", kit);
        body.addProperty("ranked", ranked);
        return post(base() + "/friends/invite", body);
    }

    public static CompletableFuture<JsonObject> acceptDuelInvite(String inviteId) {
        return post(base() + "/friends/invite/" + URLEncoder.encode(inviteId, StandardCharsets.UTF_8) + "/accept",
            new JsonObject());
    }

    public static CompletableFuture<JsonObject> declineDuelInvite(String inviteId) {
        return post(base() + "/friends/invite/" + URLEncoder.encode(inviteId, StandardCharsets.UTF_8) + "/decline",
            new JsonObject());
    }

    // ── Sponsor Coins ────────────────────────────────────────────────────────

    public static CompletableFuture<JsonObject> myCoins() {
        return get(base() + "/coins");
    }

    public static CompletableFuture<JsonObject> sponsorPlayer(String targetIdentifier, int coins) {
        JsonObject body = new JsonObject();
        body.addProperty("coins", coins);
        return post(base() + "/sponsor/" + URLEncoder.encode(targetIdentifier, StandardCharsets.UTF_8), body);
    }

    public static CompletableFuture<JsonObject> sponsorProfile(String identifier) {
        return get(base() + "/sponsor/profile/" + URLEncoder.encode(identifier, StandardCharsets.UTF_8));
    }

    public static CompletableFuture<JsonObject> myEarnings() {
        return get(base() + "/sponsor/earnings");
    }

    // ── Auth / account info ──────────────────────────────────────────────────

    /** GET /auth/me, profile + earnings summary for the InfoScreen. */
    public static CompletableFuture<JsonObject> me() {
        return get(base() + "/auth/me");
    }

    /** POST /auth/web-code, issues a 6-char one-time login code (5min TTL). */
    public static CompletableFuture<JsonObject> webCode() {
        return post(base() + "/auth/web-code", new JsonObject());
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static String base() {
        return RevivalPVPMod.get().config().backendUrl();
    }

    private static CompletableFuture<JsonObject> get(String url) {
        var b = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(10));
        String token = RevivalPVPMod.get().config().authToken();
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        return HTTP.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() == 200) {
                    try { return GSON.fromJson(resp.body(), JsonObject.class); }
                    catch (Exception e) {
                        RevivalPVPMod.LOGGER.warn("Bad JSON from {}: {}", url, e.getMessage());
                        return null;
                    }
                }
                RevivalPVPMod.LOGGER.warn("GET {} returned {}", url, resp.statusCode());
                return null;
            }).exceptionally(e -> {
                RevivalPVPMod.LOGGER.warn("GET {} error: {}", url, e.getMessage());
                return null;
            });
    }

    private static CompletableFuture<JsonObject> delete(String url) {
        var b = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .DELETE()
            .timeout(Duration.ofSeconds(10));
        String token = RevivalPVPMod.get().config().authToken();
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        return HTTP.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() == 200) {
                    try { return GSON.fromJson(resp.body(), JsonObject.class); }
                    catch (Exception e) { return null; }
                }
                RevivalPVPMod.LOGGER.warn("DELETE {} returned {}", url, resp.statusCode());
                return null;
            }).exceptionally(e -> {
                RevivalPVPMod.LOGGER.warn("DELETE {} error: {}", url, e.getMessage());
                return null;
            });
    }

    /**
     * POST that returns the response JSON regardless of status, with a synthetic
     * `_error` field set on non-200 (mirroring FastAPI's `detail` if present).
     * Callers should check `resp.has("_error")` to distinguish failure.
     */
    private static CompletableFuture<JsonObject> postWithError(String url, JsonObject body) {
        var b = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(10));
        String token = RevivalPVPMod.get().config().authToken();
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        return HTTP.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                JsonObject parsed = null;
                try { parsed = GSON.fromJson(resp.body(), JsonObject.class); }
                catch (Exception ignored) {}
                if (resp.statusCode() == 200) {
                    return parsed != null ? parsed : new JsonObject();
                }
                JsonObject err = parsed != null ? parsed : new JsonObject();
                String detail = err.has("detail") && !err.get("detail").isJsonNull()
                    ? err.get("detail").getAsString()
                    : ("HTTP " + resp.statusCode());
                err.addProperty("_error", detail);
                return err;
            }).exceptionally(e -> {
                JsonObject err = new JsonObject();
                err.addProperty("_error", "Network error: " + e.getMessage());
                return err;
            });
    }

    private static CompletableFuture<JsonObject> post(String url, JsonObject body) {
        var b = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(10));
        String token = RevivalPVPMod.get().config().authToken();
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        return HTTP.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() == 200) {
                    try { return GSON.fromJson(resp.body(), JsonObject.class); }
                    catch (Exception e) {
                        RevivalPVPMod.LOGGER.warn("Bad JSON from {}: {}", url, e.getMessage());
                        return null;
                    }
                }
                // Body intentionally omitted: error responses may echo the
                // request payload (username, uuid, etc) and we don't want
                // that landing in latest.log. Status + URL is enough to
                // diagnose; full body is available on the backend side.
                RevivalPVPMod.LOGGER.warn("POST {} returned {}", url, resp.statusCode());
                return null;
            }).exceptionally(e -> {
                RevivalPVPMod.LOGGER.warn("POST {} error: {}", url, e.getMessage());
                return null;
            });
    }
}
