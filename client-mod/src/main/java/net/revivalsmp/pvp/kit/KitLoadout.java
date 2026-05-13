// SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
// Copyright (c) 2026 RevivalSMP. See LICENSE.md and NOTICE.md.

package net.revivalsmp.pvp.kit;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Static registry mapping each {@link Kit} to a list of {@link LoadoutSlot} entries
 * describing what the player will spawn with for that kit.
 *
 * <p>Used by the queue tab's 3D player viewer to render the equipped gear, and by
 * the hover-tooltip system to surface item descriptions.
 */
public final class KitLoadout {

    private KitLoadout() {}

    /** Equipment slot identifier. Stable for hover-region mapping. */
    public enum Slot {
        HEAD, CHEST, LEGS, FEET,
        MAINHAND, OFFHAND,
        HOTBAR1, HOTBAR2, HOTBAR3, HOTBAR4, HOTBAR5
    }

    /** A single equipped item in a kit. */
    public static final class LoadoutSlot {
        public final Slot slot;
        public final ItemStack item;
        public final String displayName;
        public final String description;

        public LoadoutSlot(Slot slot, ItemStack item, String displayName, String description) {
            this.slot = slot;
            this.item = item;
            this.displayName = displayName;
            this.description = description;
        }
    }

    private static final Map<Kit, List<LoadoutSlot>> LOADOUTS = new EnumMap<>(Kit.class);
    /** Tracks whether the (registry-touching) initializer has completed. We
     *  defer it until first call so the title-screen-opened hub doesn't crash
     *  with "Components not bound yet" when item registries aren't loaded. */
    private static volatile boolean initialized = false;

    private static synchronized void initIfNeeded() {
        if (initialized) return;
        try {
            populate();
            initialized = true;
        } catch (Throwable t) {
            // Item registry not ready, leave LOADOUTS empty and try again
            // next call. Caller will see Collections.emptyList() and skip
            // item rendering safely.
            LOADOUTS.clear();
        }
    }

    private static void populate() {
        // FIST, empty hands, no armor
        LOADOUTS.put(Kit.FIST, List.of(
            new LoadoutSlot(Slot.MAINHAND, ItemStack.EMPTY, "Bare Fists", "No weapon. Crits and sprint resets are everything.")
        ));

        // SWORD, full netherite + sword + 16 gapples
        {
            List<LoadoutSlot> l = new ArrayList<>();
            l.add(new LoadoutSlot(Slot.HEAD,     new ItemStack(Items.NETHERITE_HELMET),     "Netherite Helmet",     "Prot IV. Reduces incoming damage."));
            l.add(new LoadoutSlot(Slot.CHEST,    new ItemStack(Items.NETHERITE_CHESTPLATE), "Netherite Chestplate", "Prot IV. The bulk of your damage soak."));
            l.add(new LoadoutSlot(Slot.LEGS,     new ItemStack(Items.NETHERITE_LEGGINGS),   "Netherite Leggings",   "Prot IV. Crit-soak when low."));
            l.add(new LoadoutSlot(Slot.FEET,     new ItemStack(Items.NETHERITE_BOOTS),      "Netherite Boots",      "Prot IV + Feather Falling IV."));
            l.add(new LoadoutSlot(Slot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD),      "Netherite Sword",      "Sharpness V. 8 base + crit damage."));
            l.add(new LoadoutSlot(Slot.HOTBAR1,  itemWithCount(Items.GOLDEN_APPLE, 16),     "Golden Apple x16",     "Absorption + Regen II. Right-click to eat."));
            l.add(new LoadoutSlot(Slot.HOTBAR2,  itemWithCount(Items.OAK_PLANKS, 32),       "Oak Planks x32",       "Construction blocks for cover + pillars."));
            l.add(new LoadoutSlot(Slot.HOTBAR3,  itemWithCount(Items.COBWEB, 8),            "Cobweb x8",            "Defensive trap, slows opponents."));
            LOADOUTS.put(Kit.SWORD, l);
        }

