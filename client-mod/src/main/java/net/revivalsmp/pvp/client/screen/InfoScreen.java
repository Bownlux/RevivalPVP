// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.client.screen;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    private static final int K_REFRESH = 7;
    private static final int K_DISCORD = 8;

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
        // Trigger an update check the first time the screen opens. Hits the
        // CurseForge files.rss feed asynchronously; the pill button below renders
        // on the next frame once the response lands.
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
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
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
        g.centeredText(font, Component.literal("§b§lAccount"),
            cx, py + 10, PVPTheme.TEXT);
        renderTextBtn(g, mx, my, px + CARD_W - 60, py + 8, 52, 14,
            "§bWebsite ↗", 0xFF1A2A3A, 0xFF66E5FF, K_OPEN, 0);

        int rowY = py + 32;

        // ── Connection block ────────────────────────────────────────────────
        if (meLoading && me == null) {
            g.text(font, Component.literal("§7Loading..."), px + 12, rowY,
                PVPTheme.TEXT_MUTED, false);
            rowY += 24;
        } else if (meError != null) {
            g.text(font, Component.literal("§c" + meError), px + 12, rowY,
                0xFFFF6666, false);
            rowY += 24;
        } else if (me != null) {
            // Connection status dot + text.
            BackendWS ws = RevivalPVPClient.get().backendWS();
            boolean connected = ws != null && ws.isConnected();
            int dot = connected ? 0xFF44CC44 : 0xFFCC4444;
            g.fill(px + 12, rowY + 3, px + 17, rowY + 8, dot);
            g.text(font, Component.literal(
                connected ? "§aConnected" : "§cOffline"),
                px + 22, rowY, PVPTheme.TEXT, false);

            String username = optStr(me, "username", "?");
            g.text(font, Component.literal("§f§l" + username),
                px + 12, rowY + 12, PVPTheme.TEXT, false);

            String mcUuid = optStr(me, "mc_uuid", null);
            if (mcUuid != null && mcUuid.length() >= 8) {
                g.text(font, Component.literal("§7MC: " + mcUuid.substring(0, 8) + "…"),
                    px + 12, rowY + 24, PVPTheme.TEXT_MUTED, false);
            }
            String iss = optStr(me, "iss", null);
            if (iss != null) {
                g.text(font, Component.literal("§8via " + shortIssuer(iss)),
                    px + CARD_W - 12 - font.width("via " + shortIssuer(iss)),
                    rowY + 24, PVPTheme.TEXT_MUTED, false);
            }
            rowY += 38;

            // ── Sponsor coin balance (spendable) + lifetime totals ──────────
            // Three distinct values surfaced for clarity:
            //   1) Spendable sponsor-coin balance (what you can give right now).
            //   2) Lifetime totals — coins purchased, sponsored, received.
            //   3) Sponsorship USD earnings (cash payable to YOU because
            //      others have sponsored you).
            // Each gets its own labeled line so the heart glyph stops
            // doing double duty for unrelated stats.
            int coinBal       = optInt(me, "coin_balance", 0);
            int paidBal       = optInt(me, "paid_balance", 0);
            int freeBal       = optInt(me, "free_balance", 0);
            int totalBought   = optInt(me, "total_purchased", 0);
            int totalSpent    = optInt(me, "total_spent",     0);
            int totalReceived = optInt(me, "total_received",  0);

            g.text(font, Component.literal(
                "§d♥ §f" + coinBal + " §7coins to spend"),
                px + 12, rowY, PVPTheme.TEXT, false);
            if (paidBal != coinBal) {
                g.text(font, Component.literal("§8(" + paidBal + "p · " + freeBal + "f)"),
                    px + 12 + font.width("♥ " + coinBal + " coins to spend ") + 4, rowY,
                    PVPTheme.TEXT_MUTED, false);
            }
            // Click-to-refresh icon at the right edge of the coin row. Forces
            // a fresh /auth/me fetch, useful right after a Tebex purchase
            // when the cached `me` payload hasn't picked up the new balance.
            int refreshX = px + CARD_W - 12 - 14;
            renderTextBtn(g, mx, my, refreshX, rowY - 2, 14, 14,
                meLoading ? "§7…" : "§b↻", 0xFF14202A, 0xFF66E5FF, K_REFRESH, 0);
            rowY += 12;

            // Lifetime coin totals. One compact line, hidden when all three
            // are zero (new accounts) so we don't waste vertical space.
            // Abbreviated to keep within CARD_W on the smallest GUI scale.
            if (totalBought > 0 || totalSpent > 0 || totalReceived > 0) {
                String life = String.format(
                    "§8Lifetime: §7bought §f%d §8· §7sent §f%d §8· §7got §f%d",
                    totalBought, totalSpent, totalReceived);
                g.text(font, Component.literal(life), px + 12, rowY, PVPTheme.TEXT_MUTED, false);
                rowY += 12;
            }

            // ── Earnings (received from being sponsored, payable in USD) ───
            // These dollars are NOT the coin balance above. They're cash you
            // can withdraw because OTHER players have spent paid coins on
            // you. "held" is the 60-day rolling window per the sponsor
            // policy; "available" is what's past the hold and ready to
            // request via payout. min payout threshold goes on its own
            // line so the headline numbers fit within CARD_W.
            double avail = optDouble(me, "earnings_available", 0);
            double held  = optDouble(me, "earnings_held",      0);
            double minP  = optDouble(me, "min_payout_usd",     10);
            String earn = String.format(
                "§7Payouts: §a$%.2f §7avail §8· §7$%.2f held",
                avail, held);
            g.text(font, Component.literal(earn), px + 12, rowY, PVPTheme.TEXT_MUTED, false);
            rowY += 12;
            g.text(font, Component.literal(String.format("§8min payout $%.0f", minP)),
                px + 12, rowY, PVPTheme.TEXT_MUTED, false);
            rowY += 14;
        }

        // ── Web Login Code section ──────────────────────────────────────────
        g.fill(px + 8, rowY, px + CARD_W - 8, rowY + 1, 0xFF2A2A3A);
        rowY += 6;
        g.text(font, Component.literal("§b§lWeb Login"), px + 12, rowY,
            0xFF66E5FF, false);
        rowY += 12;

        if (loginCode != null) {
            int secsLeft = Math.max(0,
                loginCodeTtlSeconds - (int)((System.currentTimeMillis() - loginCodeIssuedAt) / 1000));
            if (secsLeft <= 0) {
                loginCode = null;
            } else {
                g.text(font, Component.literal(
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
                g.centeredText(font, Component.literal("§f§l" + loginCode),
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
                g.text(font, Component.literal(tt), px + 12, rowY,
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
                g.text(font, Component.literal("§c" + codeError),
                    px + 12, rowY, 0xFFFF6666, false);
                rowY += 12;
            }
        }

        // ── Legal + Discord links row ───────────────────────────────────────
        g.fill(px + 8, rowY + 2, px + CARD_W - 8, rowY + 3, 0xFF2A2A3A);
        rowY += 8;
        // 5 buttons (4 legal + Discord) with 6px gaps between → 4 gaps total.
        int linkW = (CARD_W - 24 - 24) / 5;
        for (int i = 0; i < LEGAL_LABELS.length; i++) {
            int lx = px + 12 + i * (linkW + 6);
            renderTextBtn(g, mx, my, lx, rowY, linkW, 14,
                "§b" + LEGAL_LABELS[i], 0xFF14202A, 0xFF66E5FF, K_LEGAL, i);
        }
        // Discord button — Discord brand purple, sits at the right end of
        // the legal row. Opens the public vanity invite directly so the URL
        // is short and game-friendly.
        int discX = px + 12 + LEGAL_LABELS.length * (linkW + 6);
        renderTextBtn(g, mx, my, discX, rowY, linkW, 14,
            "§9§lDiscord", 0xFF1A1A3A, 0xFF7289DA, K_DISCORD, 0);
        rowY += 18;

        // ── Version + update banner ─────────────────────────────────────────
        // Always-visible footer: shows the running version and (when an
        // update is detected) a clickable "Update available" pill.
        UpdateChecker.Result upd = UpdateChecker.latest();
        String releaseName = BuildInfo.releaseName();
        String verLine = "§8v" + BuildInfo.version()
            + (releaseName.isEmpty() ? "" : " §7- §f" + releaseName);
        g.text(font, Component.literal(verLine), px + 12, py + cardH - 22,
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

        super.extractRenderState(g, mx, my, delta);
    }

    private void renderTextBtn(GuiGraphicsExtractor g, int mx, int my,
                                int x, int y, int w, int h,
                                String label, int bg, int border,
                                int kind, int idx) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, hover ? 0xFFFFFFFF & border | 0xFF000000 : border);
        g.fill(x, y, x + w, y + h, bg);
        g.centeredText(font, Component.literal(label), x + w / 2, y + (h - 8) / 2, PVPTheme.TEXT);
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
                    case K_DISCORD -> openBrowser("https://discord.gg/revival-smp");
                    case K_REFRESH -> {
                        // Drop the cached `me` payload and refetch. Drives
                        // both the coin balance line and the earnings
                        // summary back to whatever the backend currently
                        // reports.
                        me = null;
                        meError = null;
                        loadMeIfNeeded();
                    }
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
