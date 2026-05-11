// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.servermod.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.revivalsmp.pvp.servermod.RevivalPVPServerMod;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Tenant-keyed HTTP calls to /tenant/* endpoints.
 *
 * <p>Auth: X-Tenant-Key (server-wide) + X-MC-UUID (per-player). Backend
 * resolves to the player's internal uuid. Mirrors the bearer-JWT calls the
 * client-mod makes, but carries no per-player secret.
 *
 * <p>Anonymous reads (sponsor/profile, sponsor/leaderboard, rankings) hit
 * the regular /sponsor and /rankings endpoints with no auth headers.
 */
public final class TenantHttpClient {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        // Force HTTP/1.1 — Java's default HTTP/2 upgrade headers (h2c) are
        // rejected by uvicorn ("Invalid HTTP request received" → 400) and
        // poison the body parse on every call.
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    private TenantHttpClient() {}

    // ── Friends (tenant-keyed) ────────────────────────────────────────────────

    public static CompletableFuture<JsonObject> listFriends(RevivalPVPServerMod plugin, UUID mcUuid) {
        return getTenant(plugin, "/tenant/friends", mcUuid);
    }

    public static CompletableFuture<JsonObject> listFriendRequests(RevivalPVPServerMod plugin, UUID mcUuid) {
        return getTenant(plugin, "/tenant/friends/requests", mcUuid);
    }

    public static CompletableFuture<JsonObject> sendFriendRequest(
            RevivalPVPServerMod plugin, UUID mcUuid, String targetUsername) {
        JsonObject body = new JsonObject();
        body.addProperty("target_username", targetUsername);
        return postTenant(plugin, "/tenant/friends/request", mcUuid, body);
    }

    public static CompletableFuture<JsonObject> acceptFriendRequest(
            RevivalPVPServerMod plugin, UUID mcUuid, long requestId) {
        JsonObject body = new JsonObject();
        body.addProperty("request_id", requestId);
        return postTenant(plugin, "/tenant/friends/accept", mcUuid, body);
    }

    public static CompletableFuture<JsonObject> rejectFriendRequest(
            RevivalPVPServerMod plugin, UUID mcUuid, long requestId) {
        JsonObject body = new JsonObject();
        body.addProperty("request_id", requestId);
        return postTenant(plugin, "/tenant/friends/reject", mcUuid, body);
    }

    public static CompletableFuture<JsonObject> sendDuelInvite(
            RevivalPVPServerMod plugin, UUID mcUuid, String friendUuid, String kit, boolean ranked) {
        JsonObject body = new JsonObject();
        body.addProperty("friend_uuid", friendUuid);
        body.addProperty("kit", kit);
        body.addProperty("ranked", ranked);
        return postTenant(plugin, "/tenant/friends/invite", mcUuid, body);
    }

    public static CompletableFuture<JsonObject> acceptInvite(
            RevivalPVPServerMod plugin, UUID mcUuid, String inviteId) {
        return postTenant(plugin,
            "/tenant/friends/invite/" + URLEncoder.encode(inviteId, StandardCharsets.UTF_8) + "/accept",
            mcUuid, new JsonObject());
    }

    /** Accept-invite variant that returns the FastAPI error detail on failure
     *  so callers can surface the real reason ('Invite expired', 'Both
     *  players must be online', etc.) instead of a generic 'no longer valid'. */
    public record AcceptResult(JsonObject body, String errorMessage) {}

