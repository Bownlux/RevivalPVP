// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp;

import net.fabricmc.api.ModInitializer;
import net.revivalsmp.pvp.config.PVPConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RevivalPVPMod implements ModInitializer {

    public static final String MOD_ID = "revival-pvp";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static RevivalPVPMod instance;
    private PVPConfig config;

    @Override
    public void onInitialize() {
        instance = this;
        config = PVPConfig.load();
        LOGGER.info("RevivalPVP {} initialized, backend: {}", BuildInfo.version(), config.backendUrl());
    }

    public static RevivalPVPMod get() { return instance; }
    public PVPConfig config() { return config; }
}
