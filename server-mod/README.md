# RevivalPVP — Server Mod

Paper / Purpur plugin that adds `/pvp` to any Minecraft server. Players
queue against opponents from across the RevivalPVP network; the actual
duel runs on our arena server and they're returned to your server when
it ends.

**For operators**: <https://play.revivalsmp.net/pvp/host>
**Discord**: <https://discord.gg/revival-smp>

## What this plugin does

- Adds `/pvp` (chest GUI to pick kit, scope, ranked/unranked, queue).
- Adds `/pvpaccept` (accept a friend's duel invite from chat).
- Adds `/pvpadmin reload|status|key`.
- Heartbeats to the RevivalPVP backend every ten minutes so the
  operator dashboard at `play.revivalsmp.net/pvp/host/dashboard`
  shows your server as connected.
- Fetches the kit catalog from the backend on demand, so kit
  additions and renames don't require a plugin update.

## What this plugin does NOT do

- It does not run duels itself. When a match starts, players are
  transferred to our arena server (`pvp.revivalpvp.net`) and back to
  yours when the match ends. The arena code, maps, and kit-delivery
  logic are not part of this plugin.
- It does not store player data locally beyond per-session caches.
  Stats, ratings, friends, sponsor coins live on the backend.

## Requirements

- Paper or a Paper fork (Purpur recommended) on Minecraft 1.21+.
- Java 21+ (Java 25 if you're on Minecraft 26.1+).
- Internet access from the server host to `api.revivalpvp.net`.
- A free tenant key from <https://play.revivalsmp.net/pvp/host>.

## Install

1. Download the latest `RevivalPVPServerMod-X.Y.Z.jar` from
   [Releases](../../../releases).
2. Drop it in your server's `plugins/` folder.
3. Restart the server. The plugin will generate a default
   `plugins/RevivalPVPServerMod/config.yml`.
4. Sign up at <https://play.revivalsmp.net/pvp/host> if you haven't
   already. You'll get a tenant key by email.
5. Edit `plugins/RevivalPVPServerMod/config.yml` and paste the key:

   ```yaml
   tenant-key: "rpvp_xxxxxxxxxxxxxxxxxx"
   backend-url: "https://api.revivalpvp.net"
   origin-server: "yourserver.example.com:25565"
   default-scope: "global"
   ```

   - `origin-server` is how your server is reachable from the public
     internet — used to transfer players back after a duel ends.
6. Restart the server. You should see in the console:
   ```
   [RevivalPVPServerMod] RevivalPVPServerMod enabled — /pvp ready
   [RevivalPVPServerMod] Kit catalog refreshed: N kits loaded.
   [RevivalPVPServerMod] Dashboard connection registered — plan=free, status=trial.
   ```
7. Visit your operator dashboard — the install panel collapses to a
   green "Server connected" banner once the heartbeat lands.

## Building from source

```bash
mvn -DskipTests clean package
# Output: target/RevivalPVPServerMod-X.Y.Z.jar
```

Requires Java 25 + Maven 3.9+.

## License

PolyForm Noncommercial 1.0.0 — see [../LICENSE.md](../LICENSE.md) and
[../NOTICE.md](../NOTICE.md). Free for noncommercial server use.
Paid-server use needs a separate agreement; ping us on Discord.
