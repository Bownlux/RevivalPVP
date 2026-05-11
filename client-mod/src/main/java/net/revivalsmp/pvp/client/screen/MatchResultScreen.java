// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.revivalsmp.pvp.client.ui.PVPTheme;
import net.revivalsmp.pvp.matchmaking.MatchmakingService;

/**
 * Post-duel result screen.
 * Shows WIN/LOSS, LP change, new rank, and promotion banner if applicable.
 * Auto-dismisses after 8 seconds.
 */
public class MatchResultScreen extends Screen {

    private final MatchmakingService matchmaking;
    private final MatchmakingService.MatchResult result;
    private int ticksOpen = 0;
    private static final int AUTO_CLOSE_TICKS = 160; // 8 seconds

    public MatchResultScreen(MatchmakingService matchmaking) {
        super(Component.literal("Match Result"));
        this.matchmaking = matchmaking;
        this.result      = matchmaking.lastResult();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (result == null) { onClose(); return; }

        int cx = width / 2, cy = height / 2;
        int pw = 320, ph = 200;
        int px = cx - pw / 2, py = cy - ph / 2;

        g.fill(0, 0, width, height, 0xCC000000);
        g.fill(px, py, px + pw, py + ph, PVPTheme.PANEL);

        boolean won = result.won();
        int accentColor = won ? PVPTheme.WIN : PVPTheme.LOSS;
        g.fill(px, py, px + pw, py + 3, accentColor);

        // Result headline
        String headline = won ? "§a§lVICTORY" : "§c§lDEFEAT";
        g.centeredText(font, Component.literal(headline), cx, py + 16, accentColor);

        // LP change
        String lpStr = (result.lpChange() >= 0 ? "§a+" : "§c") + result.lpChange() + " LP";
        g.centeredText(font, Component.literal(lpStr), cx, py + 40, PVPTheme.TEXT);

        // New rank
        g.centeredText(font, Component.literal("§7" + result.newRank()), cx, py + 56, PVPTheme.TEXT_MUTED);

        // Promotion banner
        if (result.promoted()) {
            g.fill(px + 20, py + 74, px + pw - 20, py + 94, 0xFF1A2A1A);
            g.fill(px + 20, py + 74, px + pw - 20, py + 77, PVPTheme.WIN);
            g.centeredText(font, Component.literal("§a§l★  RANK UP!  ★"), cx, py + 80, PVPTheme.WIN);
        }

        // Progress bar (auto-close countdown)
        float progress = 1f - ((float) ticksOpen / AUTO_CLOSE_TICKS);
        int barW = pw - 40;
        g.fill(px + 20, py + ph - 14, px + 20 + barW, py + ph - 8, 0xFF1A1A2A);
        g.fill(px + 20, py + ph - 14, px + 20 + (int)(barW * progress), py + ph - 8, accentColor);
        g.centeredText(font, Component.literal("§7Click anywhere to dismiss"), cx, py + ph - 26, PVPTheme.TEXT_MUTED);

        super.extractRenderState(g, mx, my, delta);
    }

    @Override
    public void tick() {
        ticksOpen++;
        if (ticksOpen >= AUTO_CLOSE_TICKS) onClose();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        onClose();
        return true;
    }

    @Override
    public void onClose() {
        matchmaking.clearResult();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
