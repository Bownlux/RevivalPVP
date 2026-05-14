// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.revivalsmp.pvp.RevivalPVPMod;
import net.revivalsmp.pvp.client.render.PlayerLoadoutRenderer;
import net.revivalsmp.pvp.client.ui.PVPTheme;
import net.revivalsmp.pvp.client.RevivalPVPClient;
import net.revivalsmp.pvp.kit.Kit;
import net.revivalsmp.pvp.kit.KitLoadout;
import net.revivalsmp.pvp.kit.VariantItemFactory;
import net.revivalsmp.pvp.matchmaking.FriendsService;
import net.revivalsmp.pvp.matchmaking.MatchmakingService;
import net.revivalsmp.pvp.network.BackendHttpClient;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main RevivalPVP Hub Screen.
 *
 * Tabs: QUEUE | LEADERBOARD | HISTORY | FRIENDS | SETTINGS
 *
 * Dark custom theme. All colors from PVPTheme constants.
 * Queue tab: ranked toggle + horizontally scrollable kit list + 3D player loadout viewer.
 */
public class PVPHubScreen extends Screen {

    private static final int BG_COLOR      = 0xFF0A0A0F;
    private static final int PANEL_COLOR   = 0xFF12121A;
    private static final int BORDER_COLOR  = 0xFF00D4FF;
    private static final int TEXT_PRIMARY  = 0xFFE0E0FF;
    private static final int TEXT_MUTED    = 0xFF7070A0;

    // Rank tier palette, gothic/souls aesthetic. Used by the rank band above
    // the 3D viewer. Iron is rough/dull, Diamond is cold cyan, Master+ are
    // ceremonial purples/oranges/golds.
    private static final int RANK_IRON        = 0xFFB0A89A;
    private static final int RANK_BRONZE      = 0xFFB87333;
    private static final int RANK_SILVER      = 0xFFC8C8D0;
    private static final int RANK_GOLD        = 0xFFD4A85A;
    private static final int RANK_PLATINUM    = 0xFF7FE0C0;
    private static final int RANK_DIAMOND     = 0xFF66E0FF;
    private static final int RANK_MASTER      = 0xFFB37AE8;
    private static final int RANK_GRANDMASTER = 0xFFFF6A4D;
    private static final int RANK_CHALLENGER  = 0xFFFFE07A;
    private static final int RANK_PIP_EMPTY   = 0xFF1F1F2C;
    private static final int RANK_AMBER       = 0xFFE0A040;   // placement nag accent

    private enum Tab { QUEUE, LEADERBOARD, MATCHES, FRIENDS }
    /** Sub-tabs under MATCHES. LIVE shows in-progress duels; HISTORY shows finished ones. */
    private enum MatchesSub { LIVE, HISTORY }
    /** Filter for the HISTORY sub-tab, your own matches or every player's. */
    private enum HistoryScope { SELF, EVERYONE }

    // Queue tab layout constants.
    private static final int KIT_CARD_W = 120;
    private static final int KIT_CARD_H = 36;
    private static final int KIT_CARD_GAP = 6;
    // Right-side 3D viewer column (spans the full content height).
    private static final int VIEWER_W = 180;
    private static final int VIEWER_GAP = 12;

    private final MatchmakingService matchmaking;
    private Tab activeTab = Tab.QUEUE;
    private Kit selectedKit = Kit.SWORD;
    private boolean ranked = true;
    /** Match scope. "local" = same source server, "region" = same geo region, "global" = anyone. */
    private String scope = "global";
    private float pulseAnim = 0f;

    // Horizontal scroll for kit list (in pixels). Positive = scrolled right.
    private int kitScroll = 0;

    // 3D viewer.
    private final PlayerLoadoutRenderer loadoutRenderer = new PlayerLoadoutRenderer();
    // Last hovered loadout slot (computed during render, used by next render's tooltip pane).
    private PlayerLoadoutRenderer.HoveredItem hoveredItem;

    // Lazily-loaded tab data. null = not fetched yet, empty array = fetched empty.
    private JsonArray leaderboardEntries;
    private boolean   leaderboardLoading;
    /** Active leaderboard scope. "global" + 9 kit ids (uppercase) + "sponsors". */
    private String    leaderboardScope = "global";
    /** Cached scope key whose data is currently in leaderboardEntries; null = none yet. */
    private String    leaderboardScopeLoaded = null;
    /** Per-frame click rects for the scope filter pills (rebuilt every render). */
    private final java.util.List<int[]>    lbScopeRects   = new java.util.ArrayList<>();
    private final java.util.List<String>   lbScopeIds     = new java.util.ArrayList<>();
    /** Leaderboard body Y captured during render. Click handler reads this so
     *  hit-rects line up exactly with the rendered rows even when scope pills
     *  wrap to a second row at narrow widths. */
    private int lbBodyY = 0;
    private int lbMaxRows = 0;
    private static final int LB_ROW_H = 12;
    private JsonArray historyEntries;
    private boolean   historyLoading;
    /** Tracks which scope was last fetched, so flipping the toggle invalidates cache. */
    private HistoryScope historyScopeLoaded = null;
    private JsonArray liveDuels;
    private boolean   liveDuelsLoading;
    /** Last live-duels fetch timestamp; we refresh every 5s to keep the list current. */
    private long      liveDuelsFetchedAt;

    // Matches sub-tab state.
    private MatchesSub  matchesSub   = MatchesSub.LIVE;
    private HistoryScope historyScope = HistoryScope.SELF;

    // Per-frame hit rects + matched match-ids for the SPECTATE buttons in the
    // Live Duels list. Rebuilt every render; consumed in mouseClicked.
    private final java.util.List<int[]>  spectateRowRects = new java.util.ArrayList<>();
    private final java.util.List<String> spectateMatchIds = new java.util.ArrayList<>();

    // Per-kit rating data (from /rankings/player/{uuid}). null = not fetched yet.
    // Lazy-loaded; refreshed once per screen open.
    private JsonObject playerRatings;
    private boolean    playerRatingsLoading;

    // ── Loadout editor state (Phase 2) ───────────────────────────────────────
    /** Cached fetched loadouts per kit. Value is the raw JSON returned by GET /kits/loadouts/{kit}. */
    private final Map<Kit, JsonObject> loadoutByKit = new EnumMap<>(Kit.class);
    /** Per-kit loading flag so we don't issue duplicate GETs while one is in flight. */
    private final Map<Kit, Boolean> loadoutLoading = new EnumMap<>(Kit.class);
    /** Per-kit selected variant id (mutable: updated when the user picks something in the variant picker). */
    private final Map<Kit, Map<String, Integer>> selectedVariantId = new EnumMap<>(Kit.class);
    /**
     * Per-row hit rect for click handling in the loadout editor (recomputed every frame).
     * Slot string (e.g. "HEAD") → {x1, y1, x2, y2}.
     */
    private final Map<String, int[]> loadoutRowRects = new HashMap<>();
    /** Debounce: epoch-ms of the last in-memory selection change; we save 250ms after settle. */
    private long pendingSaveAt = 0L;
    /** Kit that has a pending dirty save. */
    private Kit pendingSaveKit;
    /** Vertical scroll offset (px) for the loadout slot rows. Reset on kit change. */
    private int loadoutScrollY = 0;
    /** Captured per-frame so mouseScrolled knows where to apply the scroll. */
    private int loadoutPanelX, loadoutPanelY, loadoutPanelW, loadoutPanelH;
    private int loadoutRowsTop, loadoutRowsBottom, loadoutRowsTotalH;

    // ── Friends tab state ─────────────────────────────────────────────────────
    /** Captured during render, consumed by mouseClicked. Each rect is
     *  {x1,y1,x2,y2,kind,index} where kind=0 invite-friend, 1=accept-req,
     *  2=reject-req, 3=unfriend, 4=add-friend-button, 5=expand-toggle. */
    private final List<int[]> friendsHitRects = new ArrayList<>();
    private boolean pendingExpanded = false;

    // ── Sponsor coin balance state ────────────────────────────────────────────
    /** -1 = not fetched yet. Updated lazily from /coins on hub open. */
    private int    coinBalance = -1;
    /** Tebex purchases queued but not yet redeemed for this player (typically
     *  bought before they ever authed). Surface a notice so they know to click
     *  through. */
    private int    pendingCoins = 0;
    private boolean coinBalanceLoading;
    /** Click-rect for the heart pill in the title bar; populated each frame. */
    private int[] coinPillRect;

