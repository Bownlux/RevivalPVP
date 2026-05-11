# RevivalPVP

> Cross-server ranked PvP duels for Minecraft. Queue from any server or
> single-player, transfer to a low-latency arena, climb a per-kit ladder.

**Website**: <https://revivalpvp.net> · **Discord**: <https://discord.gg/revival-smp>

This repository contains the **two open-source components** of the
RevivalPVP platform:

| Module | What it is | Who installs it |
|---|---|---|
| **[client-mod/](client-mod/)** | Fabric mod for Minecraft Java | Players (anyone who wants to queue from anywhere) |
| **[server-mod/](server-mod/)** | Paper / Purpur plugin | Server operators who want `/pvp` on their server |

The matchmaking backend, web frontend, and duel-host arena plugin are
operated by us and are **not** part of this repository — see
[Architecture](docs/architecture.md) for the full picture.

## Quick start

### Players (client mod)

1. Install [Fabric Loader](https://fabricmc.net/use/) for your Minecraft version.
2. Download `revival-pvp-mod-X.Y.Z.jar` from
   [CurseForge](https://www.curseforge.com/minecraft/mc-mods/revivalpvp) or
   [Releases](../../releases).
3. Drop it in `mods/` along with [Fabric API](https://modrinth.com/mod/fabric-api).
4. Launch the game, press `Y` from any server, single-player world, or main menu.

### Server operators (server mod)

1. Sign up at <https://play.revivalsmp.net/pvp/host> — free tier is
   forever-free, no card required.
2. Download `RevivalPVPServerMod-X.Y.Z.jar` from [Releases](../../releases).
3. Drop it in your server's `plugins/` folder, restart.
4. Paste your tenant key into `plugins/RevivalPVPServerMod/config.yml`,
   restart again.
5. Your players can now run `/pvp` to queue.

Full operator guide: [docs/operator-guide.md](docs/operator-guide.md).

## License

[PolyForm Noncommercial 1.0.0](LICENSE.md) — free for personal,
hobbyist, educational, and noncommercial server use. Commercial use
(paid ranks, paid hosting service, paid modpack bundling, etc.)
requires a separate written agreement. See [NOTICE.md](NOTICE.md)
for the full rundown.

## Contributing

Issues and pull requests welcome. Cross-cutting protocol changes
should touch both modules in one PR. Before opening a large PR
please open an issue or ping us on Discord first to make sure the
direction lines up with where we're taking the platform.

## What's NOT in this repo

- **The matchmaking backend** (`revival-pvp-backend`) — closed-source
  FastAPI service that runs at `api.revivalpvp.net`.
- **The arena duel-host plugin** (`revival-pvp-plugin`) — closed-source
  Paper plugin that runs the actual fights on our servers.
- **The web frontend** (operator dashboard, leaderboards, etc.) —
  lives at `play.revivalsmp.net`.

This separation is intentional: the open-source mods let you queue
and host servers; the closed-source pieces are the operated service.
