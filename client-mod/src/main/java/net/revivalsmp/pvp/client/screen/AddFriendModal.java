// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.client.screen;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.revivalsmp.pvp.client.ui.PVPTheme;
import net.revivalsmp.pvp.matchmaking.FriendsService;
import net.revivalsmp.pvp.network.BackendHttpClient;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Modal screen overlaying the hub for "Add Friend by username".
 * On submit, issues POST /friends/request and closes back to the hub.
 *
 * Drawn as a centered 240x100 card with backdrop dim. Submitting via Enter
 * also dispatches the request. Cancel via Esc.
 */
public class AddFriendModal extends Screen {

    private final Screen parent;
    private final FriendsService friends;
    private EditBox usernameBox;
    private String  status = "";
    private int     statusColor = PVPTheme.TEXT_MUTED;
    private boolean submitting = false;

    // Autocomplete: 250ms debounce on EditBox changes; latest suggestions below.
    private final List<String> suggestions = new ArrayList<>();
    private long  pendingFetchAt = 0L;
    private String lastFetched = "";
    /** Captured each frame for click-to-select on a suggestion row. */
    private final List<int[]> suggestionRects = new ArrayList<>();

    public AddFriendModal(Screen parent, FriendsService friends) {
        super(Component.literal("Add Friend"));
        this.parent  = parent;
        this.friends = friends;
    }

    @Override
    protected void init() {
        int cx = width / 2, cy = height / 2;
        usernameBox = new EditBox(font, cx - 100, cy - 8, 200, 18, Component.literal("Minecraft username"));
        usernameBox.setMaxLength(16);
        usernameBox.setHint(Component.literal("Type to search..."));
        usernameBox.setResponder(value -> {
            // Debounce: 250ms after last keystroke we issue a search.
            pendingFetchAt = System.currentTimeMillis() + 250L;
        });
        addRenderableWidget(usernameBox);
        // Defer focus until next frame, setInitialFocus alone doesn't reliably
        // capture keyboard input on MC 26.1, the EditBox needs an explicit
        // setFocused(true) after it's been added to the screen.
        setInitialFocus(usernameBox);
        usernameBox.setFocused(true);
        Minecraft.getInstance().execute(() -> {
            if (usernameBox != null) {
                usernameBox.setFocused(true);
                usernameBox.moveCursorToEnd(false);
            }
        });
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        // Fire any pending suggestion fetch.
        if (pendingFetchAt != 0L && System.currentTimeMillis() >= pendingFetchAt) {
            pendingFetchAt = 0L;
            fetchSuggestions(usernameBox.getValue().trim());
        }

        // Dim backdrop only.
        g.fill(0, 0, width, height, 0xCC000000);

        int cx = width / 2, cy = height / 2;
        // Card grows when suggestions are visible.
        int suggestionsH = Math.min(5, suggestions.size()) * 14;
        int pw = 240, ph = 110 + suggestionsH;
        int px = cx - pw / 2, py = cy - ph / 2;

        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, PVPTheme.BORDER);
        g.fill(px, py, px + pw, py + ph, PVPTheme.PANEL);
        g.fill(px, py, px + pw, py + 2, PVPTheme.BORDER);

        g.drawCenteredString(font, Component.literal("§b§lAdd Friend"), cx, py + 8, PVPTheme.TEXT);
        g.drawCenteredString(font, Component.literal("§7Type to search RevivalPVP players"),
            cx, py + 22, PVPTheme.TEXT_MUTED);

        // Suggestions list below the input.
        suggestionRects.clear();
        int sx = cx - 100, sy = cy + 14;
        int n = Math.min(5, suggestions.size());
        for (int i = 0; i < n; i++) {
            int ry = sy + i * 14;
            boolean hover = mx >= sx && mx < sx + 200 && my >= ry && my < ry + 13;
            g.fill(sx - 1, ry, sx + 201, ry + 13, hover ? 0xFF2A3A4A : 0xFF1F1F2C);
            g.drawString(font, Component.literal("§7• §f" + suggestions.get(i)),
                sx + 6, ry + 3, PVPTheme.TEXT, false);
            suggestionRects.add(new int[]{sx, ry, sx + 200, ry + 13});
        }

