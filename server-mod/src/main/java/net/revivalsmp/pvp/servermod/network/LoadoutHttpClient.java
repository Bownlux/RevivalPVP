// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.servermod.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.revivalsmp.pvp.servermod.RevivalPVPServerMod;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for the backend's plugin-key-auth loadout endpoints.
 *
 * <p>These endpoints use the {@code X-Plugin-Key} header (not bearer JWT) and
 * are keyed by the backend's <em>internal</em> player_uuid (not the raw
 * Mojang UUID). The internal uuid is cached on
 * {@link RevivalPVPServerMod} from the WS {@code auth_ok} response.
 *
 * <p>All methods return a {@link CompletableFuture}. Failures resolve to
 * {@code null} after logging — callers should handle null gracefully (chat
 * message + close GUI is the typical pattern).
 *
 * <p>Mirrors the bearer-auth client at
 * {@code revival-pvp-mod/src/.../network/BackendHttpClient.java}, but with
 * different header + different URL shape (per-uuid path).
 */
public final class LoadoutHttpClient {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .version(HttpClient.Version.HTTP_1_1)   // uvicorn rejects HTTP/2 upgrade
        .build();

    private LoadoutHttpClient() {}

    /** GET /kits/loadouts/{kit}/by-uuid/{internalUuid} */
    public static CompletableFuture<JsonObject> getLoadout(
            RevivalPVPServerMod plugin, String kit, String playerInternalUuid) {
        String url = base(plugin) + "/kits/loadouts/"
            + URLEncoder.encode(kit, StandardCharsets.UTF_8)
            + "/by-uuid/"
            + URLEncoder.encode(playerInternalUuid, StandardCharsets.UTF_8);
        return get(plugin, url);
    }

    /**
     * POST /kits/loadouts/{kit}/by-uuid/{internalUuid}
     * <p>Body: {@code {"selections":[{"slot":"HEAD","variant_id":1}, ...]}}
     */
    public static CompletableFuture<JsonObject> setLoadout(
            RevivalPVPServerMod plugin, String kit, String playerInternalUuid, JsonArray selections) {
        String url = base(plugin) + "/kits/loadouts/"
            + URLEncoder.encode(kit, StandardCharsets.UTF_8)
            + "/by-uuid/"
            + URLEncoder.encode(playerInternalUuid, StandardCharsets.UTF_8);
        JsonObject body = new JsonObject();
        body.add("selections", selections);
        return post(plugin, url, body);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static String base(RevivalPVPServerMod plugin) {
        String b = plugin.backendUrl();
        // Strip trailing slash so URL concat is predictable.
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private static CompletableFuture<JsonObject> get(RevivalPVPServerMod plugin, String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(10));
        applyAuth(plugin, b);
        return HTTP.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() == 200) {
                    try { return GSON.fromJson(resp.body(), JsonObject.class); }
                    catch (Exception e) {
                        plugin.getLogger().warning("Bad JSON from " + url + ": " + e.getMessage());
                        return null;
                    }
                }
                plugin.getLogger().warning("GET " + url + " returned " + resp.statusCode());
                return null;
            }).exceptionally(e -> {
                plugin.getLogger().warning("GET " + url + " error: " + e.getMessage());
                return null;
            });
    }

    private static CompletableFuture<JsonObject> post(RevivalPVPServerMod plugin, String url, JsonObject body) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(10));
        applyAuth(plugin, b);
        return HTTP.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() == 200) {
                    try { return GSON.fromJson(resp.body(), JsonObject.class); }
                    catch (Exception e) {
                        plugin.getLogger().warning("Bad JSON from " + url + ": " + e.getMessage());
                        return null;
                    }
                }
                plugin.getLogger().warning("POST " + url + " returned " + resp.statusCode() + ": " + resp.body());
                return null;
            }).exceptionally(e -> {
                plugin.getLogger().warning("POST " + url + " error: " + e.getMessage());
                return null;
            });
    }

    private static void applyAuth(RevivalPVPServerMod plugin, HttpRequest.Builder b) {
        String key = plugin.pluginApiKey();
        if (key != null && !key.isBlank()) {
            b.header("X-Plugin-Key", key);
        }
    }
}