        // ARCHER, leather armor, bow + arrows + iron sword + offhand gapple
        {
            List<LoadoutSlot> l = new ArrayList<>();
            l.add(new LoadoutSlot(Slot.HEAD,     new ItemStack(Items.LEATHER_HELMET),     "Leather Helmet",     "Light armor. Prot III."));
            l.add(new LoadoutSlot(Slot.CHEST,    new ItemStack(Items.LEATHER_CHESTPLATE), "Leather Chestplate", "Light armor. Prot III."));
            l.add(new LoadoutSlot(Slot.LEGS,     new ItemStack(Items.LEATHER_LEGGINGS),   "Leather Leggings",   "Light armor. Prot III."));
            l.add(new LoadoutSlot(Slot.FEET,     new ItemStack(Items.LEATHER_BOOTS),      "Leather Boots",      "Light armor + Feather Falling IV."));
            l.add(new LoadoutSlot(Slot.MAINHAND, new ItemStack(Items.BOW),                "Power V Bow",        "Punch II + Power V. Long-range pressure."));
            l.add(new LoadoutSlot(Slot.OFFHAND,  new ItemStack(Items.GOLDEN_APPLE),       "Golden Apple",       "Quick offhand heal."));
            l.add(new LoadoutSlot(Slot.HOTBAR1,  itemWithCount(Items.ARROW, 64),          "Arrow x64",          "Ammo for the bow."));
            l.add(new LoadoutSlot(Slot.HOTBAR2,  new ItemStack(Items.IRON_SWORD),         "Iron Sword",         "Backup melee for close encounters."));
            l.add(new LoadoutSlot(Slot.HOTBAR3,  itemWithCount(Items.OAK_PLANKS, 32),     "Oak Planks x32",     "Construction blocks for sightlines."));
            l.add(new LoadoutSlot(Slot.HOTBAR4,  itemWithCount(Items.COBWEB, 8),          "Cobweb x8",          "Slow opponents to land arrows."));
            LOADOUTS.put(Kit.ARCHER, l);
        }

        // MACE, iron armor, mace mainhand, shield offhand, ender pearls + gapples
        {
            List<LoadoutSlot> l = new ArrayList<>();
            l.add(new LoadoutSlot(Slot.HEAD,     new ItemStack(Items.IRON_HELMET),       "Iron Helmet",       "Prot IV."));
            l.add(new LoadoutSlot(Slot.CHEST,    new ItemStack(Items.IRON_CHESTPLATE),   "Iron Chestplate",   "Prot IV."));
            l.add(new LoadoutSlot(Slot.LEGS,     new ItemStack(Items.IRON_LEGGINGS),     "Iron Leggings",     "Prot IV."));
            l.add(new LoadoutSlot(Slot.FEET,     new ItemStack(Items.IRON_BOOTS),        "Iron Boots",        "Prot IV + Feather Falling IV (mandatory for slam)."));
            l.add(new LoadoutSlot(Slot.MAINHAND, new ItemStack(Items.MACE),              "Mace",              "Density V + Wind Burst III. Slam from height."));
            l.add(new LoadoutSlot(Slot.OFFHAND,  new ItemStack(Items.SHIELD),            "Shield",            "Block crits and projectile pressure."));
            l.add(new LoadoutSlot(Slot.HOTBAR1,  itemWithCount(Items.ENDER_PEARL, 4),    "Ender Pearl x4",    "Position for slams."));
            l.add(new LoadoutSlot(Slot.HOTBAR2,  itemWithCount(Items.GOLDEN_APPLE, 8),   "Golden Apple x8",   "Heal between slams."));
            l.add(new LoadoutSlot(Slot.HOTBAR3,  itemWithCount(Items.OAK_PLANKS, 32),    "Oak Planks x32",    "Construction blocks, pillar up for slams."));
            l.add(new LoadoutSlot(Slot.HOTBAR4,  itemWithCount(Items.COBWEB, 8),         "Cobweb x8",         "Defensive trap, slows opponents."));
            LOADOUTS.put(Kit.MACE, l);
        }

