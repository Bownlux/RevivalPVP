#!/usr/bin/env bash
# Render the website's RankBadge SVGs to 64x64 transparent PNGs that the
# mod can blit. Mirror of minecraft-revival-react/src/components/PvpIcons.jsx
# RankBadge component — JSX camelCase attrs converted to SVG kebab-case here.
#
# Usage:  bash render.sh
# Writes: ../../src/main/resources/assets/revivalpvp/textures/gui/rank/<tier>.png
#
# Why this script exists:
#   Polished website tier badges are inline SVG in PvpIcons.jsx. We can't
#   ship JSX into a Fabric mod, so we screenshot the rendered SVG with
#   headless Chrome and ship the resulting PNG as a resource pack texture.
#   One-off generator — re-run only if the website badges change.

set -euo pipefail

CHROME='/c/Program Files/Google/Chrome/Application/chrome.exe'
OUT_DIR='../../src/main/resources/assets/revivalpvp/textures/gui/rank'
TMP_DIR=$(mktemp -d -t rank-badges-XXXXXX)
trap 'rm -rf "$TMP_DIR"' EXIT

# render <tier> <svg-inner-html>
render() {
  local tier="$1"
  local body="$2"
  local html="$TMP_DIR/${tier}.html"
  cat > "$html" <<EOF
<!doctype html>
<html><head><meta charset="utf-8"><style>
html,body{margin:0;padding:0;background:transparent;width:64px;height:64px;overflow:hidden}
svg{display:block}
</style></head><body>
<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64">
${body}
</svg>
</body></html>
EOF
  # --headless=new + --default-background-color=00000000 → transparent PNG.
  # --hide-scrollbars + window 64x64 keeps the screenshot pixel-aligned.
  # cygpath converts the git-bash POSIX path to a Windows-rooted file://
  # URL; without this Chrome.exe sees /tmp/... and silently loads its
  # error page, then screenshots that.
  local win_url
  win_url=$(cygpath -m "$html")
  "$CHROME" \
    --headless=new \
    --disable-gpu \
    --hide-scrollbars \
    --no-sandbox \
    --default-background-color=00000000 \
    --window-size=64,64 \
    --virtual-time-budget=2000 \
    --screenshot="$TMP_DIR/${tier}.png" \
    "file:///$win_url" >/dev/null 2>&1
  cp "$TMP_DIR/${tier}.png" "$OUT_DIR/${tier}.png"
  echo "wrote ${tier}.png ($(wc -c <"$OUT_DIR/${tier}.png") bytes)"
}

# ── IRON ─────────────────────────────────────────────────────────────────────
render iron '
  <defs>
    <linearGradient id="iron-g" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#7a6a5a"/>
      <stop offset="50%" stop-color="#4a3f33"/>
      <stop offset="100%" stop-color="#231a14"/>
    </linearGradient>
    <radialGradient id="iron-h" cx="35%" cy="25%" r="40%">
      <stop offset="0%" stop-color="#fff" stop-opacity="0.25"/>
      <stop offset="100%" stop-color="#fff" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <path d="M 32 4 L 56 16 L 52 50 L 32 60 L 12 50 L 8 16 Z" fill="url(#iron-g)"/>
  <path d="M 32 4 L 56 16 L 52 50 L 32 60 L 12 50 L 8 16 Z" fill="url(#iron-h)"/>
  <path d="M 32 4 L 56 16 L 52 50 L 32 60 L 12 50 L 8 16 Z" fill="none" stroke="#0c0805" stroke-width="1.5"/>
  <circle cx="14" cy="18" r="1.6" fill="#0c0805"/>
  <circle cx="50" cy="18" r="1.6" fill="#0c0805"/>
  <circle cx="14" cy="46" r="1.6" fill="#0c0805"/>
  <circle cx="50" cy="46" r="1.6" fill="#0c0805"/>
  <circle cx="32" cy="8"  r="1.6" fill="#0c0805"/>
  <circle cx="22" cy="34" r="2.2" fill="#0c0805" opacity="0.35"/>
  <circle cx="38" cy="40" r="1.8" fill="#0c0805" opacity="0.35"/>
  <ellipse cx="40" cy="26" rx="3" ry="1.8" fill="#7a3a1a" opacity="0.45"/>
  <text x="32" y="44" text-anchor="middle" font-family="serif" font-weight="900" font-size="20" fill="#0c0805" opacity="0.7">I</text>
'

