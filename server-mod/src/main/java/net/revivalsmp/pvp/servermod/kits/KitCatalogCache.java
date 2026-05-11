// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.servermod.kits;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.revivalsmp.pvp.servermod.RevivalPVPServerMod;
import org.bukkit.Material;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backend-sourced kit catalog cache for the {@code /pvp} chest GUI.
 *
 * <p>Before v1.6.0 the chest GUI baked in 11 kit slots, which meant every
 * kit addition or rename forced a re-jar + redistribution. This cache
 * mirrors the client mod's data-driven pattern: fetch {@code /kits/catalog}
 * on demand with a 5-minute TTL, fall back to a hardcoded baseline if the
 * backend is unreachable (so the GUI still works during transient outages
 * or before the first successful fetch).
 *
 * <p>Catalog filter: {@code mod_only=true} kits are skipped because the
 * chest GUI exists for players who don't run the client mod — those kits
 * need the mod to render their custom abilities so showing them here would
 * just lead to confusing duels.
 *
 * <p>Thread-safety: the catalog reference is held in an {@link AtomicReference}.
 * Fetch is async (virtual thread); read is non-blocking and returns the last
 * known good value (or the fallback) immediately.
 */
public final class KitCatalogCache {

    /**
     * A single kit row as the chest GUI cares about it. Strict subset of the
     * {@code /kits/catalog} payload — we ignore `description` and
     * `default_loadout` here because the chest GUI doesn't surface them
     * (loadout view is its own GUI, hub here is just slot picker).
     */
    public record Kit(String name, String display, Material icon, int sortOrder) {
        public Kit {
            if (icon == null) icon = Material.IRON_SWORD;  // safety net
        }
    }

    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TTL          = Duration.ofMinutes(5);

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    private static final Gson GSON = new Gson();

    /** Fallback kit set used before the first successful fetch and on hard
     *  network failure. Mirrors the v1.5.0 hardcoded set so existing tenants
     *  don't regress if the backend is briefly unreachable. */
    private static final List<Kit> FALLBACK = List.of(
        new Kit("FIST",     "Fist",     Material.PLAYER_HEAD,    0),
        new Kit("SWORD",    "Sword",    Material.IRON_SWORD,     1),
        new Kit("ARCHER",   "Archer",   Material.BOW,            2),
        new Kit("CROSSBOW", "Crossbow", Material.CROSSBOW,       3),
        new Kit("MACE",     "Mace",     Material.MACE,           4),
        new Kit("CRYSTAL",  "Crystal",  Material.END_CRYSTAL,    5),
        new Kit("SPEAR",    "Spear",    Material.IRON_SWORD,     6),  // SPEAR doesn't exist pre-1.21
        new Kit("TRIDENT",  "Trident",  Material.TRIDENT,        7),
        new Kit("TNT",      "TNT",      Material.TNT,            8),
        new Kit("POTIONS",  "Potions",  Material.SPLASH_POTION,  9),
        new Kit("AXE",      "Axe",      Material.NETHERITE_AXE, 10)
    );

    private final RevivalPVPServerMod plugin;
    private final AtomicReference<List<Kit>> cached = new AtomicReference<>(FALLBACK);
    private volatile Instant lastFetchedAt = Instant.EPOCH;
    private volatile boolean fetchInFlight = false;

    public KitCatalogCache(RevivalPVPServerMod plugin) {
        this.plugin = plugin;
    }

    /**
     * Returns the most recent cached catalog (never null, never empty). Kicks
     * off a refresh in the background if the cache is older than TTL — call
     * sites stay non-blocking so the chest GUI opens instantly. The next
     * open will see the refreshed list.
     */
    public List<Kit> get() {
        if (shouldRefresh()) {
            refreshAsync();
        }
        return cached.get();
    }

    /** Force a refresh — call on plugin enable so the cache is warm by the
     *  time someone runs /pvp. */
    public void warmUp() {
        refreshAsync();
    }

    private boolean shouldRefresh() {
        if (fetchInFlight) return false;
        return Instant.now().isAfter(lastFetchedAt.plus(TTL));
    }

    private void refreshAsync() {
        if (fetchInFlight) return;
        fetchInFlight = true;
        CompletableFuture.runAsync(this::fetchSync)
            .exceptionally(e -> {
                plugin.getLogger().warning("Kit catalog refresh failed: " + e.getMessage()
                    + ". Using last cached catalog or fallback.");
                return null;
            })
            .whenComplete((v, e) -> fetchInFlight = false);
    }

    private void fetchSync() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(plugin.backendUrl() + "/kits/catalog"))
                .timeout(FETCH_TIMEOUT)
                .GET()
                .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                plugin.getLogger().warning("Kit catalog HTTP " + resp.statusCode()
                    + " — keeping previous cache.");
                return;
            }
            JsonObject root = GSON.fromJson(resp.body(), JsonObject.class);
            if (root == null || !root.has("kits")) {
                plugin.getLogger().warning("Kit catalog response missing 'kits' — keeping previous cache.");
                return;
            }
            List<Kit> parsed = new ArrayList<>();
            for (var el : root.getAsJsonArray("kits")) {
                JsonObject k = el.getAsJsonObject();
                // Skip mod_only kits in the chest GUI — those need the client mod to render.
                if (k.has("mod_only") && k.get("mod_only").getAsBoolean()) continue;
                String name    = k.get("name").getAsString();
                String display = k.has("display") ? k.get("display").getAsString() : name;
                String matName = k.has("icon_material") ? k.get("icon_material").getAsString() : "IRON_SWORD";
                int order      = k.has("sort_order") ? k.get("sort_order").getAsInt() : 999;
                Material mat = Material.matchMaterial(matName);
                if (mat == null) mat = Material.IRON_SWORD;
                parsed.add(new Kit(name, display, mat, order));
            }
            if (parsed.isEmpty()) {
                plugin.getLogger().warning("Kit catalog returned no non-mod-only kits — keeping previous cache.");
                return;
            }
            parsed.sort(Comparator.comparingInt(Kit::sortOrder));
            cached.set(Collections.unmodifiableList(parsed));
            lastFetchedAt = Instant.now();
            plugin.getLogger().info("Kit catalog refreshed: " + parsed.size() + " kits loaded.");
        } catch (Exception e) {
            plugin.getLogger().warning("Kit catalog fetch error: " + e.getMessage()
                + " — keeping previous cache.");
        }
    }
}