        // CRYSTAL, full netherite, end crystal mainhand, totem offhand, obsidian + anchors + glowstone
        {
            List<LoadoutSlot> l = new ArrayList<>();
            l.add(new LoadoutSlot(Slot.HEAD,     new ItemStack(Items.NETHERITE_HELMET),     "Netherite Helmet",     "Prot IV. Crystal blast soak."));
            l.add(new LoadoutSlot(Slot.CHEST,    new ItemStack(Items.NETHERITE_CHESTPLATE), "Netherite Chestplate", "Prot IV. Crystal blast soak."));
            l.add(new LoadoutSlot(Slot.LEGS,     new ItemStack(Items.NETHERITE_LEGGINGS),   "Netherite Leggings",   "Prot IV."));
            l.add(new LoadoutSlot(Slot.FEET,     new ItemStack(Items.NETHERITE_BOOTS),      "Netherite Boots",      "Prot IV + Feather Falling IV."));
            l.add(new LoadoutSlot(Slot.MAINHAND, new ItemStack(Items.END_CRYSTAL),          "End Crystal",          "Place + detonate. Anchor combos for full damage."));
            l.add(new LoadoutSlot(Slot.OFFHAND,  new ItemStack(Items.TOTEM_OF_UNDYING),     "Totem of Undying",     "Auto-revive when killed. Cycle on hit."));
            l.add(new LoadoutSlot(Slot.HOTBAR1,  itemWithCount(Items.OBSIDIAN, 32),         "Obsidian x32",         "Crystal placement surface."));
            l.add(new LoadoutSlot(Slot.HOTBAR2,  itemWithCount(Items.RESPAWN_ANCHOR, 4),    "Respawn Anchor x4",    "Nether-only weapon. Massive damage when charged."));
            l.add(new LoadoutSlot(Slot.HOTBAR3,  itemWithCount(Items.GLOWSTONE, 16),        "Glowstone x16",        "Charges respawn anchors."));
            l.add(new LoadoutSlot(Slot.HOTBAR4,  itemWithCount(Items.OAK_PLANKS, 32),       "Oak Planks x32",       "Construction blocks for cover."));
            l.add(new LoadoutSlot(Slot.HOTBAR5,  itemWithCount(Items.COBWEB, 8),            "Cobweb x8",            "Defensive trap, slows opponents."));
            LOADOUTS.put(Kit.CRYSTAL, l);
        }

        // SPEAR, iron polearm. Trident as a melee weapon (Sharpness V +
        // Sweeping Edge II, no Loyalty/Riptide). Iron armor + shield. The
        // throwing-trident loadout lives in TRIDENT now.
        {
            List<LoadoutSlot> l = new ArrayList<>();
            l.add(new LoadoutSlot(Slot.HEAD,     new ItemStack(Items.IRON_HELMET),          "Iron Helmet",        "Prot IV. Polearm reach trades for slightly heavier kit."));
            l.add(new LoadoutSlot(Slot.CHEST,    new ItemStack(Items.IRON_CHESTPLATE),      "Iron Chestplate",    "Prot IV. Soak the closing distance."));
            l.add(new LoadoutSlot(Slot.LEGS,     new ItemStack(Items.IRON_LEGGINGS),        "Iron Leggings",      "Prot IV."));
            l.add(new LoadoutSlot(Slot.FEET,     new ItemStack(Items.IRON_BOOTS),           "Iron Boots",         "Prot IV + Feather Falling IV."));
            l.add(new LoadoutSlot(Slot.MAINHAND, new ItemStack(Items.IRON_SPEAR),           "Iron Spear",         "Sharpness V. Polearm reach (4.5 blocks) + charge attacks."));
            l.add(new LoadoutSlot(Slot.OFFHAND,  new ItemStack(Items.SHIELD),               "Shield",             "Block + recover spacing."));
            l.add(new LoadoutSlot(Slot.HOTBAR1,  itemWithCount(Items.GOLDEN_APPLE, 8),      "Golden Apple x8",    "Sustain. Right-click to eat."));
            l.add(new LoadoutSlot(Slot.HOTBAR2,  new ItemStack(Items.IRON_SWORD),           "Iron Sword",         "Backup melee for tight spaces."));
            l.add(new LoadoutSlot(Slot.HOTBAR3,  itemWithCount(Items.OAK_PLANKS, 32),       "Oak Planks x32",     "Construction blocks, wall up the close-in."));
            l.add(new LoadoutSlot(Slot.HOTBAR4,  itemWithCount(Items.COBWEB, 8),            "Cobweb x8",          "Defensive trap, slows opponents."));
            LOADOUTS.put(Kit.SPEAR, l);
        }

