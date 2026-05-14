// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.kit;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public enum Kit {
    FIST     ("Fist",     "Raw hand-to-hand. No items. Sprint timing and crits decide.",     Items.PLAYER_HEAD),
    SWORD    ("Sword",    "Netherite sword + armor + golden apples. Classic SMP kit.",       Items.NETHERITE_SWORD),
    ARCHER   ("Archer",   "Power V bow focus. Kite, punish, retreat.",                       Items.BOW),
    MACE     ("Mace",     "1.21 mace + shield + iron armor. Ground slam specialists.",       Items.MACE),
    CRYSTAL  ("Crystal",  "End crystals + totems + obsidian. Highest skill cap.",            Items.END_CRYSTAL),
    SPEAR    ("Spear",    "Iron Spear polearm, charge attacks + extended reach. Sharpness V.", Items.IRON_SWORD),
    TRIDENT  ("Trident",  "Throwing trident, Loyalty + Riptide. Cast, retrieve, repeat.",     Items.TRIDENT),
    TNT      ("TNT",      "Flint + 64x TNT + TNT carts on powered rails. Detonate openings.",  Items.TNT),
    CROSSBOW ("Crossbow", "Multishot V crossbow + iron sword backup. Bolt-and-bait combat.",   Items.CROSSBOW),
    MIXED    ("Mixed",    "Custom kit blend. Available for custom duels only.",              Items.CRAFTING_TABLE);

    public final String display;
    public final String description;
    public final Item icon;

    Kit(String display, String description, Item icon) {
        this.display = display;
        this.description = description;
        this.icon = icon;
    }

    public boolean isRankable() { return this != MIXED; }
}