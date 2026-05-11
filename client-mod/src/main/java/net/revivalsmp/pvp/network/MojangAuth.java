// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.revivalsmp.pvp.RevivalPVPMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Zero-interaction backend auth via Mojang's session servers.
 * Mirrors the petlings-mod pattern.
 *
 * Flow:
 *   1. POST {backend}/auth/begin                              → {server_id}
 *   2. POST sessionserver.mojang.com/session/minecraft/join   (proves we own the MC account)
 *   3. POST {backend}/auth/verify {username}                  → {token, uuid, username}
 *
 * On success, {@code config.authToken} is set and saved. The existing
 * {@link BackendWS} picks it up on the next reconnect via its `auth` message.
 *
 * Offline / cracked clients: step 2 will fail because Mojang rejects the
 * (missing/dummy) access token. The future completes false; caller surfaces
 * a chat error.
 */
public final class MojangAuth {

    private static final Gson GSON = new Gson();
    private static final String MOJANG_JOIN_URL =
        "https://sessionserver.mojang.com/session/minecraft/join";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private MojangAuth() {}

    /**
     * Run the full auth flow async. Resolves true on success (token saved
     * to config), false otherwise. Never throws.
     */
    public static CompletableFuture<Boolean> authenticate() {
        User session = Minecraft.getInstance().getUser();
        if (session == null
            || session.getAccessToken() == null
            || session.getAccessToken().isBlank()
            || session.getProfileId() == null) {
            RevivalPVPMod.LOGGER.warn("No Minecraft session, cannot Mojang-auth");
            return CompletableFuture.completedFuture(false);
        }

        String accessToken = session.getAccessToken();
        String uuid        = session.getProfileId().toString().replace("-", "");
        String username    = session.getName();
        String backendUrl  = RevivalPVPMod.get().config().backendUrl();

        return requestChallenge(backendUrl).thenCompose(serverId -> {
            if (serverId == null) return CompletableFuture.completedFuture(false);
            return joinMojang(accessToken, uuid, serverId).thenCompose(joined -> {
                if (!joined) return CompletableFuture.completedFuture(false);
                return verifyWithBackend(backendUrl, username);
            });
        }).exceptionally(e -> {
            RevivalPVPMod.LOGGER.warn("Mojang auth failed: {}", e.getMessage());
            return false;
        });
    }

    private static CompletableFuture<String> requestChallenge(String backendUrl) {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(backendUrl + "/auth/begin"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(10))
            .build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() == 200) {
                    return GSON.fromJson(resp.body(), JsonObject.class)
                        .get("server_id").getAsString();
                }
                RevivalPVPMod.LOGGER.warn("auth/begin returned HTTP {}", resp.statusCode());
                return null;
            }).exceptionally(e -> {
                RevivalPVPMod.LOGGER.warn("auth/begin error: {}", e.getMessage());
                return null;
            });
    }

    private static CompletableFuture<Boolean> joinMojang(String accessToken, String uuid, String serverId) {
        JsonObject body = new JsonObject();
        body.addProperty("accessToken",     accessToken);
        body.addProperty("selectedProfile", uuid);
        body.addProperty("serverId",        serverId);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(MOJANG_JOIN_URL))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .timeout(Duration.ofSeconds(10))
            .build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() == 204 || resp.statusCode() == 200) return true;
                // Body intentionally omitted: Mojang error payloads can include
                // session-related identifiers we don't want in client logs.
                RevivalPVPMod.LOGGER.warn("Mojang join failed (HTTP {})", resp.statusCode());
                return false;
            }).exceptionally(e -> {
                RevivalPVPMod.LOGGER.warn("Mojang join error: {}", e.getMessage());
                return false;
            });
    }

    private static CompletableFuture<Boolean> verifyWithBackend(String backendUrl, String username) {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(backendUrl + "/auth/verify"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .timeout(Duration.ofSeconds(15))
            .build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() == 200) {
                    JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
                    RevivalPVPMod.get().config().setAuthToken(json.get("token").getAsString());
                    if (json.has("uuid")) {
                        RevivalPVPMod.get().config().setPlayerUuid(json.get("uuid").getAsString());
                    }
                    RevivalPVPMod.LOGGER.info("Mojang auth verified, token + uuid saved");
                    return true;
                }
                // Body intentionally omitted: backend errors may echo the
                // username/uuid or token fragments. Log status only.
                RevivalPVPMod.LOGGER.warn("auth/verify failed (HTTP {})", resp.statusCode());
                return false;
            }).exceptionally(e -> {
                RevivalPVPMod.LOGGER.warn("auth/verify error: {}", e.getMessage());
                return false;
            });
    }
}