        // TRIDENT, leather armor + throwing trident with Loyalty III + Riptide II.
        // The original "Spear" kit, now correctly named.
        {
            List<LoadoutSlot> l = new ArrayList<>();
            l.add(new LoadoutSlot(Slot.HEAD,     new ItemStack(Items.LEATHER_HELMET),       "Leather Helmet",     "Light armor for mobility."));
            l.add(new LoadoutSlot(Slot.CHEST,    new ItemStack(Items.LEATHER_CHESTPLATE),   "Leather Chestplate", "Light armor for mobility."));
            l.add(new LoadoutSlot(Slot.LEGS,     new ItemStack(Items.LEATHER_LEGGINGS),     "Leather Leggings",   "Light armor for mobility."));
            l.add(new LoadoutSlot(Slot.FEET,     new ItemStack(Items.LEATHER_BOOTS),        "Leather Boots",      "Light armor + Feather Falling IV."));
            l.add(new LoadoutSlot(Slot.MAINHAND, new ItemStack(Items.TRIDENT),              "Trident",            "Loyalty III + Riptide II. Throw and retrieve."));
            l.add(new LoadoutSlot(Slot.OFFHAND,  new ItemStack(Items.SHIELD),               "Shield",             "Block crits and projectile pressure."));
            l.add(new LoadoutSlot(Slot.HOTBAR1,  new ItemStack(Items.IRON_SWORD),           "Iron Sword",         "Backup melee for close encounters."));
            l.add(new LoadoutSlot(Slot.HOTBAR2,  itemWithCount(Items.GOLDEN_APPLE, 8),      "Golden Apple x8",    "Quick heals + absorption."));
            l.add(new LoadoutSlot(Slot.HOTBAR3,  itemWithCount(Items.OAK_PLANKS, 32),       "Oak Planks x32",     "Construction blocks, pillar, wall off, fortify."));
            l.add(new LoadoutSlot(Slot.HOTBAR4,  itemWithCount(Items.COBWEB, 8),            "Cobweb x8",          "Defensive trap, slows opponents."));
            LOADOUTS.put(Kit.TRIDENT, l);
        }

        // TNT, iron + blast prot, flint & steel, 64x TNT, plus the rail/cart
        // arsenal: powered rails + minecarts + TNT minecarts for chain detonations.
        {
            List<LoadoutSlot> l = new ArrayList<>();
            l.add(new LoadoutSlot(Slot.HEAD,     new ItemStack(Items.IRON_HELMET),          "Iron Helmet",        "Blast Prot IV, TNT blast soak."));
            l.add(new LoadoutSlot(Slot.CHEST,    new ItemStack(Items.IRON_CHESTPLATE),      "Iron Chestplate",    "Blast Prot IV. Survive your own kit."));
            l.add(new LoadoutSlot(Slot.LEGS,     new ItemStack(Items.IRON_LEGGINGS),        "Iron Leggings",      "Blast Prot IV."));
            l.add(new LoadoutSlot(Slot.FEET,     new ItemStack(Items.IRON_BOOTS),           "Iron Boots",         "Blast Prot IV + Feather Falling IV."));
            l.add(new LoadoutSlot(Slot.MAINHAND, new ItemStack(Items.FLINT_AND_STEEL),      "Flint and Steel",    "Primary trigger, light TNT + ignite traps."));
            l.add(new LoadoutSlot(Slot.OFFHAND,  itemWithCount(Items.GOLDEN_APPLE, 8),      "Golden Apple x8",    "Heal between detonations."));
            l.add(new LoadoutSlot(Slot.HOTBAR1,  itemWithCount(Items.TNT, 64),              "TNT x64",            "Stack of explosives. Place + retreat."));
            l.add(new LoadoutSlot(Slot.HOTBAR2,  itemWithCount(Items.TNT_MINECART, 4),      "TNT Minecart x4",    "Roll-up bombs. Pair with rails for delivery."));
            l.add(new LoadoutSlot(Slot.HOTBAR3,  itemWithCount(Items.POWERED_RAIL, 32),     "Powered Rail x32",   "Build the delivery route. Need redstone or block-side power."));
            l.add(new LoadoutSlot(Slot.HOTBAR4,  itemWithCount(Items.MINECART, 4),          "Minecart x4",        "Push or ride. Spam the lane."));
            l.add(new LoadoutSlot(Slot.HOTBAR5,  new ItemStack(Items.IRON_SWORD),           "Iron Sword",         "Backup melee for the close-quarters phase."));
            LOADOUTS.put(Kit.TNT, l);
        }