# ── BRONZE ───────────────────────────────────────────────────────────────────
render bronze '
  <defs>
    <radialGradient id="bronze-g" cx="38%" cy="32%" r="65%">
      <stop offset="0%" stop-color="#f0b878"/>
      <stop offset="50%" stop-color="#cd7f32"/>
      <stop offset="100%" stop-color="#5a2a08"/>
    </radialGradient>
  </defs>
  <path d="M 32 4 L 60 32 L 32 60 L 4 32 Z" fill="url(#bronze-g)"/>
  <path d="M 32 4 L 60 32 L 32 32 L 4 32 Z" fill="#fff" opacity="0.18"/>
  <path d="M 32 32 L 60 32 L 32 60 L 4 32 Z" fill="#000" opacity="0.18"/>
  <path d="M 32 14 L 50 32 L 32 50 L 14 32 Z" fill="none" stroke="#3a1a08" stroke-width="1.2" opacity="0.7"/>
  <path d="M 32 14 L 50 32 L 32 50 L 14 32 Z" fill="none" stroke="#fff" stroke-width="0.8" opacity="0.35"/>
  <text x="32" y="38" text-anchor="middle" font-family="serif" font-weight="900" font-size="16" fill="#3a1a08" opacity="0.8">II</text>
'

# ── SILVER ───────────────────────────────────────────────────────────────────
render silver '
  <defs>
    <radialGradient id="silver-g" cx="38%" cy="28%" r="60%">
      <stop offset="0%" stop-color="#fff"/>
      <stop offset="40%" stop-color="#d8d8d8"/>
      <stop offset="100%" stop-color="#5a5a5a"/>
    </radialGradient>
  </defs>
  <path d="M 8 18 Q 0 24 4 38 Q 6 28 12 30 Z" fill="url(#silver-g)" opacity="0.85"/>
  <path d="M 56 18 Q 64 24 60 38 Q 58 28 52 30 Z" fill="url(#silver-g)" opacity="0.85"/>
  <path d="M 32 4 L 56 16 L 56 48 L 32 60 L 8 48 L 8 16 Z" fill="url(#silver-g)"/>
  <path d="M 32 4 L 56 16 L 32 32 L 8 16 Z" fill="#fff" opacity="0.22"/>
  <path d="M 32 10 L 50 20 L 50 44 L 32 54 L 14 44 L 14 20 Z" fill="none" stroke="#4a4a4a" stroke-width="0.8" opacity="0.7"/>
  <text x="32" y="40" text-anchor="middle" font-family="serif" font-weight="900" font-size="14" fill="#2a2a2a">III</text>
'

# ── GOLD ─────────────────────────────────────────────────────────────────────
render gold '
  <defs>
    <radialGradient id="gold-g" cx="40%" cy="30%" r="65%">
      <stop offset="0%" stop-color="#fff7c2"/>
      <stop offset="40%" stop-color="#FFD700"/>
      <stop offset="100%" stop-color="#7a5a00"/>
    </radialGradient>
    <radialGradient id="gold-c" cx="50%" cy="40%" r="50%">
      <stop offset="0%" stop-color="#ffb3b3"/>
      <stop offset="100%" stop-color="#a00000"/>
    </radialGradient>
  </defs>
  <circle cx="32" cy="32" r="26" fill="none" stroke="#FFD700" stroke-opacity="0.35" stroke-width="0.8"/>
  <polygon points="32,4 39,24 60,24 43,36 50,56 32,44 14,56 21,36 4,24 25,24" fill="url(#gold-g)"/>
  <polygon points="32,12 36,24 47,24 38,32 42,44 32,36 22,44 26,32 17,24 28,24" fill="#fff" opacity="0.22"/>
  <circle cx="32" cy="32" r="4" fill="url(#gold-c)"/>
  <circle cx="31" cy="31" r="1.5" fill="#fff" opacity="0.8"/>
  <text x="32" y="60" text-anchor="middle" font-family="serif" font-weight="900" font-size="9" fill="#7a5a00" opacity="0.85">IV</text>
'

