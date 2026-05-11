// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.kit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.revivalsmp.pvp.RevivalPVPMod;
import net.revivalsmp.pvp.network.BackendHttpClient;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime catalog of kits. Replaces the old hardcoded {@code Kit} enum so
 * the kit list is data-driven from the backend's {@code /kits/catalog} endpoint.
 *
 * <p>Lookup chain on first access (in order):
 * <ol>
 *   <li>In-memory map (populated by a previous fetch).</li>
 *   <li>Disk cache at {@code <config>/revivalpvp-kit-catalog.json} (last
 *       known-good response from the backend).</li>
 *   <li>Built-in minimal fallback (FIST + SWORD only) so the hub still
 *       opens even when offline / first-run / backend permanently dead.</li>
 * </ol>
 *
 * <p>{@link #refresh()} kicks off an async HTTP fetch and updates the
 * in-memory map + disk cache on success. Call it on hub-open so the
 * catalog stays warm; existing UI rendering keeps working off the stale
 * map while the new one loads in the background.
 */
public final class KitRegistry {

    private KitRegistry() {}

    private static final String CACHE_FILE = "revivalpvp-kit-catalog.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Live catalog. LinkedHashMap preserves the backend's sort_order. */
    private static volatile LinkedHashMap<String, Kit> kits = null;
    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicBoolean fetchInFlight = new AtomicBoolean(false);

    /** Lazy first-time init from disk cache or bundled fallback. Safe to call
     *  from any thread; only the first caller actually loads. Subsequent
     *  accessors are non-blocking. */
    private static void initIfNeeded() {
        if (initialized.get()) return;
        synchronized (KitRegistry.class) {
            if (initialized.get()) return;
            LinkedHashMap<String, Kit> seed = loadFromDisk();
            if (seed == null || seed.isEmpty()) seed = bundledFallback();
            kits = seed;
            initialized.set(true);
        }
    }

    /** Get a kit by name (case-insensitive). Returns null if not in the catalog. */
    public static Kit get(String name) {
        if (name == null) return null;
        initIfNeeded();
        return kits.get(name.toUpperCase(Locale.ROOT));
    }

    /** Like {@link #get(String)} but returns a placeholder Kit on miss so UI
     *  callers don't NPE. Useful for live-match result screens where the
     *  catalog might lag behind a brand-new kit. */
    public static Kit getOrPlaceholder(String name) {
        Kit k = get(name);
        if (k != null) return k;
        return new Kit(
            name == null ? "?" : name.toUpperCase(Locale.ROOT),
            name == null ? "Unknown Kit" : name,
            "Loading catalog…",
            "BARRIER",
            true,           // mark mod_only so UI knows to grey it
            Integer.MAX_VALUE,
            new JsonArray()
        );
    }

    /** Snapshot of the current catalog, sorted by the backend's sort_order. */
    public static List<Kit> all() {
        initIfNeeded();
        return new ArrayList<>(kits.values());
    }

    /** Kick off a background HTTP refresh. No-op if a fetch is already in flight.
     *  Call on hub-open so the catalog stays current; UI keeps rendering the
     *  cached version while this runs. */
    public static CompletableFuture<Void> refresh() {
        if (!fetchInFlight.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        return BackendHttpClient.kitCatalog()
            .thenAccept(resp -> {
                try {
                    if (resp == null || !resp.has("kits")) return;
                    LinkedHashMap<String, Kit> next = parseCatalog(resp);
                    if (next.isEmpty()) return;
                    synchronized (KitRegistry.class) {
                        kits = next;
                        initialized.set(true);
                    }
                    KitLoadout.invalidateCache();
                    saveToDisk(resp);
                } catch (Throwable t) {
                    RevivalPVPMod.LOGGER.warn("KitRegistry: refresh parse failed", t);
                }
            })
            .exceptionally(ex -> {
                RevivalPVPMod.LOGGER.warn("KitRegistry: refresh fetch failed: {}", ex.getMessage());
                return null;
            })
            .whenComplete((v, t) -> fetchInFlight.set(false));
    }

    // ── Parsing & persistence ──────────────────────────────────────────────

    private static LinkedHashMap<String, Kit> parseCatalog(JsonObject resp) {
        LinkedHashMap<String, Kit> out = new LinkedHashMap<>();
        for (JsonElement el : resp.getAsJsonArray("kits")) {
            JsonObject k = el.getAsJsonObject();
            String name        = optStr(k, "name", "?");
            String display     = optStr(k, "display", name);
            String description = optStr(k, "description", "");
            String iconMat     = optStr(k, "icon_material", "BARRIER");
            boolean modOnly    = k.has("mod_only") && k.get("mod_only").getAsBoolean();
            int sortOrder      = k.has("sort_order") ? k.get("sort_order").getAsInt() : 0;
            JsonArray loadout  = k.has("default_loadout") && k.get("default_loadout").isJsonArray()
                ? k.getAsJsonArray("default_loadout") : new JsonArray();
            out.put(name.toUpperCase(Locale.ROOT),
                new Kit(name, display, description, iconMat, modOnly, sortOrder, loadout));
        }
        return out;
    }

    private static LinkedHashMap<String, Kit> loadFromDisk() {
        Path path = cachePath();
        if (!Files.exists(path)) return null;
        try (Reader r = Files.newBufferedReader(path)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            return parseCatalog(root);
        } catch (Throwable t) {
            RevivalPVPMod.LOGGER.warn("KitRegistry: failed to read disk cache: {}", t.getMessage());
            return null;
        }
    }

    private static void saveToDisk(JsonObject resp) {
        try (Writer w = Files.newBufferedWriter(cachePath())) {
            GSON.toJson(resp, w);
        } catch (IOException e) {
            RevivalPVPMod.LOGGER.warn("KitRegistry: failed to persist disk cache: {}", e.getMessage());
        }
    }

    private static Path cachePath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CACHE_FILE);
    }

    /** Tiny bundled fallback used only when there is no disk cache AND the
     *  backend can't be reached on first-ever launch. Lets the hub open with
     *  enough to show "you're offline" rather than a blank screen. */
    private static LinkedHashMap<String, Kit> bundledFallback() {
        LinkedHashMap<String, Kit> out = new LinkedHashMap<>();
        out.put("FIST",  new Kit("FIST",  "Fist",  "Raw hand-to-hand. No items.",
            "PLAYER_HEAD", false, 0, new JsonArray()));
        out.put("SWORD", new Kit("SWORD", "Sword", "Classic SMP kit.",
            "NETHERITE_SWORD", false, 1, new JsonArray()));
        return out;
    }

    private static String optStr(JsonObject o, String key, String fallback) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : fallback;
    }
}
