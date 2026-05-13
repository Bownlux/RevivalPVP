// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.revivalsmp.pvp.RevivalPVPMod;

/**
 * Connects the player to a duel server when a match is found.
 *
 * Saves the current server address so we can reconnect after the duel ends.
 * If the player is in single-player, we save that state and return them
 * to the singleplayer world select screen after.
 */
public class DuelServerConnector {

    private static String savedServerAddress = null;
    private static boolean wasInSingleplayer = false;

    /**
     * Connect to the duel server. Must be called on the Minecraft main thread.
     *
     * @param host          Duel server hostname
     * @param port          Duel server port (usually 25565)
     * @param sessionToken  Passed as the "brand" channel on join so the plugin can authenticate
     */
    public static void connectToDuelServer(String host, int port, String sessionToken) {
        Minecraft mc = Minecraft.getInstance();

        // Save where the player currently is so we can return after the duel.
        // Order matters: check the active level first to detect singleplayer;
        // fall back to the connected-server address otherwise.
        var current = mc.getCurrentServer();
        if (mc.level != null && mc.isLocalServer()) {
            wasInSingleplayer = true;
            savedServerAddress = null;
        } else if (current != null && current.ip != null && !current.ip.isBlank()) {
            wasInSingleplayer = false;
            savedServerAddress = current.ip;
        } else {
            wasInSingleplayer = false;
            savedServerAddress = null;
        }
        RevivalPVPMod.LOGGER.info(
            "Saved return target: ip={}, name={}, singleplayer={}, mc.screen={}",
            savedServerAddress,
            current != null ? current.name : "<no current>",
            wasInSingleplayer,
            mc.screen != null ? mc.screen.getClass().getSimpleName() : "<null>");

        // Store token so the plugin connection listener can send it on join
        PendingDuelSession.set(sessionToken, host, port);

        String address = host + ":" + port;
        ServerData serverData = new ServerData("RevivalPVP Duel", address, ServerData.Type.OTHER);
        serverData.setResourcePackStatus(ServerData.ServerPackStatus.DISABLED);

        RevivalPVPMod.LOGGER.info("Connecting to duel server: {}", address);

        boolean inSp = mc.hasSingleplayerServer();

        if (inSp) {
            // Show Saving level screen first. ConnectScreen.startConnecting()
            // calls mc.disconnect() synchronously to halt the SP integrated
            // server, which blocks the render thread for several seconds —
            // user sees a black screen unless something is already painted.
            mc.setScreen(new net.minecraft.client.gui.screens.GenericMessageScreen(
                net.minecraft.network.chat.Component.translatable("menu.savingLevel")));

            // mc.execute() from the render thread runs immediately on 1.21.5,
            // so it doesn't actually defer anything. Use a virtual thread to
            // sleep briefly off-thread, then mc.execute back onto the render
            // thread for the actual connect. The sleep gives MC's render loop
            // a chance to paint a few frames of the saving screen before the
            // disconnect-induced freeze begins.
            RevivalPVPMod.LOGGER.info("In SP — scheduling startConnecting after 150ms paint window");
            Thread.ofVirtual().name("rpvp-deferred-connect").start(() -> {
                try { Thread.sleep(150); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                mc.execute(() -> {
                    RevivalPVPMod.LOGGER.info("Calling startConnecting from deferred path");
                    try {
                        ConnectScreen.startConnecting(
                            mc.screen, mc, ServerAddress.parseString(address),
                            serverData, false, null);
                    } catch (Throwable t) {
                        RevivalPVPMod.LOGGER.error("startConnecting threw for {}: {}",
                            address, t.toString(), t);
                    }
                });
            });
        } else {
            // Not in SP — no integrated server shutdown to worry about, just
            // call startConnecting directly.
            try {
                ConnectScreen.startConnecting(
                    mc.screen, mc, ServerAddress.parseString(address),
                    serverData, false, null);
            } catch (Throwable t) {
                RevivalPVPMod.LOGGER.error("startConnecting threw for {}: {}",
                    address, t.toString(), t);
            }
        }
    }

    /**
     * Called by the plugin listener after the duel ends.
     * Reconnects the player to their original server.
     */
    public static void returnFromDuel() {
        Minecraft mc = Minecraft.getInstance();
        boolean stillConnected = mc.player != null && mc.player.connection != null;
        var curScreen = mc.screen;
        RevivalPVPMod.LOGGER.info(
            "returnFromDuel: wasInSingleplayer={}, savedServerAddress={}, stillConnected={}, screen={}",
            wasInSingleplayer, savedServerAddress, stillConnected,
            curScreen != null ? curScreen.getClass().getSimpleName() : "<null>");

        if (wasInSingleplayer) {
            mc.execute(() -> mc.setScreen(new net.minecraft.client.gui.screens.worldselection.SelectWorldScreen(null)));
            wasInSingleplayer = false;
            return;
        }

        if (savedServerAddress != null) {
            // Hold the address until startConnecting actually fires. If the
            // disconnect/reconnect lambda throws between here and there we
            // want the address still set so a retry (or hasSavedServer()
            // check) can recover instead of falling to MultiplayerScreen.
            String address = savedServerAddress;
            mc.execute(() -> {
                RevivalPVPMod.LOGGER.info("Reconnecting to {}", address);

                // Tear down the current connection BEFORE starting the new
                // one. Without this, ConnectScreen.startConnecting hangs for
                // ~30s waiting for the still-open duel-server connection to
                // time out before it can initiate the new TCP handshake. The
                // plugin sent us revivalpvp:return but does NOT disconnect us
                //, that's the mod's responsibility.
                try {
                    if (mc.player != null && mc.player.connection != null) {
                        mc.player.connection.getConnection().disconnect(
                            net.minecraft.network.chat.Component.literal("Returning to origin server"));
                    }
                } catch (Throwable t) {
                    RevivalPVPMod.LOGGER.warn("Pre-reconnect disconnect failed (non-fatal): {}",
                        t.getMessage());
                }

                try {
                    ServerData serverData = new ServerData("Previous Server", address, ServerData.Type.OTHER);
                    ConnectScreen.startConnecting(
                        new net.minecraft.client.gui.screens.TitleScreen(),
                        mc,
                        ServerAddress.parseString(address),
                        serverData, false, null
                    );
                    RevivalPVPMod.LOGGER.info("startConnecting dispatched for {}", address);
                    savedServerAddress = null;
                } catch (Throwable t) {
                    RevivalPVPMod.LOGGER.error("startConnecting threw for {}: {}", address, t.toString());
                    // Don't clear savedServerAddress — leave it for retry/diagnostics.
                    // Drop the player on the multiplayer list so they aren't stranded.
                    mc.setScreen(new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(
                        new net.minecraft.client.gui.screens.TitleScreen()));
                }
            });
            return;
        }
        // No saved address, fall back to the multiplayer server-list screen so
        // the player isn't stranded with no obvious next step. Happens when the
        // mod hub was opened from the title screen with no prior server join.
        RevivalPVPMod.LOGGER.info("No saved server, opening multiplayer server list.");
        mc.execute(() -> mc.setScreen(
            new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(
                new net.minecraft.client.gui.screens.TitleScreen())));
    }

    public static boolean hasSavedServer() {
        return savedServerAddress != null || wasInSingleplayer;
    }
}