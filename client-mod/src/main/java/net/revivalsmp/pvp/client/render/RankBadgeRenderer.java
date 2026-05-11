// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.client.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * Pixel-art rank badges drawn entirely with {@code g.fill} calls so we don't
 * need to ship a texture pack with the mod.
 *
 * <p>Each tier is a 14x14 pattern using a small palette:
 * <pre>
 *   '.' = transparent
 *   '#' = base tier color
 *   '*' = highlight (brighter, top-left lit)
 *   'd' = dark accent (engrave / outline)
 *   'o' = orb / gem center (white-ish)
 * </pre>
 *
 * <p>The renderer scales the pattern by {@code size / 14}, rounded to the
 * nearest int, so a request for size=22 paints each cell as a 1x1 quad with
 * a tighter inner cell. Anything south of size=14 collapses cells together.
 *
 * <p>Inspired by the website's {@code RankBadge} SVG (PvpIcons.jsx) but
 * redrawn for low-res visibility, the SVGs are 64x64 and don't downscale
 * legibly to 14px.
 */
public final class RankBadgeRenderer {

    private RankBadgeRenderer() {}

    private static final int GRID = 14;

    public static void render(GuiGraphicsExtractor g, String tier, int x, int y, int size) {
        if (g == null) return;
        String key = tier == null ? "" : tier.toUpperCase();

        // Prefer the high-fidelity PNG texture (rendered from the website's
        // RankBadge SVG via scripts/rank_badges/render.sh). Falls through to
        // the pixel-pattern path if blit throws — registries aren't bound on
        // the title screen, and we want the badge to still appear.
        Identifier tex = textureFor(key);
        if (tex != null) {
            try {
                g.blit(RenderPipelines.GUI_TEXTURED, tex,
                    x, y, 0f, 0f, size, size, 64, 64, 64, 64);
                return;
            } catch (Throwable ignored) {
                // fall through to pattern fallback
            }
        }

        String[] pat = patternFor(key);
        Palette p = paletteFor(key);
        if (pat == null || p == null) return;

        // Each pattern cell is rendered as a (size / GRID)-sized square so
        // the badge fills the requested box. fractional cell sizes are
        // handled by computing the cell-edge in floor-rounded fashion;
        // this avoids 1px gaps without overdraw.
        for (int row = 0; row < GRID; row++) {
            String line = pat[row];
            for (int col = 0; col < GRID; col++) {
                char c = col < line.length() ? line.charAt(col) : '.';
                int color = colorFor(c, p);
                if (color == 0) continue;
                int x0 = x + (col * size) / GRID;
                int x1 = x + ((col + 1) * size) / GRID;
                int y0 = y + (row * size) / GRID;
                int y1 = y + ((row + 1) * size) / GRID;
                if (x1 <= x0) x1 = x0 + 1;
                if (y1 <= y0) y1 = y0 + 1;
                g.fill(x0, y0, x1, y1, color);
            }
        }
    }

    /** Resolve the resource identifier for a tier's PNG. Lowercase the tier
     *  name and look under {@code assets/revivalpvp/textures/gui/rank/}.
     *  Returns null for blank input so the caller falls through to the
     *  pattern path. */
    private static Identifier textureFor(String tier) {
        if (tier == null || tier.isBlank()) return null;
        String slug = tier.toLowerCase(Locale.ROOT);
        try {
            return Identifier.fromNamespaceAndPath(
                "revivalpvp", "textures/gui/rank/" + slug + ".png");
        } catch (Throwable t) {
            return null;
        }
    }

    private static int colorFor(char c, Palette p) {
        return switch (c) {
            case '#' -> p.base;
            case '*' -> p.hi;
            case 'd' -> p.dark;
            case 'o' -> p.gem;
            default  -> 0;
        };
    }

    private record Palette(int base, int hi, int dark, int gem) {}

    private static Palette paletteFor(String tier) {
        return switch (tier) {
            case "IRON"        -> new Palette(0xFFB0A89A, 0xFFD7D2C4, 0xFF55503F, 0xFFE0DAC8);
            case "BRONZE"      -> new Palette(0xFFB87333, 0xFFE0A560, 0xFF5A3010, 0xFFFFD9A0);
            case "SILVER"      -> new Palette(0xFFC8C8D0, 0xFFFFFFFF, 0xFF505058, 0xFFFFFFFF);
            case "GOLD"        -> new Palette(0xFFD4A85A, 0xFFFFE38A, 0xFF5A3F00, 0xFFFFF2A8);
            case "PLATINUM"    -> new Palette(0xFF7FE0C0, 0xFFC9FFEC, 0xFF1F5A4A, 0xFFFFFFFF);
            case "DIAMOND"     -> new Palette(0xFF66E0FF, 0xFFC8F7FF, 0xFF1A4566, 0xFFFFFFFF);
            case "MASTER"      -> new Palette(0xFFB37AE8, 0xFFE6BFFF, 0xFF3A1A5A, 0xFFFFD9FF);
            case "GRANDMASTER" -> new Palette(0xFFFF6A4D, 0xFFFFB29A, 0xFF5A1A0A, 0xFFFFE0D0);
            case "CHALLENGER"  -> new Palette(0xFFFFE07A, 0xFFFFF6CC, 0xFF5A4500, 0xFFFFFFFF);
            case "SOVEREIGN"   -> new Palette(0xFFFFFFFF, 0xFFFFFFFF, 0xFF606078, 0xFFFFFFC0);
            case "UNRANKED"    -> new Palette(0xFF606078, 0xFF8080A0, 0xFF202028, 0xFF8080A0);
            default            -> new Palette(0xFF606078, 0xFF8080A0, 0xFF202028, 0xFF8080A0);
        };
    }

