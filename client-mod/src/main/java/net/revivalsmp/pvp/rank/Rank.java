// SPDX-License-Identifier: LicenseRef-PolyForm-NC-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.rank;

public enum Rank {
    UNRANKED  ("Unranked",    0x444455, false),
    IRON      ("Iron",        0x8B7355, true),
    BRONZE    ("Bronze",      0xCD7F32, true),
    SILVER    ("Silver",      0xC0C0C0, true),
    GOLD      ("Gold",        0xFFD700, true),
    PLATINUM  ("Platinum",    0x00CED1, true),
    DIAMOND   ("Diamond",     0x00BFFF, true),
    MASTER    ("Master",      0x9B59B6, false),
    GRANDMASTER("Grandmaster",0xE74C3C, false),
    CHALLENGER("Challenger",  0xF1C40F, false);

    public final String display;
    public final int color;
    public final boolean hasDivisions;

    Rank(String display, int color, boolean hasDivisions) {
        this.display = display;
        this.color = color;
        this.hasDivisions = hasDivisions;
    }

    public boolean isApex() { return this == MASTER || this == GRANDMASTER || this == CHALLENGER; }
}
