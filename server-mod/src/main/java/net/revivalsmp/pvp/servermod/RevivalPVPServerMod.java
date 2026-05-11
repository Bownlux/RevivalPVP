// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.servermod;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.revivalsmp.pvp.servermod.command.PvpAcceptCommand;
import net.revivalsmp.pvp.servermod.command.PvpAdminCommand;
import net.revivalsmp.pvp.servermod.command.PvpCommand;
import net.revivalsmp.pvp.servermod.kits.KitCatalogCache;
import net.revivalsmp.pvp.servermod.queue.QueueRegistry;
import net.revivalsmp.pvp.servermod.ws.BackendClient;
import net.revivalsmp.pvp.servermod.ws.PresenceRegistry;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main plugin class for revival-pvp-server-mod.
 *
 * Loads cleanly even if {@code tenant-key} is unset; /pvp will refuse to
 * queue with a clear error message. This avoids breaking server starts
 * during deploy or while a key is being provisioned.
 */
public class RevivalPVPServerMod extends JavaPlugin {

    private static RevivalPVPServerMod instance;

    private QueueRegistry queueRegistry;
    private PresenceRegistry presenceRegistry;
    private KitCatalogCache kitCatalog;

    /**
     * Cache of Bukkit player UUID → backend "internal" player_uuid string.
     *
     * <p>The backend's plugin-key-auth endpoints (e.g. the loadout editor)
     * are keyed by the internal uuid that {@code _resolve_or_create_player_by_mc_uuid}
     * returns, NOT the raw Mojang/Bukkit UUID. We populate this map from
     * the {@code auth_ok} payload in {@link BackendClient#handleAuthOk}, so
     * any flow that wants to talk to those endpoints needs the player to
     * have authed (via /pvp queue) at least once this session.
     */
    private final Map<UUID, String> internalUuidByPlayer = new ConcurrentHashMap<>();

    /**
     * Ops we've already shown the "key not configured" join hint to this session.
     *
     * <p>Cleared on plugin disable (so a /reload retriggers, which is what we
     * want — if an op reloaded after editing config.yml they should see the
     * confirmation message if the key is still missing).
     */
    private final Set<UUID> nudgedOps = ConcurrentHashMap.newKeySet();

    private static final String DASHBOARD_URL = "https://play.revivalsmp.net/pvp/host/dashboard";

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        queueRegistry = new QueueRegistry(this);
        presenceRegistry = new PresenceRegistry(this);
        presenceRegistry.register();
        kitCatalog = new KitCatalogCache(this);
        kitCatalog.warmUp();

        var pvp = getCommand("pvp");
        if (pvp != null) pvp.setExecutor(new PvpCommand(this));

        var pvpAccept = getCommand("pvpaccept");
        if (pvpAccept != null) pvpAccept.setExecutor(new PvpAcceptCommand(this));

        var pvpAdmin = getCommand("pvpadmin");
        if (pvpAdmin != null) pvpAdmin.setExecutor(new PvpAdminCommand(this));

        getServer().getPluginManager().registerEvents(new OpJoinNudgeListener(), this);

