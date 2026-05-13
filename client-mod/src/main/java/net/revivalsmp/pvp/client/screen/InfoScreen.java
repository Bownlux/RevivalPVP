// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.client.screen;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.revivalsmp.pvp.BuildInfo;
import net.revivalsmp.pvp.RevivalPVPMod;
import net.revivalsmp.pvp.client.RevivalPVPClient;
import net.revivalsmp.pvp.client.ui.PVPTheme;
import net.revivalsmp.pvp.network.BackendHttpClient;
import net.revivalsmp.pvp.network.BackendWS;
import net.revivalsmp.pvp.network.UpdateChecker;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Account / Info / Connection panel, opened from the (i) icon in the hub
 * title bar. Modeled on Petlings AccountScreen: centered dark card with
 * sections for connection state, earnings summary, web-login-code generator,
 * and outgoing links to the legal docs on revivalpvp.net.
 *
 * Returns to the parent screen on close (Esc / Back / outside-click).
 */
public class InfoScreen extends Screen {

    // Card geometry. Width matches Petlings AccountScreen for visual consistency
    // across mods; height grows when the web-login code is showing.
    private static final int CARD_W = 280;
    // Card height grew when the Version row was added (was 230). Includes the
    // legal links + sign-out + version footer below them.
    private static final int CARD_H_BASE = 252;

    private final Screen parent;

    private JsonObject me;             // /auth/me response, lazy-loaded
    private boolean    meLoading;
    private String     meError;

    /** Most recent web-login code response. Null = no code outstanding. */
    private String  loginCode;
    private long    loginCodeIssuedAt;
    private int     loginCodeTtlSeconds;
    private boolean codeRequesting;
    private String  codeError;
    /** Toast: epoch-ms until which the "Copied!" badge stays on the Copy button. */
    private long    copiedUntilMs;

    /** Captured each frame for click handling. */
    private final List<int[]> hitRects = new ArrayList<>();
    /** kind codes for hitRects rows: 0=back, 1=copy code, 2=gen code,
     *  3=legal link (terms/privacy/sponsor/refunds via index), 4=sign out,
     *  5=open website (revivalpvp.net root), 6=open update page */
    private static final int K_BACK    = 0;
    private static final int K_COPY    = 1;
    private static final int K_GEN     = 2;
    private static final int K_LEGAL   = 3;
    private static final int K_SIGNOUT = 4;
    private static final int K_OPEN    = 5;
    private static final int K_UPDATE  = 6;

    private static final String[] LEGAL_LABELS = {"Terms", "Privacy", "Sponsor", "Refunds"};
    private static final String[] LEGAL_PATHS  = {
        "/pvp/legal/terms", "/pvp/legal/privacy",
        "/pvp/legal/sponsor-policy", "/refunds",
    };

