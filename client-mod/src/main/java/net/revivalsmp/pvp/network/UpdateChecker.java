// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.network;

import net.revivalsmp.pvp.BuildInfo;
import net.revivalsmp.pvp.RevivalPVPMod;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polls CurseForge's public files.rss feed for newer mod releases.
 *
 * <p>Why CurseForge RSS, not the CurseForge REST API: the REST API requires
 * an API key, and embedding one in a publicly distributed mod jar is a
 * security risk (anyone can extract and abuse it). The files.rss feed is
 * unauthenticated and publishes the same data: title (jar filename),
 * link (download page), pubDate.
 *
 * <p>Why not our own backend: we want this to work even when the player has
 * never logged into RevivalPVP, and we don't want every cold launch hitting
 * our infrastructure. CurseForge serves the feed via their CDN.
 *
 * <p>Privacy: a vanilla GET to {@code www.curseforge.com}; no mod data,
 * player UUID, or auth headers are sent. CurseForge sees the same traffic
 * any browser or RSS reader would send them.
 */
public final class UpdateChecker {

    /** CurseForge project slug. The public file feed URL is built from it. */
    public static final String CURSEFORGE_SLUG = "revivalpvp";

    private static final String FEED_URL =
        "https://www.curseforge.com/minecraft/mc-mods/" + CURSEFORGE_SLUG + "/files.rss";

    private static final String PAGE_URL =
        "https://www.curseforge.com/minecraft/mc-mods/" + CURSEFORGE_SLUG;

    /** Extracts the first {@code 1.2.3} (or {@code 1.2.3.4}) sequence from a
     *  jar filename. CurseForge's RSS title is the literal filename, e.g.
     *  "revival-pvp-mod-1.1.0-26.1.jar". */
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?)");

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        // Same HTTP/1.1 pin we use everywhere else (CF's edge will accept
        // HTTP/2 but pinning matches the rest of the mod's outbound traffic
        // and dodges upgrade-header weirdness on some Java builds).
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    /** Result of the most recent check. Null until the first check completes. */
    private static final AtomicReference<Result> LATEST = new AtomicReference<>(null);

    /** Snapshot of the most recent check result, or null if no check has succeeded. */
    public static Result latest() { return LATEST.get(); }

    /** Triggers a new check. Returns a future that resolves to the result.
     *  Safe to call repeatedly; failed checks log at INFO and leave LATEST alone. */
    public static CompletableFuture<Result> checkOnce() {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(FEED_URL))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "revival-pvp-mod/" + BuildInfo.version() + " (update-check)")
            .header("Accept",     "application/rss+xml, application/xml, text/xml")
            .GET()
            .build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray())
            .handle((resp, err) -> {
                if (err != null || resp.statusCode() != 200) {
                    RevivalPVPMod.LOGGER.info("update check failed: {}",
                        err != null ? err.getMessage() : "HTTP " + resp.statusCode());
                    return null;
                }
                try {
                    String latestVer = parseLatestVersion(resp.body());
                    if (latestVer == null) {
                        RevivalPVPMod.LOGGER.info("update check: no version found in CF feed");
                        return null;
                    }
                    boolean newer = isNewer(latestVer, BuildInfo.version());
                    Result r = new Result(BuildInfo.version(), latestVer, newer, PAGE_URL);
                    LATEST.set(r);
                    return r;
                } catch (Exception parseErr) {
                    RevivalPVPMod.LOGGER.info("update check parse failed: {}", parseErr.getMessage());
                    return null;
                }
            });
    }

    /** Extract the latest version string from the CurseForge RSS feed body.
     *  Picks the first {@code <item><title>} and pulls a SemVer-shaped run
     *  of digits from it. Returns null if the feed has no items / no parseable
     *  version. */
    static String parseLatestVersion(byte[] xmlBody) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        // Prevent XXE: CF's feed is trusted, but defensive defaults are cheap.
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setExpandEntityReferences(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(new ByteArrayInputStream(xmlBody));
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            NodeList titles = ((org.w3c.dom.Element) items.item(i)).getElementsByTagName("title");
            if (titles.getLength() == 0) continue;
            String title = titles.item(0).getTextContent();
            if (title == null) continue;
            Matcher m = VERSION_PATTERN.matcher(title);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /** Lexicographic-by-segment comparison; "1.2.0" > "1.1.9", "1.0.0" < "1.0.0+1".
     *  Good enough for SemVer-shaped tags; we don't need a full SemVer parser. */
    static boolean isNewer(String latest, String current) {
        if (latest == null || current == null) return false;
        // Strip the MC build suffix that BuildInfo adds: "1.1.0-26.1" → "1.1.0"
        current = stripBuildSuffix(current);
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

    /** BuildInfo.version() returns "1.1.0-26.1"; the CF feed extracts "1.1.0".
     *  Trim everything after the MC-build dash to make the comparison meaningful. */
    private static String stripBuildSuffix(String s) {
        if (s == null) return null;
        Matcher m = VERSION_PATTERN.matcher(s);
        return m.find() ? m.group(1) : s;
    }

    private static int compareSegment(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    /** Result of the most recent check. {@code latestVersion} equals
     *  {@code currentVersion} when no update is available. {@code pageUrl}
     *  is the CurseForge mod page (always non-null when a check succeeded). */
    public record Result(
        String  currentVersion,
        String  latestVersion,
        boolean updateAvailable,
        String  pageUrl
    ) {}

    private UpdateChecker() {}
}
