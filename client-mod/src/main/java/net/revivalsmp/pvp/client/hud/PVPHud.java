// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.revivalsmp.pvp.RevivalPVPMod;
import net.revivalsmp.pvp.client.RevivalPVPClient;
import net.revivalsmp.pvp.client.ui.PVPTheme;
import net.revivalsmp.pvp.matchmaking.MatchmakingService;

/**
 * Renders the RevivalPVP HUD overlay:
 *  - Top-right corner: rank badge + LP bar (when in a duel or queuing)
 *  - Queue status ticker (when queuing)
 *  - Match timer (when in duel)
 */
public class PVPHud {

    private static final int HUD_X_OFFSET = 4; // from right edge
    private static final int HUD_Y        = 4;
    private static final int BADGE_W      = 90;
    private static final int BADGE_H      = 22;

    public void render(GuiGraphics g, net.minecraft.client.DeltaTracker dt) {
        if (!RevivalPVPMod.get().config().showHud) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return; // hide HUD when any screen is open

        var ms = RevivalPVPClient.get().matchmaking();
        MatchmakingService.State state = ms.state();

        int sw = mc.getWindow().getGuiScaledWidth();
        int bx = sw - BADGE_W - HUD_X_OFFSET;

        switch (state) {
            case QUEUING  -> renderQueueBadge(g, ms, bx);
            case IN_DUEL  -> renderDuelBadge(g, ms, bx);
            default       -> {} // idle, no HUD
        }
    }

    private void renderQueueBadge(GuiGraphics g, MatchmakingService ms, int x) {
        long secs = ms.queueElapsedMs() / 1000;
        String time = String.format("%02d:%02d", secs / 60, secs % 60);
        String kit  = ms.queuedKit() != null ? ms.queuedKit().display : "?";

        drawBadge(g, x, HUD_Y, BADGE_W, BADGE_H, PVPTheme.WARNING);
        drawText(g, "§e⏳ " + kit + " " + time, x + 4, HUD_Y + 7, PVPTheme.TEXT);
    }

    private void renderDuelBadge(GuiGraphics g, MatchmakingService ms, int x) {
        var match = ms.activeMatch();
        if (match == null) return;
        drawBadge(g, x, HUD_Y, BADGE_W, BADGE_H, PVPTheme.BORDER);
        drawText(g, "§b⚔ vs §f" + match.opponentName(), x + 4, HUD_Y + 7, PVPTheme.TEXT);
    }

    private void drawBadge(GuiGraphics g, int x, int y, int w, int h, int borderColor) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, PVPTheme.alpha(borderColor, 180));
        g.fill(x, y, x + w, y + h, PVPTheme.alpha(PVPTheme.PANEL, 200));
    }

    private void drawText(GuiGraphics g, String text, int x, int y, int color) {
        var font = Minecraft.getInstance().font;
        g.text(font, text, x, y, color, false);
    }
}