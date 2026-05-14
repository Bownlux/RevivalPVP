// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.revivalsmp.pvp.kit.VariantItemFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Modal variant picker. Displayed when the user clicks a slot row in the
 * Loadout Editor. Renders a card grid of available variants for that slot:
 * each card shows item icon, name (color-coded by balance_class), description,
 * and a "Selected" border on the currently-active variant.
 *
 * Esc closes without saving (returns to parent screen).
 * Clicking a card invokes the callback with that variant's id and closes.
 */
public class VariantPickerScreen extends Screen {

    private static final int BG_OVERLAY    = 0xCC000000;
    private static final int PANEL_COLOR   = 0xFF12121A;
    private static final int BORDER_COLOR  = 0xFF00D4FF;
    private static final int CARD_BG       = 0xFF14141F;
    private static final int CARD_BG_HOVER = 0xFF1C1C2A;
    private static final int CARD_BG_SEL   = 0xFF1C2C3A;
    private static final int CARD_BORDER   = 0xFF2A2A3A;
    private static final int CARD_BORDER_HOVER = 0xFF4A4A6A;
    private static final int TEXT_PRIMARY  = 0xFFE0E0FF;
    private static final int TEXT_MUTED    = 0xFF7070A0;

    // Card layout
    private static final int CARD_W      = 160;
    private static final int CARD_H      = 70;
    private static final int CARD_GAP    = 8;
    private static final int CARDS_PER_ROW = 3;
    private static final int PANEL_PADDING = 16;

    private final Screen parent;
    private final String slot;
    private final List<JsonObject> variants;
    private final int currentVariantId;
    private final IntConsumer onPicked;

    /** Resolved ItemStack icons for each variant index (cached). */
    private final List<ItemStack> icons = new ArrayList<>();

    public VariantPickerScreen(Screen parent, String slot, JsonArray variants,
                               int currentVariantId, IntConsumer onPicked) {
        super(Component.literal("Variant Picker, " + slot));
        this.parent = parent;
        this.slot = slot;
        this.variants = new ArrayList<>();
        if (variants != null) {
            for (JsonElement el : variants) {
                if (el != null && el.isJsonObject()) this.variants.add(el.getAsJsonObject());
            }
        }
        for (JsonObject v : this.variants) {
            JsonObject item = v.has("item") && v.get("item").isJsonObject() ? v.getAsJsonObject("item") : null;
            this.icons.add(VariantItemFactory.fromJson(item));
        }
        this.currentVariantId = currentVariantId;
        this.onPicked = onPicked;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    /** Total panel rect computed once per frame so render & click handlers stay in sync. */
    private int panelX, panelY, panelW, panelH;

    private void recomputeLayout() {
        // Panel sized to fit the grid plus padding + header.
        int rows = Math.max(1, (variants.size() + CARDS_PER_ROW - 1) / CARDS_PER_ROW);
        panelW = CARDS_PER_ROW * CARD_W + (CARDS_PER_ROW - 1) * CARD_GAP + PANEL_PADDING * 2;
        panelH = rows * CARD_H + (rows - 1) * CARD_GAP + PANEL_PADDING * 2 + 28; // 28px header
        if (panelW > width - 20) panelW = width - 20;
        if (panelH > height - 20) panelH = height - 20;
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        recomputeLayout();

        // Dim background (full screen)
        g.fill(0, 0, width, height, BG_OVERLAY);

        // Modal panel
        g.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY + panelH + 1, BORDER_COLOR);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL_COLOR);

        // Header
        String title = "§b§lChoose Variant, §f" + slot;
        g.drawString(font, Component.literal(title), panelX + PANEL_PADDING, panelY + 10, TEXT_PRIMARY, false);
        g.drawString(font, "§8[Esc] cancel", panelX + panelW - PANEL_PADDING - 70, panelY + 10, TEXT_MUTED, false);

        // Grid
        int gridX = panelX + PANEL_PADDING;
        int gridY = panelY + PANEL_PADDING + 22;
        for (int i = 0; i < variants.size(); i++) {
            int row = i / CARDS_PER_ROW;
            int col = i % CARDS_PER_ROW;
            int cx = gridX + col * (CARD_W + CARD_GAP);
            int cy = gridY + row * (CARD_H + CARD_GAP);
            renderCard(g, i, cx, cy, mx, my);
        }

