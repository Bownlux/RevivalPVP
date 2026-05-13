// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.revivalsmp.pvp.RevivalPVPMod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class PVPConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE = "revivalpvp.json";

    // Fields serialized to/from JSON
    public String backendUrl = "https://api.revivalpvp.net";
    public String authToken  = null;   // JWT, null until Mojang auth completes
    public String playerUuid = null;   // Internal player uuid from /auth/verify
    public boolean showHud   = true;
    public String hubKey     = "P";

    // ── Derived accessors ─────────────────────────────────────────────────────

    public String backendUrl() { return backendUrl; }

    public String backendWsUrl() {
        return backendUrl.replaceFirst("^http", "ws") + "/queue/ws";
    }

    public String authToken() { return authToken; }

    public void setAuthToken(String token) {
        authToken = token;
        save();
    }

    public String playerUuid() { return playerUuid; }

    public void setPlayerUuid(String uuid) {
        playerUuid = uuid;
        save();
    }

    // ── Load / Save ───────────────────────────────────────────────────────────

    public static PVPConfig load() {
        Path path = configPath();
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path)) {
                PVPConfig cfg = GSON.fromJson(r, PVPConfig.class);
                if (cfg != null) return cfg;
            } catch (Exception e) {
                RevivalPVPMod.LOGGER.warn("Failed to read config, using defaults: {}", e.getMessage());
            }
        }
        PVPConfig defaults = new PVPConfig();
        defaults.save();
        return defaults;
    }

    public void save() {
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                GSON.toJson(this, w);
            }
        } catch (IOException e) {
            RevivalPVPMod.LOGGER.warn("Failed to save config: {}", e.getMessage());
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE);
    }
}