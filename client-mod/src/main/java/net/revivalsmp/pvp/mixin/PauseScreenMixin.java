// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.revivalsmp.pvp.client.RevivalPVPClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "RevivalPVP" button to the pause menu so players don't need a
 * keybind to open the hub. Click → opens login screen if not authed,
 * else the queue hub.
 */
@Mixin(PauseScreen.class)
public class PauseScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void revivalpvp$addPvpButton(CallbackInfo ci) {
        PauseScreen screen = (PauseScreen) (Object) this;

        Button pvpButton = Button.builder(
                Component.translatable("revival-pvp.button.open_hub"),
                button -> {
                    if (RevivalPVPClient.get() != null) {
                        RevivalPVPClient.get().openHub();
                    }
                }
        )
        .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("revival-pvp.button.tooltip")))
        .bounds(screen.width - 110, 6, 100, 20)
        .build();

        ((ScreenAccessor) screen).revivalpvp$addRenderableWidget(pvpButton);
    }
}