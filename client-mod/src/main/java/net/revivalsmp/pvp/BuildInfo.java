// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Optional;

/**
 * Mod version + build info, sourced from Fabric loader metadata (which is in
 * turn populated from {@code fabric.mod.json}, which is in turn populated from
 * {@code gradle.properties} at build time). One source of truth, bumping
 * {@code mod_version} in {@code gradle.properties} updates everything.
 *
 * <p>Intentionally no embedded git hash, build host, or user identity, this
 * class is reachable from the public mod jar so it should never leak build-
 * environment information.
 */
public final class BuildInfo {
    public static final String MOD_ID = "revival-pvp";

    private BuildInfo() {}

    /** Mod version string from fabric.mod.json (e.g. "1.0.0+26.1"). */
    public static String version() {
        Optional<ModContainer> mc = FabricLoader.getInstance().getModContainer(MOD_ID);
        return mc.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    /** Friendly display name. */
    public static String name() {
        Optional<ModContainer> mc = FabricLoader.getInstance().getModContainer(MOD_ID);
        return mc.map(c -> c.getMetadata().getName()).orElse("RevivalPVP");
    }

    /** Friendly release codename ("Dynamic Kit Catalog" etc.), populated from
     *  the {@code revival-pvp:release_name} custom field in fabric.mod.json,
     *  which is in turn populated from {@code release_name} in gradle.properties.
     *  Returns an empty string for nameless releases. */
    public static String releaseName() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getCustomValue("revival-pvp:release_name"))
            .filter(v -> v != null && v.getType() == net.fabricmc.loader.api.metadata.CustomValue.CvType.STRING)
            .map(v -> v.getAsString())
            .orElse("");
    }
}
