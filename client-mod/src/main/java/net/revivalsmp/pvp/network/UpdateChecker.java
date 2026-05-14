// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.revivalsmp.pvp.BuildInfo;
import net.revivalsmp.pvp.RevivalPVPMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks the public Modrinth API for newer mod releases.
 *
 * <p>This is intentionally <b>off by default</b>, flip {@link #ENABLED} to
 * {@code true} once the mod has its first Modrinth release uploaded. While
 * disabled, {@link #checkOnce()} immediately resolves to "no update" so any
 * UI surface that consumes {@link #latest()} simply renders nothing.
 *
 * <p>Why public Modrinth, not our own backend: the version-check path runs
 * for every player at every launch and we don't want to put that traffic on
 * our backend. Modrinth's public API is unauthenticated and CDN-fronted.
 *
 * <p>Privacy: the request is a vanilla GET to {@code api.modrinth.com}; no
 * mod data, player UUID, or auth headers are sent. Modrinth sees the same
 * traffic any other mod manager (e.g. Prism) sends them.
 */
public final class UpdateChecker {

    /** Flip this to true when the first Modrinth release exists. Until then,
     *  the checker short-circuits and never sends a network request. */
    public static final boolean ENABLED = false;

    /** Modrinth project slug. Provisional, confirm the actual slug at upload
     *  time and update if different. The value isn't sensitive: anyone with
     *  the mod jar will see HTTP traffic to it. */
    public static final String MODRINTH_SLUG = "revivalpvp";

    private static final String VERSIONS_URL =
        "https://api.modrinth.com/v2/project/" + MODRINTH_SLUG + "/version";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /** Result of the most recent check. Null = never checked or check failed. */
    private static final AtomicReference<Result> LATEST = new AtomicReference<>(null);

    /** Snapshot of the most recent check result, or null if no check has succeeded. */
    public static Result latest() { return LATEST.get(); }

    /** Triggers a new check. Returns a future that resolves to the result.
     *  Safe to call repeatedly; failed checks log at INFO and leave LATEST alone. */
    public static CompletableFuture<Result> checkOnce() {
        if (!ENABLED) {
            Result none = new Result(BuildInfo.version(), BuildInfo.version(), false, null);
            LATEST.set(none);
            return CompletableFuture.completedFuture(none);
        }

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(VERSIONS_URL))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "revival-pvp-mod/" + BuildInfo.version())
            .GET()
            .build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .handle((resp, err) -> {
                if (err != null || resp.statusCode() != 200) {
                    RevivalPVPMod.LOGGER.info("update check failed: {}",
                        err != null ? err.getMessage() : "HTTP " + resp.statusCode());
                    return null;
                }
                try {
                    JsonElement el = JsonParser.parseString(resp.body());
                    if (!el.isJsonArray() || el.getAsJsonArray().isEmpty()) return null;
                    JsonArray arr = el.getAsJsonArray();
                    JsonObject latest = arr.get(0).getAsJsonObject();
                    String latestVer = latest.get("version_number").getAsString();
                    String pageUrl = "https://modrinth.com/mod/" + MODRINTH_SLUG;
                    boolean newer = isNewer(latestVer, BuildInfo.version());
                    Result r = new Result(BuildInfo.version(), latestVer, newer, pageUrl);
                    LATEST.set(r);
                    return r;
                } catch (Exception parseErr) {
                    RevivalPVPMod.LOGGER.info("update check parse failed: {}", parseErr.getMessage());
                    return null;
                }
            });
    }

    /** Lexicographic-by-segment comparison; "1.2.0" > "1.1.9", "1.0.0" < "1.0.0+1".
     *  Good enough for SemVer-shaped tags; we don't need a full SemVer parser. */
    static boolean isNewer(String latest, String current) {
        if (latest == null || current == null) return false;
        if (latest.equals(current)) return false;
        String[] a = latest.split("[.+-]");
        String[] b = current.split("[.+-]");
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int cmp = compareSegment(a[i], b[i]);
            if (cmp != 0) return cmp > 0;
        }
        return a.length > b.length;
    }

    private static int compareSegment(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    /** Result of the most recent check. {@code latestVersion} equals
     *  {@code currentVersion} when no update is available or the check is
     *  disabled. {@code pageUrl} is null for the disabled/no-update case. */
    public record Result(
        String  currentVersion,
        String  latestVersion,
        boolean updateAvailable,
        String  pageUrl
    ) {}

    private UpdateChecker() {}
}