// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
// fabric-api 0.128.x for 1.21.5 lacks HudElementRegistry — use HudRenderCallback.
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.revivalsmp.pvp.client.hud.PVPHud;
import net.revivalsmp.pvp.client.screen.MatchResultScreen;
import net.revivalsmp.pvp.client.screen.PVPHubScreen;
import net.revivalsmp.pvp.matchmaking.FriendsService;
import net.revivalsmp.pvp.matchmaking.MatchmakingService;
import net.revivalsmp.pvp.network.BackendWS;
import net.revivalsmp.pvp.network.DuelServerListener;
import net.revivalsmp.pvp.network.MojangAuth;
import org.lwjgl.glfw.GLFW;

public class RevivalPVPClient implements ClientModInitializer {

    private static RevivalPVPClient instance;

    private KeyMapping openHubKey;
    private BackendWS backendWS;
    private MatchmakingService matchmaking;
    private FriendsService friends;
    private PVPHud hud;

    @Override
    public void onInitializeClient() {
        instance = this;

        // Default Y, vanilla 1.21+ doesn't bind Y, so no collision. Users
        // can rebind via Options → Controls → RevivalPVP, or use the
        // pause-menu button added by PauseScreenMixin.
        openHubKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.revival-pvp.open_hub",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Y,
            "key.categories.misc"
        ));

        backendWS  = new BackendWS();
        matchmaking = new MatchmakingService(backendWS);
        friends     = new FriendsService(backendWS);
        hud        = new PVPHud();

        // Register plugin message channels for duel server communication
        DuelServerListener.register();

        HudRenderCallback.EVENT.register(
            (guiGraphics, deltaTracker) -> hud.render(guiGraphics, deltaTracker)
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openHubKey.consumeClick()) {
                openHub(client);
            }
            backendWS.tick();
            matchmaking.tick();
            friends.tick();

            // Auto-show result screen when a duel ends
            if (matchmaking.state() == MatchmakingService.State.RESULT
                    && !(client.screen instanceof MatchResultScreen)) {
                client.setScreen(new MatchResultScreen(matchmaking));
            }
        });

        // Graceful shutdown when the player quits Minecraft. Drops the WS,
        // halts the reconnect loop on the virtual thread, and tells the
        // backend our queue/heartbeat is gone (best-effort goodbye frame).
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            try {
                if (matchmaking != null && matchmaking.state() == MatchmakingService.State.QUEUING) {
                    // Best-effort: notify backend so the queue entry is dropped
                    // immediately instead of waiting for heartbeat timeout.
                    matchmaking.leaveQueue();
                }
            } catch (Throwable ignored) {}
            try { backendWS.shutdown(); } catch (Throwable ignored) {}
        });

        backendWS.connect();
    }

    /** Public so PauseScreenMixin's button can invoke the same flow. */
    public void openHub() {
        openHub(Minecraft.getInstance());
    }

    private void openHub(Minecraft client) {
        var config = net.revivalsmp.pvp.RevivalPVPMod.get().config();
        if (config.authToken() != null) {
            client.setScreen(new PVPHubScreen(matchmaking));
            return;
        }
        // Zero-interaction Mojang session auth. Tells the player it's working
        // via a brief HUD message rather than a screen so we don't block input.
        client.gui.setOverlayMessage(
            net.minecraft.network.chat.Component.literal("§7Authenticating with Mojang..."), false);
        MojangAuth.authenticate().thenAccept(ok -> client.execute(() -> {
            if (ok) {
                // Force WS reconnect so it picks up the saved token
                if (backendWS.isConnected()) backendWS.close();
                backendWS.connect();
                client.setScreen(new PVPHubScreen(matchmaking));
            } else {
                client.gui.setOverlayMessage(
                    net.minecraft.network.chat.Component.literal(
                        "§cRevivalPVP auth failed, Mojang session unavailable. Are you in offline mode?"),
                    false);
            }
        }));
    }

    public static RevivalPVPClient get()          { return instance; }
    public BackendWS backendWS()                  { return backendWS; }
    public MatchmakingService matchmaking()        { return matchmaking; }
    public FriendsService friends()                { return friends; }
}