        // Submit / cancel buttons under the suggestions.
        int btnY = py + ph - 24;
        renderBtn(g, mx, my, cx - 100, btnY, 96, 18, "§a§lSEND", 0xFF1A3A1A, 0xFF00CC44);
        renderBtn(g, mx, my, cx + 4,   btnY, 96, 18, "§7Cancel",  0xFF1A1A2A, 0xFF4A4A6A);

        if (!status.isEmpty()) {
            // Status line above the buttons. Wrap to fit the card width so long
            // backend errors (e.g. "vloxnux hasn't joined RevivalPVP yet, they
            // need to /pvp queue at least once first") don't overflow.
            int sw = pw - 20;
            var lines = font.split(Component.literal(status), sw);
            int sy2 = btnY - lines.size() * 10 - 4;
            for (int i = 0; i < lines.size(); i++) {
                g.drawCenteredString(font, lines.get(i), cx, sy2 + i * 10, statusColor);
            }
        }

        super.render(g, mx, my, delta);
    }

    private void fetchSuggestions(String q) {
        if (q.length() < 2) {
            suggestions.clear();
            return;
        }
        if (q.equalsIgnoreCase(lastFetched)) return;
        lastFetched = q;
        BackendHttpClient.searchPlayers(q, 8).thenAccept(resp ->
            Minecraft.getInstance().execute(() -> {
                suggestions.clear();
                if (resp == null || !resp.has("results")) return;
                for (var el : resp.getAsJsonArray("results")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    if (o.has("username")) suggestions.add(o.get("username").getAsString());
                }
            }));
    }

    private void renderBtn(GuiGraphics g, int mx, int my, int x, int y, int w, int h,
                           String label, int bg, int border) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, hover ? PVPTheme.BORDER : border);
        g.fill(x, y, x + w, y + h, bg);
        g.drawCenteredString(font, Component.literal(label), x + w / 2, y + (h - 8) / 2, PVPTheme.TEXT);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean dbl) {
        double mx = event.x(), my = event.y();

        // Suggestion-row click → fill the input + immediately submit.
        for (int i = 0; i < suggestionRects.size(); i++) {
            int[] r = suggestionRects.get(i);
            if (mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3]) {
                String name = suggestions.get(i);
                usernameBox.setValue(name);
                suggestions.clear();
                submit();
                return true;
            }
        }

        int cx = width / 2, cy = height / 2;
        int suggestionsH = Math.min(5, suggestions.size()) * 14;
        int pw = 240, ph = 110 + suggestionsH;
        int px = cx - pw / 2, py = cy - ph / 2;
        int btnY = py + ph - 24;
        if (my >= btnY && my < btnY + 18) {
            if (mx >= cx - 100 && mx < cx - 4)        { submit();              return true; }
            if (mx >= cx + 4   && mx < cx + 100)      { close();               return true; }
        }
        return super.mouseClicked(event, dbl);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if (k == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) { submit(); return true; }
        return super.keyPressed(event);
    }

    private void submit() {
        if (submitting) return;
        String name = usernameBox.getValue().trim();
        if (name.length() < 3) {
            status = "§cUsername too short";
            statusColor = 0xFFFF6666;
            return;
        }
        submitting = true;
        status = "§7Sending...";
        statusColor = PVPTheme.TEXT_MUTED;
        friends.sendRequest(name, () -> {
            String err = friends.lastError();
            if (err != null && (System.currentTimeMillis() - friends.lastErrorAt()) < 2000) {
                status = "§c" + err;
                statusColor = 0xFFFF6666;
                friends.clearError();
                submitting = false;
            } else {
                status = "§aSent!";
                statusColor = 0xFF99FF99;
                Minecraft.getInstance().execute(this::close);
            }
        });
    }

    private void close() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override public boolean isPauseScreen() { return false; }
}