# RevivalPVP — Use Notice

RevivalPVP is distributed under **PolyForm Shield 1.0.0**
(see [LICENSE.md](LICENSE.md)). This NOTICE clarifies what Shield
means in plain language for the most common ways people use this
software.

The Shield license is more permissive than a typical "noncommercial"
license. The bar is **competing with the licensor**, not "commercial
use" in general.

## What you can do (no permission needed)

- **Run the server mod on any Minecraft server**, including ones that
  charge players for ranks, cosmetics, world access, queue priority,
  donations, or any other in-game monetization. You're a *user* of
  the RevivalPVP service, not a competitor to it.
- **Run the client mod** on your personal client or modpack.
- **Modify either mod** for your own use, your own server, or your
  own community.
- **Redistribute** either mod (modified or unmodified) under the same
  PolyForm Shield license.
- **Bundle in a modpack** (free or paid). The modpack itself isn't
  competing with our matchmaking service.
- **Use for research, education, demonstrations, hobby projects.**

## What requires a separate written agreement

The license prohibits **Competing Uses** — using this software to
provide a product or service that competes with what RevivalSMP
offers. Concretely, that means:

- **Running a competing cross-server PVP matchmaking service** that
  rival server operators connect their Minecraft servers to. This is
  the core product we sell tenant subscriptions for.
- **Hosting RevivalPVP as a paid managed service** (e.g. "I'll host
  your tenant for you" SaaS).
- **Selling subscriptions to a RevivalPVP-like network** built on
  forks of these mods plus your own backend.

The simple test: is what you're doing **using** RevivalPVP, or
**replicating** it? Using is fine, replicating is not.

If you're not sure whether your use case crosses the line, ask first.
We'd rather have the conversation than have you guess wrong.

## How to ask

- **Discord**: <https://discord.gg/revival-smp> (open a ticket)
- **Email**: <support@revivalsmp.net>

## Tenant model

The **server mod** is designed to connect to the official RevivalPVP
backend at <https://api.revivalpvp.net>. To run it functionally you'll
need a free tenant key from <https://play.revivalsmp.net/pvp/host>.

You may technically point the server mod at a different backend by
editing `config.yml`, but the protocol is not stable, undocumented, and
the backend is closed-source. Hosting a parallel matchmaking service
would be a Competing Use under the license terms above.

---

Copyright © 2026 RevivalSMP. All rights reserved except as granted in
LICENSE.md and this NOTICE.md.