# ── PLATINUM ─────────────────────────────────────────────────────────────────
render platinum '
  <defs>
    <radialGradient id="plat-g" cx="38%" cy="28%" r="60%">
      <stop offset="0%" stop-color="#e0ffff"/>
      <stop offset="45%" stop-color="#00CED1"/>
      <stop offset="100%" stop-color="#003a4a"/>
    </radialGradient>
  </defs>
  <polygon points="32,4 56,18 56,46 32,60 8,46 8,18" fill="#0e1f24"/>
  <polygon points="32,4 56,18 56,46 32,60 8,46 8,18" fill="none" stroke="#00CED1" stroke-opacity="0.6" stroke-width="0.8"/>
  <polygon points="32,10 50,22 50,42 32,54 14,42 14,22" fill="url(#plat-g)"/>
  <line x1="32" y1="10" x2="32" y2="54" stroke="#fff" stroke-opacity="0.45" stroke-width="0.8"/>
  <line x1="14" y1="22" x2="50" y2="42" stroke="#fff" stroke-opacity="0.22" stroke-width="0.6"/>
  <line x1="50" y1="22" x2="14" y2="42" stroke="#fff" stroke-opacity="0.22" stroke-width="0.6"/>
  <polygon points="32,10 50,22 32,30 14,22" fill="#fff" opacity="0.22"/>
  <polygon points="32,10 50,22 50,42 32,54 14,42 14,22" fill="none" stroke="#00CED1" stroke-width="1.5" stroke-opacity="0.5"/>
  <text x="32" y="42" text-anchor="middle" font-family="serif" font-weight="900" font-size="11" fill="#003a4a" opacity="0.8">V</text>
'

# ── DIAMOND ──────────────────────────────────────────────────────────────────
render diamond '
  <defs>
    <radialGradient id="dia-g" cx="40%" cy="30%" r="60%">
      <stop offset="0%" stop-color="#fff"/>
      <stop offset="45%" stop-color="#00BFFF"/>
      <stop offset="100%" stop-color="#003a66"/>
    </radialGradient>
  </defs>
  <polygon points="32,4 16,22 48,22" fill="url(#dia-g)"/>
  <polygon points="16,22 32,30 32,4" fill="#fff" opacity="0.32"/>
  <polygon points="48,22 32,30 32,4" fill="#fff" opacity="0.18"/>
  <polygon points="4,30 16,22 32,30" fill="url(#dia-g)" opacity="0.85"/>
  <polygon points="60,30 48,22 32,30" fill="url(#dia-g)" opacity="0.7"/>
  <polygon points="4,30 32,30 32,60" fill="url(#dia-g)" opacity="0.78"/>
  <polygon points="60,30 32,30 32,60" fill="url(#dia-g)" opacity="0.62"/>
  <line x1="32" y1="4" x2="32" y2="60" stroke="#fff" stroke-opacity="0.4" stroke-width="0.6"/>
  <line x1="4" y1="30" x2="60" y2="30" stroke="#fff" stroke-opacity="0.35" stroke-width="0.5"/>
  <circle cx="20" cy="12" r="1.2" fill="#fff" opacity="0.7"/>
  <circle cx="44" cy="30" r="1.2" fill="#fff" opacity="0.6"/>
  <circle cx="14" cy="40" r="1.2" fill="#fff" opacity="0.55"/>
  <circle cx="48" cy="46" r="1.2" fill="#fff" opacity="0.55"/>
'

# ── MASTER ───────────────────────────────────────────────────────────────────
render master '
  <defs>
    <radialGradient id="master-g" cx="40%" cy="30%" r="60%">
      <stop offset="0%" stop-color="#d8a8e8"/>
      <stop offset="50%" stop-color="#9B59B6"/>
      <stop offset="100%" stop-color="#3a0a52"/>
    </radialGradient>
  </defs>
  <path d="M 8 28 Q -2 24 2 42 Q 8 32 14 36 Z" fill="url(#master-g)" opacity="0.85"/>
  <path d="M 4 32 Q 10 30 12 38" stroke="#fff" stroke-width="0.5" stroke-opacity="0.4" fill="none"/>
  <path d="M 56 28 Q 66 24 62 42 Q 56 32 50 36 Z" fill="url(#master-g)" opacity="0.85"/>
  <path d="M 60 32 Q 54 30 52 38" stroke="#fff" stroke-width="0.5" stroke-opacity="0.4" fill="none"/>
  <path d="M 32 8 L 50 16 L 50 40 Q 50 54 32 60 Q 14 54 14 40 L 14 16 Z" fill="url(#master-g)"/>
  <path d="M 32 8 L 50 16 L 50 26 L 32 22 L 14 26 L 14 16 Z" fill="#fff" opacity="0.2"/>
  <path d="M 32 16 L 44 22 L 44 40 Q 44 50 32 54 Q 20 50 20 40 L 20 22 Z" fill="none" stroke="#fff" stroke-opacity="0.38" stroke-width="0.8"/>
  <path d="M 26 8 L 32 4 L 38 8 L 36 14 L 28 14 Z" fill="#FFD700"/>
  <circle cx="32" cy="6" r="1.6" fill="#fff"/>
  <circle cx="28" cy="11" r="0.8" fill="#fff" opacity="0.9"/>
  <circle cx="36" cy="11" r="0.8" fill="#fff" opacity="0.9"/>