    public InfoScreen(Screen parent) {
        super(Component.literal("RevivalPVP Account"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    protected void init() {
        loadMeIfNeeded();
        // Trigger an update check the first time the screen opens. Until
        // UpdateChecker.ENABLED is true (no Modrinth release exists yet), this
        // resolves immediately to "no update" without any network request.
        if (UpdateChecker.latest() == null) UpdateChecker.checkOnce();
    }

    private void loadMeIfNeeded() {
        if (me != null || meLoading) return;
        meLoading = true;
        BackendHttpClient.me().thenAccept(resp -> Minecraft.getInstance().execute(() -> {
            meLoading = false;
            if (resp == null) meError = "Could not reach backend.";
            else if (resp.has("_error")) meError = resp.get("_error").getAsString();
            else me = resp;
        }));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        this.renderBackground(g, mx, my, delta);
        hitRects.clear();

        // Backdrop dim
        g.fill(0, 0, width, height, 0xCC000000);

        int extraH = (loginCode != null) ? 36 : 0;
        int cardH  = CARD_H_BASE + extraH;
        int cx = width / 2;
        int cy = height / 2;
        int px = cx - CARD_W / 2;
        int py = cy - cardH / 2;

        // Card frame
        g.fill(px - 1, py - 1, px + CARD_W + 1, py + cardH + 1, PVPTheme.BORDER);
        g.fill(px, py, px + CARD_W, py + cardH, PVPTheme.PANEL);
        g.fill(px, py, px + CARD_W, py + 2, PVPTheme.BORDER);

        // Header: back button (left) + title (center) + open-web-button (right)
        renderTextBtn(g, mx, my, px + 8, py + 8, 44, 14,
            "§7← Back", 0xFF1A1A2A, 0xFF4A4A6A, K_BACK, 0);
        g.drawCenteredString(font, Component.literal("§b§lAccount"),
            cx, py + 10, PVPTheme.TEXT);
        renderTextBtn(g, mx, my, px + CARD_W - 60, py + 8, 52, 14,
            "§bWebsite ↗", 0xFF1A2A3A, 0xFF66E5FF, K_OPEN, 0);

        int rowY = py + 32;

        // ── Connection block ────────────────────────────────────────────────
        if (meLoading && me == null) {
            g.drawString(font, Component.literal("§7Loading..."), px + 12, rowY,
                PVPTheme.TEXT_MUTED, false);
            rowY += 24;
        } else if (meError != null) {
            g.drawString(font, Component.literal("§c" + meError), px + 12, rowY,
                0xFFFF6666, false);
            rowY += 24;
        } else if (me != null) {
            // Connection status dot + text.
            BackendWS ws = RevivalPVPClient.get().backendWS();
            boolean connected = ws != null && ws.isConnected();
            int dot = connected ? 0xFF44CC44 : 0xFFCC4444;
            g.fill(px + 12, rowY + 3, px + 17, rowY + 8, dot);
            g.drawString(font, Component.literal(
                connected ? "§aConnected" : "§cOffline"),
                px + 22, rowY, PVPTheme.TEXT, false);

            String username = optStr(me, "username", "?");
            g.drawString(font, Component.literal("§f§l" + username),
                px + 12, rowY + 12, PVPTheme.TEXT, false);

            String mcUuid = optStr(me, "mc_uuid", null);
            if (mcUuid != null && mcUuid.length() >= 8) {
                g.drawString(font, Component.literal("§7MC: " + mcUuid.substring(0, 8) + "…"),
                    px + 12, rowY + 24, PVPTheme.TEXT_MUTED, false);
            }
            String iss = optStr(me, "iss", null);
            if (iss != null) {
                g.drawString(font, Component.literal("§8via " + shortIssuer(iss)),
                    px + CARD_W - 12 - font.width("via " + shortIssuer(iss)),
                    rowY + 24, PVPTheme.TEXT_MUTED, false);
            }
            rowY += 38;

            // ── Coin balance + earnings ───────────────────────────────────
            int coinBal = optInt(me, "coin_balance", 0);
            int paidBal = optInt(me, "paid_balance", 0);
            g.drawString(font, Component.literal("§d♥ §f" + coinBal + " §7coins"),
                px + 12, rowY, PVPTheme.TEXT, false);
            if (paidBal != coinBal) {
                g.drawString(font, Component.literal("§8(" + paidBal + " paid)"),
                    px + 12 + font.width("♥ " + coinBal + " coins ") + 6, rowY,
                    PVPTheme.TEXT_MUTED, false);
            }
            rowY += 12;

            double avail = optDouble(me, "earnings_available", 0);
            double held  = optDouble(me, "earnings_held",      0);
            double minP  = optDouble(me, "min_payout_usd",     10);
            String earn = String.format("§a$%.2f §7available §8· §7$%.2f held §8· §7min $%.0f",
                avail, held, minP);
            g.drawString(font, Component.literal(earn), px + 12, rowY, PVPTheme.TEXT_MUTED, false);
            rowY += 16;
        }

        // ── Web Login Code section ──────────────────────────────────────────
        g.fill(px + 8, rowY, px + CARD_W - 8, rowY + 1, 0xFF2A2A3A);
        rowY += 6;
        g.drawString(font, Component.literal("§b§lWeb Login"), px + 12, rowY,
            0xFF66E5FF, false);
        rowY += 12;

        if (loginCode != null) {
            int secsLeft = Math.max(0,
                loginCodeTtlSeconds - (int)((System.currentTimeMillis() - loginCodeIssuedAt) / 1000));
            if (secsLeft <= 0) {
                loginCode = null;
            } else {
                g.drawString(font, Component.literal(
                    "§7Visit §brevivalpvp.net/login §7and enter:"),
                    px + 12, rowY, PVPTheme.TEXT_MUTED, false);
                rowY += 12;
                // Code display box on the LEFT, Copy button on the RIGHT.
                // Geometry: code box (px+12, rowY, ..., rowY+18); Copy button
                // 80px wide at right edge with 4px gap; centered text uses the
                // box midpoint (NOT averaged with px, that's the bug that
                // dragged the code off the left side of the panel).
                int codeBoxLeft  = px + 12;
                int codeBoxRight = px + CARD_W - 4 - 80 - 4;   // = panel_right - copy_button_w - gaps
                int codeBoxMid   = (codeBoxLeft + codeBoxRight) / 2;
                g.fill(codeBoxLeft - 1, rowY - 1, codeBoxRight + 1, rowY + 19, 0xFFFF6BA8);
                g.fill(codeBoxLeft, rowY, codeBoxRight, rowY + 18, 0xFF101019);
                g.drawCenteredString(font, Component.literal("§f§l" + loginCode),
                    codeBoxMid, rowY + 5, PVPTheme.TEXT);

                // Copy button, flips to "Copied!" for 1.5s after click.
                boolean justCopied = System.currentTimeMillis() < copiedUntilMs;
                int btnX = codeBoxRight + 4;
                if (justCopied) {
                    renderTextBtn(g, mx, my, btnX, rowY, 80, 18,
                        "§a✓ Copied", 0xFF1A3A1A, 0xFF66FF99, K_COPY, 0);
                } else {
                    renderTextBtn(g, mx, my, btnX, rowY, 80, 18,
                        "§eCopy", 0xFF2A2A1A, 0xFFCCCC44, K_COPY, 0);
                }
                rowY += 22;
                String tt = String.format("§8expires in %d:%02d", secsLeft / 60, secsLeft % 60);
                g.drawString(font, Component.literal(tt), px + 12, rowY,
                    PVPTheme.TEXT_MUTED, false);
                rowY += 14;
            }
        }
        if (loginCode == null) {
            String btnLabel = codeRequesting ? "§7Requesting..." : "§a§l▶ Generate Login Code";
            renderTextBtn(g, mx, my, px + 12, rowY, CARD_W - 24, 18,
                btnLabel, 0xFF1A3A1A, 0xFF00CC44, K_GEN, 0);
            rowY += 22;
            if (codeError != null) {
                g.drawString(font, Component.literal("§c" + codeError),
                    px + 12, rowY, 0xFFFF6666, false);
                rowY += 12;
            }
        }

        // ── Legal links row ─────────────────────────────────────────────────
        g.fill(px + 8, rowY + 2, px + CARD_W - 8, rowY + 3, 0xFF2A2A3A);
        rowY += 8;
        int linkW = (CARD_W - 24 - 18) / 4;   // 4 links + 6px gap each
        for (int i = 0; i < LEGAL_LABELS.length; i++) {
            int lx = px + 12 + i * (linkW + 6);
            renderTextBtn(g, mx, my, lx, rowY, linkW, 14,
                "§b" + LEGAL_LABELS[i], 0xFF14202A, 0xFF66E5FF, K_LEGAL, i);
        }
        rowY += 18;

        // ── Version + update banner ─────────────────────────────────────────
        // Always-visible footer: shows the running version and (when an
        // update is detected) a clickable "Update available" pill.
        UpdateChecker.Result upd = UpdateChecker.latest();
        String verLine = "§8v" + BuildInfo.version();
        g.drawString(font, Component.literal(verLine), px + 12, py + cardH - 22,
            PVPTheme.TEXT_MUTED, false);
        if (upd != null && upd.updateAvailable() && upd.pageUrl() != null) {
            String label = "§e↑ Update to " + upd.latestVersion();
            int btnW = font.width(label) + 12;
            renderTextBtn(g, mx, my, px + 12 + font.width(verLine) + 8,
                py + cardH - 24, btnW, 14,
                label, 0xFF2A2A0A, 0xFFCCCC44, K_UPDATE, 0);
        }

        // ── Sign out (small, dim) ───────────────────────────────────────────
        renderTextBtn(g, mx, my, px + CARD_W - 80, py + cardH - 22, 68, 14,
            "§7Sign Out", 0xFF2A1A1A, 0xFF553333, K_SIGNOUT, 0);

        super.render(g, mx, my, delta);
    }

    private void renderTextBtn(GuiGraphics g, int mx, int my,
                                int x, int y, int w, int h,
                                String label, int bg, int border,
                                int kind, int idx) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, hover ? 0xFFFFFFFF & border | 0xFF000000 : border);
        g.fill(x, y, x + w, y + h, bg);
        g.drawCenteredString(font, Component.literal(label), x + w / 2, y + (h - 8) / 2, PVPTheme.TEXT);
        hitRects.add(new int[]{x, y, x + w, y + h, kind, idx});
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean dbl) {
        double mx = event.x(), my = event.y();
        for (int[] r : hitRects) {
            if (mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3]) {
                int kind = r[4], idx = r[5];
                switch (kind) {
                    case K_BACK    -> Minecraft.getInstance().setScreen(parent);
                    case K_OPEN    -> openBrowser("https://revivalpvp.net/");
                    case K_COPY    -> {
                        if (loginCode != null) {
                            Minecraft.getInstance().keyboardHandler.setClipboard(loginCode);
                            copiedUntilMs = System.currentTimeMillis() + 1500;
                        }
                    }
                    case K_GEN     -> requestLoginCode();
                    case K_LEGAL   -> openBrowser("https://revivalpvp.net" + LEGAL_PATHS[idx]);
                    case K_UPDATE  -> {
                        UpdateChecker.Result u = UpdateChecker.latest();
                        if (u != null && u.pageUrl() != null) openBrowser(u.pageUrl());
                    }
                    case K_SIGNOUT -> signOut();
                }
                return true;
            }
        }
        return super.mouseClicked(event, dbl);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        return super.keyPressed(event);
    }