        // CROSSBOW, leather armor + Crossbow (Multishot, Quick Charge III, Piercing IV) +
        // arrows + iron sword backup + construction blocks.
        {
            List<LoadoutSlot> l = new ArrayList<>();
            l.add(new LoadoutSlot(Slot.HEAD,     new ItemStack(Items.LEATHER_HELMET),       "Leather Helmet",     "Light armor for mobility."));
            l.add(new LoadoutSlot(Slot.CHEST,    new ItemStack(Items.LEATHER_CHESTPLATE),   "Leather Chestplate", "Light armor for mobility."));
            l.add(new LoadoutSlot(Slot.LEGS,     new ItemStack(Items.LEATHER_LEGGINGS),     "Leather Leggings",   "Light armor for mobility."));
            l.add(new LoadoutSlot(Slot.FEET,     new ItemStack(Items.LEATHER_BOOTS),        "Leather Boots",      "Light armor + Feather Falling IV."));
            l.add(new LoadoutSlot(Slot.MAINHAND, new ItemStack(Items.CROSSBOW),             "Crossbow",           "Multishot + Quick Charge III + Piercing IV."));
            l.add(new LoadoutSlot(Slot.OFFHAND,  new ItemStack(Items.GOLDEN_APPLE),         "Golden Apple",       "Quick offhand heal."));
            l.add(new LoadoutSlot(Slot.HOTBAR1,  itemWithCount(Items.ARROW, 64),            "Arrow x64",          "Standard ammo."));
            l.add(new LoadoutSlot(Slot.HOTBAR2,  new ItemStack(Items.IRON_SWORD),           "Iron Sword",         "Backup melee."));
            l.add(new LoadoutSlot(Slot.HOTBAR3,  itemWithCount(Items.GOLDEN_APPLE, 8),      "Golden Apple x8",    "Sustain through bolt-and-bait combat."));
            l.add(new LoadoutSlot(Slot.HOTBAR4,  itemWithCount(Items.OAK_PLANKS, 32),       "Oak Planks x32",     "Construction blocks for cover + sightlines."));
            l.add(new LoadoutSlot(Slot.HOTBAR5,  itemWithCount(Items.COBWEB, 8),            "Cobweb x8",          "Slow opponents to land bolts."));
            LOADOUTS.put(Kit.CROSSBOW, l);
        }

        // MIXED, placeholder
        LOADOUTS.put(Kit.MIXED, List.of(
            new LoadoutSlot(Slot.MAINHAND, ItemStack.EMPTY, "Custom Kit", "Configure your own loadout. Available in custom duels only.")
        ));
    }

    /** Returns the loadout for the given kit. Never null; always at least one entry. */
    public static List<LoadoutSlot> get(Kit kit) {
        if (!initialized) initIfNeeded();
        List<LoadoutSlot> l = LOADOUTS.get(kit);
        return l == null ? Collections.emptyList() : l;
    }

    /** Find the slot entry for a given equipment slot, or null if not present. */
    public static LoadoutSlot find(Kit kit, Slot slot) {
        for (LoadoutSlot ls : get(kit)) {
            if (ls.slot == slot) return ls;
        }
        return null;
    }

    private static ItemStack itemWithCount(net.minecraft.world.item.Item item, int count) {
        ItemStack s = new ItemStack(item);
        s.setCount(count);
        return s;
    }
}