'

# ── GRANDMASTER ──────────────────────────────────────────────────────────────
render grandmaster '
  <defs>
    <radialGradient id="gm-g" cx="40%" cy="30%" r="60%">
      <stop offset="0%" stop-color="#ff8a8a"/>
      <stop offset="50%" stop-color="#E74C3C"/>
      <stop offset="100%" stop-color="#5a0a0a"/>
    </radialGradient>
    <linearGradient id="gm-c" x1="0%" y1="0%" x2="0%" y2="100%">
      <stop offset="0%" stop-color="#fff7c2"/>
      <stop offset="100%" stop-color="#a87a00"/>
    </linearGradient>
  </defs>
  <path d="M 12 18 L 18 8 L 22 16 L 28 6 L 32 16 L 36 6 L 42 16 L 46 8 L 52 18 L 12 18 Z" fill="url(#gm-c)"/>
  <rect x="12" y="18" width="40" height="3" fill="#a87a00"/>
  <circle cx="22" cy="16" r="1.5" fill="#E74C3C"/>
  <circle cx="32" cy="16" r="2" fill="#fff"/>
  <circle cx="42" cy="16" r="1.5" fill="#E74C3C"/>
  <path d="M 12 22 L 52 22 L 50 44 Q 48 56 32 62 Q 16 56 14 44 Z" fill="url(#gm-g)"/>
  <rect x="30" y="28" width="4" height="22" fill="#FFD700" opacity="0.45"/>
  <rect x="22" y="34" width="20" height="4" fill="#FFD700" opacity="0.45"/>
  <path d="M 12 22 L 52 22 L 50 30 L 32 28 L 14 30 Z" fill="#fff" opacity="0.2"/>
'

# ── CHALLENGER ───────────────────────────────────────────────────────────────
render challenger '
  <defs>
    <radialGradient id="ch-g" cx="40%" cy="30%" r="60%">
      <stop offset="0%" stop-color="#fff7c2"/>
      <stop offset="50%" stop-color="#F1C40F"/>
      <stop offset="100%" stop-color="#7a5a00"/>
    </radialGradient>
    <linearGradient id="ch-l" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#5e8e3e"/>
      <stop offset="100%" stop-color="#264a16"/>
    </linearGradient>
  </defs>
  <circle cx="32" cy="32" r="28" fill="none" stroke="#F1C40F" stroke-opacity="0.35" stroke-width="0.8"/>
  <path d="M 6 32 Q 0 18 8 6" stroke="url(#ch-l)" stroke-width="2" fill="none" stroke-linecap="round"/>
  <ellipse cx="6" cy="24" rx="2.4" ry="3.6" fill="url(#ch-l)" transform="rotate(-30 6 24)"/>
  <ellipse cx="4" cy="16" rx="2.2" ry="3.4" fill="url(#ch-l)" transform="rotate(-15 4 16)"/>
  <ellipse cx="8" cy="8"  rx="2"   ry="3"   fill="url(#ch-l)"/>
  <path d="M 58 32 Q 64 18 56 6" stroke="url(#ch-l)" stroke-width="2" fill="none" stroke-linecap="round"/>
  <ellipse cx="58" cy="24" rx="2.4" ry="3.6" fill="url(#ch-l)" transform="rotate(30 58 24)"/>
  <ellipse cx="60" cy="16" rx="2.2" ry="3.4" fill="url(#ch-l)" transform="rotate(15 60 16)"/>
  <ellipse cx="56" cy="8"  rx="2"   ry="3"   fill="url(#ch-l)"/>
  <path d="M 16 16 L 48 16 L 46 36 Q 40 44 32 44 Q 24 44 18 36 Z" fill="url(#ch-g)"/>
  <ellipse cx="32" cy="16" rx="16" ry="3" fill="#FFE066"/>
  <ellipse cx="32" cy="16" rx="16" ry="3" fill="none" stroke="#7a5a00" stroke-width="0.6"/>
  <path d="M 16 18 Q 8 22 12 32" stroke="url(#ch-g)" stroke-width="2.5" fill="none"/>
  <path d="M 48 18 Q 56 22 52 32" stroke="url(#ch-g)" stroke-width="2.5" fill="none"/>
  <rect x="28" y="44" width="8"  height="6" fill="url(#ch-g)"/>
  <rect x="20" y="50" width="24" height="6" rx="1" fill="url(#ch-g)"/>
  <polygon points="32,20 34,26 40,26 35,30 37,36 32,32 27,36 29,30 24,26 30,26" fill="#fff" opacity="0.75"/>
  <circle cx="48" cy="22" r="1" fill="#fff" opacity="0.9"/>
  <circle cx="14" cy="36" r="1" fill="#fff" opacity="0.7"/>
  <circle cx="44" cy="40" r="0.8" fill="#fff" opacity="0.85"/>