    public static CompletableFuture<AcceptResult> acceptInviteWithError(
            RevivalPVPServerMod plugin, UUID mcUuid, String inviteId) {
        String path = "/tenant/friends/invite/"
            + URLEncoder.encode(inviteId, StandardCharsets.UTF_8) + "/accept";
        var b = HttpRequest.newBuilder()
            .uri(URI.create(base(plugin) + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(10));
        applyTenantAuth(plugin, b, mcUuid);
        return HTTP.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                int status = resp.statusCode();
                if (status == 200) {
                    try {
                        JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
                        return new AcceptResult(json, null);
                    } catch (Exception e) {
                        return new AcceptResult(null, "Bad response from backend.");
                    }
                }
                String detail = "Invite no longer valid.";
                try {
                    JsonObject parsed = GSON.fromJson(resp.body(), JsonObject.class);
                    if (parsed != null && parsed.has("detail")) {
                        detail = parsed.get("detail").getAsString();
                    } else {
                        detail = "Accept failed (HTTP " + status + ").";
                    }
                } catch (Exception ignored) {
                    detail = "Accept failed (HTTP " + status + ").";
                }
                // Body deliberately not logged — public release goes onto
                // operator consoles and we don't want to leak backend
                // internals (stack traces, field names) into their stdout.
                plugin.getLogger().warning("POST " + path + " returned HTTP " + status);
                return new AcceptResult(null, detail);
            }).exceptionally(e -> {
                plugin.getLogger().warning("POST " + path + " error: " + e.getMessage());
                return new AcceptResult(null, "Backend unreachable. Try again.");
            });
    }

    public static CompletableFuture<JsonObject> declineInvite(
            RevivalPVPServerMod plugin, UUID mcUuid, String inviteId) {
        return postTenant(plugin,
            "/tenant/friends/invite/" + URLEncoder.encode(inviteId, StandardCharsets.UTF_8) + "/decline",
            mcUuid, new JsonObject());
    }

    // ── Sponsor coins (tenant-keyed) ─────────────────────────────────────────

    public static CompletableFuture<JsonObject> myCoins(RevivalPVPServerMod plugin, UUID mcUuid) {
        return getTenant(plugin, "/tenant/coins", mcUuid);
    }

    public static CompletableFuture<JsonObject> sponsor(
            RevivalPVPServerMod plugin, UUID mcUuid, String targetIdentifier, int coins) {
        JsonObject body = new JsonObject();
        body.addProperty("coins", coins);
        return postTenant(plugin,
            "/tenant/sponsor/" + URLEncoder.encode(targetIdentifier, StandardCharsets.UTF_8),
            mcUuid, body);
    }

    // ── Anonymous reads ─────────────────────────────────────────────────────

    public static CompletableFuture<JsonObject> sponsorProfile(RevivalPVPServerMod plugin, String identifier) {
        return getAnon(plugin, "/sponsor/profile/" + URLEncoder.encode(identifier, StandardCharsets.UTF_8));
    }

    public static CompletableFuture<JsonObject> sponsorLeaderboard(RevivalPVPServerMod plugin, int limit) {
        return getAnon(plugin, "/sponsor/leaderboard?season=current&limit=" + limit);
    }

    public static CompletableFuture<JsonObject> kitLeaderboard(RevivalPVPServerMod plugin, String kit, int limit) {
        return getAnon(plugin, "/rankings/" + URLEncoder.encode(kit, StandardCharsets.UTF_8) + "?limit=" + limit);
    }

    /** Anonymous: full per-kit stat block for any player by username/uuid.
     *  Used by ProfileGui to render the player's own ranks. */
    public static CompletableFuture<JsonObject> playerStats(RevivalPVPServerMod plugin, String identifier) {
        return getAnon(plugin, "/players/" + URLEncoder.encode(identifier, StandardCharsets.UTF_8) + "/stats");
    }

    /** Tenant-keyed: issue a one-time web login code so the player can sign in
     *  on revivalpvp.net/login from /pvp without ever leaving the game. */
    public static CompletableFuture<JsonObject> webCode(RevivalPVPServerMod plugin, UUID mcUuid) {
        return postTenant(plugin, "/tenant/web-code", mcUuid, new JsonObject());
    }

    // ── Live duels + spectate ────────────────────────────────────────────────

    /** Anonymous list of currently-live matches (no auth needed — same data
     *  the website's Live Duels page reads). Cap at 50 by default. */
    public static CompletableFuture<JsonObject> liveDuels(RevivalPVPServerMod plugin) {
        return getAnon(plugin, "/duels/live?limit=50");
    }

    /** Tenant-keyed: issue a spectator session for the given match. Backend
     *  returns {@code relay_host}, {@code relay_port}, and a {@code spec-…}
     *  {@code session_token} the duel-plugin recognizes as spectator-only. */
    public static CompletableFuture<JsonObject> spectate(
            RevivalPVPServerMod plugin, UUID mcUuid, String matchId) {
        return postTenant(plugin,
            "/tenant/duels/" + URLEncoder.encode(matchId, StandardCharsets.UTF_8) + "/spectate",
            mcUuid, new JsonObject());
    }

    /** Tenant-keyed: submit a map vote for the player's active match. */
    public static CompletableFuture<JsonObject> voteMap(
            RevivalPVPServerMod plugin, UUID mcUuid, String matchId, String mapId) {
        JsonObject body = new JsonObject();
        body.addProperty("map_id", mapId);
        return postTenant(plugin,
            "/tenant/duels/" + URLEncoder.encode(matchId, StandardCharsets.UTF_8) + "/vote-map",
            mcUuid, body);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private static String base(RevivalPVPServerMod plugin) {
        String b = plugin.backendUrl();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private static CompletableFuture<JsonObject> getAnon(RevivalPVPServerMod plugin, String path) {
        var b = HttpRequest.newBuilder()
            .uri(URI.create(base(plugin) + path))
            .GET()
            .timeout(Duration.ofSeconds(10));
        return send(plugin, b.build(), "GET", path);
    }

    private static CompletableFuture<JsonObject> getTenant(
            RevivalPVPServerMod plugin, String path, UUID mcUuid) {
        var b = HttpRequest.newBuilder()
            .uri(URI.create(base(plugin) + path))
            .GET()
            .timeout(Duration.ofSeconds(10));
        applyTenantAuth(plugin, b, mcUuid);
        return send(plugin, b.build(), "GET", path);
    }

    private static CompletableFuture<JsonObject> postTenant(
            RevivalPVPServerMod plugin, String path, UUID mcUuid, JsonObject body) {
        var b = HttpRequest.newBuilder()
            .uri(URI.create(base(plugin) + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(10));
        applyTenantAuth(plugin, b, mcUuid);
        return send(plugin, b.build(), "POST", path);
    }

    /**
     * Plugin-enable "I'm here" ping. Distinct from {@link #postTenant} because
     * it carries no per-player MC UUID — it's a server-wide signal.
     *
     * <p>Backend side-effect: stamps {@code tenants.last_seen_at}, which the
     * operator dashboard polls on to collapse the InstallPanel into the green
     * "Server connected" banner. Returns the tenant's plan + status so the
     * mod can log/show informational hints on enable.
     */
    public static CompletableFuture<JsonObject> heartbeat(RevivalPVPServerMod plugin) {
        if (plugin.tenantKey().isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        var b = HttpRequest.newBuilder()
            .uri(URI.create(base(plugin) + "/tenants/heartbeat"))
            .header("Content-Type", "application/json")
            .header("X-Tenant-Key", plugin.tenantKey())
            .header("X-Plugin-Version", plugin.getPluginMeta().getVersion())
            .POST(HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(10));
        return send(plugin, b.build(), "POST", "/tenants/heartbeat");
    }

    private static void applyTenantAuth(RevivalPVPServerMod plugin, HttpRequest.Builder b, UUID mcUuid) {
        b.header("X-Tenant-Key", plugin.tenantKey());
        b.header("X-MC-UUID", mcUuid.toString());
    }

    private static CompletableFuture<JsonObject> send(
            RevivalPVPServerMod plugin, HttpRequest req, String method, String path) {
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() == 200) {
                    try { return GSON.fromJson(resp.body(), JsonObject.class); }
                    catch (Exception e) {
                        plugin.getLogger().warning(method + " " + path + " bad JSON: " + e.getMessage());
                        return null;
                    }
                }
                // Body deliberately not logged — see comment above.
                plugin.getLogger().warning(method + " " + path + " returned HTTP " + resp.statusCode());
                return null;
            }).exceptionally(e -> {
                plugin.getLogger().warning(method + " " + path + " error: " + e.getMessage());
                return null;
            });
    }
}