    // ── Player detail sub-view (leaderboard click-through) ───────────────────
    /** When non-null, the LEADERBOARD tab renders the player detail panel
     *  for this leaderboard row instead of the list. */
    private JsonObject playerDetailEntry;
    private JsonObject playerDetailProfile;     // GET /sponsor/profile/{username}
    private boolean    playerDetailLoading;
    /** Captured per-frame for the back-button hit rect on the detail view. */
    private int[] detailBackRect;
    private int[] detailSponsor10Rect;
    private int[] detailSponsor100Rect;
    private int[] detailSponsor500Rect;
    /** Click-rect of the "buy more coins" hint line below the sponsor
     *  buttons. Null when the line isn't being rendered (player has at
     *  least 10 coins AND no insufficient-balance click is fresh). */
    private int[] detailBuyCoinsRect;
    /** Transient "you need X coins" message shown after the player clicks
     *  a tier they can't afford. Cleared when the timer expires. */
    private String lowBalanceMsg;
    private long   lowBalanceMsgUntilMs;
    /** Cached skin texture for the currently-displayed detail player.
     *  Reads from skinTextureCache (LRU below) on each render. */
    private net.minecraft.resources.ResourceLocation detailSkinTex;
    private String detailSkinUuid;
    /** Per-uuid skin texture cache, bounded LRU. The Mojang HTTP profile
     *  fetch is the expensive step (~200-500ms + a fair-use rate limit),
     *  so cache the resolved ResourceLocation across player clicks. Reopening
     *  any previously-viewed player is instant. Bound is generous: 128
     *  entries is ~kilobytes of references, but caps cumulative growth
     *  for long sessions on busy leaderboards. */
    private final java.util.Map<String, net.minecraft.resources.ResourceLocation> skinTextureCache =
        java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, net.minecraft.resources.ResourceLocation> e) {
                return size() > 128;
            }
        });
    /** Tracks uuids whose Mojang HTTP fetch is currently in flight, so a
     *  rapid sequence of clicks on the same row doesn't trigger N parallel
     *  fetches. Cleared when the fetch completes (success or failure). */
    private final java.util.Set<String> skinFetchInFlight =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** Vertical scroll offset (in rows) for the sponsor list inside the
     *  player detail panel. Reset to 0 whenever a new player is opened. */
    private int detailSponsorScroll = 0;
    /** Hit-rect of the sponsor list area, captured each frame so the mouse
     *  wheel handler knows when to scroll vs. let other handlers pick up. */
    private int[] detailSponsorListRect;
    /** Total number of sponsor rows we'd render if there were unlimited
     *  vertical room. Click handler clamps scroll against this. */
    private int detailSponsorTotal = 0;
    private int detailSponsorVisible = 0;

    // ── Invite overlay (renders on top of any tab) ────────────────────────────
    private int[] inviteAcceptRect;
    private int[] inviteDeclineRect;

    // ── Info icon hit rect (small (i) at title-bar right) ────────────────────
    private int[] infoIconRect;

    // ── Friends-tab transient state ──────────────────────────────────────────
    /** When user clicks the unfriend ✕, we don't unfriend immediately, first
     *  click flips the button to "Confirm?" and starts the timer below. The
     *  second click within {@link #UNFRIEND_CONFIRM_MS} actually unfriends. */
    private static final long UNFRIEND_CONFIRM_MS = 3000;
    private String unfriendArmedUuid;
    private long   unfriendArmedAt;
    /** Toast above the friends list, shows after invite/unfriend so the user
     *  sees the action took effect. Cleared after FRIENDS_TOAST_MS. */
    private static final long FRIENDS_TOAST_MS = 4000;
    private String friendsToast;
    private long   friendsToastAt;
    private int    friendsToastColor;
    /** Auto-refresh the friends list while the Friends tab is open so online
     *  state stays current (no presence push from backend yet). 10s cadence
     *  matches the server-mod's online-state heartbeat granularity. */
    private static final long FRIENDS_AUTO_REFRESH_MS = 10_000;
    private long friendsLastAutoRefreshAt;

    public PVPHubScreen(MatchmakingService matchmaking) {
        super(Component.literal("RevivalPVP"));
        this.matchmaking = matchmaking;
    }

    /** Force the queue tab (used when match_found arrives while the hub is
     *  already open on a different tab, without this the player can't see
     *  the map picker until they manually switch). */
    public void switchToQueueTab() {
        activeTab = Tab.QUEUE;
        if (playerDetailEntry != null) closePlayerDetail();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        pulseAnim += delta * 0.05f;

        int w = width, h = height;
        int panelW = Math.min(w - 40, 700);
        int panelH = Math.min(h - 40, 480);
        int px = (w - panelW) / 2;
        int py = (h - panelH) / 2;

        // Dark overlay behind panel
        g.fill(0, 0, w, h, 0xCC000000);

        // Main panel
        g.fill(px, py, px + panelW, py + panelH, PANEL_COLOR);
        // Cyan top border
        g.fill(px, py, px + panelW, py + 2, BORDER_COLOR);

        // Title
        g.drawString(font, "§b§lRevivalPVP", px + 12, py + 8, TEXT_PRIMARY, false);

        // Sponsor key balance pill (top-right of title row, above tab bar).
        renderKeyBalancePill(g, px, py, panelW, mx, my);

        // (i) icon, sits just left of the coin pill (or where the pill would
        // be if balance is 0). Click → InfoScreen.
        renderInfoIcon(g, px, py, panelW, mx, my);

        // Tab bar
        renderTabs(g, px, py + 24, panelW);

        // Content area
        int contentY = py + 44;
        switch (activeTab) {
            case QUEUE       -> renderQueueTab(g, mx, my, px, contentY, panelW, panelH - 44);
            case LEADERBOARD -> renderLeaderboardTab(g, mx, my, px, contentY, panelW, panelH - 44);
            case MATCHES     -> renderMatchesTab(g, mx, my, px, contentY, panelW, panelH - 44);
            case FRIENDS     -> renderFriendsTab(g, mx, my, px, contentY, panelW, panelH - 44);
        }

        // Incoming-duel-invite overlay sits on top of any tab.
        renderInviteOverlay(g, px, py, panelW, mx, my);

        super.render(g, mx, my, delta);
    }

    private void renderTabs(GuiGraphics g, int px, int ty, int panelW) {
        int tabW = panelW / Tab.values().length;
        for (int i = 0; i < Tab.values().length; i++) {
            Tab tab = Tab.values()[i];
            int tx = px + i * tabW;
            boolean active = tab == activeTab;
            g.fill(tx, ty, tx + tabW, ty + 18, active ? 0xFF1A1A2E : 0xFF0E0E18);
            if (active) g.fill(tx, ty + 16, tx + tabW, ty + 18, BORDER_COLOR);
            int labelColor = active ? BORDER_COLOR : TEXT_MUTED;
            String label = tab.name().substring(0, 1) + tab.name().substring(1).toLowerCase();
            g.drawCenteredString(font, Component.literal(label), tx + tabW / 2, ty + 5, labelColor);
        }
    }

    // ---------------- Queue tab ----------------

    private void renderQueueTab(GuiGraphics g, int mx, int my, int px, int y, int panelW, int panelH) {
        // Layout:
        //   right column = 3D viewer + hover label + queue/accept button (stacked)
        //   left column  = ranked/scope toggles + kit list + loadout editor (full height)
        int viewerX = px + panelW - VIEWER_W - 8;
        int viewerY = y + 8;
        // Reserve 36px at the bottom of the right column for the queue/accept button.
        int rightBtnH = 36;
        int viewerH = panelH - 16 - rightBtnH;

        // Left column extents, no button reserve here anymore, loadout fills.
        int leftAreaX = px + 8;
        int leftAreaW = viewerX - VIEWER_GAP - leftAreaX;

        // Row 1: Ranked/Unranked toggle (left), Scope picker (right of toggle).
        // Scope: Local = only same-server players, Region = same GeoIP region, Global = anyone.
        // Clamp the row to the panel: at narrow panels (high GUI scale or
        // small window) the centered offset would push "Ranked" off the left.
        int toggleX = Math.max(leftAreaX + 4, leftAreaX + leftAreaW / 2 - 165);
        renderToggleBtn(g, toggleX,        y + 8, "Ranked",   ranked,  mx, my);
        renderToggleBtn(g, toggleX + 82,   y + 8, "Unranked", !ranked, mx, my);
        // Scope toggle group (3 small buttons)
        int scopeX = toggleX + 82 + 84;
        renderScopeBtn(g, scopeX,        y + 8, "Local",  "local",  mx, my);
        renderScopeBtn(g, scopeX + 50,   y + 8, "Region", "region", mx, my);
        renderScopeBtn(g, scopeX + 100,  y + 8, "Global", "global", mx, my);

        // Row 2/3: when a match is found, the left column flips to a map picker
        // until the backend broadcasts the chosen map and we transition to
        // CONNECTING. Kit list + loadout editor are hidden during that window.
        if (matchmaking.state() == MatchmakingService.State.MATCH_FOUND) {
            int pickerY = y + 36;
            int pickerH = panelH - (pickerY - y) - 16;
            renderMapPickerPanel(g, mx, my, leftAreaX + 8, pickerY, leftAreaW - 16, pickerH);
        } else {
            // Row 2a: Placement progress pill, visible in IDLE + QUEUING.
            // Toggle buttons render at y+8 with height 18 (end at y+26), so
            // the pill needs to start at y+30+ to clear them.
            int placementY = y + 32;
            renderPlacementPill(g, leftAreaX + 8, placementY, leftAreaW - 16, 14);

            // Row 2b: Horizontal kit list, shift down by the same amount so
            // gap between pill and kit-list cards stays consistent.
            int kitListY = y + 60;
            int kitListX = leftAreaX + 24;
            int kitListW = leftAreaW - 48;
            int kitListH = KIT_CARD_H;
            renderKitList(g, mx, my, kitListX, kitListY, kitListW, kitListH);

            // Row 3: Loadout editor, fills remaining vertical space (queue button is
            // now in the right column, so left column has no bottom reserve).
            int descY = kitListY + kitListH + 12;
            int descH = panelH - (descY - y) - 16;
            if (descH < 60) descH = 60;
            renderLoadoutEditorPanel(g, mx, my, leftAreaX + 8, descY, leftAreaW - 16, descH);

            // Process any pending debounced save (250ms after last edit settles).
            flushPendingSaveIfDue();
        }

        // Right column: 3D viewer at full content height with hovered-item label below the figure
        renderPlayerViewerPanel(g, mx, my, viewerX, viewerY, VIEWER_W, viewerH);

        // Queue button / status, centered in right column below the 3D viewer
        // panel. Keeps it well clear of the loadout editor so the timer can't
        // visually overlap slot rows.
        MatchmakingService.State ms = matchmaking.state();
        int btnX = viewerX + (VIEWER_W - 110) / 2;
        int btnY = viewerY + viewerH + 7;

        if (ms == MatchmakingService.State.IDLE) {
            // Show last server error (e.g. "Complete placement matches before joining ranked")
            // for ~6s above the QUEUE UP button so the user knows why their click was rejected.
            String err = matchmaking.lastErrorMessage();
            if (err != null && System.currentTimeMillis() - matchmaking.lastErrorAt() < 6000) {
                g.drawCenteredString(font, Component.literal("§c" + err),
                    leftAreaX + leftAreaW / 2, btnY - 12, TEXT_MUTED);
            }
            renderBtn(g, btnX, btnY, 110, 22, "§a§l▶  QUEUE UP", 0xFF1A3A1A, 0xFF00CC44);
        } else if (ms == MatchmakingService.State.QUEUING) {
            long secs = matchmaking.queueElapsedMs() / 1000;
            String label = String.format("§e⏳  %02d:%02d", secs / 60, secs % 60);
            renderBtn(g, btnX, btnY, 110, 22, label, 0xFF3A3A0A, 0xFFCCCC00);
            g.drawCenteredString(font, Component.literal("§7[click to cancel · ESC keeps queue]"),
                leftAreaX + leftAreaW / 2, btnY - 12, TEXT_MUTED);
        } else if (ms == MatchmakingService.State.MATCH_FOUND) {
            var m = matchmaking.activeMatch();
            g.drawCenteredString(font, Component.literal("§b⚔  Match found! vs §f" + m.opponentName()),
                leftAreaX + leftAreaW / 2, btnY - 12, TEXT_PRIMARY);
            // The map picker on the left drives the transition to CONNECTING via
            // map_selected. Until the backend locks a map in, the right-column
            // button is a soft "skip", clicking accepts immediately and lets
            // the duel plugin pick a random arena.
            String chosen = matchmaking.chosenMap();
            if (chosen != null) {
                renderBtn(g, btnX, btnY, 110, 22, "§a§l▶  GO " + chosen.toUpperCase(),
                    0xFF1A3A1A, 0xFF00CC44);
            } else {
                long elapsedMs = matchmaking.matchFoundElapsedMs();
                int  voteSecs  = Math.max(matchmaking.mapVoteSeconds(), 6);
                int  remaining = (int) Math.max(0, voteSecs - elapsedMs / 1000);
                renderBtn(g, btnX, btnY, 110, 22, "§7§o" + remaining + "s · skip",
                    0xFF1F1F2A, 0xFF666688);
            }
        } else if (ms == MatchmakingService.State.CONNECTING) {
            // User just clicked ACCEPT, show immediate feedback so they don't
            // double-click while the transfer is being initiated (~1-2s window).
            float pulse = 0.6f + 0.4f * (float) Math.sin(pulseAnim * 5);
            int alphaColor = ((int)(pulse * 255) << 24) | 0xCCCC00;
            renderBtn(g, btnX, btnY, 110, 22, "§e§l⏳ CONNECTING...", 0xFF2A2A0A, alphaColor);
            g.drawCenteredString(font, Component.literal("§7Transferring you to the duel server"),
                leftAreaX + leftAreaW / 2, btnY - 12, TEXT_MUTED);
        } else if (ms == MatchmakingService.State.IN_DUEL) {
            // Should auto-close, but if hub is reopened mid-duel show clear state.
            renderBtn(g, btnX, btnY, 110, 22, "§a§l⚔ IN DUEL", 0xFF1A3A1A, 0xFF00CC44);
        }
    }

    /** Horizontally scrolling kit list with [icon] Name cards and left/right arrow buttons. */
    private void renderKitList(GuiGraphics g, int mx, int my, int x, int y, int w, int h) {
        Kit[] kits = Kit.values();
        int totalContentW = kits.length * KIT_CARD_W + (kits.length - 1) * KIT_CARD_GAP;
        int maxScroll = Math.max(0, totalContentW - w);
        if (kitScroll < 0) kitScroll = 0;
        if (kitScroll > maxScroll) kitScroll = maxScroll;

        // Clip to the list area so cards don't bleed past the arrows.
        g.enableScissor(x, y, x + w, y + h);
        for (int i = 0; i < kits.length; i++) {
            int cx = x + i * (KIT_CARD_W + KIT_CARD_GAP) - kitScroll;
            int cy = y;
            // Skip cards fully off-screen.
            if (cx + KIT_CARD_W < x || cx > x + w) continue;
            renderKitCard(g, kits[i], cx, cy, mx, my);
        }
        g.disableScissor();

        // Left/right arrows (outside the clip area)
        boolean canLeft  = kitScroll > 0;
        boolean canRight = kitScroll < maxScroll;
        renderArrow(g, x - 18, y + h / 2 - 8, "◄", canLeft);
        renderArrow(g, x + w + 2, y + h / 2 - 8, "►", canRight);
    }

    private void renderKitCard(GuiGraphics g, Kit kit, int x, int y, int mx, int my) {
        boolean sel = kit == selectedKit;
        boolean hover = mx >= x && mx < x + KIT_CARD_W && my >= y && my < y + KIT_CARD_H;
        int border = sel ? BORDER_COLOR : (hover ? 0xFF4A4A6A : 0xFF2A2A3A);
        int fill   = sel ? 0xFF1C2C3A : (hover ? 0xFF181828 : 0xFF141420);

        g.fill(x - 1, y - 1, x + KIT_CARD_W + 1, y + KIT_CARD_H + 1, border);
        g.fill(x, y, x + KIT_CARD_W, y + KIT_CARD_H, fill);

        // Icon at left (16x16). On the title screen (no world joined), the
        // item registry isn't bound and `new ItemStack` throws "Components
        // not bound yet". Fail safe: render a colored placeholder square.
        int iconX = x + 6;
        int iconY = y + (KIT_CARD_H - 16) / 2;
        ItemStack iconStack = safeItem(kit.icon);
        if (iconStack != null) {
            g.renderItem(iconStack, iconX, iconY);
        } else {
            g.fill(iconX, iconY, iconX + 16, iconY + 16, sel ? BORDER_COLOR : 0xFF333344);
        }

        // Kit name to the right of the icon
        int textX = iconX + 20;
        int textY = y + (KIT_CARD_H - 8) / 2;
        int textColor = sel ? BORDER_COLOR : TEXT_PRIMARY;
        g.drawString(font, Component.literal(kit.display), textX, textY, textColor, false);
    }

    private void renderArrow(GuiGraphics g, int x, int y, String label, boolean enabled) {
        int color = enabled ? BORDER_COLOR : 0xFF333344;
        g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF1A1A2A);
        g.drawCenteredString(font, Component.literal(label), x + 8, y + 4, color);
    }

    /** Slot-display order. Only slots actually present in the kit's by_slot are rendered. */
    private static final String[] SLOT_ORDER = {
        "HEAD", "CHEST", "LEGS", "FEET", "MAINHAND", "OFFHAND",
        "HOTBAR1", "HOTBAR2", "HOTBAR3", "HOTBAR4"
    };

    /** Short label per slot for the row prefix. */
    private static String slotLabel(String slot) {
        return switch (slot) {
            case "MAINHAND" -> "HAND";
            case "OFFHAND"  -> "OFF";
            case "HOTBAR1", "HOTBAR2", "HOTBAR3", "HOTBAR4" -> "HOTBAR";
            default         -> slot;
        };
    }

    // ── Map picker (rendered in MATCH_FOUND state) ───────────────────────────

    // Hit regions captured during render and consulted in mouseClicked.
    private int mapPickerX, mapPickerY, mapPickerW, mapPickerH, mapCardH;
    private int mapCardCount;
    /** Vertical scroll offset for the map list, auto-clamped to [0,maxScroll]
     *  during render. Scrollwheel + click hit-tests both consult this. */
    private int mapPickerScroll = 0;

    /**
     * Map picker shown after match_found and before the duel server transfer.
     * One full-width card per available map. Click → vote. Selected vote
     * highlights cyan. After both players vote (or timeout), backend broadcasts
     * map_selected and the mod auto-transitions to CONNECTING.
     */
    private static final net.minecraft.resources.ResourceLocation MATCH_BANNER_TEX =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("revival-pvp", "textures/gui/match_banner.png");

    private void renderMapPickerPanel(GuiGraphics g, int mx, int my, int x, int y, int w, int h) {
        // Panel border
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF2A2A3A);
        // Base fill (kept as fallback if texture render fails)
        g.fill(x, y, x + w, y + h, 0xFF141420);

        // Stretch the duel banner across the panel as atmospheric backdrop,
        // then layer a light translucent overlay so text + map cards stay
        // legible without crushing the artwork. Wrapped in try-catch, the
        // blit signature requires a RenderPipeline first arg in MC 26.1+;
        // we fall back gracefully if anything throws.
        try {
            int texW = 1024, texH = 318;   // compressed banner dimensions
            // 12-arg blit: (pipeline, tex, x, y, u, v, renderW, renderH,
            //              regionW, regionH, textureW, textureH).
            // Setting regionW/H == textureW/H makes the full image stretch to
            // fit (renderW x renderH); the previous 10-arg variant cropped
            // instead of scaling.
            g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                MATCH_BANNER_TEX, x, y, 0f, 0f, w, h, texW, texH, texW, texH);
            // Light overlay (~40% opacity) so artwork is clearly visible
            // while text remains readable.
            g.fill(x, y, x + w, y + h, 0x66000000);
        } catch (Throwable t) {
            net.revivalsmp.pvp.RevivalPVPMod.LOGGER.warn("Match banner blit failed: {}", t.toString());
        }

        var maps = matchmaking.availableMaps();
        mapPickerX = x; mapPickerY = y; mapPickerW = w; mapPickerH = h;
        mapCardCount = maps.size();

        // Header
        var match = matchmaking.activeMatch();
        String header = "§b§lMAP VOTE";
        g.drawCenteredString(font, Component.literal(header), x + w / 2, y + 8, BORDER_COLOR);

        // Sub-header: opponent + countdown
        long elapsedMs = matchmaking.matchFoundElapsedMs();
        int  voteSecs  = Math.max(matchmaking.mapVoteSeconds(), 6);
        int  remaining = (int) Math.max(0, voteSecs - elapsedMs / 1000);
        String sub = "§7vs §f" + (match != null ? match.opponentName() : "?")
            + "   §8|   §7picks lock in §f" + remaining + "s";
        g.drawCenteredString(font, Component.literal(sub), x + w / 2, y + 22, TEXT_MUTED);

        if (maps.isEmpty()) {
            g.drawCenteredString(font, Component.literal("§7Loading maps..."), x + w / 2, y + h / 2, TEXT_MUTED);
            mapCardH = 0;
            return;
        }

        // Card layout, FIXED height per card. Previously the card height
        // shrank with the map count, leaving cards looking 'floating' with
        // big gaps when only 2 maps were available, and unreadable squashed
        // strips with 6+. Fixed height + scroll = clean at any count.
        int topPad = 40;
        int gap    = 6;
        int cardH  = 56;
        mapCardH   = cardH;

        // Scrollable visible area below the header.
        int areaTop = y + topPad;
        int areaBot = y + h - 8;
        int areaH   = areaBot - areaTop;
        int contentH = maps.size() * cardH + (maps.size() - 1) * gap;
        int maxScroll = Math.max(0, contentH - areaH);
        if (mapPickerScroll < 0)         mapPickerScroll = 0;
        if (mapPickerScroll > maxScroll) mapPickerScroll = maxScroll;

        // Clip to the scroll area so cards don't bleed past header / bottom edge.
        g.enableScissor(x, areaTop, x + w, areaBot);

        String myVote = matchmaking.myVote();
        String chosen = matchmaking.chosenMap();
        for (int i = 0; i < maps.size(); i++) {
            var m  = maps.get(i);
            int cx = x + 8;
            int cy = areaTop + i * (cardH + gap) - mapPickerScroll;
            int cw = w - 16 - (maxScroll > 0 ? 6 : 0);   // leave space for scrollbar

            // Skip cards entirely off-screen (saves a tiny bit of work).
            if (cy + cardH < areaTop || cy > areaBot) continue;

            boolean voted = myVote != null && myVote.equalsIgnoreCase(m.id());
            boolean locked = chosen != null && chosen.equalsIgnoreCase(m.id());
            boolean hover = mx >= cx && mx < cx + cw && my >= cy && my < cy + cardH
                         && my >= areaTop && my < areaBot;

            int border = locked ? 0xFF66FF99 : (voted ? BORDER_COLOR : (hover ? 0xFF4A4A6A : 0xFF2A2A3A));
            int fill   = locked ? 0xFF1F3A24 : (voted ? 0xFF1C2C3A : (hover ? 0xFF181828 : 0xFF141420));

            g.fill(cx - 1, cy - 1, cx + cw + 1, cy + cardH + 1, border);
            g.fill(cx, cy, cx + cw, cy + cardH, fill);

            // Title
            g.drawString(font, Component.literal("§f§l" + m.displayName()), cx + 12, cy + 10, TEXT_PRIMARY, false);
            // Description
            g.drawString(font, Component.literal("§7" + m.description()), cx + 12, cy + 24, TEXT_MUTED, false);

            // Status pill (right side)
            String pill;
            int pillColor;
            if (locked) {
                pill = "§a§lSELECTED";
                pillColor = 0xFF66FF99;
            } else if (voted) {
                pill = "§b§lYOUR VOTE";
                pillColor = BORDER_COLOR;
            } else {
                pill = "§7Click to vote";
                pillColor = TEXT_MUTED;
            }
            int pillW = font.width(pill.replaceAll("§.", "")) + 12;
            g.drawString(font, Component.literal(pill), cx + cw - pillW, cy + cardH - 14, pillColor, false);
        }
        g.disableScissor();

        // Scrollbar, only when content overflows.
        if (maxScroll > 0) {
            int trackX = x + w - 5;
            int trackY = areaTop;
            int trackH = areaH;
            g.fill(trackX, trackY, trackX + 3, trackY + trackH, 0xFF1A1A2A);
            int thumbH = Math.max(20, (int) ((long) trackH * areaH / contentH));
            int thumbY = trackY + (int) ((long) (trackH - thumbH) * mapPickerScroll / maxScroll);
            g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, BORDER_COLOR);
        }
    }

    /**
     * Loadout Editor panel: kit name + compact description on top, then one row per
     * slot present in the current kit's by_slot, each row a clickable button that opens
     * the variant picker.
     */
    private void renderLoadoutEditorPanel(GuiGraphics g, int mx, int my, int x, int y, int w, int h) {
        // Panel background
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF2A2A3A);
        g.fill(x, y, x + w, y + h, 0xFF141420);

        // Capture panel rect for the scroll handler.
        loadoutPanelX = x; loadoutPanelY = y; loadoutPanelW = w; loadoutPanelH = h;

        // Trigger lazy fetch of the loadout for the current kit.
        loadLoadoutIfNeeded(selectedKit);

        int padding = 8;
        int innerX = x + padding;
        int innerY = y + padding;
        int innerW = w - padding * 2;

        // Header: kit name
        g.drawString(font, Component.literal("§b§l" + selectedKit.display), innerX, innerY, TEXT_PRIMARY, false);

        // Compact description (1 line, truncated/wrapped to fit panel width).
        int lineY = innerY + 12;
        int lineHeight = 10;
        var descLines = font.split(Component.literal("§7" + selectedKit.description), innerW);
        if (!descLines.isEmpty()) {
            g.drawString(font, descLines.get(0), innerX, lineY, TEXT_MUTED);
            lineY += lineHeight;
        }

        // Divider
        int dividerY = lineY + 2;
        g.fill(innerX, dividerY, innerX + innerW, dividerY + 1, 0xFF2A2A3A);

        // Slot rows region.
        int rowsTop    = dividerY + 6;
        int rowsBottom = y + h - padding;
        int rowsAvail  = rowsBottom - rowsTop;
        int rowHeight  = 16;
        int rowGap     = 2;

        loadoutRowsTop = rowsTop;
        loadoutRowsBottom = rowsBottom;
        loadoutRowRects.clear();

        JsonObject loadout = loadoutByKit.get(selectedKit);
        if (loadout == null) {
            String msg = Boolean.TRUE.equals(loadoutLoading.get(selectedKit))
                ? "§7Loading loadout..."
                : "§8(Loadout unavailable. Defaults will be used.)";
            g.drawCenteredString(font, Component.literal(msg), innerX + innerW / 2, rowsTop + rowsAvail / 2 - 4, TEXT_MUTED);
            loadoutRowsTotalH = 0;
            return;
        }

        JsonObject bySlot = loadout.has("by_slot") ? loadout.getAsJsonObject("by_slot") : null;
        if (bySlot == null || bySlot.size() == 0) {
            g.drawCenteredString(font, Component.literal("§7No customizable slots for this kit yet."),
                innerX + innerW / 2, rowsTop + rowsAvail / 2 - 4, TEXT_MUTED);
            loadoutRowsTotalH = 0;
            return;
        }

        // Build the list of slots actually present in this kit (in canonical order).
        List<String> slots = new ArrayList<>();
        for (String s : SLOT_ORDER) if (bySlot.has(s)) slots.add(s);

        int totalH    = slots.size() * (rowHeight + rowGap);
        int maxScroll = Math.max(0, totalH - rowsAvail);
        if (loadoutScrollY < 0) loadoutScrollY = 0;
        if (loadoutScrollY > maxScroll) loadoutScrollY = maxScroll;
        loadoutRowsTotalH = totalH;

        // Reserve right gutter for the scrollbar when content overflows.
        boolean hasScroll = totalH > rowsAvail;
        int rowsW = innerW - (hasScroll ? 6 : 0);

        // Render rows with scroll offset; scissor clips to the rows area.
        g.enableScissor(innerX, rowsTop, innerX + rowsW, rowsBottom);
        int baseY = rowsTop - loadoutScrollY;
        for (int i = 0; i < slots.size(); i++) {
            int ry = baseY + i * (rowHeight + rowGap);
            // Skip rows that are fully outside the visible band, saves text+icon work.
            if (ry + rowHeight < rowsTop || ry > rowsBottom) continue;
            JsonObject selectedVariant = resolveSelectedVariant(loadout, slots.get(i));
            renderLoadoutRow(g, mx, my, slots.get(i), selectedVariant, innerX, ry, rowsW, rowHeight);
        }
        g.disableScissor();

        // Scrollbar (right gutter).
        if (hasScroll) {
            int barX = innerX + rowsW + 1;
            int barW = 3;
            int trackTop = rowsTop;
            int trackBot = rowsBottom;
            int trackH = trackBot - trackTop;
            int thumbH = Math.max(16, (int) ((float) trackH * rowsAvail / totalH));
            int thumbY = trackTop + (maxScroll == 0 ? 0 : (int) ((float) loadoutScrollY * (trackH - thumbH) / maxScroll));
            g.fill(barX, trackTop, barX + barW, trackBot, 0xFF1A1A2A);
            g.fill(barX, thumbY,  barX + barW, thumbY + thumbH, 0xFF4A4A6A);
        }
    }

    /**
     * Find the current variant chosen for {@code slot} in {@code loadout}, applying our
     * in-memory override map first, then falling back to the server's "selected" map.
     */
    private JsonObject resolveSelectedVariant(JsonObject loadout, String slot) {
        Map<String, Integer> overrides = selectedVariantId.get(selectedKit);
        Integer overrideId = overrides == null ? null : overrides.get(slot);
        if (overrideId != null) {
            // Locate that id in by_slot.
            JsonArray arr = loadout.getAsJsonObject("by_slot").getAsJsonArray(slot);
            for (JsonElement el : arr) {
                JsonObject v = el.getAsJsonObject();
                if (v.has("id") && v.get("id").getAsInt() == overrideId) return v;
            }
        }
        if (loadout.has("selected") && loadout.getAsJsonObject("selected").has(slot)) {
            JsonElement el = loadout.getAsJsonObject("selected").get(slot);
            if (el != null && el.isJsonObject()) return el.getAsJsonObject();
        }
        // Last fallback: first variant in by_slot.
        JsonArray arr = loadout.getAsJsonObject("by_slot").getAsJsonArray(slot);
        if (arr != null && !arr.isEmpty()) return arr.get(0).getAsJsonObject();
        return null;
    }

    private void renderLoadoutRow(GuiGraphics g, int mx, int my,
                                  String slot, JsonObject variant,
                                  int x, int y, int w, int h) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        int border = hover ? 0xFF4A4A6A : 0xFF1F1F2C;
        int bg     = hover ? 0xFF181828 : 0xFF101019;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, border);
        g.fill(x, y, x + w, y + h, bg);

        // Save row hit rect for click handling.
        loadoutRowRects.put(slot, new int[]{ x, y, x + w, y + h });

        // Icon at left
        int iconX = x + 4;
        int iconY = y + (h - 16) / 2;
        ItemStack icon = ItemStack.EMPTY;
        String name = "(none)";
        if (variant != null) {
            JsonObject item = variant.has("item") && variant.get("item").isJsonObject()
                ? variant.getAsJsonObject("item") : null;
            icon = VariantItemFactory.fromJson(item);
            if (variant.has("name") && !variant.get("name").isJsonNull()) {
                name = variant.get("name").getAsString();
            }
        }
        if (icon != null && !icon.isEmpty()) {
            g.renderItem(icon, iconX, iconY);
        } else {
            g.fill(iconX, iconY, iconX + 16, iconY + 16, 0xFF1F1F2C);
        }

        // Slot label (slate-gray, fixed-width-ish)
        int slotLabelX = iconX + 22;
        int slotLabelW = 50;
        int textY = y + (h - 8) / 2;
        g.drawString(font, Component.literal("§7" + slotLabel(slot)), slotLabelX, textY, TEXT_MUTED, false);

        // Variant name (white), clipped to remaining width
        int nameX = slotLabelX + slotLabelW;
        int arrowX = x + w - 12;
        int nameMaxW = arrowX - nameX - 4;
        String cleanName = stripLeadingLegacyColors(name);
        var nameLines = font.split(Component.literal("§f" + cleanName), nameMaxW);
        if (!nameLines.isEmpty()) {
            g.drawString(font, nameLines.get(0), nameX, textY, TEXT_PRIMARY);
        }

        // Right arrow indicator (clickable)
        int arrowColor = hover ? BORDER_COLOR : TEXT_MUTED;
        g.drawString(font, Component.literal("§b▶"), arrowX, textY, arrowColor, false);
    }

    private static String stripLeadingLegacyColors(String s) {
        if (s == null) return "";
        int i = 0;
        while (i + 1 < s.length() && s.charAt(i) == '§') i += 2;
        return s.substring(i);
    }

    /** Right content panel: 3D player figure + hovered-item label below it. */
    private void renderPlayerViewerPanel(GuiGraphics g, int mx, int my, int x, int y, int w, int h) {
        // Panel background
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF2A2A3A);
        g.fill(x, y, x + w, y + h, 0xFF0E0E18);

        // Top band: rank/placement info for the selected kit (38px tall).
        // Eats into the 3D viewer height so the figure stays unobstructed.
        int rankBandH = 38;
        renderRankBand(g, x, y, w, rankBandH);

        // 3D viewer + hovered-item label split the remaining vertical space.
        int remaining = h - rankBandH;
        int viewerH = (int) (remaining * 0.70f);
        int labelH  = remaining - viewerH;
        int viewerY = y + rankBandH;

        // The 3D viewer needs a loaded world (registries bound + entity
        // model). On the title screen mc.level is null and the renderer
        // throws downstream. Detect proactively and render a clear
        // explainer instead of the cryptic exception fallback.
        boolean noLevel = net.minecraft.client.Minecraft.getInstance().level == null;
        if (noLevel) {
            int cx = x + w / 2;
            int cy = viewerY + viewerH / 2 - 18;
            g.drawCenteredString(font, Component.literal("§f§lJoin a game"), cx, cy, TEXT_PRIMARY);
            g.drawCenteredString(font, Component.literal("§7to see your"), cx, cy + 14, TEXT_MUTED);
            g.drawCenteredString(font, Component.literal("§73D character"), cx, cy + 24, TEXT_MUTED);
            hoveredItem = null;
        } else {
            // Belt-and-suspenders try/catch: even with a level, the
            // renderer can throw on first paint while registries finish
            // binding. Fall back to the same explainer.
            try {
                loadoutRenderer.render(g, selectedKit, x, viewerY, w, viewerH, mx, my);
                hoveredItem = loadoutRenderer.getHoveredItem(mx, my);
            } catch (Throwable t) {
                int cx = x + w / 2;
                int cy = viewerY + viewerH / 2 - 6;
                g.drawCenteredString(font, Component.literal("§7Viewer warming up..."),
                    cx, cy, TEXT_MUTED);
                hoveredItem = null;
            }
        }

        // Divider between the 3D viewer and the hovered-item label area.
        int dividerY = viewerY + viewerH - 1;
        g.fill(x + 6, dividerY, x + w - 6, dividerY + 1, 0xFF2A2A3A);

        // Hovered-item label area
        int labelX = x + 8;
        int labelY = viewerY + viewerH + 6;
        int labelW = w - 16;
        if (hoveredItem != null) {
            // Prefer the SELECTED variant's name + description (Phase 2 hover spec) over the
            // KitLoadout default that the renderer's resolveHover() returns.
            String displayName = hoveredItem.displayName();
            String description = hoveredItem.description();
            JsonObject loadout = loadoutByKit.get(selectedKit);
            if (loadout != null) {
                JsonObject variant = resolveSelectedVariant(loadout, hoveredItem.slot().name());
                if (variant != null) {
                    if (variant.has("name") && !variant.get("name").isJsonNull()) {
                        displayName = stripLeadingLegacyColors(variant.get("name").getAsString());
                    }
                    if (variant.has("description") && !variant.get("description").isJsonNull()) {
                        description = variant.get("description").getAsString();
                    }
                }
            }
            g.drawString(font, Component.literal("§b" + displayName), labelX, labelY, TEXT_PRIMARY, false);
            int dy = labelY + 12;
            int lineHeight = 10;
            int maxLines = (labelH - 18) / lineHeight;
            int drawn = 0;
            for (var line : font.split(Component.literal(description == null ? "" : description), labelW)) {
                if (drawn >= maxLines) break;
                g.drawString(font, line, labelX, dy, TEXT_MUTED);
                dy += lineHeight;
                drawn++;
            }
        } else {
            g.drawCenteredString(font, Component.literal("§8Hover the item"), x + w / 2, labelY + 4, TEXT_MUTED);
            g.drawCenteredString(font, Component.literal("§8in the figure"), x + w / 2, labelY + 14, TEXT_MUTED);
        }
    }

    // ---------------- Other tabs (unchanged) ----------------

    /** Scope ids in render order. Stays in sync with the website leaderboard
     *  pills on /revival-pvp. Edit both when adding/removing a kit. */
    private static final String[] LB_SCOPE_IDS = {
        "global", "FIST", "SWORD", "ARCHER", "CROSSBOW", "MACE",
        "SPEAR", "TRIDENT", "TNT", "CRYSTAL", "sponsors",
    };

    private static String lbScopeLabel(String id) {
        if ("global".equals(id))   return "GLOBAL";
        if ("sponsors".equals(id)) return "SPONSORS";
        return id;
    }

    private void renderLeaderboardTab(GuiGraphics g, int mx, int my, int px, int y, int panelW, int panelH) {
        // Sub-view: clicked-through to a player detail. Render that instead.
        if (playerDetailEntry != null) {
            renderPlayerDetailView(g, mx, my, px, y, panelW, panelH);
            return;
        }

        // ── Scope filter pills (top of panel) ──────────────────────────────
        // Auto-wrap to next line if pills overflow available panel width.
        lbScopeRects.clear();
        lbScopeIds.clear();
        int scopeY     = y + 6;
        int pillX      = px + 12;
        int pillStartX = pillX;
        int pillH      = 14;
        int pillGap    = 4;
        int rightLim   = px + panelW - 12;
        int rowsUsed   = 1;
        for (String id : LB_SCOPE_IDS) {
            String label = lbScopeLabel(id);
            int w = font.width(label) + 12;
            if (pillX + w > rightLim) {
                pillX = pillStartX;
                scopeY += pillH + 3;
                rowsUsed++;
            }
            boolean active = id.equals(leaderboardScope);
            int bg = active ? 0xFF00D4FF : 0x88101828;
            int fg = active ? 0xFF0A0A0F : 0xFF8888AA;
            if ("sponsors".equals(id) && !active) fg = 0xFFFF6BA8;
            g.fill(pillX, scopeY, pillX + w, scopeY + pillH, bg);
            g.drawString(font, "§r" + label, pillX + 6, scopeY + 3, fg, false);
            lbScopeRects.add(new int[]{ pillX, scopeY, pillX + w, scopeY + pillH });
            lbScopeIds.add(id);
            pillX += w + pillGap;
        }

        // Body shifts down by however many pill rows we used.
        int bodyY = y + 6 + rowsUsed * (pillH + 3) + 4;

        loadLeaderboardIfNeeded();
        if (leaderboardEntries == null) {
            String msg = leaderboardLoading ? "§7Loading leaderboard..." : "§cFailed to load leaderboard";
            g.drawCenteredString(font, Component.literal(msg), px + panelW / 2,
                bodyY + ((y + panelH) - bodyY) / 2, TEXT_MUTED);
            return;
        }
        if (leaderboardEntries.isEmpty()) {
            String empty = "sponsors".equals(leaderboardScope)
                ? "§7No coins spent this season yet."
                : "§7No ranked players in this kit yet.";
            g.drawCenteredString(font, Component.literal(empty), px + panelW / 2,
                bodyY + ((y + panelH) - bodyY) / 2, TEXT_MUTED);
            return;
        }

        // ── Header row (column titles differ for sponsors mode) ────────────
        int rowX = px + 16, rowY = bodyY;
        boolean sponsorMode = "sponsors".equals(leaderboardScope);
        if (sponsorMode) {
            g.drawString(font, "§7#",       rowX,        rowY, TEXT_MUTED, false);
            g.drawString(font, "§7Player",  rowX + 28,   rowY, TEXT_MUTED, false);
            g.drawString(font, "§7Coins",   rowX + 200,  rowY, TEXT_MUTED, false);
        } else {
            g.drawString(font, "§7#",       rowX,        rowY, TEXT_MUTED, false);
            g.drawString(font, "§7Player",  rowX + 28,   rowY, TEXT_MUTED, false);
            g.drawString(font, "§7Rank",    rowX + 200,  rowY, TEXT_MUTED, false);
            g.drawString(font, "§7LP",      rowX + 320,  rowY, TEXT_MUTED, false);
            g.drawString(font, "§7W/L",     rowX + 380,  rowY, TEXT_MUTED, false);
            g.drawString(font, "§7Win%",    rowX + 440,  rowY, TEXT_MUTED, false);
        }

        int rowH = LB_ROW_H;
        int maxRows = ((y + panelH) - (rowY + 14)) / rowH;
        int n = Math.min(leaderboardEntries.size(), maxRows);
        // Snapshot the row geometry for the click handler, keeps hit-rects in
        // exact lockstep with what we just drew, even when pills wrap.
        lbBodyY   = rowY;
        lbMaxRows = maxRows;
        for (int i = 0; i < n; i++) {
            JsonObject e = leaderboardEntries.get(i).getAsJsonObject();
            int ry = rowY + 14 + i * rowH;
            if (sponsorMode) {
                g.drawString(font, "§f" + e.get("rank").getAsString(),     rowX,        ry, TEXT_PRIMARY, false);
                g.drawString(font, "§f" + e.get("username").getAsString(), rowX + 28,   ry, TEXT_PRIMARY, false);
                g.drawString(font, "§d♥ " + e.get("coins").getAsString(),  rowX + 200,  ry, TEXT_PRIMARY, false);
            } else {
                g.drawString(font, "§f" + e.get("rank_position").getAsString(),  rowX,        ry, TEXT_PRIMARY, false);
                g.drawString(font, "§f" + e.get("username").getAsString(),       rowX + 28,   ry, TEXT_PRIMARY, false);
                g.drawString(font, "§b" + e.get("rank").getAsString() + " " + e.get("division").getAsString(),
                                                                            rowX + 200,  ry, TEXT_PRIMARY, false);
                g.drawString(font, "§e" + e.get("lp").getAsString(),              rowX + 320,  ry, TEXT_PRIMARY, false);
                g.drawString(font, e.get("wins").getAsString() + "/" + e.get("games").getAsString(),
                                                                            rowX + 380,  ry, TEXT_PRIMARY, false);
                g.drawString(font, e.get("win_rate").getAsString() + "%",         rowX + 440,  ry, TEXT_PRIMARY, false);
            }
        }
    }

    // ── Matches tab: Live + History ──────────────────────────────────────────

    private void renderMatchesTab(GuiGraphics g, int mx, int my, int px, int y, int panelW, int panelH) {
        // Sub-tab header, two pill buttons LIVE | HISTORY
        int subY = y + 8;
        renderSubTab(g, px + 16, subY, "Live Duels", matchesSub == MatchesSub.LIVE, mx, my);
        renderSubTab(g, px + 16 + 92, subY, "History", matchesSub == MatchesSub.HISTORY, mx, my);

        // History sub-tab also shows a Self/Everyone toggle on the right.
        if (matchesSub == MatchesSub.HISTORY) {
            renderSubTab(g, px + panelW - 16 - 80 - 4 - 70, subY, "Self",
                historyScope == HistoryScope.SELF, mx, my);
            renderSubTab(g, px + panelW - 16 - 80, subY, "Everyone",
                historyScope == HistoryScope.EVERYONE, mx, my);
        }

        // Capture the sub-tab band rect for click handling.
        matchesSubBandY = subY;

        int contentTop = subY + 24;
        int contentH   = panelH - (contentTop - y) - 8;

        if (matchesSub == MatchesSub.LIVE) {
            renderLiveDuels(g, mx, my, px, contentTop, panelW, contentH);
        } else {
            renderHistory(g, px, contentTop, panelW, contentH);
        }
    }

    /** Top-of-tab pill button used for Live/History + Self/Everyone toggles. */
    private void renderSubTab(GuiGraphics g, int x, int y, String label,
                              boolean active, int mx, int my) {
        int w = label.equals("Live Duels") ? 88 : (label.equals("History") ? 60 : 70);
        int bg     = active ? 0xFF1A2A3A : 0xFF0E0E18;
        int border = active ? BORDER_COLOR : 0xFF2A2A3A;
        g.fill(x - 1, y - 1, x + w + 1, y + 19, border);
        g.fill(x, y, x + w, y + 18, bg);
        g.drawCenteredString(font, Component.literal(label), x + w / 2, y + 5,
            active ? BORDER_COLOR : TEXT_MUTED);
    }
    /** Captured during render; consumed by mouseClicked for sub-tab clicks. */
    private int matchesSubBandY;

    /** Refresh-button hit rect (Live Duels). Set each render in renderLiveDuels. */
    private int liveRefreshX, liveRefreshY, liveRefreshW, liveRefreshH;

    private void renderLiveDuels(GuiGraphics g, int mx, int my, int px, int y, int panelW, int panelH) {
        // Refresh button, top-right of the Live Duels area. CD is implicit
        // (the cache TTL on liveDuelsFetchedAt). Click forces an immediate
        // refetch instead of waiting for the 5s auto-refresh.
        int btnW = 70, btnH = 14;
        int btnX = px + panelW - 16 - btnW;
        int btnY = y - 18;
        long sinceFetch = System.currentTimeMillis() - liveDuelsFetchedAt;
        boolean canRefresh = sinceFetch > 1000;   // 1s cooldown
        boolean btnHover  = canRefresh && mx >= btnX && mx < btnX + btnW && my >= btnY && my < btnY + btnH;
        int border = canRefresh ? (btnHover ? BORDER_COLOR : 0xFF2A2A3A) : 0xFF1F1F2C;
        int fill   = canRefresh ? (btnHover ? 0xFF1A2A3A : 0xFF101019) : 0xFF14141C;
        g.fill(btnX - 1, btnY - 1, btnX + btnW + 1, btnY + btnH + 1, border);
        g.fill(btnX, btnY, btnX + btnW, btnY + btnH, fill);
        String label = canRefresh ? "§b⟲ REFRESH" : "§8⟲ ...";
        g.drawCenteredString(font, Component.literal(label), btnX + btnW / 2, btnY + 3,
            canRefresh ? BORDER_COLOR : TEXT_MUTED);
        liveRefreshX = btnX; liveRefreshY = btnY; liveRefreshW = btnW; liveRefreshH = btnH;
        loadLiveDuelsIfNeeded();
        if (liveDuels == null) {
            String msg = liveDuelsLoading ? "§7Loading live duels..." : "§cFailed to load live duels.";
            g.drawCenteredString(font, Component.literal(msg), px + panelW / 2, y + panelH / 2, TEXT_MUTED);
            return;
        }
        if (liveDuels.isEmpty()) {
            g.drawCenteredString(font, Component.literal("§7No live duels right now, queue up to start one!"),
                px + panelW / 2, y + panelH / 2, TEXT_MUTED);
            return;
        }

        int rowX   = px + 16;
        int innerW = panelW - 32;
        int rowY   = y;
        // Tightened columns, Mode is now [R]/[U] so it only needs ~12px.
        int cPlayers = 0;
        int cKit     = (int)(innerW * 0.36);
        int cMode    = (int)(innerW * 0.50);
        int cMap     = (int)(innerW * 0.56);
        int cPhase   = (int)(innerW * 0.72);
        int cSpec    = (int)(innerW * 0.86);

        g.drawString(font, "§7Players",  rowX + cPlayers, rowY, TEXT_MUTED, false);
        g.drawString(font, "§7Kit",      rowX + cKit,     rowY, TEXT_MUTED, false);
        g.drawString(font, "§7M",        rowX + cMode,    rowY, TEXT_MUTED, false);
        g.drawString(font, "§7Map",      rowX + cMap,     rowY, TEXT_MUTED, false);
        g.drawString(font, "§7Phase",    rowX + cPhase,   rowY, TEXT_MUTED, false);

        // Capture row hit-rects for click-to-spectate. Cleared each frame.
        spectateRowRects.clear();

        int rowH = 14;
        int maxRows = (panelH - 16) / rowH;
        int n = Math.min(liveDuels.size(), maxRows);
        for (int i = 0; i < n; i++) {
            JsonObject m = liveDuels.get(i).getAsJsonObject();
            String matchId = m.has("match_id") ? m.get("match_id").getAsString() : null;
            String a   = m.has("player_a") ? m.get("player_a").getAsString() : "?";
            String b   = m.has("player_b") ? m.get("player_b").getAsString() : "?";
            String kit = m.has("kit") ? m.get("kit").getAsString() : "?";
            boolean ranked = m.has("ranked") && m.get("ranked").getAsBoolean();
            String map = m.has("map_name") && !m.get("map_name").isJsonNull()
                ? m.get("map_name").getAsString() : "-";
            String phase = m.has("phase") ? m.get("phase").getAsString() : "?";

            int ry = rowY + 14 + i * rowH;
            g.drawString(font, "§f" + a + " §7vs §f" + b,           rowX + cPlayers, ry + 2, TEXT_PRIMARY, false);
            g.drawString(font, "§b" + kit,                           rowX + cKit,     ry + 2, TEXT_PRIMARY, false);
            g.drawString(font, ranked ? "§e§l[R]" : "§7§l[U]",        rowX + cMode,    ry + 2, TEXT_PRIMARY, false);
            g.drawString(font, "§7" + map,                           rowX + cMap,     ry + 2, TEXT_PRIMARY, false);
            int phaseColor = "active".equals(phase) ? 0xFF66E099 : 0xFFFFC85A;
            g.drawString(font, phase.toUpperCase(),                  rowX + cPhase,   ry + 2, phaseColor, false);

            // Spectate pill, only enabled for active duels (vote-phase duels
            // can't be entered yet; the plugin would kick).
            int specX = rowX + cSpec;
            int specW = innerW - cSpec - 2;
            int specY = ry;
            int specH = 12;
            boolean active = "active".equals(phase);
            boolean specHover = active && mx >= specX && mx < specX + specW && my >= specY && my < specY + specH;
            int specBorder = active ? (specHover ? 0xFF66FF99 : BORDER_COLOR) : 0xFF2A2A3A;
            int specFill   = active ? (specHover ? 0xFF1F3A24 : 0xFF1A2A3A) : 0xFF14141C;
            g.fill(specX - 1, specY - 1, specX + specW + 1, specY + specH + 1, specBorder);
            g.fill(specX, specY, specX + specW, specY + specH, specFill);
            String specLabel = active ? "§b§lSPECTATE" : "§8--";
            g.drawCenteredString(font, Component.literal(specLabel), specX + specW / 2, specY + 2,
                active ? BORDER_COLOR : TEXT_MUTED);
            if (active && matchId != null) {
                spectateRowRects.add(new int[]{specX, specY, specX + specW, specY + specH});
                spectateMatchIds.add(matchId);
            }
        }
        g.drawString(font, "§8Click §bSPECTATE§8 to watch a live duel, /spectatequit to leave.",
            rowX, y + panelH - 10, TEXT_MUTED, false);
    }

    private void renderHistory(GuiGraphics g, int px, int y, int panelW, int panelH) {
        loadHistoryIfNeeded();
        if (historyEntries == null) {
            String msg = null;
            String hint = null;
            if (historyLoading) {
                msg = "§7Loading history...";
            } else if (historyScope == HistoryScope.SELF) {
                String me = RevivalPVPMod.get().config().playerUuid();
                if (me == null || me.isBlank()) {
                    // No cached PVP identity, guide the user instead of pretending
                    // an auth process is in flight (it isn't; loadHistoryIfNeeded
                    // returns early when playerUuid is missing).
                    msg  = "§eNo PVP profile yet on this client.";
                    hint = "§7Click §bⓘ Account§7 to sign in, or §bQUEUE UP§7 once to register.";
                } else {
                    msg = "§cFailed to load history.";
                }
            } else {
                msg = "§cFailed to load history.";
            }
            g.drawCenteredString(font, Component.literal(msg),
                px + panelW / 2, y + panelH / 2 - (hint != null ? 6 : 0), TEXT_MUTED);
            if (hint != null) {
                g.drawCenteredString(font, Component.literal(hint),
                    px + panelW / 2, y + panelH / 2 + 6, TEXT_MUTED);
            }
            return;
        }
        if (historyEntries.isEmpty()) {
            String msg = historyScope == HistoryScope.SELF
                ? "§7No matches yet, queue up to play your first duel!"
                : "§7No matches recorded yet on this server.";
            g.drawCenteredString(font, Component.literal(msg), px + panelW / 2, y + panelH / 2, TEXT_MUTED);
            return;
        }

        String myUuid = RevivalPVPMod.get().config().playerUuid();
        // Column positions are fractions of panelW so the last column doesn't
        // overflow at higher GUI scales / smaller windows.
        int rowX   = px + 16;
        int innerW = panelW - 32;
        int rowY   = y;
        int rowH   = 12;
        int maxRows = (panelH - 16) / rowH;
        int n = Math.min(historyEntries.size(), maxRows);

        if (historyScope == HistoryScope.SELF) {
            int cResult = 0;
            int cKit    = (int)(innerW * 0.18);
            int cMode   = (int)(innerW * 0.34);
            int cLp     = (int)(innerW * 0.55);
            int cWhen   = (int)(innerW * 0.68);
            g.drawString(font, "§7Result", rowX + cResult, rowY, TEXT_MUTED, false);
            g.drawString(font, "§7Kit",    rowX + cKit,    rowY, TEXT_MUTED, false);
            g.drawString(font, "§7Mode",   rowX + cMode,   rowY, TEXT_MUTED, false);
            g.drawString(font, "§7LP",     rowX + cLp,     rowY, TEXT_MUTED, false);
            g.drawString(font, "§7When",   rowX + cWhen,   rowY, TEXT_MUTED, false);

            for (int i = 0; i < n; i++) {
                JsonObject m = historyEntries.get(i).getAsJsonObject();
                int ry = rowY + 14 + i * rowH;
                String when = m.has("started_at") && !m.get("started_at").isJsonNull()
                    ? m.get("started_at").getAsString().replace("T", " ").substring(0, 16) : "";
                String kit  = m.has("kit") ? m.get("kit").getAsString() : "?";
                String mode = m.has("mode") ? m.get("mode").getAsString() : "?";
                String winner = m.has("winner_uuid") && !m.get("winner_uuid").isJsonNull()
                    ? m.get("winner_uuid").getAsString() : "";
                boolean won = myUuid != null && myUuid.equals(winner);
                int lp = won
                    ? (m.has("lp_change_winner") && !m.get("lp_change_winner").isJsonNull() ? m.get("lp_change_winner").getAsInt() : 0)
                    : (m.has("lp_change_loser")  && !m.get("lp_change_loser").isJsonNull()  ? m.get("lp_change_loser").getAsInt()  : 0);
                g.drawString(font, won ? "§a§lWIN" : "§c§lLOSS",   rowX + cResult, ry, TEXT_PRIMARY, false);
                g.drawString(font, "§f" + kit,                     rowX + cKit,    ry, TEXT_PRIMARY, false);
                g.drawString(font, "§7" + mode,                    rowX + cMode,   ry, TEXT_PRIMARY, false);
                g.drawString(font, (lp >= 0 ? "§a+" : "§c") + lp,  rowX + cLp,     ry, TEXT_PRIMARY, false);
                g.drawString(font, "§7" + when,                    rowX + cWhen,   ry, TEXT_PRIMARY, false);
            }
        } else {
            int cWin    = 0;
            int cLose   = (int)(innerW * 0.20);
            int cKit    = (int)(innerW * 0.42);
            int cMode   = (int)(innerW * 0.55);
            int cWhen   = (int)(innerW * 0.70);
            g.drawString(font, "§7Winner",   rowX + cWin,  rowY, TEXT_MUTED, false);
            g.drawString(font, "§7vs Loser", rowX + cLose, rowY, TEXT_MUTED, false);
            g.drawString(font, "§7Kit",      rowX + cKit,  rowY, TEXT_MUTED, false);
            g.drawString(font, "§7Mode",     rowX + cMode, rowY, TEXT_MUTED, false);
            g.drawString(font, "§7When",     rowX + cWhen, rowY, TEXT_MUTED, false);

            for (int i = 0; i < n; i++) {
                JsonObject m = historyEntries.get(i).getAsJsonObject();
                int ry = rowY + 14 + i * rowH;
                String when = m.has("started_at") && !m.get("started_at").isJsonNull()
                    ? m.get("started_at").getAsString().replace("T", " ").substring(0, 16) : "";
                String kit  = m.has("kit") ? m.get("kit").getAsString() : "?";
                String mode = m.has("mode") ? m.get("mode").getAsString() : "?";
                String wn = m.has("winner_name") && !m.get("winner_name").isJsonNull()
                    ? m.get("winner_name").getAsString() : "?";
                String ln = m.has("loser_name") && !m.get("loser_name").isJsonNull()
                    ? m.get("loser_name").getAsString() : "?";
                g.drawString(font, "§a" + wn,    rowX + cWin,  ry, TEXT_PRIMARY, false);
                g.drawString(font, "§c" + ln,    rowX + cLose, ry, TEXT_PRIMARY, false);
                g.drawString(font, "§b" + kit,   rowX + cKit,  ry, TEXT_PRIMARY, false);
                g.drawString(font, "§7" + mode,  rowX + cMode, ry, TEXT_PRIMARY, false);
                g.drawString(font, "§7" + when,  rowX + cWhen, ry, TEXT_PRIMARY, false);
            }
        }
    }

    private void loadLeaderboardIfNeeded() {
        // Skip if currently loading, or if the cached entries are for the
        // active scope. Scope changes invalidate cache below.
        if (leaderboardLoading) return;
        if (leaderboardEntries != null && leaderboardScope.equals(leaderboardScopeLoaded)) return;

        leaderboardLoading = true;
        leaderboardEntries = null; // hide stale data while we fetch
        final String scope = leaderboardScope;

        java.util.concurrent.CompletableFuture<com.google.gson.JsonObject> future =
            "sponsors".equals(scope)
                ? BackendHttpClient.sponsorLeaderboard(100)
                : BackendHttpClient.leaderboard(scope, 100);

        future.thenAccept(resp -> {
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                leaderboardLoading = false;
                leaderboardScopeLoaded = scope;
                if (resp != null && resp.has("entries")) {
                    leaderboardEntries = resp.getAsJsonArray("entries");
                } else {
                    // Treat missing/error as empty so the UI shows the
                    // "no players yet" state instead of stale "Loading...".
                    leaderboardEntries = new JsonArray();
                }
            });
        });
    }

    // ── Player ratings (per-kit rank/lp/placement), driven by F1 + F2 ───────

    private void loadPlayerRatingsIfNeeded() {
        if (playerRatings != null || playerRatingsLoading) return;
        String myUuid = RevivalPVPMod.get().config().playerUuid();
        if (myUuid == null || myUuid.isBlank()) return;
        playerRatingsLoading = true;
        BackendHttpClient.playerRatings(myUuid).thenAccept(resp -> {
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                playerRatingsLoading = false;
                if (resp != null) playerRatings = resp;
            });
        });
    }

    /** Pull the rating row for the currently selected kit. Returns null while
     *  the fetch is in flight or if the backend returned an empty payload. */
    private JsonObject ratingForKit(Kit kit) {
        if (playerRatings == null || kit == null) return null;
        // Backend returns { "ratings": { "SWORD": {...}, "ARCHER": {...}, ... } }
        // OR a flat list under "ratings": [{...}, {...}]. Handle both.
        if (playerRatings.has("ratings") && playerRatings.get("ratings").isJsonObject()) {
            JsonObject byKit = playerRatings.getAsJsonObject("ratings");
            if (byKit.has(kit.name()) && byKit.get(kit.name()).isJsonObject()) {
                return byKit.getAsJsonObject(kit.name());
            }
        }
        if (playerRatings.has("ratings") && playerRatings.get("ratings").isJsonArray()) {
            JsonArray arr = playerRatings.getAsJsonArray("ratings");
            for (var el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject row = el.getAsJsonObject();
                if (row.has("kit") && kit.name().equalsIgnoreCase(row.get("kit").getAsString())) {
                    return row;
                }
            }
        }
        return null;
    }

    private static int rankColor(String rank) {
        if (rank == null) return TEXT_MUTED;
        return switch (rank.toUpperCase()) {
            case "IRON"        -> RANK_IRON;
            case "BRONZE"      -> RANK_BRONZE;
            case "SILVER"      -> RANK_SILVER;
            case "GOLD"        -> RANK_GOLD;
            case "PLATINUM"    -> RANK_PLATINUM;
            case "DIAMOND"     -> RANK_DIAMOND;
            case "MASTER"      -> RANK_MASTER;
            case "GRANDMASTER" -> RANK_GRANDMASTER;
            case "CHALLENGER"  -> RANK_CHALLENGER;
            default            -> TEXT_MUTED;
        };
    }

    /** Adventure legacy color char for inline §-coded text in titles/lore. */
    private static String rankLegacyColor(String rank) {
        if (rank == null) return "§7";
        return switch (rank.toUpperCase()) {
            case "IRON"        -> "§7";
            case "BRONZE"      -> "§6";
            case "SILVER"      -> "§f";
            case "GOLD"        -> "§e";
            case "PLATINUM"    -> "§b";
            case "DIAMOND"     -> "§b";
            case "MASTER"      -> "§d";
            case "GRANDMASTER" -> "§c";
            case "CHALLENGER"  -> "§e";
            default            -> "§7";
        };
    }

    /** Map division "I"/"II"/"III"/"IV" → filled pip count (I=4, IV=1). */
    private static int divisionPipCount(String division) {
        if (division == null) return 0;
        return switch (division) {
            case "I"   -> 4;
            case "II"  -> 3;
            case "III" -> 2;
            case "IV"  -> 1;
            default    -> 0;
        };
    }

    /** Tier names whose rank doesn't have divisions (apex bar instead of pips). */
    private static boolean isApexRank(String rank) {
        if (rank == null) return false;
        String r = rank.toUpperCase();
        return r.equals("MASTER") || r.equals("GRANDMASTER") || r.equals("CHALLENGER");
    }

    // ── F1: Placement progress pill (above kit list) ─────────────────────────

    private void renderPlacementPill(GuiGraphics g, int x, int y, int w, int h) {
        loadPlayerRatingsIfNeeded();

        // Background + 1px border. Border accents amber while in placement to
        // visually nag, swaps to muted once ranked.
        JsonObject row = playerRatings == null ? null : ratingForKit(selectedKit);
        boolean placementDone = row != null && row.has("placement_done") && row.get("placement_done").getAsBoolean();
        int borderColor = placementDone ? 0xFF2A2A3A : RANK_AMBER;

        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, borderColor);
        g.fill(x, y, x + w, y + h, 0xFF101019);

        if (playerRatings == null) {
            String msg = playerRatingsLoading ? "§8Loading placements..." : "§8No rating data";
            g.drawCenteredString(font, Component.literal(msg), x + w / 2, y + 3, TEXT_MUTED);
            return;
        }
        if (row == null) {
            g.drawCenteredString(font, Component.literal("§8No data for " + selectedKit.name()), x + w / 2, y + 3, TEXT_MUTED);
            return;
        }

        if (placementDone) {
            // Compact "you're ranked" line, no progress bar.
            String rank = row.has("rank") && !row.get("rank").isJsonNull() ? row.get("rank").getAsString() : "UNRANKED";
            String division = row.has("division") && !row.get("division").isJsonNull() ? row.get("division").getAsString() : "";
            int lp = row.has("lp") && !row.get("lp").isJsonNull() ? row.get("lp").getAsInt() : 0;
            String line = "§7Placed.  §fCurrent: " + rankLegacyColor(rank) + rank + (division.isBlank() ? "" : " " + division)
                + " §7• §e" + lp + " LP";
            g.drawString(font, Component.literal(line), x + 6, y + 3, TEXT_PRIMARY, false);
            return;
        }

        // Placement state, show 10-segment progress bar + W counter.
        int played = row.has("placement_games_played") ? row.get("placement_games_played").getAsInt() : 0;
        int wins   = row.has("placement_wins") ? row.get("placement_wins").getAsInt() : 0;

        // Left-aligned label
        g.drawString(font, Component.literal("§7Placements:"), x + 6, y + 3, TEXT_MUTED, false);

        // 10-segment bar, each segment 8px wide with 1px gap. Total ~89px.
        int barX = x + 64;
        int barY = y + 3;
        int segW = 8;
        int segGap = 1;
        for (int i = 0; i < 10; i++) {
            int sx = barX + i * (segW + segGap);
            int color = i < played ? RANK_AMBER : 0xFF1F1F2C;
            g.fill(sx, barY, sx + segW, barY + 8, color);
        }

        // Right-aligned text "X/10  • W: Y"
        String stat = "§f" + played + "§7/§f10  §8•  §7W: §a" + wins;
        int statW = font.width(stat.replaceAll("§.", ""));
        g.drawString(font, Component.literal(stat), x + w - statW - 6, y + 3, TEXT_PRIMARY, false);
    }

    // ── F2: Rank band above the 3D viewer ────────────────────────────────────

    private void renderRankBand(GuiGraphics g, int x, int y, int w, int h) {
        loadPlayerRatingsIfNeeded();

        // Background + tier-colored top stripe (resolved once row is fetched).
        g.fill(x, y, x + w, y + h, PANEL_COLOR);

        if (playerRatings == null) {
            int accent = playerRatingsLoading ? TEXT_MUTED : RANK_AMBER;
            g.fill(x, y, x + w, y + 2, accent);
            String msg = playerRatingsLoading ? "§7Loading rank..." : "§7No rating data";
            g.drawCenteredString(font, Component.literal(msg), x + w / 2, y + 14, TEXT_MUTED);
            // Bottom divider
            g.fill(x, y + h - 1, x + w, y + h, 0xFF2A2A3A);
            return;
        }

        JsonObject row = ratingForKit(selectedKit);
        boolean placementDone = row != null && row.has("placement_done") && row.get("placement_done").getAsBoolean();
        String rank = row != null && row.has("rank") && !row.get("rank").isJsonNull()
            ? row.get("rank").getAsString() : "UNRANKED";
        String division = row != null && row.has("division") && !row.get("division").isJsonNull()
            ? row.get("division").getAsString() : "";
        int lp = row != null && row.has("lp") && !row.get("lp").isJsonNull() ? row.get("lp").getAsInt() : 0;
        int wins = row != null && row.has("placement_wins") ? row.get("placement_wins").getAsInt() : 0;
        int played = row != null && row.has("placement_games_played") ? row.get("placement_games_played").getAsInt() : 0;

        int accent = placementDone ? rankColor(rank) : RANK_AMBER;
        // Top stripe in tier color
        g.fill(x, y, x + w, y + 2, accent);
        // Bottom divider
        g.fill(x, y + h - 1, x + w, y + h, 0xFF2A2A3A);

        // Glyph slot, 16x16 item icon with tier-colored ring.
        int glyphX = x + 8;
        int glyphY = y + 11;
        // Ring (1px outline)
        g.fill(glyphX - 2, glyphY - 2, glyphX + 18, glyphY - 1, accent);
        g.fill(glyphX - 2, glyphY + 17, glyphX + 18, glyphY + 18, accent);
        g.fill(glyphX - 2, glyphY - 2, glyphX - 1, glyphY + 18, accent);
        g.fill(glyphX + 17, glyphY - 2, glyphX + 18, glyphY + 18, accent);
        try {
            ItemStack icon = new ItemStack(rankIconMaterial(placementDone ? rank : "UNRANKED"));
            g.renderItem(icon, glyphX, glyphY);
        } catch (Throwable ignored) {}

        // Text stack, to the right of the glyph
        int textX = glyphX + 24;
        if (placementDone) {
            String line1 = "§l" + rankLegacyColor(rank) + rank.toUpperCase() + (division.isBlank() ? "" : " " + division);
            g.drawString(font, Component.literal(line1), textX, y + 8, rankColor(rank), false);
            String line2 = "§7" + lp + " LP §8• §7" + wins + "W / " + played + "G";
            g.drawString(font, Component.literal(line2), textX, y + 22, TEXT_MUTED, false);
        } else {
            g.drawString(font, Component.literal("§7§lUNRANKED"), textX, y + 8, TEXT_MUTED, false);
            g.drawString(font, Component.literal("§7Placement §f" + played + "§7/§f10"), textX, y + 22, TEXT_MUTED, false);
        }

        // Right-side division pips (or apex bar)
        if (placementDone) {
            int pipsX = x + w - 28;
            int pipsY = y + 8;
            if (isApexRank(rank)) {
                g.fill(pipsX, pipsY + 4, pipsX + 22, pipsY + 8, rankColor(rank));
            } else {
                int filled = divisionPipCount(division);
                for (int i = 0; i < 4; i++) {
                    int cx = pipsX + i * 6;
                    int color = i < filled ? rankColor(rank) : RANK_PIP_EMPTY;
                    g.fill(cx, pipsY, cx + 4, pipsY + 4, color);
                }
            }
        }
    }

    /**
     * Safe ItemStack constructor, returns null instead of throwing on the
     * title screen (where Holder.Reference.components() throws because the
     * item registry isn't bound yet). All on-screen item rendering should
     * route through this to gracefully degrade to a placeholder rect.
     */
    private static ItemStack safeItem(net.minecraft.world.item.Item item) {
        if (item == null) return null;
        try { return new ItemStack(item); }
        catch (Throwable t) { return null; }
    }

    /** Choose a Material per tier for the rank glyph icon. */
    private static net.minecraft.world.item.Item rankIconMaterial(String rank) {
        if (rank == null) return net.minecraft.world.item.Items.IRON_NUGGET;
        return switch (rank.toUpperCase()) {
            case "IRON"        -> net.minecraft.world.item.Items.IRON_NUGGET;
            case "BRONZE"      -> net.minecraft.world.item.Items.COPPER_INGOT;
            case "SILVER"      -> net.minecraft.world.item.Items.IRON_INGOT;
            case "GOLD"        -> net.minecraft.world.item.Items.GOLD_INGOT;
            case "PLATINUM"    -> net.minecraft.world.item.Items.NETHERITE_SCRAP;
            case "DIAMOND"     -> net.minecraft.world.item.Items.DIAMOND;
            case "MASTER"      -> net.minecraft.world.item.Items.AMETHYST_SHARD;
            case "GRANDMASTER" -> net.minecraft.world.item.Items.NETHER_STAR;
            case "CHALLENGER"  -> net.minecraft.world.item.Items.DRAGON_HEAD;
            case "UNRANKED"    -> net.minecraft.world.item.Items.PAPER;
            default            -> net.minecraft.world.item.Items.PAPER;
        };
    }

    // ── Loadout fetch + save ─────────────────────────────────────────────────

    private void loadLoadoutIfNeeded(Kit kit) {
        if (kit == null || !kit.isRankable()) return; // skip MIXED
        if (loadoutByKit.containsKey(kit)) return;
        if (Boolean.TRUE.equals(loadoutLoading.get(kit))) return;
        loadoutLoading.put(kit, true);
        BackendHttpClient.getLoadout(kit.name()).thenAccept(resp -> {
            Minecraft.getInstance().execute(() -> {
                loadoutLoading.put(kit, false);
                if (resp != null && resp.has("by_slot")) {
                    loadoutByKit.put(kit, resp);
                    rebuildVariantOverridesFromLoadout(kit);
                }
            });
        });
    }

    /**
     * Build the renderer override map from the loadout's "selected" map (server-side state).
     * Called after a successful GET, and after a successful POST.
     */
    private void rebuildVariantOverridesFromLoadout(Kit kit) {
        JsonObject loadout = loadoutByKit.get(kit);
        if (loadout == null) return;

        Map<KitLoadout.Slot, ItemStack> rendererOverrides = new EnumMap<>(KitLoadout.Slot.class);
        Map<String, Integer> idMap = new HashMap<>();

        // Keep any existing in-memory edits the user already made (rare but possible).
        Map<String, Integer> existing = selectedVariantId.get(kit);
        if (existing != null) idMap.putAll(existing);

        if (loadout.has("selected")) {
            JsonObject sel = loadout.getAsJsonObject("selected");
            for (var entry : sel.entrySet()) {
                String slot = entry.getKey();
                if (!entry.getValue().isJsonObject()) continue;
                JsonObject variant = entry.getValue().getAsJsonObject();
                if (!idMap.containsKey(slot) && variant.has("id")) {
                    idMap.put(slot, variant.get("id").getAsInt());
                }
            }
        }

        // Resolve the chosen variant for each slot in by_slot and build the renderer override.
        if (loadout.has("by_slot")) {
            JsonObject bySlot = loadout.getAsJsonObject("by_slot");
            for (var entry : bySlot.entrySet()) {
                String slot = entry.getKey();
                KitLoadout.Slot mapped;
                try { mapped = KitLoadout.Slot.valueOf(slot); }
                catch (IllegalArgumentException e) { continue; }
                JsonObject variant = resolveSelectedVariant(loadout, slot);
                if (variant == null) continue;
                JsonObject item = variant.has("item") && variant.get("item").isJsonObject()
                    ? variant.getAsJsonObject("item") : null;
                ItemStack stack = VariantItemFactory.fromJson(item);
                rendererOverrides.put(mapped, stack == null ? ItemStack.EMPTY : stack);
            }
        }

        selectedVariantId.put(kit, idMap);
        loadoutRenderer.setSelectedVariants(kit, rendererOverrides);
    }

    /**
     * Called from the variant picker callback. Updates in-memory state, the renderer
     * override, and schedules a debounced save.
     */
    private void onVariantPicked(Kit kit, String slot, int newVariantId) {
        Map<String, Integer> map = selectedVariantId.computeIfAbsent(kit, k -> new HashMap<>());
        map.put(slot, newVariantId);

        // Refresh the renderer override for that slot using the new variant.
        JsonObject loadout = loadoutByKit.get(kit);
        if (loadout != null && loadout.has("by_slot")) {
            JsonArray arr = loadout.getAsJsonObject("by_slot").has(slot)
                ? loadout.getAsJsonObject("by_slot").getAsJsonArray(slot) : null;
            if (arr != null) {
                for (JsonElement el : arr) {
                    JsonObject v = el.getAsJsonObject();
                    if (v.has("id") && v.get("id").getAsInt() == newVariantId) {
                        // Rebuild full override map so any prior changes stay applied.
                        rebuildVariantOverridesFromLoadout(kit);
                        // (Note: rebuildVariantOverridesFromLoadout reads selectedVariantId
                        // first, so the just-stored newVariantId wins over server "selected".)
                        break;
                    }
                }
            }
        }

        // Schedule a debounced save.
        pendingSaveAt = System.currentTimeMillis() + 250L;
        pendingSaveKit = kit;
    }

    private void flushPendingSaveIfDue() {
        if (pendingSaveAt == 0L || pendingSaveKit == null) return;
        if (System.currentTimeMillis() < pendingSaveAt) return;
        Kit kit = pendingSaveKit;
        Map<String, Integer> map = selectedVariantId.get(kit);
        // Reset state regardless, even if map is empty, we don't want to retry.
        pendingSaveAt = 0L;
        pendingSaveKit = null;
        if (map == null || map.isEmpty()) return;

        JsonArray selections = new JsonArray();
        for (var e : map.entrySet()) {
            JsonObject sel = new JsonObject();
            sel.addProperty("slot", e.getKey());
            sel.addProperty("variant_id", e.getValue());
            selections.add(sel);
        }

        BackendHttpClient.setLoadout(kit.name(), selections).thenAccept(resp -> {
            Minecraft.getInstance().execute(() -> {
                if (resp != null && resp.has("by_slot")) {
                    // Server is the source of truth, replace the cached loadout and rebuild.
                    loadoutByKit.put(kit, resp);
                    rebuildVariantOverridesFromLoadout(kit);
                }
            });
        });
    }

    private void loadHistoryIfNeeded() {
        // Re-fetch when scope changes (cache is keyed by scope).
        if (historyScopeLoaded != historyScope) {
            historyEntries = null;
            historyLoading = false;
        }
        if (historyEntries != null || historyLoading) return;

        // Self-mode requires an authed playerUuid. Title-screen-opened hub may
        // hit this before MojangAuth completes; bail out cleanly so the panel
        // shows "Loading..." instead of crashing in URLEncoder on a null uuid.
        HistoryScope scopeAtFetch = historyScope;
        String myUuid = RevivalPVPMod.get().config().playerUuid();
        if (scopeAtFetch == HistoryScope.SELF && (myUuid == null || myUuid.isBlank())) {
            return;
        }

        historyLoading = true;
        var future = (scopeAtFetch == HistoryScope.SELF)
            ? BackendHttpClient.history(myUuid, 50)
            : BackendHttpClient.historyAll(50);
        future.thenAccept(resp -> {
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                historyLoading = false;
                historyScopeLoaded = scopeAtFetch;
                if (resp != null && resp.has("history")) {
                    historyEntries = resp.getAsJsonArray("history");
                }
            });
        });
    }

    /** Called from the SPECTATE button click. POSTs to /duels/{id}/spectate
     *  and on success transfers the player into the duel server with the
     *  returned spec- token. */
    private void startSpectate(String matchId) {
        BackendHttpClient.spectate(matchId).thenAccept(resp -> Minecraft.getInstance().execute(() -> {
            if (resp == null) {
                RevivalPVPMod.LOGGER.warn("Spectate request returned null for {}", matchId);
                Minecraft.getInstance().gui.setOverlayMessage(
                    Component.literal("§cSpectate failed: backend unreachable. Try again."), false);
                return;
            }
            String host  = resp.has("relay_host") && !resp.get("relay_host").isJsonNull()
                ? resp.get("relay_host").getAsString() : null;
            int    port  = resp.has("relay_port") && !resp.get("relay_port").isJsonNull()
                ? resp.get("relay_port").getAsInt() : 25565;
            String token = resp.has("session_token") && !resp.get("session_token").isJsonNull()
                ? resp.get("session_token").getAsString() : null;
            if (host == null || token == null) {
                RevivalPVPMod.LOGGER.warn("Spectate response missing required fields for {}", matchId);
                Minecraft.getInstance().gui.setOverlayMessage(
                    Component.literal("§cSpectate failed: match no longer active or server full."), false);
                return;
            }
            net.revivalsmp.pvp.network.DuelServerConnector.connectToDuelServer(host, port, token);
        }));
    }

    private void loadLiveDuelsIfNeeded() {
        long now = System.currentTimeMillis();
        boolean stale = liveDuels == null || (now - liveDuelsFetchedAt) > 5_000;
        if (!stale || liveDuelsLoading) return;
        liveDuelsLoading = true;
        BackendHttpClient.liveDuels(50).thenAccept(resp -> {
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                liveDuelsLoading = false;
                liveDuelsFetchedAt = System.currentTimeMillis();
                if (resp != null && resp.has("live")) {
                    liveDuels = resp.getAsJsonArray("live");
                }
            });
        });
    }

    private void renderFriendsTab(GuiGraphics g, int mx, int my,
                                  int px, int y, int panelW, int panelH) {
        FriendsService f = friendsService();
        if (f == null) {
            g.drawCenteredString(font, Component.literal("§7Friends service unavailable"),
                px + panelW / 2, y + panelH / 2, TEXT_MUTED);
            return;
        }
        loadFriendsIfNeeded();
        autoRefreshFriendsIfDue(f);

        friendsHitRects.clear();

        // ── Top bar: search input + Add Friend + Refresh ─────────────────────
        // Layout: (px+16, y+8, panelW-32, 18). Refresh = 22px square on the
        // right; Add Friend = 80px to its left; search fills the rest.
        int barX = px + 16, barY = y + 8, barW = panelW - 32, barH = 18;
        int addBtnW = 80, refreshBtnW = 22;
        int searchW = barW - addBtnW - refreshBtnW - 12;  // 6px gaps × 2

        // Search rect, clicking it opens the same AddFriendModal that has
        // typeahead autocomplete, so this is functional, not "coming soon".
        boolean searchHover = mx >= barX && mx < barX + searchW
                            && my >= barY && my < barY + barH;
        g.fill(barX - 1, barY - 1, barX + searchW + 1, barY + barH + 1,
            searchHover ? BORDER_COLOR : 0xFF2A2A3A);
        g.fill(barX, barY, barX + searchW, barY + barH,
            searchHover ? 0xFF14202A : 0xFF101019);
        g.drawString(font, Component.literal("§7§o>  Click to search players and add a friend"),
            barX + 6, barY + 5, TEXT_MUTED, false);
        // kind=4 reuses the "open Add Friend modal" handler.
        friendsHitRects.add(new int[]{barX, barY, barX + searchW, barY + barH, 4, 0});

        int addBtnX = barX + searchW + 6;
        boolean addHover = mx >= addBtnX && mx < addBtnX + addBtnW
                        && my >= barY  && my < barY + barH;
        g.fill(addBtnX - 1, barY - 1, addBtnX + addBtnW + 1, barY + barH + 1,
            addHover ? 0xFF66FF99 : 0xFF00CC44);
        g.fill(addBtnX, barY, addBtnX + addBtnW, barY + barH, 0xFF1A3A1A);
        g.drawCenteredString(font, Component.literal("§a§l+ ADD FRIEND"),
            addBtnX + addBtnW / 2, barY + 5, 0xFF66FF99);
        friendsHitRects.add(new int[]{addBtnX, barY, addBtnX + addBtnW, barY + barH, 4, 0});

        // Refresh icon (22px square), manual list refresh. Auto-refresh runs
        // every 10s while this tab is open; this is the explicit action.
        int refreshBtnX = addBtnX + addBtnW + 6;
        boolean refreshHover = mx >= refreshBtnX && mx < refreshBtnX + refreshBtnW
                            && my >= barY && my < barY + barH;
        g.fill(refreshBtnX - 1, barY - 1, refreshBtnX + refreshBtnW + 1, barY + barH + 1,
            refreshHover ? BORDER_COLOR : 0xFF2A2A3A);
        g.fill(refreshBtnX, barY, refreshBtnX + refreshBtnW, barY + barH, 0xFF14202A);
        g.drawCenteredString(font, Component.literal("§b⟳"),
            refreshBtnX + refreshBtnW / 2, barY + 5, 0xFF66E5FF);
        friendsHitRects.add(new int[]{refreshBtnX, barY, refreshBtnX + refreshBtnW, barY + barH, 6, 0});

        // ── Toast (invite sent / unfriended / error) ─────────────────────────
        if (friendsToast != null
            && System.currentTimeMillis() - friendsToastAt < FRIENDS_TOAST_MS) {
            int toastY = y + 16;
            g.drawCenteredString(font, Component.literal(friendsToast),
                px + panelW / 2, toastY, friendsToastColor);
        }

        // ── Pending-requests row (collapsible), incoming + outgoing ─────────
        int rowsTop = y + 32;
        var incoming = f.incomingRequests();
        var outgoing = f.outgoingRequests();
        int totalPending = incoming.size() + outgoing.size();
        int pendW = panelW - 32;

        if (totalPending == 0) {
            g.fill(px + 16 - 1, rowsTop - 1, px + 16 + pendW + 1, rowsTop + 18 + 1, 0xFF2A2A3A);
            g.fill(px + 16, rowsTop, px + 16 + pendW, rowsTop + 18, 0xFF1A1A2A);
            g.drawString(font, Component.literal("§8No pending requests"),
                px + 16 + 8, rowsTop + 5, TEXT_MUTED, false);
            pendingExpanded = false;
        } else {
            int border = pendingExpanded ? BORDER_COLOR : RANK_AMBER;
            g.fill(px + 16 - 1, rowsTop - 1, px + 16 + pendW + 1, rowsTop + 18 + 1, border);
            g.fill(px + 16, rowsTop, px + 16 + pendW, rowsTop + 18, 0xFF1A1A2A);
            // Show split count: "2 incoming · 1 sent" so the user can tell
            // their own outgoing requests are tracked.
            String label = (incoming.isEmpty() ? "" : "§e" + incoming.size() + " incoming")
                + (incoming.isEmpty() || outgoing.isEmpty() ? "" : " §8· ")
                + (outgoing.isEmpty() ? "" : "§b" + outgoing.size() + " sent")
                + " §7, click to " + (pendingExpanded ? "collapse" : "expand");
            g.drawString(font, Component.literal(label), px + 16 + 8, rowsTop + 5, TEXT_PRIMARY, false);
            friendsHitRects.add(new int[]{px + 16, rowsTop, px + 16 + pendW, rowsTop + 18, 5, 0});
        }

        int listTop = rowsTop + 22;

        // Expanded pending rows, incoming first (action-required), then sent.
        if (pendingExpanded && totalPending > 0) {
            int maxVisible = Math.min(5, totalPending);
            int rendered = 0;
            for (int i = 0; i < incoming.size() && rendered < maxVisible; i++, rendered++) {
                renderPendingRequestRow(g, mx, my, px + 16, listTop + rendered * 22, pendW,
                    incoming.get(i));
            }
            for (int i = 0; i < outgoing.size() && rendered < maxVisible; i++, rendered++) {
                renderOutgoingRequestRow(g, px + 16, listTop + rendered * 22, pendW,
                    outgoing.get(i));
            }
            listTop += rendered * 22 + 4;
        }

        // ── Friends list ─────────────────────────────────────────────────────
        var list = f.friends();
        if (list.isEmpty()) {
            g.drawCenteredString(font, Component.literal("§7No friends yet, invite someone with [+ ADD FRIEND]"),
                px + panelW / 2, listTop + 30, TEXT_MUTED);
            return;
        }

        int rowH = 22;
        int maxRows = Math.max(1, (panelH - (listTop - y) - 8) / rowH);
        int n = Math.min(list.size(), maxRows);
        for (int i = 0; i < n; i++) {
            var fr = list.get(i);
            int ry = listTop + i * rowH;
            renderFriendRow(g, mx, my, px + 16, ry, panelW - 32, fr, i);
        }
    }

    private void renderPendingRequestRow(GuiGraphics g, int mx, int my,
                                          int x, int y, int w,
                                          FriendsService.FriendRequest req) {
        g.fill(x - 1, y - 1, x + w + 1, y + 19, 0xFF2A2A3A);
        g.fill(x, y, x + w, y + 18, 0xFF101019);

        g.drawString(font, Component.literal("§f" + req.senderUsername()),
            x + 8, y + 5, TEXT_PRIMARY, false);

        // [Accept] [Reject] pills on the right.
        int aX = x + w - 122, aW = 56;
        int rX = x + w - 60,  rW = 56;
        renderSmallPill(g, mx, my, aX, y + 1, aW, "§a✓ Accept",
            0xFF1A3A1A, 0xFF00CC44);
        renderSmallPill(g, mx, my, rX, y + 1, rW, "§c✗ Reject",
            0xFF3A1A1A, 0xFFCC4444);
        friendsHitRects.add(new int[]{aX, y + 1, aX + aW, y + 17, 1, (int) req.id()});
        friendsHitRects.add(new int[]{rX, y + 1, rX + rW, y + 17, 2, (int) req.id()});
    }

    private void setFriendsToast(String text, int color) {
        friendsToast      = text;
        friendsToastAt    = System.currentTimeMillis();
        friendsToastColor = color;
    }

    /** Trigger {@link FriendsService#refresh()} at most every
     *  {@link #FRIENDS_AUTO_REFRESH_MS} while the Friends tab is rendering.
     *  Backend has no presence push for friends yet, so without this the list
     *  goes stale immediately after the initial load. */
    private void autoRefreshFriendsIfDue(FriendsService f) {
        long now = System.currentTimeMillis();
        if (now - friendsLastAutoRefreshAt < FRIENDS_AUTO_REFRESH_MS) return;
        friendsLastAutoRefreshAt = now;
        f.refresh();
    }

    /** Outgoing request row, read-only, dimmer styling, "Awaiting reply" hint
     *  on the right. No clicks; sender can't cancel from here in v1. */
    private void renderOutgoingRequestRow(GuiGraphics g, int x, int y, int w,
                                            FriendsService.OutgoingRequest req) {
        g.fill(x - 1, y - 1, x + w + 1, y + 19, 0xFF1A2A3A);
        g.fill(x, y, x + w, y + 18, 0xFF0F1620);
        g.drawString(font, Component.literal("§b→ §f" + req.targetUsername()),
            x + 8, y + 5, TEXT_PRIMARY, false);
        String hint = "§8awaiting reply";
        g.drawString(font, Component.literal(hint),
            x + w - font.width("awaiting reply") - 8, y + 5, TEXT_MUTED, false);
    }

    private void renderFriendRow(GuiGraphics g, int mx, int my,
                                  int x, int y, int w,
                                  FriendsService.Friend fr, int idx) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 20;
        g.fill(x - 1, y - 1, x + w + 1, y + 21, hover ? 0xFF4A4A6A : 0xFF1F1F2C);
        g.fill(x, y, x + w, y + 20, hover ? 0xFF181828 : 0xFF101019);

        // Avatar (12x12). Pulls the friend's skin from the same LRU cache the
        // detail panel uses; first render kicks off the Mojang fetch, every
        // subsequent render is a Map lookup. Falls back to a colored fill
        // until the fetch completes.
        int ax = x + 4, ay = y + 4, asz = 12;
        net.minecraft.resources.ResourceLocation tex = prefetchSkinIfNeeded(fr.uuid(), fr.username());
        if (tex != null) {
            try {
                g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    tex, ax, ay, 8f, 8f, asz, asz, 8, 8, 64, 64);
                g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    tex, ax, ay, 40f, 8f, asz, asz, 8, 8, 64, 64);
            } catch (Throwable t) {
                g.fill(ax, ay, ax + asz, ay + asz, RANK_IRON);
            }
        } else {
            g.fill(ax, ay, ax + asz, ay + asz, RANK_IRON);
        }

        // Username.
        g.drawString(font, Component.literal("§f" + fr.username()),
            x + 20, y + 6, TEXT_PRIMARY, false);

        // Presence dot + label.
        int dotX = x + w - 140;
        int dotColor = switch (fr.onlineState() == null ? "" : fr.onlineState()) {
            case "idle"     -> 0xFF44CC44;
            case "in_queue" -> 0xFFCCCC44;
            case "in_duel"  -> 0xFFCC8844;
            default         -> 0xFF444444;
        };
        g.fill(dotX, y + 8, dotX + 5, y + 13, dotColor);
        String stateLabel = switch (fr.onlineState() == null ? "" : fr.onlineState()) {
            case "idle"     -> "online";
            case "in_queue" -> "queue";
            case "in_duel"  -> "duel";
            default         -> "offline";
        };
        g.drawString(font, Component.literal("§7" + stateLabel),
            dotX + 8, y + 6, TEXT_MUTED, false);

        // Kit-rank stub if present.
        if (fr.kit() != null && fr.rank() != null) {
            String text = rankLegacyColor(fr.rank()) + fr.rank().substring(0, 1) + fr.rank().substring(1).toLowerCase()
                + (fr.division() == null || fr.division().isBlank() ? "" : " " + fr.division())
                + " §7" + fr.lp() + "LP";
            g.drawString(font, Component.literal(text), x + w - 200, y + 6, TEXT_PRIMARY, false);
        }

        // Buttons. Invite ALWAYS rendered, disabled (greyed) when friend
        // isn't idle. The click still registers so the user gets a clear
        // "X is offline" toast instead of clicking a phantom invisible button.
        int invX = x + w - 90;
        boolean canInvite = fr.canInvite();
        renderSmallPill(g, mx, my, invX, y + 2, 44,
            canInvite ? "§b⚔ Invite" : "§7⚔ Invite",
            canInvite ? 0xFF1A2A3A : 0xFF1A1A22,
            canInvite ? BORDER_COLOR : 0xFF333344);
        friendsHitRects.add(new int[]{invX, y + 2, invX + 44, y + 18, 0, idx});

        // Unfriend ✕, two-step. First click arms confirm, second within 3s
        // actually deletes. Visual armed state: red fill + "Confirm?" label.
        int xX = x + w - 38;
        boolean armed = fr.uuid().equals(unfriendArmedUuid)
            && System.currentTimeMillis() - unfriendArmedAt < UNFRIEND_CONFIRM_MS;
        if (armed) {
            renderSmallPill(g, mx, my, xX, y + 2, 24, "§4§l!?",
                0xFF5A1A1A, 0xFFFF4444);
        } else {
            renderSmallPill(g, mx, my, xX, y + 2, 24, "§c✕",
                0xFF2A1A1A, 0xFF553333);
        }
        friendsHitRects.add(new int[]{xX, y + 2, xX + 24, y + 18, 3, idx});
    }

    private void renderSmallPill(GuiGraphics g, int mx, int my,
                                  int x, int y, int w, String label,
                                  int bg, int border) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + 16;
        g.fill(x - 1, y - 1, x + w + 1, y + 17, hover ? BORDER_COLOR : border);
        g.fill(x, y, x + w, y + 16, bg);
        g.drawCenteredString(font, Component.literal(label), x + w / 2, y + 4, TEXT_PRIMARY);
    }

    private void renderBtn(GuiGraphics g, int x, int y, int w, int h, String label, int bg, int border) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, border);
        g.fill(x, y, x + w, y + h, bg);
        g.drawCenteredString(font, Component.literal(label), x + w / 2, y + (h - 8) / 2, TEXT_PRIMARY);
    }

    // ── Friends data + sponsor data lifecycle ─────────────────────────────────

    private FriendsService friendsService() {
        var c = RevivalPVPClient.get();
        return c == null ? null : c.friends();
    }

    private boolean friendsLoaded;
    private void loadFriendsIfNeeded() {
        if (friendsLoaded) return;
        var f = friendsService();
        if (f == null) return;
        friendsLoaded = true;
        f.refresh();
    }

    private void loadCoinBalanceIfNeeded() {
        if (coinBalance >= 0 || coinBalanceLoading) return;
        coinBalanceLoading = true;
        BackendHttpClient.myCoins().thenAccept(resp -> Minecraft.getInstance().execute(() -> {
            coinBalanceLoading = false;
            if (resp != null && resp.has("balance") && !resp.get("balance").isJsonNull()) {
                coinBalance = resp.get("balance").getAsInt();
            } else {
                coinBalance = 0;
            }
            pendingCoins = (resp != null && resp.has("pending") && !resp.get("pending").isJsonNull())
                ? resp.get("pending").getAsInt() : 0;
        }));
    }

    private void invalidateCoinBalance() {
        coinBalance = -1;
        coinBalanceLoading = false;
    }

    // ── (i) info icon (top-right of title bar) ────────────────────────────────

    private void renderInfoIcon(GuiGraphics g, int px, int py, int panelW,
                                 int mx, int my) {
        // Visible "Account" pill at the right edge of the title row. Sits to
        // the LEFT of the coin pill if one is being rendered; otherwise
        // anchors to the right edge with a small gap.
        int pillW = 70, pillH = 14;
        int x = (coinPillRect != null) ? (coinPillRect[0] - pillW - 6)
                                       : (px + panelW - pillW - 8);
        int y = py + 6;
        boolean hover = mx >= x && mx < x + pillW && my >= y && my < y + pillH;
        int border = hover ? 0xFFFFFFFF : BORDER_COLOR;
        int fill   = hover ? 0xFF1A2A3A : 0xFF0E1A24;
        g.fill(x - 1, y - 1, x + pillW + 1, y + pillH + 1, border);
        g.fill(x, y, x + pillW, y + pillH, fill);
        g.drawCenteredString(font, Component.literal("§b§lⓘ Account"),
            x + pillW / 2, y + 3, BORDER_COLOR);
        infoIconRect = new int[]{x, y, x + pillW, y + pillH};
    }

    // ── Title-bar coin pill (♥ N) ─────────────────────────────────────────────

    private void renderKeyBalancePill(GuiGraphics g, int px, int py, int panelW,
                                       int mx, int my) {
        loadCoinBalanceIfNeeded();

        // Pending notice is shown even if the player has zero balance (it's
        // their cue to /pvp queue once to claim a recent purchase).
        if (coinBalance <= 0 && pendingCoins <= 0) {
            coinPillRect = null;
            return;
        }

        if (coinBalance > 0) {
            int digits = Math.max(1, (int) Math.log10(Math.max(1, coinBalance)) + 1);
            int pillW = Math.max(80, 56 + digits * 6);
            int pillH = 14;
            int x = px + panelW - pillW - 8;
            int y = py + 6;
            boolean hover = mx >= x && mx < x + pillW && my >= y && my < y + pillH;
            int border = hover ? 0xFFFF99CC : 0xFFFF6BA8;
            g.fill(x - 1, y - 1, x + pillW + 1, y + pillH + 1, border);
            g.fill(x, y, x + pillW, y + pillH, 0xFF1A1020);
            g.drawCenteredString(font, Component.literal("§d♥ §f" + coinBalance + " coins"),
                x + pillW / 2, y + 3, 0xFFFF99CC);
            coinPillRect = new int[]{x, y, x + pillW, y + pillH};
        } else {
            coinPillRect = null;
        }

        // Pending coins notice, small amber line below the pill (or in the
        // pill's slot if no balance yet). Distinct color so it doesn't read
        // as part of the current balance.
        if (pendingCoins > 0) {
            String text = "§e+" + pendingCoins + " pending, auth via /pvp queue";
            int w = font.width(text.replaceAll("§.", ""));
            int x = px + panelW - w - 12;
            int y = (coinBalance > 0) ? (py + 22) : (py + 8);
            g.drawString(font, Component.literal(text), x, y, RANK_AMBER, false);
        }
    }

    // ── Incoming-invite overlay ──────────────────────────────────────────────

    private void renderInviteOverlay(GuiGraphics g, int px, int py, int panelW,
                                      int mx, int my) {
        FriendsService f = friendsService();
        if (f == null) { inviteAcceptRect = null; inviteDeclineRect = null; return; }
        FriendsService.DuelInvite inv = f.activeInvite();
        if (inv == null) { inviteAcceptRect = null; inviteDeclineRect = null; return; }

        int w = 200, h = 72;
        int x = px + panelW - w - 16;
        int y = py + 30;

        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER_COLOR);
        g.fill(x, y, x + w, y + h, 0xFF1C1C2C);

        g.drawString(font, Component.literal("§b§l⚔ Duel Invite"), x + 8, y + 8, BORDER_COLOR, false);
        g.drawString(font, Component.literal("§ffrom §f§l" + inv.otherUsername()), x + 8, y + 22, TEXT_PRIMARY, false);
        g.drawString(font, Component.literal("§7" + inv.kit() + (inv.ranked() ? " · ranked" : " · unranked")),
            x + 8, y + 32, TEXT_MUTED, false);

        int axB = x + 8, ayB = y + 44;
        renderSmallPill(g, mx, my, axB, ayB, 60, "§a✓ Accept",
            0xFF1A3A1A, 0xFF00CC44);
        int dxB = x + 76, dyB = y + 44;
        renderSmallPill(g, mx, my, dxB, dyB, 60, "§c✗ Decline",
            0xFF3A1A1A, 0xFFCC4444);
        inviteAcceptRect  = new int[]{axB, ayB, axB + 60, ayB + 16};
        inviteDeclineRect = new int[]{dxB, dyB, dxB + 60, dyB + 16};

        // Countdown bar (depleting from right to left over 30s).
        long secs = inv.secondsLeft();
        float frac = Math.max(0f, Math.min(1f, secs / 30f));
        int barX = x;
        int barW = (int) (w * frac);
        g.fill(barX, y + h - 4, barX + barW, y + h, 0xFFFF6BA8);
    }

    // ── Player detail sub-view (replaces leaderboard list when active) ───────

    private void renderPlayerDetailView(GuiGraphics g, int mx, int my,
                                         int px, int y, int panelW, int panelH) {
        if (playerDetailEntry == null) return;
        loadPlayerDetailIfNeeded();

        int x = px + 16;
        int innerW = panelW - 32;

        // Back button.
        boolean backHover = mx >= x && mx < x + 50 && my >= y + 8 && my < y + 24;
        g.fill(x - 1, y + 7, x + 51, y + 25, backHover ? BORDER_COLOR : 0xFF2A2A3A);
        g.fill(x, y + 8, x + 50, y + 24, 0xFF1A1A2A);
        g.drawCenteredString(font, Component.literal("§7← Back"), x + 25, y + 12, TEXT_MUTED);
        detailBackRect = new int[]{x, y + 8, x + 50, y + 24};

        // Header row.
        int headY = y + 32;
        String username = optStrEntry(playerDetailEntry, "username", "?");
        String uuid     = optStrEntry(playerDetailEntry, "uuid", null);

        // Kick off skin load on first render after open. Lands back on the
        // MC thread once Mojang's session server returns the textures.
        loadDetailSkinIfNeeded(uuid, username);

        // 32x32 head: face layer + hat overlay. If the skin hasn't loaded yet
        // (or uuid is missing) fall back to a colored fill so the layout
        // doesn't shift when the texture pops in.
        if (detailSkinTex != null) {
            try {
                // Face: 8x8 region at u=8, v=8 of a 64x64 skin texture, scaled
                // up to 32x32. Then the hat layer on top at u=40, v=8.
                g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    detailSkinTex, x, headY, 8f, 8f, 32, 32, 8, 8, 64, 64);
                g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    detailSkinTex, x, headY, 40f, 8f, 32, 32, 8, 8, 64, 64);
            } catch (Throwable t) {
                g.fill(x, headY, x + 32, headY + 32, RANK_IRON);
            }
        } else {
            g.fill(x, headY, x + 32, headY + 32, RANK_IRON);
        }
        // Subtle border around the head.
        g.fill(x - 1, headY - 1, x + 33, headY,        0xFF2A2A3A);
        g.fill(x - 1, headY + 32, x + 33, headY + 33,  0xFF2A2A3A);
        g.fill(x - 1, headY,    x,        headY + 32,  0xFF2A2A3A);
        g.fill(x + 32, headY,   x + 33,   headY + 32,  0xFF2A2A3A);

        int rankPos     = playerDetailEntry.has("rank_position") ? playerDetailEntry.get("rank_position").getAsInt() : 0;
        g.drawString(font, Component.literal("§f§l" + username),
            x + 40, headY + 4, TEXT_PRIMARY, false);
        g.drawString(font, Component.literal("§7Global rank §f#" + rankPos),
            x + 40, headY + 16, TEXT_MUTED, false);

        // Per-kit rank line, single row pulled from leaderboardEntry (one kit only).
        int kitsY = headY + 40;
        String rank = optStrEntry(playerDetailEntry, "rank", "UNRANKED");
        String div  = optStrEntry(playerDetailEntry, "division", "");
        int lp = playerDetailEntry.has("lp") ? playerDetailEntry.get("lp").getAsInt() : 0;
        g.drawString(font, Component.literal(rankLegacyColor(rank) + rank
                + (div.isBlank() ? "" : " " + div) + " §7• §e" + lp + " LP"),
            x, kitsY, rankColor(rank), false);

        // Sponsor block, pulled from /sponsor/profile. Sized dynamically to
        // fill the space between the kit-rank line and the sponsor buttons.
        int btnYReserved  = y + panelH - 28;
        int sponsorY      = kitsY + 16;
        int sponsorH      = (btnYReserved - 12) - sponsorY;
        if (sponsorH < 80) sponsorH = 80;
        g.fill(x - 1, sponsorY - 1, x + innerW + 1, sponsorY + sponsorH + 1, 0xFF2A2A3A);
        g.fill(x, sponsorY, x + innerW, sponsorY + sponsorH, 0xFF101019);
        g.drawString(font, Component.literal("§d§l♥ Sponsorship"), x + 8, sponsorY + 6, 0xFFFF6BA8, false);

        if (playerDetailLoading) {
            g.drawString(font, Component.literal("§7Loading sponsor data..."),
                x + 8, sponsorY + 22, TEXT_MUTED, false);
            detailSponsorListRect = null;
        } else if (playerDetailProfile != null) {
            int total  = playerDetailProfile.has("total_coins")  ? playerDetailProfile.get("total_coins").getAsInt()  : 0;
            int season = playerDetailProfile.has("season_coins") ? playerDetailProfile.get("season_coins").getAsInt() : 0;
            g.drawString(font, Component.literal("§fTotal coins received: §d♥ " + total + " §7(season: " + season + ")"),
                x + 8, sponsorY + 22, TEXT_PRIMARY, false);

            // Top sponsors list, render as many rows as fit in the remaining
            // sponsor block height; scroll wheel cycles through extras.
            JsonArray ts = playerDetailProfile.has("top_sponsors")
                ? playerDetailProfile.getAsJsonArray("top_sponsors")
                : new JsonArray();
            detailSponsorTotal = ts.size();

            int listTop      = sponsorY + 38;
            int listBottom   = sponsorY + sponsorH - 6;
            int rowHeight    = 11;
            int visibleRows  = Math.max(0, (listBottom - listTop) / rowHeight);
            detailSponsorVisible = visibleRows;
            // Clamp scroll so we never blank-scroll past the end.
            int maxScroll = Math.max(0, detailSponsorTotal - visibleRows);
            if (detailSponsorScroll > maxScroll) detailSponsorScroll = maxScroll;
            if (detailSponsorScroll < 0) detailSponsorScroll = 0;

            if (detailSponsorTotal == 0) {
                g.drawString(font, Component.literal("§8No sponsors yet, be the first to back this player."),
                    x + 8, listTop, TEXT_MUTED, false);
            } else {
                int shown = Math.min(visibleRows, detailSponsorTotal - detailSponsorScroll);
                for (int i = 0; i < shown; i++) {
                    int idx = i + detailSponsorScroll;
                    JsonObject s = ts.get(idx).getAsJsonObject();
                    String name  = optStrEntry(s, "sponsor_name", "?");
                    int coins    = s.has("coins_spent") ? s.get("coins_spent").getAsInt() : 0;
                    g.drawString(font, Component.literal("§7" + (idx + 1) + ". §f" + name + " §7, §d♥" + coins),
                        x + 8, listTop + i * rowHeight, TEXT_PRIMARY, false);
                }
                // Scrollbar (only if scrollable).
                if (detailSponsorTotal > visibleRows) {
                    int trackX = x + innerW - 6;
                    int trackTop = listTop;
                    int trackBot = listTop + visibleRows * rowHeight;
                    int trackH   = trackBot - trackTop;
                    g.fill(trackX, trackTop, trackX + 3, trackBot, 0xFF1f1f2c);
                    int thumbH = Math.max(8, trackH * visibleRows / detailSponsorTotal);
                    int thumbY = trackTop + (trackH - thumbH) * detailSponsorScroll
                                          / Math.max(1, maxScroll);
                    g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFFFF6BA8);
                }
            }
            detailSponsorListRect = new int[]{ x + 4, listTop - 2,
                                                x + innerW - 4, listBottom };
        }

        // Sponsor amount buttons (10 / 100 / 500). Disabled state when balance
        // can't cover the tier. Clicking debits the sponsor's balance and
        // increments the recipient's coins-received total + revenue ledger.
        int btnY = y + panelH - 28;
        boolean can10  = coinBalance >= 10;
        boolean can100 = coinBalance >= 100;
        boolean can500 = coinBalance >= 500;

        int btnW = 76;
        int gap  = 6;
        // Buttons stay clickable even when the balance is short — clicks on
        // an under-balance tier surface a "need more coins" notice + a
        // clickable link to the store, instead of silently no-op'ing.
        int row1X = x + innerW - (btnW * 3 + gap * 2);
        renderBigBtn(g, mx, my, row1X, btnY, btnW, 22,
            "§d♥10",  can10  ? 0xFF3A1A2A : 0xFF1F1F2C, can10  ? 0xFFFF6BA8 : 0xFF2A2A3A);
        detailSponsor10Rect  = new int[]{row1X, btnY, row1X + btnW, btnY + 22};

        int row2X = row1X + btnW + gap;
        renderBigBtn(g, mx, my, row2X, btnY, btnW, 22,
            "§d♥100", can100 ? 0xFF3A1A2A : 0xFF1F1F2C, can100 ? 0xFFFF6BA8 : 0xFF2A2A3A);
        detailSponsor100Rect = new int[]{row2X, btnY, row2X + btnW, btnY + 22};

        int row3X = row2X + btnW + gap;
        renderBigBtn(g, mx, my, row3X, btnY, btnW, 22,
            "§d♥500", can500 ? 0xFF3A1A2A : 0xFF1F1F2C, can500 ? 0xFFFF6BA8 : 0xFF2A2A3A);
        detailSponsor500Rect = new int[]{row3X, btnY, row3X + btnW, btnY + 22};

        // Hint line above the button row. Always show a clickable "Buy more"
        // link when balance is short OR a recent click landed on a tier the
        // player can't afford (lowBalanceMsgUntilMs gates the visible text).
        long now = System.currentTimeMillis();
        boolean showShortMsg = lowBalanceMsgUntilMs > now;
        if (!can10 || showShortMsg) {
            String msg = showShortMsg
                ? "§c" + lowBalanceMsg + " §7§n(click to buy more)"
                : "§8Need more coins to sponsor. §7§n(click to buy on the website)";
            int hintW = font.width(msg);
            g.drawString(font, Component.literal(msg), x, btnY - 12,
                showShortMsg ? 0xFFFF6BA8 : TEXT_MUTED, false);
            detailBuyCoinsRect = new int[]{ x, btnY - 14, x + hintW, btnY - 2 };
        } else {
            detailBuyCoinsRect = null;
        }
    }

    private void renderBigBtn(GuiGraphics g, int mx, int my, int x, int y, int w, int h,
                              String label, int bg, int border) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, hover ? 0xFFFF99CC : border);
        g.fill(x, y, x + w, y + h, bg);
        g.drawCenteredString(font, Component.literal(label), x + w / 2, y + (h - 8) / 2, TEXT_PRIMARY);
    }

    private void loadPlayerDetailIfNeeded() {
        if (playerDetailEntry == null || playerDetailLoading) return;
        if (playerDetailProfile != null) return;
        String identifier = optStrEntry(playerDetailEntry, "username", null);
        if (identifier == null) return;
        playerDetailLoading = true;
        BackendHttpClient.sponsorProfile(identifier).thenAccept(resp ->
            Minecraft.getInstance().execute(() -> {
                playerDetailLoading = false;
                if (resp != null) playerDetailProfile = resp;
            }));
    }

    private static String optStrEntry(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    /**
     * Resolve the player's skin texture for an arbitrary uuid (a leaderboard
     * row, not necessarily an online player). Two-step:
     *   1. fillProfileProperties() against Mojang's session server to attach
     *      the "textures" property to the bare GameProfile. Without this step
     *      SkinManager has no skin URL and falls back to the default Steve.
     *   2. SkinManager.get(profile) downloads + caches the actual skin
     *      texture and returns a PlayerSkin we pull a Texture path from.
     *
     * fillProfileProperties is a blocking HTTP call so we hop to a background
     * thread. The SkinManager call already returns a future. Final assignment
     * to detailSkinTex happens on the MC thread. Idempotent per-uuid.
     */
    /**
     * Generic skin prefetcher used by both the detail panel and the friends
     * list row avatars. Idempotent and dedup'd via skinFetchInFlight; on
     * success the resolved ResourceLocation lands in skinTextureCache. Returns
     * the cached ResourceLocation if there's an immediate hit (so single-frame
     * renders can branch without a second map lookup).
     */
    private net.minecraft.resources.ResourceLocation prefetchSkinIfNeeded(String uuid, String username) {
        if (uuid == null) return null;
        net.minecraft.resources.ResourceLocation cached = skinTextureCache.get(uuid);
        if (cached != null) return cached;
        if (!skinFetchInFlight.add(uuid)) return null;

        final java.util.UUID parsed;
        try { parsed = java.util.UUID.fromString(uuid); }
        catch (Exception e) { skinFetchInFlight.remove(uuid); return null; }
        com.mojang.authlib.GameProfile barebones =
            new com.mojang.authlib.GameProfile(parsed, username == null ? "" : username);

        RevivalPVPMod.LOGGER.info("[skin] fetching profile for {} ({})", username, uuid);
        java.util.concurrent.CompletableFuture
            .supplyAsync(() -> fetchMojangProfile(parsed, username == null ? "" : username, barebones))
            // 1.21.7 SkinManager: getOrLoad(GameProfile) returns
            // CompletableFuture<Optional<PlayerSkin>>; PlayerSkin has
            // texture() directly (no .body() wrapper). Both renamed in 1.21.8+.
            .thenCompose(populated -> Minecraft.getInstance().getSkinManager().getOrLoad(populated))
            .thenAccept(opt -> Minecraft.getInstance().execute(() -> {
                skinFetchInFlight.remove(uuid);
                if (opt == null || opt.isEmpty()) {
                    RevivalPVPMod.LOGGER.warn("[skin] SkinManager.getOrLoad returned empty for {}", uuid);
                    return;
                }
                try {
                    var tex = opt.get().texture();
                    skinTextureCache.put(uuid, tex);
                    // Detail panel render-loop convenience: if this fetch was
                    // for the currently-open detail player, surface it now.
                    if (uuid.equals(detailSkinUuid)) detailSkinTex = tex;
                    RevivalPVPMod.LOGGER.info("[skin] resolved {} -> {}", uuid, tex);
                } catch (Throwable t) {
                    RevivalPVPMod.LOGGER.warn("[skin] body().texturePath() threw: {}", t.toString());
                }
            }));
        return null;
    }

    /** Detail-panel wrapper: tracks the currently-open uuid so async fetch
     *  callbacks can update detailSkinTex when the active player matches. */
    private void loadDetailSkinIfNeeded(String uuid, String username) {
        if (uuid == null) return;
        detailSkinUuid = uuid;
        net.minecraft.resources.ResourceLocation hit = prefetchSkinIfNeeded(uuid, username);
        if (hit != null) detailSkinTex = hit;
    }

    private static final java.net.http.HttpClient SKIN_HTTP =
        java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .build();
    private static final com.google.gson.Gson SKIN_GSON = new com.google.gson.Gson();

    /**
     * Direct GET to Mojang's session server for the player's profile + signed
     * textures property. Returns a GameProfile with the textures property
     * attached so SkinManager can resolve a real skin URL. On any failure
     * (network, rate limit, malformed response) returns the barebones profile
     * so the caller still gets the default Steve fallback.
     *
     * Endpoint: GET https://sessionserver.mojang.com/session/minecraft/profile/{uuid}?unsigned=false
     * Response shape:
     *   { "id": "...", "name": "...", "properties": [
     *       { "name": "textures", "value": "<base64>", "signature": "..." }
     *   ] }
     */
    private static com.mojang.authlib.GameProfile fetchMojangProfile(
            java.util.UUID uuid, String username, com.mojang.authlib.GameProfile fallback) {
        try {
            // Step 1: try the session-server fetch with the uuid we got from
            // the leaderboard. Returns 204 No Content for offline-mode UUIDs
            // (the backend currently hashes "OfflinePlayer:{name}" instead of
            // recording the real Mojang UUID — fix in v1.1). When that happens
            // we fall back to a username-based lookup against api.mojang.com
            // to recover the real UUID, then redo the session-server call.
            java.util.UUID effectiveUuid = uuid;
            String url = "https://sessionserver.mojang.com/session/minecraft/profile/"
                + effectiveUuid.toString().replace("-", "") + "?unsigned=false";
            var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(8))
                .GET().build();
            var resp = SKIN_HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 204 && username != null && !username.isBlank()) {
                RevivalPVPMod.LOGGER.info("[skin] uuid lookup 204 (likely offline-mode UUID); retrying by username {}", username);
                String nameUrl = "https://api.mojang.com/users/profiles/minecraft/"
                    + java.net.URLEncoder.encode(username, java.nio.charset.StandardCharsets.UTF_8);
                var nameReq = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(nameUrl))
                    .timeout(java.time.Duration.ofSeconds(8))
                    .GET().build();
                var nameResp = SKIN_HTTP.send(nameReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (nameResp.statusCode() == 200) {
                    var nameJson = SKIN_GSON.fromJson(nameResp.body(), com.google.gson.JsonObject.class);
                    if (nameJson != null && nameJson.has("id")) {
                        String hex = nameJson.get("id").getAsString();
                        // Mojang returns the uuid as 32-char hex with no dashes; reinsert.
                        if (hex.length() == 32) {
                            String dashed = hex.substring(0, 8)  + "-" + hex.substring(8, 12)
                                + "-" + hex.substring(12, 16) + "-" + hex.substring(16, 20)
                                + "-" + hex.substring(20);
                            try {
                                effectiveUuid = java.util.UUID.fromString(dashed);
                                url = "https://sessionserver.mojang.com/session/minecraft/profile/"
                                    + hex + "?unsigned=false";
                                req = java.net.http.HttpRequest.newBuilder()
                                    .uri(java.net.URI.create(url))
                                    .timeout(java.time.Duration.ofSeconds(8))
                                    .GET().build();
                                resp = SKIN_HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                                RevivalPVPMod.LOGGER.info("[skin] resolved real uuid {} for {}", dashed, username);
                            } catch (Exception ignored) { /* fall through */ }
                        }
                    }
                } else {
                    RevivalPVPMod.LOGGER.warn("[skin] username lookup returned HTTP {}", nameResp.statusCode());
                }
            }

            if (resp.statusCode() != 200) {
                RevivalPVPMod.LOGGER.warn("[skin] Mojang profile API returned HTTP {}", resp.statusCode());
                return fallback;
            }
            com.google.gson.JsonObject json = SKIN_GSON.fromJson(resp.body(), com.google.gson.JsonObject.class);
            String resolvedName = json.has("name") ? json.get("name").getAsString() : username;
            // 1.21.7 ships older authlib — PropertyMap is a no-arg constructor
            // and GameProfile only takes (UUID, String). Properties are set via
            // profile.getProperties().put(k, v).
            com.mojang.authlib.GameProfile profile =
                new com.mojang.authlib.GameProfile(uuid, resolvedName);
            int propCount = 0;
            if (json.has("properties") && json.get("properties").isJsonArray()) {
                var arr = json.getAsJsonArray("properties");
                for (var el : arr) {
                    if (!el.isJsonObject()) continue;
                    var p = el.getAsJsonObject();
                    String n = p.has("name") ? p.get("name").getAsString() : null;
                    String v = p.has("value") ? p.get("value").getAsString() : null;
                    String s = p.has("signature") && !p.get("signature").isJsonNull()
                        ? p.get("signature").getAsString() : null;
                    if (n == null || v == null) continue;
                    profile.getProperties().put(n, new com.mojang.authlib.properties.Property(n, v, s));
                    propCount++;
                }
            }
            RevivalPVPMod.LOGGER.info("[skin] Mojang HTTP got {} properties for {}", propCount, uuid);
            return profile;
        } catch (Throwable t) {
            RevivalPVPMod.LOGGER.warn("[skin] Mojang HTTP failed: {}", t.toString());
            return fallback;
        }
    }

    private void openPlayerDetail(JsonObject entry) {
        playerDetailEntry   = entry;
        playerDetailProfile = null;
        playerDetailLoading = false;
        // Don't wipe skin state — skinTextureCache is keyed by uuid and will
        // either return a cached ResourceLocation instantly or kick off a single
        // dedup'd fetch. Just reset the scroll position for the new view.
        detailSponsorScroll = 0;
    }

    private void closePlayerDetail() {
        playerDetailEntry   = null;
        playerDetailProfile = null;
    }

    private static boolean hit(int[] rect, double mx, double my) {
        return rect != null && mx >= rect[0] && mx < rect[2] && my >= rect[1] && my < rect[3];
    }

    private void trySponsor(int coins) {
        if (playerDetailEntry == null) return;
        String uuid = optStrEntry(playerDetailEntry, "uuid", null);
        if (uuid == null) return;
        if (coinBalance < coins) {
            // Surface a transient "not enough coins" notice with the gap
            // between current balance and the tier — the next render will
            // also flip the hint line into a "click to buy more" prompt.
            int need = coins - Math.max(0, coinBalance);
            lowBalanceMsg = "Need " + need + " more coin" + (need == 1 ? "" : "s")
                + " for ♥" + coins + ".";
            lowBalanceMsgUntilMs = System.currentTimeMillis() + 6000;
            return;
        }

        BackendHttpClient.sponsorPlayer(uuid, coins).thenAccept(resp ->
            Minecraft.getInstance().execute(() -> {
                if (resp != null && resp.has("balance")) {
                    coinBalance = resp.get("balance").getAsInt();
                    // Reload sponsor profile to show the new totals.
                    playerDetailProfile = null;
                    playerDetailLoading = false;
                } else {
                    invalidateCoinBalance();
                }
            }));
    }

    private void renderToggleBtn(GuiGraphics g, int x, int y, String label, boolean active, int mx, int my) {
        int bg = active ? 0xFF1A1A2E : 0xFF0E0E18;
        int border = active ? BORDER_COLOR : 0xFF2A2A3A;
        g.fill(x - 1, y - 1, x + 79, y + 19, border);
        g.fill(x, y, x + 78, y + 18, bg);
        g.drawCenteredString(font, Component.literal(label), x + 39, y + 5, active ? BORDER_COLOR : TEXT_MUTED);
    }

    /** Smaller pill button for the scope picker (Local/Region/Global), 48px wide. */
    private void renderScopeBtn(GuiGraphics g, int x, int y, String label, String scopeValue, int mx, int my) {
        boolean active = scopeValue.equals(this.scope);
        int bg = active ? 0xFF1A1A2E : 0xFF0E0E18;
        int border = active ? BORDER_COLOR : 0xFF2A2A3A;
        g.fill(x - 1, y - 1, x + 49, y + 19, border);
        g.fill(x, y, x + 48, y + 18, bg);
        g.drawCenteredString(font, Component.literal(label), x + 24, y + 5, active ? BORDER_COLOR : TEXT_MUTED);
    }

    // ---------------- Mouse / keyboard ----------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double mx = mouseX;
        double my = mouseY;

        int w = width, h = height;
        int panelW = Math.min(w - 40, 700);
        int panelH = Math.min(h - 40, 480);
        int px = (w - panelW) / 2;
        int py = (h - panelH) / 2;

        // ── (i) info icon → InfoScreen ───────────────────────────────────────
        if (infoIconRect != null && hit(infoIconRect, mx, my)) {
            Minecraft.getInstance().setScreen(new InfoScreen(this));
            return true;
        }

        // ── Invite overlay click (top-priority, paints over everything) ─────
        if (inviteAcceptRect != null && hit(inviteAcceptRect, mx, my)) {
            FriendsService f = friendsService();
            if (f != null) f.acceptIncomingInvite();
            return true;
        }
        if (inviteDeclineRect != null && hit(inviteDeclineRect, mx, my)) {
            FriendsService f = friendsService();
            if (f != null) f.declineIncomingInvite();
            return true;
        }

        // ── Coin balance pill click → jump to Leaderboard ────────────────────
        if (coinPillRect != null && hit(coinPillRect, mx, my)) {
            activeTab = Tab.LEADERBOARD;
            closePlayerDetail();
            return true;
        }

        // Tab clicks
        int tabW = panelW / Tab.values().length;
        int ty = py + 24;
        if (my >= ty && my < ty + 18) {
            int col = (int)(mx - px) / tabW;
            if (col >= 0 && col < Tab.values().length) {
                activeTab = Tab.values()[col];
                if (activeTab != Tab.LEADERBOARD) closePlayerDetail();
                return true;
            }
        }

        // ── Friends tab clicks ───────────────────────────────────────────────
        if (activeTab == Tab.FRIENDS) {
            for (int[] r : friendsHitRects) {
                if (mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3]) {
                    int kind = r[4];
                    int idx  = r[5];
                    FriendsService f = friendsService();
                    if (f == null) return true;
                    switch (kind) {
                        case 0 -> {  // invite
                            var list = f.friends();
                            if (idx >= 0 && idx < list.size()) {
                                var fr = list.get(idx);
                                if (!fr.canInvite()) {
                                    String state = fr.onlineState() == null ? "offline" : fr.onlineState();
                                    setFriendsToast("§e" + fr.username() + " is " + state
                                        + ", try again when they're online.", 0xFFCCCC44);
                                } else {
                                    f.invite(fr.uuid(), selectedKit.name(), ranked);
                                    setFriendsToast("§a✓ Invited " + fr.username()
                                        + " (" + selectedKit.name() + (ranked ? " ranked" : "") + ")",
                                        0xFF66FF99);
                                }
                            }
                        }
                        case 1 -> f.accept(idx);                    // accept request
                        case 2 -> f.reject(idx);                    // reject request
                        case 3 -> {                                    // unfriend (two-step)
                            var list = f.friends();
                            if (idx >= 0 && idx < list.size()) {
                                var fr = list.get(idx);
                                long now = System.currentTimeMillis();
                                boolean armed = fr.uuid().equals(unfriendArmedUuid)
                                    && now - unfriendArmedAt < UNFRIEND_CONFIRM_MS;
                                if (armed) {
                                    f.unfriend(fr.uuid());
                                    unfriendArmedUuid = null;
                                    setFriendsToast("§c✕ Removed " + fr.username() + " from your friends.",
                                        0xFFFF6666);
                                } else {
                                    unfriendArmedUuid = fr.uuid();
                                    unfriendArmedAt   = now;
                                    setFriendsToast("§e⚠ Click again within 3s to remove "
                                        + fr.username() + ".", 0xFFCCCC44);
                                }
                            }
                        }
                        case 4 -> Minecraft.getInstance().setScreen(new AddFriendModal(this, f));
                        case 5 -> pendingExpanded = !pendingExpanded;
                        case 6 -> {                                  // manual refresh
                            friendsLastAutoRefreshAt = System.currentTimeMillis();
                            f.refresh();
                            setFriendsToast("§b⟳ Refreshing friends list...", 0xFF66E5FF);
                        }
                    }
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // ── Player detail sub-view clicks ────────────────────────────────────
        if (activeTab == Tab.LEADERBOARD && playerDetailEntry != null) {
            if (detailBackRect != null && hit(detailBackRect, mx, my)) {
                closePlayerDetail();
                return true;
            }
            if (detailSponsor10Rect != null && hit(detailSponsor10Rect, mx, my)) {
                trySponsor(10);
                return true;
            }
            if (detailSponsor100Rect != null && hit(detailSponsor100Rect, mx, my)) {
                trySponsor(100);
                return true;
            }
            if (detailSponsor500Rect != null && hit(detailSponsor500Rect, mx, my)) {
                trySponsor(500);
                return true;
            }
            if (detailBuyCoinsRect != null && hit(detailBuyCoinsRect, mx, my)) {
                // Open the sponsor coin store in the system browser.
                net.minecraft.Util.getPlatform().openUri("https://revivalpvp.net/pvp/store");
                lowBalanceMsg        = null;
                lowBalanceMsgUntilMs = 0;
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // ── Leaderboard scope pills → switch the active scope + refetch ──
        if (activeTab == Tab.LEADERBOARD && playerDetailEntry == null
            && !lbScopeRects.isEmpty()) {
            for (int i = 0; i < lbScopeRects.size(); i++) {
                int[] r = lbScopeRects.get(i);
                if (mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3]) {
                    String chosen = lbScopeIds.get(i);
                    if (!chosen.equals(leaderboardScope)) {
                        leaderboardScope = chosen;
                        // Drop cache so next render kicks off a fresh fetch.
                        leaderboardEntries     = null;
                        leaderboardScopeLoaded = null;
                    }
                    return true;
                }
            }
        }

        // ── Leaderboard list rows → open player detail (kit modes only) ──
        // Sponsors mode rows don't have rank/division/LP, so the player-
        // detail screen would render half-empty. Disable click-through there.
        // Hit-rect math reads lbBodyY captured by the renderer rather than
        // hardcoding y-offsets, the pill row may wrap to two lines on narrow
        // panels and previously the click target drifted off the actual rows.
        if (activeTab == Tab.LEADERBOARD && leaderboardEntries != null && playerDetailEntry == null
            && !"sponsors".equals(leaderboardScope) && lbMaxRows > 0) {
            int rowX = px + 16;
            int n = Math.min(leaderboardEntries.size(), lbMaxRows);
            for (int i = 0; i < n; i++) {
                int ry = lbBodyY + 14 + i * LB_ROW_H;
                if (mx >= rowX && mx < rowX + panelW - 32 && my >= ry && my < ry + LB_ROW_H) {
                    openPlayerDetail(leaderboardEntries.get(i).getAsJsonObject());
                    return true;
                }
            }
        }

        // Matches sub-tab clicks (Live | History) + Self/Everyone toggle
        if (activeTab == Tab.MATCHES && my >= matchesSubBandY && my < matchesSubBandY + 18) {
            // Live Duels pill, width 88, x = px+16
            if (mx >= px + 16 && mx < px + 16 + 88) {
                matchesSub = MatchesSub.LIVE;
                return true;
            }
            // History pill, width 60, x = px+16+92
            if (mx >= px + 16 + 92 && mx < px + 16 + 92 + 60) {
                matchesSub = MatchesSub.HISTORY;
                return true;
            }
            // History scope toggle, only when on History sub-tab
            if (matchesSub == MatchesSub.HISTORY) {
                int selfX     = px + panelW - 16 - 80 - 4 - 70;
                int everyoneX = px + panelW - 16 - 80;
                if (mx >= selfX && mx < selfX + 70) {
                    historyScope = HistoryScope.SELF;
                    return true;
                }
                if (mx >= everyoneX && mx < everyoneX + 80) {
                    historyScope = HistoryScope.EVERYONE;
                    return true;
                }
            }
        }

        // ── Live Duels [SPECTATE] click + Refresh ────────────────────────────
        if (activeTab == Tab.MATCHES && matchesSub == MatchesSub.LIVE) {
            // Refresh button
            if (mx >= liveRefreshX && mx < liveRefreshX + liveRefreshW
                && my >= liveRefreshY && my < liveRefreshY + liveRefreshH) {
                if (System.currentTimeMillis() - liveDuelsFetchedAt > 1000) {
                    liveDuels = null;          // force refetch
                    liveDuelsFetchedAt = 0L;
                    loadLiveDuelsIfNeeded();
                }
                return true;
            }
            for (int i = 0; i < spectateRowRects.size(); i++) {
                int[] r = spectateRowRects.get(i);
                if (mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3]) {
                    String matchId = i < spectateMatchIds.size() ? spectateMatchIds.get(i) : null;
                    if (matchId != null) startSpectate(matchId);
                    return true;
                }
            }
        }

        if (activeTab == Tab.QUEUE) {
            int contentY = py + 44;
            int contentH = panelH - 44;

            // Same layout math as renderQueueTab, keep these in sync.
            int viewerX = px + panelW - VIEWER_W - 8;
            int leftAreaX = px + 8;
            int leftAreaW = viewerX - VIEWER_GAP - leftAreaX;

            // Ranked / unranked toggle + scope picker (must match renderQueueTab math)
            int toggleX = Math.max(leftAreaX + 4, leftAreaX + leftAreaW / 2 - 165);
            int toggleY = contentY + 8;
            if (my >= toggleY && my < toggleY + 18) {
                if (mx >= toggleX && mx < toggleX + 79) { ranked = true; return true; }
                if (mx >= toggleX + 82 && mx < toggleX + 161) { ranked = false; return true; }
                int scopeX = toggleX + 82 + 84;
                if (mx >= scopeX && mx < scopeX + 49)             { scope = "local";  return true; }
                if (mx >= scopeX + 50 && mx < scopeX + 50 + 49)   { scope = "region"; return true; }
                if (mx >= scopeX + 100 && mx < scopeX + 100 + 49) { scope = "global"; return true; }
            }

            // Map picker click handling (only active in MATCH_FOUND state).
            // Kit list / loadout aren't rendered during MATCH_FOUND, so we
            // only need to handle map-card clicks; clicks elsewhere fall
            // through to the queue-button / 3D-viewer logic below.
            if (matchmaking.state() == MatchmakingService.State.MATCH_FOUND
                && mapCardCount > 0 && mapCardH > 0) {
                int gap    = 6;
                int topPad = 40;
                int areaTop = mapPickerY + topPad;
                int areaBot = mapPickerY + mapPickerH - 8;
                int areaH   = areaBot - areaTop;
                int mapContentH = mapCardCount * mapCardH + (mapCardCount - 1) * gap;
                int maxScroll = Math.max(0, mapContentH - areaH);

                // Scrollbar click → jump-scroll. Track lives at x = mapPickerX
                // + mapPickerW - 5, width 3, height = areaH. Click position
                // within the track maps proportionally to the scroll range.
                if (maxScroll > 0) {
                    int trackX = mapPickerX + mapPickerW - 5;
                    if (mx >= trackX - 2 && mx < trackX + 5
                        && my >= areaTop && my < areaBot) {
                        double frac = (my - areaTop) / (double) areaH;
                        mapPickerScroll = (int) Math.round(frac * maxScroll);
                        if (mapPickerScroll < 0)         mapPickerScroll = 0;
                        if (mapPickerScroll > maxScroll) mapPickerScroll = maxScroll;
                        return true;
                    }
                }
                // Apply the same scroll offset render uses; gate on the
                // scroll area so clicks outside the visible window don't
                // register cards that are scrolled out of sight.
                for (int i = 0; i < mapCardCount; i++) {
                    int cx = mapPickerX + 8;
                    int cy = areaTop + i * (mapCardH + gap) - mapPickerScroll;
                    int cw = mapPickerW - 16;
                    if (cy + mapCardH < areaTop || cy > areaBot) continue;
                    if (mx >= cx && mx < cx + cw
                        && my >= Math.max(cy, areaTop)
                        && my <  Math.min(cy + mapCardH, areaBot)) {
                        var maps = matchmaking.availableMaps();
                        if (i < maps.size()) {
                            matchmaking.voteMap(maps.get(i).id());
                            return true;
                        }
                    }
                }
            }

            // Kit list / loadout clicks are skipped during MATCH_FOUND so the
            // map picker has exclusive control over the left column area.
            boolean inMatchFound = matchmaking.state() == MatchmakingService.State.MATCH_FOUND;

            // Kit list scroll arrows + card clicks (shifted +18px for the placement pill)
            int kitListY = contentY + 60;
            int kitListX = leftAreaX + 24;
            int kitListW = leftAreaW - 48;
            int kitListH = KIT_CARD_H;

            // Left arrow
            if (!inMatchFound && mx >= kitListX - 18 - 1 && mx < kitListX - 18 + 17 &&
                my >= kitListY + kitListH / 2 - 8 - 1 && my < kitListY + kitListH / 2 - 8 + 17) {
                kitScroll -= KIT_CARD_W + KIT_CARD_GAP;
                if (kitScroll < 0) kitScroll = 0;
                return true;
            }
            // Right arrow
            if (!inMatchFound && mx >= kitListX + kitListW + 2 - 1 && mx < kitListX + kitListW + 2 + 17 &&
                my >= kitListY + kitListH / 2 - 8 - 1 && my < kitListY + kitListH / 2 - 8 + 17) {
                kitScroll += KIT_CARD_W + KIT_CARD_GAP;
                Kit[] kits = Kit.values();
                int totalContentW = kits.length * KIT_CARD_W + (kits.length - 1) * KIT_CARD_GAP;
                int maxScroll = Math.max(0, totalContentW - kitListW);
                if (kitScroll > maxScroll) kitScroll = maxScroll;
                return true;
            }

            // Kit card clicks (only if click is inside the list rect)
            if (!inMatchFound && mx >= kitListX && mx < kitListX + kitListW && my >= kitListY && my < kitListY + kitListH) {
                Kit[] kits = Kit.values();
                for (int i = 0; i < kits.length; i++) {
                    int cx = kitListX + i * (KIT_CARD_W + KIT_CARD_GAP) - kitScroll;
                    if (mx >= cx && mx < cx + KIT_CARD_W && my >= kitListY && my < kitListY + KIT_CARD_H) {
                        if (selectedKit != kits[i]) loadoutScrollY = 0;
                        selectedKit = kits[i];
                        return true;
                    }
                }
            }

            // Loadout editor row clicks → open VariantPickerScreen for that slot.
            if (!inMatchFound) for (var entry : loadoutRowRects.entrySet()) {
                int[] r = entry.getValue();
                if (mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3]) {
                    String slot = entry.getKey();
                    JsonObject loadout = loadoutByKit.get(selectedKit);
                    if (loadout != null && loadout.has("by_slot")
                        && loadout.getAsJsonObject("by_slot").has(slot)) {
                        JsonArray variants = loadout.getAsJsonObject("by_slot").getAsJsonArray(slot);
                        JsonObject curr = resolveSelectedVariant(loadout, slot);
                        int currId = (curr != null && curr.has("id")) ? curr.get("id").getAsInt() : -1;
                        Kit kitForCb = selectedKit;
                        Minecraft.getInstance().setScreen(new VariantPickerScreen(
                            this, slot, variants, currId,
                            picked -> onVariantPicked(kitForCb, slot, picked)
                        ));
                        return true;
                    }
                }
            }

            // Queue / accept button (centered in right column below the viewer)
            int rightBtnH2 = 36;
            int viewerH2 = panelH - 44 /*tab bar*/ - 8 /*top pad*/ - 8 /*bot pad of viewer*/ - rightBtnH2;
            // Recompute with same math as renderQueueTab:
            int viewerY2 = py + 44 + 8;
            int viewerH3 = (panelH - 44) - 16 - rightBtnH2;
            int btnX = viewerX + (VIEWER_W - 110) / 2;
            int btnY = viewerY2 + viewerH3 + 7;
            if (mx >= btnX && mx < btnX + 110 && my >= btnY && my < btnY + 22) {
                MatchmakingService.State st = matchmaking.state();
                if (st == MatchmakingService.State.IDLE) {
                    matchmaking.joinQueue(selectedKit, ranked, scope);
                } else if (st == MatchmakingService.State.QUEUING) {
                    // Click on the running timer cancels the queue. ESC alone
                    // just closes the panel and lets the queue keep running.
                    matchmaking.leaveQueue();
                } else if (st == MatchmakingService.State.MATCH_FOUND) {
                    // Click ACCEPT manually before auto-accept fires.
                    matchmaking.acceptMatch();
                }
                // CONNECTING / IN_DUEL: no-op (prevents double-click).
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        // Sponsor list scroll on the player-detail panel takes priority, it
        // sits inside the LEADERBOARD tab and we want wheel events over the
        // sponsor list to scroll the list, not whatever else might be below.
        if (activeTab == Tab.LEADERBOARD && playerDetailEntry != null
            && detailSponsorListRect != null
            && mx >= detailSponsorListRect[0] && mx < detailSponsorListRect[2]
            && my >= detailSponsorListRect[1] && my < detailSponsorListRect[3]
            && detailSponsorTotal > detailSponsorVisible) {
            int delta = (int) Math.signum(sy);
            detailSponsorScroll -= delta;   // wheel up = scroll up = lower index
            int maxScroll = Math.max(0, detailSponsorTotal - detailSponsorVisible);
            if (detailSponsorScroll < 0) detailSponsorScroll = 0;
            if (detailSponsorScroll > maxScroll) detailSponsorScroll = maxScroll;
            return true;
        }

        if (activeTab == Tab.QUEUE) {
            boolean inMatchFound = matchmaking.state() == MatchmakingService.State.MATCH_FOUND;

            // Map picker has scroll priority while MATCH_FOUND is active,
            // it occupies the same area the kit list + loadout panel would
            // otherwise use, and those handlers were swallowing wheel
            // events with return-true before this one could see them.
            if (inMatchFound && mapCardCount > 0
                && mx >= mapPickerX && mx < mapPickerX + mapPickerW
                && my >= mapPickerY && my < mapPickerY + mapPickerH) {
                mapPickerScroll -= (int) (sy * (mapCardH + 6));   // ~one card per wheel notch
                if (mapPickerScroll < 0) mapPickerScroll = 0;
                return true;
            }

            // Kit list + loadout scroll only when NOT in match-found state
            // (they're not visually rendered then; firing return-true would
            // just consume the wheel).
            if (inMatchFound) return super.mouseScrolled(mx, my, sx, sy);

            int w = width, h = height;
            int panelW = Math.min(w - 40, 700);
            int panelH = Math.min(h - 40, 480);
            int px = (w - panelW) / 2;
            int py = (h - panelH) / 2;
            int contentY = py + 44;
            int kitListY = contentY + 60;
            int viewerX = px + panelW - VIEWER_W - 8;
            int leftAreaX = px + 8;
            int leftAreaW = viewerX - VIEWER_GAP - leftAreaX;
            int kitListX = leftAreaX + 24;
            int kitListW = leftAreaW - 48;
            int kitListH = KIT_CARD_H;
            if (mx >= kitListX && mx < kitListX + kitListW && my >= kitListY && my < kitListY + kitListH) {
                double delta = (sy != 0 ? sy : sx);
                kitScroll -= (int) (delta * (KIT_CARD_W + KIT_CARD_GAP) / 2);
                if (kitScroll < 0) kitScroll = 0;
                Kit[] kits = Kit.values();
                int totalContentW = kits.length * KIT_CARD_W + (kits.length - 1) * KIT_CARD_GAP;
                int maxScroll = Math.max(0, totalContentW - kitListW);
                if (kitScroll > maxScroll) kitScroll = maxScroll;
                return true;
            }

            if (mx >= loadoutPanelX && mx < loadoutPanelX + loadoutPanelW
                && my >= loadoutPanelY && my < loadoutPanelY + loadoutPanelH) {
                int rowsAvail = loadoutRowsBottom - loadoutRowsTop;
                int maxScroll = Math.max(0, loadoutRowsTotalH - rowsAvail);
                if (maxScroll > 0) {
                    loadoutScrollY -= (int) (sy * 18);
                    if (loadoutScrollY < 0) loadoutScrollY = 0;
                    if (loadoutScrollY > maxScroll) loadoutScrollY = maxScroll;
                }
                return true;
            }
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            // ESC always closes the panel, the queue keeps running in the
            // background so the player can keep playing on their SMP while
            // waiting. To cancel the queue, click the timer pill.
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        // Force-flush any debounced save before tearing down so we don't drop edits.
        if (pendingSaveAt != 0L && pendingSaveKit != null) {
            pendingSaveAt = 0L;
            // Trigger the save immediately.
            Kit kit = pendingSaveKit;
            pendingSaveKit = null;
            Map<String, Integer> map = selectedVariantId.get(kit);
            if (map != null && !map.isEmpty()) {
                JsonArray selections = new JsonArray();
                for (var e : map.entrySet()) {
                    JsonObject sel = new JsonObject();
                    sel.addProperty("slot", e.getKey());
                    sel.addProperty("variant_id", e.getValue());
                    selections.add(sel);
                }
                BackendHttpClient.setLoadout(kit.name(), selections);
            }
        }
        loadoutRenderer.close();
        super.removed();
    }
}