        // Validate config keys early. We don't fail-hard-disable on missing
        // keys (would leave the server unable to start during deploy / while
        // a key is being provisioned) — instead we warn loudly and let /pvp
        // refuse to queue with a clear error to the player.
        // tenantKey()/pluginApiKey() already return "" for known placeholders.
        boolean tenantOk = !tenantKey().isBlank();
        boolean apiOk    = !pluginApiKey().isBlank();
        if (!tenantOk) {
            getLogger().warning("================================================================");
            getLogger().warning(" RevivalPVP is installed but needs setup.");
            getLogger().warning("");
            getLogger().warning("   1. Sign up (free) at " + DASHBOARD_URL);
            getLogger().warning("   2. Paste your tenant-key into config.yml");
            getLogger().warning("   3. Restart the server (or run /pvpadmin reload)");
            getLogger().warning("");
            getLogger().warning("   See WELCOME-READ-ME-FIRST.txt in this plugin's data folder.");
            getLogger().warning("================================================================");
            ensureWelcomeFile();
        } else if (!apiOk) {
            getLogger().info("RevivalPVPServerMod enabled — /pvp ready (backend=" + backendUrl() + ").");
            getLogger().warning("plugin-api-key is unset — loadout customization will be disabled. "
                + "This is optional; queueing works without it.");
            removeWelcomeFile();
            scheduleHeartbeat();
        } else {
            getLogger().info("RevivalPVPServerMod enabled — /pvp ready (backend=" + backendUrl() + ").");
            removeWelcomeFile();
            scheduleHeartbeat();
        }
    }

    /**
     * Drops a friendly text file into the plugin's data folder on first
     * unconfigured start so operators who skip the console find clear
     * instructions next to their config.yml — the exact directory they'll
     * open to paste their tenant key. Idempotent: if the file already
     * exists (operator hasn't acted on it yet) we leave it alone.
     */
    private void ensureWelcomeFile() {
        java.io.File welcome = new java.io.File(getDataFolder(), "WELCOME-READ-ME-FIRST.txt");
        if (welcome.exists()) return;
        try (java.io.PrintWriter w = new java.io.PrintWriter(welcome, java.nio.charset.StandardCharsets.UTF_8)) {
            w.println("============================================================");
            w.println("  Welcome to RevivalPVP!");
            w.println("============================================================");
            w.println();
            w.println("This plugin needs a tenant-key to connect to the RevivalPVP");
            w.println("matchmaking network. The free tier is forever-free, no card");
            w.println("required.");
            w.println();
            w.println("Setup in 4 steps:");
            w.println();
            w.println("  1. Sign up at:");
            w.println("       " + DASHBOARD_URL);
            w.println();
            w.println("  2. Click 'Apply for a tenant key', fill the form.");
            w.println();
            w.println("  3. Paste the key you receive into config.yml in this folder:");
            w.println("       tenant-key: \"rpvp_xxxxxxxxxxxxxxxxxx\"");
            w.println("       origin-server: \"yourserver.example.com:25565\"");
            w.println();
            w.println("  4. Restart the server (or run /pvpadmin reload in-game).");
            w.println();
            w.println("Once your server connects, your dashboard at the URL above");
            w.println("shows a green 'Server connected' banner and this file is");
            w.println("automatically removed.");
            w.println();
            w.println("Need help?");
            w.println("  Discord: https://discord.gg/revival-smp");
            w.println("  Email:   support@revivalsmp.net");
            w.println();
            w.println("------------------------------------------------------------");
            w.println("RevivalPVP is distributed under PolyForm Shield 1.0.0.");
            w.println("Permissive for almost all uses, including paid Minecraft");
            w.println("servers. The license restricts competing uses (running a");
            w.println("rival cross-server PVP matchmaking service). See LICENSE.md");
            w.println("and NOTICE.md at github.com/Bownlux/RevivalPVP for details.");
        } catch (java.io.IOException e) {
            getLogger().warning("Could not write WELCOME-READ-ME-FIRST.txt: " + e.getMessage());
        }
    }

    /** Cleanup once the operator successfully configures the plugin — we
     *  don't want stale onboarding files cluttering up healthy installs. */
    private void removeWelcomeFile() {
        java.io.File welcome = new java.io.File(getDataFolder(), "WELCOME-READ-ME-FIRST.txt");
        if (welcome.exists() && welcome.delete()) {
            getLogger().info("Setup complete — removed WELCOME-READ-ME-FIRST.txt.");
        }
    }

    /**
     * Sends an initial heartbeat to the backend right after enable, then
     * keeps it warm every 10 minutes.
     *
     * <p>The initial ping is the operator-facing signal — it stamps
     * {@code tenants.last_seen_at} on the backend, which makes the operator
     * dashboard collapse its "Awaiting server" install panel into the
     * green "Server connected" banner within ~15s of server start. The
     * periodic refresh keeps the banner truthful for ops watching live
     * (a server that crashed an hour ago shouldn't keep showing as connected).
     *
     * <p>Heartbeat skipped when keys are misconfigured — guarded above.
     * The initial ping is delayed 5s so post-enable logs don't interleave
     * with the heartbeat response.
     */
    private void scheduleHeartbeat() {
        // Initial ping (delayed so it lands after the rest of plugin init).
        getServer().getScheduler().runTaskLaterAsynchronously(this, () -> {
            net.revivalsmp.pvp.servermod.network.TenantHttpClient.heartbeat(this)
                .thenAccept(resp -> {
                    if (resp != null) {
                        String plan   = resp.has("plan")   ? resp.get("plan").getAsString()   : "?";
                        String status = resp.has("status") ? resp.get("status").getAsString() : "?";
                        getLogger().info("Dashboard connection registered — plan=" + plan
                            + ", status=" + status + ". Visit " + DASHBOARD_URL);
                    }
                });
        }, 100L); // 5s after enable

        // Periodic refresh every 10 min while the server is up.
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            net.revivalsmp.pvp.servermod.network.TenantHttpClient.heartbeat(this);
        }, 12000L, 12000L); // 10 min in ticks
    }

    private static String describeRawKeyState(String raw) {
        if (raw == null) return "empty";
        String k = raw.trim();
        if (k.isBlank()) return "empty";
        if (PLACEHOLDER_KEYS.contains(k.toLowerCase(java.util.Locale.ROOT))) {
            return "still the default placeholder value (\"" + k + "\")";
        }
        return "set";
    }

    private static final java.util.Set<String> PLACEHOLDER_KEYS = java.util.Set.of(
        "changeme", "your-key-here", "todo", "placeholder", "<your-key>",
        "your-tenant-key", "your-plugin-key"
    );

    @Override
    public void onDisable() {
        if (presenceRegistry != null) presenceRegistry.shutdown();
        if (queueRegistry != null) queueRegistry.shutdown();
        internalUuidByPlayer.clear();
        nudgedOps.clear();
    }

    public static RevivalPVPServerMod get() { return instance; }

    public QueueRegistry queueRegistry() { return queueRegistry; }
    public KitCatalogCache kitCatalog()  { return kitCatalog; }

    /** Look up the backend internal uuid for a Bukkit player; null if not yet authed. */
    public String getInternalUuid(UUID bukkitUuid) {
        return internalUuidByPlayer.get(bukkitUuid);
    }

    /** Cache the backend internal uuid for a Bukkit player. Called from {@link BackendClient}. */
    public void setInternalUuid(UUID bukkitUuid, String internalUuid) {
        if (internalUuid == null || internalUuid.isBlank()) return;
        internalUuidByPlayer.put(bukkitUuid, internalUuid);
    }

    /** Tenant key from config. Returns "" if the key is unset OR if it's still
     *  one of the well-known placeholder values like "changeme" — callers can
     *  keep their existing {@code .isBlank()} checks and a sloppy default
     *  won't sneak into HTTP calls as a real key. */
    public String tenantKey()    { return resolveKey(getConfig().getString("tenant-key", "")); }
    public String pluginApiKey() { return resolveKey(getConfig().getString("plugin-api-key", "")); }

    private static String resolveKey(String raw) {
        if (raw == null) return "";
        String k = raw.trim();
        if (k.isBlank()) return "";
        if (PLACEHOLDER_KEYS.contains(k.toLowerCase(java.util.Locale.ROOT))) return "";
        return k;
    }
    public String backendUrl()   { return getConfig().getString("backend-url", "https://api.revivalpvp.net").trim(); }
    public String backendWsUrl() {
        String b = backendUrl();
        if (b.startsWith("https://")) return "wss://" + b.substring(8) + "/queue/ws";
        if (b.startsWith("http://"))  return "ws://"  + b.substring(7) + "/queue/ws";
        return b + "/queue/ws";
    }
    public String originServer() { return getConfig().getString("origin-server", "").trim(); }
    public String defaultScope() { return getConfig().getString("default-scope", "global").trim().toLowerCase(); }

    /**
     * Nudges ops on join when the tenant key isn't configured yet.
     *
     * <p>Server owners who install the jar and start their server otherwise
     * have no in-game signal that something is missing — the console warning
     * is easy to miss. This listener catches op-level joins (server owners,
     * admins) and surfaces a clickable link to the dashboard. Once per session
     * per op so it doesn't spam on every rejoin.
     */
    private final class OpJoinNudgeListener implements Listener {
        @EventHandler
        public void onJoin(PlayerJoinEvent event) {
            var player = event.getPlayer();
            if (!player.isOp()) return;
            if (!tenantKey().isBlank()) return;
            if (!nudgedOps.add(player.getUniqueId())) return;

            // Delay slightly so the message lands after vanilla join messages
            // and any motd, not buried in them.
            getServer().getScheduler().runTaskLater(RevivalPVPServerMod.this, () -> {
                if (!player.isOnline()) return;
                player.sendMessage(Component.empty());
                player.sendMessage(Component.text(
                    "▶ RevivalPVP is installed but not configured.",
                    NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
                player.sendMessage(Component.text(
                    "  No tenant-key set — /pvp is offline until you add yours.",
                    NamedTextColor.GRAY));
                player.sendMessage(Component.text(
                    "  Free tier is forever — no card needed. Get your key:",
                    NamedTextColor.GRAY));
                Component link = Component.text(DASHBOARD_URL, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(DASHBOARD_URL))
                    .hoverEvent(HoverEvent.showText(Component.text(
                        "Open the operator dashboard in your browser",
                        NamedTextColor.GRAY)));
                player.sendMessage(Component.text("  ▸ ", NamedTextColor.DARK_GRAY).append(link));
                player.sendMessage(Component.text(
                    "  Then paste it into plugins/RevivalPVPServerMod/config.yml and restart.",
                    NamedTextColor.GRAY));
                player.sendMessage(Component.empty());
            }, 40L); // ~2 seconds
        }
    }
}