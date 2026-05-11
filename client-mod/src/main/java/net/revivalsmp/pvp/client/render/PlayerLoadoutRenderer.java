// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.client.render;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.revivalsmp.pvp.kit.Kit;
import net.revivalsmp.pvp.kit.KitLoadout;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders a 3D player figure wearing the selected kit's gear inside a GUI rectangle.
 *
 * <p>Phase 2 update: supports a per-slot {@link ItemStack} override map so the loadout
 * editor can re-equip the figure with the user's selected variants. The override is set
 * by {@link #setSelectedVariants(Kit, Map)}, slots not in the map fall back to the
 * hardcoded {@link KitLoadout} defaults.
 *
 * <p>Implementation: builds a fake {@link RemotePlayer} on the client level using the local
 * player's {@link GameProfile} so the rendered figure inherits the user's actual skin.
 */
public class PlayerLoadoutRenderer {

    private RemotePlayer fakePlayer;
    private ClientLevel cachedLevel;
    private Kit lastEquippedKit;
    /** Tracks the override map identity used in the last applyKit so we re-equip on change. */
    private Map<KitLoadout.Slot, ItemStack> lastAppliedOverrides;

    /** Per-kit override map. {@code null} means "no override, use KitLoadout defaults".
     *  Keyed by Kit since Kit is now a record (equals/hashCode by all fields, but
     *  KitRegistry returns canonical singletons so reference equality also works). */
    private final Map<Kit, Map<KitLoadout.Slot, ItemStack>> overrides = new java.util.HashMap<>();

    /** Hover regions for visible armor + held items. Updated each render. */
    private final Map<KitLoadout.Slot, int[]> hoverRects = new EnumMap<>(KitLoadout.Slot.class);

    /**
     * Replace (or clear) the override map for a kit. Call when the user picks a new variant
     * in the variant picker. Pass {@code null} to clear and fall back to defaults.
     */
    public void setSelectedVariants(Kit kit, Map<KitLoadout.Slot, ItemStack> selected) {
        if (selected == null) overrides.remove(kit);
        else overrides.put(kit, new EnumMap<>(selected));
        if (kit != null && kit.equals(lastEquippedKit)) {
            // Force a re-equip on next render.
            lastEquippedKit = null;
        }
    }

    public void render(GuiGraphicsExtractor g, Kit kit, int x, int y, int w, int h, float mouseX, float mouseY) {
        LivingEntity entity = ensureEntity();
        if (entity == null) {
            g.fill(x, y, x + w, y + h, 0xFF14141F);
            return;
        }

        Map<KitLoadout.Slot, ItemStack> ov = overrides.get(kit);
        if (kit != lastEquippedKit || ov != lastAppliedOverrides) {
            applyKit(entity, kit, ov);
            lastEquippedKit = kit;
            lastAppliedOverrides = ov;
        }

        int scale = Math.min(w, h) / 4;
        if (scale < 10) scale = 10;
        int cx = x + w / 2;
        int cy = y + h / 2;
        // Eye is roughly h/4 above panel center.
        int eyeY = cy - h / 4;

        // The vanilla helper expects raw mouseX/mouseY in screen coords; it
        // computes (cx - mouseX) / 40 internally for yaw and (cy - mouseY)/40
        // for pitch. Previously this code pre-computed those deltas and passed
        // them in, causing vanilla to double-subtract and produce garbage.
        //
        // To keep the model from twisting hard-left when the cursor sits in
        // the kit-list column, we virtualize a mouse position clamped to a
        // small box around the eye. When the cursor is far away, the model
        // renders nearly forward; when nearby, it tracks naturally.
        float maxDX = 80f, maxDY = 50f;
        float dx = mouseX - cx;
        float dy = mouseY - eyeY;
        if (dx >  maxDX) dx =  maxDX;
        if (dx < -maxDX) dx = -maxDX;
        if (dy >  maxDY) dy =  maxDY;
        if (dy < -maxDY) dy = -maxDY;
        float virtMouseX = cx + dx;
        float virtMouseY = eyeY + dy;

        InventoryScreen.extractEntityInInventoryFollowsMouse(
            g,
            x, y, x + w, y + h,
            scale,
            0.0625f,
            virtMouseX, virtMouseY,
            entity
        );

        // Recompute hover regions: split the figure rect into vertical bands for armor +
        // a lower-right band for the main hand and lower-left for the off-hand.
        hoverRects.clear();
        // Vertical thirds of the figure for HEAD / CHEST+LEGS / FEET
        int top = y + (int) (h * 0.18f);
        int chestTop = y + (int) (h * 0.32f);
        int legsTop = y + (int) (h * 0.55f);
        int feetTop = y + (int) (h * 0.78f);
        int feetBot = y + h - 4;
        int leftEdge  = x + (int) (w * 0.30f);
        int rightEdge = x + (int) (w * 0.70f);

        hoverRects.put(KitLoadout.Slot.HEAD,  new int[]{ leftEdge, top,      rightEdge, chestTop });
        hoverRects.put(KitLoadout.Slot.CHEST, new int[]{ leftEdge, chestTop, rightEdge, legsTop });
        hoverRects.put(KitLoadout.Slot.LEGS,  new int[]{ leftEdge, legsTop,  rightEdge, feetTop });
        hoverRects.put(KitLoadout.Slot.FEET,  new int[]{ leftEdge, feetTop,  rightEdge, feetBot });

        // Hands: lower-right ~main hand, lower-left ~ off hand.
        hoverRects.put(KitLoadout.Slot.MAINHAND, new int[]{
            (int) (x + w * 0.62f), (int) (y + h * 0.55f),
            (int) (x + w * 0.92f), (int) (y + h * 0.92f)
        });
        hoverRects.put(KitLoadout.Slot.OFFHAND, new int[]{
            (int) (x + w * 0.08f), (int) (y + h * 0.55f),
            (int) (x + w * 0.38f), (int) (y + h * 0.92f)
        });
    }

    /**
     * Returns a {@link HoveredItem} record describing the slot under the mouse and the
     * effective item there (override or KitLoadout default), or null if no slot is hovered.
     */
    public HoveredItem getHoveredItem(int mouseX, int mouseY) {
        if (lastEquippedKit == null) return null;
        for (var e : hoverRects.entrySet()) {
            int[] r = e.getValue();
            if (mouseX >= r[0] && mouseX < r[2] && mouseY >= r[1] && mouseY < r[3]) {
                return resolveHover(lastEquippedKit, e.getKey());
            }
        }
        return null;
    }

    /** Backwards-compat shim used by older callers that take a {@link KitLoadout.LoadoutSlot}. */
    public KitLoadout.LoadoutSlot getHoveredKitSlot(int mouseX, int mouseY) {
        HoveredItem h = getHoveredItem(mouseX, mouseY);
        if (h == null) return null;
        // Build a synthetic LoadoutSlot reflecting the override-aware state.
        ItemStack stack = h.itemStack == null ? ItemStack.EMPTY : h.itemStack;
        return new KitLoadout.LoadoutSlot(h.slot, stack, h.displayName, h.description);
    }

    /**
     * Result of a hover query. The displayName/description prefer the override variant's
     * fields; if no override is present we fall back to {@link KitLoadout} defaults.
     */
    public record HoveredItem(KitLoadout.Slot slot, ItemStack itemStack, String displayName, String description) {}

    private HoveredItem resolveHover(Kit kit, KitLoadout.Slot slot) {
        Map<KitLoadout.Slot, ItemStack> ov = overrides.get(kit);
        // Try the override first; if present and non-empty, prefer it but use KitLoadout's
        // displayName/description as a stable fallback (caller may swap for variant text).
        ItemStack overrideStack = ov != null ? ov.get(slot) : null;

        KitLoadout.LoadoutSlot defaults = KitLoadout.find(kit, slot);
        if (overrideStack != null && !overrideStack.isEmpty()) {
            String name = defaults != null ? defaults.displayName : slot.name();
            String desc = defaults != null ? defaults.description : "";
            return new HoveredItem(slot, overrideStack, name, desc);
        }
        if (defaults != null && !defaults.item.isEmpty()) {
            return new HoveredItem(slot, defaults.item, defaults.displayName, defaults.description);
        }
        return null;
    }

    public void close() {
        fakePlayer = null;
        cachedLevel = null;
        lastEquippedKit = null;
        lastAppliedOverrides = null;
        overrides.clear();
        hoverRects.clear();
    }

    // -- internals --

    /** Cached profile from the first time we had access to mc.player. Lets us
     *  recreate the viewer entity later (e.g. after returning to title screen). */
    private GameProfile cachedProfile;

    private LivingEntity ensureEntity() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer local = mc.player;

        // Always cache the profile when we can, this survives world changes.
        if (local != null) {
            try { cachedProfile = local.getGameProfile(); } catch (Throwable ignored) {}
        }

        // If we already have a fake player and the world hasn't changed to a
        // NEW non-null world, reuse it, keeps the viewer alive on title-screen
        // returns where mc.level becomes null.
        if (fakePlayer != null && (level == null || cachedLevel == level)) {
            return fakePlayer;
        }

        // First-time creation or world swap. Need a level to construct the
        // RemotePlayer. If we don't have one yet, return null and let the
        // caller render a placeholder.
        if (level == null || cachedProfile == null) return null;

        try {
            fakePlayer = new RemotePlayer(level, cachedProfile);
            fakePlayer.setCustomNameVisible(false);
            cachedLevel = level;
            lastEquippedKit = null;
            lastAppliedOverrides = null;
        } catch (Exception e) {
            fakePlayer = null;
            return null;
        }
        return fakePlayer;
    }

    /**
     * Equip the entity from the kit defaults, then overlay any per-slot overrides.
     * If an override slot maps to {@link ItemStack#EMPTY} the slot is left empty
     * (rather than re-equipping the default), so a user-cleared HEAD stays bare.
     */
    private void applyKit(LivingEntity entity, Kit kit, Map<KitLoadout.Slot, ItemStack> ov) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            try { entity.setItemSlot(slot, ItemStack.EMPTY); } catch (Exception ignored) {}
        }
        // Build a fully-resolved per-slot map: override entries win over defaults.
        Map<KitLoadout.Slot, ItemStack> resolved = new HashMap<>();
        for (KitLoadout.LoadoutSlot ls : KitLoadout.get(kit)) {
            resolved.put(ls.slot, ls.item == null ? ItemStack.EMPTY : ls.item);
        }
        if (ov != null) {
            for (var e : ov.entrySet()) resolved.put(e.getKey(), e.getValue());
        }

        for (var e : resolved.entrySet()) {
            EquipmentSlot eq = mapSlot(e.getKey());
            if (eq == null) continue;
            ItemStack stack = e.getValue();
            if (stack == null || stack.isEmpty()) continue;
            entity.setItemSlot(eq, stack.copy());
        }
    }

    private static EquipmentSlot mapSlot(KitLoadout.Slot s) {
        return switch (s) {
            case HEAD     -> EquipmentSlot.HEAD;
            case CHEST    -> EquipmentSlot.CHEST;
            case LEGS     -> EquipmentSlot.LEGS;
            case FEET     -> EquipmentSlot.FEET;
            case MAINHAND -> EquipmentSlot.MAINHAND;
            case OFFHAND  -> EquipmentSlot.OFFHAND;
            case HOTBAR1, HOTBAR2, HOTBAR3, HOTBAR4, HOTBAR5 -> null;
        };
    }
}