    /** 14x14 patterns, top-down. Each shape designed to be readable at 14-22px. */
    private static String[] patternFor(String tier) {
        return switch (tier) {
            // Hex shield, rough hammered iron
            case "IRON" -> new String[] {
                "....####......",
                "...######.....",
                "..########....",
                ".##########...",
                ".##*#######...",
                ".##*#######...",
                ".#########d...",
                ".#########d...",
                ".#########d...",
                "..########....",
                "...######d....",
                "....####d.....",
                ".....##d......",
                "..............",
            };
            // Diamond (rotated square), polished bronze
            case "BRONZE" -> new String[] {
                "......##......",
                ".....####.....",
                "....#####d....",
                "...###***d....",
                "..#####**dd...",
                ".######**dd...",
                "########ddd...",
                ".########dd...",
                "..########d...",
                "...#######....",
                "....#####.....",
                ".....###......",
                "......#.......",
                "..............",
            };
            // Hex shield with side wings, polished silver
            case "SILVER" -> new String[] {
                "..............",
                ".#..######..#.",
                "##.########.##",
                "###*#######*##",
                "###*########d#",
                "##*##########d",
                ".###########d.",
                ".###########d.",
                "..#########d..",
                "...########...",
                "....#######...",
                ".....#####....",
                "......###.....",
                ".......#......",
            };
            // 5-point star with ruby center, gold
            case "GOLD" -> new String[] {
                "..............",
                "......##......",
                "......##......",
                ".....####.....",
                "##############",
                ".###*****####.",
                "..####ooo###..",
                "...##ooooo##..",
                "....##ood##...",
                "...##d##d###..",
                "..##d#d##d##d.",
                ".#d##....##d#.",
                "..............",
                "..............",
            };
            // Inverted triangle / chevron wing, platinum
            case "PLATINUM" -> new String[] {
                "..............",
                "##############",
                ".############.",
                "..##*#####d##.",
                "...#*#####d#..",
                "....#####dd...",
                ".....######...",
                "......####....",
                "......####....",
                ".......##.....",
                ".......##.....",
                "........#.....",
                "..............",
                "..............",
            };
            // Vertical rhombus gem with facet line, diamond blue
            case "DIAMOND" -> new String[] {
                "..............",
                "......##......",
                ".....****.....",
                "....##**##....",
                "...##*****##..",
                "..##*******##.",
                ".###########d.",
                "..#########d..",
                "...########...",
                "....#####d....",
                ".....####d....",
                "......##d.....",
                ".......d......",
                "..............",
            };
            // 3-spike crown, master
            case "MASTER" -> new String[] {
                "..............",
                "..#........#..",
                "..#...##...#..",
                "..#..####..#..",
                "..#*.####.*#..",
                "..#*######*#..",
                "..#********#..",
                "..############",
                "..############",
                "..#dddddddd##.",
                "..#oo#oo#oo##.",
                "..############",
                "..............",
                "..............",
            };
            // 5-spike crown, grandmaster
            case "GRANDMASTER" -> new String[] {
                "..............",
                "..#..#.##.#..#",
                "..#..#####..#.",
                "..#*##ooo##*#.",
                "..#*##ooo##*#.",
                "..############",
                "..#**********#",
                "..############",
                "..#oo#oo#oo##.",
                "..############",
                "..#dddddddd##.",
                "..############",
                "..............",
                "..............",
            };
            // Crown with star, challenger
            case "CHALLENGER" -> new String[] {
                "..............",
                "......##......",
                ".#.##*##*##.#.",
                ".#*########*#.",
                ".#*##oooo##*#.",
                ".############.",
                ".#**********#.",
                ".############.",
                ".#oo#oo#oo###.",
                ".############.",
                ".#dddddddd###.",
                ".############.",
                "..............",
                "..............",
            };
            // Solid orb with starburst, sovereign
            case "SOVEREIGN" -> new String[] {
                "......##......",
                "......##......",
                "..#...##...#..",
                "...#..##..#...",
                "...##*##*##...",
                "....######....",
                "##.##oooo##.##",
                "##.##oooo##.##",
                "....######....",
                "...##*##*##...",
                "...#..##..#...",
                "..#...##...#..",
                "......##......",
                "......##......",
            };
            // Question-mark "in placement" badge. Used both for the literal
            // "UNRANKED" tier the backend returns before a player places into
            // a tier, and as a fallback for any unknown string the caller
            // hands us. Better than rendering an empty box.
            case "UNRANKED" -> new String[] {
                "..............",
                ".....####.....",
                "....##..##....",
                "...##....##...",
                ".........##...",
                "........##....",
                ".......##.....",
                "......##......",
                "......##......",
                "..............",
                "..............",
                "......##......",
                "......##......",
                "..............",
            };
            default -> new String[] {
                "..............",
                ".....####.....",
                "....##..##....",
                "...##....##...",
                ".........##...",
                "........##....",
                ".......##.....",
                "......##......",
                "......##......",
                "..............",
                "..............",
                "......##......",
                "......##......",
                "..............",
            };
        };
    }
}