        super.render(g, mx, my, delta);
    }

    private void renderCard(GuiGraphics g, int index, int x, int y, int mx, int my) {
        JsonObject v = variants.get(index);
        boolean selected = v.has("id") && v.get("id").getAsInt() == currentVariantId;
        boolean hover = mx >= x && mx < x + CARD_W && my >= y && my < y + CARD_H;

        int border = selected ? BORDER_COLOR : (hover ? CARD_BORDER_HOVER : CARD_BORDER);
        int bg     = selected ? CARD_BG_SEL : (hover ? CARD_BG_HOVER : CARD_BG);
        g.fill(x - 1, y - 1, x + CARD_W + 1, y + CARD_H + 1, border);
        g.fill(x, y, x + CARD_W, y + CARD_H, bg);

        // Icon (16x16 top-left with padding)
        ItemStack icon = icons.get(index);
        int iconX = x + 8;
        int iconY = y + 8;
        if (icon != null && !icon.isEmpty()) {
            g.renderItem(icon, iconX, iconY);
        } else {
            g.fill(iconX, iconY, iconX + 16, iconY + 16, 0xFF2A2A3A);
        }

        // Name, color-coded by balance_class
        String name = v.has("name") && !v.get("name").isJsonNull() ? v.get("name").getAsString() : "(unnamed)";
        String balance = v.has("balance_class") && !v.get("balance_class").isJsonNull()
            ? v.get("balance_class").getAsString().toLowerCase() : "";
        String colorPrefix = switch (balance) {
            case "defensive" -> "§b";
            case "offensive" -> "§c";
            case "utility"   -> "§e";
            case "mobility"  -> "§a";
            default          -> selected ? "§b" : "§f";
        };
        // Strip any leading legacy color codes from raw name (server may send §7-prefixed names),
        // then apply our balance-class color.
        String cleanName = stripLegacyColors(name);
        int textX = iconX + 22;
        int textY = y + 10;
        // Trim the name to avoid overflowing the card.
        int maxNameW = CARD_W - (textX - x) - 8;
        Component nameC = Component.literal(colorPrefix + (selected ? "§l" : "") + cleanName);
        // font.split for clipping, just render first line.
        var nameLines = font.split(nameC, maxNameW);
        if (!nameLines.isEmpty()) {
            g.drawString(font, nameLines.get(0), textX, textY, TEXT_PRIMARY);
        }

        // Description (wrapped, multi-line, clipped to remaining card height)
        String desc = v.has("description") && !v.get("description").isJsonNull()
            ? v.get("description").getAsString() : "";
        int descY = y + 30;
        int descMaxY = y + CARD_H - 6;
        int lineHeight = 9;
        int maxLines = (descMaxY - descY) / lineHeight;
        int drawn = 0;
        for (var line : font.split(Component.literal("§7" + desc), CARD_W - 16)) {
            if (drawn >= maxLines) break;
            g.drawString(font, line, x + 8, descY + drawn * lineHeight, TEXT_MUTED);
            drawn++;
        }

        // Selected badge top-right
        if (selected) {
            g.drawString(font, "§b§l✓", x + CARD_W - 14, y + 6, BORDER_COLOR, false);
        }
    }

    private static String stripLegacyColors(String s) {
        if (s == null) return "";
        // Drop leading §X color/style codes; keep the rest as-is.
        int i = 0;
        while (i + 1 < s.length() && s.charAt(i) == '§') i += 2;
        return s.substring(i);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();

        // Outside the panel → close (matches "tap-out" UX).
        if (mx < panelX || mx > panelX + panelW || my < panelY || my > panelY + panelH) {
            onClose();
            return true;
        }

        int gridX = panelX + PANEL_PADDING;
        int gridY = panelY + PANEL_PADDING + 22;
        for (int i = 0; i < variants.size(); i++) {
            int row = i / CARDS_PER_ROW;
            int col = i % CARDS_PER_ROW;
            int cx = gridX + col * (CARD_W + CARD_GAP);
            int cy = gridY + row * (CARD_H + CARD_GAP);
            if (mx >= cx && mx < cx + CARD_W && my >= cy && my < cy + CARD_H) {
                JsonObject v = variants.get(i);
                if (v.has("id")) {
                    int picked = v.get("id").getAsInt();
                    if (onPicked != null) onPicked.accept(picked);
                }
                onClose();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}