'

# ── SOVEREIGN ────────────────────────────────────────────────────────────────
render sovereign '
  <defs>
    <radialGradient id="sov-g" cx="50%" cy="38%" r="60%">
      <stop offset="0%" stop-color="#fff"/>
      <stop offset="50%" stop-color="#f4f1ff"/>
      <stop offset="100%" stop-color="#a098b0"/>
    </radialGradient>
    <radialGradient id="sov-h" cx="50%" cy="50%" r="50%">
      <stop offset="0%" stop-color="#fff" stop-opacity="0.55"/>
      <stop offset="50%" stop-color="#fff" stop-opacity="0.18"/>
      <stop offset="100%" stop-color="#fff" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <circle cx="32" cy="32" r="30" fill="url(#sov-h)"/>
  <line x1="32" y1="2" x2="32" y2="6"  stroke="#fff" stroke-width="1.4" stroke-opacity="0.85"/>
  <line x1="32" y1="2" x2="32" y2="6"  stroke="#fff" stroke-width="1.4" stroke-opacity="0.85" transform="rotate(45 32 32)"/>
  <line x1="32" y1="2" x2="32" y2="6"  stroke="#fff" stroke-width="1.4" stroke-opacity="0.85" transform="rotate(90 32 32)"/>
  <line x1="32" y1="2" x2="32" y2="6"  stroke="#fff" stroke-width="1.4" stroke-opacity="0.85" transform="rotate(135 32 32)"/>
  <line x1="32" y1="2" x2="32" y2="6"  stroke="#fff" stroke-width="1.4" stroke-opacity="0.85" transform="rotate(180 32 32)"/>
  <line x1="32" y1="2" x2="32" y2="6"  stroke="#fff" stroke-width="1.4" stroke-opacity="0.85" transform="rotate(225 32 32)"/>
  <line x1="32" y1="2" x2="32" y2="6"  stroke="#fff" stroke-width="1.4" stroke-opacity="0.85" transform="rotate(270 32 32)"/>
  <line x1="32" y1="2" x2="32" y2="6"  stroke="#fff" stroke-width="1.4" stroke-opacity="0.85" transform="rotate(315 32 32)"/>
  <path d="M 12 30 L 18 16 L 22 26 L 28 12 L 32 24 L 36 12 L 42 26 L 46 16 L 52 30 L 12 30 Z" fill="url(#sov-g)"/>
  <rect x="12" y="30" width="40" height="6"   fill="url(#sov-g)"/>
  <rect x="12" y="30" width="40" height="1.5" fill="#fff" opacity="0.7"/>
  <circle cx="22" cy="22" r="1.6" fill="#FFD700"/>
  <circle cx="32" cy="20" r="2.2" fill="#00d4ff"/>
  <circle cx="32" cy="20" r="2.2" fill="none" stroke="#fff" stroke-opacity="0.7" stroke-width="0.5"/>
  <circle cx="42" cy="22" r="1.6" fill="#FFD700"/>
  <path d="M 16 36 L 48 36 L 46 44 Q 32 50 18 44 Z" fill="url(#sov-g)" opacity="0.65"/>
  <path d="M 12 30 L 32 24 L 52 30 Z" fill="#fff" opacity="0.32"/>
'

# ── UNRANKED ─────────────────────────────────────────────────────────────────
# Custom badge for backend "UNRANKED" string (placement complete but no tier
# computed yet, or never queued ranked). Question mark on a muted shield.
render unranked '
  <defs>
    <linearGradient id="un-g" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#7a7a90"/>
      <stop offset="100%" stop-color="#2a2a3a"/>
    </linearGradient>
  </defs>
  <path d="M 32 6 L 54 16 L 50 48 L 32 58 L 14 48 L 10 16 Z" fill="url(#un-g)"/>
  <path d="M 32 6 L 54 16 L 50 48 L 32 58 L 14 48 L 10 16 Z" fill="none" stroke="#1a1a26" stroke-width="1.5"/>
  <text x="32" y="42" text-anchor="middle" font-family="serif" font-weight="900" font-size="28" fill="#1a1a26" opacity="0.85">?</text>
'

echo "done; PNGs written to $OUT_DIR"
