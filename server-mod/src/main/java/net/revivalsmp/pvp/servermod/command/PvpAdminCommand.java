// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.servermod.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.revivalsmp.pvp.servermod.RevivalPVPServerMod;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * /pvpadmin — server-operator-facing diagnostics and config reload.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code /pvpadmin status} — show tenant + plugin-key config state,
 *       backend URL, default scope. Nothing secret.</li>
 *   <li>{@code /pvpadmin reload} — re-read config.yml from disk. Useful
 *       after pasting a new tenant key. Does not reconnect live WS sessions;
 *       new queues will pick up the new config.</li>
 *   <li>{@code /pvpadmin key show} — print the last 6 characters of the
 *       configured tenant key (fingerprint only, never the full key). Lets
 *       an admin confirm which key is loaded without exposing it in chat /
 *       console screen-share.</li>
 * </ul>
 *
 * <p>Gated by {@code revival.pvp.servermod.admin} (declared in plugin.yml
 * with {@code default: op}).
 */
public final class PvpAdminCommand implements CommandExecutor {

    private final RevivalPVPServerMod plugin;

    public PvpAdminCommand(RevivalPVPServerMod plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("revival.pvp.servermod.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use /pvpadmin.",
                NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "setup"  -> sendSetup(sender);
            case "status" -> sendStatus(sender);
            case "reload" -> reload(sender);
            case "key"    -> handleKey(sender, args);
            default       -> sendUsage(sender);
        }
        return true;
    }

    /**
     * First-time-setup walkthrough for operators. Distinct from /pvpadmin
     * status: this is a goal-oriented checklist with a clickable dashboard
     * URL and the exact next step, instead of a dump of every config key.
     *
     * <p>Same content the op-join listener nudges with — exposing it as a
     * command lets operators check progress without rejoining (and lets
     * fully-configured operators run it as a "did I miss anything?" check).
     */
    private void sendSetup(CommandSender sender) {
        boolean tenantOk = !plugin.tenantKey().isBlank();
        boolean apiOk    = !plugin.pluginApiKey().isBlank();
        boolean originOk = !plugin.originServer().isBlank();

        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("RevivalPVP setup checklist",
            NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        sender.sendMessage(checkLine(tenantOk, "tenant-key", "Get it free at the dashboard"));
        sender.sendMessage(checkLine(originOk, "origin-server",
            "Set to how your server is reachable, e.g. \"mc.yourdomain.com:25565\""));
        sender.sendMessage(checkLine(apiOk, "plugin-api-key",
            "Optional - only needed for loadout customization"));
        sender.sendMessage(Component.empty());

        if (tenantOk && originOk) {
            sender.sendMessage(Component.text(
                "All required keys set. /pvp should be working.",
                NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Next step:", NamedTextColor.WHITE));
            Component link = Component.text(DASHBOARD_URL,
                    NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(DASHBOARD_URL))
                .hoverEvent(HoverEvent.showText(
                    Component.text("Open the operator dashboard", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("  1. Open the dashboard: ", NamedTextColor.GRAY).append(link));
            sender.sendMessage(Component.text(
                "  2. Sign up (free, no card) and apply for a tenant key.",
                NamedTextColor.GRAY));
            sender.sendMessage(Component.text(
                "  3. Paste it into plugins/RevivalPVPServerMod/config.yml",
                NamedTextColor.GRAY));
            sender.sendMessage(Component.text(
                "  4. Run /pvpadmin reload (or restart the server).",
                NamedTextColor.GRAY));
        }
        sender.sendMessage(Component.empty());
    }

    private static final String DASHBOARD_URL =
        "https://play.revivalsmp.net/pvp/host/dashboard";

    private static Component checkLine(boolean ok, String label, String hint) {
        String mark = ok ? "[OK]" : "[  ]";
        NamedTextColor markColor = ok ? NamedTextColor.GREEN : NamedTextColor.GOLD;
        return Component.text("  " + mark + " ", markColor)
            .append(Component.text(label, NamedTextColor.WHITE))
            .append(Component.text(ok ? "" : "  - " + hint,
                NamedTextColor.GRAY));
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(Component.text("─── RevivalPVPServerMod status ───",
            NamedTextColor.LIGHT_PURPLE));
        String tenant = plugin.tenantKey();
        String apiKey = plugin.pluginApiKey();
        sender.sendMessage(line("tenant-key",     describeKey(tenant)));
        sender.sendMessage(line("plugin-api-key", describeKey(apiKey)));
        sender.sendMessage(line("backend-url",    plugin.backendUrl()));
        sender.sendMessage(line("origin-server",  blankOr(plugin.originServer(), "<unset>")));
        sender.sendMessage(line("default-scope",  plugin.defaultScope()));
        sender.sendMessage(line("plugin version", plugin.getDescription().getVersion()));
    }

    private void reload(CommandSender sender) {
        plugin.reloadConfig();
        sender.sendMessage(Component.text("Config reloaded from disk. New queues will pick up the new values.",
            NamedTextColor.GREEN));
        sender.sendMessage(Component.text(
            "Live duels and existing WS sessions are not affected.", NamedTextColor.GRAY));
        plugin.getLogger().info(sender.getName() + " ran /pvpadmin reload");
    }

    private void handleKey(CommandSender sender, String[] args) {
        if (args.length < 2 || !"show".equalsIgnoreCase(args[1])) {
            sender.sendMessage(Component.text("Usage: /pvpadmin key show", NamedTextColor.GRAY));
            return;
        }
        String tenant = plugin.tenantKey();
        if (tenant.isBlank()) {
            sender.sendMessage(Component.text(
                "tenant-key is not set. Configure it in config.yml.", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text(
            "tenant-key fingerprint: ...…" + tail(tenant, 6),
            NamedTextColor.GRAY));
        plugin.getLogger().info(sender.getName() + " ran /pvpadmin key show");
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage:", NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  /pvpadmin setup    - first-time setup checklist",
            NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  /pvpadmin status   - dump current config state",
            NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  /pvpadmin reload   - re-read config.yml",
            NamedTextColor.GRAY));
        sender.sendMessage(Component.text("  /pvpadmin key show - show last 6 chars of tenant key",
            NamedTextColor.GRAY));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.AQUA)
            .append(Component.text(value, NamedTextColor.WHITE));
    }

    private static String describeKey(String key) {
        if (key == null || key.isBlank()) return "<unset>";
        return "set (…" + tail(key, 6) + ")";
    }

    private static String tail(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    private static String blankOr(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}