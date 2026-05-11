// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.revivalsmp.pvp.client.RevivalPVPClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "RevivalPVP" button to the main title screen, mirroring the
 * top-right placement in {@link PauseScreenMixin}. The hub is server-context
 * independent (talks to the backend WS over HTTPS, auths via Mojang session)
 * so the button works from the title screen too, no game world required.
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void revivalpvp$addPvpButton(CallbackInfo ci) {
        TitleScreen screen = (TitleScreen) (Object) this;

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
