// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.servermod.queue;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.revivalsmp.pvp.servermod.RevivalPVPServerMod;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/**
 * Per-player BossBar shown while in matchmaking queue.
 *
 * <p>Why a BossBar: chat messages scroll away. Players without the client mod
 * (the audience for revival-pvp-server-mod) were left wondering whether their
 * queue was still active. The BossBar sits at the top of the screen, updates
 * every second with elapsed time, and disappears the moment the queue ends
 * for any reason (match found, timeout, error, cancellation, disconnect).
 *
 * <p>Progress bar fills as we approach the backend's queue timeout (300s
 * default for ranked, 60s for unranked per the queue config), so players
 * can see roughly how close they are to timing out.
 *
 * <p>Lifecycle owned by {@link net.revivalsmp.pvp.servermod.ws.BackendClient}:
 * <ul>
 *   <li>{@link #start} on {@code queue_joined}</li>
 *   <li>{@link #stop} on {@code match_found / queue_timeout / queue_error /
 *       auth_error}, and unconditionally on socket close to avoid orphans.</li>
 * </ul>
 */
public final class QueueDisplay {

    /** Soft cap — the bar shows 100% beyond this many seconds, in case the
     *  backend's timeout is longer than expected. */
    private static final int TIMEOUT_HINT_SECONDS_RANKED   = 300;
    private static final int TIMEOUT_HINT_SECONDS_UNRANKED =  60;

    private final RevivalPVPServerMod plugin;
    private final UUID playerUuid;

    private BossBar  bar;
    private BukkitTask updateTask;
    private long startMs = 0L;

    public QueueDisplay(RevivalPVPServerMod plugin, UUID playerUuid) {
        this.plugin     = plugin;
        this.playerUuid = playerUuid;
    }

    /** Show the bar and start the 1-second refresh loop. Idempotent — safe to
     *  call twice; the second call just resets the timer. */
    public void start(String kit, boolean ranked, String scope) {
        stop();   // clear any stale state
        startMs = System.currentTimeMillis();
        int timeoutHint = ranked ? TIMEOUT_HINT_SECONDS_RANKED
                                 : TIMEOUT_HINT_SECONDS_UNRANKED;
        bar = BossBar.bossBar(
            buildTitle(kit, ranked, scope, 0),
            0.0f,
            BossBar.Color.BLUE,
            BossBar.Overlay.PROGRESS
        );
        showToPlayer();
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(kit, ranked, scope, timeoutHint),
            20L, 20L);   // 1s
    }

    /** Hide the bar and cancel the refresh loop. Safe to call when not running. */
    public void stop() {
        if (updateTask != null) {
            try { updateTask.cancel(); } catch (Exception ignored) {}
            updateTask = null;
        }
        if (bar != null) {
            Player p = Bukkit.getPlayer(playerUuid);
            if (p != null && p.isOnline()) {
                try { p.hideBossBar(bar); } catch (Exception ignored) {}
            }
            bar = null;
        }
    }

    /** Returns the elapsed-time string ("Mm Ss") since {@link #start} ran, or
     *  {@code "?"} if no display is active. Used by chat messages on queue-end
     *  events so the player sees how long they waited. */
    public String elapsedDisplay() {
        if (startMs == 0) return "?";
        return formatElapsed((System.currentTimeMillis() - startMs) / 1000);
    }

    private void tick(String kit, boolean ranked, String scope, int timeoutHint) {
        if (bar == null) return;
        Player p = Bukkit.getPlayer(playerUuid);
        if (p == null || !p.isOnline()) {
            // Player disconnected mid-queue. BackendClient.onClose will run
            // separately to clean up the WS; we just bail.
            stop();
            return;
        }
        long elapsedSec = (System.currentTimeMillis() - startMs) / 1000;
        bar.name(buildTitle(kit, ranked, scope, elapsedSec));
        float pct = Math.min(1.0f, (float) elapsedSec / (float) timeoutHint);
        bar.progress(pct);
        // Color shifts as we approach timeout so the urgency is visible at a glance.
        BossBar.Color color =
            pct < 0.5f ? BossBar.Color.BLUE  :
            pct < 0.8f ? BossBar.Color.YELLOW :
                         BossBar.Color.RED;
        if (bar.color() != color) bar.color(color);
    }

    private void showToPlayer() {
        Player p = Bukkit.getPlayer(playerUuid);
        if (p != null && p.isOnline() && bar != null) {
            try { p.showBossBar(bar); } catch (Exception ignored) {}
        }
    }

    private static Component buildTitle(String kit, boolean ranked, String scope, long elapsedSec) {
        return Component.text("RevivalPVP ", NamedTextColor.LIGHT_PURPLE)
            .append(Component.text("- ", NamedTextColor.DARK_GRAY))
            .append(Component.text(displayKit(kit), NamedTextColor.AQUA))
            .append(Component.text(" (" + (ranked ? "Ranked" : "Unranked")
                    + ", " + capitalizeScope(scope) + ") ",
                NamedTextColor.GRAY))
            .append(Component.text("- ", NamedTextColor.DARK_GRAY))
            .append(Component.text(formatElapsed(elapsedSec), NamedTextColor.WHITE));
    }

    private static String formatElapsed(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return m + "m " + s + "s";
    }

    private static String displayKit(String raw) {
        if (raw == null || raw.isEmpty()) return "?";
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1).toLowerCase();
    }

    private static String capitalizeScope(String s) {
        if (s == null || s.isEmpty()) return "?";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}