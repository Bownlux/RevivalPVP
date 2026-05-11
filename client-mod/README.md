# RevivalPVP

Cross-server ranked PvP duels for Minecraft. Queue from any server or single-player, transfer to a low-latency arena, climb a per-kit ladder.

[revivalpvp.net](https://revivalpvp.net)

## What it does

- Press `Y` (rebindable) from any server, single-player world, or the main menu to open the RevivalPVP hub.
- Pick a kit (Fist, Sword, Archer, Crossbow, Mace, Spear, Trident, TNT, Crystal) and queue ranked or unranked.
- Get matched against another player on the same global pool. The mod transfers you to the duel arena, runs the fight, and transfers you back to your origin server when it ends.
- Climb the ladder per kit. Each kit has its own LP rating (LoL-style: Iron through Sovereign).
- Friends list with chat-clickable duel invites. Live duel spectating from the website or in-mod.

Players on Bedrock or any vanilla client can also play by joining a server that runs the [revival-pvp-server-mod](https://github.com/bownlux/revivalpvpserver) plugin and running `/pvp` in chat. No client mod required for that path.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.16.0+ for Minecraft 26.1.
2. Drop `fabric-api` into your `mods/` folder.
3. Drop `revival-pvp-mod-<version>.jar` into your `mods/` folder.
4. Launch Minecraft. Press `Y` from anywhere to open the hub.

You'll be prompted to authenticate via your Mojang session on first open. No password needed.

## Compatibility

- **Minecraft**: 1.21.11 and 26.1+ (separate JARs per version)
- **Fabric Loader**: 0.16.0+
- **Java**: 25
- **Side**: client only. Servers do not need this mod (they need the server-mod plugin instead, which is optional and only required for the in-game `/pvp` flow).

## Reporting bugs

[github.com/bownlux/revivalpvpmod/issues](https://github.com/bownlux/revivalpvpmod/issues)

## License

PolyForm Noncommercial 1.0.0. See [LICENSE](./LICENSE).

You can read, modify, share patches, and use the mod for personal, educational, or charitable use. Commercial use, including running the mod against a competing or commercial backend, is not permitted without a separate license.