    private void requestLoginCode() {
        if (codeRequesting) return;
        codeRequesting = true;
        codeError = null;
        BackendHttpClient.webCode().thenAccept(resp -> Minecraft.getInstance().execute(() -> {
            codeRequesting = false;
            if (resp == null) {
                codeError = "Failed to issue code.";
                return;
            }
            if (resp.has("code") && resp.has("expires_in")) {
                loginCode = resp.get("code").getAsString();
                loginCodeIssuedAt = System.currentTimeMillis();
                loginCodeTtlSeconds = resp.get("expires_in").getAsInt();
            } else {
                codeError = "Bad response from backend.";
            }
        }));
    }

    private void signOut() {
        // Drop the token and bounce back to the login flow on next hub open.
        var cfg = RevivalPVPMod.get().config();
        cfg.setAuthToken(null);
        cfg.setPlayerUuid(null);
        BackendWS ws = RevivalPVPClient.get().backendWS();
        if (ws != null && ws.isConnected()) ws.close();
        Minecraft.getInstance().setScreen(null);
    }

    private void openBrowser(String url) {
        try { Util.getPlatform().openUri(URI.create(url)); }
        catch (Throwable t) { RevivalPVPMod.LOGGER.warn("openUri failed: {}", t.getMessage()); }
    }

    private static String shortIssuer(String iss) {
        if (iss == null) return "?";
        int i = iss.indexOf("//");
        return i >= 0 ? iss.substring(i + 2) : iss;
    }

    private static String optStr(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }
    private static int optInt(JsonObject o, String key, int fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : fallback;
    }
    private static double optDouble(JsonObject o, String key, double fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsDouble() : fallback;
    }
}