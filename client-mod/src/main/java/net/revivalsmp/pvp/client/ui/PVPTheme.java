// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.client.ui;

/**
 * Centralized color + sizing constants for all RevivalPVP screens and HUD.
 */
public final class PVPTheme {

    // ── Base palette ─────────────────────────────────────────────────────────
    public static final int BG           = 0xFF0A0A0F;
    public static final int PANEL        = 0xFF12121A;
    public static final int PANEL_HOVER  = 0xFF1C1C2E;
    public static final int BORDER       = 0xFF00D4FF;
    public static final int BORDER_DIM   = 0xFF003A50;

    // ── Text ─────────────────────────────────────────────────────────────────
    public static final int TEXT         = 0xFFE0E0FF;
    public static final int TEXT_MUTED   = 0xFF7070A0;
    public static final int TEXT_ACCENT  = 0xFF00D4FF;

    // ── Status ───────────────────────────────────────────────────────────────
    public static final int WIN          = 0xFF22C55E;
    public static final int LOSS         = 0xFFEF4444;
    public static final int WARNING      = 0xFFEAB308;

    // ── Rank tier colors ─────────────────────────────────────────────────────
    public static final int[] RANK_COLORS = {
        0xFF444455,  // UNRANKED
        0xFF8B7355,  // IRON
        0xFFCD7F32,  // BRONZE
        0xFFC0C0C0,  // SILVER
        0xFFFFD700,  // GOLD
        0xFF00CED1,  // PLATINUM
        0xFF00BFFF,  // DIAMOND
        0xFF9B59B6,  // MASTER
        0xFFE74C3C,  // GRANDMASTER
        0xFFF1C40F,  // CHALLENGER
    };

    // ── Layout ───────────────────────────────────────────────────────────────
    public static final int PANEL_MAX_W  = 700;
    public static final int PANEL_MAX_H  = 480;
    public static final int PANEL_PAD    = 20;
    public static final int TAB_H        = 18;

    private PVPTheme() {}

    public static int alpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }

    public static int rankColor(String rankName) {
        return switch (rankName.toUpperCase()) {
            case "IRON"        -> RANK_COLORS[1];
            case "BRONZE"      -> RANK_COLORS[2];
            case "SILVER"      -> RANK_COLORS[3];
            case "GOLD"        -> RANK_COLORS[4];
            case "PLATINUM"    -> RANK_COLORS[5];
            case "DIAMOND"     -> RANK_COLORS[6];
            case "MASTER"      -> RANK_COLORS[7];
            case "GRANDMASTER" -> RANK_COLORS[8];
            case "CHALLENGER"  -> RANK_COLORS[9];
            default            -> RANK_COLORS[0];
        };
    }
}
